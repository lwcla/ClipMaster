package com.cla.clip.base.general.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 设备绑定的 AES-GCM 结果；IV 与带认证标签的密文均不得写入日志。 */
class KeystoreEncryptedSecret(
    /** Android Keystore 为每次加密生成的随机 IV。 */
    val iv: ByteArray,
    /** 包含 GCM 认证标签的密文字节。 */
    val ciphertext: ByteArray,
)

/** 使用指定 alias 的 Android Keystore AES-256-GCM 密钥；领域层负责选择 alias 和 AAD。 */
class AndroidKeystoreSecretCipher(
    /** 业务独占的固定 alias，不能包含用户输入或凭据。 */
    private val keyAlias: String,
) {
    init {
        require(keyAlias.isNotBlank())
    }

    /** 使用系统随机 IV 和调用方提供的 AAD 加密；Keystore 失败直接抛出，由领域层分类处理。 */
    fun encrypt(
        /** 仅在调用期间使用的明文字节，调用方负责限制大小与生命周期。 */
        plaintext: ByteArray,
        /** 业务身份的认证附加数据，解密时必须传入相同字节。 */
        associatedData: ByteArray,
    ): KeystoreEncryptedSecret {
        /** cipher 为当前操作独占，避免跨线程共享可变密码学状态。 */
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(associatedData)
        /** ciphertext 包含 GCM 标签，介质无需另存校验码。 */
        val ciphertext = cipher.doFinal(plaintext)
        /** iv 必须由系统随机生成，不接受调用方提供的值。 */
        val iv = cipher.iv ?: throw IllegalStateException()
        return KeystoreEncryptedSecret(iv = iv, ciphertext = ciphertext)
    }

    /** 使用既有密钥、保存的 IV 和相同 AAD 解密；缺钥或认证失败直接抛出且绝不生成新密钥。 */
    fun decrypt(
        /** 来自领域介质的 IV 和认证密文。 */
        secret: KeystoreEncryptedSecret,
        /** 必须与加密时完全一致的业务身份字节。 */
        associatedData: ByteArray,
    ): ByteArray {
        /** keyStore 只读取设备当前持有的密钥，避免恢复后意外重建导致误判。 */
        val keyStore = loadKeyStore()
        /** key 缺失或类型不符表示该密文已不能由当前安装解开。 */
        val key = keyStore.getKey(keyAlias, null) as? SecretKey ?: throw IllegalStateException()
        /** cipher 为本次解密独占，GCM 会校验密文与 AAD 的认证标签。 */
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, secret.iv))
        cipher.updateAAD(associatedData)
        return cipher.doFinal(secret.ciphertext)
    }

    /** 首次保存时在进程内同步创建密钥；跨实例共享锁避免同一 alias 并发生成。 */
    private fun getOrCreateKey(): SecretKey = synchronized(keyCreationLock) {
        /** keyStore 每次加载当前系统状态，不缓存可能失效的密钥引用。 */
        val keyStore = loadKeyStore()
        /** existingKey 是此 alias 已有的不可导出设备密钥。 */
        val existingKey = keyStore.getKey(keyAlias, null) as? SecretKey
        if (existingKey != null) return@synchronized existingKey

        /** keyGenerator 将生成结果直接写入 Android Keystore。 */
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        /** keySpec 仅授权 AES-GCM 加解密，不要求交互认证以支持后台任务。 */
        val keySpec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_SIZE_BITS)
            .setRandomizedEncryptionRequired(true)
            .build()
        keyGenerator.init(keySpec)
        keyGenerator.generateKey()
    }

    /** 重新打开系统 KeyStore；读取失败保留原异常供领域层判断是否可重试。 */
    private fun loadKeyStore(): KeyStore {
        /** keyStore 使用系统 provider，不依赖应用内密码文件。 */
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE)
        keyStore.load(null)
        return keyStore
    }

    /** Android Keystore 与 AES-GCM 的固定密码学参数。 */
    private companion object {
        /** Android 系统的硬件或系统托管密钥 provider。 */
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        /** 与既有 Torznab 密文兼容的认证加密转换。 */
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        /** 使用 AES-256 密钥。 */
        const val KEY_SIZE_BITS = 256
        /** 使用 128 位 GCM 认证标签。 */
        const val GCM_TAG_BITS = 128
        /** 所有共享 cipher 实例在进程内串行执行首次密钥创建。 */
        val keyCreationLock = Any()
    }
}
