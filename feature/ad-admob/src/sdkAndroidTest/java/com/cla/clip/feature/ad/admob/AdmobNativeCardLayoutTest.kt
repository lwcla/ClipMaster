package com.cla.clip.feature.ad.admob

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.InsetDrawable
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.libraries.ads.mobile.sdk.common.Image
import com.google.android.libraries.ads.mobile.sdk.common.AdChoicesInfo
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAd
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdView
import java.io.File
import java.lang.reflect.Proxy
import java.util.Locale
import java.util.concurrent.FutureTask
import kotlin.math.roundToInt
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 用生产素材构造及布局测量保护几何边界；合成广告不注册、不请求、不曝光。 */
@RunWith(AndroidJUnit4::class)
class AdmobNativeCardLayoutTest {
    /** 普通摘要保留完整信息列，按钮的可见底边就是热区底边。 */
    @Test fun compactSummaryAlignsCopyAndKeepsIndependentControls() = withCard(false) {
        assertEquals(dp(56), elements.icon.width)
        assertEquals(elements.headline.left, elements.identity.left)
        assertEquals(elements.headline.left, elements.body.left)
        assertTrue(elements.body.top >= elements.identity.bottom)
        assertEquals(dp(48), elements.dismiss.width)
        assertEquals(dp(48), elements.dismiss.height)
        assertEquals(elements.action.right, elements.dismiss.right)
        assertEquals(dp(48), elements.action.height)
        // 绘制后读取自有背景的显色区域，确认没有系统按钮底部内距。
        snapshot("summary-light.png")
        // 按钮背景实际绘制的边界，用于检查热区与显色区的区别。
        val face = (elements.action.background as InsetDrawable).drawable!!.bounds
        assertEquals(dp(36), face.height())
        assertEquals(elements.action.height, face.bottom)
        assertNoOverlaps()
    }

    /** 正常视频版的右侧正文与媒体上边对齐，CTA 与媒体下边对齐。 */
    @Test fun mediaKeepsTopCopyBottomActionAndFixedSize() = withCard(true) {
        // 记录生产媒体 View，校验文字没有拉伸媒体。
        val media = checkNotNull(elements.media)
        assertEquals(dp(120), media.width)
        assertEquals(dp(120), media.height)
        assertEquals(media.top, elements.body.top)
        assertEquals(media.bottom, elements.action.bottom)
        snapshot("media-light.png")
        assertNoOverlaps()
    }

    /** 改动若重新挤压文字或缩小媒体，会被全部屏宽与字体组合捕获。 */
    @Test fun longCopyFitsNarrowScreensAndLargeFonts() {
        // 屏宽、字体与素材类型分别覆盖最容易发生压缩和重叠的组合。
        for (screenWidth in listOf(320, 360, 411)) {
            for (scale in listOf(1f, 1.3f, 2f)) {
                for (media in listOf(false, true)) {
                    withCard(media, screenWidth - 26, scale) {
                        (elements.headline as TextView).text = "从城市出发，发现属于你的周末旅行与山野时光"
                        (elements.action as TextView).text = "了解适合我的周末旅行方案"
                        measure(screenWidth - 26)
                        assertNoOverlaps()
                        elements.media?.let {
                            assertEquals(dp(120), it.width)
                            assertEquals(dp(120), it.height)
                        }
                    }
                }
            }
        }
    }

    /** 长 CTA 高度超限时纵排，恢复短文案后同一素材树重新横排。 */
    @Test fun tallActionFallsBackWithoutStretchingMediaAndRecovers() = withCard(true) {
        // 保留同一媒体与按钮引用，验证恢复横排时没有重建素材。
        val media = checkNotNull(elements.media)
        val action = elements.action as TextView
        action.text = "完整操作说明".repeat(12)
        measure()
        assertTrue(elements.body.top >= media.bottom)
        assertEquals(dp(120), media.height)
        assertNoOverlaps()
        action.text = "了解详情"
        measure()
        assertSame(media, elements.media)
        assertEquals(media.top, elements.body.top)
        assertEquals(media.bottom, action.bottom)
    }

    /** 图标及正文缺失都释放实际空间，不继承上一次测量的空白。 */
    @Test fun absentAssetsReleaseColumnsAndRows() = withCard(false) {
        // 用完整短正文产生自然换行，确保正文实际撑高了头部；短单行可能仍被图标高度覆盖。
        (elements.body as TextView).text = "发现附近的风景，出发正当时。".repeat(2)
        measure()
        // 初始高度和文字列位置作为缺失资产回收空间的参照。
        val initialHeight = card.height
        val initialTextLeft = elements.headline.left
        elements.body.visibility = View.GONE
        measure()
        assertTrue(card.height < initialHeight)
        elements.icon.visibility = View.GONE
        measure()
        assertEquals(dp(64), initialTextLeft - elements.headline.left)
        assertNoOverlaps()
    }

    /** 标准角落选项应利用 CTA 左侧空闲区域，不能再无条件增加整行高度。 */
    @Test fun standardAdChoicesSharesSummaryFooterWithoutExtraHeight() = withCard(false) {
        assertNull("不得登记受限的自定义选项 View", root.adChoicesView)
        assertTrue("选项应与 CTA 共享底部操作区", elements.choices.top < elements.action.bottom)
        assertEquals(0, elements.choices.left)
        assertEquals(card.height, elements.choices.bottom)
        assertTrue(elements.choices.right < elements.action.left)
        assertFalse(elements.choices.isClickable)
        assertNoOverlaps()
        // 同行已利用按钮旁空白，无选项时不应再改变整卡高度。
        val withChoicesHeight = card.height
        elements.choices.visibility = View.GONE
        measure()
        assertEquals(withChoicesHeight, card.height)
        assertNoOverlaps()
    }

    /** 选项过宽时下移，宽度恢复后重新同行，防止挤窄 CTA 或遗留空行。 */
    @Test fun wideChoicesStackBelowActionAndRecover() = withCard(false) {
        // 保存普通素材宽度及卡高，对比动态窗口恢复后的结果。
        val originalWidth = elements.choices.minimumWidth
        val compactHeight = card.height
        elements.choices.minimumWidth = dp(280)
        measure()
        assertTrue(elements.choices.top >= elements.action.bottom)
        assertTrue(card.height > compactHeight)
        assertNoOverlaps()
        elements.choices.minimumWidth = originalWidth
        measure()
        assertEquals(compactHeight, card.height)
        assertTrue(elements.choices.right < elements.action.left)
    }

    /** 图片按 SDK 像素/dp 比例计算，不能把高密度图片误当作等量 dp 留白。 */
    @Test fun choicesImageScaleDeterminesReservedSpace() {
        // 两组素材逻辑宽高相同，只改变图片像素密度；留位必须保持一致。
        var firstWidth = 0
        var firstHeight = 0
        withCard(false, choicesInfo = imageChoices(36, 18, 1.0)) {
            firstWidth = elements.choices.minimumWidth
            firstHeight = elements.choices.minimumHeight
            assertTrue(firstWidth >= dp(36))
            assertTrue(firstHeight >= dp(18))
        }
        withCard(false, choicesInfo = imageChoices(72, 36, 2.0)) {
            assertEquals(firstWidth, elements.choices.minimumWidth)
            assertEquals(firstHeight, elements.choices.minimumHeight)
            assertNoOverlaps()
        }
    }

    /** BitmapDrawable 的目标密度不应再次改变 SDK 已给出的图片逻辑尺寸。 */
    @Test fun bitmapChoicesIgnoreDrawableTargetDensity() {
        // 72×36 源像素配合 scale=2，内容应始终为 36×18dp；外缘各留 2dp。
        val bitmap = Bitmap.createBitmap(72, 36, Bitmap.Config.ARGB_8888).apply { density = 320 }
        try {
            // 两种 drawable density 会改变 intrinsic，但不能改变广告图片的公开 scale 契约。
            for (targetDensity in listOf(160, 480)) {
                val drawable = BitmapDrawable(null, bitmap).apply { setTargetDensity(targetDensity) }
                val info = AdChoicesInfo("", listOf(Image(drawable, Uri.EMPTY, 2.0)))
                withCard(false, choicesInfo = info) {
                    // 内容和每侧资源边距分别取整，兼容 2.75 等非整数屏幕密度。
                    val density = context.resources.displayMetrics.density
                    val inset = context.resources.getDimensionPixelSize(R.dimen.ad_admob_choices_padding)
                    assertEquals(kotlin.math.ceil(36 * density).toInt() + inset * 2, elements.choices.minimumWidth)
                    assertEquals(kotlin.math.ceil(18 * density).toInt() + inset * 2, elements.choices.minimumHeight)
                    assertNoOverlaps()
                }
            }
        } finally {
            bitmap.recycle()
        }
    }

    /** 视频底部随选项素材变化，但媒体、正文和 CTA 的相对边线保持不变。 */
    @Test fun mediaFooterTracksChoicesContentWithoutMovingAlignment() = withCard(true) {
        // 内容区底边用于排除“压缩视频或上移 CTA”造成的假紧凑。
        val media = checkNotNull(elements.media)
        val mediaBottom = media.bottom
        assertTrue("普通选项不应继续占据固定 32dp 底行", elements.choices.height < dp(32))
        assertTrue(elements.choices.top >= media.bottom)
        elements.choices.minimumHeight += dp(12)
        measure()
        assertEquals(mediaBottom, media.bottom)
        assertEquals(media.top, elements.body.top)
        assertEquals(media.bottom, elements.action.bottom)
        assertEquals(card.height, elements.choices.bottom)
        assertNoOverlaps()
    }

    /** 元数据缺失时无留位；不完整图片和非法比例使用有限兜底，不破坏布局。 */
    @Test fun absentAndIncompleteChoicesKeepFiniteLayout() {
        withCard(false, choicesInfo = null) {
            assertEquals(View.GONE, elements.choices.visibility)
            assertNoOverlaps()
        }
        withCard(false, choicesInfo = AdChoicesInfo("", emptyList())) {
            assertEquals(View.GONE, elements.choices.visibility)
            assertNoOverlaps()
        }
        withCard(false, choicesInfo = AdChoicesInfo("", listOf(Image(null, Uri.EMPTY, Double.NaN)))) {
            assertEquals(View.VISIBLE, elements.choices.visibility)
            assertTrue(elements.choices.width > 0)
            assertTrue(elements.choices.height > 0)
            assertTrue(elements.choices.right < elements.action.left)
            assertNoOverlaps()
        }
    }

    /** 构造具有明确像素尺寸的合成图片，不读取网络或真实广告。 */
    private fun imageChoices(width: Int, height: Int, scale: Double): AdChoicesInfo =
        AdChoicesInfo("", listOf(Image(object : ColorDrawable(Color.GRAY) {
            /** 模拟素材原始像素宽度，供生产缩放换算读取。 */
            override fun getIntrinsicWidth(): Int = width
            /** 模拟素材原始像素高度，供生产缩放换算读取。 */
            override fun getIntrinsicHeight(): Int = height
        }, Uri.EMPTY, scale)))

    /** 关闭回绑读取新回调；解绑后关闭不再触发，CTA 监听不受影响。 */
    @Test fun dismissBindingIsIndependentAndClearedOnDetach() = withCard(false) {
        // 三个独立计数器区分旧关闭、新关闭和 CTA，防止事件串流。
        var oldDismisses = 0
        var newDismisses = 0
        var actionClicks = 0
        elements.action.setOnClickListener { actionClicks++ }
        AdmobDismissControl.update(root, true) { oldDismisses++ }
        assertTrue(elements.dismiss.performClick())
        assertEquals(1, oldDismisses)
        assertEquals(0, actionClicks)
        AdmobDismissControl.update(root, false)
        assertFalse(elements.dismiss.performClick())
        assertFalse(elements.dismiss.hasOnClickListeners())
        AdmobDismissControl.update(root, true) { newDismisses++ }
        elements.dismiss.performClick()
        elements.action.performClick()
        assertEquals(1, oldDismisses)
        assertEquals(1, newDismisses)
        assertEquals(1, actionClicks)
    }

    /** 深色只更新现有 View，RTL 内容保持独立关闭区且不会改变物理左右结构。 */
    @Test fun themeAndRtlPreserveGeometryAndAssetIdentity() {
        // 两种卡片使用相同主题更新入口，保持资产引用与测量高度。
        for (media in listOf(false, true)) withCard(media) {
            // 主题更新前的资产身份与高度，用于检测意外重建和样式位移。
            val headline = root.headlineView
            val height = card.height
            AdmobNativeCardStyle.applyColors(root, 0xff101b21.toInt(), 0xffdce7ec.toInt(), 0xffa6bbc5.toInt(),
                0xff80d3f5.toInt(), 0xff07384b.toInt(), 0xff354750.toInt())
            measure()
            assertSame(headline, root.headlineView)
            assertEquals(height, card.height)
            snapshot(if (media) "media-dark.png" else "summary-dark.png")
            root.layoutDirection = View.LAYOUT_DIRECTION_RTL
            (elements.headline as TextView).text = "عرض تجريبي لرحلة نهاية الأسبوع"
            measure()
            assertTrue(elements.dismiss.left >= elements.headline.right)
            assertNoOverlaps()
        }
    }

    /** 每个样例创建独立 Context 与生产素材树，结束后释放 SDK 容器。 */
    private fun withCard(media: Boolean, width: Int = 334, scale: Float = 1f,
        choicesInfo: AdChoicesInfo? = AdChoicesInfo("AdChoices", emptyList()), block: Fixture.() -> Unit) {
        // View 工作完整交给主线程；FutureTask 把断言异常送回测试线程而非杀死应用。
        val task = FutureTask<Unit> { createCardOnMain(media, width, scale, choicesInfo, block) }
        InstrumentationRegistry.getInstrumentation().runOnMainSync(task)
        task.get()
    }

    /** 在主线程构造真实控件并执行断言，finally 确保失败时也释放原生容器。 */
    private fun createCardOnMain(media: Boolean, width: Int, scale: Float, choicesInfo: AdChoicesInfo?, block: Fixture.() -> Unit) {
        // 独立配置上下文只影响测试夹具，不更改设备语言或字体设置。
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val configuration = Configuration(target.resources.configuration).apply { fontScale = scale; setLocale(Locale.SIMPLIFIED_CHINESE) }
        val context = target.createConfigurationContext(configuration)
        // 真实 SDK 容器承接素材注册引用，测试不调用 registerNativeAd。
        val root = NativeAdView(context)
        // 只模拟 SDK 数据接口；控件创建、字体、样式和布局均调用生产实现。
        val ad = Proxy.newProxyInstance(NativeAd::class.java.classLoader, arrayOf(NativeAd::class.java)) { proxy, method, _ ->
            when (method.name) {
                "getHeadline" -> "把周末，交给山野"
                "getBody" -> "发现附近的风景，出发正当时。"
                "getCallToAction" -> "了解详情"
                "getAdvertiser" -> "山野旅行"
                "getIcon" -> Image(ColorDrawable(0xff2b6f62.toInt()), Uri.EMPTY, 1.0)
                "getAdChoicesInfo" -> choicesInfo
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "SyntheticNativeAd"
                else -> null
            }
        } as NativeAd
        // 素材构造与布局直接复用生产实现，避免另写一套理想化样例。
        val elements = AdmobNativeAssets(context, ad, root).create(media)
        val card = AdmobNativeCardLayout(context, elements)
        root.addView(card, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // 复用生产外壳，验证它与 SDK 根容器不会截断卡片自然高度。
        val container = AdmobNativeContainer(context).apply {
            addView(root)
        }
        AdmobNativeCardStyle.applyColors(root, Color.WHITE, 0xff192b34.toInt(), 0xff566c77.toInt(),
            0xff00658b.toInt(), Color.WHITE, 0xffc4d3d9.toInt())
        try {
            Fixture(context, root, elements, card, container).apply { measure(width); block() }
        } finally {
            root.destroy()
        }
    }

    /** 测试专用夹具，不进入生产源码或外部序列化协议。 */
    private class Fixture(
        /** 字体缩放已隔离的测试上下文。 */
        val context: Context,
        /** 真 SDK 容器，只登记资产，不注册广告或发出请求。 */
        val root: NativeAdView,
        /** 与生产卡片相同的素材引用。 */
        val elements: AdmobNativeCardElements,
        /** 本次直接测量的生产布局。 */
        val card: AdmobNativeCardLayout,
        /** 与 AndroidView 挂载相同的输入门控外壳。 */
        val container: AdmobNativeContainer,
    ) {
        /** 将测试规格换算为当前设备实际像素。 */
        fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).roundToInt()

        /** 重复测量同一树，模拟窗口变化而不重建广告资产。 */
        fun measure(width: Int = 334) {
            container.measure(View.MeasureSpec.makeMeasureSpec(dp(width), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            container.layout(0, 0, container.measuredWidth, container.measuredHeight)
            assertEquals(card.height, root.height)
            assertEquals(root.height, container.height)
        }

        /** 成对验证所有可见资产的矩形，防止透明点击区域覆盖其他素材。 */
        fun assertNoOverlaps() {
            // 只比较实际参与本轮绘制的资产，不把 GONE 的旧边界算作重叠。
            val children = listOfNotNull(elements.icon, elements.headline, elements.identity, elements.body,
                elements.action, elements.dismiss, elements.media, elements.choices).filter { it.visibility != View.GONE && it.width > 0 && it.height > 0 }
            // 当前资产与它之后的资产逐对比较，避免重复检查同一对边界。
            children.forEachIndexed { index, child ->
                // 当前资产的可点击边界必须完整落在卡片内部。
                val bounds = Rect(child.left, child.top, child.right, child.bottom)
                assertTrue("素材超出卡片：$bounds", bounds.left >= 0 && bounds.top >= 0 && bounds.right <= card.width && bounds.bottom <= card.height)
                children.drop(index + 1).forEach { other ->
                    // 另一资产的独立矩形不能被当前热区覆盖。
                    val otherBounds = Rect(other.left, other.top, other.right, other.bottom)
                    assertFalse("素材热区重叠：$bounds / $otherBounds", Rect.intersects(bounds, otherBounds))
                }
            }
        }

        /** 输出合成素材的原生 View 绘制结果，优先写入测试框架可回收的产物目录。 */
        fun snapshot(name: String) {
            // 离屏画布仅绘制合成卡片，不包含用户页面或剪贴数据。
            val bitmap = Bitmap.createBitmap(card.width, card.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            root.background?.apply { setBounds(0, 0, card.width, card.height); draw(canvas) }
            card.draw(canvas)
            // AGP 提供额外产物目录时交由它拉回；手动运行则留在测试应用自己的目录。
            val outputPath = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            val directory = File(outputPath?.let(::File)
                ?: InstrumentationRegistry.getInstrumentation().context.getExternalFilesDir(null), "ad-ui").apply { mkdirs() }
            File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            // 与原图一同导出像素边界，预览标注直接消费实测结果，不另外猜测比例。
            val geometry = JSONObject().apply {
                put("density", context.resources.displayMetrics.density)
                put("card", boundsOf(card))
                put("choices", boundsOf(elements.choices))
                put("media", elements.media?.let(::boundsOf))
            }
            File(directory, name.removeSuffix(".png") + ".json").writeText(geometry.toString())
            bitmap.recycle()
        }

        /** 只导出合成控件的几何值，不记录素材文字、URL 或用户信息。 */
        private fun boundsOf(view: View): JSONObject = JSONObject().apply {
            put("left", view.left)
            put("top", view.top)
            put("width", view.width)
            put("height", view.height)
        }
    }
}
