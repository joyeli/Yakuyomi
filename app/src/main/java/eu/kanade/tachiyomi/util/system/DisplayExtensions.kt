package eu.kanade.tachiyomi.util.system

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.view.View
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.domain.ui.model.TabletUiMode
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

private const val TABLET_UI_REQUIRED_SCREEN_WIDTH_DP = 720

// some tablets have screen width like 711dp = 1600px / 2.25
private const val TABLET_UI_MIN_SCREEN_WIDTH_PORTRAIT_DP = 700

// make sure icons on the nav rail fit
private const val TABLET_UI_MIN_SCREEN_WIDTH_LANDSCAPE_DP = 600

fun Configuration.isTabletUi(): Boolean {
    return smallestScreenWidthDp >= TABLET_UI_REQUIRED_SCREEN_WIDTH_DP
}

// Yakuyomi：依「外觀→平板介面」設定（自動/一律/橫向/永不）解析此 configuration 當下是否該用平板 UI。
// 直接從傳入的 configuration 算，不靠 createConfigurationContext 覆寫 → 折疊機折/展時用新 config 重算正確。
fun Configuration.isTabletUiMode(): Boolean {
    return when (Injekt.get<UiPreferences>().tabletUiMode.get()) {
        TabletUiMode.AUTOMATIC ->
            smallestScreenWidthDp >= when (orientation) {
                Configuration.ORIENTATION_PORTRAIT -> TABLET_UI_MIN_SCREEN_WIDTH_PORTRAIT_DP
                else -> TABLET_UI_MIN_SCREEN_WIDTH_LANDSCAPE_DP
            }
        TabletUiMode.ALWAYS -> true
        TabletUiMode.LANDSCAPE -> orientation == Configuration.ORIENTATION_LANDSCAPE
        TabletUiMode.NEVER -> false
    }
}

// TODO: move the logic to `isTabletUi()` when main activity is rewritten in Compose
fun Context.prepareTabletUiContext(): Context {
    val configuration = resources.configuration
    val expected = configuration.isTabletUiMode()
    if (configuration.isTabletUi() != expected) {
        val overrideConf = Configuration()
        overrideConf.setTo(configuration)
        overrideConf.smallestScreenWidthDp = if (expected) {
            overrideConf.smallestScreenWidthDp.coerceAtLeast(TABLET_UI_REQUIRED_SCREEN_WIDTH_DP)
        } else {
            overrideConf.smallestScreenWidthDp.coerceAtMost(TABLET_UI_REQUIRED_SCREEN_WIDTH_DP - 1)
        }
        return createConfigurationContext(overrideConf)
    }
    return this
}

/**
 * Returns true if current context is in night mode
 */
fun Context.isNightMode(): Boolean {
    return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
}

/**
 * Checks whether if the device has a display cutout (i.e. notch, camera cutout, etc.).
 *
 * Only works on Android 9+.
 */
fun Activity.hasDisplayCutout(): Boolean {
    return window.decorView.hasDisplayCutout()
}

/**
 * Checks whether if the device has a display cutout (i.e. notch, camera cutout, etc.).
 *
 * Only works on Android 9+.
 */
fun View.hasDisplayCutout(): Boolean {
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && rootWindowInsets?.displayCutout != null
}

/**
 * Gets system's config_navBarNeedsScrim boolean flag added in Android 10, defaults to true.
 */
fun Context.isNavigationBarNeedsScrim(): Boolean {
    return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
        InternalResourceHelper.getBoolean(this, "config_navBarNeedsScrim", true)
}
