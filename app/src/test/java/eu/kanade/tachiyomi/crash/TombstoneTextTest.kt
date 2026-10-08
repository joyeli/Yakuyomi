package eu.kanade.tachiyomi.crash

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

/**
 * tombstone（protobuf 二進位）→ 崩潰畫面文字。測試資料用下面的小編碼器照 tombstone.proto 的欄位編號組出來；
 * 手上沒有 strip 之後的真機 tombstone，真機格式沒有對過。
 */
class TombstoneTextTest {

    // ---- 最小 protobuf 編碼器（只給測試組資料用） ----
    private class Msg {
        val out = ByteArrayOutputStream()

        fun varint(v: Long): Msg {
            var x = v
            while (true) {
                val b = (x and 0x7F).toInt()
                x = x ushr 7
                if (x == 0L) {
                    out.write(b)
                    return this
                }
                out.write(b or 0x80)
            }
        }

        fun uint(field: Int, v: Long): Msg = varint((field.toLong() shl 3) or 0).varint(v)

        fun bytes(field: Int, b: ByteArray): Msg {
            varint((field.toLong() shl 3) or 2).varint(b.size.toLong())
            out.write(b)
            return this
        }

        fun str(field: Int, s: String): Msg = bytes(field, s.toByteArray(Charsets.UTF_8))
        fun msg(field: Int, m: Msg): Msg = bytes(field, m.toBytes())
        fun fixed64(field: Int): Msg {
            varint((field.toLong() shl 3) or 1)
            repeat(8) { out.write(0xAB) }
            return this
        }

        fun toBytes(): ByteArray = out.toByteArray()
    }

    private fun frame(relPc: Long, file: String, buildId: String, fn: String = "", fnOff: Long = 0) = Msg()
        .uint(1, relPc)
        .uint(2, 0x7a_1234_5000L + relPc) // pc（絕對位址，用不到）
        .uint(3, 0x7ff_0000_1000L) // sp
        .apply { if (fn.isNotEmpty()) str(4, fn).uint(5, fnOff) }
        .str(6, file)
        .uint(7, 0)
        .str(8, buildId)

    private fun thread(id: Int, name: String, vararg frames: Msg) = Msg()
        .uint(1, id.toLong())
        .str(2, name)
        .msg(3, Msg().str(1, "x0").uint(2, 0x1234)) // 一個暫存器（要被跳過）
        .apply { frames.forEach { msg(4, it) } }

    private fun entry(key: Int, t: Msg) = Msg().uint(1, key.toLong()).msg(2, t)

    private val lib = "/data/app/~~abc==/li.joye.yakuyomi-xyz==/lib/arm64/libyakuyomi_ncnn.so"
    private val buildId = "ff9b6f1aac3fa4c3fd4d60123e5faa57a54988f4"

    private fun tombstone(tid: Int = 4321) = Msg()
        .uint(1, 2) // arch
        .str(2, "vivo/V2429/V2429:15/AP3A/123:user/release-keys")
        .uint(5, 4300) // pid
        .uint(6, tid.toLong())
        .str(9, "li.joye.yakuyomi")
        .msg(10, Msg().uint(1, 11).str(2, "SIGSEGV").uint(3, 1).str(4, "SEGV_MAPERR").uint(8, 1).uint(9, 0x10))
        .str(14, "")
        .fixed64(900) // 不認得的欄位（要被跳過）
        .msg(
            16,
            entry(
                4300,
                thread(
                    4300,
                    "main",
                    frame(0x9d2a4, "/apex/com.android.runtime/lib64/bionic/libc.so", "aa11", "__epoll_pwait", 8),
                ),
            ),
        )
        .msg(
            16,
            entry(
                4321,
                thread(
                    4321,
                    "yaku-ncnn-pool",
                    frame(0x2c638c, lib, buildId),
                    frame(0x1a0f10, lib, buildId, "Java_li_joye_yakuyomi_engine_NcnnBackend_extractNative", 412),
                    frame(
                        0x351c0,
                        "/apex/com.android.art/lib64/libart.so",
                        "bb22",
                        "art_quick_generic_jni_trampoline",
                        148,
                    ),
                ),
            ),
        )
        .toBytes()

    @Test
    fun `位址 0x2c638c 的線格式就是查核文件記的那五個位元組`() {
        // 08 8c c7 b1 01：欄位 1（rel_pc）、變長整數 0x2c638c。當文字讀會壞的就是這一段。
        Msg().uint(1, 0x2c638c).toBytes().toList() shouldBe
            listOf(0x08, 0x8c, 0xc7, 0xb1, 0x01).map { it.toByte() }
    }

    @Test
    fun `解出 crash 執行緒的每一格：相對位址、檔名、BuildId、有名字的才帶函式`() {
        val p = TombstoneText.parse(tombstone())
        p.tid shouldBe 4321
        p.threadCount shouldBe 2
        p.signal shouldBe "signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0000000000000010"
        val t = p.crashThread!!
        t.id shouldBe 4321
        t.name shouldBe "yaku-ncnn-pool"
        t.frames.map { it.relPc } shouldBe listOf(0x2c638cL, 0x1a0f10L, 0x351c0L)
        t.frames[0].functionName shouldBe ""
        t.frames[0].buildId shouldBe buildId
        t.frames[1].functionName shouldBe "Java_li_joye_yakuyomi_engine_NcnnBackend_extractNative"
        t.frames[1].functionOffset shouldBe 412L
    }

    @Test
    fun `文字版照系統 tombstone 的格式，strip 掉名字的那格只有位址與 BuildId`() {
        val text = TombstoneText.summarize(tombstone())
        text shouldContain "signal 11 (SIGSEGV), code 1 (SEGV_MAPERR)"
        text shouldContain "tid=4321 name=yaku-ncnn-pool"
        text shouldContain "#00 pc 00000000002c638c  $lib (BuildId: $buildId)"
        text shouldContain
            "#01 pc 00000000001a0f10  $lib (Java_li_joye_yakuyomi_engine_NcnnBackend_extractNative+412) (BuildId: $buildId)"
        text shouldContain "#02 pc 00000000000351c0  /apex/com.android.art/lib64/libart.so"
        // 只印 crash 那條執行緒
        text shouldNotContain "__epoll_pwait"
        // 位址沒有被換成替代字元
        text shouldNotContain "�"
    }

    @Test
    fun `tid 對不上任何執行緒時用第一條有堆疊的`() {
        val p = TombstoneText.parse(tombstone(tid = 9999))
        p.crashThread!!.id shouldBe 4300
    }

    @Test
    fun `格數上限`() {
        val many = Msg().uint(6, 1).msg(
            16,
            entry(1, thread(1, "t", *Array(10) { frame(0x1000L + it, lib, buildId) })),
        ).toBytes()
        val text = TombstoneText.summarize(many, maxFrames = 3)
        text shouldContain "#02 pc"
        text shouldNotContain "#03 pc"
        text shouldContain "另有 7 格"
    }

    @Test
    fun `解不出堆疊就退回可讀字串，不丟例外`() {
        // 截掉尾巴：最後一條執行緒的長度前綴越界
        val raw = tombstone()
        val cut = raw.copyOf(raw.size - 5)
        val text = TombstoneText.summarize(cut)
        text shouldContain "解不出堆疊"
        text shouldContain "vivo/V2429"

        // 不是 protobuf
        TombstoneText.summarize("----- pid 1 at 2026 -----\nCmd line: li.joye.yakuyomi\n".toByteArray()) shouldContain
            "Cmd line: li.joye.yakuyomi"
        // 空的
        TombstoneText.summarize(ByteArray(0)) shouldContain "解不出堆疊"
    }

    @Test
    fun `可讀字串：去重、過短的不要`() {
        val raw = byteArrayOf(1, 2) + "libyakuyomi_ncnn.so".toByteArray() + byteArrayOf(0) +
            "abc".toByteArray() + byteArrayOf(0x7F) + "libyakuyomi_ncnn.so".toByteArray()
        TombstoneText.printableStrings(raw) shouldBe listOf("libyakuyomi_ncnn.so")
    }
}
