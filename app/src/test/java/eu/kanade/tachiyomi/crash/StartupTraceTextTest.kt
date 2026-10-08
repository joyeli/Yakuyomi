package eu.kanade.tachiyomi.crash

import eu.kanade.tachiyomi.data.translation.model.TranslationItem.Status
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * 啟動診斷（[StartupTrace]）寫進紀錄的文字。數值照 android.jar（API 37）裡的常數：
 * ApplicationStartInfo.START_TYPE_COLD＝1、START_REASON_LAUNCHER＝6、RunningAppProcessInfo.IMPORTANCE_CACHED＝400 等。
 */
class StartupTraceTextTest {

    @Test
    fun `啟動類型：行程啟動時紀錄就開著，第一次見到畫面與有沒有存檔狀態的四種組合`() {
        classifyActivityStart(firstSeen = true, hasSavedState = false, processStartTraced = true) shouldBe
            "fresh-process"
        classifyActivityStart(firstSeen = true, hasSavedState = true, processStartTraced = true) shouldBe
            "restored-after-process-death"
        classifyActivityStart(firstSeen = false, hasSavedState = false, processStartTraced = true) shouldBe
            "new-activity-in-running-process"
        classifyActivityStart(firstSeen = false, hasSavedState = true, processStartTraced = true) shouldBe
            "activity-recreated"
    }

    @Test
    fun `啟動類型：紀錄是中途打開的，第一個畫面不冒充新行程`() {
        classifyActivityStart(firstSeen = true, hasSavedState = false, processStartTraced = false) shouldBe
            "first-since-logging-enabled"
        classifyActivityStart(firstSeen = true, hasSavedState = true, processStartTraced = false) shouldBe
            "first-since-logging-enabled"
        classifyActivityStart(firstSeen = false, hasSavedState = false, processStartTraced = false) shouldBe
            "new-activity-in-running-process"
    }

    @Test
    fun `行程重要度：數字後面帶名字，不認得的叫 other`() {
        importanceName(100) shouldBe "100(foreground)"
        importanceName(125) shouldBe "125(foreground-service)"
        importanceName(300) shouldBe "300(service)"
        importanceName(400) shouldBe "400(cached)"
        importanceName(-1) shouldBe "-1(other)"
    }

    @Test
    fun `系統記的啟動類型與原因`() {
        startTypeName(0) shouldBe "0(unset)"
        startTypeName(1) shouldBe "1(cold)"
        startTypeName(2) shouldBe "2(warm)"
        startTypeName(3) shouldBe "3(hot)"
        startReasonName(5) shouldBe "5(job)"
        startReasonName(6) shouldBe "6(launcher)"
        startReasonName(7) shouldBe "7(launcher-recents)"
        startReasonName(10) shouldBe "10(service)"
        startReasonName(99) shouldBe "99(unknown)"
    }

    @Test
    fun `檔案大小：沒檔、位元組、KB、MB`() {
        formatSize(null) shouldBe "missing"
        formatSize(0) shouldBe "0 B"
        formatSize(1023) shouldBe "1023 B"
        formatSize(1536) shouldBe "1.5 KB"
        formatSize(164_048_896) shouldBe "156.4 MB"
    }

    @Test
    fun `佇列摘要：兩個池分開數，空佇列全是零`() {
        summarizeQueue(emptyList()) shouldBe
            "translate(queued=0 running=0 error=0) night(queued=0 running=0 error=0)"
        summarizeQueue(
            listOf(
                false to Status.QUEUE,
                false to Status.QUEUE,
                false to Status.TRANSLATING,
                true to Status.TRANSLATING,
                true to Status.ERROR,
            ),
        ) shouldBe "translate(queued=2 running=1 error=0) night(queued=0 running=1 error=1)"
    }
}
