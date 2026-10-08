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
    /**
     * 此項的工作種類（UI 據此決定顯示去字法晶片還是「夜讀」標籤）。
     * 翻譯／重繪走同一套素材與去字法；夜讀是另一張離線重繪圖（`.yakuyomi/<頁>.night.std.webp` 等，見 NightPages），
     * 與去字法無關 → [method] 為空、不可編輯。
     */
    val kind: Kind = Kind.TRANSLATE,
    /**
     * 單章暫停（使用者在佇列頁對這一列按 ⏸）：QUEUE 時 drain 跳過它做下一章、TRANSLATING 時停在頁邊界回 QUEUE 留著。
     * 與「整本（pool）暫停」「pool 暫停」是三層獨立的 hold，任一層 hold 住就不跑。跨重啟保留。
     */
    val paused: Boolean = false,
) {
    enum class Status {
        QUEUE, // 排隊中
        TRANSLATING, // 翻譯中
        ERROR, // 失敗（留佇列、可重試）
    }

    enum class Kind {
        TRANSLATE, // 一般翻譯（偵測/OCR/翻譯/去字全跑）
        RERENDER, // 重繪（復用素材換去字法）
        NIGHT, // 夜讀版（人物分割 + 白底變暗，另存夜讀檔）
        ;

        /** 這種工作跑在夜讀 pool（true）還是翻譯／重繪 pool（false）。 */
        val night: Boolean get() = this == NIGHT
    }

    /** 這一項所屬的「整本 × pool」鍵（佇列頁的分組、整本暫停都以此為單位）。 */
    val poolKey: QueuePoolKey get() = QueuePoolKey(manga.id, kind.night)
}

/**
 * 佇列裡「一本漫畫在某個 pool」的鍵：翻譯／重繪項與夜讀項是兩個獨立消費者（pool），同一本在兩個 pool 各是一組，
 * 整本暫停／搶翻／取消／重試／拖曳重排都以此為單位（夜讀那組暫停不影響同本正在翻的翻譯項，反之亦然）。
 */
data class QueuePoolKey(val mangaId: Long, val night: Boolean)
