package com.cla.clip.feature.ad.admob

import android.view.View

/** 同一卡片的原生素材引用，仅供测量编排；不持有请求、页面或可序列化数据。 */
internal data class AdmobNativeCardElements(
    /** 提供时展示的品牌图标，缺失时为 GONE。 */
    val icon: View,
    /** 完整标题，SDK 注册仍由素材工厂负责。 */
    val headline: View,
    /** 广告标记与可选广告主，展示层自行测量完整内容。 */
    val identity: View,
    /** 过滤后的完整短正文，缺失时为 GONE。 */
    val body: View,
    /** 包含向上透明热区的 CTA，点击监听归 SDK 所有。 */
    val action: View,
    /** 宿主关闭控件，不注册为 SDK 点击资产。 */
    val dismiss: View,
    /** 视频或未知素材需要媒体；摘要版为空。 */
    val media: View?,
    /** 标准 SDK 左下覆盖层的透明内容预算，可与摘要 CTA 同行；不绘制选项。 */
    val choices: View,
)
