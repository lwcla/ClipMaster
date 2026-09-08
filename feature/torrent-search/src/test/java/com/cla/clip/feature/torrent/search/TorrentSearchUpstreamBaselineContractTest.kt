package com.cla.clip.feature.torrent.search

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 固定上游供应链清单与资源隔离契约，防止来源、Provider 或宿主资源边界静默漂移。 */
class TorrentSearchUpstreamBaselineContractTest {
    /** 清单必须固定到已审查提交，并同时保留许可证、署名和关键文件校验值。 */
    @Test
    fun upstreamManifestPinsVerifiedSnapshotAndCriticalFiles() {
        /** 模块根目录承载清单、许可证和 NOTICE，不依赖构建产物路径。 */
        val moduleRoot = findModuleRoot()
        /** 清单文件必须先于后续运行时移植建立可审查来源。 */
        val manifestPath = moduleRoot.resolve("upstream-manifest.json")
        assertTrue("缺少 upstream-manifest.json", Files.isRegularFile(manifestPath))

        /** 独立 fixture 锁定已审查内容，禁止测试从待测 manifest 自举期望。 */
        val reviewedSnapshot = parseReviewedSnapshot(moduleRoot)
        /** manifest 原文摘要锁定全部 Provider 字段、关键文件和本地策略。 */
        val manifestText = readUtf8(manifestPath)
        assertReviewedManifestContent(manifestText, reviewedSnapshot)

        /** 动态 JSON 只用于测试固定 artifact，不引入生产序列化模型。 */
        val manifest = parseManifest(manifestPath)
        assertEquals("https://github.com/prajwalch/TorrentSearch", manifest.requiredString("repositoryUrl"))
        assertEquals("ba82e889637027f7bed82715ec4d2106cbce5a28", manifest.requiredString("commit"))
        assertEquals("MIT", manifest.requiredString("license"))
        assertTrue("缺少上游 LICENSE", Files.isRegularFile(moduleRoot.resolve("LICENSE")))
        assertTrue("缺少本地 NOTICE", Files.isRegularFile(moduleRoot.resolve("NOTICE.md")))
        /** LICENSE 摘要由独立 fixture 锁定，防止以同名但不同内容的许可证文件冒充来源。 */
        val licenseSha256 = sha256(Files.readAllBytes(moduleRoot.resolve("LICENSE")))
        assertEquals(reviewedSnapshot.requiredString("licenseSha256"), licenseSha256)

        /** 关键文件数组覆盖许可证、工具链、入口和 Provider 契约审计点。 */
        val criticalFiles = manifest.requiredArray("criticalFiles").map { element -> element.jsonObject }
        /** 手工固定的关键路径避免只记录容易通过的任意文件。 */
        val requiredPaths = setOf(
            "LICENSE",
            "gradle/libs.versions.toml",
            "app/build.gradle.kts",
            "app/src/main/kotlin/com/prajwalch/torrentsearch/TorrentSearchApplication.kt",
            "app/src/main/kotlin/com/prajwalch/torrentsearch/ui/main/MainActivity.kt",
            "app/src/main/kotlin/com/prajwalch/torrentsearch/providers/SearchProvider.kt",
            "app/src/main/kotlin/com/prajwalch/torrentsearch/di/BuiltinSearchProvidersModule.kt",
        )
        /** 清单中的路径集合用于发现遗漏或重复审计记录。 */
        val actualPaths = criticalFiles.map { file -> file.requiredString("path") }.toSet()
        assertTrue("关键文件记录不完整：${requiredPaths - actualPaths}", actualPaths.containsAll(requiredPaths))
        /** file 是逐项校验摘要格式的关键上游文件。 */
        criticalFiles.forEach { file ->
            /** 每个关键文件必须使用完整小写 SHA-256，避免短哈希或占位值。 */
            val sha256 = file.requiredString("sha256")
            assertTrue("关键文件 SHA-256 非法：${file.requiredString("path")}", SHA_256_REGEX.matches(sha256))
        }
    }

    /** 已审查资源清单必须逐文件锁定，防止前缀扫描漏掉翻译或 UI 基线的删除。 */
    @Test
    fun resourceInventoryMatchesReviewedSnapshot() {
        /** 模块目录用于定位真实 Android 资源，不读取构建中间产物。 */
        val moduleRoot = findModuleRoot()
        /** 独立 fixture 保存固定路径和内容摘要，不能由当前资源即时生成。 */
        val reviewedSnapshot = parseReviewedSnapshot(moduleRoot)
        /** 当前资源清单保留相对路径和逐文件 SHA，覆盖翻译、XML 与二进制资源。 */
        val actualInventory = resourceInventory(moduleRoot.resolve("src/main/res"))
        assertReviewedResourceInventory(actualInventory, reviewedSnapshot)
    }

    /** 受控变异必须触发失败，证明快照会拒绝错误域名、字段漂移和资源缺失。 */
    @Test
    fun reviewedSnapshotsRejectProviderAndResourceMutations() {
        /** 模块目录提供真实基线内容，变异只停留在内存中。 */
        val moduleRoot = findModuleRoot()
        /** 独立 fixture 是变异校验唯一的不可变期望来源。 */
        val reviewedSnapshot = parseReviewedSnapshot(moduleRoot)
        /** 当前 manifest 原文用于构造不会写回磁盘的错误 Provider 域名。 */
        val manifestText = readUtf8(moduleRoot.resolve("upstream-manifest.json"))
        /** 错误 host 模拟注释 URL 误入后续网络白名单的审查回归。 */
        val incorrectDomainManifest = manifestText.replaceFirst("\"1337x.to\"", "\"github.com\"")
        assertValidationFails("错误 Provider 域名必须被快照拒绝") {
            assertReviewedManifestContent(incorrectDomainManifest, reviewedSnapshot)
        }

        /** 错误显示名模拟 Provider 字段被格式校验无法发现的静默漂移。 */
        val incorrectProviderFieldManifest = manifestText.replaceFirst(
            "\"displayName\": \"AniLibria\"",
            "\"displayName\": \"Unexpected Provider\"",
        )
        assertValidationFails("错误 Provider 字段必须被快照拒绝") {
            assertReviewedManifestContent(incorrectProviderFieldManifest, reviewedSnapshot)
        }

        /** 资源清单仅移除一项，模拟机械导入漏掉一个翻译或 UI 文件。 */
        val incompleteInventory = resourceInventory(moduleRoot.resolve("src/main/res")).dropLast(1)
        assertValidationFails("缺失资源必须被完整 inventory 拒绝") {
            assertReviewedResourceInventory(incompleteInventory, reviewedSnapshot)
        }
    }

    /** 内置 Provider 清单必须恰好 43 项，并完整描述稳定协议、安全和网络边界。 */
    @Test
    fun upstreamManifestContainsCompleteProviderRegistry() {
        /** 固定清单由测试直接读取，后续 Provider 注册实现只能消费同一审计基线。 */
        val manifest = parseManifest(findModuleRoot().resolve("upstream-manifest.json"))
        /** Provider 数组是后续任务注册表和 fixture 的唯一固定来源。 */
        val providers = manifest.requiredArray("providers").map { element -> element.jsonObject }
        assertEquals("内置 Provider 数量必须固定为 43", 43, providers.size)

        /** 稳定 code 必须唯一且不依赖类名、枚举名或 ordinal。 */
        val providerCodes = providers.map { provider -> provider.requiredString("code") }
        assertEquals("Provider code 必须唯一", providerCodes.size, providerCodes.toSet().size)
        /** code 是等待格式校验的单个稳定 Provider 标识。 */
        providerCodes.forEach { code ->
            assertTrue("Provider code 格式非法：$code", PROVIDER_CODE_REGEX.matches(code))
        }

        /** 上游源路径必须一项一文件，确保 SHA 可以追溯到真实 Provider 实现。 */
        val sourcePaths = providers.map { provider -> provider.requiredString("sourcePath") }
        assertEquals("Provider 上游源路径必须唯一", sourcePaths.size, sourcePaths.toSet().size)
        /** provider 是等待完整元数据校验的单个清单对象。 */
        providers.forEach { provider ->
            /** 当前 Provider code 用于输出可行动的失败消息。 */
            val code = provider.requiredString("code")
            assertTrue("Provider 显示名缺失：$code", provider.requiredString("displayName").isNotBlank())
            assertTrue("Provider 源路径缺失：$code", provider.requiredString("sourcePath").endsWith(".kt"))
            assertTrue("Provider SHA-256 非法：$code", SHA_256_REGEX.matches(provider.requiredString("sha256")))

            /** 能力列表至少包含基础搜索，并只使用固定的小写 capability code。 */
            val capabilities = provider.requiredArray("capabilities").map { capability -> capability.jsonPrimitive.content }
            assertTrue("Provider 缺少 search 能力：$code", "search" in capabilities)
            assertTrue("Provider 能力 code 非法：$code", capabilities.all(CAPABILITY_CODES::contains))

            /** safetyLevel 必须区分安全、不安全和支持 NSFW 的来源。 */
            val safetyLevel = provider.requiredString("safetyLevel")
            assertTrue("Provider safetyLevel 非法：$code", safetyLevel in SAFETY_LEVELS)
            /** 域名白名单不能为空，后续网络包装只能在该集合中选择 HTTPS 端点。 */
            val domains = provider.requiredArray("domains").map { domain -> domain.jsonPrimitive.content }
            assertTrue("Provider 域名为空：$code", domains.isNotEmpty())
            assertTrue("Provider 域名格式非法：$code", domains.all(DOMAIN_REGEX::matches))

            /** 本地策略必须明确禁止 HTTP 与白名单外跳转，而非只依赖上游当前 URL。 */
            val localNetworkPolicy = provider.requiredObject("localNetworkPolicy")
            assertTrue("Provider 未启用 HTTPS-only：$code", localNetworkPolicy.requiredBoolean("httpsOnly"))
            assertFalse("Provider 仍允许明文 HTTP：$code", localNetworkPolicy.requiredBoolean("allowCleartext"))
            assertFalse("Provider 仍允许白名单外域名：$code", localNetworkPolicy.requiredBoolean("allowUnlistedDomains"))
        }

        /** 1337x 只允许可执行主站和详情下载选择器使用的 host，注释链接不得扩大白名单。 */
        val thirteenThirtySevenX = providers.single { provider -> provider.requiredString("code") == "1337x" }
        /** 域名顺序按 manifest 固定，方便后续网络策略和审查差异精确定位。 */
        val thirteenThirtySevenXDomains = thirteenThirtySevenX.requiredArray("domains")
            // domain 是 1337x 白名单中的单个主机名元素。
            .map { domain -> domain.jsonPrimitive.content }
        assertEquals(listOf("1337x.to", "itorrents.org"), thirteenThirtySevenXDomains)
    }

    /** 本地改造策略必须显式拒绝远程代码、选择器或脚本下发。 */
    @Test
    fun upstreamManifestForbidsRemoteExecutableConfiguration() {
        /** 本地改造对象记录首轮允许的兼容、安全和宿主接入边界。 */
        val localAdaptations = parseManifest(findModuleRoot().resolve("upstream-manifest.json"))
            .requiredObject("localAdaptations")
        assertFalse(localAdaptations.requiredBoolean("remoteCodeDeliveryAllowed"))
        assertFalse(localAdaptations.requiredBoolean("remoteSelectorDeliveryAllowed"))
        assertFalse(localAdaptations.requiredBoolean("remoteScriptDeliveryAllowed"))

        /** 完整 JSON 字符串用于兜底阻止伪装成 URL/endpoint 的远程执行配置字段。 */
        val manifestText = readUtf8(findModuleRoot().resolve("upstream-manifest.json"))
        assertFalse("清单不得包含远程代码/脚本下发 URL", REMOTE_EXECUTABLE_URL_KEY_REGEX.containsMatchIn(manifestText))
    }

    /** values 定义和 XML 内部非系统资源引用必须统一使用模块前缀。 */
    @Test
    fun logicalResourceIdentifiersUseTorrentSearchPrefix() {
        /** res 根目录同时覆盖默认资源、翻译、版本限定和文件型资源。 */
        val resDirectory = findModuleRoot().resolve("src/main/res")
        /** resourcePath 是等待扫描引用和定义前缀的单个 XML 文件。 */
        resourceXmlFiles(resDirectory).forEach { resourcePath ->
            /** 当前 XML 文本用于扫描所有本地类型引用，Android 系统资源会被正则排除。 */
            val xmlText = readUtf8(resourcePath)
            /** match 是当前非系统资源引用的正则匹配结果。 */
            RESOURCE_REFERENCE_REGEX.findAll(xmlText).forEach { match ->
                /** 捕获组只包含非 android 命名空间的资源 entry 名。 */
                val resourceName = match.groupValues[1]
                assertTrue("资源引用缺少前缀：$resourcePath -> $resourceName", resourceName.startsWith(RESOURCE_PREFIX))
            }

            if (resourcePath.parent.fileName.toString().startsWith("values")) {
                /** DOM 只读取 values 顶层资源定义的 name，不绑定 XML 排版。 */
                val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(resourcePath.toFile())
                /** resources 的直接子节点包含 string/plurals/color/style 等逻辑资源。 */
                val definitions = document.documentElement.childNodes
                // index 逐项访问 DOM NodeList，避免依赖实现不保证的集合转换行为。
                for (index in 0 until definitions.length) {
                    /** 非元素节点是缩进文本或注释，不参与资源 ID 契约。 */
                    val definition = definitions.item(index)
                    if (definition.nodeType != org.w3c.dom.Node.ELEMENT_NODE || !definition.hasAttributes()) continue
                    /** 顶层 name 属性是 Android 生成 R 符号的逻辑 ID。 */
                    val nameAttribute = definition.attributes.getNamedItem("name") ?: continue
                    assertTrue(
                        "资源定义缺少前缀：$resourcePath -> ${nameAttribute.nodeValue}",
                        nameAttribute.nodeValue.startsWith(RESOURCE_PREFIX),
                    )
                }
            }
        }
    }

    /** drawable、mipmap、xml 等文件型资源必须用模块前缀隔离宿主命名空间。 */
    @Test
    fun fileBasedResourcesUseTorrentSearchPrefix() {
        /** res 根目录下非 values 目录中的每个文件都会生成文件型资源 ID。 */
        val resDirectory = findModuleRoot().resolve("src/main/res")
        /** AGP 固定元数据文件不生成 R ID，必须保留其约定名称和默认语言语义。 */
        val metadataPath = resDirectory.resolve("resources.properties")
        assertTrue("缺少 AGP resources.properties 元数据", Files.isRegularFile(metadataPath))
        assertEquals("unqualifiedResLocale=en\n", normalizeTextLineEndings(readUtf8(metadataPath)))
        /** paths 是 res 树的受控流，use 结束时必须关闭文件系统资源。 */
        Files.walk(resDirectory).use { paths ->
            /** 只检查普通文件；values XML 的 ID 已由独立契约逐项检查。 */
            val fileResources = paths
                .filter(Files::isRegularFile)
                // path 是等待排除 values 目录的单个普通资源路径。
                .filter { path -> !path.parent.fileName.toString().startsWith("values") }
                // path 是等待排除 AGP 固定元数据的候选路径。
                .filter { path -> path != metadataPath }
                .toList()
            /** resourcePath 是等待前缀校验的单个文件型资源。 */
            fileResources.forEach { resourcePath ->
                assertTrue(
                    "文件型资源缺少前缀：$resourcePath",
                    resourcePath.fileName.toString().startsWith(RESOURCE_PREFIX),
                )
            }
        }
    }

    /** 默认英文和简体中文都必须保留任务 2 的入口、空壳和备份核心文案。 */
    @Test
    fun coreIntegrationStringsExistInEnglishAndSimplifiedChinese() {
        /** 默认 values 必须是英文基线，不能依赖 values-en 才获得英文。 */
        val defaultStrings = readNamedResourceValues(findModuleRoot().resolve("src/main/res/values/strings.xml"))
        /** 简体中文固定放在 Android 标准 zh-rCN 目录。 */
        val simplifiedChineseStrings = readNamedResourceValues(
            findModuleRoot().resolve("src/main/res/values-zh-rCN/strings.xml"),
        )
        /** 空壳已公开使用的核心资源必须在两种语言中完整保留。 */
        val requiredNames = setOf(
            "torrent_search_title",
            "torrent_search_entry_description",
            "torrent_search_placeholder_message",
            "torrent_search_backup_bookmarks",
            "torrent_search_backup_search_histories",
            "torrent_search_backup_viewed_items",
            "torrent_search_backup_settings",
        )
        assertTrue("默认英文缺少核心字符串：${requiredNames - defaultStrings.keys}", defaultStrings.keys.containsAll(requiredNames))
        assertTrue(
            "简体中文缺少核心字符串：${requiredNames - simplifiedChineseStrings.keys}",
            simplifiedChineseStrings.keys.containsAll(requiredNames),
        )
        assertEquals("Torrent Search", defaultStrings.getValue("torrent_search_title"))
        assertEquals("Torrent 搜索", simplifiedChineseStrings.getValue("torrent_search_title"))
    }

    /** 从 Gradle 可能采用的仓库根或模块根工作目录定位当前模块。 */
    private fun findModuleRoot(): Path {
        /** 测试工作目录由 Gradle 决定，因此同时兼容仓库根和模块根。 */
        val workingDirectory = Path.of("").toAbsolutePath().normalize()
        /** 直接命中用于 Gradle 将 user.dir 设为模块目录的情况。 */
        val directModuleDirectory = workingDirectory.takeIf { directory ->
            directory.fileName?.toString() == "torrent-search"
        }
        if (directModuleDirectory != null) return directModuleDirectory

        /** 仓库根命中是当前项目标准 Gradle Test 工作目录。 */
        val nestedModuleDirectory = workingDirectory.resolve("feature/torrent-search")
        require(Files.isDirectory(nestedModuleDirectory)) { "无法定位 feature/torrent-search：$workingDirectory" }
        return nestedModuleDirectory
    }

    /** 解析供应链清单并返回动态 JSON 对象，失败时保留文件路径上下文。 */
    private fun parseManifest(manifestPath: Path): JsonObject {
        /** 清单原文只包含公开上游元数据，不承载用户或运行时数据。 */
        val manifestText = readUtf8(manifestPath)
        return Json.parseToJsonElement(manifestText).jsonObject
    }

    /** 读取独立审查 fixture；该文件与待测 manifest 分离，专门固定供应链事实。 */
    private fun parseReviewedSnapshot(moduleRoot: Path): JsonObject {
        /** fixture 路径固定在测试资源目录，避免生产 artifact 反向成为期望来源。 */
        val snapshotPath = moduleRoot.resolve(REVIEWED_SNAPSHOT_RELATIVE_PATH)
        assertTrue("缺少独立供应链快照 fixture", Files.isRegularFile(snapshotPath))
        /** fixture 内容只含公开源路径、摘要和 Provider 稳定 code。 */
        val snapshotText = readUtf8(snapshotPath)
        return Json.parseToJsonElement(snapshotText).jsonObject
    }

    /** 精确核验 manifest 摘要、43 个 code/path 对和关键文件 SHA，拒绝合法格式的错误事实。 */
    private fun assertReviewedManifestContent(manifestText: String, reviewedSnapshot: JsonObject) {
        /** manifest 字节摘要一次性锁定所有展示名、能力、安全级、NSFW、域名和策略字段。 */
        val manifestSha256 = sha256(normalizeTextLineEndings(manifestText).toByteArray(Charsets.UTF_8))
        assertEquals(reviewedSnapshot.requiredString("manifestSha256"), manifestSha256)

        /** 待测对象仍单独解析 code/path，避免摘要误改时难以指出 Provider 注册边界。 */
        val manifest = Json.parseToJsonElement(manifestText).jsonObject
        /** 当前 Provider 对以稳定 code 和上游相对路径组合，顺序也属于审查结果。 */
        val actualProviderPairs = manifest.requiredArray("providers").map { provider ->
            /** 当前 manifest Provider 对象，只读取固定 code 与 sourcePath。 */
            val providerObject = provider.jsonObject
            providerObject.requiredString("code") + "|" + providerObject.requiredString("sourcePath")
        }
        /** fixture 明确列出 43 对 code/path，禁止仅按数量或格式自洽。 */
        val expectedProviderPairs = reviewedSnapshot.requiredArray("providerSourcePairs").map { provider ->
            /** 当前审查 fixture Provider 对象，作为独立固定期望。 */
            val providerObject = provider.jsonObject
            providerObject.requiredString("code") + "|" + providerObject.requiredString("sourcePath")
        }
        assertEquals(expectedProviderPairs, actualProviderPairs)

        /** 关键文件数组的 path/SHA 对独立锁定，避免替换为任意 64 位十六进制。 */
        val actualCriticalFiles = manifest.requiredArray("criticalFiles").map { file ->
            /** 当前 manifest 关键文件对象，只读取路径与摘要。 */
            val fileObject = file.jsonObject
            fileObject.requiredString("path") + "|" + fileObject.requiredString("sha256")
        }
        /** fixture 记录固定提交对应的七个审计文件和实际 SHA。 */
        val expectedCriticalFiles = reviewedSnapshot.requiredArray("criticalFiles").map { file ->
            /** 当前审查 fixture 关键文件对象，提供独立摘要期望。 */
            val fileObject = file.jsonObject
            fileObject.requiredString("path") + "|" + fileObject.requiredString("sha256")
        }
        assertEquals(expectedCriticalFiles, actualCriticalFiles)
    }

    /** 枚举资源相对路径与内容摘要；固定 AGP 元数据也纳入完整基线而不当作 R 资源。 */
    private fun resourceInventory(resDirectory: Path): List<String> =
        /** paths 是计算资源 inventory 时必须在 use 结束后关闭的文件流。 */
        Files.walk(resDirectory).use { paths ->
            paths
                .filter(Files::isRegularFile)
                // resourcePath 是等待归一化相对路径和摘要的单个资源文件。
                .map { resourcePath ->
                    /** Windows 分隔符归一为 /，确保 fixture 不随测试机变化。 */
                    val relativePath = resDirectory.relativize(resourcePath).toString().replace('\\', '/')
                    /** 原始字节保留二进制资源的逐字节完整性。 */
                    val resourceBytes = Files.readAllBytes(resourcePath)
                    /** 文本资源先统一换行，避免 Git 检出策略改变同一内容的摘要。 */
                    val canonicalBytes = if (
                        resourcePath.fileName.toString() == "resources.properties" ||
                        resourcePath.fileName.toString().endsWith(".xml")
                    ) {
                        normalizeTextLineEndings(resourceBytes.toString(Charsets.UTF_8)).toByteArray(Charsets.UTF_8)
                    } else {
                        resourceBytes
                    }
                    /** 每个资源的规范摘要保护翻译文本和二进制图标内容。 */
                    val contentSha256 = sha256(canonicalBytes)
                    relativePath + "|" + contentSha256
                }
                .sorted()
                .toList()
        }

    /** 将实际资源清单与独立 fixture 逐项比较，删除、替换或新增都会显式失败。 */
    private fun assertReviewedResourceInventory(actualInventory: List<String>, reviewedSnapshot: JsonObject) {
        /** fixture 总数先提供可行动的缺失/多余资源错误。 */
        val expectedCount = reviewedSnapshot.requiredString("resourceFileCount").toInt()
        assertEquals(expectedCount, actualInventory.size)
        /** fixture 中每项均为相对路径和审查时的字节 SHA。 */
        val expectedInventory = reviewedSnapshot.requiredArray("resourceFiles").map { resource ->
            /** 当前审查 fixture 资源对象，提供固定路径与摘要。 */
            val resourceObject = resource.jsonObject
            resourceObject.requiredString("path") + "|" + resourceObject.requiredString("sha256")
        }
        assertEquals(expectedInventory.sorted(), actualInventory)
    }

    /** 受控错误必须造成断言失败，避免测试出现“变异后仍全绿”的伪保护。 */
    private fun assertValidationFails(message: String, validation: () -> Unit) {
        /** 标记 validation 是否真的拒绝了变异，而不是被测试自身的 fail 误吞。 */
        var rejected = false
        try {
            validation()
        // 下划线参数表示预期的断言拒绝，不读取异常详情。
        } catch (_: AssertionError) {
            rejected = true
        }
        assertTrue(message, rejected)
    }

    /** 使用标准 SHA-256 计算字节摘要，作为固定快照的内容完整性锚点。 */
    private fun sha256(bytes: ByteArray): String {
        /** 摘要器不持久化状态，每次调用独立处理一份公开 artifact。 */
        val digest = MessageDigest.getInstance("SHA-256")
        /** 小写十六进制与 manifest/fixture 保持相同的稳定编码。 */
        val encodedDigest = digest.digest(bytes).joinToString(separator = "") { byte -> "%02x".format(byte) }
        return encodedDigest
    }

    /** 将 Git 可能检出的 CRLF 或裸 CR 统一为 LF，只消除跨平台文本换行差异。 */
    private fun normalizeTextLineEndings(
        /** 等待计算稳定摘要或进行精确断言的文本。 */
        text: String,
    ): String = text.replace("\r\n", "\n").replace('\r', '\n')

    /** 以 UTF-8 读取清单和 XML，并兼容 Android 单测使用的 Java API 基线。 */
    private fun readUtf8(path: Path): String {
        /** 资源与清单都固定为 UTF-8；显式字符集避免依赖测试机默认编码。 */
        val bytes = Files.readAllBytes(path)
        return bytes.toString(Charsets.UTF_8)
    }

    /** 枚举 res 中全部 XML，供 values 定义与 XML 引用契约共同复用。 */
    private fun resourceXmlFiles(resDirectory: Path): List<Path> =
        /** paths 是枚举 XML 时必须在 use 结束后关闭的文件流。 */
        Files.walk(resDirectory).use { paths ->
            paths
                .filter(Files::isRegularFile)
                // path 是等待 XML 后缀筛选的单个普通文件路径。
                .filter { path -> path.fileName.toString().endsWith(".xml") }
                .toList()
        }

    /** 读取 string 资源 name 到文本的映射，只保护任务 2 已公开的核心文案。 */
    private fun readNamedResourceValues(stringsPath: Path): Map<String, String> {
        /** DOM 解析避免通过源文本 grep 绑定排版或属性顺序。 */
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(stringsPath.toFile())
        /** string 节点列表包含当前语言目录的全部普通字符串。 */
        val stringNodes = document.getElementsByTagName("string")
        /** 结果映射保留资源名与最终文本，供核心文案精确断言。 */
        val values = linkedMapOf<String, String>()
        // index 逐项访问 DOM NodeList，并保持资源文件中的稳定遍历顺序。
        for (index in 0 until stringNodes.length) {
            /** 当前节点的 name 和 textContent 分别对应 R 名称与本地化文本。 */
            val stringNode = stringNodes.item(index)
            values[stringNode.attributes.getNamedItem("name").nodeValue] = stringNode.textContent
        }
        return values
    }

    /** 读取必填 JSON 字符串；缺失或类型错误直接让契约测试失败。 */
    private fun JsonObject.requiredString(key: String): String = getValue(key).jsonPrimitive.content

    /** 读取必填 JSON 布尔值；禁止用字符串形式模糊策略语义。 */
    private fun JsonObject.requiredBoolean(key: String): Boolean = getValue(key).jsonPrimitive.boolean

    /** 读取必填 JSON 数组；缺失或类型错误直接让契约测试失败。 */
    private fun JsonObject.requiredArray(key: String): JsonArray = getValue(key).jsonArray

    /** 读取必填 JSON 对象；用于保持网络和本地改造策略结构稳定。 */
    private fun JsonObject.requiredObject(key: String): JsonObject = getValue(key).jsonObject

    /** 上游基线 fixture 的稳定常量入口，避免测试方法内散落路径和协议值。 */
    private companion object {
        /** 模块资源前缀同时约束逻辑 ID、文件名和 XML 内部引用。 */
        const val RESOURCE_PREFIX = "torrent_search_"

        /** 独立 fixture 相对模块目录固定，避免依赖 classpath 或 Gradle 临时目录。 */
        const val REVIEWED_SNAPSHOT_RELATIVE_PATH =
            "src/test/resources/com/cla/clip/feature/torrent/search/torrent_search_upstream_baseline_snapshot.json"

        /** 完整 SHA-256 固定为 64 位小写十六进制。 */
        val SHA_256_REGEX = Regex("^[0-9a-f]{64}$")

        /** Provider code 只允许稳定小写字母、数字和单下划线分段。 */
        val PROVIDER_CODE_REGEX = Regex("^[a-z0-9]+(?:_[a-z0-9]+)*$")

        /** 域名只允许 ASCII 小写 host，不接受 scheme、路径、端口或通配符。 */
        val DOMAIN_REGEX = Regex("^[a-z0-9]+(?:[.-][a-z0-9]+)+$")

        /** XML 引用只扫描本地具体资源类型；android 命名空间不会匹配。 */
        val RESOURCE_REFERENCE_REGEX = Regex(
            "@(?:string|plurals|color|dimen|drawable|mipmap|xml|style|array|bool|integer|font|anim|animator|menu|raw)/([A-Za-z0-9_.]+)",
        )

        /** 首轮清单允许的 Provider 能力由真实接口实现推导，不能凭空扩展。 */
        val CAPABILITY_CODES = setOf("search", "details", "latest", "top", "cloudflare")

        /** 安全等级区分普通、上游显式 unsafe 以及支持 NSFW 分类三类。 */
        val SAFETY_LEVELS = setOf("safe", "unsafe", "nsfw")

        /** 拒绝 remote code/script/selector 的 URL 或 endpoint 字段，避免形成热更新入口。 */
        val REMOTE_EXECUTABLE_URL_KEY_REGEX = Regex(
            "\\\"remote(?:Code|Script|Selector)(?:Url|Endpoint)\\\"\\s*:",
            RegexOption.IGNORE_CASE,
        )
    }
}
