package eu.kanade.tachiyomi.data.translation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 「翻譯正在吃 CPU」的全行程計數：夜讀據此自動讓路（見 [eu.kanade.tachiyomi.data.nightread.NightGovernor]）。
 *
 * 放在 object 而不是 [PageTranslator] 實例上：PageTranslator 有好幾個實例（佇列、reader、即時翻 loader…），計數要全行程共用。
 * 包住三個會吃 CPU 的翻譯入口：佇列的翻譯／重繪項（`TranslationManager.runDrain`，含即時翻整章插隊與跨章預取）、reader「翻譯這頁」
 * （[PageTranslator.translateSinglePage]）、reader「重繪這頁」（[PageTranslator.reRenderPage]）。引擎建構與暖機另由
 * [TranslationEngineService.loading] 反映，兩者合成夜讀的忙碌訊號。
 */
object TranslationBusy {
    private val _active = MutableStateFlow(0)

    /** 目前在跑的翻譯入口數（> 0＝翻譯在跑）。 */
    val active: StateFlow<Int> = _active.asStateFlow()

    /** 在 [block] 期間把 [active] 加一。 */
    suspend fun <T> track(block: suspend () -> T): T {
        _active.update { it + 1 }
        try {
            return block()
        } finally {
            _active.update { it - 1 }
        }
    }
}
