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
 * Yakuyomi 擷取：**漫畫層** meta 檔（放書名夾根、`.` 開頭隱藏、**刻意無副檔名**）。
 * 記這本漫畫的來源網址（首頁 / 目錄頁），供日後「繼續擷取」從書櫃開回原站。與整章 meta（[CAPTURE_META_FILE]）
 * 分開放：整章 meta 在**話夾**、漫畫 meta 在**書名夾根**。
 *
 * ★ 為何無副檔名（2026-07 修）：mihon 的 `LocalSource.getMangaDetails` 會把書名夾根**任何 `extension == "json"`**
 * 的檔當成 legacy 詳情檔（`MangaDetails` 欄位全 nullable + `ignoreUnknownKeys` ⇒ 我們的 `{"url":...}` 也「解析成功」）、
 * 寫出 `ComicInfo.xml` 後 **`legacyJsonDetailsFile.delete()` 把它刪掉** → 舊檔名 `.yakuyomi_manga.json` 每次進詳情頁必被刪。
 * 改成無副檔名後 `UniFile.extension` ＝ `"yakuyomi_manga"` ≠ `"json"` → 不再被撈走（同款無副檔名的
 * `.yakuyomi_translated` marker 早已長期驗證可靠）。內容格式不變（仍是 JSON 字串）。
 * **不可改放子資料夾**：書名夾下任何子目錄都會被 LocalSource 當成一話。
 */
const val MANGA_META_FILE = ".yakuyomi_manga"

/** 舊檔名（會被 LocalSource 當 legacy json 刪掉，見 [MANGA_META_FILE]）：只為「讀得到就 migrate」保留。 */
const val MANGA_META_FILE_LEGACY = ".yakuyomi_manga.json"

/**
 * 讀漫畫層 meta 的 `url` 欄位（[dir]＝書名夾）。檔不存在 / 內容空 / JSON 壞掉 / url 空 → 回 null
 * （null-safe，任何解析例外都吞掉不 crash）。
 *
 * 相容：新檔名（[MANGA_META_FILE]）找不到才找舊的（[MANGA_META_FILE_LEGACY]）；讀到舊的就順手用新檔名重寫一份
 * 並刪掉舊檔（migrate，見 [migrateMangaMeta]）——舊檔遲早會被 LocalSource 刪掉，趁還讀得到先搬走。
 */
fun readMangaMeta(dir: UniFile): String? {
    dir.findFile(MANGA_META_FILE)?.takeIf { it.isFile }?.let { return parseMangaMetaUrl(it) }
    val legacy = dir.findFile(MANGA_META_FILE_LEGACY)?.takeIf { it.isFile } ?: return null
    val url = parseMangaMetaUrl(legacy)
    if (url != null) migrateMangaMeta(dir, legacy, url)
    return url
}

/** 解析單一 meta 檔的 `url`；空 / 壞 JSON / `about:blank`（舊 bug 可能寫入）一律回 null。 */
private fun parseMangaMetaUrl(file: UniFile): String? = runCatching {
    val text = file.openInputStream().use { it.readBytes().toString(Charsets.UTF_8) }
    if (text.isBlank()) return@runCatching null
    JSONObject(text).optString("url").trim().takeIf { it.isNotEmpty() && it != "about:blank" }
}.getOrNull()

/**
 * 把舊檔名的內容搬到新檔名並刪掉舊檔。新檔是 `createFile` 出來的空檔 ⇒ 不必截斷寫、也就不必拿 Context
 * （[readMangaMeta] 的呼叫端如 `MangaViewModel` 手上沒有 Context）。best-effort、吞例外。
 */
private fun migrateMangaMeta(dir: UniFile, legacy: UniFile, url: String) {
    runCatching {
        if (dir.findFile(MANGA_META_FILE)?.isFile == true) return
        val file = dir.createFile(MANGA_META_FILE) ?: return
        file.openOutputStream().use {
            it.write(JSONObject().put("url", url).toString().toByteArray(Charsets.UTF_8))
        }
        legacy.delete()
    }
}

/**
 * 寫漫畫層 meta（[dir]＝書名夾）：`{ "url": <trimmed url> }`，截斷寫（同 [writeMeta] 的截斷規則）。
 * best-effort：檔案建立失敗或 I/O 例外一律吞掉不 crash。[url] 空/空白＝不寫（不建檔）。
 * 寫成功後順手刪掉舊檔名（避免同一夾兩份、也免舊檔被 LocalSource 拿去寫 ComicInfo.xml）。
 */
fun writeMangaMeta(context: Context, dir: UniFile, url: String?) {
    val trimmed = url?.trim().orEmpty()
    // 空白 / about:blank ＝無效來源網址，寫了會讓「繼續擷取」開回 about:blank。
    if (trimmed.isEmpty() || trimmed == "about:blank") return
    runCatching {
        val root = JSONObject().put("url", trimmed)
        val file = dir.findFile(MANGA_META_FILE) ?: dir.createFile(MANGA_META_FILE) ?: return
        openTruncatingMeta(context, file).use { it.write(root.toString().toByteArray(Charsets.UTF_8)) }
        dir.findFile(MANGA_META_FILE_LEGACY)?.takeIf { it.isFile }?.delete()
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
