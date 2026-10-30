package com.cla.clip.master.backup.local;

import androidx.datastore.core.DataStore;
import androidx.datastore.preferences.core.Preferences;
import com.cla.clip.base.general.config.clip.ClipSettingsRepository;
import com.cla.clip.base.general.config.datastore.HostSettingsStore;
import com.cla.clip.base.general.config.installation.InstallationIdentityRepository;

/** 仅测试使用的 JVM 构造桥：装配独立 DataStore，不扩大基础模块 Kotlin internal 的生产 API。 */
final class DeviceBackupSettingsFactory {
    /** 不创建工厂实例，两个方法只装配测试所需的真实设置仓库。 */
    private DeviceBackupSettingsFactory() {}

    /** 使用测试独占介质装配剪贴设置，避免访问安装中的正式偏好。 */
    static ClipSettingsRepository clipSettings(DataStore<Preferences> dataStore) {
        return new ClipSettingsRepository(new HostSettingsStore(dataStore));
    }

    /** 使用同一测试介质生成合成安装身份，只用于备份文件名。 */
    static InstallationIdentityRepository installation(DataStore<Preferences> dataStore) {
        return new InstallationIdentityRepository(new HostSettingsStore(dataStore));
    }
}
