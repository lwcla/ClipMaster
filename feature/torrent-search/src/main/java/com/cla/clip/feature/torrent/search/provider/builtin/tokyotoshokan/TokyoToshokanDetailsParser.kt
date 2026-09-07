package com.cla.clip.feature.torrent.search.provider.builtin.tokyotoshokan

import com.cla.clip.feature.torrent.search.model.contract.TorrentDetails
import com.cla.clip.feature.torrent.search.provider.ProviderDescriptor
import com.cla.clip.feature.torrent.search.provider.builtin.common.MagnetInfoHashParser
import com.cla.clip.feature.torrent.search.provider.builtin.common.ProviderDateParser
import com.cla.clip.feature.torrent.search.provider.builtin.common.normalizeProviderDisplaySize
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

/** TokyoToshokan 详情页无网络 parser。 */
internal class TokyoToshokanDetailsParser(
    /** Provider 稳定身份来源。 */ private val descriptor: ProviderDescriptor,
    /** 同站文件与类别链接策略。 */ private val urlPolicy: TokyoToshokanUrlPolicy,
    /** DOM、日期与领域映射调度器。 */ private val parsingDispatcher: CoroutineDispatcher,
) {
    /** 名称和 hash 必填；现有 magnet 必须与 hash 一致，缺失时由公共 builder 生成。 */
    suspend fun parse(
        /** 有大小上限的 HTML。 */ html: String,
        /** 已验证详情页地址。 */ pageUrl: String,
    ): TorrentDetails? = withContext(parsingDispatcher) {
        /** document 仅静态解析，不执行页面脚本。 */ val document = Jsoup.parse(html, pageUrl)
        /** nameAnchor 同时承载名称和可选 torrent 文件。 */ val nameAnchor = document.selectFirst(NAME) ?: return@withContext null
        /** name 去除边界空白后必须非空。 */ val name = nameAnchor.ownText().trim().takeIf(String::isNotEmpty) ?: return@withContext null
        /** infoHash 由公共解析器规范化详情文本。 */ val infoHash = document.selectFirst(INFO_HASH)?.ownText()?.let(MagnetInfoHashParser::normalize) ?: return@withContext null
        /** pageMagnet 是页面可能缺失的 magnet 候选。 */ val pageMagnet = document.selectFirst(MAGNET_URI)?.attr("href")?.takeIf(String::isNotBlank)
        /** magnetUri 缺失时生成；存在时必须与详情 hash 一致。 */
        val magnetUri = if (pageMagnet == null) MagnetInfoHashParser.create(infoHash) else pageMagnet.takeIf { magnet -> MagnetInfoHashParser.parse(magnet) == infoHash } ?: return@withContext null
        /** fileDownloadLink 只有精确 MIME 且同站链接安全时保留。 */
        val fileDownloadLink = if (nameAnchor.attr("type") == "application/x-bittorrent") {
            urlPolicy.optionalSiteUrl(pageUrl, nameAnchor.attr("href"))
        } else {
            null
        }
        /** categoryId 只读取类别链接的 cat 参数。 */ val categoryId = urlPolicy.optionalCategoryId(document.selectFirst(CATEGORY)?.attr("href"))
        TorrentDetails(
            infoHash = infoHash,
            name = name,
            size = document.selectFirst(SIZE)?.ownText()?.let(::normalizeProviderDisplaySize),
            seeders = document.selectFirst(SEEDERS)?.ownText()?.trim()?.toUIntOrNull(),
            peers = document.selectFirst(PEERS)?.ownText()?.trim()?.toUIntOrNull(),
            providerId = descriptor.id,
            providerName = descriptor.displayName,
            /** date 是不会进入日志的详情日期文本。 */
            uploadEpochMillis = document.selectFirst(UPLOAD_DATE)?.ownText()?.let { date -> ProviderDateParser.parseUtcEpochMillis(date, "yyyy-MM-dd HH:mm z") },
            category = categoryId?.let(TokyoToshokanCategoryMapper::fromPageId),
            uploader = document.selectFirst(UPLOADER)?.ownText()?.trim()?.takeIf(String::isNotEmpty),
            magnetUri = magnetUri,
            fileDownloadLink = fileDownloadLink,
        )
    }

    /** 固定详情 selector。 */
    private companion object {
        /** 第十八项 hash。 */ private const val INFO_HASH = "#main > div.details > ul > li:nth-child(18)"
        /** 第六项名称和文件 anchor。 */ private const val NAME = "#main > div.details > ul > li:nth-child(6) > a"
        /** 第十项大小。 */ private const val SIZE = "#main > div.details > ul > li:nth-child(10)"
        /** 第二十项做种数。 */ private const val SEEDERS = "#main > div.details > ul > li:nth-child(20)"
        /** 第二十二项节点数。 */ private const val PEERS = "#main > div.details > ul > li:nth-child(22)"
        /** 第八项上传时间。 */ private const val UPLOAD_DATE = "#main > div.details > ul > li:nth-child(8)"
        /** 第二项类别链接。 */ private const val CATEGORY = "#main > div.details > ul > li:nth-child(2) > a"
        /** 第二十八项发布者。 */ private const val UPLOADER = "#main > div.details > ul > li:nth-child(28)"
        /** 任意 magnet anchor。 */ private const val MAGNET_URI = "a[href^=\"magnet:?\"]"
    }
}
