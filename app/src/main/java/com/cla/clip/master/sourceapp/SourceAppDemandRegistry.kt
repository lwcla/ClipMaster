package com.cla.clip.master.sourceapp

/** 纯包名需求注册表，由 Store 在 Main 串行调用；不持有页面、协程或系统资源。 */
internal class SourceAppDemandRegistry {
    /** 各可见会话的包名需求；令牌不包含页面或 Context。 */
    private val sessions = mutableMapOf<Any, Set<String>>()
    /** 替换同一会话需求，空身份不发起系统读取。 */
    fun update(owner: Any, packages: Set<String>) {
        sessions[owner] = packages.filterTo(mutableSetOf()) { it.isNotBlank() }
    }
    /** 会话离开后的移除契约，重复释放应幂等。 */
    fun remove(owner: Any) {
        sessions.remove(owner)
    }
    /** 只返回当前有效需求，空包名不发起平台读取。 */
    fun packages(): Set<String> = sessions.values.flatten().toSet()
}
