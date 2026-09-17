package eu.kanade.presentation.more.settings.screen

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.tachiyomi.data.nightread.NightReadBenchmark
import eu.kanade.tachiyomi.data.translation.TranslationEngineConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import java.io.File

/**
 * 夜讀模式的上機測試台（實驗性）。
 *
 * 一鍵對四張內建測試頁跑兩套模型配方（未量化 / 量化），把結果並排、分段耗時印在同一張大圖上，
 * 存進相簿。量化的差異多半是局部的（某塊背景填了沒填、某顆泡破沒破），分開看截圖比不出來。
 *
 * 測試頁的最後一張是**已經貼好譯文的成品頁**：產品路徑上夜讀處理的一律是翻譯完成後的頁，
 * 所以量測要帶著它，不能只看原文頁。
 *
 * 模型放在 app 私有的 `files/models/`，外部塞不進去，所以下面有匯入鈕。
 * 未量化配方要 `dbnet*.param`（NCNN，與翻譯共用）與 `manga_seg_s.onnx`；
 * 量化配方要 `dbnet*.onnx`（int8）與 `manga_seg_s_int8.onnx`。
 * `cartoonseg.onnx` 兩套共用、可不放——少它會多幾框違規，但兩邊條件一致，仍比得出量化的影響。
 */
class NightReadTestScreen : Screen {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val scope = rememberCoroutineScope()

        val modelsDir = remember { TranslationEngineConfig.downloadedDir(context) }
        var scan by remember { mutableStateOf(0) }
        val models = remember(scan) { resolveModels(modelsDir) }

        var busy by remember { mutableStateOf(false) }
        var progress by remember { mutableStateOf("") }
        var summary by remember { mutableStateOf<List<String>>(emptyList()) }
        var sheet by remember { mutableStateOf<Bitmap?>(null) }
        var sheetUri by remember { mutableStateOf<Uri?>(null) }

        val importer = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri ?: return@rememberLauncherForActivityResult
            scope.launch {
                busy = true
                try {
                    val name = withContext(Dispatchers.IO) {
                        val display = context.contentResolver.query(uri, null, null, null, null)?.use { cur ->
                            val idx = cur.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                            if (idx >= 0 && cur.moveToFirst()) cur.getString(idx) else null
                        } ?: "imported.onnx"
                        modelsDir.mkdirs()
                        val dst = File(modelsDir, display)
                        context.contentResolver.openInputStream(uri)!!.use { input ->
                            dst.outputStream().use { input.copyTo(it) }
                        }
                        display
                    }
                    progress = "imported $name"
                    scan++
                } catch (e: Throwable) {
                    progress = e.message ?: e.javaClass.simpleName
                } finally {
                    busy = false
                }
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(MR.strings.pref_nightread_test)) },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
                        }
                    },
                )
            },
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    stringResource(MR.strings.pref_nightread_test_models),
                    style = MaterialTheme.typography.titleSmall,
                )
                models.rows.forEach { (label, path) ->
                    Text(
                        text = (if (path != null) "✓ " else "✗ ") + label,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (path != null) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }

                OutlinedButton(
                    onClick = { importer.launch("*/*") },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(MR.strings.pref_nightread_test_import)) }

                Button(
                    onClick = {
                        scope.launch {
                            busy = true
                            summary = emptyList()
                            sheet = null
                            try {
                                val report = withContext(Dispatchers.Default) {
                                    NightReadBenchmark(context).run(
                                        assetPages = PAGES,
                                        recipes = models.recipes(),
                                    ) { p -> progress = p }
                                }
                                sheet = report.sheet
                                sheetUri = report.uri
                                summary = report.lines
                                progress = if (report.uri != null) "saved to gallery" else "done"
                            } catch (e: Throwable) {
                                progress = e.message ?: e.javaClass.simpleName
                            } finally {
                                busy = false
                            }
                        }
                    },
                    enabled = !busy && models.ready,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(MR.strings.pref_nightread_test_run)) }

                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                }
                if (progress.isNotEmpty()) {
                    Text(progress, style = MaterialTheme.typography.bodySmall)
                }
                summary.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }

                sheetUri?.let { uri ->
                    OutlinedButton(
                        onClick = {
                            val share = Intent(Intent.ACTION_SEND).apply {
                                type = "image/png"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(share, null))
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(MR.strings.pref_nightread_test_share)) }
                }

                sheet?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }

    /** 兩套配方各需要的模型。檔名關鍵字要分得出 fp32 與 int8，所以比對得精確。 */
    private class Models(
        val detNcnn: String?,
        val detOnnx: String?,
        val yolo32: String?,
        val yolo8: String?,
        val cseg: String?,
    ) {
        val rows: List<Pair<String, String?>> = listOf(
            "dbnet .param (NCNN fp16, baseline)" to detNcnn,
            "dbnet .onnx (int8)" to detOnnx,
            "manga_seg_s.onnx (fp32)" to yolo32,
            "manga_seg_s_int8.onnx" to yolo8,
            "cartoonseg.onnx (shared, optional)" to cseg,
        )

        val ready: Boolean get() = detNcnn != null && detOnnx != null && yolo32 != null && yolo8 != null

        fun recipes(): List<NightReadBenchmark.Recipe> {
            fun mb(vararg p: String?) = p.filterNotNull().sumOf { File(it).length() } / 1048576.0
            val ncnnBin = detNcnn?.removeSuffix(".param")?.plus(".bin")
            return listOf(
                NightReadBenchmark.Recipe(
                    label = "fp16 + fp32",
                    detectorNcnn = detNcnn,
                    detectorOnnx = null,
                    yoloseg = yolo32!!,
                    cseg = cseg,
                    sizeMb = mb(detNcnn, ncnnBin, yolo32, cseg),
                ),
                NightReadBenchmark.Recipe(
                    label = "int8 + int8",
                    detectorNcnn = null,
                    detectorOnnx = detOnnx,
                    yoloseg = yolo8!!,
                    cseg = cseg,
                    sizeMb = mb(detOnnx, yolo8, cseg),
                ),
            )
        }
    }

    private fun resolveModels(dir: File): Models {
        val files = dir.takeIf { it.isDirectory }?.listFiles().orEmpty()
        fun find(pred: (String) -> Boolean) = files.firstOrNull { pred(it.name.lowercase()) }?.absolutePath
        return Models(
            detNcnn = find { it.contains("dbnet") && it.endsWith(".param") },
            detOnnx = find { it.contains("dbnet") && it.endsWith(".onnx") },
            yolo32 = find { it.contains("manga_seg") && it.endsWith(".onnx") && !it.contains("int8") },
            yolo8 = find { it.contains("manga_seg") && it.endsWith(".onnx") && it.contains("int8") },
            cseg = find { it.contains("cartoonseg") && it.endsWith(".onnx") && !it.contains("int8") },
        )
    }

    private companion object {
        /**
         * 四張代表頁（放 debug sourceSet，release build 不含）：
         * 有框黑白、多泡、無框彩頁，**外加一張已經貼好譯文的成品頁**。
         *
         * 最後那張是產品路徑的實況——夜讀在產品裡處理的一律是翻譯完成後的頁，不是原圖。
         * 譯文是純色黑字，墨度與邊緣都跟手寫原文不同，值得每次量測都帶著它一起看。
         */
        val PAGES = listOf("ch34_011.jpg", "ch34_014.jpg", "demo05.png", "translated.webp")
    }
}
