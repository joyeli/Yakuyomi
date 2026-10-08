package eu.kanade.tachiyomi.data.translation

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.data.translation.TranslationEngineConfig.ModelGroup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import li.joye.yakuyomi.engine.ModelDownloader
import li.joye.yakuyomi.engine.ModelProgress
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * 模型自動下載的 reader 端管理者（BYOM 手動放檔的「自動版」）。
 *
 * 把引擎 [ModelDownloader]（撈 manifest → 串流下載 → sha256 驗）跑在 app-scope coroutine 裡，落點＝
 * [TranslationEngineConfig.downloadedDir]（`filesDir/models`，引擎直接從此載入、免 SAF）。進度同時發
 * [StateFlow]（設定頁內嵌顯示）＋ 進度通知（背景可見）。**冪等**：已存在且 sha256 正確的檔跳過，
 * 中斷後重按即續（壞檔/半成品重抓）。
 *
 * 注意：非前景服務——使用者主動觸發的一次性下載夠用；app 若在背景被系統清掉，下次重按即可（sha256 跳過已好的）。
 *
 * **分組下載／刪除（2026-09-26）**：同一份 manifest、同一個資料夾，但以 [ModelGroup] 為單位——
 *  - TRANSLATION＝偵測器＋OCR＋去字（≈247 MB）：設定 › 翻譯 的「下載翻譯模型」。
 *  - NIGHT＝人物分割兩顆＋偵測器（≈147 MB；偵測器已有就跳過）：設定 › 夜讀 的「下載夜讀模型」。
 * 偵測器兩組共用：誰先下誰帶進來；[delete] 只在另一組也不用它時才刪。只裝其中一組的人另一組就是「未就緒」，
 * 事後從那一頁補齊。prune（清 manifest 外的殘留檔）仍用**整份** manifest 當 keep-set，下這組不會清掉那組。
 */
class ModelDownloadManager(private val context: Context) {

    /**
     * 常駐引擎服務：模型檔換新後要主動叫它釋放（見 [download] 末尾的說明）。
     * 取法對齊本層既有風格（[PageTranslator]/[TranslationManager] 也是欄位 `Injekt.get()`）。
     */
    private val engineService: TranslationEngineService = Injekt.get()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _filesRevision = MutableStateFlow(0)

    /**
     * 模型資料夾內容變動的版本計數（[delete] 刪了檔、[download] 落新檔或清殘留 → +1）。設定頁用它當「重掃模型狀態」的 key：
     * 刪除後 state 是 Idle→Idle，StateFlow 不會發射，靠 state 當 key 會讓已刪的模型一直顯示 ✓。
     */
    val filesRevision: StateFlow<Int> = _filesRevision.asStateFlow()

    /** 下載狀態；帶 [group] 讓兩個設定頁各自只顯示自己那組的進度／結果（另一組看到的是 Idle）。 */
    sealed interface State {
        data object Idle : State
        data class Running(val group: ModelGroup, val label: String, val percent: Int) : State
        data class Done(val group: ModelGroup) : State
        data class Error(val group: ModelGroup, val message: String) : State

        /** 這個狀態是不是 [group] 那組的（Idle 對誰都是）。 */
        fun isFor(group: ModelGroup): Boolean = when (this) {
            Idle -> true
            is Running -> this.group == group
            is Done -> this.group == group
            is Error -> this.group == group
        }
    }

    /** 觸發某組下載（任一組已在下載中則忽略——同一個資料夾、同一條通知，不並行）。 */
    fun download(group: ModelGroup) {
        if (_state.value is State.Running) return
        update(State.Running(group, context.stringResource(MR.strings.model_dl_preparing), 0))
        scope.launch {
            try {
                val manifest = ModelDownloader.fetchManifest()
                // 只下這組的檔（偵測器兩組都含；ensure 對已存在且 sha256 相符的檔直接跳過 → 另一組已帶進來就不重抓）。
                val models = manifest.filter { group in TranslationEngineConfig.groupsOfRole(it.role) }
                val dir = TranslationEngineConfig.downloadedDir(context)
                val total = models.sumOf { it.size }.coerceAtLeast(1)
                var completed = 0L
                // 本次是否真的有新檔落地（決定要不要叫 warm 引擎釋放，見本函式末尾）。
                // 判準用 [ModelProgress.Verifying]：它只在「真的下載完一顆」之後才發（引擎 ModelDownloader.ensure
                // 對已存在且 size+sha256 相符的檔是直接發 Done 跳過、不發 Verifying），且不受檔案大小影響
                // （Downloading 每 ~1MB 才回報一次，小檔如 .param 可能一次都沒報）。
                var modelsChanged = false
                ModelDownloader.ensure(models, dir) { p ->
                    when (p) {
                        is ModelProgress.Downloading -> {
                            val pct = ((completed + p.bytes) * 100 / total).toInt().coerceIn(0, 100)
                            update(
                                State.Running(
                                    group,
                                    context.stringResource(MR.strings.model_dl_downloading, p.name),
                                    pct,
                                ),
                            )
                        }
                        is ModelProgress.Verifying -> {
                            modelsChanged = true // 走到驗證＝這顆是剛下載下來的新檔（非跳過）
                            update(
                                State.Running(
                                    group,
                                    context.stringResource(MR.strings.model_dl_verifying, p.name),
                                    (completed * 100 / total).toInt().coerceIn(0, 100),
                                ),
                            )
                        }
                        is ModelProgress.Done -> {
                            // 依「檔名」累計（NCNN 一個 role 有 .param+.bin 兩檔、OCR 更是兩份 .param（base + _mixed）
                            // 共用一份 .bin 共三檔、v5 的 charseg 一個 role 兩顆各 .param+.bin 共四檔，
                            // 用 role 找會重覆算 .param、漏掉 .bin）。
                            completed += models.firstOrNull { it.name == p.name }?.size ?: 0
                            update(
                                State.Running(
                                    group,
                                    context.stringResource(MR.strings.model_dl_done_one, p.name),
                                    (completed * 100 / total).toInt().coerceIn(0, 100),
                                ),
                            )
                        }
                        is ModelProgress.Failed -> Unit // 例外會由下面 catch 統一處理
                    }
                }
                // 清掉不在 manifest 內的殘留檔：v1→v2 換了檔名（lama-manga.onnx / comictextdetector.pt.onnx /
                // ocr_48px_ctc.onnx 等），不清會殘留 ~460MB，且讓「舊版模型」偵測誤判。dir 只放自動下載、刪除安全。
                // v3→v4 OCR 改 NCNN 混合精度，舊 ocr_int8.onnx 也是在這一步被清掉。
                // ★ keep-set 用**整份** manifest（不是這組的子集）：下翻譯組時夜讀組的檔在 keep 內、不會被當殘留誤刪，反之亦然。
                // ★ 但只清「這組的舊檔」或認不出組別的殘留（v1 的 comictextdetector / lama-manga.onnx 之類）：另一組被
                //   manifest 改名的檔留給那一組的下載去換——這裡刪了不補、那組就壞了。偵測器屬兩組，誰先下誰換。
                // keep 空（理論上的退化 manifest）就不 prune——絕不因空清單掃掉已下載好的模型。
                val keep = manifest.map { it.name }.toSet()
                if (keep.isNotEmpty()) {
                    dir.listFiles()?.forEach { f ->
                        if (!f.isFile || f.name in keep) return@forEach
                        val fileGroups = TranslationEngineConfig.groupsOfFile(f.name)
                        // 刪掉殘留＝模型資料夾內容變了（下次解析可能挑到不同檔）→ 也算 modelsChanged。
                        if ((group in fileGroups || fileGroups.isEmpty()) && f.delete()) modelsChanged = true
                    }
                }
                if (modelsChanged) _filesRevision.value++
                update(State.Done(group))
                // ★ 模型換新後主動釋放 warm 引擎（下次翻譯會 lazy 重建、載到新權重）。
                // 原因：[TranslationEngineService.ensureEngine] 只比對 [configSignature]（一堆 pref 值），
                // **模型檔路徑/內容不在簽章裡**（要 stat SAF/檔案系統，每頁翻譯都做太貴）→ 引擎 warm 時按「下載/更新
                // 翻譯模型」，新權重落地後引擎不會自己重建，會沿用舊模型 session。這裡在下載**全部成功**後補一次釋放。
                // 只在真有新檔落地（或清掉殘留）且是翻譯組時做——夜讀組的 CharSegmenter 不由此服務持有，
                // 換新檔後由夜讀端下次建構時自然載到（偵測器若在夜讀組被更新，翻譯引擎沿用舊檔到下次釋放；可接受）。
                if (modelsChanged && group == ModelGroup.TRANSLATION) engineService.shutdownAsync()
            } catch (t: Throwable) {
                // 原始例外訊息只進 logcat：引擎的 sha256 訊息是中文、OkHttp／JSON 的是英文，直接上畫面（設定頁副標＋
                // 失敗通知）會跟介面語言對不上。畫面用 [localizedError] 的分類文字，判斷不中就退回通用的「下載失敗」。
                logcat(LogPriority.ERROR, t) { "模型下載失敗（${group.name}）：${t.message}" }
                update(State.Error(group, localizedError(t)))
            }
        }
    }

    /**
     * 刪除某組的自動下載模型（只動 [TranslationEngineConfig.downloadedDir]，**不碰** BYOM 的 SAF 資料夾）：
     *  - NIGHT → 人物分割兩顆；偵測器只在翻譯組沒裝時一起刪。
     *  - TRANSLATION → OCR＋去字；偵測器只在夜讀組沒裝（人物分割一顆都沒有、含 BYOM）時一起刪。
     * 兩邊都刪過後偵測器不會變孤兒（後刪的那組會帶走它）。
     * 刪翻譯組後主動釋放 warm 引擎（它可能還握著剛刪的檔的 session；下次 isReady 會判未就緒）。
     * 下載中不刪（同一個資料夾在寫）。回傳刪了幾個檔。
     */
    fun delete(group: ModelGroup): Int {
        if (_state.value is State.Running) return 0
        val dir = TranslationEngineConfig.downloadedDir(context)
        val files = dir.listFiles()?.filter { it.isFile }.orEmpty()
        fun localOnly(g: ModelGroup) = files.any { TranslationEngineConfig.groupsOfFile(it.name) == setOf(g) }
        // 「另一組還裝著」要連 BYOM（SAF 資料夾）一起算：那邊的檔本來就不會被刪，但它靠的偵測器可能是這裡自動下載的那顆。
        val otherInstalled = when (group) {
            ModelGroup.TRANSLATION -> localOnly(ModelGroup.NIGHT) || TranslationEngineConfig.charSegResolvable(context)
            ModelGroup.NIGHT -> localOnly(ModelGroup.TRANSLATION) || TranslationEngineConfig.hasAllModels(context)
        }
        var removed = 0
        files.forEach { f ->
            val groups = TranslationEngineConfig.groupsOfFile(f.name)
            val shared = groups.size > 1 // 偵測器：兩組共用，另一組還裝著就留
            val hit = when {
                group !in groups -> false
                !shared -> true
                else -> !otherInstalled
            }
            if (hit && f.delete()) removed++
        }
        if (removed > 0) {
            _state.value = State.Idle // 清掉上一輪 Done/Error 副標（Idle→Idle 不發射，重掃靠 filesRevision）
            _filesRevision.value++
            if (group == ModelGroup.TRANSLATION) engineService.shutdownAsync()
        }
        return removed
    }

    private fun update(s: State) {
        _state.value = s
        when (s) {
            is State.Running ->
                notify(
                    context.stringResource(MR.strings.model_dl_notif_title, s.percent),
                    s.label,
                    ongoing = true,
                    percent = s.percent,
                )
            is State.Done ->
                notify(
                    context.stringResource(MR.strings.model_dl_notif_done),
                    context.stringResource(MR.strings.model_dl_notif_done_text),
                    ongoing = false,
                )
            is State.Error ->
                notify(context.stringResource(MR.strings.model_dl_notif_failed), s.message, ongoing = false)
            State.Idle -> Unit
        }
    }

    /**
     * 下載例外 → 跟介面語言一致的文字（原始訊息已由呼叫端記進 logcat）。
     * 網路類看整條 cause 鏈；sha256／HTTP 狀態碼對的是引擎 [ModelDownloader] 拋的 RuntimeException 訊息格式。
     * 其他（寫檔失敗、磁碟滿、manifest 解析失敗…）一律通用「下載失敗」：最壞只是少了細節，不會出現錯的語言。
     */
    private fun localizedError(t: Throwable): String {
        val chain = generateSequence(t) { it.cause }.take(8).toList()
        val message = t.message.orEmpty()
        val httpStatus = HTTP_STATUS.matchEntire(message)?.groupValues?.get(1)?.toIntOrNull()
        return when {
            chain.any {
                it is UnknownHostException ||
                    it is SocketTimeoutException ||
                    it is ConnectException ||
                    it is SSLException
            } -> context.stringResource(MR.strings.model_dl_error_network)
            message.startsWith("sha256") -> context.stringResource(MR.strings.model_dl_error_checksum)
            httpStatus != null -> context.stringResource(MR.strings.model_dl_error_http, httpStatus)
            else -> context.stringResource(MR.strings.model_dl_failed)
        }
    }

    /** 進度通知（best-effort：無 POST_NOTIFICATIONS 權限則靜默略過）。 */
    private fun notify(title: String, text: String, ongoing: Boolean, percent: Int = 0) {
        try {
            val builder = NotificationCompat.Builder(context, Notifications.CHANNEL_TRANSLATOR_PROGRESS)
                .setSmallIcon(R.drawable.ic_mihon)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(ongoing)
                .setOnlyAlertOnce(true)
            if (ongoing) builder.setProgress(100, percent, false)
            NotificationManagerCompat.from(context).notify(Notifications.ID_MODEL_DOWNLOAD, builder.build())
        } catch (_: Throwable) {
            // 通知是加分、不擋下載
        }
    }

    private companion object {
        /** 引擎 [ModelDownloader] 的 HTTP 失敗訊息格式：`HTTP <狀態碼>`。 */
        val HTTP_STATUS = Regex("""HTTP (\d+)""")
    }
}
