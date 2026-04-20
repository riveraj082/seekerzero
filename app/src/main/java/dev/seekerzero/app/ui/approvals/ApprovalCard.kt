package dev.seekerzero.app.ui.approvals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.seekerzero.app.api.models.Approval
import dev.seekerzero.app.ui.components.CardSurface
import dev.seekerzero.app.ui.components.StatusDot
import dev.seekerzero.app.ui.theme.SeekerZeroColors

@Composable
fun ApprovalCard(
    approval: Approval,
    busy: Boolean,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier
) {
    CardSurface(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatusDot(color = riskColor(approval.risk), size = 8.dp)
                Text(
                    text = approval.risk.uppercase(),
                    color = riskColor(approval.risk),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "·",
                    color = SeekerZeroColors.TextDisabled,
                    fontSize = 11.sp
                )
                Text(
                    text = approval.category.uppercase(),
                    color = SeekerZeroColors.TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "·",
                    color = SeekerZeroColors.TextDisabled,
                    fontSize = 11.sp
                )
                Text(
                    text = approval.source,
                    color = SeekerZeroColors.TextDisabled,
                    fontSize = 11.sp
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = approval.summary,
                color = SeekerZeroColors.TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = approval.detail,
                color = SeekerZeroColors.TextSecondary,
                fontSize = 13.sp
            )
            Spacer(Modifier.height(14.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onApprove,
                    enabled = !busy,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SeekerZeroColors.Primary,
                        contentColor = SeekerZeroColors.OnPrimary
                    )
                ) {
                    Text("Approve")
                }
                OutlinedButton(
                    onClick = onReject,
                    enabled = !busy,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = SeekerZeroColors.TextPrimary
                    )
                ) {
                    Text("Reject")
                }
                if (busy) {
                    Spacer(Modifier.height(1.dp))
                    CircularProgressIndicator(
                        modifier = Modifier.height(18.dp),
                        strokeWidth = 2.dp,
                        color = SeekerZeroColors.Primary
                    )
                }
            }
        }
    }
}

private fun riskColor(risk: String): Color = when (risk.lowercase()) {
    "high" -> SeekerZeroColors.Error
    "medium" -> SeekerZeroColors.Warning
    "low" -> SeekerZeroColors.Success
    else -> SeekerZeroColors.TextSecondary
}
