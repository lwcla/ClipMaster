package com.cla.clip.feature.ad.uniad

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** uni-ad SDK 制品测试，确认仓库内 AAR 文件和 SHA-256 与接入计划一致。 */
class UniAdSdkArtifactTest {
    /** 所有 v1 需要的 AAR 都应存在并匹配校验值。 */
    @Test
    fun requiredArtifactsExistAndMatchChecksums() {
        /** uni-ad AAR 固定目录；从仓库根目录解析，避免 JVM 测试工作目录差异导致误判。 */
        val artifactDir = repositoryRoot().resolve("third_party/uniad/UNI_AD_android_5.5.2.0606")

        requiredChecksums.forEach { (artifactName, expectedSha256) ->
            /** 当前待校验 AAR 文件；文件名必须显式存在。 */
            val artifactFile = artifactDir.resolve(artifactName)
            assertTrue("缺少 $artifactName", artifactFile.isFile)
            assertEquals(expectedSha256, artifactFile.sha256Hex())
        }
    }

    /** 计算文件 SHA-256；只用于测试本地制品完整性。 */
    private fun File.sha256Hex(): String {
        /** SHA-256 摘要器；用于确认 AAR 没被替换。 */
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            /** 分块读取缓冲区；避免一次性把 AAR 全部载入内存。 */
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                /** 本次读取的字节数；-1 表示文件读取完成。 */
                val read = input.read(buffer)
                if (read == -1) {
                    break
                }
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    /** 从当前 JVM 测试工作目录向上寻找仓库根目录。 */
    private fun repositoryRoot(): File {
        /** 当前 JVM 工作目录文本；缺失时从当前目录兜底。 */
        val currentUserDir = System.getProperty("user.dir") ?: "."
        /** 当前候选目录；Gradle 可能把工作目录设为根项目或子模块。 */
        var candidate: File? = File(currentUserDir).absoluteFile
        while (candidate != null) {
            /** settings 文件；存在时认为已到达仓库根。 */
            val settingsFile = candidate.resolve("settings.gradle.kts")
            if (settingsFile.isFile) {
                return candidate
            }
            candidate = candidate.parentFile
        }
        error("无法从当前测试目录定位仓库根目录")
    }

    private companion object {
        /** v1 章鱼 + 泛连信息流需要的 AAR 与固定 SHA-256。 */
        private val requiredChecksums = linkedMapOf(
            "uniad-native-release.aar" to "a7b2940e976c6618d652899046042d159aa5731d3f0de1d2f5c421dd961db6c5",
            "android-gif-drawable-1.2.29.aar" to "611e2699782ee0d56168b6546962f75a54bdff03136d9db94019a65c0924eddd",
            "uniad-zy-release.aar" to "e5a3a71baaac54b7be355a2b0ac6ffd8b05760845452b1824c123505f34ab5fc",
            "octopus_ad_sdk_2.5.10.5.aar" to "9a6979b4521dbff1698050ffc36ccf3d51eda9baec335644c867c6fcc546b9bc",
            "Funlink_2.8.8_76006310_release.aar" to "93265aed7be72da39bdf5e64324c451c6528264fccc2fbaf5bbb64ca656e314a",
            "Funlink_adapter_uniad_2.8.4_74659082_release.aar" to "0d22430179566ee62d82fde84dab2f7bdb800a9f4a224b503b5804567130c789",
        )
    }
}
