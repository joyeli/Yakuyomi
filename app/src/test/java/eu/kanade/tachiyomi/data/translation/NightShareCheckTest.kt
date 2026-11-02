package eu.kanade.tachiyomi.data.translation

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * debug 一次性比對的名額（S1，[NightShareCheck]）：一個行程只給一頁；搶到的頁沒走到比對就還回去，之後的頁還能比。
 * 單元測試跑的是 debug 建置（BuildConfig.DEBUG＝true）。
 */
class NightShareCheckTest {

    @Test
    fun `只有一頁搶得到；沒比成就還回去，下一頁再搶`() {
        NightShareCheck.unclaim()
        try {
            NightShareCheck.pending() shouldBe true
            NightShareCheck.claim() shouldBe true
            NightShareCheck.claim() shouldBe false // 同時借的另一頁搶不到
            NightShareCheck.pending() shouldBe false
            NightShareCheck.unclaim() // 搶到的那頁被暫停／丟例外，沒走到比對
            NightShareCheck.pending() shouldBe true
            NightShareCheck.claim() shouldBe true
        } finally {
            NightShareCheck.unclaim()
        }
    }
}
