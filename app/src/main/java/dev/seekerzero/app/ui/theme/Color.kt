package dev.seekerzero.app.ui.theme

import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object SeekerZeroColors {
    val Background = Color(0xFF1B120C)
    val Surface = Color(0xFF1F1812)
    val SurfaceVariant = Color(0xFF281E14)
    val CardBorder = Color(0xFF3A2E1E)
    val Divider = Color(0xFF2F241A)

    val Primary = Color(0xFFFB8C00)
    val OnPrimary = Color(0xFF000000)

    val Accent = Color(0xFFFFD54F)
    val Warning = Color(0xFFFFB74D)
    val Error = Color(0xFFFF5252)
    val Success = Color(0xFF69F0AE)

    val TextPrimary = Color(0xFFE6E8EB)
    val TextSecondary = Color(0xFF8A929C)
    val TextDisabled = Color(0xFF565C65)
}

@Composable
fun seekerZeroSwitchColors(): SwitchColors = SwitchDefaults.colors(
    checkedThumbColor = SeekerZeroColors.OnPrimary,
    checkedTrackColor = SeekerZeroColors.Primary,
    checkedBorderColor = SeekerZeroColors.Primary,
    uncheckedThumbColor = SeekerZeroColors.TextSecondary,
    uncheckedTrackColor = SeekerZeroColors.Surface,
    uncheckedBorderColor = SeekerZeroColors.CardBorder,
    disabledCheckedThumbColor = SeekerZeroColors.TextDisabled,
    disabledCheckedTrackColor = SeekerZeroColors.SurfaceVariant,
    disabledUncheckedThumbColor = SeekerZeroColors.TextDisabled,
    disabledUncheckedTrackColor = SeekerZeroColors.SurfaceVariant
)