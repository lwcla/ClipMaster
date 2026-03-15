package com.cla.clip.master.utils

import com.cla.clip.base.general.dao.ClipDao
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ContentProviderEntryPoint {
    fun clipDao(): ClipDao
}