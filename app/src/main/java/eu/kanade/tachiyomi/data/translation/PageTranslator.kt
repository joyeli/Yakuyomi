package eu.kanade.tachiyomi.data.translation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.net.toUri
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.BuildConfig
import li.joye.yakuyomi.engine.Detector
import li.joye.yakuyomi.engine.EngineConfig
import li.joye.yakuyomi.engine.Inpainter
import li.joye.yakuyomi.engine.LlmTranslator
import li.joye.yakuyomi.engine.Ocr
import li.joye.yakuyomi.engine.PageResult
import li.joye.yakuyomi.engine.Pipeline
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.storage.service.StoragePreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

/**
 * M4 步驟 3a：在 mihon 內翻譯一個章節的頁圖（就地覆蓋）。
 *
 * 模型（BYOM）：放在 **mihon「儲存位置」底下的 `models/` 子資料夾**（3 個 *.onnx）——
 *   跟下載/翻譯後的檔案同一個地方（使用者把儲存位置設成 OneDrive，模型/下載/譯檔就全在雲端）。
 *   走 SAF 讀取、串流複製到 filesDir 後以路徑 off-heap 載入（[ensureLocal]）。
 * key（BYOK）：[BuildConfig.DEEPSEEK_API_KEY]（冒煙測試用；正式版走設定頁 + Keystore）。
 * 字型：null → 系統 CJK fallback。
 * §11：僅成功的頁覆蓋；略過/失敗留原圖。回傳翻成功頁數。
 */
class PageTranslator(private val context: Context) {

    private val storagePreferences: StoragePreferences = Injekt.get()

    /** mihon 儲存位置（base）底下的 `models/` 子資料夾，使用者把 3 顆 onnx 放這。 */
    private fun modelsDir(): UniFile? {
        val base = storagePreferences.baseStorageDirectory.get().takeIf { it.isNotBlank() } ?: return null
        return UniFile.fromUri(context, base.toUri())?.findFile(MODELS_DIR)
    }

    /** 模型 3 顆齊 + key 有設，才翻得了（給下載 hook 判斷）。 */
    fun isReady(): Boolean {
        if (BuildConfig.DEEPSEEK_API_KEY.isBlank()) return false
        val m = modelsDir() ?: return false
        return findOnnx(m, "detect", "comictext") != null &&
            findOnnx(m, "ocr") != null &&
            findOnnx(m, "lama") != null
    }

    /** 翻譯 [chapterDir]（UniFile，相容本機/SAF）內所有頁圖、就地覆蓋成功的頁。 */
    suspend fun translateChapter(chapterDir: UniFile): Int {
        val m = modelsDir() ?: return 0
        val detU = findOnnx(m, "detect", "comictext") ?: return 0
        val ocrU = findOnnx(m, "ocr") ?: return 0
        val lamaU = findOnnx(m, "lama") ?: return 0
        val images = chapterDir.listFiles()
            ?.filter { f -> f.isFile && (f.name?.substringAfterLast('.', "")?.lowercase() ?: "") in IMAGE_EXT }
            ?.sortedBy { it.name.orEmpty() }
            ?.takeIf { it.isNotEmpty() } ?: return 0

        val alphabet = context.assets.open(ALPHABET).bufferedReader().use { it.readLines() }
        val cfg = EngineConfig()
        val det = Detector(ensureLocal(detU), cfg.detector)
        val ocr = Ocr(ensureLocal(ocrU), alphabet, cfg.ocr)
        val inp = Inpainter(ensureLocal(lamaU), cfg.inpainter)
        val translator = LlmTranslator(BuildConfig.DEEPSEEK_API_KEY, cfg.translator)
        val pipeline = Pipeline(det, ocr, translator, inp, cfg, typeface = null)

        var translated = 0
        try {
            for (img in images) {
                val bmp = context.contentResolver.openInputStream(img.uri)
                    ?.use { BitmapFactory.decodeStream(it) } ?: continue
                when (val r = pipeline.translatePage(bmp)) {
                    is PageResult.Translated -> { writeBack(img, r.page); translated++ }
                    is PageResult.Skipped -> logcat { "翻譯略過 ${img.name}：${r.reason}" }
                    is PageResult.Failed -> logcat(LogPriority.WARN) { "翻譯失敗 ${img.name}：${r.reason}" }
                }
                bmp.recycle()
            }
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "translateChapter 例外（保留原圖）" }
        } finally {
            det.close(); ocr.close(); inp.close()
        }
        return translated
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
        private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp")
    }
}
