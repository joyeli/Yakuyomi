package mihon.core.migration.migrations

import mihon.core.migration.Migration
import mihon.core.migration.MigrationContext
import tachiyomi.domain.translation.service.TranslationPreferences

/**
 * 去字方法從 3 門別（含 auto_tile「逐格」）收成 2 門別（boxfill 快速去字 / auto_whole AI 去字）後，
 * 把已退役的 stored 值（auto_tile / auto_aot / lama_* 等）正規化成 auto_whole（AI 去字）。
 *
 * 不做會怎樣：設定頁「去字方法」ListPreference 的 entries 只剩 {boxfill, auto_whole}，stored 值不在裡面時
 * subtitle 走 "%s".format(null) → 顯示字面 "null"、選單無選中項（曾在 v0.1.0–v0.2.1 選過「Auto-逐格」的使用者會踩到）。
 * 功能本身不受影響（引擎 mapInpaintMethod 對未知值一律 else→aot），純顯示修正。ALWAYS + 冪等（已是合法值就不動）。
 */
class InpaintMethodMigration : Migration {
    override val version: Float = Migration.ALWAYS

    override suspend fun invoke(migrationContext: MigrationContext): Boolean {
        val prefs = migrationContext.get<TranslationPreferences>() ?: return false
        val cur = prefs.inpaintMethod.get()
        if (cur != "boxfill" && cur != "auto_whole") prefs.inpaintMethod.set("auto_whole")
        return true
    }
}
