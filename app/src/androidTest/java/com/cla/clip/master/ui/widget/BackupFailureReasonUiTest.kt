package com.cla.clip.master.ui.widget

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cla.clip.master.backup.BackupEntryState
import com.cla.clip.master.backup.BackupResultKind
import com.cla.clip.master.backup.BackupResultPresentation
import com.cla.clip.master.ui.page.mine.BackupEntry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import com.cla.clip.base.general.R as BaseR

/** 直接渲染两个页面使用的生产组件，只传合成状态，不访问用户数据或触发备份。 */
@RunWith(AndroidJUnit4::class)
class BackupFailureReasonUiTest {
    /** 使用独立 Compose 测试宿主，避免操作正式页面的设置与业务数据。 */
    @get:Rule val compose = createComposeRule()

    /** 我的页真实入口必须显示整段原因和处理指引。 */
    @Test fun mineEntryShowsDirectoryConflictReason() {
        compose.setContent {
            MaterialTheme {
                BackupEntry(state = conflictState(), onNavigate = { /* 本用例只读文案，不触发导航。 */ })
            }
        }
        assertConflictVisible()
    }

    /** 备份页共用摘要在窄宽度及大字体下仍显示完整原因，不退回笼统不可写。 */
    @Test fun backupSummaryShowsDirectoryConflictAtLargeFont() {
        compose.setContent {
            /** 保留实际像素密度，只放大字体来覆盖多行说明。 */
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.6f)) {
                MaterialTheme {
                    Box(Modifier.width(220.dp)) { BackupResultsSummary(conflictState()) }
                }
            }
        }
        assertConflictVisible()
    }

    /** 固定合成状态分别表达本地冲突和云端未配置，组件不得串用两目标原因。 */
    private fun conflictState() = BackupEntryState(
        local = BackupResultPresentation(BackupResultKind.Failed, reasonCode = "local_directory_conflict"),
        webDav = BackupResultPresentation(BackupResultKind.WebDavUnconfigured),
    )

    /** 读取生产资源并检查真实语义节点，避免复制文案或只验证枚举。 */
    private fun assertConflictVisible() {
        /** 使用被测应用资源，和即时错误提示保持同源。 */
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithText(context.getString(BaseR.string.base_general_backup_error_local_directory_conflict), substring = true)
            .assertIsDisplayed()
    }
}
