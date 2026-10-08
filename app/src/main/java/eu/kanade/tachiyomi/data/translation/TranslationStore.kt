package eu.kanade.tachiyomi.data.translation

import android.content.Context
import androidx.core.content.edit
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * 翻譯佇列的持久化（對照 [eu.kanade.tachiyomi.data.download.DownloadStore]）。
 *
 * 把「還沒翻完的佇列」（mangaId / chapterId / 去字法 / 重繪法 / 是否夜讀 / 是否失敗 / 順序）＋兩個 pool 各自的暫停狀態
 * 序列化進 SharedPreferences，讓 **app 被系統回收 / 行程被殺 / 重開機後**，[TranslationManager]
 * 能 [restore] 重建佇列並自動續傳——否則佇列只活在記憶體，行程一死就忘了「還要翻哪幾話」
 * （已翻頁有 manifest 保護不會白翻，但剩餘章不會自己接上）。
 *
 * 只存「結構性」狀態（哪些章、什麼方法、是否已失敗），**不存 done/total 進度**——
 * 還原後進度由 manifest（page-level resume）重算；被打斷的 TRANSLATING 章一律當 QUEUE 重跑（已翻頁會跳過）。
 */
class TranslationStore(
    context: Context,
    private val json: Json = Injekt.get(),
    private val getManga: GetManga = Injekt.get(),
    private val getChapter: GetChapter = Injekt.get(),
) {

    private val preferences = context.getSharedPreferences("active_translations", Context.MODE_PRIVATE)

    /**
     * 整批覆寫目前佇列＋兩個 pool 的暫停狀態（佇列小、用全寫避免增刪漂移）。佇列順序＝list index。
     * 暫停旗標存非數字 key（[KEY_PAUSED]＝翻譯／重繪 pool、[KEY_PAUSED_NIGHT]＝夜讀 pool）；還原讀佇列時以
     * `as? String` 過濾掉、不會被當成佇列項。
     */
    fun save(items: List<Saved>, paused: Boolean, nightPaused: Boolean, marks: PendingMarks = PendingMarks()) {
        preferences.edit {
            clear()
            putBoolean(KEY_PAUSED, paused)
            putBoolean(KEY_PAUSED_NIGHT, nightPaused)
            // 標記存成字串集合（不是 String）：還原佇列時 `as? String` 自然略過
            putStringSet(KEY_PENDING_TRANSLATE, encodeMarks(marks.translate))
            putStringSet(KEY_PENDING_NIGHT, encodeMarks(marks.night))
            items.forEachIndexed { index, it ->
                val obj = TranslationObject(
                    it.mangaId,
                    it.chapterId,
                    index,
                    it.method,
                    it.reRenderMethod,
                    it.errored,
                    it.mangaPaused,
                    it.nightRender,
                    it.paused,
                    it.nightForce,
                )
                putString(index.toString(), json.encodeToString(obj))
            }
        }
    }

    /** 還原佇列（背景執行緒呼叫）。依 order 排序；查不到 manga/chapter（已刪）的項略過。 */
    suspend fun restore(): List<Restored> {
        val objs = preferences.all.values
            .mapNotNull { it as? String }
            .mapNotNull { deserialize(it) }
            .sortedBy { it.order }
        if (objs.isEmpty()) return emptyList()

        val out = mutableListOf<Restored>()
        val cachedManga = mutableMapOf<Long, Manga?>()
        for (o in objs) {
            val manga = cachedManga.getOrPut(o.mangaId) { getManga.await(o.mangaId) } ?: continue
            val chapter = getChapter.await(o.chapterId) ?: continue
            out.add(
                Restored(
                    manga,
                    chapter,
                    o.method,
                    o.reRenderMethod,
                    o.errored,
                    o.mangaPaused,
                    o.nightRender,
                    o.paused,
                    o.nightForce,
                ),
            )
        }
        return out
    }

    /** 還原翻譯／重繪 pool 的暫停狀態（沒存過＝false）。 */
    fun restorePaused(): Boolean = preferences.getBoolean(KEY_PAUSED, false)

    /** 還原夜讀 pool 的暫停狀態（沒存過＝false；升級前的持久資料無此 key → 不暫停）。 */
    fun restoreNightPaused(): Boolean = preferences.getBoolean(KEY_PAUSED_NIGHT, false)

    /** 還原「下載完要翻／要產生夜讀版」標記，只留標記時間晚於 [notBefore]（epoch ms）的。沒存過＝空。 */
    fun restorePendingMarks(notBefore: Long): PendingMarks = PendingMarks(
        translate = decodeMarks(preferences.getStringSet(KEY_PENDING_TRANSLATE, null), notBefore),
        night = decodeMarks(preferences.getStringSet(KEY_PENDING_NIGHT, null), notBefore),
    )

    /** 「下載完要翻」[translate]、「下載完要產生夜讀版」[night] 兩組標記：章 id → 標記時間（epoch ms）。 */
    data class PendingMarks(
        val translate: Map<Long, Long> = emptyMap(),
        val night: Map<Long, Long> = emptyMap(),
    )

    private fun deserialize(string: String): TranslationObject? =
        try {
            json.decodeFromString<TranslationObject>(string)
        } catch (e: Exception) {
            null
        }

    /** [save] 的輸入：[TranslationManager] 從內部 Entry 攤平來（只帶 id 與方法/狀態；順序由 [save] 用 list index 補）。 */
    data class Saved(
        val mangaId: Long,
        val chapterId: Long,
        val method: String,
        val reRenderMethod: String?,
        val errored: Boolean,
        /** 這項所屬的「整本 × pool」是否被整本暫停（同本的翻譯項與夜讀項各自記）。 */
        val mangaPaused: Boolean = false,
        /** 夜讀項（產生夜讀檔、不翻譯）；預設 false＝舊持久資料相容。 */
        val nightRender: Boolean = false,
        /** 單章暫停。 */
        val paused: Boolean = false,
        /** 夜讀強制重做（-1＝否；0＝已要求未開跑；>0＝那一輪開跑時間，見 TranslationManager.Entry.nightForce）。 */
        val nightForce: Long = -1L,
    )

    /** [restore] 的輸出：已把 id 解析回 domain 物件，交給 [TranslationManager] 重建 Entry。 */
    data class Restored(
        val manga: Manga,
        val chapter: Chapter,
        val method: String,
        val reRenderMethod: String?,
        val errored: Boolean,
        val mangaPaused: Boolean = false,
        val nightRender: Boolean = false,
        val paused: Boolean = false,
        val nightForce: Long = -1L,
    )

    companion object {
        /** 標記存成「章id:時間」字串集合。 */
        internal fun encodeMarks(marks: Map<Long, Long>): Set<String> =
            marks.entries.mapTo(HashSet()) { (id, at) -> "$id:$at" }

        /** [encodeMarks] 的反向；格式不對的略過，時間早於 [notBefore] 的丟掉（過期）。 */
        internal fun decodeMarks(raw: Set<String>?, notBefore: Long): Map<Long, Long> =
            raw.orEmpty().mapNotNull { s ->
                val id = s.substringBefore(':').toLongOrNull() ?: return@mapNotNull null
                val at = s.substringAfter(':', "").toLongOrNull() ?: return@mapNotNull null
                if (at < notBefore) null else id to at
            }.toMap()

        private const val KEY_PAUSED = "paused"
        private const val KEY_PAUSED_NIGHT = "paused_night"
        private const val KEY_PENDING_TRANSLATE = "pending_translate"
        private const val KEY_PENDING_NIGHT = "pending_night"
    }
}

/** 翻譯佇列項的序列化形狀（對照 DownloadStore 的 DownloadObject）。 */
@Serializable
private data class TranslationObject(
    val mangaId: Long,
    val chapterId: Long,
    val order: Int,
    val method: String = "",
    val reRenderMethod: String? = null,
    val errored: Boolean = false,
    val mangaPaused: Boolean = false,
    /** 夜讀項。有預設值 → 升級前存的 JSON（無此欄）仍能解析、當一般翻譯項還原。 */
    val nightRender: Boolean = false,
    /** 單章暫停（無此欄的舊 JSON＝未暫停）。 */
    val paused: Boolean = false,
    /** 夜讀強制重做（無此欄的舊 JSON＝不強制）。 */
    val nightForce: Long = -1L,
)
