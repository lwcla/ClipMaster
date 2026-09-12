package com.cla.clip.feature.torrent.search.download

import com.cla.clip.feature.torrent.search.provider.builtin.testing.FakeTorrentSearchNetworkClient
import java.security.MessageDigest
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** Torrent 下载准备阶段的验证、身份与文件名测试。 */
class TorrentDownloadCoordinatorTest {
    /** 合法 torrent 必须验证身份后才返回可交给 SAF 的字节。 */
    @Test
    fun validTorrentBecomesReadyForSaf() = runTest {
        /** bytes 是含唯一 info 字典的最小规范 torrent。 */
        val bytes = "d4:infod4:name1:xee".toByteArray()
        /** expectedHash 只对 info 字典原始编码计算。 */
        val expectedHash = sha1("d4:name1:xe".toByteArray())
        /** networkClient 返回固定二进制响应。 */
        val networkClient = FakeTorrentSearchNetworkClient(bytesHandler = { _, _ -> bytes })
        /** coordinator 使用测试调度器完成下载准备。 */
        val coordinator = TorrentDownloadCoordinator(networkClient, StandardTestDispatcher(testScheduler))

        /** result 是已经过完整校验的 SAF 候选。 */
        val result = coordinator.prepare("http://public.example/file.torrent", expectedHash, "unsafe:/name")

        require(result is TorrentDownloadPreparation.Ready)
        assertArrayEquals(bytes, result.bytes)
        assertEquals("unsafe__name.torrent", result.suggestedFileName)
        assertEquals(expectedHash, result.infoHash)
    }

    /** 下载内容身份与当前详情不一致时不得形成 SAF 候选。 */
    @Test
    fun identityMismatchIsRejected() = runTest {
        /** bytes 是结构合法但身份与页面不同的 torrent。 */
        val bytes = "d4:infod4:name1:xee".toByteArray()
        /** networkClient 返回固定二进制响应。 */
        val networkClient = FakeTorrentSearchNetworkClient(bytesHandler = { _, _ -> bytes })
        /** coordinator 使用当前测试虚拟时间。 */
        val coordinator = TorrentDownloadCoordinator(networkClient, StandardTestDispatcher(testScheduler))

        assertEquals(
            TorrentDownloadPreparation.Failure(TorrentDownloadFailureReason.IDENTITY_MISMATCH),
            coordinator.prepare("https://public.example/file.torrent", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "name"),
        )
    }

    /** 计算固定测试 info 字典的 SHA-1 身份。 */
    private fun sha1(
        /** info 字典原始字节。 */
        bytes: ByteArray,
    ): String = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { /** byte 是待格式化字节。 */ byte ->
        "%02x".format(byte.toInt() and 0xff)
    }
}
