package com.cla.clip.feature.torrent.search.torznab.security

import com.cla.clip.feature.torrent.search.torznab.TorznabConfigId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Torznab API Key 的加密介质、AAD 隔离、损坏恢复与测试替身契约。 */
class EncryptedTorznabApiKeyStoreTest {
    /** 保存时介质不得出现明文，读取应只通过注入 cipher 恢复。 */
    @Test
    fun saveEncryptsApiKeyBeforeWritingBlobStore() {
        /** blobStore 只保存密文结构，模拟独立 SharedPreferences。 */
        val blobStore = FakeEncryptedSecretBlobStore()
        /** apiKeyStore 使用可控 cipher 测试真实编排而不依赖 Android Keystore。 */
        val apiKeyStore = EncryptedTorznabApiKeyStore(FakeTorznabApiKeyCipher(), blobStore)
        /** configId 是当前密钥的稳定 AAD。 */
        val configId = TorznabConfigId("torznab_fixture")

        assertTrue(apiKeyStore.save(configId, "synthetic-key"))

        /** storedSecret 是密文介质可见的数据，不应包含 UTF-8 明文。 */
        val storedSecret = checkNotNull(blobStore.values[configId.value])
        assertFalse(storedSecret.ciphertext.decodeToString().contains("synthetic-key"))
        assertEquals("synthetic-key", apiKeyStore.read(configId))
    }

    /** 把一项密文复制到另一配置后必须解密失败并清理，证明配置 ID 参与 AAD。 */
    @Test
    fun readRejectsCiphertextCopiedAcrossConfigIds() {
        /** blobStore 暴露测试内存映射以模拟密文被复制或恢复到错误身份。 */
        val blobStore = FakeEncryptedSecretBlobStore()
        /** apiKeyStore 使用会校验 AAD 的测试 cipher。 */
        val apiKeyStore = EncryptedTorznabApiKeyStore(FakeTorznabApiKeyCipher(), blobStore)
        /** sourceId 是原始加密身份。 */
        val sourceId = TorznabConfigId("torznab_source")
        /** targetId 是不应解开原密文的另一身份。 */
        val targetId = TorznabConfigId("torznab_target")
        assertTrue(apiKeyStore.save(sourceId, "synthetic-key"))
        blobStore.values[targetId.value] = checkNotNull(blobStore.values[sourceId.value])

        assertNull(apiKeyStore.read(targetId))
        assertFalse(blobStore.values.containsKey(targetId.value))
        assertEquals("synthetic-key", apiKeyStore.read(sourceId))
    }

    /** 损坏或 Key 失效导致的解密异常应清理当前密文并返回缺失。 */
    @Test
    fun readClearsUndecryptableSecret() {
        /** blobStore 预置无法通过测试 cipher 校验的密文。 */
        val blobStore = FakeEncryptedSecretBlobStore()
        /** configId 标识损坏密文所属配置。 */
        val configId = TorznabConfigId("torznab_fixture")
        blobStore.values[configId.value] = EncryptedTorznabSecret(
            iv = byteArrayOf(1),
            ciphertext = byteArrayOf(2),
        )
        /** apiKeyStore 应在失败时执行可恢复清理。 */
        val apiKeyStore = EncryptedTorznabApiKeyStore(FakeTorznabApiKeyCipher(), blobStore)

        assertNull(apiKeyStore.read(configId))
        assertFalse(blobStore.values.containsKey(configId.value))
    }

    /** 空白 Key 等价删除，超过 4096 字符时拒绝且不得覆盖既有密文。 */
    @Test
    fun saveDeletesBlankAndRejectsOversizedApiKeys() {
        /** blobStore 保存每次成功写入的当前密文。 */
        val blobStore = FakeEncryptedSecretBlobStore()
        /** apiKeyStore 使用无 Android 依赖的测试 cipher。 */
        val apiKeyStore = EncryptedTorznabApiKeyStore(FakeTorznabApiKeyCipher(), blobStore)
        /** configId 是所有写入操作的稳定身份。 */
        val configId = TorznabConfigId("torznab_fixture")
        assertTrue(apiKeyStore.save(configId, "synthetic-key"))
        /** originalSecret 用于证明超限失败不会破坏最后一个合法值。 */
        val originalSecret = checkNotNull(blobStore.values[configId.value])

        assertFalse(apiKeyStore.save(configId, "x".repeat(EncryptedTorznabApiKeyStore.MAX_API_KEY_CHARS + 1)))
        assertEquals(originalSecret, blobStore.values[configId.value])
        assertTrue(apiKeyStore.save(configId, "  "))
        assertNull(apiKeyStore.read(configId))
    }

    /** 删除提交失败不得向 UI 报告成功，且读取/恢复异常不得逃逸安全仓库。 */
    @Test
    fun storageFailuresReturnSafeResults() {
        /** blobStore 允许分别注入读取和删除故障。 */
        val blobStore = FakeEncryptedSecretBlobStore()
        /** apiKeyStore 使用正常测试 cipher，隔离介质失败语义。 */
        val apiKeyStore = EncryptedTorznabApiKeyStore(FakeTorznabApiKeyCipher(), blobStore)
        /** configId 是待验证失败路径的稳定身份。 */
        val configId = TorznabConfigId("torznab_fixture")
        assertTrue(apiKeyStore.save(configId, "synthetic-key"))

        blobStore.failRemove = true
        assertFalse(apiKeyStore.save(configId, " "))
        assertFalse(apiKeyStore.remove(configId))
        blobStore.failRead = true
        assertNull(apiKeyStore.read(configId))
    }

    /** 解密失败后即使坏密文清理也失败，读取仍必须收敛为缺失而不是抛出。 */
    @Test
    fun readContainsSimultaneousDecryptAndCleanupFailures() {
        /** blobStore 预置损坏密文并让恢复清理抛出异常。 */
        val blobStore = FakeEncryptedSecretBlobStore().apply {
            values["torznab_fixture"] = EncryptedTorznabSecret(byteArrayOf(1), byteArrayOf(2))
            failRemove = true
        }
        /** apiKeyStore 验证组合故障仍停留在安全仓库边界。 */
        val apiKeyStore = EncryptedTorznabApiKeyStore(FakeTorznabApiKeyCipher(), blobStore)
        /** configId 是损坏密文对应的稳定配置身份。 */
        val configId = TorznabConfigId("torznab_fixture")

        assertNull(apiKeyStore.read(configId))
        assertTrue(blobStore.values.containsKey(configId.value))
    }
}

/** 测试 cipher 用首个 AAD 字节绑定身份，并用异或保证介质不含明文。 */
private class FakeTorznabApiKeyCipher : TorznabApiKeyCipher {
    /** 加密时把 AAD 校验字节写入 IV，密文逐字节异或固定掩码。 */
    override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): EncryptedTorznabSecret {
        /** aadMarker 是当前配置身份的最小测试校验值。 */
        val aadMarker = associatedData.fold(0) { checksum, byte -> checksum xor byte.toInt() }.toByte()
        /** ciphertext 证明写入介质的数据不是明文。 */
        val ciphertext = plaintext.map { byte -> (byte.toInt() xor MASK).toByte() }.toByteArray()
        return EncryptedTorznabSecret(iv = byteArrayOf(aadMarker), ciphertext = ciphertext)
    }

    /** 解密先验证 AAD，再恢复原始 UTF-8 字节；错误身份抛无正文异常。 */
    override fun decrypt(secret: EncryptedTorznabSecret, associatedData: ByteArray): ByteArray {
        /** expectedMarker 是调用方配置身份对应的校验值。 */
        val expectedMarker = associatedData.fold(0) { checksum, byte -> checksum xor byte.toInt() }.toByte()
        if (secret.iv.singleOrNull() != expectedMarker) throw IllegalStateException()
            return secret.ciphertext.map { /** byte 是当前待还原的伪密文字节。 */ byte ->
                (byte.toInt() xor MASK).toByte()
            }.toByteArray()
    }

    /** 固定异或掩码只用于测试替身，不表达生产密码学。 */
    companion object {
        /** 非零掩码避免短明文直接出现在密文字节。 */
        private const val MASK = 0x5a
    }
}

/** 内存密文介质替代 SharedPreferences，只保留真实仓库的读写删除语义。 */
private class FakeEncryptedSecretBlobStore : TorznabEncryptedSecretBlobStore {
    /** values 按稳定配置 ID 保存当前密文。 */
    val values = linkedMapOf<String, EncryptedTorznabSecret>()
    /** failRead 模拟 SharedPreferences 类型损坏或读取异常。 */
    var failRead = false
    /** failRemove 模拟 commit 返回失败。 */
    var failRemove = false

    /** 读取指定配置的密文快照。 */
    override fun read(configId: TorznabConfigId): EncryptedTorznabSecret? {
        if (failRead) throw IllegalStateException()
        return values[configId.value]
    }

    /** 原子替换指定配置的密文。 */
    override fun write(configId: TorznabConfigId, secret: EncryptedTorznabSecret) {
        values[configId.value] = secret
    }

    /** 删除指定配置的密文。 */
    override fun remove(configId: TorznabConfigId) {
        if (failRemove) throw IllegalStateException()
        values.remove(configId.value)
    }
}
