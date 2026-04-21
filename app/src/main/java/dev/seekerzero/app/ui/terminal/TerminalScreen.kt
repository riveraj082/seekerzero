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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.seekerzero.app.R
import dev.seekerzero.app.ssh.SshKeyManager
import dev.seekerzero.app.ui.components.CardSurface
import dev.seekerzero.app.ui.components.SeekerZeroScaffold
import dev.seekerzero.app.ui.theme.SeekerZeroColors

/**
 * Phase 6a: setup-only screen. Shows the device's SSH public key so the
 * user can paste it into a0prod's authorized_keys. SSH connection + pty
 * land in 6b/6c/6d — this is just the key-display surface, generated
 * on first access and stable thereafter.
 */
@Composable
fun TerminalScreen() {
    val context = LocalContext.current
    var publicKey by remember { mutableStateOf<String?>(null) }
    var keyError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try {
            publicKey = SshKeyManager.getPublicKeyOpenSsh()
        } catch (t: Throwable) {
            keyError = t.message ?: "unknown error"
        }
    }

    SeekerZeroScaffold(title = stringResource(R.string.tab_terminal)) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Top
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
            Spacer(Modifier.height(16.dp))

            when {
                keyError != null -> {
                    ErrorBlock(keyError!!)
                }
                publicKey == null -> {
                    Text(
                        text = stringResource(R.string.terminal_setup_generating),
                        color = SeekerZeroColors.TextSecondary,
                        fontSize = 13.sp
                    )
                }
                else -> {
                    val installCommand = remember(publicKey) {
                        buildInstallCommand(publicKey!!)
                    }
                    KeyBlock(publicKey!!)
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { copyToClipboard(context, publicKey!!, "SeekerZero SSH key") },
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
                    Spacer(Modifier.height(24.dp))
                    Text(
                        text = stringResource(R.string.terminal_setup_command_header),
                        color = SeekerZeroColors.TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(6.dp))
                    KeyBlock(installCommand)
                }
            }
        }
    }
}

@Composable
private fun KeyBlock(key: String) {
    CardSurface(modifier = Modifier.fillMaxWidth()) {
        Box(modifier = Modifier.padding(12.dp)) {
            Text(
                text = key,
                color = SeekerZeroColors.TextPrimary,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun ErrorBlock(msg: String) {
    CardSurface(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .background(SeekerZeroColors.Error)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text("error", color = SeekerZeroColors.OnPrimary, fontSize = 10.sp)
            }
            Spacer(Modifier.padding(end = 8.dp))
            Text(
                text = msg,
                color = SeekerZeroColors.TextPrimary,
                fontSize = 12.sp
            )
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

/**
 * Builds a one-shot bash command that appends the public key to the
 * user's authorized_keys and sets correct permissions. Escaping: the
 * key itself contains no single-quote characters (base64 + ASCII only),
 * so wrapping in single quotes is safe.
 */
private fun buildInstallCommand(publicKey: String): String {
    return "mkdir -p ~/.ssh && chmod 700 ~/.ssh && " +
        "echo '$publicKey' >> ~/.ssh/authorized_keys && " +
        "chmod 600 ~/.ssh/authorized_keys"
}
