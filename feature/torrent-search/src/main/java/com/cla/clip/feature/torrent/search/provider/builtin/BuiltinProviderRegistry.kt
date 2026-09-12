package com.cla.clip.feature.torrent.search.provider.builtin

import com.cla.clip.feature.torrent.search.network.TorrentSearchNetworkClient
import com.cla.clip.feature.torrent.search.provider.SearchProvider
import com.cla.clip.feature.torrent.search.provider.builtin.anilibria.AniLibriaProvider
import com.cla.clip.feature.torrent.search.provider.builtin.animetosho.AnimeToshoProvider
import com.cla.clip.feature.torrent.search.provider.builtin.anirena.AniRenaProvider
import com.cla.clip.feature.torrent.search.provider.builtin.audiobookbay.AudioBookBayProvider
import com.cla.clip.feature.torrent.search.provider.builtin.bangumimoe.BangumiMoeProvider
import com.cla.clip.feature.torrent.search.provider.builtin.bitsearch.BitSearchProvider
import com.cla.clip.feature.torrent.search.provider.builtin.blueroms.BlueRomsProvider
import com.cla.clip.feature.torrent.search.provider.builtin.bt4g.Bt4gProvider
import com.cla.clip.feature.torrent.search.provider.builtin.btdigg.BtDiggProvider
import com.cla.clip.feature.torrent.search.provider.builtin.btsow.BtsowProvider
import com.cla.clip.feature.torrent.search.provider.builtin.dmhy.DmhyProvider
import com.cla.clip.feature.torrent.search.provider.builtin.ext.ExtProvider
import com.cla.clip.feature.torrent.search.provider.builtin.eztv.EztvProvider
import com.cla.clip.feature.torrent.search.provider.builtin.filemood.FileMoodProvider
import com.cla.clip.feature.torrent.search.provider.builtin.internetarchive.InternetArchiveProvider
import com.cla.clip.feature.torrent.search.provider.builtin.knaben.KnabenProvider
import com.cla.clip.feature.torrent.search.provider.builtin.limetorrents.LimeTorrentsProvider
import com.cla.clip.feature.torrent.search.provider.builtin.linuxtracker.LinuxTrackerProvider
import com.cla.clip.feature.torrent.search.provider.builtin.megapeer.MegaPeerProvider
import com.cla.clip.feature.torrent.search.provider.builtin.mikan.MikanProvider
import com.cla.clip.feature.torrent.search.provider.builtin.mypornclub.MyPornClubProvider
import com.cla.clip.feature.torrent.search.provider.builtin.nekobt.NekoBtProvider
import com.cla.clip.feature.torrent.search.provider.builtin.nyaa.NyaaProvider
import com.cla.clip.feature.torrent.search.provider.builtin.oxtorrent.OxTorrentProvider
import com.cla.clip.feature.torrent.search.provider.builtin.rutor.RutorProvider
import com.cla.clip.feature.torrent.search.provider.builtin.subsplease.SubsPleaseProvider
import com.cla.clip.feature.torrent.search.provider.builtin.sukebei.SukebeiProvider
import com.cla.clip.feature.torrent.search.provider.builtin.thepiratebay.ThePirateBayProvider
import com.cla.clip.feature.torrent.search.provider.builtin.therarbag.TheRarBgProvider
import com.cla.clip.feature.torrent.search.provider.builtin.thirteenthirtysevenx.ThirteenThirtySevenXProvider
import com.cla.clip.feature.torrent.search.provider.builtin.tokyotoshokan.TokyoToshokanProvider
import com.cla.clip.feature.torrent.search.provider.builtin.torrent9.Torrent9Provider
import com.cla.clip.feature.torrent.search.provider.builtin.torrentdatabase.TorrentDatabaseProvider
import com.cla.clip.feature.torrent.search.provider.builtin.torrentdownload.TorrentDownloadProvider
import com.cla.clip.feature.torrent.search.provider.builtin.torrentdownloads.TorrentDownloadsProvider
import com.cla.clip.feature.torrent.search.provider.builtin.torrentkitty.TorrentKittyProvider
import com.cla.clip.feature.torrent.search.provider.builtin.torrentscsv.TorrentsCsvProvider
import com.cla.clip.feature.torrent.search.provider.builtin.torrentz.TorrentzProvider
import com.cla.clip.feature.torrent.search.provider.builtin.uindex.UIndexProvider
import com.cla.clip.feature.torrent.search.provider.builtin.xxxclub.XXXClubProvider
import com.cla.clip.feature.torrent.search.provider.builtin.xxxtracker.XxxTrackerProvider
import com.cla.clip.feature.torrent.search.provider.builtin.yts.YtsProvider
import com.cla.clip.feature.torrent.search.provider.builtin.zeromagnet.ZeroMagnetProvider

/** 固定上游快照的内置 Provider 注册表，只负责构造稳定有序集合。 */
internal object BuiltinProviderRegistry {
    /**
     * 使用功能内共享网络边界创建全部内置 Provider。
     *
     * 列表顺序与 upstream-manifest.json 保持一致，调用方不得按类名或展示名重建顺序。
     */
    fun create(
        /** 所有 Provider 共用的可取消网络客户端，注册过程本身不发起请求。 */
        networkClient: TorrentSearchNetworkClient,
        /** 需要解析相对日期的 Provider 共用毫秒时间源，测试可注入固定值。 */
        currentEpochMillis: () -> Long = System::currentTimeMillis,
    ): List<SearchProvider> = listOf(
        AniLibriaProvider(networkClient),
        AnimeToshoProvider(networkClient),
        AniRenaProvider(networkClient),
        AudioBookBayProvider(networkClient),
        BangumiMoeProvider(networkClient),
        BitSearchProvider(networkClient),
        BlueRomsProvider(networkClient),
        Bt4gProvider(networkClient),
        BtDiggProvider(networkClient),
        BtsowProvider(networkClient),
        DmhyProvider(networkClient),
        ExtProvider(networkClient),
        EztvProvider(networkClient),
        FileMoodProvider(networkClient),
        InternetArchiveProvider(networkClient),
        KnabenProvider(networkClient),
        LimeTorrentsProvider(networkClient),
        LinuxTrackerProvider(networkClient),
        MegaPeerProvider(networkClient),
        MikanProvider(networkClient),
        MyPornClubProvider(networkClient),
        NekoBtProvider(networkClient),
        NyaaProvider(networkClient),
        OxTorrentProvider(networkClient),
        RutorProvider(networkClient),
        SubsPleaseProvider(networkClient),
        SukebeiProvider(networkClient),
        ThePirateBayProvider(networkClient),
        TheRarBgProvider(networkClient),
        ThirteenThirtySevenXProvider(networkClient, currentEpochMillis),
        TokyoToshokanProvider(networkClient),
        Torrent9Provider(networkClient),
        TorrentDatabaseProvider(networkClient),
        TorrentDownloadProvider(networkClient, currentEpochMillis),
        TorrentDownloadsProvider(networkClient),
        TorrentKittyProvider(networkClient),
        TorrentsCsvProvider(networkClient),
        TorrentzProvider(networkClient),
        UIndexProvider(networkClient),
        XXXClubProvider(networkClient),
        XxxTrackerProvider(networkClient),
        YtsProvider(networkClient),
        ZeroMagnetProvider(networkClient),
    )
}
