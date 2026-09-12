package com.cla.clip.feature.torrent.search.system

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.cla.clip.feature.torrent.search.provider.builtin.common.MagnetInfoHashParser
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** 可翻译为系统 Intent 或剪贴板写入的封闭动作。 */
internal sealed interface TorrentExternalAction {
    /** 使用系统分享面板发送纯文本。 */
    data class ShareText(
        /** 分享正文，不得进入日志或异常。 */
        val text: String,
    ) : TorrentExternalAction

    /** 使用系统已注册客户端打开合法 btih magnet。 */
    data class OpenMagnet(
        /** 已通过 btih 解析的完整磁力 URI。 */
        val magnetUri: String,
    ) : TorrentExternalAction

    /** 写入系统剪贴板的文本。 */
    data class CopyText(
        /** 已裁剪且非空的复制正文。 */
        val text: String,
    ) : TorrentExternalAction
}

/** 从不可信详情字段构造封闭系统动作，Android API 不参与规则判断。 */
internal object TorrentExternalActionFactory {
    /** 非空文本可进入系统纯文本分享面板。 */
    fun shareText(
        /** 磁力或详情页分享正文。 */
        rawText: String,
    ): TorrentExternalAction.ShareText? {
        /** text 只裁剪边界空白，不改变 magnet 或 URL 内部编码。 */
        val text = rawText.trim()
        return text.takeIf(String::isNotEmpty)?.let(TorrentExternalAction::ShareText)
    }

    /** 只有能解析出合法 btih 的 magnet 才允许交给外部客户端。 */
    fun openMagnet(
        /** Provider 返回的候选磁力 URI。 */
        rawMagnet: String,
    ): TorrentExternalAction.OpenMagnet? {
        /** magnetUri 保留原始查询参数，但边界空白不属于 URI。 */
        val magnetUri = rawMagnet.trim()
        return if (MagnetInfoHashParser.parse(magnetUri) == null) null else TorrentExternalAction.OpenMagnet(magnetUri)
    }

    /** 非空文本可写入剪贴板，空白输入稳定拒绝。 */
    fun copyText(
        /** 待复制 magnet 或详情页地址。 */
        rawText: String,
    ): TorrentExternalAction.CopyText? {
        /** text 只裁剪边界空白。 */
        val text = rawText.trim()
        return text.takeIf(String::isNotEmpty)?.let(TorrentExternalAction::CopyText)
    }
}

/** Android 平台动作固定结果；不携带系统异常或用户内容。 */
internal enum class TorrentExternalActionResult {
    /** 系统动作已启动或剪贴板已写入。 */
    SUCCESS,
    /** 系统没有可处理当前磁力/分享动作的组件。 */
    NO_HANDLER,
    /** 平台服务不可用或系统拒绝动作。 */
    FAILED,
}

/** 把封闭动作翻译为 Android Intent/剪贴板调用的唯一平台边界。 */
internal class AndroidTorrentExternalActionGateway @Inject constructor(
    /** 应用 Context 只启动 NEW_TASK 系统动作，不持有 Activity。 */
    @param:ApplicationContext private val context: Context,
) {
    /** 执行动作；分享标题由页面资源传入但不进入日志。 */
    fun execute(
        /** 已通过纯工厂收窄的动作。 */
        action: TorrentExternalAction,
        /** 系统分享选择器标题。 */
        chooserTitle: String,
    ): TorrentExternalActionResult {
        return try {
            when (action) {
                is TorrentExternalAction.ShareText -> {
                    /** shareIntent 只声明纯文本 MIME 和正文。 */
                    val shareIntent = Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, action.text)
                    /** chooser 防止应用静默选择固定外部目标。 */
                    val chooser = Intent.createChooser(shareIntent, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(chooser)
                }
                is TorrentExternalAction.OpenMagnet -> {
                    /** viewIntent 只承载已验证 magnet URI。 */
                    val viewIntent = Intent(Intent.ACTION_VIEW, Uri.parse(action.magnetUri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(viewIntent)
                }
                is TorrentExternalAction.CopyText -> {
                    /** clipboardManager 从应用 Context 即时获取，不长期持有系统服务。 */
                    val clipboardManager = context.getSystemService(ClipboardManager::class.java)
                        ?: return TorrentExternalActionResult.FAILED
                    clipboardManager.setPrimaryClip(ClipData.newPlainText("torrent", action.text))
                }
            }
            TorrentExternalActionResult.SUCCESS
        /** noHandler 不读取系统异常 message。 */
        } catch (noHandler: ActivityNotFoundException) {
            TorrentExternalActionResult.NO_HANDLER
        /** failure 覆盖 SecurityException 等平台拒绝且不回显详情。 */
        } catch (failure: RuntimeException) {
            TorrentExternalActionResult.FAILED
        }
    }
}
