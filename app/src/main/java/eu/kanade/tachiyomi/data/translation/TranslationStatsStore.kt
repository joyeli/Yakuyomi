package eu.kanade.tachiyomi.data.translation

import android.content.Context
import androidx.core.content.edit
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.LocalDate

/**
 * Yakuyomi：翻譯統計的每日計數（SharedPreferences；與翻譯佇列/快取同層走檔案+prefs、**不碰 DB schema**，避開
 * 上游 merge 衝突——翻譯狀態一律不進 sqldelight）。
 *
 * 每天一筆（key＝ISO `yyyy-MM-dd`），記當日「新翻」的章數/頁數與 LLM token 用量（prompt/completion）。
 * **只記 raw 每日量、由 raw 聚合週/月/區間**（避免「單一累加總計被清資料破壞」的整合性坑）。成本只記 token、不計價
 * （各家/時段計費不一，難以可靠估算）。時間序列**無法回填**：只從本功能上線那天起累積（與 mihon 閱讀統計同宿命）。
 *
 * 寫入點＝[PageTranslator.translateChapter] 章翻完時 [record]；讀取＝統計畫面 [allDays]。
 * 寫入由翻譯佇列單一 drain 序列觸發，仍加 `@Synchronized` 防並發 read-modify-write。
 */
class TranslationStatsStore(
    context: Context,
    private val json: Json = Injekt.get(),
) {
    private val prefs = context.getSharedPreferences("translation_stats", Context.MODE_PRIVATE)

    /** 累加當日一筆（章/頁/token）。今天 key＝裝置本地日期。全 0 不寫。 */
    @Synchronized
    fun record(chapters: Int, pages: Int, promptTokens: Int, completionTokens: Int) {
        if (chapters == 0 && pages == 0 && promptTokens == 0 && completionTokens == 0) return
        val key = LocalDate.now().toString()
        val cur = read(key)
        val next = DayRecord(
            chapters = cur.chapters + chapters,
            pages = cur.pages + pages,
            promptTokens = cur.promptTokens + promptTokens,
            completionTokens = cur.completionTokens + completionTokens,
        )
        prefs.edit { putString(key, json.encodeToString(next)) }
    }

    /** 全部每日資料，依日期升冪。解析失敗 / 非日期 key 略過。 */
    fun allDays(): List<DayStat> =
        prefs.all.mapNotNull { (k, v) ->
            val date = runCatching { LocalDate.parse(k) }.getOrNull() ?: return@mapNotNull null
            val rec = (v as? String)
                ?.let { runCatching { json.decodeFromString<DayRecord>(it) }.getOrNull() }
                ?: return@mapNotNull null
            DayStat(date, rec.chapters, rec.pages, rec.promptTokens, rec.completionTokens)
        }.sortedBy { it.date }

    private fun read(key: String): DayRecord =
        prefs.getString(key, null)
            ?.let { runCatching { json.decodeFromString<DayRecord>(it) }.getOrNull() }
            ?: DayRecord()

    /** 對外（畫面用）：某日的統計。token 用 Long（長期累積可能很大）。 */
    data class DayStat(
        val date: LocalDate,
        val chapters: Int,
        val pages: Int,
        val promptTokens: Long,
        val completionTokens: Long,
    )

    @Serializable
    private data class DayRecord(
        val chapters: Int = 0,
        val pages: Int = 0,
        val promptTokens: Long = 0,
        val completionTokens: Long = 0,
    )
}
