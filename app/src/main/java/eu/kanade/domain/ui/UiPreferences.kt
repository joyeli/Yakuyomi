package eu.kanade.domain.ui

import eu.kanade.domain.ui.model.AppTheme
import eu.kanade.domain.ui.model.TabletUiMode
import eu.kanade.domain.ui.model.ThemeMode
import eu.kanade.tachiyomi.util.system.DeviceUtil
import eu.kanade.tachiyomi.util.system.isDynamicColorAvailable
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

class UiPreferences(
    preferenceStore: PreferenceStore,
) {

    val themeMode: Preference<ThemeMode> = preferenceStore.getEnum("pref_theme_mode_key", ThemeMode.SYSTEM)

    val appTheme: Preference<AppTheme> = preferenceStore.getEnum(
        "pref_app_theme",
        if (DeviceUtil.isDynamicColorAvailable) {
            AppTheme.MONET
        } else {
            AppTheme.DEFAULT
        },
    )

    val themeDarkAmoled: Preference<Boolean> = preferenceStore.getBoolean("pref_theme_dark_amoled_key", false)

    val relativeTime: Preference<Boolean> = preferenceStore.getBoolean("relative_time_v2", true)

    val dateFormat: Preference<String> = preferenceStore.getString("app_date_format", "")

    val tabletUiMode: Preference<TabletUiMode> = preferenceStore.getEnum("tablet_ui_mode", TabletUiMode.AUTOMATIC)

    val imagesInDescription: Preference<Boolean> = preferenceStore.getBoolean("pref_render_images_description", true)

    // Yakuyomi：漫畫詳情頁的簡介區塊預設是否展開（預設摺疊）。
    val expandMangaSummary: Preference<Boolean> = preferenceStore.getBoolean("pref_expand_manga_summary", false)

    // Yakuyomi：底部導覽列精簡模式（僅圖示、無文字、縮高度與留白）。
    val bottomNavCompact: Preference<Boolean> = preferenceStore.getBoolean("pref_bottom_nav_compact", false)

    // Yakuyomi：漫畫詳情頁依封面取色的動態主題（預設關閉，避免影響全 app 觀感）。
    val coverBasedTheme: Preference<Boolean> = preferenceStore.getBoolean("pref_cover_based_theme", false)

    // Yakuyomi：**舊** More「以 WebView 開啟網址」入口的輸入歷史（純 url JSON 陣列 `["a","b"]`，最近的在最前）。
    // 該入口已被獨立的「擷取漫畫」畫面取代並移除 → 現在**只剩讀取**：CaptureViewModel 在新的
    // captureUrlHistory 還空時讀這裡當初值做遷移（見下）。**別刪**，刪了既有使用者的歷史會消失。
    val lastWebViewUrls: Preference<String> = preferenceStore.getString("last_webview_urls", "[]")

    // Yakuyomi：擷取瀏覽器的網址歷史（**帶頁面標題**）。JSON 陣列 `[{"url":"...","title":"..."}]`，最新在最前。
    // 與舊 More 入口的 lastWebViewUrls（純 url 陣列、無標題）刻意分開的格式。首次讀取（本 pref 還空）時
    // 讀 lastWebViewUrls 當初值（標題留空、相容舊資料），待下次造訪/刪除才以新格式落地（見 CaptureViewModel）。
    val captureUrlHistory: Preference<String> = preferenceStore.getString("capture_url_history", "[]")

    // Yakuyomi：擷取瀏覽器的「我的最愛」（手動存常用站 + 命名別名）。JSON 陣列
    // `[{"url":"...","alias":"..."}]`，最新加入的在最前；與自動記錄的網址歷史（captureUrlHistory）分開。
    val captureBookmarks: Preference<String> = preferenceStore.getString("capture_bookmarks", "[]")

    // Yakuyomi：擷取的**逐站（host）設定**——網頁畫布寬度% + 去頭去尾裁切比例。JSON 物件
    // `{"<host>":{"scale":80,"cropTop":0.08,"cropBottom":0.12}}`：
    // - `scale`＝畫布寬度百分比（50–100，100＝不縮，套用方式見 CaptureScreenContent 的 applyInitialScale）
    // - `cropTop`/`cropBottom`＝存檔前從**上／下**各裁掉多少，**佔畫面高度的比例 0.0–1.0**
    //   （存比例而非像素 → 換解析度/旋轉/摺疊展開都適用）
    // host 已正規化（小寫、去 `www.`）；全為預設值的站不寫入（保持 pref 乾淨）。
    val captureSiteSettings: Preference<String> = preferenceStore.getString("capture_site_settings", "{}")

    companion object {
        fun dateFormat(format: String): DateTimeFormatter = when (format) {
            "" -> DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)
            else -> DateTimeFormatter.ofPattern(format, Locale.getDefault())
        }
    }
}
