package dev.seekerzero.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.seekerzero.app.ui.theme.SeekerZeroColors

/**
 * Tappable label row with an optional status/value on the right and a
 * trailing chevron. Used for "tap to open something" entries inside a
 * [CardSurface] — settings shortcuts, deep links into system UI, etc.
 */
@Composable
fun NavRow(
    label: String,
    value: String? = null,
    onClick: () -> Unit,
    isLast: Boolean = false,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, color = SeekerZeroColors.TextPrimary)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (value != null) {
                    Text(value, color = SeekerZeroColors.TextSecondary)
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = null,
                    tint = SeekerZeroColors.TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        if (!isLast) {
            HorizontalDivider(color = SeekerZeroColors.Divider, thickness = 1.dp)
        }
    }
}
