package eu.kanade.tachiyomi.ui.browse.source

import androidx.compose.runtime.Immutable
import androidx.lifecycle.viewModelScope
import eu.kanade.domain.source.interactor.GetEnabledSources
import eu.kanade.domain.source.interactor.ToggleSource
import eu.kanade.domain.source.interactor.ToggleSourcePin
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.presentation.browse.SourceUiModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import logcat.LogPriority
import mihon.core.viewmodel.StateViewModel
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.source.model.Pin
import tachiyomi.domain.source.model.Source
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.TreeMap

class SourcesViewModel(
    private val getEnabledSources: GetEnabledSources = Injekt.get(),
    private val toggleSource: ToggleSource = Injekt.get(),
    private val toggleSourcePin: ToggleSourcePin = Injekt.get(),
    private val sourcePreferences: SourcePreferences = Injekt.get(),
) : StateViewModel<SourcesViewModel.State>(State()) {

    private val _events = Channel<Event>(Int.MAX_VALUE)
    val events = _events.receiveAsFlow()

    init {
        viewModelScope.launchIO {
            getEnabledSources.subscribe()
                .catch {
                    logcat(LogPriority.ERROR, it)
                    _events.send(Event.FailedFetchingSources)
                }
                .collectLatest(::collectLatestSources)
        }
    }

    private fun collectLatestSources(sources: List<Source>) {
        mutableState.update { state ->
            val map = TreeMap<String, MutableList<Source>> { d1, d2 ->
                // Sources without a lang defined will be placed at the end
                when {
                    d1 == LAST_USED_KEY && d2 != LAST_USED_KEY -> -1
                    d2 == LAST_USED_KEY && d1 != LAST_USED_KEY -> 1
                    d1 == PINNED_KEY && d2 != PINNED_KEY -> -1
                    d2 == PINNED_KEY && d1 != PINNED_KEY -> 1
                    d1 == "" && d2 != "" -> 1
                    d2 == "" && d1 != "" -> -1
                    else -> d1.compareTo(d2)
                }
            }
            val byLang = sources.groupByTo(map) {
                when {
                    it.isUsedLast -> LAST_USED_KEY
                    Pin.Actual in it.pin -> PINNED_KEY
                    else -> it.lang
                }
            }

            state.copy(
                isLoading = false,
                items = byLang
                    .flatMap {
                        listOf(
                            SourceUiModel.Header(it.key),
                            *it.value.map { source ->
                                SourceUiModel.Item(source)
                            }.toTypedArray(),
                        )
                    },
                snapshotSourceIds = sources.filter { hasSnapshot(it.id) }.map { it.id }.toSet(),
            )
        }
    }

    private fun hasSnapshot(sourceId: Long): Boolean =
        sourcePreferences.browseSnapshot(sourceId).get().isNotEmpty()

    /** Yakuyomi：重新計算「哪些來源有快照」（從各來源 browse 頁存了快照後回到此頁時刷新）。 */
    fun refreshSnapshots() {
        mutableState.update { state ->
            val ids = state.items
                .filterIsInstance<SourceUiModel.Item>()
                .map { it.source.id }
                .filter { hasSnapshot(it) }
                .toSet()
            state.copy(snapshotSourceIds = ids)
        }
    }

    fun showSnapshotClearDialog(source: Source) {
        mutableState.update { it.copy(snapshotClearTarget = source) }
    }

    fun dismissSnapshotClearDialog() {
        mutableState.update { it.copy(snapshotClearTarget = null) }
    }

    fun clearSnapshot(source: Source) {
        sourcePreferences.browseSnapshot(source.id).set("")
        mutableState.update {
            it.copy(
                snapshotSourceIds = it.snapshotSourceIds - source.id,
                snapshotClearTarget = null,
            )
        }
    }

    fun toggleSource(source: Source) {
        toggleSource.await(source)
    }

    fun togglePin(source: Source) {
        toggleSourcePin.await(source)
    }

    fun showSourceDialog(source: Source) {
        mutableState.update { it.copy(dialog = Dialog(source)) }
    }

    fun closeDialog() {
        mutableState.update { it.copy(dialog = null) }
    }

    sealed interface Event {
        data object FailedFetchingSources : Event
    }

    data class Dialog(val source: Source)

    @Immutable
    data class State(
        val dialog: Dialog? = null,
        val isLoading: Boolean = true,
        val items: List<SourceUiModel> = listOf(),
        // Yakuyomi：有快照的來源 id 集合（決定來源列是否顯示「快照」按鈕）。
        val snapshotSourceIds: Set<Long> = emptySet(),
        // Yakuyomi：長按快照按鈕後待確認清除的來源。
        val snapshotClearTarget: Source? = null,
    ) {
        val isEmpty = items.isEmpty()
    }

    companion object {
        const val PINNED_KEY = "pinned"
        const val LAST_USED_KEY = "last_used"
    }
}
