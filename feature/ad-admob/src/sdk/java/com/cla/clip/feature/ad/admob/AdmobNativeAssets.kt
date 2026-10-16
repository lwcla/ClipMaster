package com.cla.clip.feature.ad.admob

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.libraries.ads.mobile.sdk.nativead.MediaView
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAd
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdView

/** 创建并登记素材 View，布局与样式由独立协作者负责，不持有页面或请求状态。 */
internal class AdmobNativeAssets(
    /** 页面 Context 只随素材树存活。 */
    private val context: Context,
    /** 已加载的 SDK 素材，只读取展示字段，不输出到日志。 */
    private val ad: NativeAd,
    /** 每个素材均登记到同一个 SDK 根容器。 */
    private val root: NativeAdView,
) {
    /** 构建一次完整素材集，视频类型由原有纯策略决定，未知类型保留媒体。 */
    fun create(requiresMedia: Boolean): AdmobNativeCardElements {
        /** 标题、正文、CTA 分别保持 SDK 所有权及完整文本，不装自己的点击监听。 */
        val headline = label(ad.headline, R.dimen.ad_admob_headline_text).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            root.headlineView = this
        }
        val body = label(AdmobNativeLayoutPolicy.compactBody(ad.body), R.dimen.ad_admob_body_text).apply {
            visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
            root.bodyView = this
        }
        val action = Button(context, null, 0).apply {
            text = ad.callToAction
            setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.ad_admob_action_text))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            isAllCaps = false
            includeFontPadding = false
            minWidth = 0
            minimumWidth = 0
            visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
            // 挂载前由 Compose 同步主题；先建立确定的尺寸和背景轮廓，不继承 OEM 按钮样式。
            AdmobNativeCardStyle.applyAction(this, Color.TRANSPARENT, Color.TRANSPARENT)
            root.callToActionView = this
        }
        /** 图标原始素材保持比例，不裁切、不绘制额外背景填补透明边缘。 */
        val icon = ImageView(context).apply {
            setImageDrawable(ad.icon?.drawable)
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = if (ad.icon == null) View.GONE else View.VISIBLE
            root.iconView = this
        }
        /** 标准选项仍由 SDK 放在根容器左下，不使用需要额外账号权限的自定义控件。 */
        val choices = AdmobAdChoicesSpace(context, ad.adChoicesInfo)
        /** 摘要版不创建媒体；媒体版尺寸由布局限定为 120dp，比例由 SDK 保留。 */
        val media = if (requiresMedia) MediaView(context).apply { imageScaleType = ImageView.ScaleType.FIT_CENTER } else null
        return AdmobNativeCardElements(icon, headline, identity(), body, action, AdmobDismissControl(context), media, choices)
    }

    /** 广告标记与广告主在标题下同排，广告主长文本在剩余宽度完整换行。 */
    private fun identity(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.TOP
        isBaselineAligned = false
        addView(label(context.getString(R.string.ad_admob_attribution), R.dimen.ad_admob_identity_text).apply {
            id = R.id.ad_admob_attribution
            /** 标记留足最小高度与横向内距，边线由主题样式统一绘制。 */
            val inset = resources.getDimensionPixelSize(R.dimen.ad_admob_badge_padding)
            setPadding(inset, 0, inset, 0)
            minHeight = resources.getDimensionPixelSize(R.dimen.ad_admob_badge_height)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        if (!ad.advertiser.isNullOrBlank()) {
            addView(label(ad.advertiser, R.dimen.ad_admob_identity_text).apply { root.advertiserView = this },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = resources.getDimensionPixelSize(R.dimen.ad_admob_gap_small)
                })
        }
    }

    /** 使用 sp 资源响应系统字体缩放，文字方向由内容决定，排布仍按卡片物理左右约定。 */
    private fun label(value: String?, sizeResource: Int): TextView = TextView(context).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(sizeResource))
        includeFontPadding = false
        textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        textAlignment = View.TEXT_ALIGNMENT_TEXT_START
    }
}
