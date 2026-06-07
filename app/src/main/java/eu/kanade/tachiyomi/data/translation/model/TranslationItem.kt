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
    /**
     * 此項生效的去字方法原始字串（boxfill / auto_whole / auto_tile）。
     * 翻譯項＝排入當下擷取的全域偏好（可在排隊時被改）；重繪項＝重繪所選方法。
     * UI 用來顯示每章去字法、QUEUE 項另可改（見 [eu.kanade.tachiyomi.data.translation.TranslationManager.setItemMethod]）。
     */
    val method: String = "",
) {
    enum class Status {
        QUEUE, // 排隊中
        TRANSLATING, // 翻譯中
        ERROR, // 失敗（留佇列、可重試）
    }
}
