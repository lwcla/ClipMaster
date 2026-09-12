package com.cla.clip.feature.torrent.search.backup

import com.cla.clip.base.general.backup.BackupFeatureContributor
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/** 把可选实现模块的 schema v4 贡献器加入 base 空集合扩展点。 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class TorrentSearchBackupModule {
    /** 启用 feature 时注册唯一 TorrentSearch 备份参与者。 */
    @Binds
    @IntoSet
    abstract fun bindTorrentSearchBackupContributor(contributor: TorrentSearchBackupContributor): BackupFeatureContributor
}
