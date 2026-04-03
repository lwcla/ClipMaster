package com.cla.clip.base.general.utils

import com.cla.clip.base.general.logE
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Singleton

annotation class ApplicationScope

val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
    logE("CoroutineExceptionHandler", tr = throwable) { "Coroutine exception" }
}

@Module
@InstallIn(SingletonComponent::class)
object ScopeModule {

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope {
        return CoroutineScope(SupervisorJob() + Dispatchers.Default + exceptionHandler)
    }
}

val clipDataFlow = MutableStateFlow<Triple<String, String?, ByteArray?>?>(null)