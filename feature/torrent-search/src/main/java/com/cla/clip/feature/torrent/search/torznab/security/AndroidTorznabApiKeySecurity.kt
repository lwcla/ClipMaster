package com.cla.clip.feature.torrent.search.torznab.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.cla.clip.base.general.security.AndroidKeystoreSecretCipher
import com.cla.clip.base.general.security.KeystoreEncryptedSecret
import com.cla.clip.feature.torrent.search.torznab.TorznabConfigId

/** 保留 Torznab 密文契约和既有 alias，仅将设备密码学操作交给共享原语。 */
internal class AndroidKeystoreTorznabApiKeyCipher : TorznabApiKeyCipher {
    /** cipher 只使用 Torznab 既有 alias，与未来 WebDAV 凭据密钥隔离。 */
    private val cipher = AndroidKeystoreSecretCipher(KEY_ALIAS)

    /** 继续由上层传入配置 ID 的 AAD，并映射为既有 Torznab 密文结构。 */
    override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): EncryptedTorznabSecret {
        /** secret 的 IV 与密文字节完全保留共享原语输出，兼容已有介质。 */
        val secret = cipher.encrypt(plaintext, associatedData)
        return EncryptedTorznabSecret(iv = secret.iv, ciphertext = secret.ciphertext)
    }

    /** 使用原 IV、密文及配置 AAD 解密，失败仍由 Torznab 仓库处理。 */
    override fun decrypt(secret: EncryptedTorznabSecret, associatedData: ByteArray): ByteArray =
        cipher.decrypt(
            KeystoreEncryptedSecret(iv = secret.iv, ciphertext = secret.ciphertext),
            associatedData,
        )

    /** Torznab 设备密钥身份保持稳定，不能随文件迁移改变。 */
    private companion object {
        /** 既有 Torznab alias，所有已保存的 API Key 依赖该值。 */
        const val KEY_ALIAS = "clipmaster_torrent_search_torznab_api_key_v1"
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
