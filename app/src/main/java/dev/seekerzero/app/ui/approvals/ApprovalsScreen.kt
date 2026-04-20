package dev.seekerzero.app.ui.approvals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.seekerzero.app.R
import dev.seekerzero.app.ui.components.SeekerZeroScaffold
import dev.seekerzero.app.ui.components.StatusDot
import dev.seekerzero.app.ui.theme.SeekerZeroColors
import dev.seekerzero.app.util.ConnectionState

@Composable
fun ApprovalsScreen(
    viewModel: ApprovalsViewModel = viewModel()
) {
    val approvals by viewModel.approvals.collectAsStateWithLifecycle()
    val connection by viewModel.connectionState.collectAsStateWithLifecycle()
    val inFlight by viewModel.inFlight.collectAsStateWithLifecycle()

    SeekerZeroScaffold(title = stringResource(R.string.tab_approvals)) { pad ->
        Column(modifier = Modifier.fillMaxSize().padding(pad)) {
            ConnectionRow(connection, approvals.size)
            if (approvals.isEmpty()) {
                EmptyState()
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(approvals, key = { it.id }) { approval ->
                        ApprovalCard(
                            approval = approval,
                            busy = inFlight.contains(approval.id),
                            onApprove = { viewModel.approve(approval) },
                            onReject = { viewModel.reject(approval) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionRow(state: ConnectionState, pendingCount: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StatusDot(
            color = connectionColor(state),
            size = 10.dp,
            pulsing = state == ConnectionState.RECONNECTING
        )
        Text(
            text = connectionLabel(state),
            color = SeekerZeroColors.TextSecondary,
            fontSize = 13.sp
        )
        Text(
            text = "·",
            color = SeekerZeroColors.TextDisabled,
            fontSize = 13.sp
        )
        Text(
            text = if (pendingCount == 1) "1 pending" else "$pendingCount pending",
            color = SeekerZeroColors.TextSecondary,
            fontSize = 13.sp
        )
    }
}

@Composable
private fun EmptyState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "You're all caught up",
                color = SeekerZeroColors.TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Agent Zero has no pending approval gates.",
                color = SeekerZeroColors.TextSecondary,
                fontSize = 13.sp
            )
        }
    }
}

private fun connectionColor(state: ConnectionState) = when (state) {
    ConnectionState.CONNECTED -> SeekerZeroColors.Success
    ConnectionState.RECONNECTING -> SeekerZeroColors.Warning
    ConnectionState.PAUSED_NO_NETWORK -> SeekerZeroColors.Warning
    ConnectionState.OFFLINE -> SeekerZeroColors.Error
    ConnectionState.DISCONNECTED -> SeekerZeroColors.TextDisabled
}

private fun connectionLabel(state: ConnectionState) = when (state) {
    ConnectionState.CONNECTED -> "Connected"
    ConnectionState.RECONNECTING -> "Reconnecting"
    ConnectionState.PAUSED_NO_NETWORK -> "Offline — waiting for network"
    ConnectionState.OFFLINE -> "Offline"
    ConnectionState.DISCONNECTED -> "Disconnected"
}
