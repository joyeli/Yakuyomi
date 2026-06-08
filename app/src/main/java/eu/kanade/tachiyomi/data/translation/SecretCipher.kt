package eu.kanade.tachiyomi.data.translation

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import logcat.LogPriority
import tachiyomi.core.common.preference.StringCrypto
import tachiyomi.core.common.util.system.logcat
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android Keystore 對稱加密小工具：把敏感字串（目前只有 BYOK API key）在落地前加密。
 *
 * 設計重點（呼叫端絕不會因此 crash）：
 *  - 金鑰是 **AES-256-GCM**，由 AndroidKeyStore 產生並保管（金鑰本體不出 keystore、不進 app 記憶體）；
 *    GCM 自帶完整性標籤＝竄改/截斷的密文解不開（回 null，而非吐出垃圾）。
 *  - [encrypt]/[decrypt] **永不向呼叫端拋例外**：keystore 不可用（極少數裝置/廠商 ROM）、
 *    金鑰失效（生物辨識重設、還原備份到別台機）等任何失敗 → 記 logcat 後回退（加密回原文視為不可用、
 *    解密回 null）。最壞情況只是 key 讀不出來 → 翻譯顯示「引擎未就緒」（§11-safe），不會閃退。
 *  - 密文格式：`Base64( IV(12 bytes) ‖ ciphertext+GCMtag )`，單一字串方便塞回原本的字串型 SharedPreferences。
 *
 * minSdk = 26，AndroidKeyStore 的 AES/GCM（API 23+）必定可用，故不需版本分支。
 *
 * 實作 [StringCrypto]（core/common 的抽象）→ 注入 [tachiyomi.domain.translation.service.TranslationPreferences]
 * 讓 apiKey 透明加密；core/common 本身不碰 Android Keystore。
 */
object SecretCipher : StringCrypto {

    private const val KEYSTORE = "AndroidKeyStore"

    /** 金鑰別名（帶 _v1：日後若要換演算法/輪替金鑰可升版，不污染舊別名）。 */
    private const val KEY_ALIAS = "yakuyomi_secret_v1"

    /** AES-GCM/NoPadding：GCM 是 AEAD，自帶驗證標籤、不需另外 padding。 */
    private const val TRANSFORMATION =
        "${KeyProperties.KEY_ALGORITHM_AES}/${KeyProperties.BLOCK_MODE_GCM}/${KeyProperties.ENCRYPTION_PADDING_NONE}"

    /** GCM 建議 12 byte IV；驗證標籤 128 bit。 */
    private const val IV_LENGTH = 12
    private const val GCM_TAG_BITS = 128

    /**
     * 把 [plain] 加密成 `Base64(IV ‖ ciphertext)`。任何失敗（keystore 不可用等）→ 記 log 後**回傳原文**
     * （視為「此裝置無法加密」的退化：仍能用、只是這顆 key 落地時沒被加密；不讓使用者因 keystore 故障而存不了 key）。
     * 空字串直接回空（沒東西要加密）。
     */
    override fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            }
            val iv = cipher.iv // GCM 模式由 provider 隨機產生、每次不同（不可自行固定 IV）
            val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            val combined = ByteArray(iv.size + ciphertext.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)
            Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "API key 加密失敗，回退明文落地" }
            plain
        }
    }

    /**
     * 解密 [encrypted]（[encrypt] 產出的 `Base64(IV ‖ ciphertext)`）→ 明文。
     * 任何失敗（解析錯誤 / 金鑰失效 / GCM 驗證失敗＝密文被竄改或截斷 / keystore 不可用）→ 記 log 回 **null**。
     * 呼叫端應把 null 當「沒有可用的 key」。空字串回 null（沒設過 key）。
     */
    override fun decrypt(encrypted: String): String? {
        if (encrypted.isEmpty()) return null
        return try {
            val combined = Base64.decode(encrypted, Base64.NO_WRAP)
            if (combined.size <= IV_LENGTH) return null // 連 IV 都不夠＝不是我們的密文（或損毀）
            val iv = combined.copyOfRange(0, IV_LENGTH)
            val ciphertext = combined.copyOfRange(IV_LENGTH, combined.size)
            val key = loadKey() ?: return null // 金鑰不在（被清/還原到別台）→ 解不了
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            }
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Throwable) {
            // 包含 AEADBadTagException（竄改/金鑰不符）等：一律當「無可用 key」，不外拋。
            logcat(LogPriority.WARN, e) { "API key 解密失敗（回退空 key）" }
            null
        }
    }

    /** 取既有金鑰；沒有就在 AndroidKeyStore 產一把新的（AES-256-GCM、無使用者驗證需求）。 */
    private fun getOrCreateKey(): SecretKey {
        loadKey()?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // 不設 setUserAuthenticationRequired：背景翻譯 worker 也要能無感讀 key（不彈鎖屏）。
                .build(),
        )
        return generator.generateKey()
    }

    /** 從 keystore 撈金鑰；不存在或 keystore 不可用回 null（讓呼叫端走退化路徑、不 crash）。 */
    private fun loadKey(): SecretKey? = try {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        keyStore.getKey(KEY_ALIAS, null) as? SecretKey
    } catch (e: Throwable) {
        logcat(LogPriority.WARN, e) { "讀取 keystore 金鑰失敗" }
        null
    }
}
