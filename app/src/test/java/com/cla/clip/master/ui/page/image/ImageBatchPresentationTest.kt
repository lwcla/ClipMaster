package com.cla.clip.master.ui.page.image

import com.cla.clip.base.general.dao.ImageExtractBatchData
import com.cla.clip.base.general.dao.ImageExtractItemData
import org.junit.Assert.*
import org.junit.Test

/** 下载到临时文件不等于已保存；所有期待值来自合成任务的业务含义。 */
class ImageBatchPresentationTest {
    /** 查询失败不能混同记录被删除，否则用户无法选择重试读取。 */
    @Test fun readFailureIsActionable() {
        assertEquals(ImageBatchPhase.ReadError, presentImageBatch(ImageBatchSnapshot(null, emptyList(), readFailed = true)).phase)
    }
    /** 临时文件齐备后必须显示发布，失败/过滤也计入已处理但不冒充成功。 */
    @Test fun tempFilesEnterPublishingBeforeFinalResult() {
        // 两张待发布、一张已发布，说明网络阶段结束但发布尚未完成。
        val items = listOf(item("temp_ready"), item("temp_ready"), item("success"))
        val result = presentImageBatch(ImageBatchSnapshot(batch(), items))
        assertEquals(ImageBatchPhase.Publishing, result.phase)
        assertEquals(1, result.processed)
        assertEquals(3, result.total)
    }

    /** 等待读取、记录缺失和排队必须给出不同状态。 */
    @Test fun missingBatchDoesNotRemainLoading() {
        assertEquals(ImageBatchPhase.Loading, presentImageBatch(null).phase)
        assertEquals(ImageBatchPhase.Missing, presentImageBatch(ImageBatchSnapshot(null, emptyList())).phase)
        assertEquals(ImageBatchPhase.Queued, presentImageBatch(ImageBatchSnapshot(batch("extracted"), emptyList())).phase)
    }

    /** 尚有 pending 时仍是下载，最后一张网络完成才进入发布。 */
    @Test fun pendingItemsKeepDownloadStage() {
        // 成功下载、被过滤和未处理各一张，网络阶段已处理二张。
        val result = presentImageBatch(ImageBatchSnapshot(batch(), listOf(item("pending"), item("temp_ready"), item("filtered"))))
        assertEquals(ImageBatchPhase.Downloading, result.phase)
        assertEquals(2, result.processed)
        assertEquals(3, result.total)
    }

    /** 全失败与部分成功均为终态，保留 item 列表由调用方继续展示。 */
    @Test fun terminalBatchesKeepResultPhase() {
        assertEquals(ImageBatchPhase.Result, presentImageBatch(ImageBatchSnapshot(batch("failed"), listOf(item("failed")))).phase)
        assertEquals(ImageBatchPhase.Result, presentImageBatch(ImageBatchSnapshot(batch("partial_success"), listOf(item("success")))).phase)
    }

    /** 只构造所需状态，时间固定且没有用户地址。 */
    private fun batch(status: String = "downloading") = ImageExtractBatchData(pageUrl = "https://example.test", pageName = "测试", status = status, totalCount = 3, createTime = 0, updateTime = 0)
    /** 单项状态合成输入；无文件 IO 或 Android 依赖。 */
    private fun item(status: String) = ImageExtractItemData(batchId = 1, url = "https://example.test/a.png", displayOrder = 0, status = status)
}
