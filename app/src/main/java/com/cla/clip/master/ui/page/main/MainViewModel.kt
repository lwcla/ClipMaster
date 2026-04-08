package com.cla.clip.master.ui.page.main

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import androidx.paging.map
import com.cla.clip.base.general.entity.toUi
import com.cla.clip.master.ClipBaseVm
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * 主屏幕的ViewModel。
 * 使用 @HiltViewModel 注解，Hilt会自动处理它的创建和依赖注入。
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    @param:ApplicationContext override val appContext: Context
) : ClipBaseVm(appContext) {

    val pagedClips = Pager(
        config = PagingConfig(
            pageSize = 20,       // 每页加载数量
            prefetchDistance = 5, // 距离底部多少个item时开始预加载
            enablePlaceholders = false
        )
    ) {
        clipDao.get().loadAllClips()
    }.flow.map { it.map { data -> data.toUi() } }.cachedIn(
        CoroutineScope(viewModelScope.coroutineContext + Dispatchers.IO)
    )
}