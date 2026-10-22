/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.activity

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Lock
import me.rerere.rikkahub.data.service.AppLockGuard
import me.rerere.rikkahub.data.service.AppLockStore

class AppLockUnlockActivity : ComponentActivity() {

    companion object {
        const val EXTRA_TARGET_PACKAGE = "target_package"
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
        val targetPackage = intent.getStringExtra(EXTRA_TARGET_PACKAGE)
        if (targetPackage.isNullOrBlank()) {
            finish()
            return
        }
        setContent {
            me.rerere.rikkahub.ui.theme.RikkahubTheme {
                AppLockUnlockScreen(
                    targetPackage = targetPackage,
                    onUnlocked = {
                        AppLockGuard.grantGraceUnlock(targetPackage)
                        val launchIntent = packageManager.getLaunchIntentForPackage(targetPackage)
                        if (launchIntent != null) {
                            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            startActivity(launchIntent)
                        }
                        finish()
                    },
                    onCancel = { AppLockGuard.goHome(); finish() }
                )
            }
        }
    }

    override fun onBackPressed() {
        // B模式(require_pin=false)下:只退出拦截页,不调用 grantGraceUnlock,
        // 用户下次打开 App 仍会被拦截,只有 AI 调用 unlock_app 才真正解除。
        AppLockGuard.goHome()
        finish()
    }
}

private fun loadAppLabel(context: android.content.Context, packageName: String): String =
    try {
        val info: ApplicationInfo = context.packageManager.getApplicationInfo(packageName, 0)
        context.packageManager.getApplicationLabel(info).toString()
    } catch (_: Exception) {
        packageName
    }

private fun loadAppIcon(context: android.content.Context, packageName: String): Drawable? =
    try {
        context.packageManager.getApplicationIcon(packageName)
    } catch (_: Exception) {
        null
    }

@Composable
private fun AppLockUnlockScreen(
    targetPackage: String,
    onUnlocked: () -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val appLabel = remember(targetPackage) { loadAppLabel(context, targetPackage) }
    val appIcon = remember(targetPackage) { loadAppIcon(context, targetPackage) }
    val requirePin = remember(targetPackage) { AppLockStore.getRequirePin(context, targetPackage) }
    val lockMessage = remember(targetPackage) { AppLockStore.getLockMessage(context, targetPackage) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        if (!requirePin) {
            // B模式: 极简 UI, 只有图标(带锁徽章) + 留言, 无任何可点击的解锁操作
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                AppIconWithBadge(appIcon = appIcon, contentDescription = appLabel)
                Spacer(Modifier.height(20.dp))
                if (!lockMessage.isNullOrBlank()) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) {
                        Text(
                            text = lockMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        } else {
            // A模式: 完整 UI (图标+徽章 + 名称 + 留言 + PIN圆点 + 数字键盘 + 取消)
            PinUnlockContent(
                appLabel = appLabel,
                appIcon = appIcon,
                lockMessage = lockMessage,
                onUnlocked = onUnlocked,
                onCancel = onCancel,
            )
        }
    }
}

@Composable
private fun AppIconWithBadge(appIcon: Drawable?, contentDescription: String) {
    Box(modifier = Modifier.size(72.dp)) {
        if (appIcon != null) {
            androidx.compose.foundation.Image(
                bitmap = appIcon.toBitmap().asImageBitmap(),
                contentDescription = contentDescription,
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(18.dp)),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) { Text(text = "\uD83D\uDCF1", fontSize = 32.sp) }
        }
        // 锁徽章, 叠在右下角
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 4.dp, y = 4.dp)
                .size(28.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape)
                .border(3.dp, MaterialTheme.colorScheme.background, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = HugeIcons.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@Composable
private fun PinUnlockContent(
    appLabel: String,
    appIcon: Drawable?,
    lockMessage: String?,
    onUnlocked: () -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val shakeOffset = remember { Animatable(0f) }
    val hasPin = remember { AppLockStore.hasPin(context) }
    val pinLength = remember { AppLockStore.getPinLength(context) }

    fun submit(candidate: String) {
        if (!hasPin) {
            errorMessage = "还没有设置解锁密码,请先让橘瓣设置密码"
            return
        }
        if (AppLockStore.verifyPin(context, candidate)) {
            onUnlocked()
        } else {
            errorMessage = "密码错误"
            pin = ""
            scope.launch {
                shakeOffset.snapTo(0f)
                shakeOffset.animateTo(20f, tween(50))
                shakeOffset.animateTo(-20f, tween(50))
                shakeOffset.animateTo(20f, tween(50))
                shakeOffset.animateTo(0f, tween(50))
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // 区块1: App 图标 (带锁徽章) + App 名称
        AppIconWithBadge(appIcon = appIcon, contentDescription = appLabel)
        Spacer(Modifier.height(8.dp))
        Text(text = appLabel, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

        Spacer(Modifier.height(20.dp))

        // 区块2: 留言气泡 (仅保留气泡本身, 去掉外层标签)
        if (!lockMessage.isNullOrBlank()) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.padding(horizontal = 8.dp),
            ) {
                Text(
                    text = lockMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                    textAlign = TextAlign.Center,
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        // 区块3: PIN 圆点 + 错误提示
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.offset(x = shakeOffset.value.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                repeat(pinLength) { index ->
                    Box(modifier = Modifier
                        .size(14.dp)
                        .background(
                            color = if (index < pin.length) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant,
                            shape = CircleShape
                        )
                    )
                }
            }
            errorMessage?.let {
                Text(text = it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }

        Spacer(Modifier.height(32.dp))

        // 区块4: 数字键盘 + 取消按钮
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val rows = listOf(
                listOf("1", "2", "3"),
                listOf("4", "5", "6"),
                listOf("7", "8", "9"),
                listOf("", "0", "back")
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                rows.forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        row.forEach { key ->
                            Box(modifier = Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                                when {
                                    key == "back" -> {
                                        IconButton(onClick = {
                                            if (pin.isNotEmpty()) pin = pin.dropLast(1)
                                            errorMessage = null
                                        }) {
                                            Text(text = "\u232B", fontSize = 22.sp)
                                        }
                                    }
                                    key.isNotEmpty() -> {
                                        IconButton(onClick = {
                                            if (pin.length < pinLength) {
                                                pin += key
                                                errorMessage = null
                                                if (pin.length == pinLength) submit(pin)
                                            }
                                        }) {
                                            Text(text = key, fontSize = 24.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            IconButton(onClick = onCancel) { Text(text = "\u2715", fontSize = 20.sp) }
        }
    }
}