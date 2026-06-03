package eu.kanade.tachiyomi.data.translation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.net.toUri
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.BuildConfig
import li.joye.yakuyomi.engine.EngineConfig
import li.joye.yakuyomi.engine.InpainterConfig
import li.joye.yakuyomi.engine.ModelSet
import li.joye.yakuyomi.engine.PageResult
import li.joye.yakuyomi.engine.RenderConfig
import li.joye.yakuyomi.engine.TextOrientation
import li.joye.yakuyomi.engine.TranslatorConfig
import li.joye.yakuyomi.engine.Yakuyomi
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.storage.service.StoragePreferences
import tachiyomi.domain.translation.service.TranslationPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

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

    private val storagePreferences: StoragePreferences = Injekt.get()
    private val translationPreferences: TranslationPreferences = Injekt.get()

    /** key：優先設定頁（BYOK）；空白時 fallback build-time key（冒煙測試）。 */
    private fun apiKey(): String =
        translationPreferences.apiKey.get().ifBlank { BuildConfig.DEEPSEEK_API_KEY }

    /** mihon 儲存位置（base）底下的 `models/` 子資料夾，使用者把 3 顆 onnx 放這。 */
    private fun modelsDir(): UniFile? {
        val base = storagePreferences.baseStorageDirectory.get().takeIf { it.isNotBlank() } ?: return null
        return UniFile.fromUri(context, base.toUri())?.findFile(MODELS_DIR)
    }

    /** 翻譯開關開 + key 有設 + 模型 3 顆齊，才翻得了（給下載 hook 判斷）。 */
    fun isReady(): Boolean {
        if (!translationPreferences.translationEnabled.get()) return false
        if (apiKey().isBlank()) return false
        val m = modelsDir() ?: return false
        return findOnnx(m, "detect", "comictext") != null &&
            findOnnx(m, "ocr") != null &&
            findOnnx(m, "lama") != null
    }

    /**
     * 翻譯 [chapterDir]（UniFile，相容本機/SAF）內所有頁圖、就地覆蓋成功的頁。
     * page-level resume（§11）：跳過 manifest 已記的頁、只補沒翻的；每頁成功就更新 manifest，
     * 中斷後重跑只補剩下的。回傳這次新翻成功的頁數。
     */
    suspend fun translateChapter(chapterDir: UniFile): Int {
        val m = modelsDir() ?: return 0
        val detU = findOnnx(m, "detect", "comictext") ?: return 0
        val ocrU = findOnnx(m, "ocr") ?: return 0
        val lamaU = findOnnx(m, "lama") ?: return 0
        val images = chapterDir.listFiles()
            ?.filter { f -> f.isFile && (f.name?.substringAfterLast('.', "")?.lowercase() ?: "") in IMAGE_EXT }
            ?.sortedBy { it.name.orEmpty() }
            ?.takeIf { it.isNotEmpty() } ?: return 0

        // resume：manifest 已處理過的頁跳過
        val done = readDonePages(chapterDir).toMutableSet()
        val pending = images.filter { (it.name ?: "") !in done }
        if (pending.isEmpty()) {
            logcat { "已翻過、跳過 ${chapterDir.name}" }
            return 0
        }

        val alphabet = context.assets.open(ALPHABET).bufferedReader().use { it.readLines() }
        val models = ModelSet(ensureLocal(detU), ensureLocal(ocrU), ensureLocal(lamaU))
        // 語言對從設定頁讀（預設日→繁中）。改了目標語言就清掉引擎內建的日→繁中 few-shot，
        // 免得範例語言（繁中）跟新目標衝突、把輸出帶偏。
        val target = translationPreferences.targetLangName.get()
        var translatorCfg = TranslatorConfig(
            toLangName = target,
            fromLangName = translationPreferences.sourceLangName.get(),
        )
        if (target != TranslationPreferences.DEFAULT_TARGET_LANG) {
            translatorCfg = translatorCfg.copy(sampleSource = "", sampleTarget = "")
        }

        // 排版方向
        val orient = when (translationPreferences.orientation.get()) {
            "vertical" -> TextOrientation.VERTICAL
            "horizontal" -> TextOrientation.HORIZONTAL
            else -> TextOrientation.AUTO
        }

        // 去字方法
        val (method, whole) = when (translationPreferences.inpaintMethod.get()) {
            "boxfill" -> "boxfill" to true
            "auto_whole" -> "auto" to true
            "lama_whole" -> "lama" to true
            "lama_tile" -> "lama" to false
            else -> "auto" to false // auto_tile（預設）
        }

        val cfg = EngineConfig(
            translator = translatorCfg,
            inpainter = InpainterConfig(method = method, wholeImage = whole),
            render = RenderConfig(orientation = orient),
        )

        var translated = 0
        try {
            // 工廠取引擎、`use { }` 自動釋放三顆模型
            Yakuyomi.create(models, alphabet, apiKey(), cfg).use { engine ->
                for (img in pending) {
                    val name = img.name ?: continue
                    val bmp = context.contentResolver.openInputStream(img.uri)
                        ?.use { BitmapFactory.decodeStream(it) } ?: continue
                    when (val r = engine.translatePage(bmp)) {
                        is PageResult.Translated -> {
                            writeBack(img, r.page); translated++
                            done.add(name); writeManifest(chapterDir, done)
                        }
                        is PageResult.Skipped -> {
                            logcat { "翻譯略過 $name：${r.reason}" }
                            done.add(name); writeManifest(chapterDir, done) // 略過＝沒字可翻、算處理過、不重試
                        }
                        is PageResult.Failed ->
                            logcat(LogPriority.WARN) { "翻譯失敗 $name：${r.reason}" } // 不標記、下次重試
                    }
                    bmp.recycle()
                }
            }
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "translateChapter 例外（保留原圖）" }
        }
        return translated
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
        val f = chapterDir.findFile(MARKER) ?: chapterDir.createFile(MARKER) ?: return
        runCatching {
            f.openOutputStream().use { it.write(done.joinToString("\n").toByteArray()) }
        }
    }

    private fun writeBack(file: UniFile, bmp: Bitmap) {
        val fmt = if (file.name?.endsWith(".png", ignoreCase = true) == true) {
            Bitmap.CompressFormat.PNG
        } else {
            Bitmap.CompressFormat.JPEG
        }
        file.openOutputStream().use { bmp.compress(fmt, 92, it) }
    }

    /** SAF 模型串流複製到 filesDir（64KB、不佔 JVM heap），回傳路徑；已存在且同大小則跳過。 */
    private fun ensureLocal(doc: UniFile): String {
        val name = doc.name ?: "model.onnx"
        val out = File(context.filesDir, name)
        if (out.exists() && out.length() == doc.length()) return out.absolutePath
        context.contentResolver.openInputStream(doc.uri)!!.use { input ->
            out.outputStream().use { input.copyTo(it, 1 shl 16) }
        }
        return out.absolutePath
    }

    private fun findOnnx(dir: UniFile, vararg keywords: String): UniFile? =
        dir.listFiles()?.firstOrNull { f ->
            val n = f.name?.lowercase() ?: return@firstOrNull false
            n.endsWith(".onnx") && keywords.any { n.contains(it) }
        }

    companion object {
        private const val MODELS_DIR = "models"
        private const val ALPHABET = "models/alphabet-all-v5.txt"
        // 章內 manifest：已處理頁名（每行一個）＝page-level resume + 「已翻」標記。
        // 放章節內（隨 CBZ/資料夾走）：reader 依副檔名濾掉、也不會灌爆 mihon 的下載計數。
        private const val MARKER = ".yakuyomi_translated"
        private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp")
    }
}
