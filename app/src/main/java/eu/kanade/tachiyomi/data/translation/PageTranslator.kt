package eu.kanade.tachiyomi.data.translation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Build
import android.util.Base64
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.crash.TraceLog
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
import li.joye.yakuyomi.engine.Inpainter
import li.joye.yakuyomi.engine.InpainterConfig
import li.joye.yakuyomi.engine.PageAnalysis
import li.joye.yakuyomi.engine.PageResult
import li.joye.yakuyomi.engine.Pt
import li.joye.yakuyomi.engine.RenderConfig
import li.joye.yakuyomi.engine.Renderer
import li.joye.yakuyomi.engine.TextLine
import li.joye.yakuyomi.engine.TextOrientation
import li.joye.yakuyomi.engine.TextRegion
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.translation.service.TranslationPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * M4 步驟 3a/3b：在 mihon 內翻譯一個章節的頁圖（就地覆蓋）。
 *
 * 模型（BYOM）：放在 **mihon「儲存位置」底下的 `models/` 子資料夾**（3 個 *.onnx）——
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

    /**
     * 保護 manifest 的「讀-改-寫」（[persistLivePage] 可能多頁併發落地）。
     * [translateChapter] 是單消費者（drainMutex 序列化）故不經此鎖；即時逐頁翻才需要它（多個 loadPage 併發持久化同章）。
     */
    private val manifestMutex = Mutex()

    /** key：優先設定頁（BYOK）；空白時 fallback build-time key（冒煙測試）。 */
    private fun apiKey(): String =
        translationPreferences.activeApiKey().ifBlank {
            // baked key 只是 DeepSeek 的冒煙測試後備；換 provider 後不套用（免拿 DeepSeek key 去打別家）。
            if (translationPreferences.provider.get() == "deepseek") BuildConfig.DEEPSEEK_API_KEY else ""
        }

    /** mihon 儲存位置（base）底下的 `models/` 子資料夾，使用者把 3 顆 onnx 放這。委派共用 [TranslationEngineConfig]。 */
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
     */
    suspend fun translateChapter(
        chapterDir: UniFile,
        method: String = translationPreferences.inpaintMethod.get(),
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        shouldStop: () -> Boolean = { false },
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
                "depth=${pipelineDepth(inpaintMethodRaw)} method=$inpaintMethodRaw keepMat=$keepMaterials",
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
                            TraceLog.log("page", "$name decode.start")
                            val bmp = context.contentResolver.openInputStream(img.uri)
                                ?.use { BitmapFactory.decodeStream(it) } ?: return@launch
                            TraceLog.log("page", "$name decoded ${bmp.width}x${bmp.height} -> engine")
                            try {
                                // 併發進引擎（EngineService 已放鬆成可並發；同章去字法相同、不重建）。§11 三態處理與循序版完全一致。
                                when (val r = engineService.translatePage(bmp, inpaintMethodRaw)) {
                                    is PageResult.Translated -> {
                                        writeBack(img, r.page) // 各頁寫各自檔、鎖外並發
                                        r.page.recycle() // 譯圖已落檔、後面用不到 → 立即回收（跨頁併發下少堆一張 bitmap，降記憶體峰值）
                                        TraceLog.log("page", "$name translated.done")
                                        // 保留重繪素材（best-effort、不擋翻譯）：bmp 為剛解碼的原圖（引擎不 mutate 輸入、回新 bitmap）。鎖外並發。
                                        val matErr = if (keepMaterials && r.analysis != null) {
                                            saveMaterials(chapterDir, name, bmp, r.analysis!!, inpaintMethodRaw)
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
                                            writeManifest(chapterDir, done)
                                        }
                                        // 推「這頁翻好了」事件 → 即時翻 loader 直接重畫該頁（不靠輪詢 manifest／queueState，
                                        // 後者是 conflated StateFlow + 慢的檔案讀 → 某頁常要等後面頁的 emit 才被順便比中、更新延遲）。
                                        onPageDone(name)
                                    }
                                    is PageResult.Skipped -> stateMutex.withLock {
                                        promptTokens += r.stats.promptTokens // 全數過濾的略過仍耗了 token（其餘 Skipped＝0）
                                        completionTokens += r.stats.completionTokens
                                        TraceLog.log("page", "$name skipped")
                                        logcat { "翻譯略過 $name：${r.reason}" }
                                        done.add(name)
                                        writeManifest(chapterDir, done) // 略過＝沒字可翻、算處理過、不重試
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
                            // 只有跑到 when 的頁（含例外頁）計進度；shouldStop/無檔名/解碼失敗提早 return 者不計（同循序版 continue 語義）。
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
        if (errors.isNotEmpty()) {
            overwriteBytes(chapterDir, ERRORS_FILE, errors.toString().toByteArray())
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
     * 失敗/略過頁不進來、下載檔維持原圖。[saveMaterials] 另把原圖存成 `orig.webp`（重繪源），不動下載檔。
     *
     * @param chapterDir 章目錄（鬆散下載夾；即時翻本里程碑只走已下載章）。
     * @param pageFile   這一頁的下載檔（會被 [translated] 覆蓋）。
     * @param original   這頁的原圖（引擎輸入；引擎回的是新 bitmap、不會動到它）——存成重繪源。
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
    ): Boolean {
        val name = pageFile.name ?: return false
        return runCatching {
            // 1. 譯圖覆蓋下載檔（§11：呼叫端保證只有成功頁才進來）。
            writeBack(pageFile, translated)
            // 2. 強制存重繪素材（即時翻不看 keepMaterials 開關；重繪需要它）。saveMaterials 本身 best-effort + "wt" 截斷安全。
            if (analysis != null) {
                saveMaterials(chapterDir, name, original, analysis, methodRaw)
            }
            // 3. 加進 manifest（page-level resume 標記）。多頁併發 → manifestMutex 序列化讀-改-寫。
            manifestMutex.withLock {
                writeManifest(chapterDir, readDonePages(chapterDir) + name)
            }
            true
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
     * @return true＝翻成功並已覆蓋落地；false＝略過/失敗/解析不到檔（原圖未動）。
     */
    suspend fun translateSinglePage(
        chapterDir: UniFile,
        pageFileName: String,
        method: String = translationPreferences.inpaintMethod.get(),
    ): Boolean {
        if (!TranslationEngineConfig.modelsResolvable(context)) return false
        // 解析目標頁圖檔（頂層、副檔名為圖、檔名 == pageFileName）。
        val pageFile = chapterDir.listFiles()
            ?.firstOrNull { f ->
                f.isFile &&
                    (f.name?.substringAfterLast('.', "")?.lowercase() ?: "") in IMAGE_EXT &&
                    f.name == pageFileName
            }
            ?: return false
        val original = context.contentResolver.openInputStream(pageFile.uri)
            ?.use { BitmapFactory.decodeStream(it) }
            ?: return false
        return try {
            when (val r = engineService.translatePage(original, method)) {
                is PageResult.Translated -> {
                    // 與佇列逐頁一致：覆蓋下載檔 + 強制存素材（重繪用）+ 記 manifest（manifestMutex 序列化）。
                    persistLivePage(chapterDir, pageFile, original, r.page, r.analysis, method)
                    r.page.recycle()
                    true
                }
                is PageResult.Skipped -> {
                    logcat { "翻譯這頁略過 $pageFileName：${r.reason}" } // 無字可翻：不覆蓋、不算成功
                    false
                }
                is PageResult.Failed -> {
                    logcat(LogPriority.WARN) { "翻譯這頁失敗 $pageFileName：${r.reason}" } // 網路/引擎：留原圖、可重試
                    false
                }
            }
        } finally {
            original.recycle()
        }
    }

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
        val jsonFile = matDir.listFiles()?.firstOrNull { f ->
            f.isFile && (f.name?.endsWith(".json") == true)
        } ?: return null
        val base = jsonFile.name?.removeSuffix(".json") ?: return null
        return decodeMaterials(matDir, base)?.method
    }

    /**
     * 換去字法重繪整章（§ 不重跑偵測/OCR/翻譯）：對 [chapterDir] 內每頁「有保留素材」的頁，
     * 用 [newMethod]（[TranslationPreferences.inpaintMethod] 原始字串）重做**去字 + 排版**、就地覆蓋頁圖。
     *
     * 復用 [saveMaterials] 存下的素材（`.yakuyomi/<base>.orig.webp` 原圖 + `<base>.json` 遮罩/文字區/譯文），
     * 故**全程無網路、無 LLM、不載偵測/OCR 模型**——只載 lama 一顆。素材缺的頁直接跳過（不毀原圖）。
     * 重繪後把 json 的 `method` 改成 [newMethod]（保持記錄與檔案一致，下次比對才準）。回傳成功重繪的頁數。
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
        // 原圖：還原整章 orig.webp，不需 lama → 早於 lama 載入處理。工作清單同去字路徑（有素材 json 的頁）。
        if (newMethod == ORIGINAL_METHOD) {
            val matDir = chapterDir.findFile(MATERIALS_DIR) ?: run {
                onProgress(0, 0)
                return 0
            }
            val workList = chapterDir.listFiles()
                ?.filter { f -> f.isFile && (f.name?.substringAfterLast('.', "")?.lowercase() ?: "") in IMAGE_EXT }
                ?.sortedBy { it.name.orEmpty() }
                ?.filter { img ->
                    val base = (img.name ?: return@filter false).substringBeforeLast('.')
                    matDir.findFile("$base.json")?.isFile == true
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

        // 去字方法（與 translateChapter 同一映射）：boxfill＝快速去字／其餘＝AI 去字（aot）。
        val method = TranslationEngineConfig.mapInpaintMethod(newMethod)

        // 進階數值：存字串、parse + clamp 到值域（與 translateChapter 完全相同的 pf/pi + pref 讀法）。
        fun pf(s: String, lo: Float, hi: Float, d: Float) = s.toFloatOrNull()?.coerceIn(lo, hi) ?: d
        fun pi(s: String, lo: Int, hi: Int, d: Int) = s.toIntOrNull()?.coerceIn(lo, hi) ?: d
        val p = translationPreferences

        // 排版方向（同 translateChapter）。
        val orient = when (p.orientation.get()) {
            "vertical" -> TextOrientation.VERTICAL
            "horizontal" -> TextOrientation.HORIZONTAL
            else -> TextOrientation.AUTO
        }

        // 去字設定：segThreshold 屬偵測器（這裡不跑偵測、遮罩直接取素材）故不需；其餘去字參數照 translateChapter。
        val inpainterCfg = InpainterConfig(
            method = method,
            bboxPad = pi(p.bboxPad.get(), 0, 64, 16),
        )
        // 排版設定：與 translateChapter 的 RenderConfig 逐欄相同。
        val renderCfg = RenderConfig(
            orientation = orient,
            fontBorder = p.fontBorder.get(),
            colorMode = if (p.colorMode.get() == "mono") "mono" else "auto",
            artStrokeRatio = pf(p.artStrokeRatio.get(), 0f, 0.5f, 0.16f),
            fontSizeMax = pi(p.fontSizeMax.get(), 20, 120, 60),
            fontSizeMin = pi(p.fontSizeMin.get(), 6, 40, 9),
            colTrim = pi(p.colTrim.get(), 0, 10, 3),
            rowTrim = pi(p.rowTrim.get(), 0, 10, 3),
            fontScale = pf(p.fontScale.get(), 0.3f, 1.5f, 0.85f),
        )

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
                val base = (img.name ?: return@filter false).substringBeforeLast('.')
                matDir.findFile("$base.json")?.isFile == true
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
     */
    suspend fun reRenderPage(
        chapterDir: UniFile,
        pageFileName: String,
        newMethod: String,
    ): Boolean {
        // 原圖：用素材的 orig.webp 還原該頁，不需 lama / 排版 → 早於 lama 載入處理（避免為還原白載 ~100MB）。
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
        // 只需去字模型（同 reRenderChapter）→ NCNN AOT 優先（.param+.bin）。模型解析委派共用 [TranslationEngineConfig]。
        val inpaintPath = TranslationEngineConfig.resolveInpaintModel(context) ?: return false

        // 去字方法映射（與 reRenderChapter/translateChapter 同一映射）。
        val method = TranslationEngineConfig.mapInpaintMethod(newMethod)

        // 進階數值（與 reRenderChapter 完全相同的讀法/clamp）。
        fun pf(s: String, lo: Float, hi: Float, d: Float) = s.toFloatOrNull()?.coerceIn(lo, hi) ?: d
        fun pi(s: String, lo: Int, hi: Int, d: Int) = s.toIntOrNull()?.coerceIn(lo, hi) ?: d
        val p = translationPreferences

        val orient = when (p.orientation.get()) {
            "vertical" -> TextOrientation.VERTICAL
            "horizontal" -> TextOrientation.HORIZONTAL
            else -> TextOrientation.AUTO
        }
        val inpainterCfg = InpainterConfig(
            method = method,
            bboxPad = pi(p.bboxPad.get(), 0, 64, 16),
        )
        val renderCfg = RenderConfig(
            orientation = orient,
            fontBorder = p.fontBorder.get(),
            colorMode = if (p.colorMode.get() == "mono") "mono" else "auto",
            artStrokeRatio = pf(p.artStrokeRatio.get(), 0f, 0.5f, 0.16f),
            fontSizeMax = pi(p.fontSizeMax.get(), 20, 120, 60),
            fontSizeMin = pi(p.fontSizeMin.get(), 6, 40, 9),
            colTrim = pi(p.colTrim.get(), 0, 10, 3),
            rowTrim = pi(p.rowTrim.get(), 0, 10, 3),
            fontScale = pf(p.fontScale.get(), 0.3f, 1.5f, 0.85f),
        )

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
     * 重繪單頁的核心（[reRenderChapter] 逐頁迴圈 / [reRenderPage] 共用）：
     * 讀 [img] 對應的素材（json + `.orig.webp` 原圖 + 遮罩）→ lama 去字 → Renderer 排版 → 覆蓋 [img]，
     * 再把 json 的 `method` 更新為 [newMethod]。
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
        val base = name.substringBeforeLast('.')
        return try {
            val materials = decodeMaterials(matDir, base) ?: return false
            val original = matDir.findFile("$base.orig.webp")
                ?.let { f ->
                    context.contentResolver.openInputStream(f.uri)?.use { BitmapFactory.decodeStream(it) }
                }
                ?: return false

            // 原圖：用素材的 orig.webp 還原該頁（不去字/不排版）。記 method=original、保留 manifest（即時翻譯視為已處理、
            // 不會又翻回去）；檔案＝原圖。inpainter/renderCfg 此路徑用不到（呼叫端對原圖傳 null、不載 lama）。
            if (newMethod == ORIGINAL_METHOD) {
                writeBack(img, original)
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

            writeBack(img, finalPage)
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
    private fun decodeMaterials(matDir: UniFile, base: String): PageMaterials? {
        val f = matDir.findFile("$base.json") ?: return null
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
     * 走 native stream 解碼、不把整串 base64 灌進 JVM heap。
     */
    private fun loadMask(matDir: UniFile, base: String, materials: PageMaterials): Bitmap? {
        matDir.findFile("$base.mask.png")?.let { f ->
            context.contentResolver.openInputStream(f.uri)?.use { BitmapFactory.decodeStream(it) }?.let { return it }
        }
        if (materials.mask.isNotEmpty()) {
            val bytes = Base64.decode(materials.mask, Base64.NO_WRAP)
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
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

    /** 覆寫 manifest（每頁成功後叫一次；小檔、相對翻譯耗時可忽略，給中斷後 resume）。 */
    private fun writeManifest(chapterDir: UniFile, done: Set<String>) {
        overwriteBytes(chapterDir, MARKER, done.joinToString("\n").toByteArray())
    }

    private fun writeBack(file: UniFile, bmp: Bitmap) {
        val fmt = if (file.name?.endsWith(".png", ignoreCase = true) == true) {
            Bitmap.CompressFormat.PNG
        } else {
            Bitmap.CompressFormat.JPEG
        }
        file.openOutputStream().use { bmp.compress(fmt, 92, it) }
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
        os.use { bmp.compress(format, quality, it) }
        true
    }.getOrDefault(false)

    /**
     * 保留重繪素材（§ 換去字法重繪用）：把這頁的原圖 + seg 遮罩 + 文字區存進章內 `.yakuyomi/` 子夾，
     * 日後可不重跑 OCR/翻譯、只換去字方法重繪。**best-effort**：全程包 runCatching，存失敗只記 log、絕不擋翻譯。
     *
     * - `$base.orig.webp`＝原圖（[original]，引擎的輸入；引擎回的是新 bitmap、不會動到它）。WEBP 有損 q90。
     * - `$base.json`＝[PageMaterials]（去字法 + base64 PNG 二值遮罩 + 各文字區四邊形/角度/onArt/原文/譯文/bbox）。
     *
     * 子夾放在 chapterDir 底下：reader 列頁只看頂層圖檔副檔名 → 自動忽略；也不動既有頂層 `.yakuyomi_translated` manifest。
     */
    private fun saveMaterials(
        chapterDir: UniFile,
        pageName: String,
        original: Bitmap,
        analysis: PageAnalysis,
        method: String,
    ): String? {
        return runCatching {
            val base = pageName.substringBeforeLast('.')
            // 併發翻多頁 → 序列化子夾的 get-or-create（見 [materialsDirLock]），避免同時 createDirectory 建重複/回 null。
            val dir = synchronized(materialsDirLock) {
                chapterDir.findFile(MATERIALS_DIR) ?: chapterDir.createDirectory(MATERIALS_DIR)
            } ?: return "素材存失敗：無法建立 .yakuyomi 子夾（此儲存位置可能不支援，建議改用內部儲存）"
            // 原圖 → WEBP（API≥30 用 WEBP_LOSSY，否則舊 WEBP）。
            val webpFmt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }
            compressToFile(dir, "$base.orig.webp", original, webpFmt, 90)
            // 遮罩 → 獨立無損 PNG 檔（不再 base64 內嵌 json）：換去字法重繪只重寫小 json、遮罩檔不動；省 ~33% 膨脹、讀取走 native stream 不灌 heap。
            val bin = binarizeMask(analysis.mask)
            compressToFile(dir, "$base.mask.png", bin, Bitmap.CompressFormat.PNG, 100)
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
            overwriteBytes(dir, "$base.json", MATERIALS_JSON.encodeToString(materials).toByteArray())
        }.fold(
            onSuccess = { null },
            onFailure = { e ->
                logcat(LogPriority.WARN, e) { "保留重繪素材失敗 $pageName（不影響翻譯）" }
                "素材存失敗（此儲存位置可能不支援保留素材／重繪，建議改用內部儲存）：" +
                    "${e.javaClass.simpleName}${e.message?.let { ": $it" } ?: ""}"
            },
        )
    }

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

    companion object {
        // 章內 manifest：已處理頁名（每行一個）＝page-level resume + 「已翻」標記。
        // 放章節內（隨 CBZ/資料夾走）：reader 依副檔名濾掉、也不會灌爆 mihon 的下載計數。
        private const val MARKER = ".yakuyomi_translated"
        private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp")

        // 重繪素材子夾（章內）：放原圖 + 遮罩/文字區 json。reader 列頁只看頂層圖檔 → 忽略此子夾。
        private const val MATERIALS_DIR = ".yakuyomi"
        private val MATERIALS_JSON = Json { prettyPrint = false }

        /**
         * 序列化 [MATERIALS_DIR] 子夾的 get-or-create（`findFile ?: createDirectory` 是 check-then-act）。
         * 跨頁併發翻多頁時，多頁同時發現子夾不存在 → 同時 createDirectory → SAF 可能建出重複子夾/部分回 null。
         * 用 object 級鎖（跨所有 PageTranslator 實例）序列化這一小段；子夾存在後 findFile 命中、不再進 create。
         */
        private val materialsDirLock = Any()

        /** 重繪的「原圖」方法：用素材 orig.webp 還原該頁（不去字/不排版、不載 lama）。重繪對話框「原圖」選項對應此值。 */
        const val ORIGINAL_METHOD = "original"

        /** 逐頁翻譯失敗紀錄（章內，每行「頁名\t原因」）：某頁失敗不中止整章，只記原因供查；reader 依副檔名濾掉。 */
        private const val ERRORS_FILE = ".yakuyomi_errors.txt"
    }
}

/**
 * 一頁的重繪素材（序列化進 `.yakuyomi/<name>.json`）。配 `<name>.orig.webp`（原圖）一起，
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
