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
 * 把「還沒翻完的佇列」（mangaId / chapterId / 去字法 / 重繪法 / 是否失敗 / 順序）＋暫停狀態
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
     * 整批覆寫目前佇列＋暫停狀態（佇列小、用全寫避免增刪漂移）。佇列順序＝list index。
     * 暫停旗標存非數字 key（[KEY_PAUSED]）；還原讀佇列時以 `as? String` 過濾掉、不會被當成佇列項。
     */
    fun save(items: List<Saved>, paused: Boolean) {
        preferences.edit {
            clear()
            putBoolean(KEY_PAUSED, paused)
            items.forEachIndexed { index, it ->
                val obj = TranslationObject(
                    it.mangaId,
                    it.chapterId,
                    index,
                    it.method,
                    it.reRenderMethod,
                    it.errored,
                    it.mangaPaused,
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
            out.add(Restored(manga, chapter, o.method, o.reRenderMethod, o.errored, o.mangaPaused))
        }
        return out
    }

    /** 還原暫停狀態（沒存過＝false）。 */
    fun restorePaused(): Boolean = preferences.getBoolean(KEY_PAUSED, false)

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
        val mangaPaused: Boolean = false,
    )

    /** [restore] 的輸出：已把 id 解析回 domain 物件，交給 [TranslationManager] 重建 Entry。 */
    data class Restored(
        val manga: Manga,
        val chapter: Chapter,
        val method: String,
        val reRenderMethod: String?,
        val errored: Boolean,
        val mangaPaused: Boolean = false,
    )

    companion object {
        private const val KEY_PAUSED = "paused"
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
)
