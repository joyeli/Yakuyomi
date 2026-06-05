package eu.kanade.tachiyomi.data.translation.model

import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

/**
 * 翻譯佇列的一項（不可變快照）。[eu.kanade.tachiyomi.data.translation.TranslationManager]
 * 內部持有可變狀態、每次變動發一份這個快照給 UI 觀察。完成的章直接離開佇列，
 * 所以快照只會是 QUEUE / TRANSLATING / ERROR 三態。
 */
data class TranslationItem(
    val manga: Manga,
    val chapter: Chapter,
    val status: Status,
    val done: Int = 0,
    val total: Int = 0,
) {
    enum class Status {
        QUEUE, // 排隊中
        TRANSLATING, // 翻譯中
        ERROR, // 失敗（留佇列、可重試）
    }
}
