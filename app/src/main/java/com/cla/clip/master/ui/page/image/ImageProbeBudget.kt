package com.cla.clip.master.ui.page.image

import kotlinx.coroutines.delay

/** 页面拥有的扫描预算；离开探测态或后台时由调用协程取消，避免扫描无限增长页面。 */
internal suspend fun runImageProbeBudget(
    hasCandidates: () -> Boolean,
    onLongRunning: () -> Unit,
    onFinish: () -> Unit,
    wait: suspend (Long) -> Unit = { delay(it) },
) {
    wait(10_000L)
    if (!hasCandidates()) {
        onFinish()
        return
    }
    wait(50_000L)
    onLongRunning()
    wait(60_000L)
    onFinish()
}
