package eu.kanade.presentation.webview

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.Window
import android.webkit.WebView

// Yakuyomi：WebView 截圖工具（B0 spike 從 WebViewScreenContent 抽出成模組內共用）。
// 目前唯一使用者＝「擷取漫畫→存 local」（CaptureScreen）；舊 WebView 畫面的「截這頁」過渡入口已移除。

/**
 * 從 Compose 的 [Context] 往上找 [Activity]（PixelCopy 需要 Activity 的 window）。
 */
internal fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

/**
 * 截 WebView 內容。首選 [PixelCopy]（抓合成後的實際像素、含硬體加速層），
 * 失敗或拿不到 window 時退回 [WebView.draw]。回呼一律在主執行緒；截不到回傳 null。
 */
internal fun captureWebView(
    webView: WebView?,
    window: Window?,
    onResult: (Bitmap?) -> Unit,
) {
    if (webView == null || webView.width <= 0 || webView.height <= 0) {
        onResult(null)
        return
    }
    val width = webView.width
    val height = webView.height
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

    fun drawFallback(): Bitmap? = try {
        webView.draw(Canvas(bitmap))
        bitmap
    } catch (e: Throwable) {
        if (!bitmap.isRecycled) bitmap.recycle()
        null
    }

    if (window != null) {
        val location = IntArray(2)
        webView.getLocationInWindow(location)
        val srcRect = Rect(
            location[0],
            location[1],
            location[0] + width,
            location[1] + height,
        )
        try {
            PixelCopy.request(
                window,
                srcRect,
                bitmap,
                { copyResult ->
                    if (copyResult == PixelCopy.SUCCESS) {
                        onResult(bitmap)
                    } else {
                        onResult(drawFallback())
                    }
                },
                Handler(Looper.getMainLooper()),
            )
            return
        } catch (e: Throwable) {
            // PixelCopy 對沒有 backing surface 的 window 會丟 IllegalArgumentException
            onResult(drawFallback())
            return
        }
    }
    onResult(drawFallback())
}
