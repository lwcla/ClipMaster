package com.cla.clip.feature.torrent.search.model.contract

/** 搜索分类的稳定持久化协议；code 可写入设置或跨层传递，禁止依赖枚举顺序。 */
enum class Category(
    /** 对外稳定分类标识，变更会影响历史设置和后续 Provider 映射。 */
    val code: String,
) {
    /** 不限定分类的默认回退值。 */
    ALL("all"),
    /** 影片分类。 */
    MOVIES("movies"),
    /** 电视剧分类。 */
    TV("tv"),
    /** 音乐分类。 */
    MUSIC("music"),
    /** 游戏分类。 */
    GAMES("games"),
    /** 应用分类。 */
    APPS("apps"),
    /** 动漫分类。 */
    ANIME("anime"),
    /** 图书分类。 */
    BOOKS("books"),
    /** 无法归类结果的保守分类。 */
    OTHER("other");

    /** 稳定 code 的兼容读取入口，集中定义未知值回退。 */
    companion object {
        /** 按稳定 code 读取分类；未知值不抛错并回退到默认全部分类。 */
        fun fromCode(code: String?): Category {
            /** 首个匹配的已知分类；null 表示旧数据或未来 code 未受支持。 */
            val matchedCategory = entries.firstOrNull { category -> category.code == code }
            return matchedCategory ?: ALL
        }
    }
}

/** 搜索结果排序的稳定持久化协议。 */
enum class Sort(
    /** 对外稳定排序标识，禁止使用 ordinal 或 name。 */
    val code: String,
) {
    /** Provider 默认相关度，是未知值的兼容回退。 */
    RELEVANCE("relevance"),
    /** 按上传人数排序。 */
    SEEDERS("seeders"),
    /** 按下载人数排序。 */
    LEECHERS("leechers"),
    /** 按资源体积排序。 */
    SIZE("size"),
    /** 按发布时间排序。 */
    DATE("date");

    /** 稳定排序 code 的兼容读取入口。 */
    companion object {
        /** 按稳定 code 读取排序；未知值回退为相关度，避免异常打断搜索。 */
        fun fromCode(code: String?): Sort {
            /** 首个匹配的排序枚举；null 表示不支持的持久化值。 */
            val matchedSort = entries.firstOrNull { sort -> sort.code == code }
            return matchedSort ?: RELEVANCE
        }
    }
}

/** Provider 失败的跨层稳定分类；每项 code 可供 UI、日志和后续存储安全传递。 */
enum class ProviderFailureReason(
    /** 对外稳定失败标识，禁止依赖异常信息或枚举名称。 */
    val code: String,
) {
    /** 可恢复网络传输失败。 */
    NETWORK("network"),
    /** 单 Provider 总任务超时。 */
    TIMEOUT("timeout"),
    /** HTTPS 策略或 HTTP 状态拒绝。 */
    HTTP_DENIED("http_denied"),
    /** 内容结构无法解析。 */
    PARSE_FAILURE("parse_failure"),
    /** 站点返回 Cloudflare 挑战而当前阶段不执行 WebView。 */
    CLOUDFLARE_LOCKED("cloudflare_locked"),
    /** 安全模式主动跳过。 */
    SAFE_MODE_DISABLED("safe_mode_disabled"),
    /** 用户、collector 或父 scope 取消。 */
    CANCELLED("cancelled"),
    /** 透明解压后的文本响应超过 5 MiB。 */
    RESPONSE_TOO_LARGE("response_too_large"),
    /** 未能安全归类的异常。 */
    UNKNOWN("unknown");

    /** 稳定失败 code 的兼容读取入口。 */
    companion object {
        /** 按稳定 code 读取失败原因；未知值收敛为 unknown。 */
        fun fromCode(code: String?): ProviderFailureReason {
            /** 匹配到的已知失败原因；不匹配时保守返回 unknown。 */
            val matchedReason = entries.firstOrNull { reason -> reason.code == code }
            return matchedReason ?: UNKNOWN
        }
    }
}

/** Provider 的安全模式等级；未知持久化值必须保守视为不安全。 */
enum class ProviderSafetyLevel(
    /** 对外稳定安全等级标识。 */
    val code: String,
) {
    /** 安全模式允许执行的默认来源。 */
    SAFE("safe"),
    /** 安全模式必须跳过的高风险来源。 */
    UNSAFE("unsafe"),
    /** 明确成人内容来源，默认也必须跳过。 */
    NSFW("nsfw");

    /** 稳定安全等级 code 的兼容读取入口。 */
    companion object {
        /** 按稳定 code 读取安全等级；未知值按不安全处理，避免策略绕过。 */
        fun fromCode(code: String?): ProviderSafetyLevel {
            /** 匹配到的安全等级；未知值不可信。 */
            val matchedLevel = entries.firstOrNull { level -> level.code == code }
            return matchedLevel ?: UNSAFE
        }
    }
}

/** 单个搜索结果的最小领域模型；后续 Provider 可增补字段但不得把页面状态放入此类型。 */
data class Torrent(
    /** 产出结果的稳定 Provider ID。 */
    val providerId: String,
    /** 结果标题，仅在内存中传递，日志禁止输出。 */
    val title: String,
    /** Provider 内部详情地址，日志禁止输出。 */
    val detailsUrl: String,
    /** 可选磁力链接，日志和错误信息禁止输出。 */
    val magnetUri: String? = null,
    /** 已归一化的领域分类。 */
    val category: Category = Category.OTHER,
    /** 可选上传人数，未知时为空。 */
    val seeders: Int? = null,
    /** 可选下载人数，未知时为空。 */
    val leechers: Int? = null,
    /** 可选字节大小，未知时为空。 */
    val sizeBytes: Long? = null,
)

/** 详情页需要的最小领域模型；网络和 UI 实现留在后续任务。 */
data class TorrentDetails(
    /** 原始结果的稳定 Provider ID。 */
    val providerId: String,
    /** 详情标题，仅供 UI 使用且禁止写日志。 */
    val title: String,
    /** 文件清单，缺失时为空列表。 */
    val files: List<TorrentFile> = emptyList(),
    /** Tracker 清单，缺失时为空列表。 */
    val trackers: List<TorrentTracker> = emptyList(),
)

/** Torrent 内文件的最小展示与下载契约。 */
data class TorrentFile(
    /** 文件相对名称，日志禁止输出完整内容。 */
    val name: String,
    /** 文件字节大小，未知时为空。 */
    val sizeBytes: Long? = null,
)

/** Torrent tracker 的最小领域契约，后续可由详情 Provider 填充。 */
data class TorrentTracker(
    /** tracker 地址，仅在业务层传递，日志禁止输出。 */
    val address: String,
)
