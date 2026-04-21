package dev.seekerzero.app.ui.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.seekerzero.app.R
import dev.seekerzero.app.ui.components.CardSurface
import dev.seekerzero.app.ui.components.SeekerZeroScaffold
import dev.seekerzero.app.ui.theme.SeekerZeroColors

@Composable
fun TerminalScreen(viewModel: TerminalViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scrollback by viewModel.scrollback.collectAsStateWithLifecycle()
    val publicKey by viewModel.publicKey.collectAsStateWithLifecycle()

    SeekerZeroScaffold(title = stringResource(R.string.tab_terminal)) { pad ->
        Column(modifier = Modifier.fillMaxSize().padding(pad)) {
            when (state) {
                is TerminalState.Connected -> ConnectedView(
                    scrollback = scrollback,
                    onSend = { viewModel.sendLine(it) },
                    onClear = { viewModel.clearScrollback() },
                    onDisconnect = { viewModel.disconnect() }
                )
                is TerminalState.Connecting -> CenteredText("Connecting to a0prod\u2026")
                is TerminalState.AuthFailed -> SetupView(
                    publicKey = publicKey,
                    hint = "Auth failed: ${(state as TerminalState.AuthFailed).message}. Install the key on a0prod and retry.",
                    onRetry = { viewModel.tryConnect() }
                )
                is TerminalState.NetworkError -> SetupView(
                    publicKey = publicKey,
                    hint = "Network error: ${(state as TerminalState.NetworkError).message}",
                    onRetry = { viewModel.tryConnect() }
                )
                TerminalState.Idle -> SetupView(
                    publicKey = publicKey,
                    hint = null,
                    onRetry = { viewModel.tryConnect() }
                )
            }
        }
    }
}

@Composable
private fun CenteredText(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = SeekerZeroColors.TextSecondary, fontSize = 13.sp)
    }
}

@Composable
private fun SetupView(
    publicKey: String?,
    hint: String?,
    onRetry: () -> Unit
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = stringResource(R.string.terminal_setup_header),
            color = SeekerZeroColors.TextPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.terminal_setup_body),
            color = SeekerZeroColors.TextSecondary,
            fontSize = 13.sp
        )
        if (hint != null) {
            Spacer(Modifier.height(12.dp))
            CardSurface(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = hint,
                    color = SeekerZeroColors.Warning,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
        Spacer(Modifier.height(16.dp))

        if (publicKey == null) {
            Text(
                text = stringResource(R.string.terminal_setup_generating),
                color = SeekerZeroColors.TextSecondary,
                fontSize = 13.sp
            )
            return@Column
        }

        val installCommand = remember(publicKey) { buildInstallCommand(publicKey) }
        CardSurface(modifier = Modifier.fillMaxWidth()) {
            Box(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = publicKey,
                    color = SeekerZeroColors.TextPrimary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { copyToClipboard(context, publicKey, "SeekerZero SSH key") },
                colors = ButtonDefaults.buttonColors(
                    containerColor = SeekerZeroColors.Primary,
                    contentColor = SeekerZeroColors.OnPrimary
                ),
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.terminal_setup_copy_key), fontSize = 12.sp)
            }
            Button(
                onClick = { copyToClipboard(context, installCommand, "SeekerZero SSH install") },
                colors = ButtonDefaults.buttonColors(
                    containerColor = SeekerZeroColors.Primary,
                    contentColor = SeekerZeroColors.OnPrimary
                ),
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.terminal_setup_copy_command), fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.terminal_setup_command_header),
            color = SeekerZeroColors.TextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(6.dp))
        CardSurface(modifier = Modifier.fillMaxWidth()) {
            Box(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = installCommand,
                    color = SeekerZeroColors.TextPrimary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onRetry,
            colors = ButtonDefaults.buttonColors(
                containerColor = SeekerZeroColors.SurfaceVariant,
                contentColor = SeekerZeroColors.TextPrimary
            )
        ) {
            Text("Retry connection")
        }
    }
}

@Composable
private fun ConnectedView(
    scrollback: String,
    onSend: (String) -> Unit,
    onClear: () -> Unit,
    onDisconnect: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Connected to a0user@a0prod",
                color = SeekerZeroColors.Success,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onClear) {
                Text("Clear", color = SeekerZeroColors.TextSecondary, fontSize = 11.sp)
            }
            TextButton(onClick = onDisconnect) {
                Text("Disconnect", color = SeekerZeroColors.Error, fontSize = 11.sp)
            }
        }
        val scrollState = rememberScrollState()
        // Auto-scroll to the bottom whenever scrollback grows.
        LaunchedEffect(scrollback.length) {
            if (scrollState.value < scrollState.maxValue) {
                scrollState.animateScrollTo(scrollState.maxValue)
            }
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .verticalScroll(scrollState)
        ) {
            Text(
                text = if (scrollback.isEmpty()) "(shell ready — type a command)" else scrollback,
                color = if (scrollback.isEmpty()) SeekerZeroColors.TextDisabled else SeekerZeroColors.TextPrimary,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
        }
        CommandInput(onSubmit = onSend)
    }
}

@Composable
private fun CommandInput(onSubmit: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    CardSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 10.dp)) {
                if (text.isEmpty()) {
                    Text(
                        text = "command",
                        color = SeekerZeroColors.TextDisabled,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 20.dp, max = 120.dp),
                    textStyle = TextStyle(
                        color = SeekerZeroColors.TextPrimary,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace
                    ),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(SeekerZeroColors.Primary)
                )
            }
            IconButton(
                onClick = {
                    val toSend = text.trim()
                    if (toSend.isNotEmpty()) {
                        onSubmit(toSend)
                        text = ""
                    }
                },
                enabled = text.isNotBlank()
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.Send,
                    contentDescription = "Run",
                    tint = if (text.isNotBlank()) SeekerZeroColors.Primary else SeekerZeroColors.TextDisabled
                )
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String, label: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    if (clipboard != null) {
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
    }
}

private fun buildInstallCommand(publicKey: String): String {
    return "mkdir -p ~/.ssh && chmod 700 ~/.ssh && " +
        "echo '$publicKey' >> ~/.ssh/authorized_keys && " +
        "chmod 600 ~/.ssh/authorized_keys"
}
