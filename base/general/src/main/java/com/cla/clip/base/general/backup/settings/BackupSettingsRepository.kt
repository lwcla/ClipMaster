package com.cla.clip.base.general.backup.settings

import android.util.Base64
import androidx.datastore.preferences.core.Preferences
import com.cla.clip.base.general.backup.normalizeWebDavRemoteDir
import com.cla.clip.base.general.config.datastore.HostSettingsKeys
import com.cla.clip.base.general.config.datastore.HostSettingsStore
import com.cla.clip.base.general.security.AndroidKeystoreSecretCipher
import com.cla.clip.base.general.security.KeystoreEncryptedSecret
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** 宿主本机备份目标与执行策略；密码只通过是否已设置字段呈现。 */
data class BackupSettingsSnapshot(
    /** WebDAV 服务根地址，空表示未配置。 */
    val webDavEndpoint: String = "",
    /** WebDAV 登录用户名，只保存在本机。 */
    val webDavUsername: String = "",
    /** 当前安装是否有一份 WebDAV 密文，不表示密钥仍可用。 */
    val hasWebDavPassword: Boolean = false,
    /** WebDAV 备份目录；实际使用时仍经过备份模块校验。 */
    val webDavRemoteDir: String = "/ClipMaster/backups/",
    /** 是否由用户允许明文 HTTP，仅影响本机连接。 */
    val webDavAllowInsecureHttp: Boolean = false,
    /** 设备绑定的 SAF 目录授权 URI。 */
    val localBackupDirUri: String = "",
    /** 本地目录 UI 展示名，不用于实际文件访问。 */
    val localBackupDirLabel: String = "",
    /** 是否由用户开启后台自动备份。 */
    val autoBackupEnabled: Boolean = false,
    /** 用户是否确认备份 ZIP 不加密的本机提示。 */
    val backupPlaintextDisclosureAccepted: Boolean = false,
    /** 普通备份每个目标保留的份数，范围 1 至 20。 */
    val backupRetentionCount: Int = 5,
)

/** 同一次 DataStore 读取取得的目标配置与密码，供网络动作或页面内存回填使用，避免旧地址配新密码。 */
data class WebDavConnectionSnapshot(
    /** 与下方密码同一次读取的本机备份设置。 */
    val settings: BackupSettingsSnapshot,
    /** 当前网络动作或备份页内存回填使用的明文，未设置时为空；不得持久化到状态恢复或备份。 */
    val password: String?,
)

/** 保存密码时的明确操作；只查看回填密码不触发替换，保留操作不重新加密。 */
sealed interface WebDavPasswordUpdate {
    /** 使用既有密文，调用方须确认目标未被不经意替换。 */
    data object Retain : WebDavPasswordUpdate
    /** 替换为用户本次输入的新密码，空值代表服务允许空密码。 */
    data class Replace(
        /** 本次提交的密码草稿，只在调用期间保留。 */
        val password: String,
    ) : WebDavPasswordUpdate
    /** 主动移除已经保存的密码。 */
    data object Clear : WebDavPasswordUpdate
}

/** 一次性提交 WebDAV 关联配置的输入，避免页面逐字段写盘造成不一致。 */
data class WebDavSettingsInput(
    /** 用户确认后的服务地址。 */
    val endpoint: String,
    /** 用户确认后的用户名。 */
    val username: String,
    /** 用户确认后的远端备份目录。 */
    val remoteDir: String,
    /** 用户对 HTTP 的明确授权。 */
    val allowInsecureHttp: Boolean,
    /** 保留、替换或清除密码的明确动作。 */
    val passwordUpdate: WebDavPasswordUpdate,
)

/** 可替换的 WebDAV 凭据编解码边界；JVM 测试不需要真实系统 Keystore。 */
interface WebDavCredentialCodec {
    /** 返回仅供设备安装内保存的认证密文；失败必须抛出。 */
    fun encrypt(password: String): String
    /** 从版本化认证密文恢复密码；缺钥、损坏和认证失败必须抛出。 */
    fun decrypt(encoded: String): String
}

/** 已保存密文无法由本机 Keystore 解密；调用方应要求用户重新输入密码。 */
class WebDavCredentialUnavailable(cause: Throwable) : Exception("webdav_credential_unavailable", cause)

/** 独立 WebDAV alias 的 Android Keystore 加密适配层，不存储或记录密码明文。 */
@Singleton
class AndroidWebDavCredentialCodec @Inject constructor() : WebDavCredentialCodec {
    /** 与 Torznab 不同的设备绑定密钥，只服务 WebDAV 密码。 */
    private val cipher = AndroidKeystoreSecretCipher("clipmaster_webdav_password_v1")

    /** 使用固定用途 AAD 加密，并保存版本、随机 IV 与带标签的密文。 */
    override fun encrypt(password: String): String {
        /** 设备加密结果，密文不含明文密码。 */
        val secret = cipher.encrypt(password.toByteArray(Charsets.UTF_8), ASSOCIATED_DATA)
        return listOf(
            FORMAT_VERSION,
            Base64.encodeToString(secret.iv, Base64.NO_WRAP),
            Base64.encodeToString(secret.ciphertext, Base64.NO_WRAP),
        ).joinToString(":")
    }

    /** 校验版本和认证标签后解密；任何格式或密钥错误向调用方传播。 */
    override fun decrypt(encoded: String): String {
        /** 密文协议固定的三个字段。 */
        val parts = encoded.split(':')
        require(parts.size == 3 && parts[0] == FORMAT_VERSION)
        /** 版本 1 持久化的随机 IV。 */
        val iv = Base64.decode(parts[1], Base64.NO_WRAP)
        /** 包含认证标签的密文字节。 */
        val ciphertext = Base64.decode(parts[2], Base64.NO_WRAP)
        require(iv.isNotEmpty() && ciphertext.isNotEmpty())
        return cipher.decrypt(KeystoreEncryptedSecret(iv, ciphertext), ASSOCIATED_DATA).toString(Charsets.UTF_8)
    }

    /** 与业务身份稳定绑定的密码学协议常量。 */
    private companion object {
        /** 可在未来显式升级的密文格式版本。 */
        const val FORMAT_VERSION = "1"
        /** 防止同一个密文被另一种用途误用的固定认证附加数据。 */
        val ASSOCIATED_DATA = "clipmaster:webdav:password:v1".toByteArray(Charsets.UTF_8)
    }
}

/** 本机备份配置仓库；所有关联字段在同一次 DataStore edit 中提交。 */
@Singleton
class BackupSettingsRepository @Inject internal constructor(
    /** 唯一的宿主 DataStore 边界。 */
    private val store: HostSettingsStore,
    /** 可替换凭据编解码器，保证加密失败不部分保存配置。 */
    private val credentialCodec: WebDavCredentialCodec,
) {
    /** 领域只读设置流，其他域键变化不触发本域重复状态。 */
    val data: Flow<BackupSettingsSnapshot> = store.data.map(::decode).distinctUntilChanged()

    /** 读取一份已提交的配置；磁盘错误不伪装成新安装。 */
    suspend fun snapshot(): BackupSettingsSnapshot = decode(store.snapshot())

    /** 先校验并加密草稿，成功后一次性提交地址、目录、HTTP 开关和密码。 */
    suspend fun saveWebDav(input: WebDavSettingsInput) {
        /** 已规范化的远端目录，非法输入不会进入持久化。 */
        val remoteDir = normalizeWebDavRemoteDir(input.remoteDir)
        /** 新密码认证密文；加密失败发生在写事务前。 */
        val encrypted = when (val passwordUpdate = input.passwordUpdate) {
            WebDavPasswordUpdate.Retain, WebDavPasswordUpdate.Clear -> null
            is WebDavPasswordUpdate.Replace -> withContext(Dispatchers.IO) { credentialCodec.encrypt(passwordUpdate.password) }
        }
        store.edit { preferences ->
            /** 本次事务开始前已保存的远端身份，凭据保留不得跨服务或账户。 */
            val previousEndpoint = preferences[HostSettingsKeys.WEBDAV_ENDPOINT].orEmpty()
            /** 同一远端服务的既有用户名。 */
            val previousUsername = preferences[HostSettingsKeys.WEBDAV_USERNAME].orEmpty()
            if (input.passwordUpdate == WebDavPasswordUpdate.Retain &&
                preferences[HostSettingsKeys.WEBDAV_PASSWORD_CIPHERTEXT] != null &&
                (previousEndpoint != input.endpoint.trim() || previousUsername != input.username)
            ) {
                throw IllegalArgumentException("webdav_identity_changed_requires_password_update")
            }
            preferences[HostSettingsKeys.WEBDAV_ENDPOINT] = input.endpoint.trim()
            preferences[HostSettingsKeys.WEBDAV_USERNAME] = input.username
            preferences[HostSettingsKeys.WEBDAV_REMOTE_DIR] = remoteDir
            preferences[HostSettingsKeys.WEBDAV_ALLOW_INSECURE_HTTP] = input.allowInsecureHttp
            when (input.passwordUpdate) {
                WebDavPasswordUpdate.Retain -> Unit
                WebDavPasswordUpdate.Clear -> preferences.remove(HostSettingsKeys.WEBDAV_PASSWORD_CIPHERTEXT)
                is WebDavPasswordUpdate.Replace -> preferences[HostSettingsKeys.WEBDAV_PASSWORD_CIPHERTEXT] = requireNotNull(encrypted)
            }
            preferences[HostSettingsKeys.WEBDAV_HEALTH] = "unknown"
            preferences[HostSettingsKeys.WEBDAV_HEALTH_CHECKED_AT] = 0L
        }
    }

    /** 从单份已提交快照读取目标与认证信息，供网络操作或页面回填使用；解密错误显式传播。 */
    suspend fun loadWebDavConnection(): WebDavConnectionSnapshot = withContext(Dispatchers.IO) {
        /** 目标配置和密码密文的同一次 DataStore 快照。 */
        val preferences = store.snapshot()
        /** 不含明文密码的领域设置值。 */
        val settings = decode(preferences)
        /** 只有配置了远端服务才需要取出和解密认证密文。 */
        val encrypted = preferences[HostSettingsKeys.WEBDAV_PASSWORD_CIPHERTEXT]
            ?.takeIf { settings.webDavEndpoint.isNotBlank() }
        /** 当前网络动作或页面回填的密码，Keystore 失败需用户重新输入。 */
        val password = encrypted?.let { ciphertext ->
            try {
                credentialCodec.decrypt(ciphertext)
            } catch (failure: Exception) {
                throw WebDavCredentialUnavailable(failure)
            }
        }
        WebDavConnectionSnapshot(settings, password)
    }

    /** 兼容仅需密码的调用入口，内部仍保持同份快照读取。 */
    suspend fun loadWebDavPassword(): String? = loadWebDavConnection().password

    /** 授权已取得后成组更新 URI 和展示名；空 URI 会同时清空展示名。 */
    suspend fun setLocalBackupDirectory(uri: String, label: String) {
        store.edit { preferences ->
            preferences[HostSettingsKeys.LOCAL_BACKUP_DIR_URI] = uri
            preferences[HostSettingsKeys.LOCAL_BACKUP_DIR_LABEL] = if (uri.isBlank()) "" else label
        }
    }

    /** 持久化用户已通过目标与披露校验的后台自动备份开关。 */
    suspend fun setAutoBackupEnabled(enabled: Boolean) {
        store.edit { preferences -> preferences[HostSettingsKeys.AUTO_BACKUP_ENABLED] = enabled }
    }

    /** 持久化本机对未加密 ZIP 的披露确认。 */
    suspend fun setPlaintextDisclosureAccepted(accepted: Boolean) {
        store.edit { preferences -> preferences[HostSettingsKeys.BACKUP_PLAINTEXT_DISCLOSURE_ACCEPTED] = accepted }
    }

    /** 按既有 1 至 20 上限保存普通备份份数。 */
    suspend fun setRetentionCount(count: Int) {
        store.edit { preferences -> preferences[HostSettingsKeys.BACKUP_RETENTION_COUNT] = count.coerceIn(1, 20) }
    }


    /** 从单次磁盘快照映射全部备份配置，密码只暴露存在性。 */
    private fun decode(preferences: Preferences): BackupSettingsSnapshot = BackupSettingsSnapshot(
        webDavEndpoint = preferences[HostSettingsKeys.WEBDAV_ENDPOINT].orEmpty(),
        webDavUsername = preferences[HostSettingsKeys.WEBDAV_USERNAME].orEmpty(),
        hasWebDavPassword = preferences[HostSettingsKeys.WEBDAV_PASSWORD_CIPHERTEXT] != null,
        webDavRemoteDir = preferences[HostSettingsKeys.WEBDAV_REMOTE_DIR] ?: "/ClipMaster/backups/",
        webDavAllowInsecureHttp = preferences[HostSettingsKeys.WEBDAV_ALLOW_INSECURE_HTTP] ?: false,
        localBackupDirUri = preferences[HostSettingsKeys.LOCAL_BACKUP_DIR_URI].orEmpty(),
        localBackupDirLabel = preferences[HostSettingsKeys.LOCAL_BACKUP_DIR_LABEL].orEmpty(),
        autoBackupEnabled = preferences[HostSettingsKeys.AUTO_BACKUP_ENABLED] ?: false,
        backupPlaintextDisclosureAccepted = preferences[HostSettingsKeys.BACKUP_PLAINTEXT_DISCLOSURE_ACCEPTED] ?: false,
        backupRetentionCount = (preferences[HostSettingsKeys.BACKUP_RETENTION_COUNT] ?: 5).coerceIn(1, 20),
    )
}
