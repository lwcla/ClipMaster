package com.cla.clip.base.general.backup.settings

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** 将设备 Keystore 凭据编解码器绑定到备份配置的数据层接口。 */
@Module
@InstallIn(SingletonComponent::class)
abstract class WebDavCredentialModule {
    /** 生产环境使用独立 WebDAV alias；JVM 测试直接注入可控 fake。 */
    @Binds
    abstract fun bindCredentialCodec(codec: AndroidWebDavCredentialCodec): WebDavCredentialCodec
}
