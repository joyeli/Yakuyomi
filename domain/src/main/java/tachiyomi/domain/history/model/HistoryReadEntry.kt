package tachiyomi.domain.history.model

import java.util.Date

/**
 * Yakuyomi：精簡的閱讀記錄投影（每章最後閱讀時間 + 所屬作品 id），供「每日讀了幾章/幾本」統計按日分桶。
 * 與 [HistoryWithRelations]（按作品分組、給歷史畫面）不同——這裡是每章一筆原始 last_read。
 */
data class HistoryReadEntry(
    val mangaId: Long,
    val readAt: Date,
)
