package eu.kanade.tachiyomi.ui.library

import eu.kanade.tachiyomi.source.getNameForMangaInfo
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.source.local.LocalSource

private const val LOCAL_SOURCE_ID_ALIAS = "local"

data class LibraryItem(
    val libraryManga: LibraryManga,
    val downloadCount: Int,
    val unreadCount: Long,
    val isLocal: Boolean,
    val badges: Badges,
) {
    val id: Long = libraryManga.id

    /**
     * Checks if a query matches the manga
     *
     * @param constraint the query to check.
     * @return true if the manga matches the query, false otherwise.
     */
    fun matches(constraint: String, sourceManager: SourceManager): Boolean {
        val source = sourceManager.getOrStub(libraryManga.manga.source)
        val sourceName by lazy { source.getNameForMangaInfo() }
        if (constraint.startsWith("id:", true)) {
            return id == constraint.substringAfter("id:").toLongOrNull()
        } else if (constraint.startsWith("src:", true)) {
            val querySource = constraint.substringAfter("src:")
            return if (querySource.equals(LOCAL_SOURCE_ID_ALIAS, ignoreCase = true)) {
                source.id == LocalSource.ID
            } else {
                source.id == querySource.toLongOrNull()
            }
        }
        // Yakuyomi：進階搜尋語法——逗號分隔多條件 AND；每條件可加 `-` 反向；可用 genre:/author:/artist:
        // 精確查欄位，否則純文字比對標題/作者/繪師/簡介/來源名/類型（任一含）。兼容舊行為（無逗號=單條件整句）。
        val manga = libraryManga.manga
        return constraint.split(",").map { it.trim() }.filter { it.isNotEmpty() }.all { condition ->
            checkNegatableConstraint(condition) { c ->
                when {
                    c.startsWith("genre:", true) ->
                        manga.genre?.any { it.contains(c.substringAfter("genre:").trim(), true) } ?: false
                    c.startsWith("author:", true) ->
                        manga.author?.contains(c.substringAfter("author:").trim(), true) ?: false
                    c.startsWith("artist:", true) ->
                        manga.artist?.contains(c.substringAfter("artist:").trim(), true) ?: false
                    else ->
                        manga.title.contains(c, true) ||
                            (manga.author?.contains(c, true) ?: false) ||
                            (manga.artist?.contains(c, true) ?: false) ||
                            (manga.description?.contains(c, true) ?: false) ||
                            sourceName.contains(c, true) ||
                            (manga.genre?.any { g -> g.contains(c, true) } ?: false)
                }
            }
        }
    }

    /**
     * Checks a predicate on a negatable constraint. If the constraint starts with a minus character,
     * the minus is stripped and the result of the predicate is inverted.
     *
     * @param constraint the argument to the predicate. Inverts the predicate if it starts with '-'.
     * @param predicate the check to be run against the constraint.
     * @return !predicate(x) if constraint = "-x", otherwise predicate(constraint)
     */
    private fun checkNegatableConstraint(
        constraint: String,
        predicate: (String) -> Boolean,
    ): Boolean {
        return if (constraint.startsWith("-")) {
            !predicate(constraint.substringAfter("-").trimStart())
        } else {
            predicate(constraint)
        }
    }

    data class Badges(
        val downloadCount: Int,
        val unreadCount: Long,
        val isLocal: Boolean,
        val sourceLanguage: String,
        // Yakuyomi：已翻譯章數角標（由 translationBadge 偏好 gate）。
        val translatedCount: Long,
    )
}
