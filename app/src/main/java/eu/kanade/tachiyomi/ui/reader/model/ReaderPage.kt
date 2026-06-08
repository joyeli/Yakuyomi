package eu.kanade.tachiyomi.ui.reader.model

import eu.kanade.tachiyomi.source.model.Page
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.InputStream

open class ReaderPage(
    index: Int,
    url: String = "",
    imageUrl: String? = null,
    var stream: (() -> InputStream)? = null,
) : Page(index, url, imageUrl, null) {

    open lateinit var chapter: ReaderChapter

    /**
     * 即時翻譯換頁用的「重畫」訊號（單調遞增計數）。頁圖檔在原處被覆蓋成譯圖後，呼叫 [reload] 讓正在顯示本頁的
     * holder 重新 decode 當前檔。用獨立計數而非靠 status `Ready→Queue→Ready`：計數每次都是新值，**永遠不會**被
     * StateFlow 同值 conflate 成 no-op（那正是「翻完某頁沒換、卡在原文」的根因，尤其滑動中 Main 忙時）。
     */
    private val _reloadFlow = MutableStateFlow(0)
    val reloadFlow: StateFlow<Int> = _reloadFlow.asStateFlow()

    /** 通知正在顯示本頁的 holder 重新 decode 當前檔（頁圖被就地覆蓋成譯圖後呼叫）。 */
    fun reload() {
        _reloadFlow.value++
    }
}
