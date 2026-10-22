/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.Image
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

@Composable
fun QRCode(
    value: String,
    modifier: Modifier = Modifier,
    size: Int = 512,
    color: Color = Color.Unspecified,
    backgroundColor: Color = Color.Unspecified
) {
    val actualColor = color.takeOrElse { MaterialTheme.colorScheme.secondary }
    val actualBackgroundColor = backgroundColor.takeOrElse { Color.Transparent }

    val qrCodeWriter = remember { QRCodeWriter() }
    val bitMatrix = remember(value) {
        qrCodeWriter.encode(value, BarcodeFormat.QR_CODE, size, size)
    }
    val bitmap = remember(bitMatrix) {
        createBitmap(size, size).apply {
            for (x in 0 until size) {
                for (y in 0 until size) {
                    this[x, y] = if (bitMatrix[x, y]) actualColor.toArgb() else actualBackgroundColor.toArgb()
                }
            }
        }
    }
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = "qrcode:$value",
        modifier = modifier
    )
}
