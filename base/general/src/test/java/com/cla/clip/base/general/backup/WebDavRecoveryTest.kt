package com.cla.clip.base.general.backup

import com.cla.clip.base.general.backup.settings.*
import com.cla.clip.base.general.config.datastore.createTestHostSettingsStore
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** 合成响应验证请求序列和真实 DataStore 许可；不连接远端，也不模拟核心事务。 */
class WebDavRecoveryTest {
    /** 目录修复后的补传超时，再获业务额度也不能重新领取目录许可。 */
    @Test fun networkRetryDoesNotResetDirectoryRecoveryPermit() = runBlocking {
        /** 合成持久化生命周期。 */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            /** 真实轮次和修复许可事务。 */
            val runtime = BackupRuntimeRepository(createTestHostSettingsStore(scope, File(folder.root, "network-retry.preferences_pb")))
            /** 固定包在两次业务调用之间不改变。 */
            val export = export()
            /** 记录跨重试请求序列，确认没有第二轮目录操作。 */
            val requests = CopyOnWriteArrayList<String>()
            runtime.replacePending(BackupPendingUpload("A", "hash", "auto", "uploading", 100L))
            try {
                scripted(requests, listOf(409, 404, 207, 404, 201, -1)).uploadBackup(config(), export, "A",
                    claimDirectoryRecovery = { runtime.claimWebDavDirectoryRecoveryIfCurrentRound("A") })
                fail("补传应超时")
            } catch (failure: BackupFailure.RemoteFailed) { assertEquals("webdav_request_timeout", failure.backupReasonCode()) }
            assertEquals(BackupWebDavRetryDecision.Queued(1), runtime.requestWebDavRetryIfCurrentRound("A"))
            /** 第二轮开始前的请求总数，后续只允许一个 PUT。 */
            val beforeRetry = requests.size
            try {
                scripted(requests, listOf(404)).uploadBackup(config(), export, "A",
                    claimDirectoryRecovery = { runtime.claimWebDavDirectoryRecoveryIfCurrentRound("A") })
                fail("不能再次修复目录")
            } catch (failure: BackupFailure.RemoteFailed) { assertEquals("webdav_http_404", failure.backupReasonCode()) }
            assertEquals(listOf("PUT /a/b/synthetic.zip"), requests.drop(beforeRetry))
        } finally { scope.coroutineContext[Job]?.cancelAndJoin() }
    }
    /** 父级在查询后被其他客户端创建时，连接测试仍须准备缺失子目录并执行实际可写探测。 */
    @Test fun connectionTestContinuesAfterParentWasCreatedElsewhere() = runBlocking {
        /** 方法和合成路径记录用于确认不会在父级 405 后直接上传到仍缺失的最终目录。 */
        val requests = CopyOnWriteArrayList<String>()
        scripted(requests, listOf(404, 404, 405, 404, 201, 201, 204)).testAndPrepare(config())
        assertEquals(listOf("PROPFIND /a/b/", "PROPFIND /a/", "MKCOL /a/",
            "PROPFIND /a/b/", "MKCOL /a/b/", "PUT /a/b/.clip_master_probe.json",
            "DELETE /a/b/.clip_master_probe.json"), requests)
    }

    /** 405 没有实际创建目录，不能因此补传；本轮失败仍保留首次 PUT 的 409。 */
    @Test fun mappedResourceDoesNotCountAsDirectoryCreation() = runBlocking {
        /** 两个分支复用相同非空包。 */
        val export = export()
        // statuses 覆盖父级已存在和本请求创建父级后子级 405，两者都没有完成修复。
        for (statuses in listOf(listOf(409, 404, 207, 404, 405), listOf(409, 404, 404, 201, 404, 405))) {
            /** 缺失子级未创建，不能被父级进展掩盖。 */
            val requests = CopyOnWriteArrayList<String>()
            try {
                scripted(requests, statuses).uploadBackup(config(), export)
                fail("目录未修复完成必须结束")
            } catch (failure: BackupFailure.RemoteFailed) { assertEquals("webdav_http_409", failure.backupReasonCode()) }
            assertEquals(1, requests.count { it.startsWith("PUT") })
            assertEquals("MKCOL /a/b/", requests.last())
        }
    }

    /** 许可不能落盘时直接传播故障，不能查询目录或创建、补传。 */
    @Test fun failedPermitPersistenceCannotProceedToDirectoryRequests() = runBlocking {
        /** 只允许首次 PUT 的合成服务。 */
        val requests = CopyOnWriteArrayList<String>()
        try {
            scripted(requests, listOf(409)).uploadBackup(config(), export(), "A", claimDirectoryRecovery = {
                throw IOException("synthetic_store_failure")
            })
            fail("许可保存失败必须中止")
        } catch (_: IOException) { }
        assertEquals(listOf("PUT /a/b/synthetic.zip"), requests)
    }
    /** 独占合成包和状态文件，避免真实用户数据。 */
    @get:Rule val folder = TemporaryFolder(File("build"))

    /** 404/409 任意组合只能修复一次，多级目录内的多个 404 不单独消耗许可。 */
    @Test fun eitherPutStatusRepairsParentsOnlyOnceAndKeepsCurrentFailure() = runBlocking {
        /** 所有合成组合复用同一只读包。 */
        val export = export()
        // first/second 是首次和补传失败状态，四种组合都必须有界结束。
        for (first in listOf(404, 409)) for (second in listOf(404, 409)) {
            /** 真实客户端产生的方法/路径序列，线程安全以兼容异步回调。 */
            val requests = CopyOnWriteArrayList<String>()
            /** 首次目录缺失，再依次创建父级与子级，补传再次失败。 */
            val client = scripted(requests, listOf(first, 404, 404, 201, 404, 201, second))
            /** 许可回调是持久化边界的替身，其副作用本身是次数契约。 */
            var claims = 0
            try {
                client.uploadBackup(config(), export, claimDirectoryRecovery = { claims++; true })
                fail("重复状态必须失败")
            } catch (failure: BackupFailure.RemoteFailed) {
                assertEquals("webdav_http_$second", failure.backupReasonCode())
            }
            assertEquals(1, claims)
            assertEquals(listOf("PUT /a/b/synthetic.zip", "PROPFIND /a/b/", "PROPFIND /a/",
                "MKCOL /a/", "PROPFIND /a/b/", "MKCOL /a/b/", "PUT /a/b/synthetic.zip"), requests)
        }
    }

    /** 实际创建完成后补传原包；成功不再请求摘要。 */
    @Test fun repairedParentsAllowOneSuccessfulZipPut() = runBlocking {
        /** 合成请求序列。 */
        val requests = CopyOnWriteArrayList<String>()
        scripted(requests, listOf(409, 404, 207, 404, 201, 201)).uploadBackup(config(), export())
        assertEquals(listOf("PUT /a/b/synthetic.zip", "PROPFIND /a/b/", "PROPFIND /a/",
            "PROPFIND /a/b/", "MKCOL /a/b/", "PUT /a/b/synthetic.zip"), requests)
    }

    /** 查询目录存在或查询失败都没有进展，禁止盲目 MKCOL 和补传。 */
    @Test fun existingDirectoryOrFailedQueryDoesNotRepeatPut() = runBlocking {
        /** 固定包不随错误改变。 */
        val export = export()
        // status 覆盖完整目录和查询失败两个结束边界。
        for (status in listOf(207, 500)) {
            /** 实际请求记录。 */
            val requests = CopyOnWriteArrayList<String>()
            try { scripted(requests, listOf(409, status)).uploadBackup(config(), export); fail("必须保留失败") }
            catch (_: BackupFailure.RemoteFailed) { }
            assertEquals(listOf("PUT /a/b/synthetic.zip", "PROPFIND /a/b/"), requests)
        }
    }

    /** 目录创建被拒绝不能继续上传，认证/权限原因优先。 */
    @Test fun deniedDirectoryCreationStopsBeforeSecondPut() = runBlocking {
        /** 合成请求序列。 */
        val requests = CopyOnWriteArrayList<String>()
        try { scripted(requests, listOf(409, 404, 404, 403)).uploadBackup(config(), export()); fail("应拒绝") }
        catch (failure: BackupFailure.AuthenticationFailed) { assertEquals("auth_failed", failure.backupReasonCode()) }
        assertEquals(1, requests.count { it.startsWith("PUT") })
        assertEquals("MKCOL /a/", requests.last())
    }

    /** 保存许可后被中断，即使新建客户端/仓库并恢复同任务也不能第二次查询。 */
    @Test fun interruptedClaimSurvivesNewClientAndRepository() = runBlocking {
        /** 真实持久化作用域。 */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            /** 独占合成存储与固定包。 */
            val store = createTestHostSettingsStore(scope, File(folder.root, "resume.preferences_pb"))
            /** 第一次真实许可事务。 */
            val runtime = BackupRuntimeRepository(store)
            /** 两次执行使用相同固定字节和文件名。 */
            val export = export()
            /** 跨客户端累计副作用，用于断言无第二次查询。 */
            val requests = CopyOnWriteArrayList<String>()
            runtime.replacePending(BackupPendingUpload("A", "hash", "auto", "uploading", 100L))
            try {
                scripted(requests, listOf(404)).uploadBackup(config(), export, "A", claimDirectoryRecovery = {
                    check(runtime.claimWebDavDirectoryRecoveryIfCurrentRound("A"))
                    throw CancellationException("synthetic_stop")
                })
                fail("取消必须传播")
            } catch (_: CancellationException) { }
            runtime.updateTargetPhase("A", BackupResultTarget.WebDav, "queued")
            try {
                scripted(requests, listOf(409)).uploadBackup(config(), export, "A", claimDirectoryRecovery = {
                    BackupRuntimeRepository(store).claimWebDavDirectoryRecoveryIfCurrentRound("A")
                })
                fail("已使用许可必须失败")
            } catch (failure: BackupFailure.RemoteFailed) { assertEquals("webdav_http_409", failure.backupReasonCode()) }
            assertEquals(listOf("PUT /a/b/synthetic.zip", "PUT /a/b/synthetic.zip"), requests)
        } finally { scope.cancel() }
    }

    /** 空包不得请求网络，也不能自动重新生成。 */
    @Test fun missingPackageStopsWithoutRequest() = runBlocking {
        /** 删除当前测试包，保留描述来复现系统恢复时的缺失。 */
        val export = export()
        export.packageFile.delete()
        /** 不应收到任何 HTTP 请求。 */
        val requests = CopyOnWriteArrayList<String>()
        try { scripted(requests, listOf(201)).uploadBackup(config(), export); fail("应中止") }
        catch (_: BackupFailure.TempFileUnavailable) { }
        assertTrue(requests.isEmpty())
    }

    /** 拦截合成服务响应，核心客户端和请求体保持真实；不会连接网络。 */
    private fun scripted(requests: MutableList<String>, statuses: List<Int>): WebDavClient {
        /** 每个响应只消费一次，超出脚本直接失败而不挂起。 */
        val index = AtomicInteger()
        return WebDavClient(OkHttpClient.Builder().addInterceptor { chain ->
            /** 只采集方法和合成路径，不采集认证头。 */
            val request = chain.request()
            requests += "${request.method} ${request.url.encodedPath}"
            /** 空合法目录用于 Depth 0，目录存在性只看状态。 */
            val body = "<d:multistatus xmlns:d=\"DAV:\"/>"
            /** -1 是仅测试使用的真实请求超时触发，其余值为 HTTP 状态。 */
            val status = statuses.getOrElse(index.getAndIncrement()) { 500 }
            if (status == -1) throw SocketTimeoutException("synthetic")
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(status).message("synthetic")
                .body(body.toResponseBody()).build()
        }.build())
    }

    /** 无凭据的合成两级目录。 */
    private fun config() = WebDavConfig("https://example.invalid", "", "", "/a/b/")

    /** 合成只读包只验证传输，ZIP 完整性继续由已有包协议测试覆盖。 */
    private fun export(): BackupExportResult {
        /** 非空固定字节与最小摘要。 */
        val file = folder.newFile("synthetic.zip").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        /** 传输测试只需要既有描述契约，完整 ZIP 由包协议套件覆盖。 */
        val manifest = BackupManifest(createdAt = 100L, applicationId = "synthetic", schemaVersion = 4,
            appVersionCode = 1, appVersionName = "test", deviceLabel = "synthetic", source = BackupSource.WebDavAuto,
            snapshotFileName = file.name, checksum = "synthetic", summary = BackupSummary())
        return BackupExportResult(file, manifest, BackupJson.encodeManifest(manifest), file.name, "${file.name}.manifest.json", folder.root)
    }
}
