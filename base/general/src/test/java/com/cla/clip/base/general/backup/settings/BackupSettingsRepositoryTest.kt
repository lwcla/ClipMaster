package com.cla.clip.base.general.backup.settings

import com.cla.clip.base.general.config.datastore.createTestHostSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 备份设置写盘契约测试，使用真实临时文件和可替换密文边界。 */
class BackupSettingsRepositoryTest {
    /** 本测试独占的虚构配置文件目录。 */
    @get:Rule val folder = TemporaryFolder(File("build"))

    @Test
    /** 凭据加密失败时，地址、用户名与其他关联字段都不能部分生效。 */
    fun failedCredentialEncryptionDoesNotCommitWebDavConfig() = runBlocking {
        /** 只服务本测试的 DataStore 作业作用域。 */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            /** 当前测试持久化配置的独立文件。 */
            val file = File(folder.root, "webdav-settings.preferences_pb")
            /** 基于真实文件的宿主设置存储。 */
            val store = createTestHostSettingsStore(scope, file)
            /** 合成的密码学失败边界；绝不访问真实 Android Keystore。 */
            val failingCodec = object : WebDavCredentialCodec {
                /** 模拟 Keystore 拒绝保存新密码。 */
                override fun encrypt(password: String): String = throw IllegalStateException("synthetic_encrypt_failure")
                /** 当前测试不读取密文。 */
                override fun decrypt(encoded: String): String = error("unreachable")
            }
            /** 被测领域仓库。 */
            val repository = BackupSettingsRepository(store, failingCodec)
            /** 用户尝试保存的合成目标，不包含实际服务地址。 */
            val input = WebDavSettingsInput(
                endpoint = "https://example.invalid/dav",
                username = "synthetic",
                remoteDir = "/ClipMaster/backups/",
                allowInsecureHttp = false,
                passwordUpdate = WebDavPasswordUpdate.Replace("synthetic-secret"),
            )

            assertThrows(IllegalStateException::class.java) { runBlocking { repository.saveWebDav(input) } }
            /** 失败后从磁盘读取的状态，不允许是只写了部分配置的快照。 */
            val saved = repository.snapshot()
            assertEquals("", saved.webDavEndpoint)
            assertEquals("", saved.webDavUsername)
            assertFalse(saved.hasWebDavPassword)
        } finally {
            scope.cancel()
        }
    }

    @Test
    /** 地址或账户变化时不能悄悄沿用已加密的旧密码，拒绝后仍保留原配置。 */
    fun retainingPasswordForChangedIdentityIsRejectedAtomically() = runBlocking {
        /** 本测试独占的 DataStore 生命周期。 */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            /** 合成设备配置文件，不接触实际账户。 */
            val file = File(folder.root, "webdav-identity.preferences_pb")
            /** 可逆的测试编解码器，仅用于验证配置与密码同快照。 */
            val codec = object : WebDavCredentialCodec {
                /** 用固定前缀模拟已加密内容。 */
                override fun encrypt(password: String): String = "encoded:$password"
                /** 只接受测试前缀并恢复合成密码。 */
                override fun decrypt(encoded: String): String = encoded.removePrefix("encoded:")
            }
            /** 被测试的本机备份设置仓库。 */
            val repository = BackupSettingsRepository(createTestHostSettingsStore(scope, file), codec)
            repository.saveWebDav(WebDavSettingsInput("https://old.invalid/dav", "old-user", "/backups/", false, WebDavPasswordUpdate.Replace("old-secret")))

            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    repository.saveWebDav(WebDavSettingsInput("https://new.invalid/dav", "old-user", "/backups/", false, WebDavPasswordUpdate.Retain))
                }
            }
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    repository.saveWebDav(WebDavSettingsInput("https://old.invalid/dav", "new-user", "/backups/", false, WebDavPasswordUpdate.Retain))
                }
            }
            /** 失败后同一磁盘快照里的旧服务与旧密码仍匹配。 */
            val connection = repository.loadWebDavConnection()
            assertEquals("https://old.invalid/dav", connection.settings.webDavEndpoint)
            assertEquals("old-user", connection.settings.webDavUsername)
            assertEquals("old-secret", connection.password)
        } finally {
            scope.cancel()
        }
    }
}
