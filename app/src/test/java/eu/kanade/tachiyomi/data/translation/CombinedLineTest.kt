package eu.kanade.tachiyomi.data.translation

import eu.kanade.tachiyomi.data.translation.CombinedLine.TranslateOutcome
import eu.kanade.tachiyomi.data.translation.CombinedNight.PagePlan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * [CombinedLine]（翻譯＋夜讀一條線的排程）：假的翻譯與夜讀、虛擬時間。驗線位上限、翻譯併發、章內順序、DONE／NIGHT 頁、
 * 過期補掃、q4、夜讀段中途關掉、工作要停、夜讀段開不起來。
 */
class CombinedLineTest {

    private enum class NightOutcome { DONE, STALE, FAILED }

    /** 假的「磁碟」：翻譯清單與夜讀版新鮮的頁。 */
    private class Disk(manifest: Set<Int> = emptySet(), fresh: Set<Int> = emptySet()) {
        val manifest = HashSet(manifest)
        val fresh = HashSet(fresh)
    }

    /**
     * 假夜讀段：送進來的頁照順序、同時最多 [cap] 頁，每頁 [nightMs] 後照 [outcome] 回報。[shouldStop] 成立就收工（手上的頁不回報＝
     * 被丟掉）；[failOpen]＝一開始就拋例外。
     */
    private class FakeNight(
        private val disk: Disk,
        private val cap: Int = 2,
        private val nightMs: Long = 9_000,
        private val failOpen: Boolean = false,
        private val outcome: (page: Int, attempt: Int) -> NightOutcome = { _, _ -> NightOutcome.DONE },
    ) : CombinedLine.NightStage<Int> {
        lateinit var line: CombinedLine<Int>
        val submitted = ArrayList<Int>()
        val finished = ArrayList<Int>()
        private val pending = ArrayDeque<Int>()
        private val attempts = HashMap<Int, Int>()
        private var closed = false

        override fun submit(page: Int): Boolean {
            synchronized(this) {
                if (closed) return false
                submitted += page
                pending.addLast(page)
            }
            return true
        }

        override fun close() {
            synchronized(this) { closed = true }
        }

        override suspend fun run(shouldStop: () -> Boolean) {
            if (failOpen) throw IllegalStateException("nets open failed")
            coroutineScope {
                val sem = Semaphore(cap)
                while (!shouldStop()) {
                    val next = synchronized(this@FakeNight) { pending.removeFirstOrNull() }
                    if (next == null) {
                        if (synchronized(this@FakeNight) { closed }) break
                        delay(50)
                        continue
                    }
                    sem.acquire()
                    launch {
                        try {
                            // 做一頁；途中每 100 ms 一個檢查點（停下＝丟掉這頁，不回報）
                            var left = nightMs
                            while (left > 0) {
                                val step = minOf(100L, left)
                                delay(step)
                                left -= step
                                if (shouldStop()) return@launch
                            }
                            val n = synchronized(this@FakeNight) {
                                (attempts.getOrDefault(next, 0) + 1).also { attempts[next] = it }
                            }
                            when (outcome(next, n)) {
                                NightOutcome.DONE -> {
                                    disk.fresh += next
                                    finished += next
                                    line.nightFinal(next, NightPageResult.DONE)
                                }
                                NightOutcome.FAILED -> {
                                    finished += next
                                    line.nightFinal(next, NightPageResult.FAILED)
                                }
                                NightOutcome.STALE -> line.nightStale(next)
                            }
                        } finally {
                            sem.release()
                        }
                    }
                }
            }
        }
    }

    /** 跑一輪的設定與觀測。 */
    private class Harness(
        val pages: List<Int>,
        val plans: Map<Int, PagePlan>,
        val disk: Disk,
        val translateMs: Long = 3_000,
        val translateOutcome: (Int) -> TranslateOutcome = { TranslateOutcome.SETTLED },
    ) {
        var translating = 0
        var maxTranslating = 0
        val translated = ArrayList<Int>()
        val startedAt = HashMap<Int, Long>()
        val progress = ArrayList<Pair<Int, Int>>()
        var stop = false
        var nightWanted = true
        var sweepRequests = 0
        var nightFailures = 0
        var maxHeldSeen = 0
        var afterTranslateAt = -1L

        /** 每頁翻譯做完（落地）那一刻 [CombinedLine.holdAllowed] 的值。 */
        val holdAtLand = HashMap<Int, Boolean>()
    }

    private fun TestScope.line(h: Harness, slots: Int, depth: Int): CombinedLine<Int> {
        lateinit var line: CombinedLine<Int>
        line = CombinedLine(
            pages = h.pages,
            plans = h.plans,
            slotCount = slots,
            translateDepth = depth,
            translate = { p ->
                h.translated += p
                h.startedAt[p] = testScheduler.currentTime
                h.translating++
                if (h.translating > h.maxTranslating) h.maxTranslating = h.translating
                h.maxHeldSeen = maxOf(h.maxHeldSeen, line.heldCount)
                try {
                    delay(h.translateMs)
                } finally {
                    h.translating--
                }
                h.holdAtLand[p] = line.holdAllowed
                h.translateOutcome(p).also {
                    if (it == TranslateOutcome.SETTLED || it == TranslateOutcome.SETTLED_FRESH) h.disk.manifest += p
                }
            },
            jobStop = { h.stop },
            nightWanted = { h.nightWanted },
            sweepRequested = {
                if (h.sweepRequests > 0) {
                    h.sweepRequests--
                    true
                } else {
                    false
                }
            },
            manifestPages = { HashSet(h.disk.manifest) },
            nightFreshPages = { HashSet(h.disk.fresh) },
            afterTranslatePhase = { h.afterTranslateAt = testScheduler.currentTime },
            onProgress = { done, total ->
                h.progress += done to total
                h.maxHeldSeen = maxOf(h.maxHeldSeen, line.heldCount)
            },
            onNightFailed = { h.nightFailures++ },
        )
        return line
    }

    private fun plansOf(pages: List<Int>, done: Set<Int> = emptySet(), night: Set<Int> = emptySet()) =
        pages.associateWith {
            when (it) {
                in done -> PagePlan.DONE
                in night -> PagePlan.NIGHT
                else -> PagePlan.TRANSLATE
            }
        }

    @Test
    fun `線位與翻譯併發都有上限，頁照章內順序，DONE 頁開頭就算完成、NIGHT 頁直接送夜讀`() = runTest {
        val pages = (1..10).toList()
        val disk = Disk(manifest = setOf(1, 2), fresh = setOf(1))
        val h = Harness(pages, plansOf(pages, done = setOf(1), night = setOf(2)), disk)
        val night = FakeNight(disk, cap = 2, nightMs = 9_000)
        val line = line(h, slots = 3, depth = 2)
        night.line = line
        val result = async { line.run(night) }
        // 線位上限：開跑後隨時取樣
        launch {
            while (!result.isCompleted) {
                h.maxHeldSeen = maxOf(h.maxHeldSeen, line.heldCount)
                delay(100)
            }
        }
        advanceUntilIdle()
        val r = result.await()

        h.translated shouldBe (3..10).toList() // DONE、NIGHT 頁不翻；其餘照章內順序取線位
        (h.maxTranslating <= 2) shouldBe true // min(depth, slots)
        (h.maxHeldSeen <= 3) shouldBe true
        (r.maxHeld <= 3) shouldBe true
        night.submitted.first() shouldBe 2 // 只做夜讀的頁最先送
        night.submitted.sorted() shouldBe (2..10).toList()
        h.progress.first() shouldBe (1 to 10) // DONE 頁開頭就算完成
        h.progress.last() shouldBe (10 to 10)
        r.output shouldBe 10
        r.nightDone shouldBe 9
        r.nightFailed shouldBe 0
        r.stageCompleted shouldBe true
        r.stageGaveUp shouldBe false
        // 翻譯最多領先夜讀一頁：夜讀 2 頁並行時，同時被線位佔著的頁不超過 3
        line.heldCount shouldBe 0
    }

    @Test
    fun `過期的頁放線位、不算完成，收尾補掃再送（上限 2 次）`() = runTest {
        val pages = (1..3).toList()
        val disk = Disk()
        val h = Harness(pages, plansOf(pages), disk, translateMs = 1_000)
        // 第 2 頁每次都過期
        val night = FakeNight(disk, nightMs = 2_000) { p, _ -> if (p == 2) NightOutcome.STALE else NightOutcome.DONE }
        val line = line(h, slots = 3, depth = 2)
        night.line = line
        val result = async { line.run(night) }
        advanceUntilIdle()
        val r = result.await()
        night.submitted.count { it == 2 } shouldBe 3 // 第一次＋補掃兩次
        r.output shouldBe 2
        h.progress.last() shouldBe (2 to 3)
        line.heldCount shouldBe 0
    }

    @Test
    fun `過期一次、補掃後做好：算完整輸出`() = runTest {
        val pages = (1..3).toList()
        val disk = Disk()
        val h = Harness(pages, plansOf(pages), disk, translateMs = 1_000)
        val night = FakeNight(disk, nightMs = 2_000) { p, n ->
            if (p == 2 && n == 1) NightOutcome.STALE else NightOutcome.DONE
        }
        val line = line(h, slots = 3, depth = 2)
        night.line = line
        val result = async { line.run(night) }
        advanceUntilIdle()
        val r = result.await()
        night.submitted.count { it == 2 } shouldBe 2
        r.output shouldBe 3
        r.nightDone shouldBe 3
        r.stageCompleted shouldBe true
    }

    @Test
    fun `翻譯失敗的頁放線位、不送夜讀；翻譯階段做完後依清單決定 q4（扣掉不可靠頁）`() = runTest {
        val pages = (1..4).toList()
        val disk = Disk()
        val h = Harness(pages, plansOf(pages), disk, translateMs = 1_000) {
            when (it) {
                2 -> TranslateOutcome.FAILED
                3 -> TranslateOutcome.UNRELIABLE
                else -> TranslateOutcome.SETTLED
            }
        }
        val submittedAt = HashMap<Int, Long>()
        val fake = FakeNight(disk, nightMs = 2_000)
        val line = line(h, slots = 3, depth = 2)
        fake.line = line
        val recording = object : CombinedLine.NightStage<Int> {
            override fun submit(page: Int): Boolean {
                submittedAt[page] = testScheduler.currentTime
                return fake.submit(page)
            }

            override fun close() = fake.close()

            override suspend fun run(shouldStop: () -> Boolean) = fake.run(shouldStop)
        }
        val result = async { line.run(recording) }
        advanceUntilIdle()
        val r = result.await()
        fake.submitted.sorted() shouldBe listOf(1, 2, 4) // 3 不可靠，q4 不補
        (submittedAt.getValue(2) >= h.afterTranslateAt) shouldBe true // q4 在翻譯階段做完之後
        r.output shouldBe 3
        line.heldCount shouldBe 0
    }

    @Test
    fun `q4：翻譯又失敗的頁夜讀版本來就新鮮時不重做，直接算完整輸出`() = runTest {
        val pages = (1..4).toList()
        // 第 2 頁上一輪就翻譯失敗、q4 用原圖做過夜讀版（新鮮）；第 3 頁沒做過
        val disk = Disk(fresh = setOf(2))
        val h = Harness(pages, plansOf(pages), disk, translateMs = 1_000) {
            if (it == 2 || it == 3) TranslateOutcome.FAILED else TranslateOutcome.SETTLED
        }
        val night = FakeNight(disk, nightMs = 2_000)
        val line = line(h, slots = 3, depth = 2)
        night.line = line
        val result = async { line.run(night) }
        advanceUntilIdle()
        val r = result.await()
        night.submitted.sorted() shouldBe listOf(1, 3, 4) // 2 已新鮮、不再送
        r.output shouldBe 4
        r.nightDone shouldBe 3
        h.progress.last() shouldBe (4 to 4)
        line.heldCount shouldBe 0
    }

    @Test
    fun `整話全失敗：不補夜讀`() = runTest {
        val pages = (1..3).toList()
        val disk = Disk()
        val h = Harness(pages, plansOf(pages), disk, translateMs = 1_000) { TranslateOutcome.FAILED }
        val night = FakeNight(disk, nightMs = 2_000)
        val line = line(h, slots = 3, depth = 2)
        night.line = line
        val result = async { line.run(night) }
        advanceUntilIdle()
        val r = result.await()
        night.submitted shouldBe emptyList()
        r.output shouldBe 0
        line.heldCount shouldBe 0
    }

    @Test
    fun `夜讀段中途關掉：佔著的線位立刻放掉、翻譯繼續做完、之後的頁不送夜讀、完成數改算翻譯落地頁、不卡死`() = runTest {
        val pages = (1..8).toList()
        val disk = Disk()
        val h = Harness(pages, plansOf(pages), disk, translateMs = 1_000)
        val night = FakeNight(disk, cap = 2, nightMs = 60_000) // 很慢：線位很快被夜讀佔滿
        val line = line(h, slots = 3, depth = 2)
        night.line = line
        val result = async { line.run(night) }
        var heldBeforeOff = -1
        launch {
            delay(5_000)
            heldBeforeOff = line.heldCount // 被夜讀佔滿，主迴圈在等線位
            h.nightWanted = false
        }
        advanceUntilIdle()
        val r = result.await()
        heldBeforeOff shouldBe 3
        // 夜讀不要了＝放棄：之後落地的頁不再 hold
        h.holdAtLand.getValue(1) shouldBe true
        h.holdAtLand.getValue(8) shouldBe false
        line.holdAllowed shouldBe false
        // 夜讀段佔著的線位立刻放掉：下一頁在一個輪詢週期內開始翻
        (h.startedAt.getValue(4) - 5_000 in 0..CombinedLine.POLL_MS) shouldBe true
        h.translated shouldBe pages // 翻譯照樣做完
        night.submitted shouldBe listOf(1, 2, 3) // 關掉之後不再送
        night.finished shouldBe emptyList()
        r.output shouldBe 8 // 改算翻譯落地頁
        h.progress.last() shouldBe (8 to 8)
        r.stageCompleted shouldBe false
        r.stageGaveUp shouldBe false
        line.heldCount shouldBe 0
    }

    @Test
    fun `工作要停：主迴圈不再取線位（含正在等線位時）、在飛翻譯做完、函式返回`() = runTest {
        val pages = (1..8).toList()
        val disk = Disk()
        val h = Harness(pages, plansOf(pages), disk, translateMs = 1_000)
        val night = FakeNight(disk, cap = 2, nightMs = 1_000_000) // 永遠做不完：主迴圈一定在等線位
        val line = line(h, slots = 3, depth = 2)
        night.line = line
        var returnedAt = -1L
        val result = async { line.run(night).also { returnedAt = testScheduler.currentTime } }
        launch {
            delay(10_000)
            h.stop = true
        }
        advanceUntilIdle()
        val r = result.await()
        h.translated shouldBe listOf(1, 2, 3) // 線位滿了之後沒再取
        (returnedAt in 10_000..11_000) shouldBe true
        h.translating shouldBe 0
        r.stageCompleted shouldBe false
    }

    @Test
    fun `工作要停：在飛的翻譯照樣做完再返回`() = runTest {
        val pages = (1..6).toList()
        val disk = Disk()
        val h = Harness(pages, plansOf(pages), disk, translateMs = 5_000)
        val night = FakeNight(disk, cap = 2, nightMs = 1_000)
        val line = line(h, slots = 3, depth = 2)
        night.line = line
        val result = async { line.run(night) }
        launch {
            delay(2_000) // 第 1、2 頁還在翻
            h.stop = true
        }
        advanceUntilIdle()
        val r = result.await()
        h.translated shouldBe listOf(1, 2)
        disk.manifest shouldBe setOf(1, 2) // 在飛的兩頁照樣落地
        h.translating shouldBe 0
        // 被要求停不算放棄夜讀：在飛的兩頁落地時照樣 hold（夜讀下一輪先做），也不算完整輸出
        h.holdAtLand shouldBe mapOf(1 to true, 2 to true)
        line.holdAllowed shouldBe true
        r.output shouldBe 0
        line.heldCount shouldBe 0
    }

    @Test
    fun `夜讀段一開始就開不起來：等同全程只翻譯`() = runTest {
        val pages = (1..5).toList()
        val disk = Disk()
        val h = Harness(pages, plansOf(pages), disk, translateMs = 1_000)
        val night = FakeNight(disk, failOpen = true)
        val line = line(h, slots = 3, depth = 2)
        night.line = line
        val result = async { line.run(night) }
        advanceUntilIdle()
        val r = result.await()
        h.nightFailures shouldBe 1
        h.translated shouldBe pages
        r.output shouldBe 5
        r.stageGaveUp shouldBe true
        r.stageCompleted shouldBe false
        line.isNightOpen shouldBe false
        line.holdAllowed shouldBe false
        line.heldCount shouldBe 0
    }

    @Test
    fun `沒有夜讀段（只翻譯）：落地就算完整輸出`() = runTest {
        val pages = (1..4).toList()
        val disk = Disk(manifest = setOf(1))
        val h = Harness(pages, plansOf(pages, night = setOf(1)), disk, translateMs = 1_000)
        val line = line(h, slots = 3, depth = 2)
        val r = async { line.run(null) }
        advanceUntilIdle()
        val res = r.await()
        h.translated shouldBe listOf(2, 3, 4)
        res.output shouldBe 4
        res.stageCompleted shouldBe false
        h.holdAtLand.values.toSet() shouldBe setOf(false) // 沒有夜讀段：不 hold
    }

    @Test
    fun `頁檔沒變、夜讀版本來就新鮮的頁：不送夜讀、直接算完成`() = runTest {
        val pages = (1..3).toList()
        val disk = Disk(fresh = setOf(2))
        val h = Harness(pages, plansOf(pages), disk, translateMs = 1_000) {
            if (it == 2) TranslateOutcome.SETTLED_FRESH else TranslateOutcome.SETTLED
        }
        val night = FakeNight(disk, nightMs = 2_000)
        val line = line(h, slots = 3, depth = 2)
        night.line = line
        val result = async { line.run(night) }
        advanceUntilIdle()
        val r = result.await()
        night.submitted.sorted() shouldBe listOf(1, 3)
        r.output shouldBe 3
    }

    @Test
    fun `補掃要求：收尾多補掃一次，接住被單頁操作作廢的頁`() = runTest {
        val pages = (1..3).toList()
        val disk = Disk()
        val h = Harness(pages, plansOf(pages), disk, translateMs = 1_000)
        val night = FakeNight(disk, nightMs = 2_000)
        val line = line(h, slots = 3, depth = 2)
        night.line = line
        val result = async { line.run(night) }
        launch {
            // 第 1 頁的夜讀版做好之後，閱讀器「重繪這頁」把它作廢了，並要求補掃
            delay(4_000)
            disk.fresh -= 1
            h.sweepRequests = 1
        }
        advanceUntilIdle()
        val r = result.await()
        night.submitted.count { it == 1 } shouldBe 2
        r.output shouldBe 3
        disk.fresh shouldBe setOf(1, 2, 3)
    }

    @Test
    fun `夜讀失敗的頁：放線位、算完整輸出，補掃不再送`() = runTest {
        val pages = (1..3).toList()
        val disk = Disk()
        val h = Harness(pages, plansOf(pages), disk, translateMs = 1_000)
        val night = FakeNight(disk, nightMs = 2_000) { p, _ -> if (p == 3) NightOutcome.FAILED else NightOutcome.DONE }
        val line = line(h, slots = 3, depth = 2)
        night.line = line
        val result = async { line.run(night) }
        advanceUntilIdle()
        val r = result.await()
        night.submitted.count { it == 3 } shouldBe 1
        r.output shouldBe 3
        r.nightFailed shouldBe 1
        r.nightDone shouldBe 2
        line.heldCount shouldBe 0
    }
}
