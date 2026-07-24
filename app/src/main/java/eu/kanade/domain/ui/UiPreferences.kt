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

    // Yakuyomi：「以 WebView 開啟網址」的輸入歷史（JSON 字串陣列，最近的在最前、去重、上限見 MoreScreenModel）。
    // More 瀏覽入口用純 url `List<String>`；擷取瀏覽器改用下面 captureUrlHistory（帶標題），兩者刻意分開。
    val lastWebViewUrls: Preference<String> = preferenceStore.getString("last_webview_urls", "[]")

    // Yakuyomi：擷取瀏覽器的網址歷史（**帶頁面標題**）。JSON 陣列 `[{"url":"...","title":"..."}]`，最新在最前。
    // 與 More 共用的 lastWebViewUrls（純 url 陣列、無標題）分開——若改共用同一份，More 端的
    // `decodeFromString<List<String>>` 會在物件陣列上解析失敗、歷史整個消失。首次讀取（本 pref 還空）時
    // 讀 lastWebViewUrls 當初值（標題留空、相容舊資料），待下次造訪/刪除才以新格式落地（見 CaptureScreenModel）。
    val captureUrlHistory: Preference<String> = preferenceStore.getString("capture_url_history", "[]")

    // Yakuyomi：擷取瀏覽器的「我的最愛」（手動存常用站 + 命名別名）。JSON 陣列
    // `[{"url":"...","alias":"..."}]`，最新加入的在最前；與自動記錄的網址歷史（lastWebViewUrls）分開。
    val captureBookmarks: Preference<String> = preferenceStore.getString("capture_bookmarks", "[]")

    companion object {
        fun dateFormat(format: String): DateTimeFormatter = when (format) {
            "" -> DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)
            else -> DateTimeFormatter.ofPattern(format, Locale.getDefault())
        }
    }
}
