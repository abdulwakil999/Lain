package com.lain.assistant.ui.knows

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lain.assistant.AppContainer
import com.lain.assistant.data.ScheduledTask
import com.lain.assistant.data.db.ActionLogEntity
import com.lain.assistant.data.db.MemoryEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class KnowsState(
    val tasks: List<ScheduledTask> = emptyList(),
    val memories: List<MemoryEntity> = emptyList(),
    val actions: List<ActionLogEntity> = emptyList()
)

class KnowsViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(KnowsState())
    val state: StateFlow<KnowsState> = _state.asStateFlow()

    /**
     * Read on demand rather than observed.
     *
     * The screen is opened deliberately and briefly; a live subscription on two
     * stores would run for the whole session to keep a page nobody is looking at up
     * to date. Every mutation below refreshes, so what is on screen stays true.
     */
    fun refresh() {
        viewModelScope.launch {
            _state.update {
                it.copy(
                    tasks = container.scheduler.all(),
                    memories = container.memory.all(),
                    actions = container.actionLog.recent()
                )
            }
        }
    }

    fun cancelTask(id: String) {
        viewModelScope.launch {
            container.scheduler.cancel(id)
            refresh()
        }
    }

    fun forget(id: String) {
        viewModelScope.launch {
            container.memory.deleteById(id)
            refresh()
        }
    }

    fun forgetAll() {
        viewModelScope.launch {
            container.memory.clear()
            refresh()
        }
    }

    /**
     * Clears the whole trail, and only the whole trail.
     *
     * There is no delete-one here on purpose. A record of what an assistant did is
     * worth something because it is complete; one you can quietly edit a line out of
     * is no longer evidence of anything. Wiping it entirely is an honest choice a
     * user is entitled to make — removing the single entry they would rather not
     * have seen is not the same act.
     */
    fun clearActions() {
        viewModelScope.launch {
            container.actionLog.clear()
            refresh()
        }
    }
}
