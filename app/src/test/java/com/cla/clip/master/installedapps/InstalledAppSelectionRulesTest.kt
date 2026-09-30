package com.cla.clip.master.installedapps

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 扫描前范围规则测试；确保隐藏系统应用、自身和空包名不会触发后续资源读取。 */
class InstalledAppSelectionRulesTest {

    @Test
    /** 默认范围包含非系统应用，排除系统应用，名称和勾选状态不参与范围判断。 */
    fun defaultScopeExcludesSystemApps() {
        assertTrue(shouldReadInstalledApp("com.example.user", "com.example.self", false, false))
        assertFalse(shouldReadInstalledApp("com.example.system", "com.example.self", true, false))
    }

    @Test
    /** 完整范围允许系统应用，但不能放行自身或无效包名。 */
    fun fullScopeStillExcludesSelfAndEmptyPackages() {
        assertTrue(shouldReadInstalledApp("com.example.system", "com.example.self", true, true))
        assertFalse(shouldReadInstalledApp(" com.example.self ", "com.example.self", false, true))
        assertFalse(shouldReadInstalledApp(" ", "com.example.self", false, true))
    }
}
