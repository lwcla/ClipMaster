package com.cla.clip.master.ui.page.backup

import androidx.lifecycle.ViewModel
import com.cla.clip.base.general.utils.logD
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** 父导航图的轻量共享 VM，只持有通信会话，不启动备份、数据库观察或扫描。 */
@HiltViewModel
internal class BackupFlowVm @Inject constructor() : ViewModel() {
    /** 同一父图 entry 下三个目的地共用的唯一会话，不通过 Hilt 重复构造。 */
    val session = BackupFlowSession()

    init {
        logD("BackupFlowVm") { "导航组会话已创建 sessionId=${session.sessionId}" }
    }

    /** 导航退出前撤销写权限并领取一次弹栈许可；重复回调不再允许导航。 */
    fun close(reasonCode: String): Boolean {
        logD("BackupFlowVm") { "请求关闭导航组 sessionId=${session.sessionId} reasonCode=$reasonCode" }
        return session.close()
    }

    /** Navigation 释放父 entry 后清理会话，幂等处理已主动关闭的组。 */
    override fun onCleared() {
        session.close()
        logD("BackupFlowVm") { "导航组会话已释放 sessionId=${session.sessionId}" }
        super.onCleared()
    }
}
