package eu.kanade.tachiyomi.ui.reader

import eu.kanade.tachiyomi.ui.reader.OnlineDownloadPoll.Step
import eu.kanade.tachiyomi.ui.reader.OnlineNightPrompt.Action
import eu.kanade.tachiyomi.ui.reader.OnlineNightPrompt.Trigger
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** 閱讀器裡線上章的流程判斷：夜讀要不要問「先下載嗎？」、按了下載之後每次輪詢怎麼走。 */
class OnlineChapterFlowTest {

    private fun decide(
        trigger: Trigger,
        online: Boolean = true,
        nightEnabled: Boolean = true,
        asked: Boolean = false,
        requested: Boolean = false,
        liveNightCovers: Boolean = false,
    ) = OnlineNightPrompt.decide(trigger, online, nightEnabled, asked, requested, liveNightCovers)

    @Test
    fun `不是線上章或夜讀總開關關著：照原本的路走`() {
        Trigger.entries.forEach { t ->
            decide(t, online = false) shouldBe Action.NONE
            decide(t, nightEnabled = false) shouldBe Action.NONE
            decide(t, online = false, requested = true) shouldBe Action.NONE
        }
    }

    @Test
    fun `線上章第一次：兩種觸發都問`() {
        decide(Trigger.NIGHT_ON) shouldBe Action.ASK
        decide(Trigger.EXPLICIT) shouldBe Action.ASK
    }

    @Test
    fun `打開夜讀模式每話只問一次；明確要夜讀版每次都問`() {
        decide(Trigger.NIGHT_ON, asked = true) shouldBe Action.SKIP
        decide(Trigger.EXPLICIT, asked = true) shouldBe Action.ASK
    }

    @Test
    fun `已經按了下載：明確要時提示下載中，打開夜讀模式時什麼都不做`() {
        decide(Trigger.EXPLICIT, requested = true) shouldBe Action.DOWNLOADING
        decide(Trigger.EXPLICIT, asked = true, requested = true) shouldBe Action.DOWNLOADING
        decide(Trigger.NIGHT_ON, requested = true) shouldBe Action.SKIP
    }

    @Test
    fun `即時翻譯會一併產生夜讀版：兩種觸發都不問（問過、按過下載也一樣）`() {
        Trigger.entries.forEach { t ->
            decide(t, liveNightCovers = true) shouldBe Action.LIVE_COVERED
            decide(t, asked = true, liveNightCovers = true) shouldBe Action.LIVE_COVERED
            decide(t, requested = true, liveNightCovers = true) shouldBe Action.LIVE_COVERED
            decide(t, asked = true, requested = true, liveNightCovers = true) shouldBe Action.LIVE_COVERED
        }
    }

    @Test
    fun `即時翻譯會一併產生夜讀版，但不是線上章或夜讀總開關關著：仍照原本的路走`() {
        Trigger.entries.forEach { t ->
            decide(t, online = false, liveNightCovers = true) shouldBe Action.NONE
            decide(t, nightEnabled = false, liveNightCovers = true) shouldBe Action.NONE
        }
    }

    @Test
    fun `即時翻譯不會一併產生夜讀版：照常問`() {
        decide(Trigger.NIGHT_ON, liveNightCovers = false) shouldBe Action.ASK
        decide(Trigger.EXPLICIT, liveNightCovers = false) shouldBe Action.ASK
        decide(Trigger.NIGHT_ON, asked = true, liveNightCovers = false) shouldBe Action.SKIP
        decide(Trigger.EXPLICIT, requested = true, liveNightCovers = false) shouldBe Action.DOWNLOADING
    }

    @Test
    fun `輪詢：使用者換到別話最優先`() {
        OnlineDownloadPoll.step(stillCurrent = false, dirFound = true, isDirectory = true, timedOut = false) shouldBe
            Step.LEFT
        OnlineDownloadPoll.step(stillCurrent = false, dirFound = false, isDirectory = false, timedOut = true) shouldBe
            Step.LEFT
    }

    @Test
    fun `輪詢：章節夾出現就重載（壓縮檔就放棄），出現時不管有沒有逾時`() {
        OnlineDownloadPoll.step(stillCurrent = true, dirFound = true, isDirectory = true, timedOut = false) shouldBe
            Step.RELOAD
        OnlineDownloadPoll.step(stillCurrent = true, dirFound = true, isDirectory = true, timedOut = true) shouldBe
            Step.RELOAD
        OnlineDownloadPoll.step(stillCurrent = true, dirFound = true, isDirectory = false, timedOut = false) shouldBe
            Step.ARCHIVE
    }

    @Test
    fun `輪詢：還沒出現就等，等太久就放棄`() {
        OnlineDownloadPoll.step(stillCurrent = true, dirFound = false, isDirectory = false, timedOut = false) shouldBe
            Step.WAIT
        OnlineDownloadPoll.step(stillCurrent = true, dirFound = false, isDirectory = false, timedOut = true) shouldBe
            Step.TIMEOUT
    }
}
