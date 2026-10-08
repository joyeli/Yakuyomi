package eu.kanade.tachiyomi.data.translation

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** 「下載完要翻／要產生夜讀版」標記的持久化格式：來回一致、格式不對的略過、過期的丟掉。 */
class TranslationStoreMarksTest {

    @Test
    fun `標記存了再讀回來一樣`() {
        val marks = mapOf(1L to 1_000L, 42L to 2_000L, -3L to 3_000L)
        TranslationStore.decodeMarks(TranslationStore.encodeMarks(marks), notBefore = 0L) shouldBe marks
        TranslationStore.decodeMarks(TranslationStore.encodeMarks(emptyMap()), notBefore = 0L) shouldBe emptyMap()
        TranslationStore.decodeMarks(null, notBefore = 0L) shouldBe emptyMap()
    }

    @Test
    fun `過期的標記丟掉，剛好在期限上的留著`() {
        val raw = TranslationStore.encodeMarks(mapOf(1L to 999L, 2L to 1_000L, 3L to 5_000L))
        TranslationStore.decodeMarks(raw, notBefore = 1_000L) shouldBe mapOf(2L to 1_000L, 3L to 5_000L)
    }

    @Test
    fun `格式不對的略過`() {
        val raw = setOf("12:100", "abc:100", "13", "14:", ":100", "15:xyz", "16:200")
        TranslationStore.decodeMarks(raw, notBefore = 0L) shouldBe mapOf(12L to 100L, 16L to 200L)
    }
}
