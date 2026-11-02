package eu.kanade.tachiyomi.ui.reader

import eu.kanade.tachiyomi.data.translation.NightLink
import eu.kanade.tachiyomi.data.translation.model.QueuePoolKey
import eu.kanade.tachiyomi.data.translation.model.TranslationItem
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

/**
 * 佇列快照 → 閱讀器當前章的角落進度（[liveTranslateProgressOf]）與夜讀懸浮鈕進度（[nightProgressOf]）：
 * 翻譯＋夜讀的合併工作在兩邊是同一個進度、同一個暫停來源（翻譯那邊）；夜讀項照夜讀那邊。
 */
class ReaderQueueProgressTest {

    private val manga = Manga.create().copy(id = 1L)

    private fun chapter(id: Long) = Chapter.create().copy(id = id, mangaId = 1L)

    private fun translate(
        id: Long,
        status: TranslationItem.Status,
        done: Int = 0,
        total: Int = 0,
        link: NightLink = NightLink.NONE,
        nightActive: Boolean = false,
        paused: Boolean = false,
    ) = TranslationItem(
        manga = manga,
        chapter = chapter(id),
        status = status,
        done = done,
        total = total,
        kind = TranslationItem.Kind.TRANSLATE,
        paused = paused,
        nightLink = link,
        nightActive = nightActive,
    )

    /** 合併工作、這一輪正在一併產生夜讀版。 */
    private fun combinedRunning(id: Long, done: Int, total: Int) = translate(
        id,
        TranslationItem.Status.TRANSLATING,
        done = done,
        total = total,
        link = NightLink.AUTO,
        nightActive = true,
    )

    private fun night(
        id: Long,
        status: TranslationItem.Status,
        done: Int = 0,
        total: Int = 0,
        paused: Boolean = false,
        waitingForCombined: Boolean = false,
    ) = TranslationItem(
        manga = manga,
        chapter = chapter(id),
        status = status,
        done = done,
        total = total,
        kind = TranslationItem.Kind.NIGHT,
        paused = paused,
        waitingForCombined = waitingForCombined,
    )

    private fun nightOf(
        queue: List<TranslationItem>,
        chapterId: Long? = 1L,
        nightEnabled: Boolean = true,
        translatePaused: Boolean = false,
        nightPaused: Boolean = false,
        pausedMangas: Set<QueuePoolKey> = emptySet(),
    ) = nightProgressOf(queue, chapterId, nightEnabled, translatePaused, nightPaused, pausedMangas)

    @Test
    fun `合併工作翻譯中：兩個指示器都標合併、進度相同`() {
        val queue = listOf(
            combinedRunning(1L, done = 6, total = 18),
        )
        val live = liveTranslateProgressOf(queue, 1L, nightEnabled = true)!!
        val n = nightOf(queue)!!
        live.withNight shouldBe true
        live.queued shouldBe false
        n.combined shouldBe true
        n.running shouldBe true
        n.paused shouldBe false
        (live.done to live.total) shouldBe (n.done to n.total)
        (n.done to n.total) shouldBe (6 to 18)
    }

    @Test
    fun `合併工作排隊中：兩邊都是合併、沒在跑`() {
        val queue = listOf(translate(1L, TranslationItem.Status.QUEUE, link = NightLink.EXPLICIT))
        val live = liveTranslateProgressOf(queue, 1L, nightEnabled = true)!!
        live.withNight shouldBe true
        live.queued shouldBe true
        val n = nightOf(queue)!!
        n.combined shouldBe true
        n.running shouldBe false
        n.paused shouldBe false
    }

    @Test
    fun `合併工作排隊中被翻譯那邊任一層暫停：paused；夜讀 pool 暫停不算`() {
        val queue = listOf(translate(1L, TranslationItem.Status.QUEUE, link = NightLink.AUTO))
        nightOf(queue, translatePaused = true)!!.paused shouldBe true
        nightOf(queue, pausedMangas = setOf(QueuePoolKey(1L, night = false)))!!.paused shouldBe true
        nightOf(listOf(translate(1L, TranslationItem.Status.QUEUE, link = NightLink.AUTO, paused = true)))!!
            .paused shouldBe true
        nightOf(queue, nightPaused = true)!!.paused shouldBe false
        nightOf(queue, pausedMangas = setOf(QueuePoolKey(1L, night = true)))!!.paused shouldBe false
    }

    @Test
    fun `只翻譯的輪、沒有合併意圖、失敗、夜讀總開關關：不算合併`() {
        val onlyTranslate = listOf(
            translate(1L, TranslationItem.Status.TRANSLATING, done = 2, total = 9, link = NightLink.AUTO),
        )
        liveTranslateProgressOf(onlyTranslate, 1L, nightEnabled = true)!!.withNight shouldBe false
        nightOf(onlyTranslate) shouldBe null

        val plain = listOf(translate(1L, TranslationItem.Status.TRANSLATING, done = 2, total = 9))
        liveTranslateProgressOf(plain, 1L, nightEnabled = true)!!.withNight shouldBe false
        nightOf(plain) shouldBe null

        val failed = listOf(translate(1L, TranslationItem.Status.ERROR, link = NightLink.AUTO))
        liveTranslateProgressOf(failed, 1L, nightEnabled = true) shouldBe null
        nightOf(failed) shouldBe null

        val running = listOf(
            combinedRunning(1L, done = 2, total = 9),
        )
        liveTranslateProgressOf(running, 1L, nightEnabled = false)!!.withNight shouldBe false
        nightOf(running, nightEnabled = false) shouldBe null
    }

    @Test
    fun `當前章有夜讀項：照夜讀項（暫停看夜讀那邊）`() {
        val queue = listOf(
            combinedRunning(1L, done = 6, total = 18),
            night(1L, TranslationItem.Status.TRANSLATING, done = 3, total = 18),
        )
        val n = nightOf(queue)!!
        n.combined shouldBe false
        n.running shouldBe true
        (n.done to n.total) shouldBe (3 to 18)

        val queued = listOf(night(1L, TranslationItem.Status.QUEUE))
        nightOf(queued, nightPaused = true)!!.paused shouldBe true
        nightOf(queued, translatePaused = true)!!.paused shouldBe false
        nightOf(queued, pausedMangas = setOf(QueuePoolKey(1L, night = true)))!!.paused shouldBe true
    }

    @Test
    fun `同章排著的夜讀項不蓋過合併工作：照合併工作，兩個指示器進度相同`() {
        val queue = listOf(
            combinedRunning(1L, done = 6, total = 18),
            night(1L, TranslationItem.Status.QUEUE, waitingForCombined = true),
        )
        val n = nightOf(queue, nightPaused = true)!!
        n.combined shouldBe true
        n.running shouldBe true
        n.paused shouldBe false
        (n.done to n.total) shouldBe (6 to 18)
        val live = liveTranslateProgressOf(queue, 1L, nightEnabled = true)!!
        (live.done to live.total) shouldBe (n.done to n.total)
    }

    @Test
    fun `夜讀項失敗不算進行中：有合併工作就照合併工作`() {
        val queue = listOf(
            night(1L, TranslationItem.Status.ERROR),
            translate(1L, TranslationItem.Status.QUEUE, link = NightLink.AUTO),
        )
        nightOf(queue)!!.combined shouldBe true
        nightOf(listOf(night(1L, TranslationItem.Status.ERROR))) shouldBe null
    }

    @Test
    fun `等合併工作做完的夜讀項：不是 running、也不是 paused`() {
        val queue = listOf(
            combinedRunning(2L, done = 1, total = 5),
            night(1L, TranslationItem.Status.QUEUE, waitingForCombined = true),
        )
        val n = nightOf(queue)!!
        n.running shouldBe false
        n.paused shouldBe false
        n.combined shouldBe false
    }

    @Test
    fun `別章的項、夜讀項、沒有當前章：不影響`() {
        val queue = listOf(
            combinedRunning(2L, done = 1, total = 5),
        )
        liveTranslateProgressOf(queue, 1L, nightEnabled = true) shouldBe null
        nightOf(queue) shouldBe null
        liveTranslateProgressOf(queue, null, nightEnabled = true) shouldBe null
        nightOf(queue, chapterId = null) shouldBe null
        // 只有夜讀項：角落指示器不亮（夜讀不是翻譯）
        liveTranslateProgressOf(listOf(night(1L, TranslationItem.Status.TRANSLATING)), 1L, true) shouldBe null
    }
}
