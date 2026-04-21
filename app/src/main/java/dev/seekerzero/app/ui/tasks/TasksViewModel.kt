package dev.seekerzero.app.ui.tasks

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.seekerzero.app.api.MobileApiClient
import dev.seekerzero.app.api.models.TaskCreateRequest
import dev.seekerzero.app.api.models.TaskDto
import dev.seekerzero.app.api.models.TaskSchedule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class TasksViewModel(app: Application) : AndroidViewModel(app) {

    private val _tasks = MutableStateFlow<List<TaskDto>>(emptyList())
    val tasks: StateFlow<List<TaskDto>> = _tasks.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _busy = MutableStateFlow<Set<String>>(emptySet())
    val busy: StateFlow<Set<String>> = _busy.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _loading.value = true
            MobileApiClient.tasksScheduled()
                .onSuccess {
                    _tasks.value = it.tasks
                    _error.value = null
                }
                .onFailure { _error.value = it.message ?: it.javaClass.simpleName }
            _loading.value = false
        }
    }

    fun runNow(uuid: String) {
        if (_busy.value.contains(uuid)) return
        _busy.value = _busy.value + uuid
        viewModelScope.launch {
            MobileApiClient.taskRun(uuid)
            _busy.value = _busy.value - uuid
            // Refresh so state/last_run update if the task flipped to running.
            refresh()
        }
    }

    fun delete(uuid: String) {
        if (_busy.value.contains(uuid)) return
        _busy.value = _busy.value + uuid
        viewModelScope.launch {
            MobileApiClient.taskDelete(uuid)
                .onSuccess {
                    // Optimistic local removal so the list updates immediately.
                    _tasks.value = _tasks.value.filterNot { it.uuid == uuid }
                }
            _busy.value = _busy.value - uuid
            refresh()
        }
    }

    fun toggle(task: TaskDto) {
        if (_busy.value.contains(task.uuid)) return
        _busy.value = _busy.value + task.uuid
        viewModelScope.launch {
            val result = if (task.state == "disabled") {
                MobileApiClient.taskEnable(task.uuid)
            } else {
                MobileApiClient.taskDisable(task.uuid)
            }
            _busy.value = _busy.value - task.uuid
            refresh()
        }
    }

    fun create(
        name: String,
        prompt: String,
        schedule: TaskSchedule,
        onDone: (Boolean, String?) -> Unit
    ) {
        viewModelScope.launch {
            MobileApiClient.taskCreate(
                TaskCreateRequest(name = name, prompt = prompt, schedule = schedule)
            )
                .onSuccess {
                    refresh()
                    onDone(true, null)
                }
                .onFailure { onDone(false, it.message ?: it.javaClass.simpleName) }
        }
    }
}
