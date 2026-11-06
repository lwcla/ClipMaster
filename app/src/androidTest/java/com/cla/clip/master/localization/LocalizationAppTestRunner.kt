package com.cla.clip.master.localization

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.test.runner.AndroidJUnitRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import java.io.File

/** 完整页面测试保留真实 App/Hilt，在业务初始化前隔离自动更新网络副作用。 */
class LocalizationAppTestRunner : AndroidJUnitRunner() {
    /** 只允许独立测试身份，并提前写入检查时间；不改变生产实现或 Play 分发界面。 */
    override fun newApplication(cl: ClassLoader?, className: String?, context: Context?): Application {
        // target 是尚未启动 Application 的测试包上下文，禁止写入正式应用设置。
        val target = requireNotNull(context)
        check(target.packageName == "com.cla.clip.master.localizationcheck")
        configureEnglishProcess(target)
        // job 控制临时 DataStore 生命周期，完成后释放文件，避免与真实 App 的实例竞争。
        val job = SupervisorJob()
        // settings 使用生产同名存储与稳定键，只设置最近检查时间以触发现有 24 小时限频。
        val settings = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) {
            // 此时 Application 尚未创建，preferencesDataStoreFile 内部的 applicationContext 为 null；
            // 直接使用同一 AndroidX 标准文件位置，仍通过 DataStore 写协议，不手写序列化。
            File(target.filesDir, "datastore/app_settings.preferences_pb")
        }
        runBlocking {
            try {
                settings.edit { preferences ->
                    // 必须仍是未接受首启、无联网预览/自动备份的独立身份，避免残留配置触发副作用。
                    check(preferences[booleanPreferencesKey("first_launch_completed")] != true)
                    check(preferences[booleanPreferencesKey("link_preview_enabled")] != true)
                    check(preferences[booleanPreferencesKey("auto_backup_enabled")] != true)
                    preferences[longPreferencesKey("app_update_last_check_at")] = System.currentTimeMillis()
                }
            } finally {
                job.cancelAndJoin()
            }
        }
        return super.newApplication(cl, className, target)
    }

}
