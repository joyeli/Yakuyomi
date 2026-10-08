package eu.kanade.tachiyomi.data.nightread

import eu.kanade.tachiyomi.data.nightread.NightConcurrency.MB
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import li.joye.yakuyomi.engine.NcnnForwardAbortedException
import java.util.PriorityQueue

/** 一頁在 worker 上跑完的結果（[NightPump] 依此決定要不要丟回待做或補跑）。 */
enum class NightPageOutcome {
    /** 做完（含彩頁略過、過期、一般錯誤——這些由頁本身記好）。 */
    DONE,

    /** 在檢查點放棄（暫停／取消，或讓路時不是最早那頁；推論前、等翻譯讓出鎖時、重繪前）：丟回待做，不寫檔、不計 done、不記錯。 */
    DROPPED,

    /** OutOfMemoryError：記下，主迴圈結束後以上限 1 補跑一輪；補跑仍 OOM 才交給 onOomFinal。 */
    OOM,
}

/**
 * 一頁放行當下拿到的東西。頁內不可變：模型組只在沒有在飛頁時換，所以一頁從頭到尾用同一組。
 * ceiling 不在這裡包：模型組帶 [NightGovernor.forwardHook]，引擎在取全域鎖之前對這條執行緒套、放鎖之後才還原
 * （見 [NightGovernor.lockedForward]）。
 */
class NightTicket<N> internal constructor(
    val nets: N,
    val kind: NetKind,
    private val admission: NightGovernor.Admission,
    private val gate: NightGovernor.ChapterGate,
    private val work: NightGovernor.Work,
    private val shouldStop: () -> Boolean,
) {
    /** 檢查點：false＝放棄這頁（暫停／取消，或讓路時不是最早放行的那頁）。每次推論前與重繪前問；引擎等翻譯讓出鎖時也會問。 */
    fun keep(): Boolean = !shouldStop() && gate.keep(admission)

    /** 推論前的檢查點：[keep] 不成立就拋 [NcnnForwardAbortedException]（頁本身接住、回 DROPPED），不做這次推論。 */
    fun checkpoint() {
        if (!keep()) throw NcnnForwardAbortedException("夜讀頁在推論前放棄")
    }

    /**
     * perf 行前綴：`mode= n=在飛/上限 nets= w=worker q=放行等待[(卡在哪:ms,…)] heapFree= est= ceil=`
     * （卡在哪見 [NightGovernor.Block]）。
     */
    fun describe(): String {
        val slot = work.slot
        val worker = slot?.worker?.substringAfterLast('-')
        val why = admission.waitWhy.let { if (it.isEmpty()) "" else "($it)" }
        return "mode=${admission.mode.tag} n=${admission.inFlight}/${admission.limit} nets=${kind.tag} " +
            "w=$worker q=${admission.waitMs}ms$why heapFree=${admission.heapFree / MB}MB " +
            "est=${admission.est / MB}MB ceil=${slot?.ceil}"
    }
}

/**
 * 夜讀一章的逐頁放行（producer）：依 [NightGovernor] 的模式與放行閘，把頁派到夜讀 worker 上並行。
 *
 *  - **放行**：每頁先 [run] 的 `estimate`（例如只讀圖檔尺寸）算 heap 預估，再 [NightGovernor.ChapterGate.admit]；
 *    名額在子工作的 invokeOnCompletion 歸還——子工作還沒開始就被取消也一樣會還，不會洩漏。
 *  - **換模型組（排空後原地換）**：頁邊界上 [NightGovernor.wantedNets] 與目前的組不同、而且還有頁要做 → 停止放行、等在飛
 *    歸零、**再確認一次**（排空期間模式可能翻回來、頁可能做完了、使用者可能按了暫停），才在 worker 上關舊組開新組，
 *    再繼續。每頁在放行當下拿到當時的組，只在在飛＝0 時換，所以不需要任何鎖。開新組失敗 → 記下、開回原組、這章不再換；
 *    原組也開不了就往外拋（今天的章錯誤路徑）。**章首**同理：想要的組開不起來 → 改開另一組、這章不再換；兩組都開不起來
 *    才往外拋。
 *    讓路夠久該換 FREE 時，用 LOCKED 組的頁（包括最早那頁）在下一次推論前／等翻譯讓出鎖時放棄、丟回待做——否則最早那頁
 *    只能在翻譯的空檔推進，換組跟著被拖住。丟了之後換成 FREE（不進鎖），同一頁不會因為等鎖再被丟；每次換組最多白做一頁
 *    已完成的推論。
 *  - **丟回待做**：頁回 [NightPageOutcome.DROPPED]（或漏出 [NcnnForwardAbortedException]）→ 依原順序放回佇列。
 *  - **OOM**：該頁記下、這章剩下的上限 1；主迴圈做完且沒被中止 → 以上限 1 補跑，每頁只補一次，再 OOM 才 `onOomFinal`。
 *  - **例外隔離**：`estimate` 或頁本身漏出的例外交給 `onError`，不取消其他頁。
 *  - **收尾**：先看在飛、再取佇列——在飛＝0 表示所有頁的本體都結束了（丟回待做發生在歸還名額之前），之後佇列不會再變多，
 *    不會把剛丟回的頁漏掉。
 *
 * 開模型、關模型、每頁都在 worker 上跑、登記進 governor（套當下模式的 nice），並綁一段 [NightGovernor.Work]
 * （引擎掛鉤靠它找這條執行緒的 slot 與中止條件）。開組（含暖機）的中止條件＝暫停／取消，或開的是 LOCKED 組、還能換組、
 * 而讓路已久該換 FREE（開好也馬上要換掉，LOCKED 暖機又只能在翻譯的空檔拿鎖）：暖機的前向在等鎖期間放棄（暖機自己吞掉
 * 例外、開好的組照樣回傳），主迴圈隨即看到停止旗標收工、或換成 FREE。放棄之後、producer 回到迴圈頂端之前讓路剛好結束
 * （[NightGovernor.wantedNets] 翻回 LOCKED）就不換：這章用沒暖過機的 LOCKED 組做。這是安全的——LOCKED 組的前向都在引擎的
 * 全域鎖內序列化，碰不到「多頁同時打進未初始化的 Net」那種 crash；代價只是第一頁的計時含首次初始化、暖機記下失敗的 WARN。
 *
 * **模型組的所有權**：`nets` 永遠記著還沒關的那組（收尾由 finally 關）。開好的組在 worker 上就記進 `nets`（`withContext` 回來前
 * 協程被取消會丟掉回傳值，記在那裡 finally 才關得到）；換組時舊組也在 worker 上才從 `nets` 拿掉並關（恰好一次）——
 * 協程在派到 worker 之前就被取消時那段不會跑，舊組還記在 `nets`、由 finally 關，不會漏。
 */
class NightPump<P : Any, N : AutoCloseable>(
    private val governor: NightGovernor,
    private val workers: CoroutineDispatcher = NightWorkers.dispatcher,
) {
    /** 這章的放行閘（在飛計數每章各自一份）。 */
    val gate: NightGovernor.ChapterGate = governor.openChapter()

    /** 在夜讀 worker 上跑 [block]（登記進 governor）；[abort]＝引擎每次推論前、等翻譯讓出鎖時問要不要放棄（收尾關組用預設的不放棄）。 */
    private suspend fun <T> onWorker(abort: () -> Boolean = { false }, block: () -> T): T {
        val work = governor.newWork(abort)
        return withContext(workers + governor.workElement(work)) {
            val slot = governor.enter()
            work.slot = slot
            try {
                block()
            } finally {
                governor.exit(slot)
            }
        }
    }

    /**
     * @param open 開一組模型（可含暖機）；在 worker 上跑。章首開不起想要的組 → 改開另一組、這章不再換；兩組都開不起來
     *   → 往外拋。
     * @param estimate 一頁在飛的 heap 預估（bytes）；在 producer 上跑，拋例外 → `onError`、跳過該頁。
     * @param page 做一頁；在 worker 上跑。一般錯誤自己記好回 DONE；不要在裡面 suspend（效能探針量的是同一條執行緒）。
     */
    suspend fun run(
        pages: List<P>,
        open: (NetKind) -> N,
        estimate: (P) -> Long,
        page: suspend (P, NightTicket<N>) -> NightPageOutcome,
        onError: (P, Throwable) -> Unit,
        onOomFinal: (P) -> Unit,
        shouldStop: () -> Boolean,
    ) {
        // 待做佇列依原頁序（丟回待做／補跑的頁插回原位）；worker 也會寫，所以用它自己當鎖。
        val queue = PriorityQueue<IndexedValue<P>>(compareBy { it.index })
        pages.forEachIndexed { i, p -> queue += IndexedValue(i, p) }
        val oomPages = ArrayList<IndexedValue<P>>() // 在 queue 的鎖下：等主迴圈後補跑的頁
        val oomOnce = HashSet<Int>() // 在 queue 的鎖下：OOM 過一次的頁（再 OOM 就是最終失敗）
        fun hasWork() = synchronized(queue) { queue.isNotEmpty() || oomPages.isNotEmpty() }
        var swapEnabled = true
        var kind = governor.wantedNets(NetKind.LOCKED)
        var nets: N? = null

        // 用 k 組、swappable（這章還能換組）時，讓路已久該換成 FREE 了（LOCKED 組的推論／暖機該放棄）
        fun wantsFree(k: NetKind, swappable: Boolean) =
            swappable && k == NetKind.LOCKED && governor.wantedNets(k) == NetKind.FREE

        // 在 worker 上把還記在 nets 的組拿掉並關（換組的舊組；恰好一次）、再開 k 組，開好就記進 nets（見類別說明
        // 「模型組的所有權」）。派到 worker 前就被取消 → 整段不跑、舊組還在 nets、由 finally 關。暖機的中止條件＝
        // 暫停／取消或 wantsFree：放棄的暖機由 open 自己吞掉、組照樣回傳，主迴圈頂端隨即收工或換組。
        suspend fun openOnWorker(k: NetKind, swappable: Boolean) {
            onWorker({ shouldStop() || wantsFree(k, swappable) }) {
                nets?.let { n ->
                    nets = null
                    runCatching { n.close() }
                }
                nets = open(k)
            }
        }

        try {
            // 章首：想要的組開不起來 → 改開另一組、這章不再換（兩組都開不起來才往外拋）
            try {
                openOnWorker(kind, swappable = true)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                val other = kind.other()
                governor.log(
                    "nets open ${kind.tag} FAILED ${t.javaClass.simpleName}: ${t.message}; fall back to ${other.tag}",
                )
                swapEnabled = false
                try {
                    openOnWorker(other, swappable = false)
                } catch (t2: Throwable) {
                    if (t2 !is CancellationException) t2.addSuppressed(t)
                    throw t2
                }
                kind = other
            }
            gate.nets = kind
            governor.log("nets open ${kind.tag} ${governor.capacityInfo()} pages=${pages.size}")
            coroutineScope {
                while (!shouldStop()) {
                    // 換模型組：只在頁邊界、還有頁要做、而且等在飛頁數歸零才換
                    if (swapEnabled && governor.wantedNets(kind) != kind && hasWork()) {
                        gate.awaitIdle(shouldStop)
                        if (shouldStop()) break
                        // 排空要等在飛頁跑完（一頁 10–25 s）：期間模式可能翻回來、頁可能做完了 → 不換（別白開一組模型）
                        val want = governor.wantedNets(kind)
                        if (want == kind || !hasWork()) continue
                        val t0 = governor.now()
                        val old = kind
                        val k = try {
                            // ★ 舊組在 worker 上才從 nets 拿掉並關（見 openOnWorker）：producer 這邊先清掉的話，派到 worker
                            // 前就被取消 → 沒人關它
                            openOnWorker(want, swappable = true)
                            want
                        } catch (c: CancellationException) {
                            throw c
                        } catch (t: Throwable) {
                            governor.log(
                                "nets swap ${old.tag}→${want.tag} FAILED " +
                                    "${t.javaClass.simpleName}: ${t.message}; reopen ${old.tag}",
                            )
                            // nets 若還記著舊組（失敗發生在關舊組之前，例如派工本身），openOnWorker(old) 會先關掉再重開；
                            // 原組也開不了就往外拋（還記在 nets 的由 finally 關）
                            try {
                                openOnWorker(old, swappable = false)
                            } catch (t2: Throwable) {
                                if (t2 !is CancellationException) t2.addSuppressed(t)
                                throw t2
                            }
                            old
                        }
                        kind = k
                        gate.nets = k
                        if (k == want) {
                            governor.log("nets swap ${old.tag}→${k.tag} took=${governor.now() - t0}ms")
                        } else {
                            swapEnabled = false
                        }
                        continue
                    }

                    // ★ 先看在飛、再取佇列（見類別說明「收尾」）：反過來的話，取到空佇列之後、看在飛之前，最後一頁可能剛好
                    // 丟回待做並歸還名額 → 看到在飛＝0 就收工，那頁留在佇列裡沒人做、也沒記錯。
                    val seen = gate.mark()
                    val wasIdle = gate.idle()
                    val next = synchronized(queue) { queue.poll() }
                    if (next == null) {
                        // 佇列空但還有在飛頁：它們可能丟回待做，等它們收尾再看
                        if (!wasIdle) {
                            gate.await(seen)
                            continue
                        }
                        // 主迴圈做完：OOM 過的頁以上限 1（oomCapped）補跑（每頁只補一次，見 oomOnce）
                        val retry = synchronized(queue) {
                            if (oomPages.isEmpty()) {
                                null
                            } else {
                                oomPages.toList().also {
                                    queue += it
                                    oomPages.clear()
                                }
                            }
                        } ?: break
                        governor.log("oomRetry ${retry.map { it.value }}")
                        continue
                    }

                    val est = try {
                        estimate(next.value)
                    } catch (t: Throwable) {
                        onError(next.value, t)
                        continue
                    }
                    val a = gate.admit(est, shouldStop) ?: break
                    if (shouldStop()) {
                        gate.release(a)
                        break
                    }
                    // 等放行期間模式變了、要換組 → 這頁先放回去，回迴圈頂端排空後換（不用要被換掉的組開新頁）
                    if (swapEnabled && governor.wantedNets(kind) != kind) {
                        gate.release(a)
                        synchronized(queue) { queue += next }
                        continue
                    }
                    val pageNets = checkNotNull(nets)
                    val pageKind = kind
                    val canSwap = swapEnabled
                    // 這頁的工作：引擎每次推論前、等翻譯讓出鎖時問——暫停／取消、讓路時不是最早放行的那頁，或（LOCKED 組）
                    // 讓路夠久該換 FREE 了（見類別說明；只放在這裡、不進 ticket.keep()：推論都做完的頁就讓它畫完）
                    val work = governor.newWork { shouldStop() || !gate.keep(a) || wantsFree(pageKind, canSwap) }
                    val ticket = NightTicket(pageNets, pageKind, a, gate, work, shouldStop)
                    // 名額在 invokeOnCompletion 還：子工作還沒開始就被取消（不跑本體）也一定會還
                    launch(workers + governor.workElement(work)) {
                        val slot = governor.enter()
                        work.slot = slot
                        val outcome = try {
                            page(next.value, ticket)
                        } catch (c: CancellationException) {
                            throw c
                        } catch (e: NcnnForwardAbortedException) {
                            NightPageOutcome.DROPPED
                        } catch (t: Throwable) {
                            onError(next.value, t)
                            NightPageOutcome.DONE
                        } finally {
                            governor.exit(slot)
                        }
                        // ★ 丟回待做／記 OOM 都在本體內、歸還名額之前（收尾靠這個順序，見類別說明）
                        when (outcome) {
                            NightPageOutcome.DONE -> Unit
                            NightPageOutcome.DROPPED -> {
                                synchronized(queue) { queue += next }
                                governor.log("requeue ${next.value}")
                            }
                            NightPageOutcome.OOM -> {
                                gate.oomCapped = true
                                val final = synchronized(queue) {
                                    if (oomOnce.add(next.index)) {
                                        oomPages += next
                                        false
                                    } else {
                                        true
                                    }
                                }
                                if (final) onOomFinal(next.value)
                            }
                        }
                    }.invokeOnCompletion { gate.release(a) }
                }
            }
        } finally {
            nets?.let { n -> withContext(NonCancellable) { onWorker { n.close() } } }
            if (!gate.idle()) governor.log("LEAK running=${gate.running}")
        }
    }
}

private fun NetKind.other(): NetKind = if (this == NetKind.LOCKED) NetKind.FREE else NetKind.LOCKED
