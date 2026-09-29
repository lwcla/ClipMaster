package com.cla.clip.base.general.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.KeyStore
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 使用设备 Android Keystore 验证真实 AES-GCM、AAD 与缺钥边界。 */
@RunWith(AndroidJUnit4::class)
class AndroidKeystoreSecretCipherInstrumentedTest {
    /** 相同 alias 与 AAD 可解密；更换 AAD 或篡改认证密文必须失败。 */
    @Test
    fun decryptRejectsWrongAadAndTamperedCiphertext() {
        /** alias 仅属于本次设备测试，不触碰应用实际凭据密钥。 */
        val alias = "clipmaster_test_secret_${UUID.randomUUID()}"
        try {
            /** cipher 使用真实系统 provider，并保留其随机 IV。 */
            val cipher = AndroidKeystoreSecretCipher(alias)
            /** plaintext 为合成凭据，禁止使用真实用户数据。 */
            val plaintext = "synthetic-secret".toByteArray(Charsets.UTF_8)
            /** aad 固定合成业务身份，用于校验跨目标复制失败。 */
            val aad = "synthetic-target".toByteArray(Charsets.UTF_8)
            /** secret 包含加密结果和系统生成的 IV。 */
            val secret = cipher.encrypt(plaintext, aad)
            assertArrayEquals(plaintext, cipher.decrypt(secret, aad))
            assertTrue(runCatching { cipher.decrypt(secret, "other-target".toByteArray(Charsets.UTF_8)) }.isFailure)

            /** alteredCiphertext 只翻转认证标签的最后一位，模拟介质篡改。 */
            val alteredCiphertext = secret.ciphertext.copyOf()
            alteredCiphertext[alteredCiphertext.lastIndex] =
                (alteredCiphertext.last().toInt() xor 1).toByte()
            assertTrue(
                runCatching {
                    cipher.decrypt(KeystoreEncryptedSecret(secret.iv, alteredCiphertext), aad)
                }.isFailure,
            )
        } finally {
            /** keyStore 删除本次测试密钥，避免设备测试污染业务 alias。 */
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)
            keyStore.deleteEntry(alias)
        }
    }

    /** 解密缺钥时必须失败，且不会意外创建该 alias 的新密钥。 */
    @Test
    fun decryptMissingAliasDoesNotCreateKey() {
        /** alias 唯一化，确保密钥缺失条件与其他测试隔离。 */
        val alias = "clipmaster_test_missing_${UUID.randomUUID()}"
        /** keyStore 检查解密前后系统密钥状态。 */
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)
        assertFalse(keyStore.containsAlias(alias))
        /** secret 使用合成 IV 和认证密文，缺钥检查应先于密码学解析。 */
        val secret = KeystoreEncryptedSecret(ByteArray(12), ByteArray(16))
        assertTrue(runCatching { AndroidKeystoreSecretCipher(alias).decrypt(secret, byteArrayOf(1)) }.isFailure)
        keyStore.load(null)
        assertFalse(keyStore.containsAlias(alias))
    }
}
