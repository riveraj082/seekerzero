package dev.seekerzero.app.ui.setup

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.seekerzero.app.R
import dev.seekerzero.app.qr.QrScannerActivity
import dev.seekerzero.app.ui.components.CardSurface
import dev.seekerzero.app.ui.components.ConfigField
import dev.seekerzero.app.ui.components.SectionLabel
import dev.seekerzero.app.ui.components.SeekerZeroScaffold
import dev.seekerzero.app.ui.theme.SeekerZeroColors

@Composable
fun SetupScreen(
    onSetupComplete: () -> Unit,
    viewModel: SetupViewModel = viewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val qrLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val raw = result.data?.getStringExtra(QrScannerActivity.EXTRA_RAW_RESULT)
        if (result.resultCode == android.app.Activity.RESULT_OK && !raw.isNullOrBlank()) {
            viewModel.onScanResult(raw)
        } else {
            viewModel.onScanCancelled()
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        viewModel.onNotificationPermissionResolved()
    }

    SeekerZeroScaffold(title = stringResource(R.string.setup_title)) { pad ->
        Box(
            modifier = Modifier.fillMaxSize().padding(pad),
            contentAlignment = Alignment.Center
        ) {
            when (val s = state) {
                is SetupUiState.Welcome -> WelcomeBody(
                    onScan = {
                        qrLauncher.launch(Intent(context, QrScannerActivity::class.java))
                    },
                    onManual = viewModel::onManualEntrySelected,
                    onDemo = {
                        dev.seekerzero.app.config.ConfigManager.applyDemoDefaults()
                        // Fire and forget: downloads the demo avatar so the
                        // chat + status screens render a professional image
                        // right away. Letter fallback if the network fails.
                        kotlinx.coroutines.CoroutineScope(
                            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
                        ).launch {
                            dev.seekerzero.app.demo.DemoData.provisionDemoAssets(context)
                        }
                        onSetupComplete()
                    }
                )

                is SetupUiState.ManualEntry -> ManualEntryBody(
                    onSubmit = viewModel::onManualEntry,
                    onBack = viewModel::onStartOver
                )

                is SetupUiState.Verifying -> VerifyingBody()

                is SetupUiState.HealthFailed -> HealthFailedBody(
                    message = s.message,
                    onRetry = viewModel::onRetryHealth,
                    onStartOver = viewModel::onStartOver
                )

                is SetupUiState.NotificationPermission -> NotificationPermissionBody(
                    onAllow = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            viewModel.onNotificationPermissionResolved()
                        }
                    },
                    onSkip = viewModel::onNotificationPermissionResolved
                )

                is SetupUiState.BatteryOptimization -> BatteryOptimizationBody(
                    onOpenSettings = {
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            .setData(Uri.parse("package:${context.packageName}"))
                        runCatching { context.startActivity(intent) }
                        viewModel.onBatteryOptimizationResolved()
                    },
                    onSkip = viewModel::onBatteryOptimizationResolved
                )

                is SetupUiState.Done -> {
                    DoneBody()
                    LaunchedEffect(Unit) {
                        kotlinx.coroutines.delay(600)
                        viewModel.onDismissDone()
                        onSetupComplete()
                    }
                }
            }
        }
    }
}

@Composable
private fun WelcomeBody(onScan: () -> Unit, onManual: () -> Unit, onDemo: () -> Unit) {
    CardSurface(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Column(modifier = Modifier.padding(20.dp)) {
            SectionLabel(title = stringResource(R.string.setup_welcome_header))
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.setup_welcome_body),
                color = SeekerZeroColors.TextPrimary
            )
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = onScan,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SeekerZeroColors.Primary,
                    contentColor = SeekerZeroColors.OnPrimary
                )
            ) { Text(stringResource(R.string.setup_scan_cta)) }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onManual, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.setup_manual_cta), color = SeekerZeroColors.Accent)
            }
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onDemo, modifier = Modifier.fillMaxWidth()) {
                Text("Start demo mode (no server needed)", color = SeekerZeroColors.TextDisabled)
            }
        }
    }
}

@Composable
private fun ManualEntryBody(
    onSubmit: (String, String?) -> Unit,
    onBack: () -> Unit
) {
    var host by remember { mutableStateOf("") }
    var clientId by remember { mutableStateOf("") }
    CardSurface(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Column(modifier = Modifier.padding(20.dp)) {
            SectionLabel(title = stringResource(R.string.setup_manual_header))
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.setup_manual_body),
                color = SeekerZeroColors.TextSecondary
            )
            Spacer(Modifier.height(8.dp))
            ConfigField(
                label = stringResource(R.string.setup_manual_field_host),
                value = host,
                onChange = { host = it }
            )
            ConfigField(
                label = stringResource(R.string.setup_manual_field_client),
                value = clientId,
                onChange = { clientId = it }
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { onSubmit(host, clientId) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SeekerZeroColors.Primary,
                    contentColor = SeekerZeroColors.OnPrimary
                )
            ) { Text(stringResource(R.string.setup_manual_submit)) }
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.setup_manual_back), color = SeekerZeroColors.TextSecondary)
            }
        }
    }
}

@Composable
private fun VerifyingBody() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CircularProgressIndicator(color = SeekerZeroColors.Primary)
        Text(stringResource(R.string.setup_verifying_body), color = SeekerZeroColors.TextSecondary)
    }
}

@Composable
private fun HealthFailedBody(
    message: String,
    onRetry: () -> Unit,
    onStartOver: () -> Unit
) {
    CardSurface(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Column(modifier = Modifier.padding(20.dp)) {
            SectionLabel(title = stringResource(R.string.setup_health_failed_header))
            Spacer(Modifier.height(8.dp))
            Text(message, color = SeekerZeroColors.Error)
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onRetry,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SeekerZeroColors.Primary,
                    contentColor = SeekerZeroColors.OnPrimary
                )
            ) { Text(stringResource(R.string.setup_health_failed_retry)) }
            Spacer(Modifier.height(4.dp))
            OutlinedButton(onClick = onStartOver, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.setup_health_failed_start_over), color = SeekerZeroColors.TextSecondary)
            }
        }
    }
}

@Composable
private fun NotificationPermissionBody(onAllow: () -> Unit, onSkip: () -> Unit) {
    CardSurface(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Column(modifier = Modifier.padding(20.dp)) {
            SectionLabel(title = stringResource(R.string.setup_notification_header))
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.setup_notification_body),
                color = SeekerZeroColors.TextPrimary
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onAllow,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SeekerZeroColors.Primary,
                    contentColor = SeekerZeroColors.OnPrimary
                )
            ) { Text(stringResource(R.string.setup_notification_allow)) }
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.setup_notification_skip), color = SeekerZeroColors.TextSecondary)
            }
        }
    }
}

@Composable
private fun BatteryOptimizationBody(onOpenSettings: () -> Unit, onSkip: () -> Unit) {
    AlertDialog(
        onDismissRequest = onSkip,
        containerColor = SeekerZeroColors.Surface,
        title = {
            Text(
                stringResource(R.string.setup_battery_header),
                color = SeekerZeroColors.TextPrimary,
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Text(
                stringResource(R.string.setup_battery_body),
                color = SeekerZeroColors.TextSecondary
            )
        },
        confirmButton = {
            TextButton(onClick = onOpenSettings) {
                Text(stringResource(R.string.setup_battery_open_settings), color = SeekerZeroColors.Primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onSkip) {
                Text(stringResource(R.string.setup_battery_skip), color = SeekerZeroColors.TextSecondary)
            }
        }
    )
}

@Composable
private fun DoneBody() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            stringResource(R.string.setup_done_primary),
            color = SeekerZeroColors.Success,
            fontWeight = FontWeight.SemiBold
        )
        Text(stringResource(R.string.setup_done_secondary), color = SeekerZeroColors.TextSecondary)
    }
}
