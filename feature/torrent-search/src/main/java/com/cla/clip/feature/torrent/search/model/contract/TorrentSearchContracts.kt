package com.cla.clip.feature.torrent.search.model.contract

import com.cla.clip.feature.torrent.search.provider.ProviderId

/** 搜索分类稳定协议；十项语义与固定上游提交一致，持久化只使用显式 code。 */
enum class Category(
    /** 对外稳定分类标识，大小写敏感且禁止使用枚举 name 或 ordinal 代替。 */
    val code: String,
    /** 是否属于成人内容分类；安全模式使用该字段而不依赖名称猜测。 */
    val isNsfw: Boolean = false,
) {
    /** 不限定分类，也是未知持久值的安全回退。 */
    ALL("all"),
    /** 动漫分类。 */
    ANIME("anime"),
    /** 应用分类。 */
    APPS("apps"),
    /** 图书分类。 */
    BOOKS("books"),
    /** 游戏分类。 */
    GAMES("games"),
    /** 电影分类。 */
    MOVIES("movies"),
    /** 音乐分类。 */
    MUSIC("music"),
    /** 成人内容分类，安全模式必须过滤。 */
    PORN("porn", isNsfw = true),
    /** 连续剧分类。 */
    SERIES("series"),
    /** 无法归入其他分类的保守类别。 */
    OTHER("other");

    /** 集中提供稳定 code 的兼容读取。 */
    companion object {
        /** 精确匹配稳定 code；null、空白、大小写变化和未知值均回退全部分类。 */
        fun fromCode(code: String?): Category = entries.firstOrNull { category -> category.code == code } ?: ALL
    }
}

/** 搜索结果排序标准稳定协议，精确保留固定上游五项能力。 */
enum class SortCriteria(
    /** 对外稳定排序标准 code。 */
    val code: String,
) {
    /** 按名称排序。 */
    NAME("name"),
    /** 按做种数排序，也是未知值默认。 */
    SEEDERS("seeders"),
    /** 按连接节点数排序。 */
    PEERS("peers"),
    /** 按文件大小排序。 */
    FILE_SIZE("file_size"),
    /** 按上传时间排序。 */
    DATE("date");

    /** 集中提供稳定 code 的兼容读取。 */
    companion object {
        /** 精确匹配稳定 code；非法值回退固定上游默认 seeders。 */
        fun fromCode(code: String?): SortCriteria = entries.firstOrNull { criteria -> criteria.code == code } ?: SEEDERS
    }
}

/** 排序方向稳定协议，未知持久值采用固定上游默认降序。 */
enum class SortOrder(
    /** 对外稳定排序方向 code。 */
    val code: String,
) {
    /** 升序。 */
    ASCENDING("ascending"),
    /** 降序，也是未知值默认。 */
    DESCENDING("descending");

    /** 集中提供稳定 code 的兼容读取。 */
    companion object {
        /** 精确匹配稳定 code；非法值回退降序。 */
        fun fromCode(code: String?): SortOrder = entries.firstOrNull { order -> order.code == code } ?: DESCENDING
    }
}

/** Provider 失败的跨层稳定分类，UI、日志和后续存储只传递显式 code。 */
enum class ProviderFailureReason(
    /** 对外稳定失败 code，禁止替换为异常 message 或枚举 name。 */
    val code: String,
) {
    /** 网络连接或传输失败。 */
    NETWORK("network"),
    /** 单 Provider 总任务或底层套接字超时。 */
    TIMEOUT("timeout"),
    /** HTTPS 策略或 HTTP 状态拒绝。 */
    HTTP_DENIED("http_denied"),
    /** 内容结构解析失败。 */
    PARSE_FAILURE("parse_failure"),
    /** 命中 Cloudflare 挑战且尚未解锁。 */
    CLOUDFLARE_LOCKED("cloudflare_locked"),
    /** 安全模式主动跳过。 */
    SAFE_MODE_DISABLED("safe_mode_disabled"),
    /** 用户、collector 或父协程取消。 */
    CANCELLED("cancelled"),
    /** 解压后文本超过 5 MiB。 */
    RESPONSE_TOO_LARGE("response_too_large"),
    /** 无法安全归类的未知失败。 */
    UNKNOWN("unknown");

    /** 集中提供稳定 reasonCode 的兼容读取。 */
    companion object {
        /** 精确匹配稳定 code；非法值保守回退 unknown。 */
        fun fromCode(code: String?): ProviderFailureReason = entries.firstOrNull { reason -> reason.code == code } ?: UNKNOWN
    }
}

/** Provider 安全等级稳定协议；未知持久值必须保守视为不安全。 */
enum class ProviderSafetyLevel(
    /** 对外稳定安全等级 code。 */
    val code: String,
) {
    /** 安全模式允许执行的普通来源。 */
    SAFE("safe"),
    /** 需要用户明确关闭安全模式的不安全来源。 */
    UNSAFE("unsafe"),
    /** 明确包含成人内容且默认关闭的来源。 */
    NSFW("nsfw");

    /** 集中提供安全等级 code 的兼容读取。 */
    companion object {
        /** 精确匹配稳定 code；非法值按不安全处理以避免策略绕过。 */
        fun fromCode(code: String?): ProviderSafetyLevel = entries.firstOrNull { level -> level.code == code } ?: UNSAFE
    }
}

/** 完整搜索结果领域模型；时间统一为 UTC epochMillis，避免 minSdk 24 引入全局 java.time desugaring。 */
data class Torrent(
    /** Torrent 唯一 infoHash，后续书签和已查看记录以此作为稳定键。 */
    val infoHash: String,
    /** Torrent 名称，仅供业务/UI 使用，日志禁止输出。 */
    val name: String,
    /** 上游展示格式的大小文本；未知时为空。 */
    val size: String? = null,
    /** 做种数；未知时为空。 */
    val seeders: UInt? = null,
    /** 连接节点数；未知时为空。 */
    val peers: UInt? = null,
    /** 产出结果的强类型稳定 Provider ID。 */
    val providerId: ProviderId,
    /** Provider 展示名，可变且不得作为稳定键。 */
    val providerName: String,
    /** 上传时间的 UTC epochMillis；未知时为空。 */
    val uploadEpochMillis: Long? = null,
    /** 归一化分类；Provider 未提供时为空。 */
    val category: Category? = null,
    /** 详情页地址，仅在业务层传递且禁止写日志。 */
    val descriptionPageUrl: String? = null,
    /** 可选磁力链接，禁止写日志。 */
    val magnetUri: String? = null,
    /** 可选 torrent 文件下载地址，禁止写日志。 */
    val fileDownloadLink: String? = null,
) {
    /** 是否属于成人内容，缺失分类时按普通内容处理。 */
    val isNsfw: Boolean get() = category?.isNsfw ?: false

    /** 做种数与节点数都明确为零时视为死种；未知数据不误判。 */
    val isDead: Boolean get() = seeders == 0u && peers == 0u
}

/** 完整 Torrent 详情领域模型；时间字段同样统一为 UTC epochMillis。 */
data class TorrentDetails(
    /** Torrent 唯一 infoHash。 */
    val infoHash: String,
    /** Torrent 名称，日志禁止输出。 */
    val name: String,
    /** 上游展示格式的大小文本。 */
    val size: String? = null,
    /** 做种数。 */
    val seeders: UInt? = null,
    /** 连接节点数。 */
    val peers: UInt? = null,
    /** 详情来源的强类型稳定 Provider ID。 */
    val providerId: ProviderId,
    /** Provider 展示名，不作为稳定键。 */
    val providerName: String,
    /** 上传时间的 UTC epochMillis。 */
    val uploadEpochMillis: Long? = null,
    /** 归一化分类。 */
    val category: Category? = null,
    /** 发布者名称，日志禁止输出。 */
    val uploader: String? = null,
    /** Provider 最后检查时间的 UTC epochMillis。 */
    val lastCheckedEpochMillis: Long? = null,
    /** 可用磁力链接，详情成功时不能为空。 */
    val magnetUri: String,
    /** 可选 torrent 文件下载地址。 */
    val fileDownloadLink: String? = null,
    /** 可选 Markdown/HTML 描述，后续展示必须禁用脚本。 */
    val description: String? = null,
    /** 可选海报地址，图片层必须按展示尺寸解码。 */
    val posterUrl: String? = null,
    /** 截图地址集合，页面退出后必须取消加载。 */
    val screenshotUrls: List<String> = emptyList(),
    /** 后续 Provider 可提供的文件清单。 */
    val files: List<TorrentFile> = emptyList(),
    /** 后续 Provider 可提供的 tracker 清单。 */
    val trackers: List<TorrentTracker> = emptyList(),
) {
    /** 是否属于成人内容，缺失分类时按普通内容处理。 */
    val isNsfw: Boolean get() = category?.isNsfw ?: false
}

/** Torrent 内单个文件的领域契约。 */
data class TorrentFile(
    /** 文件相对名称，日志禁止输出。 */
    val name: String,
    /** 文件字节大小，未知时为空。 */
    val sizeBytes: Long? = null,
)

/** Torrent tracker 的领域契约。 */
data class TorrentTracker(
    /** tracker 地址，仅在业务层传递且禁止写日志。 */
    val address: String,
)
