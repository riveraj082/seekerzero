package dev.seekerzero.app.ui.approvals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.seekerzero.app.api.MobileApiClient
import dev.seekerzero.app.api.models.Approval
import dev.seekerzero.app.util.ConnectionState
import dev.seekerzero.app.util.LogCollector
import dev.seekerzero.app.util.ServiceState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class ApprovalResolution { APPROVED, REJECTED }

class ApprovalsViewModel : ViewModel() {

    companion object {
        private const val TAG = "ApprovalsViewModel"
    }

    val approvals: StateFlow<List<Approval>> = ServiceState.pendingApprovals
    val connectionState: StateFlow<ConnectionState> = ServiceState.connectionState

    private val _inFlight = MutableStateFlow<Set<String>>(emptySet())
    val inFlight: StateFlow<Set<String>> = _inFlight

    fun approve(approval: Approval) = resolve(approval, ApprovalResolution.APPROVED)
    fun reject(approval: Approval) = resolve(approval, ApprovalResolution.REJECTED)

    private fun resolve(approval: Approval, resolution: ApprovalResolution) {
        if (_inFlight.value.contains(approval.id)) return
        _inFlight.value = _inFlight.value + approval.id
        viewModelScope.launch {
            val result = when (resolution) {
                ApprovalResolution.APPROVED -> MobileApiClient.approvalsApprove(approval.id)
                ApprovalResolution.REJECTED -> MobileApiClient.approvalsReject(approval.id)
            }
            result.onSuccess {
                LogCollector.d(TAG, "${resolution.name.lowercase()} ok for ${approval.id}")
                ServiceState.removeApproval(approval.id)
            }.onFailure {
                LogCollector.w(TAG, "${resolution.name.lowercase()} failed for ${approval.id}: ${it.message}")
            }
            _inFlight.value = _inFlight.value - approval.id
        }
    }
}
