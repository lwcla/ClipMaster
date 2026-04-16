package com.cla.clip.master.processor

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.ViewModelLifecycle
import dagger.hilt.android.components.ViewModelComponent
import dagger.hilt.android.scopes.ViewModelScoped
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import javax.inject.Qualifier

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class VmScope

@Module
@InstallIn(ViewModelComponent::class)
object VmScopeModule {

    /** 提供一个与 ViewModel 生命周期绑定的 CoroutineScope，方便在 ViewModel 中进行协程操作，无需担心内存泄漏问题。 */
    @Provides
    @ViewModelScoped
    @VmScope
    fun provideVmScope(
        lifecycle: ViewModelLifecycle
    ): CoroutineScope {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        lifecycle.addOnClearedListener { scope.cancel() }
        return scope
    }
}



