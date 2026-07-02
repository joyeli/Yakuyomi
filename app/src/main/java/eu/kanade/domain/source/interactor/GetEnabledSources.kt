package eu.kanade.domain.source.interactor

import eu.kanade.domain.source.service.SourcePreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import tachiyomi.domain.source.model.Pin
import tachiyomi.domain.source.model.Pins
import tachiyomi.domain.source.model.Source
import tachiyomi.domain.source.repository.SourceRepository
import tachiyomi.source.local.isLocal

class GetEnabledSources(
    private val repository: SourceRepository,
    private val preferences: SourcePreferences,
) {

    fun subscribe(): Flow<List<Source>> {
        // Yakuyomi：把「最後使用來源 + 顯示最近使用開關 + 顯示本地來源開關」先併成一個 flow，維持外層 5 參數具名 combine。
        val lastUsedAndToggles = combine(
            preferences.lastUsedSource.changes(),
            preferences.showRecentlyUsedSource.changes(),
            preferences.showLocalSource.changes(),
        ) { lastUsed, showRecentlyUsed, showLocal -> Triple(lastUsed, showRecentlyUsed, showLocal) }

        return combine(
            preferences.pinnedSources.changes(),
            preferences.enabledLanguages.changes(),
            preferences.disabledSources.changes(),
            lastUsedAndToggles,
            repository.getSources(),
        ) {
                pinnedSourceIds,
                enabledLanguages,
                disabledSources,
                (lastUsedSource, showRecentlyUsed, showLocal),
                sources,
            ->
            sources
                // 本地來源：開關關就不列（原本 isLocal() 一律保留）。
                .filter { (it.lang in enabledLanguages || it.isLocal()) && (showLocal || !it.isLocal()) }
                .filterNot { it.id.toString() in disabledSources }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                .flatMap {
                    val flag = if ("${it.id}" in pinnedSourceIds) Pins.pinned else Pins.unpinned
                    val source = it.copy(pin = flag)
                    val toFlatten = mutableListOf(source)
                    // 最近使用：開關關就不另產生置頂的那份 copy → 來源清單沒有「最近使用」欄。
                    if (showRecentlyUsed && source.id == lastUsedSource) {
                        toFlatten.add(source.copy(isUsedLast = true, pin = source.pin - Pin.Actual))
                    }
                    toFlatten
                }
        }
            .distinctUntilChanged()
    }
}
