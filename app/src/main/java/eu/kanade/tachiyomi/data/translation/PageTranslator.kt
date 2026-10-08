package eu.kanade.tachiyomi.data.translation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Build
import android.os.SystemClock
import android.util.Base64
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.crash.TraceLog
import eu.kanade.tachiyomi.data.nightread.NetKind
import eu.kanade.tachiyomi.data.nightread.NightConcurrency
import eu.kanade.tachiyomi.data.nightread.NightGovernor
import eu.kanade.tachiyomi.data.nightread.NightLevel
import eu.kanade.tachiyomi.data.nightread.NightPageOutcome
import eu.kanade.tachiyomi.data.nightread.NightPages
import eu.kanade.tachiyomi.data.nightread.NightPerfProbe
import eu.kanade.tachiyomi.data.nightread.NightPump
import eu.kanade.tachiyomi.data.nightread.NightTicket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import li.joye.yakuyomi.engine.CharSegmenter
import li.joye.yakuyomi.engine.Detector
import li.joye.yakuyomi.engine.Inpainter
import li.joye.yakuyomi.engine.NcnnFlavor
import li.joye.yakuyomi.engine.NcnnForwardAbortedException
import li.joye.yakuyomi.engine.NightReadRenderer
import li.joye.yakuyomi.engine.NightReadStats
import li.joye.yakuyomi.engine.PageAnalysis
import li.joye.yakuyomi.engine.PageResult
import li.joye.yakuyomi.engine.Pt
import li.joye.yakuyomi.engine.RenderConfig
import li.joye.yakuyomi.engine.Renderer
import li.joye.yakuyomi.engine.TextLine
import li.joye.yakuyomi.engine.TextRegion
import li.joye.yakuyomi.nightread.NightReadParams
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.translation.service.TranslationPreferences
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.math.abs

/**
 * M4 步驟 3a/3b：在 mihon 內翻譯一個章節的頁圖（就地覆蓋）。
 *
 * 模型（BYOM）：放在 **mihon「儲存位置」底下的 `models/` 子資料夾**（三顆 NCNN 模型：.param+.bin；OCR 兩份 .param
 *   共用一份 .bin）——
 *   跟下載/翻譯後的檔案同一個地方（使用者把儲存位置設成 OneDrive，模型/下載/譯檔就全在雲端）。
 *   走 SAF 讀取、串流複製到 filesDir 後以路徑 off-heap 載入（[ensureLocal]）。
 * key（BYOK）+ 語言對：從設定頁（[TranslationPreferences]）讀；key 空白時 fallback build-time key。
 * 字型：null → 系統 CJK fallback。
 * §11：僅成功的頁覆蓋；略過/失敗留原圖。回傳翻成功頁數。
 */
class PageTranslator(private val context: Context) {

    private val translationPreferences: TranslationPreferences = Injekt.get()

    /**
     * 常駐（warm）翻譯引擎服務（process singleton）：[translateChapter] 逐頁透過它翻，
     * 引擎跨章復用、不每章重載 ~100MB（M4 ⑦）。本服務內部以 Mutex 序列化引擎存取；
     * drain 本就是單一消費者，故這裡的呼叫天然序列、額外鎖只是保險。
     */
    private val engineService: TranslationEngineService = Injekt.get()

    /** 翻譯統計（每日章/頁/token 計數）。[translateChapter] 章翻完時 record。 */
    private val statsStore: TranslationStatsStore = Injekt.get()

    /** key：優先設定頁（BYOK）；空白時 fallback build-time key（冒煙測試）。 */
    private fun apiKey(): String =
        translationPreferences.activeApiKey().ifBlank {
            // baked key 只是 DeepSeek 的冒煙測試後備；換 provider 後不套用（免拿 DeepSeek key 去打別家）。
            if (translationPreferences.provider.get() == "deepseek") BuildConfig.DEEPSEEK_API_KEY else ""
        }

    /**
     * mihon 儲存位置（base）底下的 `models/` 子資料夾，使用者把三顆 NCNN 模型（.param+.bin；OCR 兩份 .param 共用一份 .bin）
     * 放這。委派共用 [TranslationEngineConfig]。
     */
    private fun modelsDir(): UniFile? = TranslationEngineConfig.modelsDir(context)

    /** 翻譯開關開 + key 有設 + 模型 3 顆齊，才翻得了（給下載 hook 判斷）。模型檢查委派 [TranslationEngineConfig]。 */
    fun isReady(): Boolean {
        if (!translationPreferences.translationMasterEnabled.get()) return false
        if (!translationPreferences.translationEnabled.get()) return false
        if (apiKey().isBlank()) return false
        if (TranslationEngineConfig.isProviderBaseMissing(translationPreferences)) return false
        return TranslationEngineConfig.modelsResolvable(context)
    }

    /**
     * 跨頁流水線深度（同時在飛的頁數）：去字便宜（boxfill／即時翻）→ 深 4＝網路綁定、約 2× 循序速率；
     * 去字貴（aot／下載時 AI 去字）→ 深 2＝CPU 綁定（再深不加速，且多頁去字疊加吃記憶體）。真機 benchmark 定案。
     */
    private fun pipelineDepth(methodRaw: String): Int =
        if (TranslationEngineConfig.mapInpaintMethod(methodRaw) == "boxfill") 4 else 2

    /**
     * 翻譯 [chapterDir]（UniFile，相容本機/SAF）內所有頁圖、就地覆蓋成功的頁。
     * page-level resume（§11）：跳過 manifest 已記的頁、只補沒翻的；每頁成功就更新 manifest，
     * 中斷後重跑只補剩下的。回傳這次新翻成功的頁數。
     *
     * @param method 去字方法原始字串（boxfill / auto_whole / auto_tile）。由呼叫端（佇列 [TranslationManager]）
     *   於排入當下從 [TranslationPreferences.inpaintMethod] 擷取後逐章傳入——讓佇列裡每章可各自帶不同去字法、
     *   可在排隊時被改（見 [TranslationManager.setItemMethod]）。預設＝目前全域偏好，行為與舊版「讀全域 pref」一致。
     * @param nightMaterials false＝不存夜讀素材（[saveNightMaterials]）：壓縮檔章解壓到暫存夾翻，打包時本來就跳過 `.yakuyomi/`、
     *   壓縮檔章也不能夜讀，存了只是白做（遮罩二值化與 PNG 編碼每頁要時間）。完整素材（保留素材／即時翻）照舊，不歸它管。
     */
    suspend fun translateChapter(
        chapterDir: UniFile,
        method: String = translationPreferences.inpaintMethod.get(),
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        shouldStop: () -> Boolean = { false },
        nightMaterials: Boolean = true,
        onPageDone: (pageName: String) -> Unit = {},
    ): Int {
        // 模型齊備才翻（缺任一顆 → 不翻）。引擎本身由 [engineService] 持有/建構（warm、跨章復用），這裡不再自建。
        if (!TranslationEngineConfig.modelsResolvable(context)) return 0
        val images = chapterDir.listFiles()
            ?.filter { f -> f.isFile && (f.name?.substringAfterLast('.', "")?.lowercase() ?: "") in IMAGE_EXT }
            ?.sortedBy { it.name.orEmpty() }
            ?.takeIf { it.isNotEmpty() } ?: return 0

        // resume：manifest 已處理過的頁跳過
        val done = readDonePages(chapterDir).toMutableSet()
        val pending = images.filter { (it.name ?: "") !in done }
        if (pending.isEmpty()) {
            logcat { "已翻過、跳過 ${chapterDir.name}" }
            onProgress(images.size, images.size)
            return 0
        }

        // 保留重繪素材開關（讀一次）：開時翻完每頁另存遮罩/文字區/原圖到 .yakuyomi/ 子夾，日後換去字法重繪。
        // 即時翻譯（liveTranslate）一律強制存素材——reader 端要靠 .yakuyomi/ 素材換去字法重繪（reRenderPage），
        // 故即使使用者沒開 keepMaterials，只要即時翻開著就存（與舊 persistLivePage 的「強制存素材」語義一致）。
        val keepMaterials = translationPreferences.keepMaterials.get() || translationPreferences.liveTranslate.get()
        // 沒存素材、但夜讀開著：只存夜讀要的兩份（[saveNightMaterials]）——去字遮罩（`<base>.mask.png`，每頁約 25 KB，「更多」的
        // 去字區規則，nightread 規則版本 4 q4）與文字框檔（[NightTextBoxes]，`<頁檔名>.nightboxes.json`，每頁約 5 KB，譯後頁的短中文
        // 泡要靠它，使用者 2026-10-06 決定）。不存原圖與完整 json：重繪／還原的工作清單看完整 json，這種頁不會被當成有素材。
        // 壓縮檔章（[nightMaterials]＝false）不存：暫存夾打包時跳過素材夾、壓縮檔章也不能夜讀。
        val nightOnly = nightMaterials && !keepMaterials && translationPreferences.nightReadEnabled.get()
        // 去字方法原始字串（round-trip 用）：素材記成它，重繪時照 method 還原（不存 boxfill/auto 那層映射後值）。
        // 由參數帶入（佇列逐章擷取的去字法、可在排隊時被改）——預設＝全域偏好，與舊版讀 pref 行為一致。
        // 同時也是傳給 [engineService.translatePage] 的去字法：與引擎當前去字法不同時，服務會重建引擎（章與章間換法才重載）。
        val inpaintMethodRaw = method

        val total = images.size
        var processed = total - pending.size // resume：已完成頁先計入進度
        onProgress(processed, total)
        var translated = 0
        // 統計：本輪 LLM token 用量累加（每頁 PageStats 帶 prompt/completion；含全數過濾的 Skipped 也耗了 token）。
        var promptTokens = 0
        var completionTokens = 0
        // 逐頁錯誤累積：某頁 Failed → 跳過、續翻下一頁（不再因連續失敗中止整章），失敗的頁名+原因寫進
        // 該話資料夾的 [ERRORS_FILE]（同步到雲端可直接查；未標記的頁留待重試＝整章 isChapterTranslated=false 變紅）。
        val errors = StringBuilder()
        var materialsFailed = false // 素材存失敗只記一次（避免 18 頁刷 18 行）
        // 共享可變狀態（done/計數/errors/進度）在跨頁併發下的守鎖：只保護「讀-改-寫」那一小段（含 writeManifest），
        // 重活（engine.translatePage 網路+推論、writeBack/saveMaterials 的檔案 I/O）都在鎖外並發跑。
        val stateMutex = Mutex()
        // 跨頁流水線深度（§8 二層併發之「跨頁」）：頁 N 的網路翻譯疊上頁 N+1 的裝置端偵測/OCR。
        // 去字便宜（boxfill/即時）→ 網路綁定、深 4 達約 2×；去字貴（aot）→ CPU 綁定、深 2（再深不加速且吃記憶體）。
        val gate = Semaphore(pipelineDepth(inpaintMethodRaw))
        TraceLog.log(
            "chap",
            "translateChapter.start ${chapterDir.name} pages=$total pending=${pending.size} " +
                "depth=${pipelineDepth(inpaintMethodRaw)} method=$inpaintMethodRaw keepMat=$keepMaterials " +
                "nightOnly=$nightOnly",
        )
        try {
            coroutineScope {
                for (img in pending) {
                    if (shouldStop()) break // 合作式中止：暫停/取消 → 停在頁邊界（不再派新頁；已派的跑完）
                    gate.acquire() // 限同時在飛頁數；coroutineScope 會等所有已派子協程結束才返回
                    launch(Dispatchers.Default) {
                        try {
                            if (shouldStop()) return@launch
                            val name = img.name ?: return@launch
                            // 進場檢查（頁鎖內）：這頁在開跑之後被 reader「翻譯這頁」落地了 → 跳過（頁檔已是譯圖，再翻會把譯圖
                            // 當原檔存）；上次覆蓋到一半被打斷（進行中記號在）→ 先把素材裡的原檔寫回頁檔再解碼。
                            val lock = pageLock(img)
                            when (lock.withLock { enterPendingPage(chapterDir, img, name) }) {
                                PageEntry.READY -> Unit
                                PageEntry.ALREADY_DONE -> {
                                    TraceLog.log("page", "$name already landed by another path, skip")
                                    stateMutex.withLock {
                                        done.add(name)
                                        processed++
                                        onProgress(processed, total)
                                    }
                                    return@launch
                                }
                                PageEntry.RECOVER_FAILED -> {
                                    // 原檔寫不回去：頁檔可能是譯圖或半截檔，這輪不碰它（記號留著、下輪再試），不讓壞檔進引擎。
                                    stateMutex.withLock {
                                        errors.appendLine("$name\t上次翻譯被中斷，素材裡的原檔寫不回頁檔（下次重試）")
                                        processed++
                                        onProgress(processed, total)
                                    }
                                    return@launch
                                }
                            }
                            TraceLog.log("page", "$name decode.start")
                            // 先只讀檔頭量尺寸、過了像素上限才整張解碼（[decodePageCapped]）。解碼的例外（OOM 等）也在這裡
                            // 單頁隔離：以前它在下面的 try 之外，一丟就把整章其他在飛頁一起取消。
                            val decoded = try {
                                decodePageCapped(img)
                            } catch (c: CancellationException) {
                                throw c
                            } catch (t: Throwable) {
                                PageDecode.Unreadable("解碼例外 ${t.javaClass.simpleName}: ${t.message}")
                            }
                            val bmp = when (decoded) {
                                is PageDecode.Ok -> decoded.bitmap
                                is PageDecode.TooLarge -> {
                                    // 超過 [PageSizeCap]：不解碼、不翻，原圖不動（§11）。算「略過」＝記進清單、不重試（尺寸不會變，
                                    // 重試結果一樣），跟無字可翻的略過同一套；原因寫進錯誤檔。
                                    val reason = PageSizeCap.tooLargeReason(decoded.width, decoded.height)
                                    TraceLog.log("page", "$name too large ${decoded.width}x${decoded.height}, skipped")
                                    logcat(LogPriority.WARN) { "翻譯略過 $name：$reason" }
                                    stateMutex.withLock {
                                        errors.appendLine("$name\t$reason")
                                        done.add(name)
                                        done.addAll(commitManifest(chapterDir, done))
                                        processed++
                                        onProgress(processed, total)
                                    }
                                    return@launch
                                }
                                is PageDecode.Unreadable -> {
                                    // 讀不出尺寸／解不出來（檔案壞掉、不是支援的圖檔、記憶體不足）：留原圖、不進清單、下次重試。
                                    // 以前是無聲跳過（不計進度、錯誤檔沒有它）；現在跟其他失敗一樣寫進錯誤檔。
                                    TraceLog.log("page", "$name unreadable: ${decoded.reason.take(60)}")
                                    logcat(LogPriority.WARN) { "翻譯頁讀不出來 $name：${decoded.reason}" }
                                    stateMutex.withLock {
                                        errors.appendLine("$name\t${decoded.reason}")
                                        processed++
                                        onProgress(processed, total)
                                    }
                                    return@launch
                                }
                            }
                            TraceLog.log("page", "$name decoded ${bmp.width}x${bmp.height} -> engine")
                            try {
                                // 併發進引擎（EngineService 已放鬆成可並發；同章去字法相同、不重建）。§11 三態處理與循序版完全一致。
                                when (val r = engineService.translatePage(bmp, inpaintMethodRaw)) {
                                    is PageResult.Translated -> {
                                        val keepThisPage = keepMaterials && r.analysis != null
                                        // 落地整段在頁鎖內（只擋同一頁；別頁照樣並發）：存原檔 → 覆蓋頁檔 → 存素材 → 記清單 → 刪記號。
                                        val landed = lock.withLock {
                                            // 引擎跑的這段時間裡，同一頁被 reader「翻譯這頁」落地了（頁檔現在是譯圖、原檔已存）
                                            // → 不再蓋一次：再存原檔會存到譯圖。
                                            if (name in readDonePages(chapterDir)) return@withLock false
                                            // 保留素材：原檔位元組要在頁檔被譯圖覆蓋**之前**原樣複製出去（覆蓋後就沒有了）。
                                            // 複製失敗不擋翻譯，[saveMaterials] 會退回舊做法（把解碼後的原圖存成有損 WebP）。
                                            val saved = if (keepThisPage) saveOriginalBytes(chapterDir, img) else null
                                            writeBackOrRestore(img, r.page, saved) // 各頁寫各自檔、並發
                                            r.page.recycle() // 譯圖已落檔、後面用不到 → 立即回收（跨頁併發下少堆一張 bitmap，降記憶體峰值）
                                            // 成品頁換了 → 舊夜讀版（若有，如先手動產生再翻譯）作廢；翻完由開關開著的夜讀佇列補做。
                                            invalidateNightPage(chapterDir.findFile(MATERIALS_DIR), name)
                                            TraceLog.log("page", "$name translated.done")
                                            // 保留重繪素材（best-effort、不擋翻譯）：遮罩＋文字區；原圖那份上面已原樣複製，
                                            // 沒複製成才用 bmp（剛解碼的原圖；引擎不 mutate 輸入、回新 bitmap）補一份有損 WebP。
                                            val matErr = if (keepThisPage) {
                                                saveMaterials(
                                                    chapterDir,
                                                    name,
                                                    bmp,
                                                    r.analysis!!,
                                                    inpaintMethodRaw,
                                                    saved != null,
                                                )
                                            } else if (nightOnly && r.analysis != null) {
                                                saveNightMaterials(
                                                    chapterDir,
                                                    img,
                                                    name,
                                                    r.analysis!!,
                                                    bmp.width,
                                                    bmp.height,
                                                )
                                            } else {
                                                null
                                            }
                                            stateMutex.withLock {
                                                promptTokens += r.stats.promptTokens
                                                completionTokens += r.stats.completionTokens
                                                // 素材存失敗（如 SD/SAF 不支援 .yakuyomi 子夾）不再無聲 → 首次失敗寫進該話錯誤檔，供 adb-less 診斷。
                                                if (matErr != null && !materialsFailed) {
                                                    materialsFailed = true
                                                    errors.appendLine("(materials)\t$matErr")
                                                }
                                                translated++
                                                done.add(name)
                                                done.addAll(commitManifest(chapterDir, done))
                                            }
                                            // 這頁進了清單 → 進行中記號用不到了（記號只對還沒進清單的頁有意義）。
                                            saved?.marker?.let { m -> runCatching { m.delete() } }
                                            true
                                        }
                                        if (landed) {
                                            // 推「這頁翻好了」事件 → 即時翻 loader 直接重畫該頁（不靠輪詢 manifest／queueState，
                                            // 後者是 conflated StateFlow + 慢的檔案讀 → 某頁常要等後面頁的 emit 才被順便比中、更新延遲）。
                                            onPageDone(name)
                                        } else {
                                            r.page.recycle()
                                            TraceLog.log("page", "$name landed by another path meanwhile, drop ours")
                                            stateMutex.withLock {
                                                promptTokens += r.stats.promptTokens // 這次的 token 還是花了
                                                completionTokens += r.stats.completionTokens
                                                done.add(name)
                                            }
                                        }
                                    }
                                    is PageResult.Skipped -> stateMutex.withLock {
                                        promptTokens += r.stats.promptTokens // 全數過濾的略過仍耗了 token（其餘 Skipped＝0）
                                        completionTokens += r.stats.completionTokens
                                        TraceLog.log("page", "$name skipped")
                                        logcat { "翻譯略過 $name：${r.reason}" }
                                        done.add(name)
                                        done.addAll(commitManifest(chapterDir, done)) // 略過＝沒字可翻、算處理過、不重試
                                    }
                                    is PageResult.Failed -> stateMutex.withLock {
                                        // 該頁失敗 → 跳過、留原圖、續翻下一頁；記原因供查（不標記、下次重試）。
                                        TraceLog.log("page", "$name failed: ${r.reason.take(60)}")
                                        logcat(LogPriority.WARN) { "翻譯失敗 $name：${r.reason}" }
                                        errors.appendLine("$name\t${r.reason}")
                                    }
                                }
                            } catch (c: CancellationException) {
                                throw c // 尊重結構化併發取消（整個 drain 被 cancel）——不吞（下面 Throwable catch 才不會誤吃）
                            } catch (t: Throwable) {
                                // ★ 單頁例外隔離（OOM／解碼／IO／native）：記錯、留原圖待重試，**不 cross-cancel** 其他在飛頁、不炸整章。
                                TraceLog.log("page", "$name EXCEPTION ${t.javaClass.simpleName}: ${t.message}")
                                logcat(LogPriority.ERROR, t) { "翻譯頁例外（已隔離、續其他頁）$name" }
                                stateMutex.withLock {
                                    errors.appendLine("$name\t例外 ${t.javaClass.simpleName}: ${t.message}")
                                }
                            } finally {
                                if (!bmp.isRecycled) bmp.recycle() // 任何出口（成功/略過/失敗/例外）都回收，避免併發下 bitmap 堆積 → OOM
                            }
                            // 跑到 when 的頁（含例外頁）在這裡計進度；太大／讀不出來的頁上面自己計過。shouldStop／無檔名提早 return
                            // 者不計（同循序版 continue 語義）。
                            stateMutex.withLock {
                                processed++
                                onProgress(processed, total)
                            }
                        } finally {
                            gate.release()
                        }
                    }
                }
            }
        } catch (c: CancellationException) {
            throw c // 整個 drain 被 cancel（暫停/關即時翻/行程收）→ 傳播、別當章例外記；已翻頁有 manifest、resume 續補
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "translateChapter 例外（保留原圖）" }
            errors.appendLine("(chapter)\t例外 ${e.javaClass.simpleName}: ${e.message}")
        }
        // 把逐頁失敗原因寫進該話資料夾（.yakuyomi_errors.txt）；本輪全無錯 → 清掉舊錯誤檔。
        // 之前幾輪記下的「頁圖太大」行要留著：那些頁已進清單、這輪沒處理（不在 pending），整檔重寫會讓原因消失。
        val pendingNames = pending.mapNotNullTo(HashSet()) { it.name }
        val carried = readErrorLines(chapterDir).filter { line ->
            val page = line.substringBefore('\t')
            page !in pendingNames && page in done && PageSizeCap.isTooLargeReason(line.substringAfter('\t', ""))
        }
        val allErrors = carried + errors.lines().filter { it.isNotBlank() }
        if (allErrors.isNotEmpty()) {
            overwriteBytes(chapterDir, ERRORS_FILE, allErrors.joinToString("\n", postfix = "\n").toByteArray())
        } else {
            runCatching { chapterDir.findFile(ERRORS_FILE)?.delete() }
        }
        // 統計：記當日新翻頁數 + token（resume 已跳過 done 頁 ⇒ translated 為本輪新翻、不重複計）。
        // chapters 只在本輪把整章補成「全翻完」時 +1（避免部分翻/重試各算一次）。
        if (translated > 0 || promptTokens > 0 || completionTokens > 0) {
            val chapterCount = if (translated > 0 && isChapterTranslated(chapterDir)) 1 else 0
            statsStore.record(chapterCount, translated, promptTokens, completionTokens)
        }
        return translated
    }

    /**
     * （目前未使用）即時翻譯舊路徑：loader 逐頁進引擎翻、靠本法逐頁落地。即時翻已改為「整章排入翻譯佇列、
     * loader 只當顯示層」（[translateChapter] 已強制存素材），故 reader 不再呼叫本法。保留：無害、可供日後線上
     * （未下載）即時翻或單頁路徑復用。
     *
     * 即時翻譯（reader 邊讀邊翻）逐頁落地：把單頁的譯圖**就地覆蓋**下載檔、並寫素材 + 記 manifest，
     * 形狀與 [translateChapter] 逐頁所做的完全一致 → 重開章節走 page-level resume（manifest 命中跳過、不重翻）、
     * 且素材齊備可不重跑 OCR/翻譯直接換去字法重繪（[reRenderPage]/[reRenderChapter]）。
     *
     * 與 [translateChapter] 的差異：
     *  - **強制存素材**：即時翻不看 [TranslationPreferences.keepMaterials] 開關——重繪一定要素材，故 [analysis] 非 null 就存。
     *  - **manifest 上鎖**：多個 [TranslatingPageLoader.loadPage] 可併發落地同章不同頁 → 讀-改-寫經 [manifestMutex] 序列化，避免互蓋掉彼此剛加的頁名。
     *
     * §11：呼叫端只在**翻譯成功**（[output] 來自 [PageResult.Translated]）時才呼叫本法 → [writeBack] 只覆蓋成功頁；
     * 失敗/略過頁不進來、下載檔維持原圖。覆蓋之前先把下載檔的位元組原樣複製成重繪源（[saveOriginalBytes]，
     * 檔名見 [OriginalMaterial]）。整段在頁鎖內（[pageLock]），跟佇列落地同一頁互斥。
     *
     * @param keepExistingOriginal true＝頁檔現在是我們自己的成品、引擎的輸入是素材裡的原圖（呼叫端用
     *   [ownOutputOriginal] 判的）→ 原圖素材原封不動，不拿頁檔（譯圖）重存。
     * @param wasPending true＝進引擎之前這頁還沒進已翻清單。落地時若已在清單裡＝佇列在這段時間把它落地了
     *   （頁檔已是譯圖、原檔已存）→ 不再蓋一次，直接回 true。
     *
     * @param chapterDir 章目錄（鬆散下載夾；即時翻本里程碑只走已下載章）。
     * @param pageFile   這一頁的下載檔（會被 [translated] 覆蓋）。
     * @param original   這頁的原圖（引擎輸入；引擎回的是新 bitmap、不會動到它）——原檔位元組複製不成時的後備重繪源。
     * @param translated 譯後圖（覆蓋 [pageFile]）。
     * @param analysis   重繪素材（遮罩 + 文字區）；非 null 才存素材（即時翻一律帶素材，見上）。
     * @param methodRaw  去字法原始字串（即時翻走 liveInpaintMethod，預設 auto_whole），存進素材 json 供重繪 round-trip。
     * @return 是否落地成功（覆蓋 + 記 manifest 成功）。best-effort：包 runCatching，失敗回 false（下載檔可能已被覆蓋，但這只代表「已翻」，不毀畫）。
     */
    suspend fun persistLivePage(
        chapterDir: UniFile,
        pageFile: UniFile,
        original: Bitmap,
        translated: Bitmap,
        analysis: PageAnalysis?,
        methodRaw: String,
        keepExistingOriginal: Boolean = false,
        wasPending: Boolean = false,
    ): Boolean {
        val name = pageFile.name ?: return false
        return runCatching {
            pageLock(pageFile).withLock {
                if (wasPending && name in readDonePages(chapterDir)) return@withLock true
                // 0. 原檔位元組要在頁檔被覆蓋**之前**原樣複製出去（此時 pageFile 還是下載下來的原檔）。best-effort。
                //    頁檔是我們自己的成品時不複製：素材裡留著的才是原圖。
                val copyOriginal = analysis != null && !keepExistingOriginal
                val saved = if (copyOriginal) saveOriginalBytes(chapterDir, pageFile) else null
                // 1. 譯圖覆蓋下載檔（§11：呼叫端保證只有成功頁才進來）。成品頁換了 → 舊夜讀版作廢（見 [invalidateNightPage]）。
                writeBackOrRestore(pageFile, translated, saved)
                invalidateNightPage(chapterDir.findFile(MATERIALS_DIR), name)
                // 2. 強制存重繪素材（即時翻不看 keepMaterials 開關；重繪需要它）。saveMaterials 本身 best-effort + "wt" 截斷安全。
                if (analysis != null) {
                    val originalInPlace = saved != null || keepExistingOriginal
                    saveMaterials(chapterDir, name, original, analysis, methodRaw, originalInPlace)
                }
                // 3. 加進 manifest（page-level resume 標記）。讀-改-寫經 [manifestMutex]（行程內共用）序列化。
                commitManifest(chapterDir, setOf(name))
                saved?.marker?.let { m -> runCatching { m.delete() } }
                true
            }
        }.getOrElse { e ->
            logcat(LogPriority.WARN, e) { "即時翻譯落地頁失敗 $name" }
            false
        }
    }

    /**
     * 翻譯「單一頁」（reader 內「翻譯這頁」控制鈕用）：讀 [chapterDir] 內檔名 == [pageFileName] 的頁圖 →
     * 透過 warm [engineService] 翻 → [PageResult.Translated] 時用 [persistLivePage] 落地
     * （覆蓋下載檔 + 強制存重繪素材 + 記 manifest，與佇列逐頁完全相同的形狀）→ 回傳是否翻成功。
     *
     * 與佇列共用同一條路：
     *  - **引擎鎖**：走 [engineService.translatePage]，內部 [Mutex] 與佇列 drain 序列化（同實例不會並發翻多頁）。
     *  - **manifest 鎖**：[persistLivePage] 的 manifest 讀-改-寫經 [manifestMutex] 序列化（與佇列逐頁落地不互相蓋掉）。
     *
     * §11：只有 [PageResult.Translated] 才覆蓋；[PageResult.Skipped]（無字）/[PageResult.Failed]（網路/引擎）→
     * 不動原圖、回 false（呼叫端據此提示、頁面維持原圖）。只對「已下載章（頁圖在磁碟、鬆散資料夾）」有意義。
     *
     * @param method 去字法原始字串（boxfill / auto_whole / auto_tile）；預設＝目前全域偏好。傳給引擎、也存進素材。
     * @return [SinglePageResult.TRANSLATED]＝翻成功並已覆蓋落地；[SinglePageResult.NOT_TRANSLATED]＝略過/失敗/解析不到檔
     *   （原圖未動）；[SinglePageResult.NO_ORIGINAL]＝這頁已翻過、沒有原圖素材，頁檔就是我們寫的譯圖（[isNightOnlyOutput]），
     *   拒絕再翻（什麼都不動）；[SinglePageResult.TOO_LARGE]＝頁圖超過像素上限（[PageSizeCap]），沒翻、原圖未動。
     * 整段算進 [TranslationBusy]（夜讀讓路）。
     */
    suspend fun translateSinglePage(
        chapterDir: UniFile,
        pageFileName: String,
        method: String = translationPreferences.inpaintMethod.get(),
    ): SinglePageResult = TranslationBusy.track { translateSinglePageImpl(chapterDir, pageFileName, method) }

    private suspend fun translateSinglePageImpl(
        chapterDir: UniFile,
        pageFileName: String,
        method: String,
    ): SinglePageResult {
        if (!TranslationEngineConfig.modelsResolvable(context)) return SinglePageResult.NOT_TRANSLATED
        // 解析目標頁圖檔（頂層、副檔名為圖、檔名 == pageFileName）。
        val pageFile = chapterDir.listFiles()
            ?.firstOrNull { f ->
                f.isFile &&
                    (f.name?.substringAfterLast('.', "")?.lowercase() ?: "") in IMAGE_EXT &&
                    f.name == pageFileName
            }
            ?: return SinglePageResult.NOT_TRANSLATED
        // 進場檢查（頁鎖內）。這頁還沒翻過：上次覆蓋到一半被打斷的話先把原檔寫回。這頁已翻過：頁檔若是我們自己的
        // 成品（譯圖／重繪圖／還原的原圖），拿素材裡的原圖當引擎輸入，才是真的重翻——拿譯圖去翻沒有意義，
        // 還會把譯圖當原檔存起來、原圖永久消失。已翻過、沒有原圖素材，而對得上的夜讀文字框檔證明頁檔就是我們寫的譯圖
        // （只存夜讀素材的頁）→ 拒絕：再翻只會拿譯圖去翻、把譯圖當原圖存起來，原本對準原文的框也會被換掉。
        var wasPending = false
        var keptOriginal: UniFile? = null
        var noOriginal = false
        val ready = pageLock(pageFile).withLock {
            if (pageFileName in readDonePages(chapterDir)) {
                keptOriginal = ownOutputOriginal(chapterDir, pageFile, pageFileName)
                noOriginal = keptOriginal == null && isNightOnlyOutput(chapterDir, pageFile, pageFileName)
                true
            } else {
                wasPending = true
                recoverInterrupted(chapterDir, pageFile, pageFileName)
            }
        }
        if (noOriginal) {
            logcat { "翻譯這頁拒絕 $pageFileName：已翻過、沒有原圖素材（頁檔是只存夜讀素材時寫的譯圖）" }
            return SinglePageResult.NO_ORIGINAL
        }
        if (!ready) return SinglePageResult.NOT_TRANSLATED // 原檔寫不回頁檔：不拿可能壞掉的頁檔去翻
        // 引擎輸入：頁檔是我們自己的成品時（[keptOriginal] 不是 null）只用素材裡的原圖，**絕不退回頁檔**——頁檔是譯圖，拿它去翻
        // 而且不帶 keepExistingOriginal，落地時會把譯圖複製成原圖素材、原圖從此消失（§11）。原圖素材解不開（檔案壞掉、記憶體
        // 不足）就不翻、什麼都不動。其他情況頁檔就是原圖。兩者都先過像素上限（[decodePageCapped]）。
        val kept = keptOriginal
        val decoded = if (kept != null) {
            try {
                decodePageCapped(kept)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                PageDecode.Unreadable("原圖素材解碼例外 ${t.javaClass.simpleName}: ${t.message}")
            }
        } else {
            decodePageCapped(pageFile)
        }
        val original = when (decoded) {
            is PageDecode.Ok -> decoded.bitmap
            is PageDecode.TooLarge -> {
                // 太大：不翻、原圖不動（§11）。原因寫進該話錯誤檔；不記進已翻清單（單頁路徑的略過本來就不記）。
                val reason = PageSizeCap.tooLargeReason(decoded.width, decoded.height)
                logcat(LogPriority.WARN) { "翻譯這頁略過 $pageFileName：$reason" }
                recordSinglePageError(chapterDir, pageFileName, reason)
                return SinglePageResult.TOO_LARGE
            }
            is PageDecode.Unreadable -> {
                logcat(LogPriority.WARN) { "翻譯這頁讀不出來 $pageFileName：${decoded.reason}" }
                return SinglePageResult.NOT_TRANSLATED
            }
        }
        val keptBitmapUsed = kept != null // 走到這裡＝解碼成功；頁檔是成品時解的一定是素材原圖
        return try {
            when (val r = engineService.translatePage(original, method)) {
                is PageResult.Translated -> {
                    // 與佇列逐頁一致：覆蓋下載檔 + 強制存素材（重繪用）+ 記 manifest（manifestMutex 序列化）。
                    persistLivePage(
                        chapterDir,
                        pageFile,
                        original,
                        r.page,
                        r.analysis,
                        method,
                        keepExistingOriginal = keptBitmapUsed,
                        wasPending = wasPending,
                    )
                    r.page.recycle()
                    SinglePageResult.TRANSLATED
                }
                is PageResult.Skipped -> {
                    logcat { "翻譯這頁略過 $pageFileName：${r.reason}" } // 無字可翻：不覆蓋、不算成功
                    SinglePageResult.NOT_TRANSLATED
                }
                is PageResult.Failed -> {
                    logcat(LogPriority.WARN) { "翻譯這頁失敗 $pageFileName：${r.reason}" } // 網路/引擎：留原圖、可重試
                    SinglePageResult.NOT_TRANSLATED
                }
            }
        } finally {
            original.recycle()
        }
    }

    /** [translateSinglePage] 的結果。 */
    enum class SinglePageResult {
        /** 翻成並已覆蓋落地。 */
        TRANSLATED,

        /** 略過（無字）／失敗（網路、引擎）／找不到檔：原圖未動。 */
        NOT_TRANSLATED,

        /** 已翻過、沒有原圖素材，頁檔就是我們寫的譯圖：拒絕再翻，什麼都沒動。 */
        NO_ORIGINAL,

        /** 頁圖超過像素上限（[PageSizeCap]）：沒解碼、沒翻，原圖未動；原因已寫進該話錯誤檔。 */
        TOO_LARGE,
    }

    /**
     * 頁檔是不是**只存夜讀素材時我們寫的譯圖**：沒有完整素材 json，有這頁的夜讀文字框檔（[NightTextBoxes]），而且它對得上
     * 現在的頁檔（寬高＋位元組數，[NightTextBoxes.fits]）。對得上＝頁檔跟當初翻譯落地時一個位元組不差。
     * 任何一步讀不到 → false（照以前的做法處理這頁）。
     */
    private fun isNightOnlyOutput(chapterDir: UniFile, pageFile: UniFile, name: String): Boolean = runCatching {
        val matDir = chapterDir.findFile(MATERIALS_DIR) ?: return false
        if (matDir.findFile(NightTextBoxes.materialsJsonName(name))?.isFile == true) return false
        val boxes = findNightBoxes(matDir, name)?.let { readNightBoxes(it, name) } ?: return false
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(pageFile.uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        NightTextBoxes.fits(boxes, bounds.outWidth, bounds.outHeight, pageFile.length())
    }.getOrDefault(false)

    /**
     * 這頁有沒有重繪素材（完整素材 json `<base>.json` 加原圖素材，[OriginalMaterial]）：重繪與「原圖（還原未翻）」都要兩者齊。
     * 只存夜讀素材的頁（遮罩＋夜讀文字框）、沒存素材的頁 → false。reader 開重繪對話框前先問（沒有就直接提示、不開），
     * [reRenderPage] 載去字模型前也先問。讀不到 → false。IO。
     */
    fun hasReRenderMaterials(chapterDir: UniFile, pageFileName: String): Boolean = runCatching {
        val matDir = chapterDir.findFile(MATERIALS_DIR) ?: return false
        matDir.findFile(NightTextBoxes.materialsJsonName(pageFileName))?.isFile == true &&
            findOriginalMaterial(matDir, pageFileName) != null
    }.getOrDefault(false)

    /** 該頁是否已翻（manifest 命中）：即時翻 loader 用來判斷「直接服務已覆蓋的譯圖」還是「進引擎翻」。 */
    fun isPageTranslated(chapterDir: UniFile, pageName: String): Boolean =
        pageName in readDonePages(chapterDir)

    /**
     * 讀某章已存的去字法（任一頁素材 json 的 method）；無素材＝null（不可便宜重繪）。
     * 給「改去字法後升級重繪」（[TranslationManager.reRenderAllUpgradable]）判斷向上/向下：
     * stored rank ≤ 新 rank 才重繪、向下保留最好結果。只看鬆散章（CBZ 素材在壓縮檔內、呼叫端不會傳進來）。
     */
    fun storedInpaintMethod(chapterDir: UniFile): String? {
        val matDir = chapterDir.findFile(MATERIALS_DIR) ?: return null
        // 只認完整素材 json：夜讀文字框檔（`<頁檔名>.nightboxes.json`）也是 .json 結尾，不排除的話只存文字框的章會被當成有素材
        // （先比檔名、再問 isFile：SAF 上 isFile 是逐檔查詢）。逐個試到解得開為止：萬一有個解不開的 .json 排在前面，
        // 這章照樣認得出有素材（正常第一個就解得開，只讀一個檔）。
        val candidates = matDir.listFiles()
            ?.filter { f -> f.name?.let(NightTextBoxes::isMaterialsJson) == true }
            .orEmpty()
        for (f in candidates) {
            if (!f.isFile) continue
            decodeMaterialsFile(f)?.let { return it.method }
        }
        return null
    }

    /**
     * 換去字法重繪整章（§ 不重跑偵測/OCR/翻譯）：對 [chapterDir] 內每頁「有保留素材」的頁，
     * 用 [newMethod]（[TranslationPreferences.inpaintMethod] 原始字串）重做**去字 + 排版**、就地覆蓋頁圖。
     *
     * 復用翻譯時存下的素材（`.yakuyomi/` 的原圖 `<base>.orig.<副檔名>`，舊版是 `<base>.orig.webp`，見 [OriginalMaterial]；
     * 加上 `<base>.json` 遮罩/文字區/譯文），
     * 故**全程無網路、無 LLM、不載偵測/OCR 模型**——只載 lama 一顆。素材缺的頁直接跳過（不毀原圖）。
     * 重繪後把 json 的 `method` 改成 [newMethod]（保持記錄與檔案一致，下次比對才準）。回傳成功重繪的頁數。
     * 去字與排版參數（含去字解析度、遮罩膨脹、縱中橫）照使用者**目前**的設定，與翻譯同一份映射（[EngineConfigMapping]）。
     *
     * @param onProgress 進度回呼（done, total＝有素材的頁數）。
     * @param shouldStop 合作式中止（暫停/取消）：每頁邊界檢查，停在頁界、已重繪的頁保留。
     */
    suspend fun reRenderChapter(
        chapterDir: UniFile,
        newMethod: String,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        shouldStop: () -> Boolean = { false },
    ): Int {
        // 原圖：用素材的原圖還原整章，不需 lama → 早於 lama 載入處理。工作清單同去字路徑（有素材 json 的頁）。
        if (newMethod == ORIGINAL_METHOD) {
            val matDir = chapterDir.findFile(MATERIALS_DIR) ?: run {
                onProgress(0, 0)
                return 0
            }
            val workList = chapterDir.listFiles()
                ?.filter { f -> f.isFile && (f.name?.substringAfterLast('.', "")?.lowercase() ?: "") in IMAGE_EXT }
                ?.sortedBy { it.name.orEmpty() }
                ?.filter { img ->
                    // 完整素材 json（[NightTextBoxes.materialsJsonName]）；只有遮罩／夜讀文字框的頁不算
                    val name = img.name ?: return@filter false
                    matDir.findFile(NightTextBoxes.materialsJsonName(name))?.isFile == true
                }
                ?: return 0
            onProgress(0, workList.size)
            var processed = 0
            for (img in workList) {
                if (shouldStop()) break
                if (reRenderOnePage(matDir, img, newMethod, null, null)) {
                    processed++
                    onProgress(processed, workList.size)
                }
            }
            return processed
        }
        // 只需去字模型（偵測/OCR/翻譯都不跑）→ NCNN AOT 優先（.param+.bin），模型解析委派共用 [TranslationEngineConfig]。
        val inpaintPath = TranslationEngineConfig.resolveInpaintModel(context) ?: return 0

        // 去字＋排版設定：與翻譯（常駐引擎的 buildEngineConfig）同一份映射（[EngineConfigMapping]），含去字解析度、
        // 遮罩膨脹、縱中橫——以前這裡內聯一份、漏了這三個，重繪一律用引擎預設 768／24／開。
        // 偵測／OCR／翻譯的設定這裡用不到：不跑偵測（遮罩取素材裡的原始 seg 遮罩，bbox 外擴與膨脹由 Inpainter 照設定再做）。
        val inpainterCfg = EngineConfigMapping.inpainterConfig(translationPreferences, newMethod)
        val renderCfg = EngineConfigMapping.renderConfig(translationPreferences)

        // 頂層圖檔（同 translateChapter 的 filter/sort）；只取「有素材 json」的頁＝工作清單。
        val images = chapterDir.listFiles()
            ?.filter { f -> f.isFile && (f.name?.substringAfterLast('.', "")?.lowercase() ?: "") in IMAGE_EXT }
            ?.sortedBy { it.name.orEmpty() }
            ?: return 0
        val matDir = chapterDir.findFile(MATERIALS_DIR)
        val workList = if (matDir == null) {
            emptyList()
        } else {
            images.filter { img ->
                // 完整素材 json（[NightTextBoxes.materialsJsonName]）；只有遮罩／夜讀文字框的頁不算
                val name = img.name ?: return@filter false
                matDir.findFile(NightTextBoxes.materialsJsonName(name))?.isFile == true
            }
        }
        onProgress(0, workList.size)
        if (workList.isEmpty()) return 0

        var processed = 0
        // 一顆 lama session 跨整章復用（別逐頁重建：載入 ~100MB native + 編譯耗時）；`use { }` 確保釋放。
        Inpainter(inpaintPath, inpainterCfg).use { inpainter ->
            for (img in workList) {
                if (shouldStop()) break // 合作式中止：停在頁邊界
                // 單頁失敗（素材壞/原圖缺）不該中斷整章：reRenderOnePage 內已包 try/catch、回 false 略過。
                if (reRenderOnePage(matDir!!, img, newMethod, inpainter, renderCfg)) {
                    processed++
                    onProgress(processed, workList.size)
                }
            }
        }
        return processed
    }

    /**
     * 換去字法重繪「單頁」（§ 不重跑偵測/OCR/翻譯）：對 [chapterDir] 內檔名 == [pageFileName] 的頁，
     * 用 [newMethod]（[TranslationPreferences.inpaintMethod] 原始字串）重做**去字 + 排版**、就地覆蓋頁圖。
     *
     * 與 [reRenderChapter] 共用核心 [reRenderOnePage]，差別只在這裡只開一顆 lama session、只跑一頁
     * （reader 內長按某頁→選去字法→重繪當頁用）。素材缺/模型缺 → 回 false（不毀原圖）。
     * 整段算進 [TranslationBusy]（夜讀讓路；這條不經引擎服務與佇列，沒有別的訊號反映它）。
     */
    suspend fun reRenderPage(
        chapterDir: UniFile,
        pageFileName: String,
        newMethod: String,
    ): Boolean = TranslationBusy.track { reRenderPageImpl(chapterDir, pageFileName, newMethod) }

    private suspend fun reRenderPageImpl(chapterDir: UniFile, pageFileName: String, newMethod: String): Boolean {
        // 原圖：用素材的原圖還原該頁，不需 lama / 排版 → 早於 lama 載入處理（避免為還原白載 ~100MB）。
        if (newMethod == ORIGINAL_METHOD) {
            val img = chapterDir.listFiles()
                ?.firstOrNull { f ->
                    f.isFile &&
                        (f.name?.substringAfterLast('.', "")?.lowercase() ?: "") in IMAGE_EXT &&
                        f.name == pageFileName
                }
                ?: return false
            val matDir = chapterDir.findFile(MATERIALS_DIR) ?: return false
            return reRenderOnePage(matDir, img, newMethod, null, null)
        }
        // 沒有重繪素材（沒存素材、只存夜讀素材的頁）→ 載去字模型之前就回 false，別為一定失敗的重繪白載 ~100MB。
        if (!hasReRenderMaterials(chapterDir, pageFileName)) return false
        // 只需去字模型（同 reRenderChapter）→ NCNN AOT 優先（.param+.bin）。模型解析委派共用 [TranslationEngineConfig]。
        val inpaintPath = TranslationEngineConfig.resolveInpaintModel(context) ?: return false

        // 去字＋排版設定：與 reRenderChapter／翻譯同一份映射（[EngineConfigMapping]）。
        val inpainterCfg = EngineConfigMapping.inpainterConfig(translationPreferences, newMethod)
        val renderCfg = EngineConfigMapping.renderConfig(translationPreferences)

        // 解析目標頁圖檔（頂層、副檔名為圖、檔名 == pageFileName）；不存在 → 回 false。
        val img = chapterDir.listFiles()
            ?.firstOrNull { f ->
                f.isFile &&
                    (f.name?.substringAfterLast('.', "")?.lowercase() ?: "") in IMAGE_EXT &&
                    f.name == pageFileName
            }
            ?: return false
        // 素材子夾不在 → 沒東西可重繪（回 false，保留原圖）。
        val matDir = chapterDir.findFile(MATERIALS_DIR) ?: return false

        // 只開一顆 lama session（單頁重繪、跑完即釋放）。
        return Inpainter(inpaintPath, inpainterCfg).use { inpainter ->
            reRenderOnePage(matDir, img, newMethod, inpainter, renderCfg)
        }
    }

    /**
     * 產生整章的**夜讀版**（頁面本身變暗的分區重繪：白底／白泡變暗、字反白、人物原樣；離線預算、每頁 6–25 s）。
     * 對 [chapterDir] 內每頁成品圖（列頁規則與 [translateChapter] 相同：頂層圖檔、依檔名排序；`.yakuyomi/` 子夾
     * 是資料夾、被 isFile 自然濾掉）跑 [NightReadRenderer.renderTiers]（偵測 → 人物分割 → 分析一次 → 產品兩檔
     * [NightLevel.engineTiers]＝`[L2, L3]` 各合成），存成 `.yakuyomi/<頁檔名>.night.std.webp`（標準，一定有＝完成標記）
     * ＋只在與標準不同時才有的 `.night.more.webp`（更多）；**無損** WebP；檔名慣例與查找見 [NightPages]，完整頁檔名＋尾綴、
     * 不去副檔名。三檔格式（`.l1`／`.l2`／`.l3`）與舊版單檔 `<頁檔名>.night.webp` 只讀不寫，該頁新標準檔落地後刪掉。
     *
     * **resume**：已有新鮮完成標記的頁（[NightPages.markerName]：std，沒有就看三檔格式的 l1；[NightPages.isFresh]＝不比
     * 成品頁舊）而且是目前規則產生的（[NightPages.isPageOutdated]：std 旁的版本記號 ≥ [NightPages.RULES_VERSION]）直接跳過、
     * 計入 done——中斷後重跑只補剩下的；成品頁被覆寫過（重翻／重繪）的、舊規則產生的（沒有記號的兩檔格式、三檔格式、
     * 舊版單檔）重做。全章都新鮮 → 連模型都不載（省 ~150MB 載入與暖機）。每頁換檔完成後寫目前版本的記號（[writeRulesStamp]）。
     * 舊規則的頁只在這一章被排進來時才重做（章節列點月亮、設定 › 夜讀 一鍵更新、閱讀器長按）；版本變了不會自動排。
     * [forceBefore]（「以目前設定重新產生這一話」）：完成標記早於這個時間的頁也重做（換了亮度後用；三檔格式的頁也就此
     * 換成兩檔）。
     * **不覆蓋成品頁、不動 manifest**：夜讀版是另一張圖，翻譯狀態與它無關，§11 不變式自然成立。
     * **寫入順序**（[renderNightPage]）：兩檔都先寫暫存，舊檔一直留著（重做期間 reader 照樣端出舊夜讀版）；過期檢查過了
     * 才換檔：刪舊 std→more → more 暫存改名 → std 暫存改名（完成標記），再驗過期，接著刪同頁的三檔與舊版單檔。這頁沒有
     * 任何夜讀版可看的時間只剩這幾次檔案操作。reader 只認最終名、只在有 std 時才認 more，中途被殺不會端出半套。開跑先清
     * 上次殘留的暫存與永遠不會被顯示的檔（[NightPages.leftovers]：孤兒 more／l2／l3、已被 std 取代的三檔與舊版單檔）。
     * **過期競態**：每頁 decode 前記成品頁 (mtime, size)，std 改名前後各比一次——期間被覆寫（重翻／重繪／即時翻落地）就刪掉
     * 這頁所有夜讀檔、記錯、不計 done（下次補做）；否則夜讀檔 mtime 晚於覆寫、[NightPages.isFresh] 會誤判新鮮而留下舊畫面。
     * **大頁解碼**（[probeNightBounds]）：像素數 > 2×[NightReadRenderer.MAX_PIXELS] 的頁用 inSampleSize 先降到預算內
     * 再解，別把 20+ MPx 掃圖整張灌進 heap。
     *
     * **模型**：偵測器（dbnet，與翻譯共用、預設 [li.joye.yakuyomi.engine.DetectorConfig]＝sandbox 夜讀 A/B 驗過的配方）＋
     * 人物分割（yolo／cseg：一顆就單顆、兩顆聯集），整章所有頁共用一組（[NightNets]）。人物分割一顆都沒有 → 拋
     * [IllegalStateException]（訊息＝[MR.strings.nightread_missing_models]，佇列端 catch 後標 ERROR）；偵測器缺（翻譯模型
     * 沒下）同樣拋。先全部解析完才開 native handle，缺哪顆都不會留半開的。
     *
     * **併發與讓路**（[NightPump]＋[governor]）：只有夜讀在跑時一般優先權、多頁並行（上限見 [NightConcurrency]，每頁放行前
     * 再看實測剩餘 heap）、模型組多緒進全域鎖；翻譯／即時翻／reader 單頁翻譯或重繪在跑時自動降為一次一頁、nice 9，讓路
     * 持續一陣子後模型組換成 1 緒不進鎖（排空在飛頁後原地換；最早那頁也在下一次推論前／等鎖時丟回待做，免得換組被翻譯
     * 持續搶鎖拖住）。讓路時在飛不只一頁的，除最早那頁外在下一個檢查點（每次推論前、等翻譯讓出鎖時、重繪前）丟回待做。
     * 頁都跑在夜讀專用執行緒池（[NightWorkers]），不在 Dispatchers.Default／IO——那裡的執行緒翻譯也在用、不能改它們的優先權。
     * **單頁例外隔離**：某頁失敗（解碼／IO／native）記進該章 [ERRORS_FILE]（`(night)` 前綴行，見 [writeNightErrors]）、
     * 續做其他頁，不炸整章；OOM 的頁在主迴圈後以一次一頁補跑一輪，仍 OOM 才記錯。逐頁錯誤不管章怎麼結束（含開組失敗往外拋、
     * 協程被取消）都會落地。
     *
     * @param governor 夜讀的模式與放行控制（佇列 [TranslationManager] 持有的那份）。
     * @param onProgress 進度回呼（done, total）：done＝目前確認有夜讀版的頁數（含跳過的新鮮頁）、total＝章內頁數。
     *   從多條 worker 呼叫，但在同一把鎖內、done 單調遞增。
     * @param shouldStop 合作式中止（暫停/取消）：放行前後與每頁的檢查點（推論前、等鎖時、重繪前）檢查，已產生的夜讀版保留。
     * @param forceBefore >0＝完成標記（std 或三檔格式的 l1）修改時間早於此（epoch ms）的頁一律重做；0＝照一般新鮮度。
     * @return 跑完後有夜讀版的頁數（含跳過的新鮮頁；被 [shouldStop] 中途停下時沒做到的頁不計入）；有頁的版本記號寫不出來
     *   時回 0（佇列標 ERROR，見 [NightChapter.result]）。
     * @throws IllegalStateException 人物分割／偵測模型缺、此儲存位置建不出 `.yakuyomi/` 子夾（一頁都存不了），或寫不出版本
     *   記號（[probeStampWritable]，載模型之前就試）。
     */
    suspend fun renderNightChapter(
        chapterDir: UniFile,
        governor: NightGovernor,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        shouldStop: () -> Boolean = { false },
        forceBefore: Long = 0L,
    ): Int {
        // 開跑時間（列章節夾之前記）：頁檔比它新（減寬限）的頁，文字框可能是列快照之後才寫的（[NightTextBoxes.mayHaveLateBoxes]）
        val startedAt = System.currentTimeMillis()
        val listed = chapterDir.listFiles().orEmpty()
        val images = listed
            .filter { f -> f.isFile && (f.name?.substringAfterLast('.', "")?.lowercase() ?: "") in IMAGE_EXT }
            .sortedBy { it.name.orEmpty() }
        val total = images.size

        // 列一次素材夾（無素材夾＝沒東西可清、也沒有夜讀版；這裡只 find 不 create，全章新鮮時不必建夾），照
        // [NightPages.cleanupPlan] 清掉（best-effort，刪不掉的下次開跑再清）：
        //  - 上次中途被殺留下的暫存（任何夜讀檔名 + `.tmp`；reader 不認、但佔空間）。
        //  - 永遠不會被顯示的最終檔（[NightPages.leftovers]：沒有 std 的 more、沒有 l1 的 l2／l3、有 std 的頁殘留的三檔與
        //    舊版單檔——都是被殺或刪不掉留下的）。
        //  - 已經不在這章的頁留下的夜讀檔與記號（[NightPages.filesOfMissingPages]）：不刪的話章節列會一直把這章算成「可更新」
        //    （這裡只做在的頁，永遠補不到它們）。
        //  - 孤兒與被更大版本取代的規則版本記號（[NightPages.staleStamps]）。
        // 其餘夜讀最終檔與記號留在 [existing]，下面判新鮮度直接查這張表（不逐頁 findFile）。
        // 清理放在「沒有頁」的提早結束之前：頁圖都不在了、`.yakuyomi/` 還有舊夜讀檔的章，不清的話會永遠顯示「可更新」。
        val matFiles = HashMap<String, UniFile>()
        chapterDir.findFile(MATERIALS_DIR)?.listFiles()?.forEach { f -> f.name?.let { matFiles[it] = f } }
        // 「頁還在」看章節夾頂層的所有檔名（不過 isFile／副檔名）：SAF 上 isFile 是逐檔查詢，偶發失敗不該讓好好的頁被當成不在、
        // 夜讀版被刪。列出來的東西裡沒有頁圖、也沒有素材夾本身＝這次列目錄不可信（失敗時是空的），不清「不在本章的頁」——
        // 否則一次列目錄失敗會把整章的夜讀版刪光。
        val pageNames = listed.mapNotNullTo(HashSet()) { it.name }.takeIf { total > 0 || MATERIALS_DIR in it }
        val doomed = NightPages.cleanupPlan(matFiles.keys, pageNames)
        for (n in doomed) {
            runCatching { matFiles.getValue(n).delete() }
                .onFailure { logcat(LogPriority.WARN, it) { "清夜讀殘留檔失敗 $n" } }
        }
        // 已經不在這章的頁留下的夜讀文字框檔（[NightTextBoxes.orphans]；列目錄不可信時不清，同上）。只給夜讀用的檔，在這裡一起清。
        for (n in NightTextBoxes.orphans(matFiles.keys, pageNames)) {
            runCatching { matFiles.getValue(n).delete() }
                .onFailure { logcat(LogPriority.WARN, it) { "清孤兒夜讀文字框檔失敗 $n" } }
        }
        val existing = HashMap<String, UniFile>()
        for ((n, f) in matFiles) {
            if (n !in doomed && (NightPages.isFinalNightFile(n) || NightPages.isRulesStamp(n))) existing[n] = f
        }
        if (total == 0) {
            onProgress(0, 0)
            return 0
        }

        // 先分「已新鮮」與「待做」（page-level resume，[NightPages.needsRender]）：全章新鮮就不載模型。新鮮＝有完成標記
        // （std；沒有就看三檔格式的 l1）、不比頁圖舊、而且是目前規則產生的（[NightPages.isPageOutdated]：版本記號
        // < RULES_VERSION 的、只有三檔格式或舊版單檔的都算舊版——章節列標「可更新」的就是這些頁，排進來就只補它們）。
        // [forceBefore] > 0（「以目前設定重新產生」）：標記早於它的也重做。無檔名（SAF 異常）的頁既做不了也不算有。
        val kept = existing.keys
        val stamps = NightPages.rulesStamps(kept)
        val pending = ArrayList<NightJob>(total)
        var fresh = 0
        for (img in images) {
            val name = img.name ?: continue
            val todo = NightPages.needsRender(kept, name, stamps) { m ->
                val marker = existing.getValue(m)
                NightPages.isFresh(marker, img) && (forceBefore <= 0L || marker.lastModified() >= forceBefore)
            }
            if (todo) {
                // 有去字遮罩檔＝譯後頁，重繪時多帶一份遮罩（估 heap 用，見 [NightJob.hasMask]）；文字框檔順手從同一次列目錄帶上
                pending += NightJob(
                    img,
                    name,
                    hasMask = "${OriginalMaterial.base(name)}$MASK_SUFFIX" in matFiles,
                    boxes = matFiles[NightTextBoxes.fileName(name)],
                )
            } else {
                fresh++
            }
        }
        onProgress(fresh, total)
        if (pending.isEmpty()) {
            logcat { "夜讀版已齊、跳過 ${chapterDir.name}" }
            return fresh
        }

        // 素材子夾 get-or-create 一次（與 saveMaterials 同鎖：同章可能正被翻譯佇列併發建夾）。建不出來＝一頁都存不了 → 拋。
        val matDir = synchronized(materialsDirLock) {
            chapterDir.findFile(MATERIALS_DIR) ?: chapterDir.createDirectory(MATERIALS_DIR)
        } ?: throw IllegalStateException("無法建立 .yakuyomi 子夾（此儲存位置可能不支援，建議改用內部儲存）")

        // 版本記號寫得出來才開始（[NightPages.STAMP_PROBE]）：寫不出來的話做完的每頁都會一直是舊版，使用者每按一次更新
        // 整章（整庫）就重做一次。先試、不行就整章報錯（佇列標 ERROR），原因也寫進這章的錯誤檔。
        if (!probeStampWritable(matDir)) {
            val reason = "規則版本記號寫不出來（此儲存位置可能不支援這種空檔），整章沒有產生"
            writeNightErrors(chapterDir, listOf("*\t$reason"))
            throw IllegalStateException(reason)
        }

        // 模型：先全解析（缺就拋、還沒開任何 native handle），開組交給 NightPump（在 worker 上開、依模式選組）。
        val detectorPath = TranslationEngineConfig.resolveDetectorModel(context)
            ?: throw IllegalStateException("偵測模型（dbnet）缺失，無法產生夜讀版")
        val (yoloParam, csegParam) = TranslationEngineConfig.resolveCharSegModels(context)
        if (yoloParam == null && csegParam == null) {
            throw IllegalStateException(context.stringResource(MR.strings.nightread_missing_models))
        }

        val chapter = NightChapter(
            matDir = matDir,
            namesAtStart = HashSet(existing.keys),
            startedAt = startedAt,
            total = total,
            done = fresh,
            onProgress = onProgress,
            base = nightBaseParams(),
            skipColor = translationPreferences.nightReadSkipColor.get(),
            // 夜讀版存無損 WebP（暗底上有損會出色帶）：API<30 沒有 WEBP_LOSSLESS 枚舉，退舊 WEBP q100（能拿到的最高品質）。
            webpFmt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSLESS
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            },
        )
        TraceLog.log("night", "renderNightChapter.start ${chapterDir.name} pages=$total pending=${pending.size}")
        try {
            NightPump<NightJob, NightNets>(governor).run(
                pages = pending,
                open = { kind ->
                    // NightPump 開組失敗會退回另一組、只記一行進 governor.log（TraceLog，診斷紀錄預設關、沒有堆疊）；
                    // 這裡另記 logcat 帶堆疊，某台裝置 LOCKED 組一直開不起來、每章默默退回 FREE 時才查得到
                    try {
                        openNightNets(detectorPath, yoloParam, csegParam, kind, governor)
                    } catch (t: Throwable) {
                        logcat(LogPriority.WARN, t) { "夜讀開組失敗 ${kind.tag}" }
                        throw t
                    }
                },
                estimate = { job ->
                    val bounds = probeNightBounds(job.img) ?: throw IllegalStateException("頁圖解碼失敗")
                    job.bounds = bounds
                    NightConcurrency.estBytes(bounds.workPx, inpaintMask = job.hasMask)
                },
                page = { job, ticket -> renderNightPage(job, ticket, chapter) },
                onError = { job, t ->
                    TraceLog.log("night", "${job.name} EXCEPTION ${t.javaClass.simpleName}: ${t.message}")
                    logcat(LogPriority.ERROR, t) { "夜讀頁例外（已隔離、續其他頁）${job.name}" }
                    chapter.error("${job.name}\t例外 ${t.javaClass.simpleName}: ${t.message}")
                },
                onOomFinal = { job -> chapter.error("${job.name}\t記憶體不足（OutOfMemoryError），留待下次補做") },
                shouldStop = shouldStop,
            )
        } finally {
            // 章中途換組失敗往外拋（或協程被取消）時，前面各頁記的錯誤也要落地；例外照樣往外給佇列標 ERROR。
            // writeNightErrors 自己吞例外，不會蓋掉原本的例外。
            writeNightErrors(chapterDir, chapter.errors())
        }
        return chapter.result()
    }

    /**
     * 夜讀一頁的工作項：[bounds] 由 producer 放行前量好（估 heap），worker 解碼沿用同一個 inSampleSize。
     * [hasMask]：開跑時素材夾裡有這頁的去字遮罩檔（`<base>.mask.png`）＝譯後頁，重繪會多帶一份遮罩
     * （[NightConcurrency.estBytes] 的 inpaintMask）。只看檔名、不讀 json：頁已還原成原圖（不傳遮罩）時多估一點，
     * 舊版內嵌 base64 遮罩的頁少估約 1 B/px，都在估算的雜訊內。
     * [boxes]：開跑時素材夾裡這頁的夜讀文字框檔（[NightTextBoxes]，只存夜讀素材的譯後頁才有）；沒看到＝null，
     * [renderNightPage] 在沒有完整素材 json、而且這頁可能是開跑前後才翻好的時候（[NightTextBoxes.mayHaveLateBoxes]）再找一次。
     */
    private class NightJob(val img: UniFile, val name: String, val hasMask: Boolean, val boxes: UniFile?) {
        @Volatile
        var bounds: NightBounds? = null

        override fun toString() = name
    }

    /** 頁圖尺寸與解碼降採樣；[workPx]＝實際跑的像素數（解碼後、引擎再縮到 ≤ MAX_PIXELS）。 */
    private class NightBounds(val w: Int, val h: Int, val sample: Int) {
        val workPx: Long
            get() = minOf(
                ((w + sample - 1L) / sample) * ((h + sample - 1L) / sample),
                NightReadRenderer.MAX_PIXELS.toLong(),
            )
    }

    /** 夜讀一章用的模型組（偵測器＋人物分割），整章所有頁共用；換組＝整組關掉再開（見 [NightPump]）。 */
    private class NightNets(val detector: Detector, val segmenter: CharSegmenter) : AutoCloseable {
        override fun close() {
            runCatching { segmenter.close() }
            runCatching { detector.close() }
        }
    }

    /**
     * 一章夜讀的共享狀態：done／skipped／errors 同一把鎖，`done++` 與 [onProgress] 也在鎖內（多頁並行下進度單調）。
     * 鎖的順序＝這裡 → TranslationManager 的 lock（manager 不會回呼進來，不會死鎖）。
     */
    private class NightChapter(
        val matDir: UniFile,
        /**
         * 開跑時（清完殘留後）`.yakuyomi/` 的夜讀最終檔與規則版本記號檔名，唯讀。彩頁略過時判這頁有沒有舊夜讀版要刪、
         * 寫新記號後刪同頁舊記號用——都只拿來決定要不要 findFile，不用來判新鮮度。
         */
        val namesAtStart: Set<String>,
        /** 這章夜讀開跑的時間（epoch ms，列章節夾之前記），判「文字框可能是列快照之後才寫的」用（[NightTextBoxes.mayHaveLateBoxes]）。 */
        val startedAt: Long,
        val total: Int,
        private var done: Int,
        private val onProgress: (Int, Int) -> Unit,
        /** 亮度等非檔位參數；兩檔的貼紙篩選由 nightread 的 `NightTier.apply` 套上（引擎 [NightReadRenderer.renderTiers]）。 */
        val base: NightReadParams,
        val skipColor: Boolean,
        val webpFmt: Bitmap.CompressFormat,
    ) {
        /** 開跑時每頁的規則版本記號檔名（頁檔名 → 記號檔名），唯讀。 */
        val stampsAtStart: Map<String, List<String>> = namesAtStart
            .mapNotNull { n -> NightPages.parseRulesStamp(n)?.let { (page, _) -> page to n } }
            .groupBy({ it.first }, { it.second })

        private var skipped = 0
        private var stampFailures = 0
        private val errors = ArrayList<String>()

        @Synchronized
        fun pageDone(skip: Boolean) {
            done++
            if (skip) skipped++
            onProgress(done, total)
        }

        @Synchronized
        fun error(line: String) {
            errors += line
        }

        /** 某頁夜讀版換好了、版本記號卻寫不出來（[writeRulesStamp]）：記錯，整章算失敗（見 [result]）。 */
        @Synchronized
        fun stampFailed(line: String) {
            stampFailures++
            errors += line
        }

        @Synchronized
        fun errors(): List<String> = errors.toList()

        /**
         * 成功判準（佇列端的 >0）要的是「有夜讀版的頁」：只有彩頁略過、其餘全失敗 → 0 讓 drain 標 ERROR 可重試；
         * 全章彩頁且零錯誤仍回 done>0（否則永遠 ERROR）。
         * 有頁的版本記號寫不出來 → 也回 0：那幾頁的夜讀版照樣能看，但會一直顯示「可更新」；當成功離開佇列的話使用者只看得到
         * 「可更新」、看不到原因，按一次更新又整頁重做。標 ERROR 讓問題看得見，重試只補沒有記號的頁。
         */
        @Synchronized
        fun result(): Int = if (stampFailures > 0 || (done == skipped && errors.isNotEmpty())) 0 else done
    }

    /**
     * 依模型組開偵測器＋人物分割（在夜讀 worker 上、由 [NightPump] 呼叫）。LOCKED 組開好先各暖機一次：NCNN 首次推論含記憶體
     * 配置／算子初始化，要在多頁並發打進來之前做完，也別讓第一頁的計時吃掉。**FREE 組不暖機**：它只在讓路時用、一次一頁
     * （[NightConcurrency.FREE_MAX_PAGES]），沒有並發首推論的問題；暖機的 64×64 空白圖會被放大成全尺寸輸入（偵測 1024 長邊、
     * yolo 1024²、cseg 640²），在單緒、nice 9、和翻譯搶 CPU 下等於白跑一頁的推論，期間夜讀零進度。建到一半失敗會把已開的
     * 關掉再拋。模型組帶 [NightGovernor.forwardHook]：暖機與每頁的持鎖前向在讓路時照樣套 ceiling（暖機也是完整前向）；
     * 暫停／取消時暖機與頁的推論都可在等鎖期間放棄，開 LOCKED 組時讓路已久該換 FREE 也放棄暖機（見 [NightPump]；暖機自己
     * 吞掉例外，開好的組照樣回傳）。開不起來往外拋：章首 [NightPump] 會改開另一組、這章不再換組。
     */
    private fun openNightNets(
        detectorPath: String,
        yoloParam: String?,
        csegParam: String?,
        kind: NetKind,
        governor: NightGovernor,
    ): NightNets {
        val flavor = (if (kind == NetKind.FREE) NcnnFlavor.NIGHT_FREE else NcnnFlavor.NIGHT_LOCKED)
            .copy(hook = governor.forwardHook)
        val seg = NightReadRenderer.charSegmenter(yoloParam, csegParam, flavor)
            ?: throw IllegalStateException(context.stringResource(MR.strings.nightread_missing_models))
        val detector = try {
            Detector(detectorPath, flavor = flavor)
        } catch (t: Throwable) {
            seg.close()
            throw t
        }
        if (kind == NetKind.LOCKED) {
            seg.warmUp()
            detector.warmUp()
        }
        return NightNets(detector, seg)
    }

    /**
     * 夜讀一頁（在夜讀 worker 上，由 [NightPump] 呼叫）：解碼 → 彩頁略過 → 偵測／分割（每次推論前 [NightTicket.checkpoint]，
     * 引擎等翻譯讓出鎖時也會問同一個條件）→ 重繪前檢查點（[NightTicket.keep]）→ 兩檔串流寫暫存 → 過期檢查 → 換檔（刪舊、
     * 暫存改名，std 最後＝完成標記）→ 再驗過期 → 刪同頁的舊格式（三檔、舊版單檔）→ 計 done。
     * 任一檢查點不過（暫停／取消，或讓路時不是最早那頁）→ 回 [NightPageOutcome.DROPPED] 丟回待做。
     * 一般錯誤自己記進 [chapter] 回 DONE；OOM 回 [NightPageOutcome.OOM] 交給 pump 補跑。
     *
     * **寫入順序**（兩檔版；目的＝任何時刻被殺，reader 都不會把新 more 配上舊 std、也不會讀到半截檔；而且舊夜讀版留到最後
     * 一刻——重新產生期間翻頁、預載、切檔位都照樣有舊的夜讀版可看，分析／合成／編碼途中 OOM 或例外也不會讓好好的夜讀頁消失）：
     *  1. 兩檔都只寫暫存（[NightRound.write]）；更多與標準相同時引擎交 null、不寫（查找時退到標準）。舊檔一個都不動。
     *     （規格原寫「more 直接寫最終名」；這裡照現行三檔版的做法連 more 也先寫暫存：直接寫最終名會在換檔前就蓋掉舊 more、
     *     讓它跟舊 std 配成一對，舊夜讀版也不再能原封不動地留到最後一刻。）
     *  2. 交檔途中任何例外：刪掉這一輪的暫存（[NightRound.discard]），記錯；舊檔原封不動。
     *  3. 過期檢查（改名前）：已過期＝刪暫存、連舊檔一起刪（成品頁變了，舊夜讀版也是舊畫面）。
     *  4. 換檔（[NightRound.commit]）：先刪舊 std（這頁的兩檔格式「未完成」，reader 退回三檔格式、舊版單檔或原圖），再刪舊
     *     more；刪不掉 → 刪暫存、記錯、下次再做（否則殘留的舊 more 會被當成「跟新標準不同」）。舊 std 刪不掉時就停在那裡、
     *     舊 more 不碰（舊的兩檔照舊成對顯示）。接著 more 暫存改名，最後 std 暫存改名（完成標記）。三檔格式與舊版單檔這時
     *     還在，所以舊章升級時這頁一直有夜讀版可看。
     *  5. 再驗一次過期（改名後才發現＝刪掉這頁所有夜讀檔，順序見 [NightPages.artifactNames]：先刪被 std 遮住的舊格式標記、
     *     再刪 std，刪到一半不會退回同頁的舊三檔），然後刪這頁的舊格式（l1、l2、l3、舊版單檔；std 在就不會被顯示，刪不掉
     *     也無害，下次開跑由 [NightPages.leftovers] 清）。
     *  6. 寫目前的規則版本記號、刪同頁舊記號（[writeRulesStamp]）。寫不出來＝記錯、整章標失敗（[NightChapter.result]），
     *     這頁的夜讀版照樣能看、但會顯示「可更新」，重試時重做。
     * **本函式不 suspend**：效能探針的 `thr=` 量的是同一條執行緒。
     */
    private fun renderNightPage(
        job: NightJob,
        ticket: NightTicket<NightNets>,
        chapter: NightChapter,
    ): NightPageOutcome {
        val img = job.img
        val name = job.name
        val round = NightRound(chapter.matDir, name, chapter.webpFmt)
        try {
            val bounds = job.bounds ?: probeNightBounds(img) ?: throw IllegalStateException("頁圖解碼失敗")
            // 過期競態的前置快照：decode 前記成品頁 (mtime, size)，寫完後再比（見下）。
            val stamp = img.lastModified() to img.length()
            TraceLog.log("night", "$name decode.start")
            // 逐頁效能探針（見 NightPerfProbe）：分辨 CPU 限速、記憶體回收與讓路。
            val probe = NightPerfProbe.start()
            val tDecode = SystemClock.elapsedRealtime()
            val page = decodeForNight(img, bounds.sample) ?: throw IllegalStateException("頁圖解碼失敗")
            val decodeMs = SystemClock.elapsedRealtime() - tDecode
            val stats = NightReadStats()
            var extraCount = 0
            var extraFrom = "-"
            var inpaintDims = "-"
            // 讀素材時的來源與翻譯在不在跑：換檔後判「素材是不是讀完之後才寫好」用（[NightTextBoxes.appearedLate]）
            var pickSource = NightTextBoxes.Source.NONE
            var translatingAtRead = false
            try {
                if (chapter.skipColor && isColorPage(page)) {
                    // 彩頁略過：不產生（reader 顯示原圖）、算作已處理（否則整章永遠「差幾頁」）。下次 resume 會再
                    // 量一次（解碼 + 取樣很便宜），不落任何標記檔。
                    // 這頁走到這裡＝它的夜讀版（若有）已不新鮮、被強制重做、或是舊規則產生的：照目前設定彩頁不該有夜讀版，
                    // 留著它就一直是舊版（章節列永遠「可更新」、一鍵更新也補不掉），所以一起刪掉（同作廢的順序）。
                    TraceLog.log("night", "$name SKIP colour page")
                    if (NightPages.hasAnyArtifact(chapter.namesAtStart, name)) {
                        deleteNightFiles(chapter.matDir, NightPages.artifactNames(name))
                    }
                    chapter.pageDone(skip = true)
                    return NightPageOutcome.DONE
                }
                // 譯後頁：翻譯素材的原文行框併進偵測（補短譯文漏抓、拉大泡比值分母；無素材＝空），去字遮罩給「更多」的
                // 孤島規則（nightread 規則版本 4；沒有素材也沒有遮罩檔／頁已還原成原圖＝null）。素材 json 只讀一次。
                // 行框先看完整素材 json，沒有才看夜讀文字框檔（只存夜讀素材的頁，[saveNightMaterials]；先後與防過期見
                // [NightTextBoxes.pick]）。遮罩怎麼帶見 [NightTextBoxes.maskUse]：文字框檔的頁要遮罩檔大小跟檔裡記的一樣；
                // 文字框檔對不上這張頁（頁檔換過）或讀不出來＝同時寫的遮罩也拿不準，一起不帶。
                // [translating]：讀素材之前翻譯在不在跑（[TranslationBusy]；記在頁檔快照之後）。在跑＝可能有頁正在落地、素材
                // 讀完之後才寫好，換檔後要再查一次（[lateNightMaterials]）。
                val translating = TranslationBusy.active.value > 0
                val base = OriginalMaterial.base(name)
                val materials = decodeMaterials(chapter.matDir, base)
                var boxesFile: UniFile? = null
                var boxes: NightTextBoxes.Boxes? = null
                if (materials == null) {
                    boxesFile = job.boxes
                        ?: if (NightTextBoxes.mayHaveLateBoxes(translating, stamp.first, chapter.startedAt)) {
                            findNightBoxes(chapter.matDir, name)
                        } else {
                            null
                        }
                    boxes = boxesFile?.let { readNightBoxes(it, name) }
                    if (boxes == null && job.boxes != null) {
                        // 開章快照的檔案句柄讀不出來（檔被刪掉重寫過，句柄指到舊的）→ 照名字再找一次
                        boxesFile = findNightBoxes(chapter.matDir, name)
                        boxes = boxesFile?.let { readNightBoxes(it, name) }
                    }
                }
                // 開章時看到過文字框檔、現在卻找不到也算「有、但拿不準」（遮罩一起不帶），不退回只存遮罩的舊頁那條
                val boxesPresent = boxesFile != null || job.boxes != null
                val pick = NightTextBoxes.pick(materials, boxes, boxesPresent, bounds.w, bounds.h, stamp.second)
                pickSource = pick.source
                translatingAtRead = translating
                if (pick.source == NightTextBoxes.Source.STALE_BOXES) {
                    val line = if (boxes == null) {
                        "夜讀文字框檔讀不出來，文字框與遮罩都不帶"
                    } else {
                        "夜讀文字框檔與頁對不上（${boxes.width}x${boxes.height}/${boxes.pageBytes}B 對 " +
                            "${bounds.w}x${bounds.h}/${stamp.second}B），文字框與遮罩都不帶"
                    }
                    TraceLog.log("night", "$name $line")
                    logcat(LogPriority.WARN) { "夜讀頁 $name $line" }
                }
                extraFrom = pick.source.name
                val extra = nightExtraLines(pick.quads, bounds.sample)
                extraCount = extra.size
                val inpaint = nightInpaintMask(
                    chapter.matDir,
                    base,
                    materials,
                    NightTextBoxes.maskUse(pick, boxes, job.hasMask),
                    bounds.sample,
                    page,
                )
                inpaint?.let { inpaintDims = "${it.width}x${it.height}" }
                val nets = ticket.nets
                // 引擎一次分析、產品兩檔（[NightLevel.engineTiers]＝L2、L3）依序交 ARGB_8888（>3.5 MPx 自動縮、輸出縮後
                // 尺寸）；sink 回傳後引擎就 recycle，所以這裡在 sink 裡寫完。page 與 inpaint 所有權不變：inpaint 在這次呼叫
                // 一結束就 recycle（引擎重繪前已轉成自己的遮罩），page 下面自己 recycle。
                val rendered = try {
                    NightReadRenderer.renderTiers(
                        page,
                        detect = { b ->
                            ticket.checkpoint()
                            nets.detector.detect(b)
                        },
                        segment = { b ->
                            ticket.checkpoint()
                            nets.segmenter.segment(b)
                        },
                        base = chapter.base,
                        stats = stats,
                        extraLines = extra,
                        inpaintMask = inpaint,
                        // 重繪前檢查點：只問要不要繼續，不動任何檔（舊檔留到換檔那一刻，見寫入順序）
                        beforeRender = { ticket.keep() },
                        tiers = NightLevel.engineTiers,
                    ) { tier, bmp ->
                        // 1. 與前一檔相同（bmp == null；只會是更多）不存；有圖的檔位一律只寫暫存
                        if (bmp != null) {
                            val level = checkNotNull(NightLevel.ofTier(tier)) { "引擎交回非產品檔位 ${tier.key}" }
                            round.write(level, bmp)
                        }
                    }
                } catch (t: Throwable) {
                    // 2. 交檔途中出錯：這一輪的暫存全刪、舊檔沒動（推論階段出錯時本來就沒寫任何檔）
                    round.discard()
                    throw t
                } finally {
                    inpaint?.recycle()
                }
                if (!rendered) {
                    // 暫停／取消，或讓路時不是最早那頁：不寫檔、不計 done、不記錯，丟回待做（heap 由 finally 放掉）
                    TraceLog.log("night", "$name DROPPED before render ${ticket.describe()}")
                    return NightPageOutcome.DROPPED
                }
            } finally {
                page.recycle()
            }
            // 3. 過期競態：產生期間成品頁被覆寫（重翻／重繪／即時翻落地）→ 剛寫的夜讀版是舊畫面，且它的 mtime 晚於覆寫、
            // isFresh 會誤判新鮮；刪掉、記錯、不計 done，留待下次補做（覆寫端的 invalidateNightPage 可能搶在我們落地之前
            // 跑、刪不到這份，所以這裡要自己驗；換檔前驗一次＝大多數情況連完成標記都不落地）。舊檔也一起刪：成品頁變了，
            // 舊夜讀版同樣是舊畫面。
            if (stamp != (img.lastModified() to img.length())) {
                round.discard()
                deleteNightFiles(chapter.matDir, NightPages.artifactNames(name))
                return nightPageStale(chapter, name)
            }
            // 4. 換檔：刪舊 std→more（std 刪不掉就停）→ 新 more 改名 → 新 std 改名（完成標記）。舊檔刪不掉＝放棄這頁、記錯、
            // 下次再做。
            if (!round.commit()) {
                round.discard()
                TraceLog.log("night", "$name CLEAR FAILED old night files kept")
                logcat(LogPriority.WARN) { "夜讀頁 $name 舊夜讀檔刪不掉，放棄這頁、留待下次補做" }
                chapter.error("$name\t舊夜讀檔刪不掉，留待下次補做")
                return NightPageOutcome.DONE
            }
            // 5. 改名後再驗一次過期，然後刪舊格式
            if (stamp != (img.lastModified() to img.length())) {
                deleteNightFiles(chapter.matDir, NightPages.artifactNames(name))
                return nightPageStale(chapter, name)
            }
            // 5b. 素材讀完之後才寫好（同章翻譯並行：頁檔在記快照之前已被覆蓋，遮罩／文字框或完整素材 json 卻還沒寫完）→
            // 剛換好的夜讀版少了框（泡會維持灰底黑字），而且它比頁檔新、之後會一直被當成新鮮版。刪掉、留待下次補做。
            // 只在讀素材之前翻譯正在跑、而且當時沒拿到可用的框時才查（[NightTextBoxes.appearedLate]），平常不多列素材夾。
            if (translatingAtRead &&
                lateNightMaterials(chapter.matDir, name, pickSource, bounds.w, bounds.h, stamp.second)
            ) {
                deleteNightFiles(chapter.matDir, NightPages.artifactNames(name))
                return nightPageStale(chapter, name, "翻譯素材在產生期間才寫好，留待下次補做")
            }
            // 完成標記落地 → 三檔格式與舊版單檔作廢（有 std 就只讀兩檔）。刪不掉也無害：不會再被顯示，下次開跑清掉。
            deleteNightFiles(chapter.matDir, NightPages.oldFormatNames(name))
            // 6. 規則版本記號：std 落地之後才寫（中途被殺＝新頁看起來像舊版、下次多做一次，不會讓舊頁冒充新版）
            if (!writeRulesStamp(chapter, name)) {
                chapter.stampFailed("$name\t規則版本記號寫不出來（這頁會一直顯示可更新，這章標失敗、重試只補這種頁）")
            }
            val scaled = stats.scaledTo?.let { "${it.first}x${it.second}" } ?: "-"
            val line = "detect=${stats.detectMs}ms mask=${stats.maskMs}ms render=${stats.renderMs}ms " +
                "decode=${decodeMs}ms encode=${round.encodeMs}ms tiers=[${stats.tierMs.orEmpty()}] " +
                "files=${round.written()} scaledTo=$scaled extraLines=$extraCount($extraFrom) inpaint=$inpaintDims"
            val perf = "${ticket.describe()} ${probe.finish(context)} stages=[${stats.stagesMs.orEmpty()}]"
            logcat { "夜讀 $name $line $perf" }
            TraceLog.log("night", "$name done $line")
            TraceLog.log("night", "$name perf $perf")
            chapter.pageDone(skip = false)
            return NightPageOutcome.DONE
        } catch (e: NcnnForwardAbortedException) {
            // 推論前／等鎖時放棄（暫停／取消，或讓路時不是最早那頁）：同重繪前放棄，丟回待做（page 已由 finally recycle；
            // 推論階段還沒刪舊檔、也沒寫新檔）
            TraceLog.log("night", "$name DROPPED before inference ${ticket.describe()}")
            return NightPageOutcome.DROPPED
        } catch (oom: OutOfMemoryError) {
            // ★ OOM 不混進一般例外：page 已由 finally recycle、這一輪寫出的檔已由 discard 刪掉（寫檔階段的 OOM 由
            // compressToFile 吞成 false → 拋 IllegalStateException、同樣先 discard）。交給 NightPump：這章剩下的上限 1，
            // 主迴圈後補跑一輪，仍 OOM 才記錯。
            round.discard()
            TraceLog.log("night", "$name OOM ${oom.message}")
            logcat(LogPriority.ERROR, oom) { "夜讀頁記憶體不足（已隔離、稍後一次一頁補跑）$name" }
            return NightPageOutcome.OOM
        } catch (t: Throwable) {
            // ★ 單頁例外隔離（解碼／IO／native）：記錯、續其他頁，不炸整章。
            round.discard()
            TraceLog.log("night", "$name EXCEPTION ${t.javaClass.simpleName}: ${t.message}")
            logcat(LogPriority.ERROR, t) { "夜讀頁例外（已隔離、續其他頁）$name" }
            chapter.error("$name\t例外 ${t.javaClass.simpleName}: ${t.message}")
            return NightPageOutcome.DONE
        }
    }

    /**
     * 這頁夜讀版換檔完成後寫目前的規則版本記號（[NightPages.rulesStampName]，空檔；已存在＝重用，例如同版本強制重做），
     * 再刪掉開跑時列到的同頁其他版本記號（刪不掉無害：版本取同頁最大，下次開跑也會清）。回傳記號有沒有寫成。
     */
    private fun writeRulesStamp(chapter: NightChapter, page: String): Boolean {
        val stamp = NightPages.rulesStampName(page)
        val ok = createEmptyNamed(chapter.matDir, stamp)
        if (!ok) logcat(LogPriority.WARN) { "夜讀規則版本記號寫不出來 $stamp" }
        val others = chapter.stampsAtStart[page].orEmpty().filter { it != stamp }
        if (others.isNotEmpty()) deleteNightFiles(chapter.matDir, others)
        return ok
    }

    /**
     * 在 [dir] 建（或重用）名叫 [name] 的空檔，而且檔名要**一字不差**：有的儲存位置會替不認得的副檔名補副檔名、或在同名時
     * 改名成「name (1)」，那樣記號等於沒寫（查的是原名）——建出來名字不對就刪掉、回 false。例外也回 false。
     */
    private fun createEmptyNamed(dir: UniFile, name: String): Boolean = runCatching {
        val f = dir.findFile(name) ?: dir.createFile(name) ?: return@runCatching false
        if (f.name == name) {
            true
        } else {
            runCatching { f.delete() }
            false
        }
    }.onFailure { logcat(LogPriority.WARN, it) { "建空檔失敗 $name" } }.getOrDefault(false)

    /**
     * 這個素材夾寫不寫得出版本記號（[NightPages.STAMP_PROBE]：建一個同類的空檔、檢查檔名、再刪掉）。產生端在載模型之前問一次，
     * 寫不出來就整章報錯，不要做完整章才發現每頁都沒有記號。
     */
    private fun probeStampWritable(matDir: UniFile): Boolean {
        val ok = createEmptyNamed(matDir, NightPages.STAMP_PROBE)
        runCatching { matDir.findFile(NightPages.STAMP_PROBE)?.delete() }
        return ok
    }

    /** 過期競態的收尾：記錯（[reason]）、不計 done（下次補做）。呼叫端已刪掉這頁的夜讀檔。 */
    private fun nightPageStale(
        chapter: NightChapter,
        name: String,
        reason: String = "頁圖在產生期間被覆寫，留待下次補做",
    ): NightPageOutcome {
        TraceLog.log("night", "$name STALE during render, night files dropped: $reason")
        logcat(LogPriority.WARN) { "夜讀頁 $name 丟棄：$reason" }
        chapter.error("$name\t$reason")
        return NightPageOutcome.DONE
    }

    /**
     * 換檔之後再看一次素材夾（[renderNightPage] 5b）：這頁讀素材時沒拿到可用的框（[source] 是 NONE 或 STALE_BOXES），現在卻
     * 有了完整素材 json 或對得上這張頁（[pageWidth]×[pageHeight]／[pageBytes]）的文字框檔 → true（判準見
     * [NightTextBoxes.appearedLate]）。只列一次素材夾。列不出來 → false（照舊留著這頁：少了框只會讓泡維持灰底，不會塗錯）。
     */
    private fun lateNightMaterials(
        matDir: UniFile,
        name: String,
        source: NightTextBoxes.Source,
        pageWidth: Int,
        pageHeight: Int,
        pageBytes: Long,
    ): Boolean {
        if (source != NightTextBoxes.Source.NONE && source != NightTextBoxes.Source.STALE_BOXES) return false
        val files = runCatching { matDir.listFiles() }.getOrNull() ?: return false
        val jsonName = NightTextBoxes.materialsJsonName(name)
        val boxesName = NightTextBoxes.fileName(name)
        var hasJson = false
        var boxesFile: UniFile? = null
        for (f in files) {
            when (f.name) {
                jsonName -> hasJson = true
                boxesName -> boxesFile = f
            }
        }
        val boxesNow = if (hasJson) null else boxesFile?.let { readNightBoxes(it, name) }
        return NightTextBoxes.appearedLate(source, true, hasJson, boxesNow, pageWidth, pageHeight, pageBytes)
    }

    /**
     * 一頁一輪的夜讀寫檔帳（只在一條 worker 上用）：[write] 把每個檔位寫成暫存（舊檔不動），[commit] 才一口氣換檔，
     * 失敗時 [discard] 刪掉這一輪的暫存（與換檔途中已改名的 more），不留半套。
     */
    private inner class NightRound(
        private val matDir: UniFile,
        private val page: String,
        private val format: Bitmap.CompressFormat,
    ) {
        /** 已寫好暫存、還沒改名的檔位。 */
        private val tmps = ArrayList<NightLevel>(2)

        /** 換檔途中已改成最終名的 more（std 改名失敗時要拆掉，免得變成孤兒）。 */
        private val finals = ArrayList<String>(1)
        private val levels = ArrayList<String>(2)

        /** 這一輪編碼＋寫檔的總耗時（ms）。 */
        var encodeMs = 0L
            private set

        fun write(level: NightLevel, bmp: Bitmap) {
            val t = SystemClock.elapsedRealtime()
            tmps += level // 先記帳再寫：寫到一半失敗也要被 discard 清掉
            writeNightAtomic(matDir, NightPages.levelName(page, level), bmp, format, commit = false)
            levels += level.fileKey
            encodeMs += SystemClock.elapsedRealtime() - t
        }

        /**
         * 換檔：刪這頁舊的 std→more（std 先刪，reader 立刻退回三檔格式／舊版單檔／原圖、不會把新 more 配上舊 std）→ 新 more
         * 暫存改名 → 新 std 暫存改名（完成標記）。舊檔刪不掉 → 回 false（呼叫端 [discard]、記錯）；改名失敗拋
         * [IllegalStateException]（呼叫端 catch 後 [discard]）。三檔格式與舊版單檔不在這裡刪（std 落地、再驗過期之後才刪）。
         * **舊 std 刪不掉就停、不碰舊 more**（`stopOnFailure`）：否則這頁剩下「舊 std、沒有 more」——更多會退到標準（L2），
         * 而舊 std 不比頁圖舊、被當成新鮮，之後一般的產生都跳過這頁，直到手動「以目前設定重新產生」。停在這裡則舊的兩檔
         * 原封不動（照舊顯示）；std 刪掉了、more 刪不掉時，more 沒了 std 就是孤兒（reader 不認、下次開跑清掉），這頁也
         * 沒有完成標記、下次照樣重做。
         */
        fun commit(): Boolean {
            check(NightLevel.STANDARD in tmps) { "夜讀標準檔沒寫出來" }
            if (!deleteNightFiles(matDir, NightPages.currentNames(page), stopOnFailure = true)) return false
            for (level in tmps.filter { it != NightLevel.STANDARD }) {
                val finalName = NightPages.levelName(page, level)
                tmps -= level // commitNightTmp 失敗時自己刪暫存
                commitNightTmp(matDir, finalName)
                finals += finalName
            }
            tmps -= NightLevel.STANDARD
            commitNightTmp(matDir, NightPages.levelName(page, NightLevel.STANDARD))
            finals.clear() // 兩檔整組落地：之後的例外不能再拆掉 more（會變成 std 配缺檔）
            return true
        }

        /** 刪掉這一輪的暫存與換檔途中已改名的 more（best-effort）。可重複呼叫。 */
        fun discard() {
            tmps.forEach { level ->
                runCatching { matDir.findFile(NightPages.tmpName(NightPages.levelName(page, level)))?.delete() }
            }
            tmps.clear()
            finals.forEach { n -> runCatching { matDir.findFile(n)?.delete() } }
            finals.clear()
        }

        /** 寫出的檔位（log 用），例如 `std,more`。 */
        fun written(): String = levels.joinToString(",")
    }

    /**
     * 夜讀頁的尺寸與降採樣（只讀檔頭，producer 放行前用來估 heap）。像素數 > 2×[NightReadRenderer.MAX_PIXELS] 才設
     * inSampleSize（2 的冪、取最小能把解碼後像素數壓到 ≤ 2×上限的值），再交給 [NightReadRenderer.render]（它自己會再等比
     * 縮到 ≤ 上限）。目的＝別把 20+ MPx 的掃圖整張解進 heap；預算取 2× 而非 1× 是留餘裕給引擎的雙線性等比縮（品質比純
     * 2 的冪降採樣好）。縮後邊長用 ceil 估（保守）：解碼器對非整除邊長的取整因格式而異，寧可多縮一階也別超預算。
     * 開不了串流／讀不到尺寸／不是圖 → null。
     */
    private fun probeNightBounds(img: UniFile): NightBounds? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val probe = context.contentResolver.openInputStream(img.uri) ?: return null
        probe.use { BitmapFactory.decodeStream(it, null, bounds) }
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null
        val budget = 2L * NightReadRenderer.MAX_PIXELS
        var sample = 1
        while (((w + sample - 1L) / sample) * ((h + sample - 1L) / sample) > budget) sample *= 2
        if (sample > 1) TraceLog.log("night", "${img.name} ${w}x$h > 2xMAX_PIXELS -> inSampleSize=$sample")
        return NightBounds(w, h, sample)
    }

    /** 以 [probeNightBounds] 定好的 [sample] 解碼。開不了串流／解不出 → null；OOM 照拋（呼叫端單獨 catch）。 */
    private fun decodeForNight(img: UniFile, sample: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val input = context.contentResolver.openInputStream(img.uri) ?: return null
        return input.use { BitmapFactory.decodeStream(it, null, opts) }
    }

    /**
     * 設定 › 夜讀 的亮度數值 → 引擎的**基礎**參數（字 [NightReadParams.ink]／描亮邊線 [NightReadParams.edgeInk]／人物描邊
     * [NightReadParams.strokeObjV]／底色／場景亮度上限／墨線發光上限）。這裡再 clamp 一次（設定頁滑桿已限範圍，
     * 但 pref 可被匯入的備份帶進奇怪值）；發光上限鎖 < 場景上限（研究端不變式：線永遠比紙暗）。
     * 背景填黑的檔位（貼紙篩選、偽泡與亮島關閉）**不在這裡**：產生時兩檔（[NightLevel]）一律備齊，參數由 nightread 的
     * `NightTier.apply` 單一來源套上（[NightReadRenderer.renderTiers]）；偏好 `nightread_fill_level` 只決定 reader 顯示哪一檔。
     * 其餘參數維持 library 預設（改它等於改演算法）。
     */
    private fun nightBaseParams(): NightReadParams {
        val p = translationPreferences
        val bg = p.nightReadBg.get().coerceIn(0, 64)
        val dimCeil = p.nightReadDimCeil.get().coerceIn(bg + 20, 255)
        return NightReadParams(
            bg = bg,
            ink = p.nightReadInk.get().coerceIn(bg + 40, 255),
            edgeInk = p.nightReadEdgeInk.get().coerceIn(bg + 20, 255),
            strokeObjV = p.nightReadStrokeObjV.get().coerceIn(bg + 20, 255),
            dimCeil = dimCeil,
            glowCap = p.nightReadGlowCap.get().coerceIn(bg, dimCeil - 1),
        )
    }

    /**
     * 頁面是不是彩頁（「略過彩色頁」用）：每 8 px 取樣算 RGB 極差（彩度）的平均，> [COLOR_PAGE_CHROMA] 就當彩頁。
     * 黑白掃描頁經 JPEG 後彩度均值通常 < 5、淡彩底也在 10 以下；彩頁動輒 30+。只看均值：黑白頁上一小塊紅章不算彩頁。
     */
    private fun isColorPage(bmp: Bitmap): Boolean {
        val w = bmp.width
        val h = bmp.height
        val step = 8
        val cols = (w + step - 1) / step
        val row = IntArray(w)
        var sum = 0L
        var n = 0L
        var y = 0
        while (y < h) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            var x = 0
            while (x < w) {
                val c = row[x]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                sum += maxOf(r, g, b) - minOf(r, g, b)
                n++
                x += step
            }
            y += step
        }
        if (n == 0L) return false
        return sum.toDouble() / n > COLOR_PAGE_CHROMA
    }

    /**
     * 譯後頁的「額外文字行」：這頁翻譯時的原文行四邊形（[NightTextBoxes.pick] 選好、濾好的：完整素材 `.yakuyomi/<base>.json`
     * 各文字區依序攤平，沒有才用夜讀文字框檔 `<頁檔名>.nightboxes.json`，兩份內容逐點相同）→ [TextLine]（score 1f），給
     * [NightReadRenderer.render] 與偵測結果聯集。素材字框是日文原框、比譯文大，既補 DBNet 對短譯文（「咦」「是的」）的漏抓，
     * 也把「泡面積：字框長邊²」的分母拉大——真機兩章 11 顆整顆白泡全救回、零退步（nightread docs/DECISIONS「譯後頁的泡」）。
     * 頁圖若以 inSampleSize 降採樣解碼，座標同步除以 [sample]。沒有框（未翻譯的章、沒存素材、框對不上這張頁）→ 空清單、
     * 照舊只用偵測。
     */
    private fun nightExtraLines(quads: List<List<List<Float>>>, sample: Int): List<TextLine> {
        if (quads.isEmpty()) return emptyList()
        val inv = 1f / sample
        return quads.map { q -> TextLine(q.map { Pt(it[0] * inv, it[1] * inv) }, 1f) }
    }

    /**
     * 開章快照沒看到這頁的夜讀文字框檔時再找一次（夜讀開跑之後才翻好的頁；SAF 上一次 findFile＝列一次素材夾，跟每頁數秒的
     * 夜讀比可以忽略）。找不到或例外 → null。
     */
    private fun findNightBoxes(matDir: UniFile, pageFileName: String): UniFile? =
        runCatching { matDir.findFile(NightTextBoxes.fileName(pageFileName))?.takeIf { it.isFile } }.getOrNull()

    /** 讀夜讀文字框檔（[NightTextBoxes.decode]）。讀不出、解不開 → null（記 log，這頁當作沒有）。OOM 照拋。 */
    private fun readNightBoxes(file: UniFile, pageFileName: String): NightTextBoxes.Boxes? {
        val text = try {
            context.contentResolver.openInputStream(file.uri)?.use { it.bufferedReader().readText() }
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "夜讀頁 $pageFileName 文字框檔讀不出來，這頁不帶" }
            null
        } ?: return null
        return NightTextBoxes.decode(text).also { boxes ->
            if (boxes == null) logcat(LogPriority.WARN) { "夜讀頁 $pageFileName 文字框檔解不開（或版本看不懂），這頁不帶" }
        }
    }

    /**
     * 譯後頁的去字遮罩（[loadMask]：`.yakuyomi/<base>.mask.png`，舊素材退 json 內嵌）→ [NightReadRenderer.renderTiers] 的
     * inpaintMask，給 nightread「更多」的孤島規則（規則版本 4：去字區旁的小塊不塗）。以與頁圖相同的 inSampleSize（[sample]）
     * 解碼，大頁不必整張解進記憶體；引擎再跟頁一起縮到工作尺寸（最近鄰）。
     * 尺寸要與解碼後的 [page] 每邊相差 ≤ 1 px（降採樣的取整：頁 JPEG 進位、遮罩 PNG 捨去；不降採樣時兩者同尺寸）。
     * 沒有 json、只有夜讀素材的頁（沒開保留素材、夜讀開著時翻的，[saveNightMaterials]；這個改動之前的版本只存遮罩）也讀：
     * 那種頁一定是譯後頁（沒有原圖素材，還原不了）。[use]＝怎麼帶（[NightTextBoxes.maskUse]）：不帶（日文頁、文字框檔對不上
     * 或沒寫成遮罩——不 findFile，日文頁省一次 SAF 查詢）、帶但不比大小（完整素材、只存遮罩的舊頁）、遮罩檔大小要剛好是文字框
     * 檔記的（只存夜讀素材的頁；對不上＝遮罩被換過，不帶）。
     * 回 null＝與規則版本 3 相同：沒有素材、頁已還原成原圖（method＝[ORIGINAL_METHOD]：頁上是日文原圖，這份遮罩描述的
     * 去字不在這張圖上；nightread 的契約是日文頁傳 null）、沒有遮罩、大小對不上、解不開或讀檔例外（記 log、這頁照做），
     * 尺寸對不上（舊素材對不上這張頁，記 log）。OOM 照拋（[renderNightPage] 單獨處理）。回傳的 Bitmap 呼叫端用完要 recycle。
     */
    private fun nightInpaintMask(
        matDir: UniFile,
        base: String,
        materials: PageMaterials?,
        use: NightTextBoxes.MaskUse,
        sample: Int,
        page: Bitmap,
    ): Bitmap? {
        if (materials?.method == ORIGINAL_METHOD) return null
        val expectBytes = when (use) {
            NightTextBoxes.MaskUse.Skip -> return null
            NightTextBoxes.MaskUse.Unchecked -> null
            is NightTextBoxes.MaskUse.Exact -> use.bytes
        }
        val mask = try {
            loadMask(matDir, base, materials, sample, expectBytes)
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "夜讀頁 $base 去字遮罩讀不出來，這頁不帶遮罩" }
            null
        } ?: return null
        if (abs(mask.width - page.width) > 1 || abs(mask.height - page.height) > 1) {
            val line = "去字遮罩 ${mask.width}x${mask.height} 與頁 ${page.width}x${page.height}（sample=$sample）對不上，不帶遮罩"
            TraceLog.log("night", "$base $line")
            logcat(LogPriority.WARN) { "夜讀頁 $base $line" }
            mask.recycle()
            return null
        }
        return mask
    }

    /**
     * 夜讀檔**原子落地**：先把 [bmp] 壓進 [matDir] 同夾暫存名（`<finalName>.tmp`；reader 只認最終名、絕不會讀到半截檔），
     * 檢查不是空檔；[commit]＝true 再 [commitNightTmp] 改名成 [finalName]，false＝停在暫存（兩檔版一律如此：全部寫完、
     * 過期檢查過了才由 [NightRound.commit] 換檔，見 [renderNightPage]）。任一步失敗 → 刪暫存（不留孤兒）、拋 [IllegalStateException]（呼叫端記錯、續下一頁）。
     * 寫檔走 [compressToFile]（"wt" 截斷；殘留暫存被重用也不會留舊尾）。
     */
    private fun writeNightAtomic(
        matDir: UniFile,
        finalName: String,
        bmp: Bitmap,
        format: Bitmap.CompressFormat,
        commit: Boolean,
    ) {
        val tmpName = NightPages.tmpName(finalName)
        if (!compressToFile(matDir, tmpName, bmp, format, nightQuality(format))) {
            runCatching { matDir.findFile(tmpName)?.delete() }
            throw IllegalStateException("夜讀版寫檔失敗")
        }
        val tmp = matDir.findFile(tmpName) ?: throw IllegalStateException("夜讀版暫存檔寫完卻找不到")
        if (tmp.length() <= 0L) {
            // compress 回 true 但檔是空的（SAF 串流異常）：別把空檔升成最終名、reader 會端出解不開的夜讀版
            runCatching { tmp.delete() }
            throw IllegalStateException("夜讀版暫存為空")
        }
        if (commit) commitNightTmp(matDir, finalName)
    }

    /**
     * 夜讀版的壓縮參數：無損 WebP 的 quality 是「壓縮多用力」，不影響像素——50（2026-10-06；原本 100）。桌面 libwebp 1.3.2
     * 實測夜讀頁：解碼後與原圖逐像素相同（method 0–6 × quality 0／50／100 都是），編碼 681 → 285 ms／頁、檔案大 1.4%。
     * API < 30 退回的有損 WEBP 照舊 100（那裡的 quality 是畫質）。
     */
    private fun nightQuality(format: Bitmap.CompressFormat): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && format == Bitmap.CompressFormat.WEBP_LOSSLESS) 50 else 100

    /**
     * 夜讀暫存 `<finalName>.tmp` → 最終名 [finalName]：先刪既有同名最終檔再 `renameTo`。
     * 找不到暫存或改名失敗 → 刪暫存、拋 [IllegalStateException]。
     */
    private fun commitNightTmp(matDir: UniFile, finalName: String) {
        val tmpName = NightPages.tmpName(finalName)
        val tmp = matDir.findFile(tmpName) ?: throw IllegalStateException("夜讀版暫存檔不見了")
        runCatching { matDir.findFile(finalName)?.delete() }
        val renamed = runCatching { tmp.renameTo(finalName) }.getOrDefault(false)
        if (!renamed) {
            runCatching { tmp.delete() }
            throw IllegalStateException("夜讀版暫存改名失敗（暫存已刪）")
        }
    }

    /**
     * 依序刪 [matDir] 裡的夜讀最終檔 [names]（存在才刪）。呼叫端給的清單都來自 [NightPages]（[NightPages.artifactNames]／
     * [NightPages.currentNames]／[NightPages.oldFormatNames]），每種格式都是**完成標記在前**：先刪標記，這頁在那個格式立刻
     * 「未完成」，reader 不會把殘留的 more 配上別的 std、或殘留的 l2 配上別的 l1。
     * 回傳是否全部刪乾淨（不存在的算乾淨；任何一個刪不掉＝false，呼叫端決定要不要放棄這頁）。
     * [stopOnFailure]＝true：第一個刪不掉就停、後面的不碰（[NightRound.commit] 用：舊 std 還在時不能先把舊 more 刪掉）；
     * false＝盡量刪（作廢、清舊格式：能刪多少算多少）。
     */
    private fun deleteNightFiles(matDir: UniFile, names: List<String>, stopOnFailure: Boolean = false): Boolean {
        var ok = true
        for (n in names) {
            val f = runCatching { matDir.findFile(n) }.getOrNull() ?: continue
            if (!runCatching { f.delete() }.getOrDefault(false)) {
                logcat(LogPriority.WARN) { "刪夜讀檔失敗 $n" }
                ok = false
                if (stopOnFailure) break
            }
        }
        return ok
    }

    /**
     * 把夜讀的逐頁失敗寫進該章 [ERRORS_FILE]，行格式 `(night)\t<頁名>\t<原因>`（與翻譯錯誤行 `<頁名>\t<原因>` 同檔）。
     * **只替換自己的行**：翻譯錯誤行是「未標記頁待重試」的線索，不能因夜讀跑一輪就被清掉；反過來夜讀重跑也不該累積
     * 重複行。合併後為空 → 刪檔（與 [translateChapter] 的「本輪無錯清舊檔」一致）。best-effort：失敗只記 log。
     */
    private fun writeNightErrors(chapterDir: UniFile, nightErrors: List<String>) {
        runCatching {
            val existing = chapterDir.findFile(ERRORS_FILE)?.let { f ->
                context.contentResolver.openInputStream(f.uri)?.use { it.bufferedReader().readLines() }
            }.orEmpty()
            val kept = existing.filterNot { it.isBlank() || it.startsWith(NIGHT_ERROR_PREFIX) }
            val merged = kept + nightErrors.map { "$NIGHT_ERROR_PREFIX\t$it" }
            if (merged.isEmpty()) {
                chapterDir.findFile(ERRORS_FILE)?.delete()
            } else {
                overwriteBytes(chapterDir, ERRORS_FILE, merged.joinToString("\n").toByteArray())
            }
        }.onFailure { logcat(LogPriority.WARN, it) { "寫夜讀錯誤檔失敗 ${chapterDir.name}" } }
    }

    /**
     * 作廢某頁的夜讀版（兩檔、三檔格式、舊版單檔，存在才刪；順序見 [NightPages.artifactNames]：標記照查找優先序反過來刪、
     * 附屬檔最後，刪到一半不會退回同頁更舊的夜讀版）。成品頁被覆寫——翻譯落地（[translateChapter]／
     * [persistLivePage]）、換去字法重繪或還原原圖（[reRenderOnePage]）——之後，夜讀版還是**舊畫面**：reader 開夜讀模式會
     * 端出過期的暗色頁（例如成品已換新去字法、夜讀版還是舊排版；還原原圖後夜讀版卻還是譯文）。刪掉＝退回「沒夜讀版 →
     * 顯示正常版」，由開關開著的夜讀佇列（翻完自動排）或章節多選「產生夜讀版」補做。[NightPages.isFresh] 靠 mtime 也擋得住
     * 多數情況，但 SAF 的 mtime 粒度／時鐘不保證，直接刪最穩。best-effort：[matDir] 為 null（無素材夾＝不可能有夜讀版）
     * 或刪失敗只記 log。
     */
    private fun invalidateNightPage(matDir: UniFile?, pageFileName: String) {
        if (matDir == null) return
        if (!deleteNightFiles(matDir, NightPages.artifactNames(pageFileName))) {
            logcat(LogPriority.WARN) { "刪過期夜讀版失敗 $pageFileName（reader 可能端出舊夜讀畫面）" }
        }
    }

    /**
     * 重繪單頁的核心（[reRenderChapter] 逐頁迴圈 / [reRenderPage] 共用）：
     * 讀 [img] 對應的素材（json + 原圖 + 遮罩）→ lama 去字 → Renderer 排版 → 覆蓋 [img]，
     * 再把 json 的 `method` 更新為 [newMethod]。原圖素材先找原檔 `<base>.orig.<副檔名>`、沒有才找舊版
     * `<base>.orig.webp`（[OriginalMaterial.resolve]）。[newMethod]＝[ORIGINAL_METHOD] 時不去字不排版：有原檔就把位元組
     * 原樣寫回 [img]（[restoreOriginalBytes]），只有舊版 WebP 就解碼後照頁檔格式重新編碼寫回。
     *
     * 單頁包 try/catch：某頁素材壞（json 損毀/原圖缺/遮罩解不開）不該中斷整章，只記 log、回 false 略過、保留原圖。
     * 成功覆蓋回 true。
     *
     * @param matDir 章內 `.yakuyomi/` 素材子夾（呼叫端確保非 null）。
     * @param inpainter 已建好的 lama session（跨頁復用，避免逐頁重載 ~100MB）。
     */
    private suspend fun reRenderOnePage(
        matDir: UniFile,
        img: UniFile,
        newMethod: String,
        inpainter: Inpainter?,
        renderCfg: RenderConfig?,
    ): Boolean {
        val name = img.name ?: return false
        return pageLock(img).withLock { reRenderOnePageLocked(matDir, img, name, newMethod, inpainter, renderCfg) }
    }

    /** [reRenderOnePage] 的本體；呼叫時已握著這頁的頁鎖（跟翻譯落地同一頁互斥）。 */
    private suspend fun reRenderOnePageLocked(
        matDir: UniFile,
        img: UniFile,
        name: String,
        newMethod: String,
        inpainter: Inpainter?,
        renderCfg: RenderConfig?,
    ): Boolean {
        val base = name.substringBeforeLast('.')
        return try {
            val materials = decodeMaterials(matDir, base) ?: return false
            val (originalFile, byteCopy) = findOriginalMaterial(matDir, name) ?: return false

            // 原圖：用素材的原圖還原該頁（不去字/不排版）。記 method=original、保留 manifest（即時翻譯視為已處理、
            // 不會又翻回去）；檔案＝原圖。inpainter/renderCfg 此路徑用不到（呼叫端對原圖傳 null、不載 lama）。
            // 有原檔 → 位元組原樣寫回、不解碼；寫不回去才退到下面「解碼後重新編碼」那條（舊版 WebP 素材本來就走那條）。
            if (newMethod == ORIGINAL_METHOD && byteCopy && restoreOriginalBytes(originalFile, img)) {
                invalidateNightPage(matDir, name) // 成品頁換回原圖 → 舊夜讀版（暗色譯圖）作廢
                saveMaterialsMethod(matDir, base, materials, newMethod)
                return true
            }

            val original = context.contentResolver.openInputStream(originalFile.uri)
                ?.use { BitmapFactory.decodeStream(it) }
                ?: return false

            if (newMethod == ORIGINAL_METHOD) {
                writeBack(img, original)
                invalidateNightPage(matDir, name) // 成品頁換回原圖 → 舊夜讀版（暗色譯圖）作廢
                saveMaterialsMethod(matDir, base, materials, newMethod)
                original.recycle()
                return true
            }

            val mask = loadMask(matDir, base, materials)
            if (mask == null) {
                original.recycle()
                return false
            }

            val regions = regionsFromMaterials(materials)
            val cleaned = inpainter!!.inpaint(original, regions, mask) // 引擎內部 copy 輸入，不會 mutate original/mask
            val finalPage = Renderer.render(cleaned, regions, renderCfg!!, null) // 引擎內部 copy cleaned

            try {
                writeBack(img, finalPage)
            } catch (e: PageWriteInterrupted) {
                // 頁檔寫到一半（可能已被截斷）：有原檔就把它寫回去——原圖不比半截檔差（§11）；舊夜讀版跟著作廢。
                // 編碼失敗（頁檔沒被碰）不在這裡：舊的成品原封不動留著。
                if (byteCopy && restoreOriginalBytes(originalFile, img)) {
                    invalidateNightPage(matDir, name)
                    TraceLog.log("page", "$name reRender write interrupted, original restored")
                }
                throw e
            }
            invalidateNightPage(matDir, name) // 成品頁換了去字法 → 舊夜讀版作廢
            // 更新 json 的 method（其餘素材不變），保持記錄與檔案一致。
            saveMaterialsMethod(matDir, base, materials, newMethod)

            original.recycle()
            mask.recycle()
            cleaned.recycle()
            finalPage.recycle()
            true
        } catch (e: Throwable) {
            logcat(LogPriority.WARN, e) { "重繪頁失敗 $name（跳過、保留原圖）" }
            false
        }
    }

    /** 讀 `.yakuyomi/<base>.json` → [PageMaterials]；缺檔/解析失敗回 null。 */
    private fun decodeMaterials(matDir: UniFile, base: String): PageMaterials? =
        matDir.findFile("$base.json")?.let(::decodeMaterialsFile)

    /** 讀一份完整素材 json 檔 → [PageMaterials]；解析失敗回 null。 */
    private fun decodeMaterialsFile(f: UniFile): PageMaterials? {
        return runCatching {
            context.contentResolver.openInputStream(f.uri)?.use { input ->
                val text = input.bufferedReader().readText()
                // 容錯：舊版截斷 bug 可能留下「合法 json + 殘尾」→ 嚴格解析失敗時，截到根物件平衡結束再解一次（救回舊素材、免重翻）。
                runCatching { MATERIALS_JSON.decodeFromString<PageMaterials>(text) }
                    .getOrElse { MATERIALS_JSON.decodeFromString<PageMaterials>(trimToRootObject(text)) }
            }
        }.getOrNull()
    }

    /** 從字串頭掃出根 json 物件的平衡結束位置（避開字串內的括號），截掉之後殘尾。找不到平衡點則原樣回傳（交給上層判失敗）。 */
    private fun trimToRootObject(s: String): String {
        var depth = 0
        var inStr = false
        var esc = false
        for (i in s.indices) {
            val c = s[i]
            if (inStr) {
                when {
                    esc -> esc = false
                    c == '\\' -> esc = true
                    c == '"' -> inStr = false
                }
            } else {
                when (c) {
                    '"' -> inStr = true
                    '{', '[' -> depth++
                    '}', ']' -> {
                        depth--
                        if (depth == 0) return s.substring(0, i + 1)
                    }
                }
            }
        }
        return s
    }

    /**
     * 載入這頁的去字遮罩：優先讀獨立檔 `<base>.mask.png`（新格式）；找不到才退回 json 內嵌 base64（舊素材相容）。
     * 走 native stream 解碼、不把整串 base64 灌進 JVM heap。[sample] > 1＝以 inSampleSize 降採樣解碼（夜讀跟頁圖用同一個，
     * 見 [nightInpaintMask]）；1＝原尺寸（重繪）。[expectBytes] 非 null＝遮罩檔的位元組數要剛好是它（夜讀文字框檔記的），
     * 不是就回 null（記 log），也不退回 json 內嵌。
     */
    private fun loadMask(
        matDir: UniFile,
        base: String,
        materials: PageMaterials?,
        sample: Int = 1,
        expectBytes: Long? = null,
    ): Bitmap? {
        val opts = if (sample > 1) BitmapFactory.Options().apply { inSampleSize = sample } else null
        if (expectBytes != null) {
            val f = matDir.findFile("$base$MASK_SUFFIX") ?: return null
            val len = f.length()
            if (len != expectBytes) {
                val line = "去字遮罩檔 ${len}B 與夜讀文字框檔記的 ${expectBytes}B 不同（遮罩被換過），不帶遮罩"
                TraceLog.log("night", "$base $line")
                logcat(LogPriority.WARN) { "夜讀頁 $base $line" }
                return null
            }
            return context.contentResolver.openInputStream(f.uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        }
        matDir.findFile("$base$MASK_SUFFIX")?.let { f ->
            context.contentResolver.openInputStream(f.uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                ?.let { return it }
        }
        if (materials != null && materials.mask.isNotEmpty()) {
            val bytes = Base64.decode(materials.mask, Base64.NO_WRAP)
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        }
        return null
    }

    /**
     * 從 [PageMaterials] 還原引擎的 [TextRegion] 清單（重繪用，不經偵測/OCR）：
     * 逐行四邊形 → [TextLine]（score 給 1f、回填原文供 Renderer 失敗時 fallback），
     * 再以公開建構子組 [TextRegion] 並貼回譯文/onArt。x0/y0/x1/y1 由行框自動導出（無需另存）。
     */
    private fun regionsFromMaterials(materials: PageMaterials): List<TextRegion> =
        materials.regions.map { rm ->
            val lines = rm.quads.map { q -> TextLine(q.map { Pt(it[0], it[1]) }, 1f) }
            lines.firstOrNull()?.text = rm.source // 譯文空白時 Renderer 退回 sourceText
            TextRegion(
                lines = lines,
                direction = rm.direction,
                angle = rm.angle,
                cx = rm.cx,
                cy = rm.cy,
                boxW = rm.boxW,
                boxH = rm.boxH,
            ).apply {
                translatedText = rm.target
                onArt = rm.onArt
            }
        }

    /** 重繪後覆寫 `.yakuyomi/<base>.json`：只改 [PageMaterials.method]、其餘（遮罩/文字區）原樣保留。best-effort。 */
    private fun saveMaterialsMethod(matDir: UniFile, base: String, materials: PageMaterials, newMethod: String) {
        runCatching {
            val updated = materials.copy(method = newMethod)
            overwriteBytes(matDir, "$base.json", MATERIALS_JSON.encodeToString(updated).toByteArray())
        }.onFailure { logcat(LogPriority.WARN, it) { "重繪後更新 method 失敗 $base（不影響已覆蓋的頁圖）" } }
    }

    /** 該章是否已翻：manifest 涵蓋所有現有圖頁（chapterDir＝鬆散資料夾或 CBZ 解壓後暫存夾）。 */
    fun isChapterTranslated(chapterDir: UniFile): Boolean {
        val images = chapterDir.listFiles()
            ?.filter { f -> f.isFile && (f.name?.substringAfterLast('.', "")?.lowercase() ?: "") in IMAGE_EXT }
            ?.mapNotNull { it.name } ?: return false
        if (images.isEmpty()) return false
        val done = readDonePages(chapterDir)
        return images.all { it in done }
    }

    /** 讀 manifest（已處理頁名集合）。 */
    private fun readDonePages(chapterDir: UniFile): Set<String> {
        val f = chapterDir.findFile(MARKER) ?: return emptySet()
        return runCatching {
            context.contentResolver.openInputStream(f.uri)?.use { input ->
                input.bufferedReader().readLines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            }
        }.getOrNull() ?: emptySet()
    }

    /** 覆寫 manifest（每頁成功後叫一次；小檔、相對翻譯耗時可忽略，給中斷後 resume）。只給 [commitManifest] 用。 */
    private fun writeManifest(chapterDir: UniFile, done: Set<String>) {
        overwriteBytes(chapterDir, MARKER, done.joinToString("\n").toByteArray())
    }

    /**
     * manifest 的「讀-改-寫」：磁碟上現有的 ∪ [known]，寫回，回傳合併後的集合。經 [manifestMutex]（行程內所有
     * [PageTranslator] 共用一把）序列化——佇列（[translateChapter]）與 reader 單頁翻（[persistLivePage]）是不同的
     * 實例，各寫各的會互相洗掉對方剛加的頁名（被洗掉的頁之後會被當成沒翻過、拿譯圖再翻一次）。
     * [known] 帶呼叫端手上知道的全部頁名：萬一這次讀檔失敗（回空集合），也不會把它們寫丟。
     */
    private suspend fun commitManifest(chapterDir: UniFile, known: Set<String>): Set<String> =
        manifestMutex.withLock {
            val merged = readDonePages(chapterDir) + known
            writeManifest(chapterDir, merged)
            merged
        }

    /**
     * 把 [bmp]（譯圖／重繪圖／重新編碼的原圖）寫回頁檔 [file]。格式跟著副檔名走（[WriteBackFormat]：`.png` → PNG、`.webp` →
     * 有損 WebP、其他 → JPEG；JPEG 與 WebP 品質都是 [WriteBackFormat.QUALITY]）。
     * 寫入走 [openTruncating]（SAF 用 "wt"）：UniFile 的 openOutputStream 是 "w"，SAF 上不截斷，新檔比舊檔短時（`.webp` 頁改寫
     * WebP 後常見）會留一截舊尾、檔案大小也不會變小。"wt" 開不了（極少數儲存位置不支援）才退回 "w"，跟以前一樣。
     *
     * **先在記憶體裡編碼完、才開頁檔**：截斷寫一開串流就把頁檔清空了，編碼器一開始就失敗（記憶體不足、編碼器初始化失敗）的話
     * 頁檔會變成空檔。編碼失敗拋一般 IOException（頁檔一個位元組都沒碰）；開了頁檔之後才失敗拋 [PageWriteInterrupted]
     * （頁檔可能已被截斷或只寫了一半，呼叫端手上有原檔就寫回去）。一頁編碼後只有幾 MB。
     * PNG：不透明的圖先標成沒有 alpha，寫成 RGB 而不是 RGBA（引擎輸出的 bitmap 都帶 alpha 通道，檔案會白白大一截）。
     */
    private fun writeBack(file: UniFile, bmp: Bitmap) {
        val format = WriteBackFormat.forFileName(file.name)
        // 編碼器遇到超過格式上限的尺寸會直接失敗：先擋（反正下面也是先編碼才開頁檔，這裡只是讓原因清楚）。
        if (!format.fits(bmp.width, bmp.height)) {
            throw java.io.IOException("頁檔 ${file.name} 尺寸 ${bmp.width}x${bmp.height} 超過 $format 上限 ${format.maxSide}")
        }
        val fmt = when (format) {
            WriteBackFormat.PNG -> Bitmap.CompressFormat.PNG
            WriteBackFormat.WEBP -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP // 品質 < 100 就是有損
            }
            WriteBackFormat.JPEG -> Bitmap.CompressFormat.JPEG
        }
        if (format == WriteBackFormat.PNG && bmp.hasAlpha() && runCatching { isOpaque(bmp) }.getOrDefault(false)) {
            bmp.setHasAlpha(false)
        }
        val encoded = java.io.ByteArrayOutputStream(1 shl 20)
        // 編碼失敗（記憶體不足等）：頁檔還沒碰，原封不動
        if (!bmp.compress(fmt, WriteBackFormat.QUALITY, encoded)) {
            throw java.io.IOException("頁檔編碼失敗 ${file.name}")
        }
        val os = runCatching { openTruncating(file) }
            .onFailure { logcat(LogPriority.WARN, it) { "頁檔截斷寫開不了，退回一般寫入 ${file.name}" } }
            .getOrNull()
            ?: file.openOutputStream()
        try {
            os.use { encoded.writeTo(it) }
        } catch (t: Throwable) {
            throw PageWriteInterrupted("頁檔寫到一半失敗 ${file.name}", t)
        }
    }

    /** [writeBack] 開了頁檔之後才失敗（頁檔可能已被截斷或只寫了一半）。 */
    private class PageWriteInterrupted(message: String, cause: Throwable) : java.io.IOException(message, cause)

    /** 每個像素的 alpha 都是 255（一列一列讀，不另配整張的陣列）。 */
    private fun isOpaque(bmp: Bitmap): Boolean {
        val row = IntArray(bmp.width)
        for (y in 0 until bmp.height) {
            bmp.getPixels(row, 0, bmp.width, 0, y, bmp.width, 1)
            for (p in row) if (p ushr 24 != 0xFF) return false
        }
        return true
    }

    /**
     * 用譯圖覆蓋頁檔；失敗（例外或編碼不成）而手上有剛存好的原檔 [saved] 時，立刻把原檔寫回頁檔（§11：不留半截檔在
     * 那裡等下一輪），寫得回去就順手刪掉進行中記號。然後照樣把例外往外拋（呼叫端記錯、這頁不進清單）。
     * 沒有 [saved]（沒開保留素材，或原檔複製失敗）就只能原樣拋出，跟以前一樣。
     */
    private fun writeBackOrRestore(page: UniFile, bmp: Bitmap, saved: SavedOriginal?) {
        try {
            writeBack(page, bmp)
        } catch (t: Throwable) {
            if (saved != null && restoreOriginalBytes(saved.file, page)) {
                TraceLog.log("page", "${page.name} writeBack failed, original restored")
                runCatching { saved.marker?.delete() }
            }
            throw t
        }
    }

    /** [decodePageCapped] 的結果。 */
    private sealed interface PageDecode {
        class Ok(val bitmap: Bitmap) : PageDecode

        class TooLarge(val width: Int, val height: Int) : PageDecode

        class Unreadable(val reason: String) : PageDecode
    }

    /**
     * 翻譯路徑的頁圖解碼（佇列整章 [translateChapter]、reader「翻譯這頁」[translateSinglePage] 共用；即時翻與擷取都走佇列）：
     * 先用 inJustDecodeBounds 只讀檔頭量寬高（不配置像素），過了 [PageSizeCap] 才整張解碼。超過上限 → [PageDecode.TooLarge]
     * （沒解碼）；讀不出尺寸、串流開不了、解不出來 → [PageDecode.Unreadable]（帶原因）。一般例外（IO／權限）收成 Unreadable；
     * Error（OOM 等）照拋，呼叫端自己隔離。
     */
    private fun decodePageCapped(f: UniFile): PageDecode {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val opened = try {
            // inJustDecodeBounds 的 decodeStream 一律回 null，有沒有開到串流另外記
            context.contentResolver.openInputStream(f.uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
                true
            } ?: false
        } catch (e: Exception) {
            return PageDecode.Unreadable("讀不出頁圖尺寸 ${e.javaClass.simpleName}: ${e.message}")
        }
        if (!opened) return PageDecode.Unreadable("頁圖開不了讀取串流")
        return when (val v = PageSizeCap.check(bounds.outWidth, bounds.outHeight)) {
            is PageSizeCap.Verdict.TooLarge -> PageDecode.TooLarge(v.width, v.height)
            PageSizeCap.Verdict.Unreadable -> PageDecode.Unreadable("讀不出頁圖尺寸（檔案壞掉或不是支援的圖檔）")
            PageSizeCap.Verdict.Ok -> {
                val bmp = try {
                    context.contentResolver.openInputStream(f.uri)?.use { BitmapFactory.decodeStream(it) }
                } catch (e: Exception) {
                    return PageDecode.Unreadable("頁圖解碼失敗 ${e.javaClass.simpleName}: ${e.message}")
                }
                bmp?.let { PageDecode.Ok(it) } ?: PageDecode.Unreadable("頁圖解碼失敗（檔案壞掉或不是支援的圖檔）")
            }
        }
    }

    /**
     * reader 單頁路徑把一頁的失敗原因寫進該話錯誤檔（[ERRORS_FILE]，行格式同佇列：`<頁名>\t<原因>`）：同頁舊的翻譯錯誤行換成
     * 這行，別頁的行與夜讀行原樣保留。整章佇列下一輪跑完會照它自己的結果整檔重寫。best-effort：失敗只記 log。
     */
    private fun recordSinglePageError(chapterDir: UniFile, pageName: String, reason: String) {
        runCatching {
            val kept = readErrorLines(chapterDir).filterNot { it.startsWith("$pageName\t") }
            overwriteBytes(chapterDir, ERRORS_FILE, (kept + "$pageName\t$reason").joinToString("\n").toByteArray())
        }.onFailure { logcat(LogPriority.WARN, it) { "寫翻譯錯誤檔失敗 ${chapterDir.name}" } }
    }

    /** 該話錯誤檔（[ERRORS_FILE]）現有的非空行；沒有檔、讀不到 → 空。 */
    private fun readErrorLines(chapterDir: UniFile): List<String> = runCatching {
        chapterDir.findFile(ERRORS_FILE)?.let { f ->
            context.contentResolver.openInputStream(f.uri)?.use { it.bufferedReader().readLines() }
        }.orEmpty().filter { it.isNotBlank() }
    }.getOrDefault(emptyList())

    /**
     * 還沒翻過的頁進引擎之前的檢查（呼叫時握著頁鎖）：已經在清單裡 → [PageEntry.ALREADY_DONE]；
     * 上次被打斷的先復原（[recoverInterrupted]），復原不了 → [PageEntry.RECOVER_FAILED]。
     */
    private fun enterPendingPage(chapterDir: UniFile, pageFile: UniFile, name: String): PageEntry = when {
        name in readDonePages(chapterDir) -> PageEntry.ALREADY_DONE
        recoverInterrupted(chapterDir, pageFile, name) -> PageEntry.READY
        else -> PageEntry.RECOVER_FAILED
    }

    /**
     * 上次覆蓋頁檔到一半被打斷的善後（[OriginalMaterial.shouldRecover]；只給**還沒進清單**的頁用）：進行中記號在、
     * 原檔也在 → 頁檔可能是譯圖或半截檔，把原檔位元組寫回頁檔、刪記號。之後照常解碼、翻譯、重新複製原檔
     * （這時頁檔就是原檔，複製出來的內容相同）。沒有記號（絕大多數的頁）→ 什麼都不做。
     *
     * 不用「還沒進清單而原檔在就寫回」這種比較省事的判準：本機來源的頁使用者可以自己換檔，沒有記號就寫回會把
     * 使用者換上的新頁蓋成舊的。記號只在「原檔存好」到「這頁進清單」之間存在，正常跑完不會留下。
     *
     * @return false＝記號與原檔都在但寫不回頁檔（呼叫端這輪不要碰這頁；記號留著下次再試）。其餘 true。
     */
    private fun recoverInterrupted(chapterDir: UniFile, pageFile: UniFile, name: String): Boolean {
        val matDir = chapterDir.findFile(MATERIALS_DIR) ?: return true
        val marker = matDir.findFile(OriginalMaterial.inflightName(name)) ?: return true
        val original = matDir.findFile(OriginalMaterial.exactName(name))?.takeIf { it.isFile }
        val hasOriginal = original != null
        if (!OriginalMaterial.shouldRecover(inManifest = false, hasMarker = true, hasExactOriginal = hasOriginal)) {
            runCatching { marker.delete() } // 記號在、原檔不在：沒東西可寫回
            return true
        }
        if (!restoreOriginalBytes(original!!, pageFile)) {
            TraceLog.log("page", "$name interrupted overwrite: restore original FAILED")
            logcat(LogPriority.WARN) { "上次翻譯被中斷，原檔寫不回頁檔 $name" }
            return false
        }
        TraceLog.log("page", "$name interrupted overwrite: original restored")
        runCatching { marker.delete() }
        return true
    }

    /**
     * 這頁已在已翻清單裡時：頁檔若是我們自己的成品（[OriginalMaterial.pageIsOwnOutput]），回傳素材裡的原圖檔
     * （原檔或舊版 WebP 都算），否則 null（沒有素材，或頁檔被換過＝把頁檔當新的原圖）。
     */
    private fun ownOutputOriginal(chapterDir: UniFile, pageFile: UniFile, name: String): UniFile? {
        val matDir = chapterDir.findFile(MATERIALS_DIR) ?: return null
        val json = matDir.findFile(NightTextBoxes.materialsJsonName(name))?.takeIf { it.isFile } ?: return null
        val (original, _) = findOriginalMaterial(matDir, name) ?: return null
        val own = OriginalMaterial.pageIsOwnOutput(
            inManifest = true,
            hasOriginal = true,
            pageMtime = pageFile.lastModified(),
            jsonMtime = json.lastModified(),
        )
        return original.takeIf { own }
    }

    /**
     * 開「截斷寫」串流（覆寫 [f]、不留舊尾）。
     *  - **SAF** DocumentFile 用 ContentResolver "wt"：繞過 DocumentFile「w」不截斷的老問題——寫比舊檔短的內容會留舊尾，
     *    對 json 尤其致命（去字法字串 boxfill(7)/auto_whole(10) 長度不同，由 auto_whole 改回 boxfill 時 json 變短、
     *    舊尾殘留 → decodeMaterials 解析失敗顯示「無素材」）。
     *  - **file-backed** UniFile（如壓縮檔解壓到 cacheDir 的暫存）用 [UniFile.openOutputStream]（FileOutputStream 本就截斷）：
     *    實測 ContentResolver "wt" 對 file:// uri **不落地** → marker(.yakuyomi_translated)/素材寫不進暫存 →
     *    重壓的 zip 沒 marker → isChapterTranslated=false → 整章誤判失敗（翻好卻變紅）。
     * best-effort：開不了回 null。
     */
    private fun openTruncating(f: UniFile): java.io.OutputStream? =
        if (f.uri.scheme == "file") f.openOutputStream() else context.contentResolver.openOutputStream(f.uri, "wt")

    private fun overwriteBytes(dir: UniFile, name: String, bytes: ByteArray): Boolean = runCatching {
        val f = dir.findFile(name) ?: dir.createFile(name) ?: return@runCatching false
        val os = openTruncating(f) ?: return@runCatching false
        os.use { it.write(bytes) }
        true
    }.getOrDefault(false)

    /** 把 [bmp] 以 [format]/[quality] 壓進 [dir]/[name]，同樣走 "wt" 截斷（避免短輸出留舊尾）。best-effort：回傳是否成功。 */
    private fun compressToFile(
        dir: UniFile,
        name: String,
        bmp: Bitmap,
        format: Bitmap.CompressFormat,
        quality: Int,
    ): Boolean = runCatching {
        val f = dir.findFile(name) ?: dir.createFile(name) ?: return@runCatching false
        val os = openTruncating(f) ?: return@runCatching false
        // 回傳 compress 本身的結果：編碼失敗（記憶體不足、串流中途錯）會留半截檔，呼叫端要知道（夜讀原子寫入靠這判）
        os.use { bmp.compress(format, quality, it) }
    }.getOrDefault(false)

    /**
     * 章內素材子夾 `.yakuyomi/` 的 get-or-create。併發翻多頁 → 用 [materialsDirLock] 序列化，避免同時 createDirectory
     * 建出重複子夾或回 null。建不出來回 null。
     */
    private fun materialsDir(chapterDir: UniFile): UniFile? = synchronized(materialsDirLock) {
        chapterDir.findFile(MATERIALS_DIR) ?: chapterDir.createDirectory(MATERIALS_DIR)
    }

    /**
     * 章內素材子夾 `.yakuyomi/` 有沒有、沒有就建（[materialsDir]，同一把鎖）：自動排夜讀前先確定這個儲存位置放得下夜讀檔
     * （[TranslationManager] 的 canStoreNightFiles）。建不出來回 false。IO。
     */
    fun ensureMaterialsDir(chapterDir: UniFile): Boolean =
        runCatching { materialsDir(chapterDir) != null }.getOrDefault(false)

    /** [saveOriginalBytes] 存好的原檔 [file] 與它的進行中記號 [marker]（記號建不出來是 null，只是少了中斷保護）。 */
    private class SavedOriginal(val file: UniFile, val marker: UniFile?)

    /**
     * 把頁檔 [pageFile] 的**位元組原樣**複製成重繪素材的原圖 `.yakuyomi/<base>.orig.<副檔名>`（檔名見 [OriginalMaterial]）。
     * 不解碼、不重新壓縮：存下來的就是下載下來那個檔，「原圖（還原未翻）」可以一個位元組不差地寫回去。
     *
     * **一定要在頁檔被譯圖覆蓋之前呼叫**，而且頁檔這時必須真的是原圖。呼叫端（[translateChapter]／[persistLivePage]）
     * 握著頁鎖、確認過三件事才會來：這頁不是別人剛落地的譯圖（落地前重讀清單）、不是上次覆蓋到一半留下的
     * （[recoverInterrupted] 先寫回）、不是我們自己的成品（[ownOutputOriginal]，那種情況原圖素材原封不動）。
     * 通過之後才重新複製、蓋掉同名舊檔——素材反映「這次翻譯前頁檔的內容」（擷取功能重截同名頁時，要存的是新截的那張）。
     *
     * 步驟：先寫暫存 `<最終名>.tmp`、長度與來源相同才改名成最終名，改名後再確認檔名與長度（SAF 上舊檔沒刪掉時，
     * 改名可能變成另一個檔名）；然後刪掉同頁舊版的有損 WebP、建立進行中記號 `<最終名>.wip`。被殺在半路只會留下暫存
     * （讀取端不認），不會留下半截的「原圖」讓之後的還原把壞檔寫回頁檔（§11）。
     * best-effort：任何一步失敗 → 刪暫存、回 null（呼叫端退回舊做法，不擋翻譯）。
     */
    private fun saveOriginalBytes(chapterDir: UniFile, pageFile: UniFile): SavedOriginal? {
        val pageName = pageFile.name ?: return null
        val finalName = OriginalMaterial.exactName(pageName)
        val tmpName = OriginalMaterial.tmpName(pageName)
        var pendingTmp: UniFile? = null // 還沒變成最終檔的暫存（失敗時要刪）
        return runCatching {
            val d = materialsDir(chapterDir) ?: return@runCatching null
            val tmp = d.findFile(tmpName) ?: d.createFile(tmpName) ?: return@runCatching null
            pendingTmp = tmp
            val copied = context.contentResolver.openInputStream(pageFile.uri)?.use { input ->
                openTruncating(tmp)?.use { output -> input.copyTo(output) }
            } ?: return@runCatching null
            // 空檔、或寫進去的長度跟來源／落地後的長度對不上（串流中途斷掉）→ 不要
            val srcLen = pageFile.length()
            val tmpLen = tmp.length()
            if (copied <= 0L || (srcLen > 0L && srcLen != copied) || tmpLen != copied) {
                TraceLog.log("page", "$pageName orig copy length mismatch src=$srcLen copied=$copied landed=$tmpLen")
                logcat(LogPriority.WARN) {
                    "複製原檔長度不符 $pageName：來源 $srcLen、寫入 $copied、落地 $tmpLen（退回有損 WebP）"
                }
                return@runCatching null
            }
            runCatching { d.findFile(finalName)?.delete() }
            // 改名後再看一次：檔名要真的是最終名、長度要是這次複製的長度（舊檔沒刪掉時 SAF 可能改成別的檔名還回成功）。
            if (!tmp.renameTo(finalName) || tmp.name != finalName || tmp.length() != copied) {
                TraceLog.log("page", "$pageName orig rename failed -> ${tmp.name} len=${tmp.length()} want=$copied")
                logcat(LogPriority.WARN) { "原檔暫存改名失敗 $pageName → ${tmp.name}（退回有損 WebP）" }
                return@runCatching null
            }
            pendingTmp = null // 已是最終檔
            // 原檔存好了 → 同頁舊版的有損 WebP 用不到了（查找本來就先看原檔），刪掉省空間。
            OriginalMaterial.supersededLegacyName(pageName)?.let { legacy ->
                runCatching { d.findFile(legacy)?.delete() }
            }
            // 進行中記號：從這裡到這頁進清單之間被打斷，下次靠它把原檔寫回頁檔（[recoverInterrupted]）。
            val markerName = OriginalMaterial.inflightName(pageName)
            val marker = runCatching { d.findFile(markerName) ?: d.createFile(markerName) }.getOrNull()
            if (marker == null) logcat(LogPriority.WARN) { "進行中記號建不出來 $pageName（少了中斷保護，不擋翻譯）" }
            SavedOriginal(tmp, marker)
        }.onFailure { logcat(LogPriority.WARN, it) { "複製原檔到素材失敗 $pageName（退回有損 WebP）" } }
            .getOrNull()
            .also { r -> if (r == null) runCatching { pendingTmp?.delete() } }
    }

    /**
     * 找這頁的原圖素材（[OriginalMaterial.resolve]：先原檔、後舊版）。回傳（檔, 可否位元組原樣寫回）；沒有 → null。
     * 查到的檔順手留著，不再 findFile 第二次（SAF 上每次 findFile 都是一次列目錄）：新素材查一次、舊版素材查兩次。
     */
    private fun findOriginalMaterial(matDir: UniFile, pageName: String): Pair<UniFile, Boolean>? {
        val hits = HashMap<String, UniFile>(2)
        val found = OriginalMaterial.resolve(pageName) { n ->
            matDir.findFile(n)?.takeIf { it.isFile }?.also { hits[n] = it } != null
        } ?: return null
        return hits.getValue(found.name) to found.byteCopy
    }

    /**
     * 「原圖（還原未翻）」：把素材裡的原檔 [material] **位元組原樣**寫回頁檔 [page]（截斷寫，不留舊尾），頁檔就回到下載
     * 下來時的樣子。動手前先確認 [material] 解得開（只解尺寸、不佔記憶體），解不開就不碰頁檔（§11：別用壞檔蓋掉現有的頁）。
     * 寫完比長度。回傳是否成功；false 時呼叫端退到「解碼後重新編碼」那條。
     */
    private fun restoreOriginalBytes(material: UniFile, page: UniFile): Boolean = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(material.uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching false
        val copied = context.contentResolver.openInputStream(material.uri)?.use { input ->
            openTruncating(page)?.use { output -> input.copyTo(output) }
        } ?: return@runCatching false
        val landed = page.length()
        if (copied <= 0L || landed != copied) {
            logcat(LogPriority.WARN) { "原檔寫回頁檔長度不符 ${page.name}：寫 $copied、落地 $landed（改用重新編碼）" }
            return@runCatching false
        }
        true
    }.onFailure { logcat(LogPriority.WARN, it) { "原檔寫回頁檔失敗 ${page.name}" } }.getOrDefault(false)

    /**
     * 保留重繪素材（§ 換去字法重繪用）：把這頁的原圖 + seg 遮罩 + 文字區存進章內 `.yakuyomi/` 子夾，
     * 日後可不重跑 OCR/翻譯、只換去字方法重繪。**best-effort**：全程包 runCatching，存失敗只記 log、絕不擋翻譯。
     *
     * - 原圖：正常情況由呼叫端在覆蓋頁檔**之前**用 [saveOriginalBytes] 把原檔位元組原樣複製成
     *   `$base.orig.<副檔名>`，或頁檔是我們自己的成品、素材裡本來就留著原圖（[originalSaved]＝true）：這裡不碰原圖素材。
     *   [originalSaved]＝false（複製失敗）才退回舊做法：把 [original]（引擎的輸入；引擎回的是新 bitmap、不會動到它）
     *   存成有損 WebP q90 的 `$base.orig.webp`，並刪掉可能殘留的舊原檔，免得之後還原到上一次的圖。
     * - `$base.mask.png`＝二值化的去字遮罩（無損）。
     * - `$base.json`＝[PageMaterials]（去字法 + 各文字區四邊形/角度/onArt/原文/譯文/bbox）。
     *
     * 子夾放在 chapterDir 底下：reader 列頁只看頂層圖檔副檔名 → 自動忽略；也不動既有頂層 `.yakuyomi_translated` manifest。
     */
    private fun saveMaterials(
        chapterDir: UniFile,
        pageName: String,
        original: Bitmap,
        analysis: PageAnalysis,
        method: String,
        originalSaved: Boolean,
    ): String? {
        return runCatching {
            val base = OriginalMaterial.base(pageName)
            val dir = materialsDir(chapterDir)
                ?: return "素材存失敗：無法建立 .yakuyomi 子夾（此儲存位置可能不支援，建議改用內部儲存）"
            if (!originalSaved) {
                // 後備：原檔位元組沒複製成 → 照舊把解碼後的原圖存成有損 WEBP（API≥30 用 WEBP_LOSSY，否則舊 WEBP）。
                val webpFmt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Bitmap.CompressFormat.WEBP_LOSSY
                } else {
                    @Suppress("DEPRECATION")
                    Bitmap.CompressFormat.WEBP
                }
                val legacy = OriginalMaterial.legacyName(pageName)
                val exact = OriginalMaterial.exactName(pageName)
                // 上一次翻譯留下的原檔若還在，查找會先拿到它（那是上一次的圖）→ 先刪。頁檔是 .webp 時兩個檔名相同、直接蓋。
                if (exact != legacy) runCatching { dir.findFile(exact)?.delete() }
                compressToFile(dir, legacy, original, webpFmt, 90)
            }
            // 遮罩 → 獨立無損 PNG 檔（不再 base64 內嵌 json）：換去字法重繪只重寫小 json、遮罩檔不動；省 ~33% 膨脹、讀取走 native stream 不灌 heap。
            val bin = binarizeMask(analysis.mask)
            compressToFile(dir, "$base$MASK_SUFFIX", bin, Bitmap.CompressFormat.PNG, 100)
            bin.recycle()
            // 文字區 → RegionMaterial（四邊形逐行、角度、onArt、原文、譯文、bbox）。
            val regions = analysis.regions.map { region ->
                RegionMaterial(
                    quads = region.lines.map { line -> line.quad.map { p -> listOf(p.x, p.y) } },
                    angle = region.angle,
                    onArt = region.onArt,
                    source = region.sourceText,
                    target = region.translatedText,
                    bbox = listOf(region.x0, region.y0, region.x1, region.y1),
                    direction = region.direction,
                    cx = region.cx,
                    cy = region.cy,
                    boxW = region.boxW,
                    boxH = region.boxH,
                )
            }
            // mask 改存獨立檔 → materials 不帶 mask（預設空字串、不序列化進 json）。
            val materials = PageMaterials(method = method, regions = regions)
            val jsonOk = overwriteBytes(dir, "$base.json", MATERIALS_JSON.encodeToString(materials).toByteArray())
            // 完整素材接手：之前只存夜讀素材時留下的文字框檔用不到了（夜讀先看完整 json），刪掉免得留著過期的一份。
            // json 沒寫成就留著（夜讀還能退回它）。
            if (jsonOk) {
                runCatching { dir.findFile(NightTextBoxes.fileName(pageName))?.delete() }
                    .onFailure { logcat(LogPriority.WARN, it) { "刪舊夜讀文字框檔失敗 $pageName（夜讀先看完整素材，無害）" } }
            }
        }.fold(
            onSuccess = { null },
            onFailure = { e ->
                logcat(LogPriority.WARN, e) { "保留重繪素材失敗 $pageName（不影響翻譯）" }
                "素材存失敗（此儲存位置可能不支援保留素材／重繪，建議改用內部儲存）：" +
                    "${e.javaClass.simpleName}${e.message?.let { ": $it" } ?: ""}"
            },
        )
    }

    /**
     * 只存夜讀要的兩份（沒開保留素材、即時翻譯也關、夜讀開著時翻的頁用；[translateChapter] 的 nightOnly）：
     *  1. 去字遮罩 `.yakuyomi/<base>.mask.png`（同 [saveMaterials] 的遮罩檔，無損 PNG），給夜讀「更多」的去字區規則。
     *  2. 夜讀文字框檔 `.yakuyomi/<頁檔名>.nightboxes.json`（[NightTextBoxes]）：每行原文四邊形（與 [saveMaterials] 的 json
     *     各文字區 quads 依序攤平後相同）＋頁圖寬高（[pageWidth]×[pageHeight]＝引擎輸入）＋頁檔（剛寫好的譯圖 [pageFile]）
     *     的位元組數＋遮罩檔的位元組數（遮罩沒寫成＝0），夜讀拿來補泡裡短中文的偵測；寬高與頁檔位元組數防過期，遮罩位元組數
     *     防遮罩被換掉（[NightTextBoxes.maskUse]）。
     * 遮罩先寫、文字框後寫（文字框要記遮罩的大小）。
     * **不寫的情況**：
     *  - 這頁已有完整素材 json（已翻清單遺失後重翻、或同 base 另一頁存了完整素材）：遮罩是完整素材的一部分，重繪要它跟
     *    json 配對，蓋掉會讓重繪用錯遮罩；夜讀也先看 json。兩份都不動。
     *  - 讀不到頁檔的位元組數：寫了夜讀也當對不上（只比寬高擋不住同尺寸的別張頁）。文字框不寫，同頁舊的一份刪掉（不能留著
     *    冒充這張頁）；遮罩照寫（這頁沒有文字框時跟這個改動之前只存遮罩的頁一樣用）。
     * 不寫完整 json、不存原圖：重繪／還原／升級的工作清單都看完整 json（[NightTextBoxes.materialsJsonName]），這種頁不會被
     * 當成有素材。best-effort，回錯誤訊息（null＝成功），不擋翻譯。呼叫時頁檔已是譯圖（覆蓋之後）、握著頁鎖。
     */
    private fun saveNightMaterials(
        chapterDir: UniFile,
        pageFile: UniFile,
        pageName: String,
        analysis: PageAnalysis,
        pageWidth: Int,
        pageHeight: Int,
    ): String? {
        return runCatching {
            val dir = materialsDir(chapterDir)
                ?: return "夜讀素材存失敗：無法建立 .yakuyomi 子夾（此儲存位置可能不支援，建議改用內部儲存）"
            if (dir.findFile(NightTextBoxes.materialsJsonName(pageName))?.isFile == true) {
                TraceLog.log("page", "$pageName night materials skipped: full materials json present")
                return null
            }
            // 遮罩先壓成位元組（二值遮罩的 PNG 只有幾十 KB）再寫：文字框檔要記它的確切大小
            val png = binarizeMask(analysis.mask).let { bin ->
                try {
                    java.io.ByteArrayOutputStream().use { out ->
                        if (bin.compress(Bitmap.CompressFormat.PNG, 100, out)) out.toByteArray() else null
                    }
                } finally {
                    bin.recycle()
                }
            }
            val maskOk = png != null && overwriteBytes(dir, "${OriginalMaterial.base(pageName)}$MASK_SUFFIX", png)
            val boxesName = NightTextBoxes.fileName(pageName)
            val pageBytes = pageLength(pageFile)
            if (pageBytes <= 0L) {
                runCatching { dir.findFile(boxesName)?.delete() }
                logcat(LogPriority.WARN) { "夜讀文字框沒寫 $pageName：讀不到頁檔大小" }
                return if (maskOk) "夜讀文字框沒寫：讀不到頁檔大小（不影響翻譯）" else "夜讀遮罩與文字框都沒寫成（不影響翻譯）"
            }
            val quads = analysis.regions.flatMap { region ->
                region.lines.map { line -> line.quad.map { p -> listOf(p.x, p.y) } }
            }
            val maskBytes = if (maskOk) png?.size?.toLong() ?: 0L else 0L
            val boxesOk = writeExactNamed(
                dir,
                boxesName,
                NightTextBoxes.encode(pageWidth, pageHeight, pageBytes, maskBytes, quads).toByteArray(),
            )
            when {
                !maskOk && !boxesOk -> "夜讀遮罩與文字框都寫檔失敗（不影響翻譯）"
                !boxesOk -> "夜讀文字框寫檔失敗（不影響翻譯）"
                !maskOk -> "夜讀遮罩寫檔失敗（文字框照寫、夜讀不帶遮罩；不影響翻譯）"
                else -> null
            }
        }.getOrElse { e ->
            logcat(LogPriority.WARN, e) { "夜讀素材存失敗 $pageName（不影響翻譯）" }
            "夜讀素材存失敗：${e.javaClass.simpleName}${e.message?.let { ": $it" } ?: ""}"
        }
    }

    /** 頁檔位元組數；讀不到（例外或 ≤ 0）就再讀一次，還是讀不到回 0。 */
    private fun pageLength(f: UniFile): Long {
        repeat(2) {
            val len = runCatching { f.length() }.getOrDefault(0L)
            if (len > 0L) return len
        }
        return 0L
    }

    /**
     * 同 [overwriteBytes]，但建出來的檔名要**一字不差**（儲存位置同名衝突時可能建成 `name (1)`、或替副檔名補一段）：
     * 名字不對就刪掉、回 false，不留一個誰都不認、清理也清不到的檔。
     */
    private fun writeExactNamed(dir: UniFile, name: String, bytes: ByteArray): Boolean = runCatching {
        val f = dir.findFile(name) ?: dir.createFile(name) ?: return@runCatching false
        if (f.name != name) {
            logcat(LogPriority.WARN) { "建檔名字不對：要 $name、建成 ${f.name}，刪掉" }
            runCatching { f.delete() }
            return@runCatching false
        }
        val os = openTruncating(f) ?: return@runCatching false
        os.use { it.write(bytes) }
        true
    }.getOrDefault(false)

    /** 二值化 seg 遮罩（>127 白 / 否則黑）→ 新 ARGB Bitmap（呼叫端負責 recycle）。getPixels/setPixels 批次，不逐像素。 */
    private fun binarizeMask(mask: Bitmap): Bitmap {
        val w = mask.width
        val h = mask.height
        val px = IntArray(w * h)
        mask.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            px[i] = if ((px[i] and 0xFF) > 127) Color.WHITE else Color.BLACK
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { setPixels(px, 0, w, 0, 0, w, h) }
    }

    /** [enterPendingPage] 的結果。 */
    private enum class PageEntry { READY, ALREADY_DONE, RECOVER_FAILED }

    companion object {
        // 章內 manifest：已處理頁名（每行一個）＝page-level resume + 「已翻」標記。
        // 放章節內（隨 CBZ/資料夾走）：reader 依副檔名濾掉、也不會灌爆 mihon 的下載計數。
        private const val MARKER = ".yakuyomi_translated"
        private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp")

        // 重繪素材子夾（章內）：放原圖 + 遮罩/文字區 json。reader 列頁只看頂層圖檔 → 忽略此子夾。
        private const val MATERIALS_DIR = ".yakuyomi"

        /** 素材夾裡這頁去字遮罩的檔名尾綴（`<base>.mask.png`，[saveMaterials] 寫、[loadMask] 讀）。 */
        private const val MASK_SUFFIX = ".mask.png"

        /** 「略過彩色頁」的彩度門檻（RGB 極差均值，0–255）；見 [isColorPage]。 */
        private const val COLOR_PAGE_CHROMA = 18.0
        private val MATERIALS_JSON = Json { prettyPrint = false }

        /**
         * 序列化 [MATERIALS_DIR] 子夾的 get-or-create（`findFile ?: createDirectory` 是 check-then-act）。
         * 跨頁併發翻多頁時，多頁同時發現子夾不存在 → 同時 createDirectory → SAF 可能建出重複子夾/部分回 null。
         * 用 object 級鎖（跨所有 PageTranslator 實例）序列化這一小段；子夾存在後 findFile 命中、不再進 create。
         */
        private val materialsDirLock = Any()

        /**
         * 保護 manifest 的「讀-改-寫」（[commitManifest]）。放在 companion＝行程內所有 [PageTranslator] 實例共用：
         * 佇列（[TranslationManager] 的實例）與 reader 單頁翻（[ReaderViewModel] 的實例）會同時寫同一章的 manifest。
         */
        private val manifestMutex = Mutex()

        /**
         * 頁鎖（分條，行程內共用）：同一頁的「進場檢查」與「落地」（存原檔 → 覆蓋頁檔 → 存素材 → 記清單）不交錯。
         * 佇列與 reader 單頁翻／重繪可能同時碰同一頁；沒有鎖的話後到的一方會把先到一方剛寫好的譯圖當成原檔存起來。
         * 只擋同一頁（雜湊撞條的頁偶爾互等一下，無害），別頁照樣並發。拿鎖順序固定：頁鎖 → 章內狀態鎖 → manifest 鎖。
         */
        private val pageLocks = Array(32) { Mutex() }

        private fun pageLock(pageFile: UniFile): Mutex = pageLocks[pageFile.uri.hashCode().mod(pageLocks.size)]

        /**
         * 重繪的「原圖」方法：用素材的原圖還原該頁（不去字/不排版、不載 lama）；有原檔就位元組原樣寫回，只有舊版 WebP
         * 才解碼後重新編碼（見 [OriginalMaterial]）。重繪對話框「原圖」選項對應此值。
         */
        const val ORIGINAL_METHOD = "original"

        /** 逐頁翻譯失敗紀錄（章內，每行「頁名\t原因」）：某頁失敗不中止整章，只記原因供查；reader 依副檔名濾掉。 */
        private const val ERRORS_FILE = ".yakuyomi_errors.txt"

        /** [ERRORS_FILE] 內夜讀失敗行的前綴（`(night)\t頁名\t原因`）：[writeNightErrors] 靠它只替換自己的行、不動翻譯錯誤行。 */
        private const val NIGHT_ERROR_PREFIX = "(night)"
    }
}

/**
 * 一頁的重繪素材（序列化進 `.yakuyomi/<name>.json`）。配原圖（`<name>.orig.<副檔名>`，舊版 `<name>.orig.webp`，
 * 見 [OriginalMaterial]）一起，
 * 日後可不重跑 OCR/翻譯、只換去字方法重繪整頁。
 */
@Serializable
data class PageMaterials(
    /** 當初 reader 用的去字方法字串（[TranslationPreferences.inpaintMethod] 原始值），重繪比對用。 */
    val method: String,
    val regions: List<RegionMaterial>,
    /** 舊格式：二值化 seg 遮罩內嵌 base64(NO_WRAP) PNG。新格式遮罩改存獨立檔 `<base>.mask.png`、此欄留空（相容讀舊素材）。 */
    val mask: String = "",
)

/** 一個文字區的重繪素材。 */
@Serializable
data class RegionMaterial(
    /** 逐行四邊形：每行一組 [x,y] 點（各 4 點）。 */
    val quads: List<List<List<Float>>>,
    val angle: Float,
    val onArt: Boolean,
    /** OCR 原文（region.sourceText）。 */
    val source: String,
    /** 譯文（region.translatedText）。 */
    val target: String,
    /** [x0, y0, x1, y1]。 */
    val bbox: List<Float>,
    /** 排版幾何（重繪時忠實還原 Renderer 需要）：直/橫書、中心 x/y、文字框寬/高。 */
    val direction: String,
    val cx: Float,
    val cy: Float,
    val boxW: Float,
    val boxH: Float,
)
