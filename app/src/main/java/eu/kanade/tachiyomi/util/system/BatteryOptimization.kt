package eu.kanade.tachiyomi.util.system

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import tachiyomi.i18n.MR

// Yakuyomi：電池最佳化的狀態與「允許在背景執行」的系統請求。設定 › 進階「停用電池最佳化」與 設定 › 夜讀「螢幕關閉時繼續處理」
// 共用這一份（原本寫在進階設定頁裡）。

/** 這個 app 目前**不**受電池最佳化限制（系統不會在螢幕關閉後凍結背景工作）。 */
fun Context.isIgnoringBatteryOptimizations(): Boolean = powerManager.isIgnoringBatteryOptimizations(packageName)

/**
 * 跳系統的「允許在背景執行」請求（ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS）。已經不受限制就只提示一句；
 * 系統沒有這個畫面（少數 ROM）也只提示。
 */
fun Context.requestIgnoreBatteryOptimizations() {
    if (isIgnoringBatteryOptimizations()) {
        toast(MR.strings.battery_optimization_disabled)
        return
    }
    try {
        @SuppressLint("BatteryLife")
        val intent = Intent().apply {
            action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
            data = "package:$packageName".toUri()
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        toast(MR.strings.battery_optimization_setting_activity_not_found)
    }
}

/**
 * 系統限制了這個 app 的背景活動（Android 9 起的「限制背景活動」；Android 12 起就是「電池用量：受限」）。跟電池最佳化白名單是
 * 兩個開關：開著時就算不受電池最佳化限制，佇列在背景照樣會被停住。Android 8 沒有這個開關＝false。
 */
fun Context.isBackgroundActivityRestricted(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
        getSystemService<ActivityManager>()?.isBackgroundRestricted == true

/** 開這個 app 的系統「應用程式資訊」畫面（背景活動的限制要從那裡的「電池」解除）。找不到畫面只提示。 */
fun Context.openAppDetailsSettings() {
    try {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = "package:$packageName".toUri()
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        toast(MR.strings.battery_optimization_setting_activity_not_found)
    }
}
