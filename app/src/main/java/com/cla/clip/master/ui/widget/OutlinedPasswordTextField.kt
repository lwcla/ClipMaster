package com.cla.clip.master.ui.widget

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.cla.clip.master.R

/**
 * 带框内尾部查看/隐藏按钮的通用单行密码输入框，只负责展示，不读取或保存凭据。
 *
 * 可见性仅保留在当前组合中，重新创建组件时恢复隐藏；不适用于需要跨页面共享显隐状态的流程。
 * @param value 调用方持有的密码原文，切换显隐不会改写该值。
 * @param onValueChange 用户编辑文本时通知调用方，由调用方决定存储方式。
 * @param modifier 调用方控制输入框尺寸和外部布局。
 * @param enabled 宿主配置读盘或提交期间禁用输入与显隐动作，避免草稿和已提交状态错位。
 * @param label 调用方提供领域标签，避免通用组件绑定 WebDAV 文案。
 */
@Composable
internal fun OutlinedPasswordTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** 宿主配置尚未加载或正在保存时禁止修改密码草稿。 */
    enabled: Boolean = true,
    label: @Composable () -> Unit,
) {
    // 显隐属于临时 UI 状态，不使用 rememberSaveable，避免重建页面后自动暴露明文。
    var passwordVisible by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        singleLine = true,
        label = label,
        // 两种显示状态均使用密码键盘，避免明文查看时切回普通文本联想输入。
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        visualTransformation = if (passwordVisible) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        trailingIcon = {
            IconButton(
                enabled = enabled,
                onClick = {
                    // 只切换绘制方式，不触发文本修改或凭据持久化回调。
                    passwordVisible = !passwordVisible
                },
            ) {
                Icon(
                    imageVector = if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = stringResource(
                        if (passwordVisible) R.string.host_hide_password else R.string.host_show_password,
                    ),
                )
            }
        },
    )
}
