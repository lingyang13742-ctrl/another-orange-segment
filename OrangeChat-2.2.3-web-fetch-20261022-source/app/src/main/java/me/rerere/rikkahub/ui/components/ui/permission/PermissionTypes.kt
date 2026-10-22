/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.components.ui.permission

import android.Manifest
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import me.rerere.rikkahub.R

/**
 * 权限信息数据类
 * @param permission Android权限字符串 (如 android.permission.CAMERA)
 * @param usage 权限使用说明的Composable内容
 * @param required 是否为必需权限
 */
data class PermissionInfo(
    val permission: String,
    val displayName: @Composable () -> Unit,
    val usage: @Composable () -> Unit,
    val required: Boolean = false
)

/**
 * 权限状态枚举
 */
enum class PermissionStatus {
    /** 未请求 */
    NotRequested,
    /** 已授权 */
    Granted,
    /** 被拒绝但可以再次请求 */
    Denied,
    /** 被拒绝且用户选择"不再询问" */
    DeniedPermanently
}

/**
 * 权限请求结果
 */
data class PermissionResult(
    val permission: String,
    val status: PermissionStatus,
    val isGranted: Boolean = status == PermissionStatus.Granted
)

/**
 * 多个权限的请求结果
 */
data class MultiplePermissionResult(
    val results: Map<String, PermissionResult>,
    val allGranted: Boolean = results.values.all { it.isGranted },
    val allRequiredGranted: Boolean
)

val PermissionCamera = PermissionInfo(
    permission = Manifest.permission.CAMERA,
    displayName = { Text(stringResource(R.string.permission_camera)) },
    usage = { Text(stringResource(R.string.permission_camera_desc)) },
    required = true
)

val PermissionRecordAudio = PermissionInfo(
    permission = Manifest.permission.RECORD_AUDIO,
    displayName = { Text(stringResource(R.string.permission_microphone)) },
    usage = { Text(stringResource(R.string.permission_microphone_desc)) },
    required = true
)

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
val PermissionNotification = PermissionInfo(
    permission = Manifest.permission.POST_NOTIFICATIONS,
    displayName = { Text(stringResource(R.string.permission_notification)) },
    usage = { Text(stringResource(R.string.permission_notification_desc)) },
    required = true
)

@RequiresApi(37)
val PermissionLocalNetwork = PermissionInfo(
    permission = Manifest.permission.ACCESS_LOCAL_NETWORK,
    displayName = { Text(stringResource(R.string.permission_local_network)) },
    usage = { Text(stringResource(R.string.permission_local_network_desc)) },
    required = true
)

val PermissionAccessFineLocation = PermissionInfo(
    permission = Manifest.permission.ACCESS_FINE_LOCATION,
    displayName = { Text("位置权限") },
    usage = { Text("用于获取您的精确位置信息") },
    required = true
)

val PermissionAccessCoarseLocation = PermissionInfo(
    permission = Manifest.permission.ACCESS_COARSE_LOCATION,
    displayName = { Text("粗略位置权限") },
    usage = { Text("用于获取您的粗略位置信息") },
    required = false
)

val PermissionAccessBackgroundLocation = PermissionInfo(
    permission = Manifest.permission.ACCESS_BACKGROUND_LOCATION,
    displayName = { Text("后台位置权限") },
    usage = { Text("允许应用在后台获取位置信息，用于定时同步位置数据到Supabase") },
    required = false
)

val PermissionPostNotifications = PermissionInfo(
    permission = Manifest.permission.POST_NOTIFICATIONS,
    displayName = { Text("通知权限") },
    usage = { Text("用于发送通知提醒") },
    required = false
)

val PermissionReadSms = PermissionInfo(
    permission = Manifest.permission.READ_SMS,
    displayName = { Text("短信读取权限") },
    usage = { Text("用于读取设备短信收件箱内容") },
    required = true
)

val PermissionReadCalendar = PermissionInfo(
    permission = Manifest.permission.READ_CALENDAR,
    displayName = { Text("日历读取权限") },
    usage = { Text("用于读取设备日历事件") },
    required = true
)

val PermissionWriteCalendar = PermissionInfo(
    permission = Manifest.permission.WRITE_CALENDAR,
    displayName = { Text("日历写入权限") },
    usage = { Text("用于创建和删除日历事件") },
    required = false
)


val PermissionReadPhoneState = PermissionInfo(
    permission = Manifest.permission.READ_PHONE_STATE,
    displayName = { Text("电话状态读取权限") },
    usage = { Text("用于读取SIM卡和运营商信息") },
    required = true
)
