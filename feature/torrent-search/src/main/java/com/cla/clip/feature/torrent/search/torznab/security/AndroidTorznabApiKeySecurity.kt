package com.cla.clip.feature.torrent.search.torznab.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.cla.clip.feature.torrent.search.torznab.TorznabConfigId
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Android Keystore AES-GCM 实现；主密钥不可导出且只在当前应用安装内可用。 */
internal class AndroidKeystoreTorznabApiKeyCipher : TorznabApiKeyCipher {
    /** keyCreationLock 避免首次并发保存为同一 alias 重复生成密钥。 */
    private val keyCreationLock = Any()

    /** 使用 Keystore 随机 IV 和配置 AAD 加密，不允许调用方提供 IV。 */
    override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): EncryptedTorznabSecret {
        /** cipher 是当前调用独占的 AES-GCM 实例。 */
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(associatedData)
        /** ciphertext 包含 GCM 认证标签。 */
        val ciphertext = cipher.doFinal(plaintext)
        /** iv 由 Android 密码学实现随机生成。 */
        val iv = cipher.iv ?: throw IllegalStateException()
        return EncryptedTorznabSecret(iv = iv, ciphertext = ciphertext)
    }

    /** 使用保存的 IV 与同一配置 AAD 解密，认证失败由上层统一清理密文。 */
    override fun decrypt(secret: EncryptedTorznabSecret, associatedData: ByteArray): ByteArray {
        /** keyStore 只读取现有设备绑定密钥；缺失表示系统恢复或 Key 已失效。 */
        val keyStore = loadKeyStore()
        /** key 必须是当前 alias 的 SecretKey。 */
        val key = keyStore.getKey(KEY_ALIAS, null) as? SecretKey ?: throw IllegalStateException()
        /** cipher 是当前调用独占的 AES-GCM 实例。 */
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, secret.iv))
        cipher.updateAAD(associatedData)
        return cipher.doFinal(secret.ciphertext)
    }

    /** 读取或原子创建 AES-256 GCM 主密钥。 */
    private fun getOrCreateKey(): SecretKey = synchronized(keyCreationLock) {
        /** keyStore 每次加载当前 AndroidKeyStore 状态，避免缓存失效引用。 */
        val keyStore = loadKeyStore()
        /** existingKey 是安装内此前生成的不可导出密钥。 */
        val existingKey = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existingKey != null) return@synchronized existingKey
        /** keyGenerator 直接把新密钥写入 AndroidKeyStore。 */
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        /** keySpec 只授权 AES-GCM 加解密，不要求用户认证以支持后台 Torznab 请求。 */
        val keySpec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
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

    /** 加载 AndroidKeyStore；失败由安全仓库映射为低敏失败。 */
    private fun loadKeyStore(): KeyStore {
        /** keyStore 不使用密码文件，load 参数固定为 null。 */
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE)
        keyStore.load(null)
        return keyStore
    }

    /** Android Keystore 密码学常量。 */
    companion object {
        /** 系统 KeyStore provider 名称。 */
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        /** feature 级共享主密钥 alias，不包含用户或配置数据。 */
        private const val KEY_ALIAS = "clipmaster_torrent_search_torznab_api_key_v1"
        /** 认证加密转换。 */
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        /** AES 主密钥长度。 */
        private const val KEY_SIZE_BITS = 256
        /** GCM 认证标签长度。 */
        private const val GCM_TAG_BITS = 128
    }
}

/** 使用独立 SharedPreferences 保存版本化 IV 与密文，不保存 API Key 明文。 */
internal class SharedPreferencesTorznabEncryptedSecretBlobStore(
    /** 应用级 SharedPreferences，文件已从系统备份和设备迁移排除。 */
    private val preferences: SharedPreferences,
) : TorznabEncryptedSecretBlobStore {
    /** 读取并解码版本 1 密文；损坏时立即删除当前项。 */
    override fun read(configId: TorznabConfigId): EncryptedTorznabSecret? {
        /** storageKey 只由稳定配置 ID 构成，不含名称、URL 或 Key。 */
        val storageKey = storageKey(configId)
        return try {
            /** encoded 是版本化 Base64 密文字符串，错误 SharedPreferences 类型也在此边界处理。 */
            val encoded = preferences.getString(storageKey, null) ?: return null
            /** parts 必须精确包含版本、IV 和密文三段。 */
            val parts = encoded.split(':')
            if (parts.size != 3 || parts[0] != FORMAT_VERSION) throw IllegalArgumentException()
            /** iv 由无换行 Base64 恢复。 */
            val iv = Base64.decode(parts[1], Base64.NO_WRAP)
            /** ciphertext 由无换行 Base64 恢复且必须非空。 */
            val ciphertext = Base64.decode(parts[2], Base64.NO_WRAP)
            if (iv.isEmpty() || ciphertext.isEmpty()) throw IllegalArgumentException()
            EncryptedTorznabSecret(iv = iv, ciphertext = ciphertext)
        /** failure 表示介质损坏，只清理当前项且不读取异常 message。 */
        } catch (failure: RuntimeException) {
            preferences.edit().remove(storageKey).apply()
            null
        }
    }

    /** 同步提交单项版本化密文，使保存成功返回时介质已经可读。 */
    override fun write(configId: TorznabConfigId, secret: EncryptedTorznabSecret) {
        /** encoded 只包含固定版本、随机 IV 和认证密文。 */
        val encoded = listOf(
            FORMAT_VERSION,
            Base64.encodeToString(secret.iv, Base64.NO_WRAP),
            Base64.encodeToString(secret.ciphertext, Base64.NO_WRAP),
        ).joinToString(":")
        if (!preferences.edit().putString(storageKey(configId), encoded).commit()) throw IllegalStateException()
    }

    /** 删除指定密文；失败由 SharedPreferences 返回值转成低敏异常。 */
    override fun remove(configId: TorznabConfigId) {
        if (!preferences.edit().remove(storageKey(configId)).commit()) throw IllegalStateException()
    }

    /** 构造不含用户数据的 SharedPreferences key。 */
    private fun storageKey(
        /** 稳定 Torznab 配置身份。 */
        configId: TorznabConfigId,
    ): String = "$ENTRY_PREFIX${configId.value}"

    /** 密文介质协议常量。 */
    companion object {
        /** 独立 SharedPreferences 逻辑文件名，XML 已被系统备份规则排除。 */
        const val PREFERENCES_NAME = "torrent_search_torznab_secrets"
        /** 当前密文字符串版本。 */
        private const val FORMAT_VERSION = "1"
        /** 配置密文 key 前缀。 */
        private const val ENTRY_PREFIX = "api_key_"

        /** 使用应用 Context 构造私有密文介质。 */
        fun create(
            /** 应用 Context，避免 Activity 生命周期泄漏。 */
            context: Context,
        ): SharedPreferencesTorznabEncryptedSecretBlobStore {
            /** preferences 使用 MODE_PRIVATE 且只保存认证密文。 */
            val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            return SharedPreferencesTorznabEncryptedSecretBlobStore(preferences)
        }
    }
}
