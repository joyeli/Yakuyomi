package eu.kanade.tachiyomi.crash

/**
 * 把系統給的原生 crash tombstone 整理成幾行可讀文字，放進崩潰畫面。
 *
 * tombstone 是 **protobuf 二進位**（AOSP `system/core/debuggerd/proto/tombstone.proto`），不是文字。每一格堆疊的位址
 * 是變長整數，當文字讀會被換成替代字元、還原不回來——所以**原始位元組另存成檔**（見 [NativeCrashReporter]），這裡只負責
 * 從位元組裡抽出人看得懂的部分：訊號、abort 訊息、**crash 那條執行緒**每一格的 `pc 位址 檔名 (函式+位移) (BuildId)`。
 *
 * 為什麼要自己解：原生庫 strip 之後，手機上只剩匯出的符號，NCNN 內部的函式名不會再出現在 tombstone 裡；要定位只能拿
 * 「相對位址（rel_pc）＋Build ID」到電腦上用未 strip 的庫（或 native-debug-symbols.zip）符號化。輸出的格式照系統
 * tombstone 文字版（`#00 pc 00000000002c638c  /path/lib.so (BuildId: …)`），ndk-stack／llvm-symbolizer 可以直接吃。
 *
 * 只解用得到的欄位（欄位編號照 tombstone.proto）；解不出任何一格（格式變了、檔案壞了）就退回「列出裡面的可讀字串」，
 * 至少看得到庫名與 Build ID。以另存的原始檔為準，這裡的文字只是方便先看一眼。純 Kotlin，JVM 測試見 TombstoneTextTest。
 */
internal object TombstoneText {

    /** 一格堆疊（BacktraceFrame）。 */
    data class Frame(
        val relPc: Long,
        val functionName: String,
        val functionOffset: Long,
        val fileName: String,
        val buildId: String,
    )

    /** 一條執行緒（Thread）。 */
    data class ThreadTrace(val id: Int, val name: String, val frames: List<Frame>)

    /** 解出來的重點。[crashThread]＝tid 對得上的那條；對不上就是第一條有堆疊的。 */
    data class Parsed(
        val tid: Int,
        val signal: String,
        val abortMessage: String,
        val crashThread: ThreadTrace?,
        val threadCount: Int,
    )

    /** 整理成文字；[raw] 解不出堆疊時退回可讀字串清單。不丟例外。 */
    fun summarize(raw: ByteArray, maxFrames: Int = 64): String {
        val parsed = runCatching { parse(raw) }.getOrNull()
        val thread = parsed?.crashThread
        if (parsed == null || thread == null || thread.frames.isEmpty()) {
            return "（解不出堆疊，以下是 tombstone 裡的可讀字串；請以另存的原始檔為準）\n" +
                printableStrings(raw).joinToString("\n")
        }
        return buildString {
            if (parsed.signal.isNotEmpty()) appendLine(parsed.signal)
            if (parsed.abortMessage.isNotEmpty()) appendLine("Abort message: '${parsed.abortMessage}'")
            appendLine("crash 執行緒 tid=${thread.id} name=${thread.name}（共 ${parsed.threadCount} 條執行緒）")
            appendLine("backtrace（pc＝庫內相對位址，配 BuildId 用未 strip 的庫符號化）:")
            thread.frames.take(maxFrames).forEachIndexed { i, f -> appendLine(formatFrame(i, f)) }
            if (thread.frames.size > maxFrames) appendLine("… 另有 ${thread.frames.size - maxFrames} 格")
        }.trimEnd()
    }

    /** 一格 → `#00 pc 00000000002c638c  /path/lib.so (func+12) (BuildId: abc)`（系統 tombstone 文字版的格式）。 */
    fun formatFrame(index: Int, f: Frame): String = buildString {
        append("#%02d pc %016x  %s".format(index, f.relPc, f.fileName.ifEmpty { "<unknown>" }))
        if (f.functionName.isNotEmpty()) append(" (${f.functionName}+${f.functionOffset})")
        if (f.buildId.isNotEmpty()) append(" (BuildId: ${f.buildId})")
    }

    /** 解 Tombstone 訊息；格式不對會丟例外（呼叫端 [summarize] 接住）。 */
    fun parse(raw: ByteArray): Parsed {
        var tid = 0
        var signal = ""
        var abort = ""
        val threads = ArrayList<ThreadTrace>()
        val r = Reader(raw, 0, raw.size)
        while (r.hasMore()) {
            val tag = r.varint()
            val field = (tag ushr 3).toInt()
            val wire = (tag and 7).toInt()
            when {
                field == TOMBSTONE_TID && wire == WIRE_VARINT -> tid = r.varint().toInt()
                field == TOMBSTONE_SIGNAL && wire == WIRE_LEN -> signal = parseSignal(r.sub())
                field == TOMBSTONE_ABORT && wire == WIRE_LEN -> abort = r.string()
                field == TOMBSTONE_THREADS && wire == WIRE_LEN -> parseThreadEntry(r.sub())?.let { threads += it }
                else -> r.skip(wire)
            }
        }
        val crash = threads.firstOrNull { it.id == tid && it.frames.isNotEmpty() }
            ?: threads.firstOrNull { it.frames.isNotEmpty() }
        return Parsed(tid, signal, abort, crash, threads.size)
    }

    /** `map<uint32, Thread>` 的一筆：1＝key、2＝Thread。 */
    private fun parseThreadEntry(r: Reader): ThreadTrace? {
        var key = 0
        var thread: ThreadTrace? = null
        while (r.hasMore()) {
            val tag = r.varint()
            val field = (tag ushr 3).toInt()
            val wire = (tag and 7).toInt()
            when {
                field == 1 && wire == WIRE_VARINT -> key = r.varint().toInt()
                field == 2 && wire == WIRE_LEN -> thread = parseThread(r.sub())
                else -> r.skip(wire)
            }
        }
        return thread?.let { if (it.id == 0) it.copy(id = key) else it }
    }

    private fun parseThread(r: Reader): ThreadTrace {
        var id = 0
        var name = ""
        val frames = ArrayList<Frame>()
        while (r.hasMore()) {
            val tag = r.varint()
            val field = (tag ushr 3).toInt()
            val wire = (tag and 7).toInt()
            when {
                field == THREAD_ID && wire == WIRE_VARINT -> id = r.varint().toInt()
                field == THREAD_NAME && wire == WIRE_LEN -> name = r.string()
                field == THREAD_BACKTRACE && wire == WIRE_LEN -> frames += parseFrame(r.sub())
                else -> r.skip(wire)
            }
        }
        return ThreadTrace(id, name, frames)
    }

    private fun parseFrame(r: Reader): Frame {
        var relPc = 0L
        var fn = ""
        var fnOff = 0L
        var file = ""
        var buildId = ""
        while (r.hasMore()) {
            val tag = r.varint()
            val field = (tag ushr 3).toInt()
            val wire = (tag and 7).toInt()
            when {
                field == FRAME_REL_PC && wire == WIRE_VARINT -> relPc = r.varint()
                field == FRAME_FUNCTION && wire == WIRE_LEN -> fn = r.string()
                field == FRAME_FUNCTION_OFFSET && wire == WIRE_VARINT -> fnOff = r.varint()
                field == FRAME_FILE && wire == WIRE_LEN -> file = r.string()
                field == FRAME_BUILD_ID && wire == WIRE_LEN -> buildId = r.string()
                else -> r.skip(wire)
            }
        }
        return Frame(relPc, fn, fnOff, file, buildId)
    }

    /** Signal → `signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x…`。 */
    private fun parseSignal(r: Reader): String {
        var number = 0
        var name = ""
        var code = 0
        var codeName = ""
        var hasFault = false
        var fault = 0L
        while (r.hasMore()) {
            val tag = r.varint()
            val field = (tag ushr 3).toInt()
            val wire = (tag and 7).toInt()
            when {
                field == 1 && wire == WIRE_VARINT -> number = r.varint().toInt()
                field == 2 && wire == WIRE_LEN -> name = r.string()
                field == 3 && wire == WIRE_VARINT -> code = r.varint().toInt()
                field == 4 && wire == WIRE_LEN -> codeName = r.string()
                field == 8 && wire == WIRE_VARINT -> hasFault = r.varint() != 0L
                field == 9 && wire == WIRE_VARINT -> fault = r.varint()
                else -> r.skip(wire)
            }
        }
        return buildString {
            append("signal $number ($name), code $code ($codeName)")
            if (hasFault) append(", fault addr 0x%016x".format(fault))
        }
    }

    /** [raw] 裡長度 ≥ [minLen] 的可列印 ASCII 字串（去重、保留出現順序），最多 [limit] 條。 */
    fun printableStrings(raw: ByteArray, minLen: Int = 6, limit: Int = 200): List<String> {
        val out = LinkedHashSet<String>()
        val sb = StringBuilder()
        fun flush() {
            if (sb.length >= minLen && out.size < limit) out += sb.toString()
            sb.setLength(0)
        }
        for (b in raw) {
            val c = b.toInt() and 0xFF
            if (c in 0x20..0x7E) sb.append(c.toChar()) else flush()
        }
        flush()
        return out.toList()
    }

    /** protobuf 線格式的最小讀取器（只讀、不配置；越界或格式不對丟 [IllegalStateException]）。 */
    private class Reader(private val buf: ByteArray, private var pos: Int, private val end: Int) {
        fun hasMore(): Boolean = pos < end

        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                check(pos < end) { "varint 越界" }
                val b = buf[pos++].toInt()
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
                check(shift < 70) { "varint 過長" }
            }
        }

        /** 長度前綴的子訊息（或字串、bytes）：回傳只涵蓋那一段的讀取器，本身跳到段尾。 */
        fun sub(): Reader {
            val len = varint()
            check(len in 0..(end - pos).toLong()) { "長度越界" }
            val r = Reader(buf, pos, pos + len.toInt())
            pos += len.toInt()
            return r
        }

        fun string(): String {
            val r = sub()
            return String(buf, r.pos, r.end - r.pos, Charsets.UTF_8)
        }

        fun skip(wire: Int) {
            when (wire) {
                WIRE_VARINT -> varint()
                WIRE_FIXED64 -> advance(8)
                WIRE_LEN -> sub()
                WIRE_FIXED32 -> advance(4)
                else -> error("不支援的 wire type $wire")
            }
        }

        private fun advance(n: Int) {
            check(n <= end - pos) { "越界" }
            pos += n
        }
    }

    private const val WIRE_VARINT = 0
    private const val WIRE_FIXED64 = 1
    private const val WIRE_LEN = 2
    private const val WIRE_FIXED32 = 5

    // tombstone.proto 的欄位編號
    private const val TOMBSTONE_TID = 6
    private const val TOMBSTONE_SIGNAL = 10
    private const val TOMBSTONE_ABORT = 14
    private const val TOMBSTONE_THREADS = 16
    private const val THREAD_ID = 1
    private const val THREAD_NAME = 2
    private const val THREAD_BACKTRACE = 4
    private const val FRAME_REL_PC = 1
    private const val FRAME_FUNCTION = 4
    private const val FRAME_FUNCTION_OFFSET = 5
    private const val FRAME_FILE = 6
    private const val FRAME_BUILD_ID = 8
}
