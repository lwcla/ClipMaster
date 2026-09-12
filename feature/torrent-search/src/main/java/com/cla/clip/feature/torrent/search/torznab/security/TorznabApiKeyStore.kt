package com.cla.clip.feature.torrent.search.torznab.security

import com.cla.clip.feature.torrent.search.torznab.TorznabConfigId

/** 加密后的 Torznab API Key 数据；IV 和密文都不允许进入日志或备份。 */
internal data class EncryptedTorznabSecret(
    /** AES-GCM 每次加密生成的随机 IV。 */
    val iv: ByteArray,
    /** 包含 GCM 认证标签的密文字节。 */
    val ciphertext: ByteArray,
) {
    /** ByteArray 默认按引用比较，密文值对象需要按内容比较供介质和测试使用。 */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EncryptedTorznabSecret) return false
        return iv.contentEquals(other.iv) && ciphertext.contentEquals(other.ciphertext)
    }

    /** 与内容相等语义保持一致。 */
    override fun hashCode(): Int = 31 * iv.contentHashCode() + ciphertext.contentHashCode()
}

/** 可替换密码学边界；生产使用 Android Keystore，JVM 测试使用无安全含义的 fake。 */
internal interface TorznabApiKeyCipher {
    /** 使用配置 ID 的 UTF-8 字节作为 AAD 加密 API Key。 */
    fun encrypt(plaintext: ByteArray, associatedData: ByteArray): EncryptedTorznabSecret

    /** 校验 GCM 标签和配置 AAD 后解密；失败抛不含明文的异常。 */
    fun decrypt(secret: EncryptedTorznabSecret, associatedData: ByteArray): ByteArray
}

/** 只存取版本化密文的介质边界，生产实现为独立 SharedPreferences。 */
internal interface TorznabEncryptedSecretBlobStore {
    /** 读取指定配置密文；缺失或介质格式损坏返回 null。 */
    fun read(configId: TorznabConfigId): EncryptedTorznabSecret?

    /** 原子替换指定配置密文。 */
    fun write(configId: TorznabConfigId, secret: EncryptedTorznabSecret)

    /** 删除指定配置密文。 */
    fun remove(configId: TorznabConfigId)
}

/** 页面和 Torznab Provider 访问 API Key 的唯一接口。 */
internal interface TorznabApiKeyStore {
    /** 保存或删除 API Key；超限时返回 false 且保留既有密文。 */
    fun save(configId: TorznabConfigId, apiKey: String?): Boolean

    /** 解密 API Key；缺失、损坏或 Key 失效返回 null 并清理坏密文。 */
    fun read(configId: TorznabConfigId): String?

    /** 删除指定配置密钥；只有持久介质确认删除时返回 true。 */
    fun remove(configId: TorznabConfigId): Boolean
}

/** API Key 安全日志出口，只接收固定操作、状态和 reasonCode。 */
internal interface TorznabSecurityLogger {
    /** 输出低敏安全阶段，禁止接收配置 ID、Key、密文或异常 message。 */
    fun log(operationCode: String, stateCode: String, reasonCode: String? = null)
}

/** JVM 测试与无日志场景使用的空实现。 */
internal object NoOpTorznabSecurityLogger : TorznabSecurityLogger {
    /** 不产生任何运行时输出。 */
    override fun log(operationCode: String, stateCode: String, reasonCode: String?) = Unit
}

/** 将密码学与密文介质组合成可测试的 API Key 仓库。 */
internal class EncryptedTorznabApiKeyStore(
    /** Android Keystore 或测试 cipher。 */
    private val cipher: TorznabApiKeyCipher,
    /** 独立密文介质。 */
    private val blobStore: TorznabEncryptedSecretBlobStore,
    /** 低敏安全日志出口。 */
    private val logger: TorznabSecurityLogger = NoOpTorznabSecurityLogger,
) : TorznabApiKeyStore {
    /** 保存前裁剪误粘贴的边界空白；空白等价删除。 */
    override fun save(configId: TorznabConfigId, apiKey: String?): Boolean {
        /** normalizedApiKey 是仅在本调用内存活的待加密值。 */
        val normalizedApiKey = apiKey?.trim().orEmpty()
        if (normalizedApiKey.isEmpty()) {
            return remove(configId)
        }
        if (normalizedApiKey.length > MAX_API_KEY_CHARS) return false
        logger.log("torznab_api_key_save", "started")
        return try {
            /** associatedData 将密文绑定稳定配置身份，禁止跨配置复制。 */
            val associatedData = configId.value.toByteArray(Charsets.UTF_8)
            /** secret 在写入前已经过认证加密。 */
            val secret = cipher.encrypt(normalizedApiKey.toByteArray(Charsets.UTF_8), associatedData)
            blobStore.write(configId, secret)
            logger.log("torznab_api_key_save", "completed")
            true
        /** failure 不读取 message，只记录固定密码学失败。 */
        } catch (failure: Exception) {
            logger.log("torznab_api_key_save", "failed", "encryption_failed")
            false
        }
    }

    /** 解密失败视为设备绑定状态不可恢复，清理密文并要求用户重新输入。 */
    override fun read(configId: TorznabConfigId): String? {
        logger.log("torznab_api_key_read", "started")
        /** secret 读取失败必须停在安全边界，不能把介质异常交给页面或网络层。 */
        val secret = try {
            blobStore.read(configId) ?: return null
        /** failure 表示介质类型损坏或系统读取失败。 */
        } catch (failure: Exception) {
            logger.log("torznab_api_key_read", "failed", "storage_read_failed")
            return null
        }
        return try {
            /** associatedData 必须与加密时配置身份一致。 */
            val associatedData = configId.value.toByteArray(Charsets.UTF_8)
            /** plaintextBytes 只用于严格 UTF-8 恢复，随后不再持有。 */
            val plaintextBytes = cipher.decrypt(secret, associatedData)
            /** plaintext 必须非空且仍在协议上限内，损坏值不交给网络层。 */
            val plaintext = plaintextBytes.toString(Charsets.UTF_8).takeIf { value ->
                value.isNotEmpty() && value.length <= MAX_API_KEY_CHARS
            } ?: throw IllegalStateException()
            logger.log("torznab_api_key_read", "completed")
            plaintext
        /** failure 覆盖密文损坏、Key 失效、AAD 不匹配和介质迁移。 */
        } catch (failure: Exception) {
            /** cleanupSucceeded 区分不可解密已清理与仍需后续覆盖的介质故障。 */
            val cleanupSucceeded = removeSecret(configId)
            logger.log(
                "torznab_api_key_read",
                "fallback",
                if (cleanupSucceeded) "undecryptable" else "undecryptable_cleanup_failed",
            )
            null
        }
    }

    /** 删除密文，不删除共享 Keystore 主密钥以免破坏其他配置；结果反映真实持久化状态。 */
    override fun remove(configId: TorznabConfigId): Boolean {
        logger.log("torznab_api_key_remove", "started")
        /** removed 只在底层删除没有抛出时为 true。 */
        val removed = removeSecret(configId)
        if (removed) {
            logger.log("torznab_api_key_remove", "completed")
        } else {
            logger.log("torznab_api_key_remove", "failed", "storage_failed")
        }
        return removed
    }

    /** 执行单次介质删除并收敛异常，供显式删除和不可解恢复共同复用。 */
    private fun removeSecret(
        /** 待删除密文所属的稳定配置身份。 */
        configId: TorznabConfigId,
    ): Boolean {
        return try {
            blobStore.remove(configId)
            true
        /** failure 不泄漏介质路径或异常 message。 */
        } catch (failure: Exception) {
            false
        }
    }

    /** API Key 输入安全边界。 */
    companion object {
        /** 单个 API Key 最大 4096 字符，避免异常粘贴造成内存与介质膨胀。 */
        const val MAX_API_KEY_CHARS = 4096
    }
}
