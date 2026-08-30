package com.cla.clip.feature.torrent.search

/** 显式 Activity 启动时传递候选查询文本的稳定 extra key；任务 5 会在消费侧补输入安全处理。 */
internal const val EXTRA_TORRENT_SEARCH_INITIAL_QUERY =
    "com.cla.clip.feature.torrent.search.extra.INITIAL_QUERY"

/** 显式 Activity 启动时传递 [com.cla.clip.feature.torrent.search.api.TorrentSearchLaunchSource.code] 的稳定 extra key。 */
internal const val EXTRA_TORRENT_SEARCH_LAUNCH_SOURCE =
    "com.cla.clip.feature.torrent.search.extra.LAUNCH_SOURCE"
