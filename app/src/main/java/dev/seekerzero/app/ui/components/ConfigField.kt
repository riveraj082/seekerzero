package dev.seekerzero.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.seekerzero.app.ui.theme.SeekerZeroColors
import dev.seekerzero.app.ui.theme.SeekerZeroShapes
import dev.seekerzero.app.ui.theme.SeekerZeroTheme

@Composable
fun ConfigField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    trailingHelp: (() -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(label, color = SeekerZeroColors.TextSecondary) },
            singleLine = true,
            modifier = Modifier.weight(1f),
            shape = SeekerZeroShapes.Field,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = SeekerZeroColors.TextPrimary,
                unfocusedTextColor = SeekerZeroColors.TextPrimary,
                focusedBorderColor = SeekerZeroColors.Primary,
                unfocusedBorderColor = SeekerZeroColors.CardBorder,
                cursorColor = SeekerZeroColors.Primary,
                focusedContainerColor = SeekerZeroColors.Surface,
                unfocusedContainerColor = SeekerZeroColors.Surface
            )
        )
        if (trailingHelp != null) {
            IconButton(onClick = trailingHelp) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = "Help",
                    tint = SeekerZeroColors.TextSecondary
                )
            }
        }
    }
}

@Preview
@Composable
private fun ConfigFieldPreview() {
    SeekerZeroTheme {
        ConfigField(
            label = "Tailnet host",
            value = "a0.example.ts.net",
            onChange = {},
            trailingHelp = {}
        )
    }
}
