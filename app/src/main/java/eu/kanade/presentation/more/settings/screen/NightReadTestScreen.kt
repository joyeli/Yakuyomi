package eu.kanade.presentation.more.settings.screen

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import eu.kanade.tachiyomi.data.nightread.NightReadRenderer
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
 * 挑一張圖，走完「偵測 → 人物遮罩 → 分區重繪」，並排顯示結果與各階段耗時。
 * 產品化的夜讀會走重繪佇列（與去字法正交的 palette 欄位），這裡只驗證管線在真機上跑得動、
 * 跑多久、畫面對不對。
 *
 * 模型：偵測器沿用翻譯引擎的 DBNet；人物遮罩需要 `manga_seg_s.onnx`（38.9MB，必要）與
 * `cartoonseg.onnx`（228MB，可選，加了更準）。兩者放進 app 私有的 `files/models/` 即可。
 */
class NightReadTestScreen : Screen {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val scope = rememberCoroutineScope()

        val modelsDir = remember { TranslationEngineConfig.downloadedDir(context) }
        var scan by remember { mutableStateOf(0) }
        val detectorPath = remember(scan) { findModel(modelsDir, ".param", "dbnet") }
        val yolosegPath = remember(scan) { findModel(modelsDir, ".onnx", "manga_seg", "yoloseg") }
        val csegPath = remember(scan) { findModel(modelsDir, ".onnx", "cartoonseg", "cseg") }

        var source by remember { mutableStateOf<Bitmap?>(null) }
        var rendered by remember { mutableStateOf<Bitmap?>(null) }
        var status by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }

        val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri ?: return@rememberLauncherForActivityResult
            scope.launch {
                busy = true
                rendered = null
                status = ""
                try {
                    val bmp = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use {
                            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
                                inPreferredConfig = Bitmap.Config.ARGB_8888
                            })
                        }
                    } ?: error("decode failed")
                    source = bmp
                    val det = detectorPath ?: error("missing detector")
                    val yolo = yolosegPath ?: error("missing character model")
                    val out = withContext(Dispatchers.Default) {
                        NightReadRenderer(det, yolo, csegPath).use { it.render(bmp) }
                    }
                    rendered = out.bitmap
                    status = "${bmp.width}×${bmp.height} · " +
                        "detect ${out.timing.detectMs}ms · mask ${out.timing.maskMs}ms · " +
                        "render ${out.timing.renderMs}ms · total ${out.timing.totalMs}ms"
                } catch (e: Throwable) {
                    status = e.message ?: e.javaClass.simpleName
                } finally {
                    busy = false
                }
            }
        }

        val importer = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri ?: return@rememberLauncherForActivityResult
            scope.launch {
                busy = true
                try {
                    val name = withContext(Dispatchers.IO) {
                        val display = context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
                        } ?: "imported.onnx"
                        modelsDir.mkdirs()
                        val dst = File(modelsDir, display)
                        context.contentResolver.openInputStream(uri)!!.use { input ->
                            dst.outputStream().use { input.copyTo(it) }
                        }
                        display
                    }
                    status = "imported $name"
                    scan++
                } catch (e: Throwable) {
                    status = e.message ?: e.javaClass.simpleName
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
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                                contentDescription = null,
                            )
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
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(MR.strings.pref_nightread_test_models),
                    style = MaterialTheme.typography.titleSmall,
                )
                ModelRow("DBNet (detection)", detectorPath)
                ModelRow("manga_seg_s.onnx (characters)", yolosegPath)
                ModelRow("cartoonseg.onnx (optional)", csegPath)

                Button(
                    onClick = { importer.launch("*/*") },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(MR.strings.pref_nightread_test_import))
                }

                Button(
                    onClick = { picker.launch("image/*") },
                    enabled = !busy && detectorPath != null && yolosegPath != null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(MR.strings.pref_nightread_test_pick))
                }

                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.padding(8.dp))
                }
                if (status.isNotEmpty()) {
                    Text(text = status, style = MaterialTheme.typography.bodySmall)
                }

                val src = source
                val out = rendered
                if (src != null && out != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Image(
                            bitmap = src.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.weight(1f),
                        )
                        Image(
                            bitmap = out.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun ModelRow(label: String, path: String?) {
        Text(
            text = if (path != null) "✓ $label" else "✗ $label",
            style = MaterialTheme.typography.bodySmall,
            color = if (path != null) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.error
            },
        )
    }

    private fun findModel(dir: File, ext: String, vararg keywords: String): String? =
        dir.takeIf { it.isDirectory }?.listFiles()?.firstOrNull { f ->
            val n = f.name.lowercase()
            n.endsWith(ext) && keywords.any { n.contains(it) }
        }?.absolutePath
}
