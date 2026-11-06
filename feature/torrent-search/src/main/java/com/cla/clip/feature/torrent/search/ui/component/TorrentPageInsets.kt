package com.cla.clip.feature.torrent.search.ui.component

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.mandatorySystemGestures
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.union
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.runtime.Composable

/** 保留 Scaffold 原系统栏契约，并避开底部强制手势区；某些设备导航条为零但手势指示区仍可见。 */
@Composable
internal fun torrentPageContentInsets(): WindowInsets = ScaffoldDefaults.contentWindowInsets
    .union(WindowInsets.mandatorySystemGestures.only(WindowInsetsSides.Bottom))
