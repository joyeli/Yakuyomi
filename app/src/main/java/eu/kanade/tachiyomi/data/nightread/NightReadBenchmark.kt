package eu.kanade.tachiyomi.data.nightread

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File

/**
 * 夜讀的量化 A/B 測試：同一批頁面各跑「未量化」與「量化」兩套配方，把結果與分段耗時
 * 合成一張大圖。
 *
 * 為什麼要一張大圖：兩套配方的差異多半是局部的（某塊背景填了沒填、某顆泡破沒破），
 * 分開看截圖比不出來，得並排。時間也印在同一張上，省得另外抄。
 *
 * 配方的差異**只在模型精度**，前後處理完全共用（偵測的前後處理走 `Detector` 的 companion），
 * 所以 A/B 比的是量化本身，不是兩套實作。
 */
class NightReadBenchmark(private val context: Context) {

    /** 一套配方：哪幾顆模型、什麼精度。 */
    class Recipe(
        val label: String,
        val detectorNcnn: String?,
        val detectorOnnx: String?,
        val yoloseg: String,
        val cseg: String?,
        val sizeMb: Double,
    )

    class PageRun(val page: String, val timing: NightReadRenderer.Timing, val bitmap: Bitmap)

    class Report(val sheet: Bitmap, val uri: Uri?, val lines: List<String>)

    /**
     * 跑完一批頁面 × 兩套配方。
     *
     * 每套配方只建一次 renderer（模型載入不算進單頁耗時），第一頁會吃到快取冷啟的成本，
     * 所以每頁都跑兩次、取第二次——否則量到的是載入而不是推論。
     */
    fun run(
        assetPages: List<String>,
        recipes: List<Recipe>,
        onProgress: (String) -> Unit = {},
    ): Report {
        val sources = assetPages.associateWith { name ->
            context.assets.open("nightread/$name").use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                })!!
            }
        }
        val results = LinkedHashMap<String, MutableList<PageRun>>()
        for (r in recipes) {
            onProgress("${r.label}: loading models")
            NightReadRenderer(
                detectorNcnnPath = r.detectorNcnn,
                detectorOnnxPath = r.detectorOnnx,
                yolosegPath = r.yoloseg,
                csegPath = r.cseg,
            ).use { renderer ->
                for (name in assetPages) {
                    onProgress("${r.label}: $name")
                    val src = sources[name]!!
                    renderer.render(src)                    // 熱身：把冷啟成本擋在計時之外
                    val out = renderer.render(src)
                    results.getOrPut(r.label) { mutableListOf() }
                        .add(PageRun(name, out.timing, out.bitmap))
                }
            }
        }
        val sheet = compose(sources, recipes, results)
        val uri = save(sheet)
        return Report(sheet, uri, summaryLines(recipes, results))
    }

    // ── 大圖合成 ─────────────────────────────────────────────────────

    private fun compose(
        sources: Map<String, Bitmap>,
        recipes: List<Recipe>,
        results: Map<String, List<PageRun>>,
    ): Bitmap {
        val colW = 520
        val gap = 10
        val headerH = 60 + 34 * (recipes.size * sources.size + recipes.size + 2)
        val cols = 1 + recipes.size

        // 每列的高度由該頁的長寬比決定（三欄同高）
        val rowHeights = sources.keys.map { name ->
            val b = sources[name]!!
            (colW.toFloat() / b.width * b.height).toInt() + 26
        }
        val sheetW = cols * colW + (cols + 1) * gap
        val sheetH = headerH + rowHeights.sum() + (rowHeights.size + 1) * gap

        val sheet = Bitmap.createBitmap(sheetW, sheetH, Bitmap.Config.ARGB_8888)
        val c = Canvas(sheet)
        c.drawColor(Color.rgb(18, 18, 18))
        val title = textPaint(26f, Color.WHITE)
        val body = textPaint(19f, Color.rgb(210, 210, 210))
        val dim = textPaint(17f, Color.rgb(140, 140, 140))
        val mono = textPaint(18f, Color.rgb(190, 210, 235))

        var y = 38f
        c.drawText("Night reading — quantisation A/B", gap.toFloat(), y, title)
        y += 26
        c.drawText("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}", gap.toFloat(), y, dim)
        y += 30

        for (r in recipes) {
            c.drawText("${r.label} — ${"%.0f".format(r.sizeMb)} MB", gap.toFloat(), y, body)
            y += 24
            for (run in results[r.label].orEmpty()) {
                val t = run.timing
                c.drawText(
                    "    ${run.page.padEnd(14)} detect ${t.detectMs}ms   mask ${t.maskMs}ms   " +
                        "render ${t.renderMs}ms   total ${t.totalMs}ms",
                    gap.toFloat(), y, mono,
                )
                y += 22
            }
            val tot = results[r.label].orEmpty().sumOf { it.timing.totalMs }
            c.drawText("    sum ${tot}ms", gap.toFloat(), y, dim)
            y += 28
        }

        // 圖片區：第一欄原圖，其後每欄一套配方
        var top = headerH.toFloat()
        sources.keys.forEachIndexed { rowIdx, name ->
            val rowH = rowHeights[rowIdx]
            val labels = listOf("original") + recipes.map { it.label }
            val bitmaps = listOf(sources[name]!!) + recipes.map { r ->
                results[r.label].orEmpty().first { it.page == name }.bitmap
            }
            labels.forEachIndexed { colIdx, label ->
                val x = gap + colIdx * (colW + gap)
                c.drawText("$label · $name", x.toFloat() + 2, top + 18, dim)
                val b = bitmaps[colIdx]
                val dst = Rect(x, (top + 26).toInt(), x + colW, (top + rowH).toInt())
                c.drawBitmap(b, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
            }
            top += rowH + gap
        }
        return sheet
    }

    private fun textPaint(size: Float, colour: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        color = colour
        typeface = android.graphics.Typeface.MONOSPACE
    }

    private fun summaryLines(recipes: List<Recipe>, results: Map<String, List<PageRun>>): List<String> =
        recipes.map { r ->
            val runs = results[r.label].orEmpty()
            val d = runs.sumOf { it.timing.detectMs }
            val m = runs.sumOf { it.timing.maskMs }
            val v = runs.sumOf { it.timing.renderMs }
            "${r.label}: ${"%.0f".format(r.sizeMb)}MB · detect ${d}ms · mask ${m}ms · render ${v}ms · total ${d + m + v}ms"
        }

    /** 存進相簿，方便直接分享出去。失敗（權限或廠商差異）就退回 app 外部目錄。 */
    private fun save(sheet: Bitmap): Uri? {
        val name = "nightread_ab_${System.currentTimeMillis()}.png"
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Yakuyomi")
            }
            val uri = context.contentResolver
                .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            uri?.also {
                context.contentResolver.openOutputStream(it)!!.use { os ->
                    sheet.compress(Bitmap.CompressFormat.PNG, 100, os)
                }
            }
        } catch (_: Throwable) {
            val dir = File(context.getExternalFilesDir(null), "nightread")
            dir.mkdirs()
            val f = File(dir, name)
            f.outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
            Uri.fromFile(f)
        }
    }
}
