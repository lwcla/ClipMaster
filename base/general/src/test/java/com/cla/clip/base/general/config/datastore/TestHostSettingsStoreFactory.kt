package com.cla.clip.base.general.config.datastore

import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.PreferencesSerializer
import kotlinx.coroutines.CoroutineScope
import okio.FileSystem
import okio.Path.Companion.toPath
import java.io.File

/** Windows JVM 测试介质；生产仍使用 Android 官方 FileStorage 装配。 */
internal fun createTestHostSettingsStore(scope: CoroutineScope, file: File): HostSettingsStore {
    /** Okio 存储以跨平台替换现有文件，避免 Android SDK=0 时 File.renameTo 在 Windows 上失败。 */
    val storage = OkioStorage(
        fileSystem = FileSystem.SYSTEM,
        serializer = PreferencesSerializer,
        producePath = { file.absolutePath.toPath() },
    )
    return HostSettingsStore(PreferenceDataStoreFactory.create(storage = storage, scope = scope))
}
