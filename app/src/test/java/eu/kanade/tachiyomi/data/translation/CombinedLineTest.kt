package eu.kanade.tachiyomi.data.translation

import eu.kanade.tachiyomi.data.translation.CombinedLine.TranslateOutcome
import eu.kanade.tachiyomi.data.translation.CombinedNight.PagePlan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Test

/**
 * [CombinedLine]（翻譯＋夜讀一條線的排程）：假的翻譯與夜讀、虛擬時間。驗線位上限、翻譯併發、章內順序、DONE／NIGHT 頁、
 * 過期補掃、q4、夜讀段中途關掉、工作要停、夜讀段開不起來；提早送夜讀（軌道 B2 的 S3／S4：編碼完就送、夜讀換檔前等落地、
 * 落地失敗時夜讀當過期、q4 等它離開、工作要停／夜讀被放棄／協程被取消）。
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
        val submittedAt = ArrayList<Pair<Int, Long>>()
        val finished = ArrayList<Int>()
        val finishedAt = HashMap<Int, Long>()

        /** 提早送的頁的落地結果（拿了就消耗；null＝不是提早送的、不必等落地）：模擬夜讀換檔前等落地。 */
        var landingOf: (Int) -> CompletableDeferred<Boolean>? = { null }
        var now: () -> Long = { 0L }
        private val pending = ArrayDeque<Int>()
        private val attempts = HashMap<Int, Int>()
        private var closed = false

        override fun submit(page: Int): Boolean {
            synchronized(this) {
                if (closed) return false
                submitted += page
                submittedAt += page to now()
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
                            // 提早送的頁：換檔前等落地（被要求停＝丟掉；落地沒成功＝過期）
                            val landing = landingOf(next)
                            if (landing != null) {
                                var ok: Boolean? = null
                                while (ok == null) {
                                    if (shouldStop()) return@launch
                                    ok = withTimeoutOrNull(100) { landing.await() }
                                }
                                if (ok != true) {
                                    line.nightStale(next)
                                    return@launch
                                }
                            }
                            val n = synchronized(this@FakeNight) {
                                (attempts.getOrDefault(next, 0) + 1).also { attempts[next] = it }
                            }
                            when (outcome(next, n)) {
                                NightOutcome.DONE -> {
                                    disk.fresh += next
                                    finished += next
                                    finishedAt[next] = now()
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
        /** 非 null：翻譯開始後這麼久（編碼好）就提早送夜讀，落地在 [translateMs] 時完成。 */
        val earlyAtMs: Long? = null,
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

        /** 提早送夜讀的結果（[CombinedLine.sendEarly] 的回傳）。 */
        val earlySent = HashMap<Int, Boolean>()

        /** 提早送出去的頁的落地結果（夜讀那邊拿了就消耗）。 */
        val landings = HashMap<Int, CompletableDeferred<Boolean>>()

        /** 提早送出去的頁的落地結果（不消耗；驗收用）。 */
        val landed = HashMap<Int, CompletableDeferred<Boolean>>()
        val landedAt = HashMap<Int, Long>()
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
                var landing: CompletableDeferred<Boolean>? = null
                var settledNow = false
                try {
                    try {
                        val early = h.earlyAtMs
                        if (early != null) {
                            delay(early)
                            // 編碼好了：落地結果的 Deferred 先放好（夜讀那邊換檔前等它），再提早送
                            val d = CompletableDeferred<Boolean>()
                            h.landings[p] = d
                            val sent = line.sendEarly(p)
                            h.earlySent[p] = sent
                            if (sent) {
                                landing = d
                                h.landed[p] = d
                            } else {
                                h.landings.remove(p)
                            }
                            delay(h.translateMs - early)
                        } else {
                            delay(h.translateMs)
                        }
                    } finally {
                        h.translating--
                    }
                    h.holdAtLand[p] = line.holdAllowed
                    h.translateOutcome(p).also {
                        settledNow = it == TranslateOutcome.SETTLED || it == TranslateOutcome.SETTLED_FRESH
                        if (settledNow) h.disk.manifest += p
                        h.landedAt[p] = testScheduler.currentTime
                    }
                } finally {
                    // 落地回報（取消、例外也回報失敗：夜讀那邊不會一直等）
                    landing?.complete(settledNow)
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

    private fun FakeNight.wire(h: Harness, scope: TestScope) {
        landingOf = { p -> h.landings.remove(p) }
        now = { scope.testScheduler.currentTime }
    }

    @Test
    fun `提早送夜讀：編碼好就送、夜讀跟落地重疊，換檔等落地，收尾在所有落地之後`() = runTest {
        val pages = (1..4).toList()
        val disk = Disk()
        // 編碼在第 1 s、落地在第 3 s；夜讀 1.5 s 就做完（一定要等落地）
        val h = Harness(pages, plansOf(pages), disk, translateMs = 3_000, earlyAtMs = 1_000)
        val night = FakeNight(disk, cap = 2, nightMs = 1_500)
        night.wire(h, this)
        val line = line(h, slots = 2, depth = 2)
        night.line = line
        val result = async { line.run(night) }
        advanceUntilIdle()
        val r = result.await()

        h.earlySent shouldBe pages.associateWith { true }
        night.submitted.sorted() shouldBe pages // 每頁只送一次（定案後不再送）
        for ((p, at) in night.submittedAt) {
            // 送出時落地還沒做完（重疊），夜讀結果在落地之後
            (at < h.landedAt.getValue(p)) shouldBe true
            (night.finishedAt.getValue(p) >= h.landedAt.getValue(p)) shouldBe true
        }
        (h.afterTranslateAt >= h.landedAt.values.max()) shouldBe true
        h.holdAtLand.values.toSet() shouldBe setOf(true)
        r.output shouldBe 4
        r.nightDone shouldBe 4
        r.stageCompleted shouldBe true
        h.progress.last() shouldBe (4 to 4)
        line.heldCount shouldBe 0
    }

    @Test
    fun `提早送夜讀、落地失敗：夜讀當過期丟掉，q4 等它離開夜讀段後再用原圖送`() = runTest {
        val pages = (1..3).toList()
        val disk = Disk()
        val h = Harness(pages, plansOf(pages), disk, translateMs = 3_000, earlyAtMs = 1_000) {
            if (it == 2) TranslateOutcome.FAILED else TranslateOutcome.SETTLED
        }
        // 夜讀很慢：第 2 頁的提早送那份在翻譯階段結束後還在夜讀段手上
        val night = FakeNight(disk, cap = 1, nightMs = 6_000)
        night.wire(h, this)
        val line = line(h, slots = 3, depth = 3)
        night.line = line
        val result = async { line.run(night) }
        advanceUntilIdle()
        val r = result.await()

        h.earlySent.getValue(2) shouldBe true
        h.landed.getValue(2).await() shouldBe false
        val subs2 = night.submittedAt.filter { it.first == 2 }
        subs2.size shouldBe 2 // 提早送一次（過期丟掉）＋ q4 一次
        (subs2[1].second >= h.afterTranslateAt) shouldBe true // q4 在翻譯階段之後
        // q4 那份在提早送那份離開夜讀段（過期）之後才送：夜讀 cap 1、依序做，第 2 頁的提早那份是第二個做完的
        (subs2[1].second >= 2 * 6_000) shouldBe true
        night.finished.count { it == 2 } shouldBe 1 // 只有 q4 那份做成
        r.output shouldBe 3
        r.nightDone shouldBe 3
        line.heldCount shouldBe 0
    }

    @Test
    fun `提早送夜讀後工作要停：在飛的落地照樣做完、函式返回、線位都放掉`() = runTest {
        val pages = (1..6).toList()
        val disk = Disk()
        val h = Harness(pages, plansOf(pages), disk, translateMs = 5_000, earlyAtMs = 1_000)
        val night = FakeNight(disk, cap = 2, nightMs = 60_000)
        night.wire(h, this)
        val line = line(h, slots = 2, depth = 2)
        night.line = line
        val result = async { line.run(night) }
        launch {
            delay(2_000) // 第 1、2 頁已提早送夜讀、還沒落地
            h.stop = true
        }
        advanceUntilIdle()
        val r = result.await()
        h.translated shouldBe listOf(1, 2)
        h.earlySent shouldBe mapOf(1 to true, 2 to true)
        disk.manifest shouldBe setOf(1, 2) // 照樣落地
        h.landed.values.all { it.isCompleted } shouldBe true
        h.holdAtLand shouldBe mapOf(1 to true, 2 to true) // 被要求停不算放棄：照樣 hold
        h.translating shouldBe 0
        night.finished shouldBe emptyList()
        r.output shouldBe 0
        r.stageCompleted shouldBe false
        line.heldCount shouldBe 0
    }

    @Test
    fun `提早送夜讀後夜讀被放棄：落地的頁直接算完整輸出、之後的頁不 hold`() = runTest {
        val pages = (1..4).toList()
        val disk = Disk()
        val h = Harness(pages, plansOf(pages), disk, translateMs = 3_000, earlyAtMs = 1_000)
        val night = FakeNight(disk, cap = 2, nightMs = 60_000)
        night.wire(h, this)
        val line = line(h, slots = 2, depth = 2)
        night.line = line
        val result = async { line.run(night) }
        launch {
            delay(2_000) // 第 1、2 頁已提早送、還在落地
            h.nightWanted = false
        }
        advanceUntilIdle()
        val r = result.await()
        h.translated shouldBe pages
        h.earlySent shouldBe mapOf(1 to true, 2 to true, 3 to false, 4 to false) // 放棄後送不進去
        h.holdAtLand.getValue(1) shouldBe false // 落地時已放棄
        night.finished shouldBe emptyList()
        r.output shouldBe 4 // 改算翻譯落地頁（提早送的兩頁也算）
        h.progress.last() shouldBe (4 to 4)
        line.heldCount shouldBe 0
    }

    @Test
    fun `提早送夜讀後協程被取消：不卡住，落地都回報（取消算失敗）`() = runTest {
        val pages = (1..4).toList()
        val disk = Disk()
        val h = Harness(pages, plansOf(pages), disk, translateMs = 5_000, earlyAtMs = 1_000)
        val night = FakeNight(disk, cap = 2, nightMs = 60_000)
        night.wire(h, this)
        val line = line(h, slots = 2, depth = 2)
        night.line = line
        val result = async { line.run(night) }
        launch {
            delay(2_000)
            result.cancel()
        }
        advanceUntilIdle()
        result.isCancelled shouldBe true
        h.earlySent shouldBe mapOf(1 to true, 2 to true)
        h.landed.values.map { it.isCompleted } shouldBe listOf(true, true)
        h.landed.values.map { it.getCompleted() } shouldBe listOf(false, false)
        h.translating shouldBe 0
    }

    @Test
    fun `沒有夜讀段時 sendEarly 回 false：照舊只翻譯`() = runTest {
        val pages = (1..3).toList()
        val disk = Disk()
        val h = Harness(pages, plansOf(pages), disk, translateMs = 2_000, earlyAtMs = 500)
        val line = line(h, slots = 2, depth = 2)
        val r = async { line.run(null) }
        advanceUntilIdle()
        val res = r.await()
        h.earlySent shouldBe pages.associateWith { false }
        res.output shouldBe 3
        line.heldCount shouldBe 0
    }
}
