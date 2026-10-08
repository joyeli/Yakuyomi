package eu.kanade.tachiyomi.ui.manga

import eu.kanade.presentation.manga.components.ChapterNightStatus
import eu.kanade.tachiyomi.data.translation.model.TranslationItem
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

/**
 * 章節列夜讀指示器的狀態優先序（[nightStatusOf]）：總開關關或未下載一律不畫；佇列裡的夜讀項（產生中、排隊中、失敗）
 * 蓋過「可更新」——已在處理的章不再提示更新；不在佇列時有舊版頁＝可更新、否則已有；沒有夜讀版＝一般月亮鈕。
 */
class ChapterNightStatusTest {

    private fun item(status: TranslationItem.Status) = TranslationItem(
        manga = Manga.create(),
        chapter = Chapter.create(),
        status = status,
        kind = TranslationItem.Kind.NIGHT,
    )

    @Test
    fun `總開關關或未下載：不畫`() {
        for (outdated in listOf(false, true)) {
            nightStatusOf(false, true, null, true, outdated) shouldBe ChapterNightStatus.HIDDEN
            nightStatusOf(true, false, null, true, outdated) shouldBe ChapterNightStatus.HIDDEN
            nightStatusOf(false, true, item(TranslationItem.Status.QUEUE), true, outdated) shouldBe
                ChapterNightStatus.HIDDEN
        }
    }

    @Test
    fun `做不了夜讀的章（壓縮檔章）：不畫，佇列狀態也蓋不過`() {
        nightStatusOf(true, true, null, false, false, unsupported = true) shouldBe ChapterNightStatus.HIDDEN
        for (s in TranslationItem.Status.entries) {
            nightStatusOf(true, true, item(s), true, true, unsupported = true) shouldBe ChapterNightStatus.HIDDEN
        }
    }

    @Test
    fun `佇列狀態蓋過可更新`() {
        for (has in listOf(false, true)) {
            for (outdated in listOf(false, true)) {
                nightStatusOf(true, true, item(TranslationItem.Status.TRANSLATING), has, outdated) shouldBe
                    ChapterNightStatus.RENDERING
                nightStatusOf(true, true, item(TranslationItem.Status.QUEUE), has, outdated) shouldBe
                    ChapterNightStatus.QUEUED
                nightStatusOf(true, true, item(TranslationItem.Status.ERROR), has, outdated) shouldBe
                    ChapterNightStatus.ERROR
            }
        }
    }

    @Test
    fun `不在佇列：可更新蓋過已有，沒有夜讀版就是一般鈕`() {
        nightStatusOf(true, true, null, true, true) shouldBe ChapterNightStatus.UPDATABLE
        nightStatusOf(true, true, null, true, false) shouldBe ChapterNightStatus.DONE
        nightStatusOf(true, true, null, false, false) shouldBe ChapterNightStatus.NONE
        // 沒有夜讀版卻說舊版（不會發生：summarize 只對有夜讀版的頁判舊版）也不畫成可更新
        nightStatusOf(true, true, null, false, true) shouldBe ChapterNightStatus.NONE
    }
}
