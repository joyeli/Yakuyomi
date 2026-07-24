package eu.kanade.tachiyomi.ui.capture

import android.content.Context
import com.hippo.unifile.UniFile
import org.json.JSONObject
import java.io.OutputStream

/**
 * Yakuyomi 擷取：**整章一個** meta 檔（放章夾內、`.` 開頭隱藏、非圖副檔名 → LocalSource 掃描會略過），
 * 取代先前每頁一個零散 `NNN.url` sidecar。記每頁截圖當下的網址，供確認頁重截 / 插入時開回原頁。
 *
 * 格式（Android 內建 `org.json`，無新依賴）：
 * ```
 * { "pages": { "001": { "url": "..." }, "002": { "url": "..." } } }
 * ```
 * key＝頁碼 basename 字串（`001`）；value 是物件（現在只 `url`，**預留**未來欄位）。
 *
 * 目標：章夾裡只有 `NNN.png` + **一個** `.yakuyomi_meta.json`，不再有一堆 `.url`。
 * 相容：讀不到 meta 的頁 fallback 讀舊 `NNN.url` sidecar（既有那批不用重截）。
 */
const val CAPTURE_META_FILE = ".yakuyomi_meta.json"

/**
 * 讀整章 meta → `basename→url` map（保插入序）。檔不存在 / 內容空 / JSON 壞掉 → 回空 map
 * （null-safe，任何解析例外都吞掉不 crash）。只收有非空 url 的頁。
 */
fun readMeta(dir: UniFile): MutableMap<String, String> {
    val result = mutableMapOf<String, String>()
    val file = dir.findFile(CAPTURE_META_FILE)?.takeIf { it.isFile } ?: return result
    runCatching {
        val text = file.openInputStream().use { it.readBytes().toString(Charsets.UTF_8) }
        if (text.isBlank()) return@runCatching
        val pages = JSONObject(text).optJSONObject("pages") ?: return@runCatching
        val keys = pages.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val url = pages.optJSONObject(key)?.optString("url").orEmpty()
            if (url.isNotEmpty()) result[key] = url
        }
    }
    return result
}

/**
 * 寫整章 meta（序列化成上面格式）。截斷寫（SAF ContentResolver `"wt"` / `file://` 一般串流）避免短輸出留舊尾。
 * best-effort：檔案建立失敗或 I/O 例外一律吞掉不 crash。空/空白 url 的 entry 略過（等同不記）。
 */
fun writeMeta(context: Context, dir: UniFile, map: Map<String, String>) {
    runCatching {
        val pages = JSONObject()
        for ((key, url) in map) {
            if (url.isBlank()) continue
            pages.put(key, JSONObject().put("url", url))
        }
        val root = JSONObject().put("pages", pages)
        val file = dir.findFile(CAPTURE_META_FILE) ?: dir.createFile(CAPTURE_META_FILE) ?: return
        openTruncatingMeta(context, file).use { it.write(root.toString().toByteArray(Charsets.UTF_8)) }
    }
}

/**
 * Yakuyomi 擷取：**漫畫層** meta 檔（放書名夾根、`.` 開頭隱藏、非圖副檔名 → LocalSource 掃描會略過）。
 * 記這本漫畫的來源網址（首頁 / 目錄頁），供日後「繼續擷取」從書櫃開回原站。與整章 meta（[CAPTURE_META_FILE]）
 * 分開放：整章 meta 在**話夾**、漫畫 meta 在**書名夾根**。
 *
 * 格式（Android 內建 `org.json`，無新依賴）：`{ "url": "..." }`。
 */
const val MANGA_META_FILE = ".yakuyomi_manga.json"

/**
 * 讀漫畫層 meta 的 `url` 欄位（[dir]＝書名夾）。檔不存在 / 內容空 / JSON 壞掉 / url 空 → 回 null
 * （null-safe，任何解析例外都吞掉不 crash）。
 */
fun readMangaMeta(dir: UniFile): String? {
    val file = dir.findFile(MANGA_META_FILE)?.takeIf { it.isFile } ?: return null
    return runCatching {
        val text = file.openInputStream().use { it.readBytes().toString(Charsets.UTF_8) }
        if (text.isBlank()) return@runCatching null
        // about:blank 是無效來源網址（舊 bug 可能寫入）→ 當作沒有，讓「繼續擷取」走空 url 的引導路徑。
        JSONObject(text).optString("url").trim().takeIf { it.isNotEmpty() && it != "about:blank" }
    }.getOrNull()
}

/**
 * 寫漫畫層 meta（[dir]＝書名夾）：`{ "url": <trimmed url> }`，截斷寫（同 [writeMeta] 的截斷規則）。
 * best-effort：檔案建立失敗或 I/O 例外一律吞掉不 crash。[url] 空/空白＝不寫（不建檔）。
 */
fun writeMangaMeta(context: Context, dir: UniFile, url: String?) {
    val trimmed = url?.trim().orEmpty()
    // 空白 / about:blank ＝無效來源網址，寫了會讓「繼續擷取」開回 about:blank。
    if (trimmed.isEmpty() || trimmed == "about:blank") return
    runCatching {
        val root = JSONObject().put("url", trimmed)
        val file = dir.findFile(MANGA_META_FILE) ?: dir.createFile(MANGA_META_FILE) ?: return
        openTruncatingMeta(context, file).use { it.write(root.toString().toByteArray(Charsets.UTF_8)) }
    }
}

/**
 * 覆寫用截斷串流：SAF DocumentFile 走 ContentResolver `"wt"`（DocumentFile `"w"` 不截斷、短內容留舊尾）；
 * `file://` 用一般 openOutputStream（FileOutputStream 本就截斷、且 `"wt"` 對 file:// 不落地）。
 */
private fun openTruncatingMeta(context: Context, f: UniFile): OutputStream =
    if (f.uri.scheme == "file") {
        f.openOutputStream()
    } else {
        checkNotNull(context.contentResolver.openOutputStream(f.uri, "wt")) {
            "Cannot open output stream for ${f.uri}"
        }
    }
