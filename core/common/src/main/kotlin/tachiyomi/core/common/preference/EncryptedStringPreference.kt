package tachiyomi.core.common.preference

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * 字串加解密抽象（避免 core/common 直接依賴 Android Keystore；實作放 app 層的 SecretCipher）。
 * 兩個方法都**不得拋例外**：加密失敗回原文（退化成明文落地）、解密失敗回 null（當作沒可用值）。
 */
interface StringCrypto {
    /** 明文 → 密文字串（落地用）。失敗回原文。 */
    fun encrypt(plain: String): String

    /** 密文字串 → 明文。失敗（金鑰失效/竄改/格式錯）回 null。 */
    fun decrypt(encrypted: String): String?
}

/**
 * 透明加密的字串偏好：對外是個普通的 [Preference]<String>（呼叫端拿到/設定的都是**明文**），
 * 但實際落地在 [backing]（一個存密文的字串偏好）裡的是 [crypto] 加密後的字串。
 *
 * 用途：把 BYOK API key 從明文 SharedPreferences 改成 keystore 加密，
 * 而 UI（EditTextPreference）與所有讀 key 的程式碼**完全不必改**——它們看到的仍是明文。
 *
 * 行為對齊 [AndroidPreference]：
 *  - [get]：解密 backing 的密文；解不開（沒設過/金鑰失效/竄改）→ 回 [defaultValue]。
 *  - [set]：把明文加密後寫進 backing。
 *  - [isSet]/[delete]/[key]/[defaultValue]：直接委派 backing（密文存不存在＝key 設沒設）。
 *  - [changes]/[stateIn]：backing 的 flow 逐一 map 過 decrypt（解不開的也回 defaultValue），語義同明文偏好的變更流。
 *
 * 一次性遷移：若提供 [legacyPlaintext]（舊的明文 key 偏好）且其值非空、而 backing 尚未設過，
 * 則在**首次 [get] 或 [set]** 時把舊明文加密進 backing、並清掉舊明文（不讓明文殘留磁碟）。
 */
class EncryptedStringPreference(
    private val backing: Preference<String>,
    private val crypto: StringCrypto,
    private val defaultValue: String,
    private val legacyPlaintext: Preference<String>? = null,
) : Preference<String> {

    /** 遷移只跑一次（程序內）：避免每次 get/set 都檢查舊 key。 */
    @Volatile
    private var migrated = false

    override fun key(): String = backing.key()

    override fun defaultValue(): String = defaultValue

    override fun get(): String {
        migrateIfNeeded()
        if (!backing.isSet()) return defaultValue
        return crypto.decrypt(backing.get()) ?: defaultValue
    }

    override fun set(value: String) {
        // set 前先把舊明文遷掉（避免 migrate 再用舊明文覆蓋掉這次的新值）。
        migrateIfNeeded()
        backing.set(crypto.encrypt(value))
    }

    override fun isSet(): Boolean {
        migrateIfNeeded()
        return backing.isSet()
    }

    override fun delete() {
        backing.delete()
    }

    override fun changes(): Flow<String> =
        backing.changes().map { enc -> if (enc.isEmpty()) defaultValue else (crypto.decrypt(enc) ?: defaultValue) }

    override fun stateIn(scope: CoroutineScope): StateFlow<String> =
        changes().stateIn(scope, SharingStarted.Eagerly, get())

    /**
     * 一次性：把舊明文 key 加密搬進 backing、清掉舊明文。只在 backing 還沒密文、且舊明文非空時做一次。
     * 失敗（理論上 crypto.encrypt 不拋）也吞掉、下次再試——絕不讓遷移擋住讀寫。
     */
    private fun migrateIfNeeded() {
        if (migrated) return
        migrated = true
        val legacy = legacyPlaintext ?: return
        try {
            if (!backing.isSet() && legacy.isSet()) {
                val old = legacy.get()
                if (old.isNotBlank()) {
                    backing.set(crypto.encrypt(old))
                }
                legacy.delete() // 不論是否非空，都清掉舊明文 key，避免明文殘留
            }
        } catch (_: Throwable) {
            // 遷移是 best-effort：失敗就維持舊明文（下次再試），不影響當前 get/set 的正確性。
            migrated = false
        }
    }
}
