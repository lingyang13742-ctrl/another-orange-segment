/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.components.ui.permission

import androidx.compose.runtime.Composable

/**
 * 权限管理器组件
 * 自动处理权限请求对话框的显示和隐藏
 *
 * 使用方式：
 * ```
 * val permissionState = rememberPermissionState(permissions)
 *
 * PermissionManager(permissionState = permissionState) {
 *     // 你的UI内容
 *     YourContent()
 * }
 * ```
 */
@Composable
fun PermissionManager(
    permissionState: PermissionState,
    content: @Composable () -> Unit = {},
) {
    // 显示权限请求说明对话框
    if (permissionState.showRationaleDialog && permissionState.currentRationalePermissions.isNotEmpty()) {
        PermissionRationaleDialog(
            permissions = permissionState.currentRationalePermissions,
            permanentlyDeniedPermissions = permissionState.permanentlyDeniedPermissions,
            onProceed = {
                permissionState.proceedFromRationale()
            },
            onCancel = {
                permissionState.cancelPermissionRequest()
            },
            onOpenSettings = {
                permissionState.openAppSettings()
                permissionState.cancelPermissionRequest()
            }
        )
    }

    // 主要内容
    content()
}
