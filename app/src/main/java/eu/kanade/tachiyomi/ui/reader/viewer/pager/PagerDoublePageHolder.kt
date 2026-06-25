package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.view.LayoutInflater
import androidx.core.view.isVisible
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView.SCALE_TYPE_CENTER_CROP
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE
import eu.kanade.presentation.util.formattedMessage
import eu.kanade.tachiyomi.databinding.ReaderErrorBinding
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderProgressIndicator
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
import eu.kanade.tachiyomi.widget.ViewPagerAdapter
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import logcat.LogPriority
import okio.Buffer
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR

/**
 * Yakuyomi：對開（double-page）版面 holder。把一個 [PagePair] 的兩頁**併成一張圖**顯示在單一
 * [ReaderPageImageView]——這樣縮放/平移是「整個跨頁」一起動、兩頁緊鄰像一張大圖（不是各半格、各自縮放）。
 * [PagePair.second] 為 null（封面 / 章末單頁）時只顯示一頁。左右順序依方向：RTL→較前頁(first)在右。
 */
@SuppressLint("ViewConstructor")
class PagerDoublePageHolder(
    readerThemedContext: Context,
    val viewer: PagerViewer,
    val pair: PagePair,
) : ReaderPageImageView(readerThemedContext), ViewPagerAdapter.PositionableView {

    override val item get() = pair

    private val first: ReaderPage get() = pair.first
    private val second: ReaderPage? get() = pair.second

    private var progressIndicator: ReaderProgressIndicator? = null
    private var errorLayout: ReaderErrorBinding? = null

    private val scope = MainScope()
    private var loadJob: Job? = null

    // Yakuyomi：寬圖偵測只在首次載入做一次；即時翻換圖 reload 時不重觸發版面重排。
    private var detectedFull = false

    init {
        loadJob = scope.launch { loadPagesAndProcessStatus() }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        loadJob?.cancel()
        loadJob = null
    }

    private fun initProgressIndicator() {
        if (progressIndicator == null) {
            progressIndicator = ReaderProgressIndicator(context)
            addView(progressIndicator)
        }
    }

    /** 同時載入兩頁，等兩頁都 Ready 才併圖顯示；任一頁錯誤＝顯示錯誤。 */
    private suspend fun loadPagesAndProcessStatus() {
        val firstLoader = first.chapter.pageLoader ?: return
        val secondPage = second
        val secondLoader = secondPage?.chapter?.pageLoader

        supervisorScope {
            launchIO { firstLoader.loadPage(first) }
            if (secondPage != null) {
                launchIO { secondLoader?.loadPage(secondPage) }
            }

            val firstStatus = combine(first.statusFlow, first.reloadFlow) { state, _ -> state }
            if (secondPage == null) {
                firstStatus.collectLatest { state -> process(state) }
            } else {
                val secondStatus = combine(secondPage.statusFlow, secondPage.reloadFlow) { state, _ -> state }
                combine(firstStatus, secondStatus) { a, b -> a to b }
                    .collectLatest { (a, b) -> process(a, b) }
            }
        }
    }

    private suspend fun process(first: Page.State, second: Page.State? = null) {
        when {
            first is Page.State.Error -> setError(first.error)
            second is Page.State.Error -> setError(second.error)
            first == Page.State.Ready && (second == null || second == Page.State.Ready) -> setImage()
            else -> setLoading()
        }
    }

    private fun setLoading() {
        initProgressIndicator()
        progressIndicator?.show()
        removeErrorLayout()
    }

    /** 兩頁都 Ready → 顯示。單頁＝沿用單頁路徑（BufferedSource 餵 SSIV、分塊、支援動圖）；對開＝把兩頁併成
     * 一張 Bitmap 直接餵 SSIV（無損、整個跨頁共用同一個縮放/平移、緊鄰無留白）。 */
    private suspend fun setImage() {
        progressIndicator?.setProgress(0)

        val firstStreamFn = first.stream ?: return
        val secondStreamFn = second?.stream

        val config = Config(
            zoomDuration = viewer.config.doubleTapAnimDuration,
            minimumScaleType = viewer.config.imageScaleType,
            cropBorders = viewer.config.imageCropBorders,
            zoomStartPosition = viewer.config.imageZoomType,
            // Yakuyomi：對開一律關掉「橫圖自動縮放(landscapeZoom)」——對開合成的跨頁、單張寬圖本身就是橫的，
            // 不該被當成橫圖放大到「高度填滿、左右超出」；用 fit 讓跨頁填滿寬度、完整呈現（直頁封面 fit 也正常）。
            landscapeZoom = false,
        )

        // Yakuyomi 對開填滿/對齊（B）：寬度為主＝CENTER_INSIDE（fit 寬、上下留白、整頁一眼看完）；高度為主＝
        // CENTER_CROP（fit 高、左右超出可平移）。對齊只在高度為主有感：靠邊＝起始停頁碼小側（RTL 右/LTR 左）。
        // 套用對象＝合成跨頁（必寬）＋「本身就是寬圖／跨頁」被單獨佔版的頁——後者若不套，大圖不會照填滿設定縮放。
        val fillHeight = viewer.config.doublePageFillMode == 1
        val sideAlign = viewer.config.doublePageAlign == 1
        val fillConfig = config.copy(
            minimumScaleType = if (fillHeight) SCALE_TYPE_CENTER_CROP else SCALE_TYPE_CENTER_INSIDE,
            zoomStartPosition = if (fillHeight && sideAlign) {
                if (viewer.isRtl) ZoomStartPosition.RIGHT else ZoomStartPosition.LEFT
            } else {
                ZoomStartPosition.CENTER
            },
        )

        try {
            if (secondStreamFn == null) {
                // 單頁：一般頁用 config（fit）；但「本身就是寬圖／跨頁」被單獨佔版的頁也要照對開填滿設定縮放。
                val (loaded, soloConfig) = withIOContext {
                    val source = firstStreamFn().use { Buffer().readFrom(it) }
                    val isAnimated = ImageUtil.isAnimatedAndSupported(source)
                    val background = if (!isAnimated && viewer.config.automaticBackground) {
                        ImageUtil.chooseBackground(context, source.peek().inputStream())
                    } else {
                        null
                    }
                    val cfg = if (ImageUtil.isWideImage(source)) fillConfig else config
                    Triple(source, isAnimated, background) to cfg
                }
                val (source, isAnimated, background) = loaded
                withUIContext {
                    setImage(source, isAnimated, soloConfig)
                    if (!isAnimated) pageBackground = background
                    removeErrorLayout()
                }
            } else {
                val secondPage = second ?: return
                // 對開：兩頁合成一張 Bitmap 直接餵 SSIV（無損、整個跨頁共用縮放）。但若任一頁本身是寬圖/跨頁，
                // 回報讓它單獨佔整版（避免被併成過度拼接的超寬圖）。跨頁被「兩張正常頁的邊界」拆開＝用底部
                // shift 鈕手動對齊（不同問題）。偵測只做一次（detectedFull）→ 即時翻換圖 reload 不重觸發。
                val (wides, fallbackBitmap) = withIOContext {
                    val firstSource = firstStreamFn().use { Buffer().readFrom(it) }
                    val secondSource = secondStreamFn().use { Buffer().readFrom(it) }
                    val wides = if (!detectedFull) {
                        buildList {
                            if (ImageUtil.isWideImage(firstSource)) add(first)
                            if (ImageUtil.isWideImage(secondSource)) add(secondPage)
                        }
                    } else {
                        emptyList<ReaderPage>()
                    }
                    // RTL：較前頁(first)在右、較後頁(second)在左；LTR 相反。先算好併圖當 fallback（沒拆成才用）。
                    val merged = if (viewer.isRtl) {
                        ImageUtil.mergeHorizontally(leftSource = secondSource, rightSource = firstSource)
                    } else {
                        ImageUtil.mergeHorizontally(leftSource = firstSource, rightSource = secondSource)
                    }
                    wides to merged
                }
                withUIContext {
                    if (wides.isNotEmpty()) {
                        detectedFull = true
                        // 回報重配：true＝本 holder 會被 recreate（不顯示）；false＝沒拆成 → fallback 顯示併圖、不留白。
                        if (!viewer.onFullPagesDetected(wides)) {
                            setImage(BitmapDrawable(resources, fallbackBitmap), fillConfig)
                            removeErrorLayout()
                        }
                    } else {
                        setImage(BitmapDrawable(resources, fallbackBitmap), fillConfig)
                        removeErrorLayout()
                    }
                }
            }
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e)
            withUIContext { setError(e) }
        }
    }

    private fun setError(error: Throwable?) {
        progressIndicator?.hide()
        showErrorLayout(error)
    }

    override fun onImageLoaded() {
        super.onImageLoaded()
        progressIndicator?.hide()
    }

    override fun onImageLoadError(error: Throwable?) {
        super.onImageLoadError(error)
        setError(error)
    }

    override fun onScaleChanged(newScale: Float) {
        super.onScaleChanged(newScale)
        viewer.activity.hideMenu()
    }

    private fun showErrorLayout(error: Throwable?): ReaderErrorBinding {
        if (errorLayout == null) {
            errorLayout = ReaderErrorBinding.inflate(LayoutInflater.from(context), this, true)
            errorLayout?.actionRetry?.viewer = viewer
            errorLayout?.actionRetry?.setOnClickListener {
                first.chapter.pageLoader?.retryPage(first)
                second?.let { it.chapter.pageLoader?.retryPage(it) }
            }
        }

        val imageUrl = first.imageUrl
        errorLayout?.actionOpenInWebView?.isVisible = imageUrl != null
        if (imageUrl != null && imageUrl.startsWith("http", true)) {
            errorLayout?.actionOpenInWebView?.viewer = viewer
            errorLayout?.actionOpenInWebView?.setOnClickListener {
                val sourceId = viewer.activity.viewModel.manga?.source
                val intent = WebViewActivity.newIntent(context, imageUrl, sourceId)
                context.startActivity(intent)
            }
        }

        errorLayout?.errorMessage?.text = with(context) { error?.formattedMessage }
            ?: context.stringResource(MR.strings.decode_image_error)

        errorLayout?.root?.isVisible = true
        return errorLayout!!
    }

    private fun removeErrorLayout() {
        errorLayout?.root?.isVisible = false
        errorLayout = null
    }
}
