package com.mmckb.openwrtstatus.data.local

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 路由器凭据的静态加密（at-rest encryption）。
 *
 * 设备列表此前把路由器密码与 SSH 密码以明文 JSON 写进 DataStore，root 设备或
 * `adb backup` 可直读。这里改为：**只有敏感字段**经 Android Keystore 中的
 * AES-256-GCM 密钥加密后再落盘，其余字段（地址、端口、用户名）保持明文以便排错。
 *
 * 存储格式：`enc:v1:<base64(iv ‖ ciphertext ‖ gcmTag)>`
 * - 前缀用于版本演进与「是否已加密」判定；
 * - 密钥别名与别名版本绑定（[KEY_ALIAS]），未来换算法可平滑新增 `enc:v2:`。
 *
 * 密钥特性：
 * - 生成并保存在 **AndroidKeyStore** 中，私钥材料不可导出，只存在于本机安全硬件（有 TEE 时）；
 * - 无需用户认证即可使用（后台自动刷新要能读密码），因此不设 `setUserAuthenticationRequired`。
 *
 * 兼容与降级：
 * - [decrypt] 遇到无前缀的旧值会原样返回——旧安装的明文密码可被透明读取，随后由
 *   [SettingsStore] 回写为密文，完成一次性迁移；
 * - 加解密任何异常都不抛出：加密失败退化为明文（优先保证不丢配置），
 *   解密失败退化为空串（密钥失效时让用户重填密码，而不是崩溃）。
 *
 * 该对象只做纯字节层操作，不感知 [com.mmckb.openwrtstatus.data.model.RouterConfig]。
 */
object CredentialCipher {

    private const val TAG = "CredentialCipher"
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "openwrt_status_credentials_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    /** 密文前缀：`enc:v1:`。 */
    const val PREFIX = "enc:v1:"

    /** GCM 推荐的 96 位 IV。 */
    private const val IV_BYTES = 12

    /** GCM 认证标签长度（位）。 */
    private const val TAG_BITS = 128

    private val keyLock = Any()

    /** 该字符串是否已是本对象的密文（带前缀）。空串视为非密文。 */
    fun isEncrypted(value: String): Boolean = value.startsWith(PREFIX)

    /**
     * 加密一个敏感字段。
     *
     * 空串与已是密文的值原样返回（幂等，避免重复加密）。
     * 加密失败时记日志并返回原文——宁可退回明文也不能丢配置。
     */
    fun encrypt(plain: String): String {
        if (plain.isEmpty() || isEncrypted(plain)) return plain
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val iv = cipher.iv
            val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            PREFIX + Base64.encodeToString(iv + body, Base64.NO_WRAP)
        } catch (t: Throwable) {
            Log.e(TAG, "凭据加密失败，该字段将以明文保存", t)
            plain
        }
    }

    /**
     * 解密一个敏感字段。
     *
     * - 空串 → 空串；
     * - 无前缀 → 视为旧版明文，原样返回（迁移路径）；
     * - 解密失败 → 空串（密钥失效/数据损坏时退化为「未填写」，不抛异常）。
     */
    fun decrypt(stored: String): String {
        if (stored.isEmpty() || !isEncrypted(stored)) return stored
        return try {
            val raw = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            if (raw.size <= IV_BYTES) {
                Log.w(TAG, "凭据密文长度异常，按空密码处理")
                return ""
            }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(TAG_BITS, raw, 0, IV_BYTES)
            )
            String(cipher.doFinal(raw, IV_BYTES, raw.size - IV_BYTES), Charsets.UTF_8)
        } catch (t: Throwable) {
            Log.w(TAG, "凭据解密失败（密钥可能已失效），按空密码处理", t)
            ""
        }
    }

    /**
     * 取得（必要时创建）Keystore 中的 AES 密钥。
     *
     * 多线程可能并发首次调用，用 [keyLock] 串行化，避免重复生成导致「密钥被覆盖、
     * 先前写入的密文无法解开」。
     */
    private fun secretKey(): SecretKey = synchronized(keyLock) {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        generator.generateKey()
    }
}
