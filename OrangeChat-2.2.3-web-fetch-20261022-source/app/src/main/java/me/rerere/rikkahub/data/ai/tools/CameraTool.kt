/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import android.graphics.BitmapFactory
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.service.CameraService
import me.rerere.rikkahub.utils.ImageUtils
import java.io.File

fun createCameraTool(context: Context): Tool {
    val cameraService = CameraService(context)

    return Tool(
        name = "camera_capture",
        description = "Take a photo with the device camera and return the image for visual analysis. The AI can then describe what it sees, identify objects, scenes, people, text, and more. Use this to understand the visual environment around the user.",
        needsApproval = true,
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("flash") {
                        put("type", "boolean")
                        put("description", "Whether to use flash (default false)")
                    }
                    putJsonObject("front_camera") {
                        put("type", "boolean")
                        put("description", "Whether to use front camera (default false)")
                    }
                }
            )
        },
        execute = { args ->
            val params = args.jsonObject
            try {
                val useFlash = params["flash"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
                val useFrontCamera = params["front_camera"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false

                // Take photo using CameraService (suspend function)
                val result = runBlocking {
                    cameraService.capturePhoto(
                        useFrontCamera = useFrontCamera,
                        enableFlash = useFlash
                    )
                }

                if (!result.success || result.imageData == null) {
                    return@Tool listOf(UIMessagePart.Text(
                        buildJsonObject {
                            put("success", false)
                            put("error", result.error ?: "Failed to capture photo. Camera may be in use or permission not granted.")
                        }.toString()
                    ))
                }

                // Save original high-res photo to file (preserved for user)
                val cameraDir = File(context.filesDir, "camera_captures").apply { mkdirs() }
                val originalFile = File(cameraDir, "capture_${System.currentTimeMillis()}_original.jpg")
                originalFile.outputStream().use { output ->
                    output.write(result.imageData)
                }

                // Compress image for AI - limit to 2048px max dimension, JPEG quality 85
                // This prevents sending oversized images to vision APIs (which can cause 400 errors)
                val compressedForAI = try {
                    val originalBitmap = BitmapFactory.decodeByteArray(result.imageData, 0, result.imageData.size)
                    if (originalBitmap != null) {
                        val compressed = ImageUtils.compressBitmapForAI(originalBitmap, maxSize = 2048, quality = 85)
                        // Save compressed version as a separate file for AI
                        val compressedFile = File(cameraDir, "capture_${System.currentTimeMillis()}_ai.jpg")
                        compressedFile.outputStream().use { output ->
                            output.write(compressed)
                        }
                        if (!originalBitmap.isRecycled) {
                            originalBitmap.recycle()
                        }
                        "file://${compressedFile.absolutePath}"
                    } else {
                        // If bitmap decode fails, fall back to original
                        "file://${originalFile.absolutePath}"
                    }
                } catch (e: Exception) {
                    // If compression fails, fall back to original file
                    "file://${originalFile.absolutePath}"
                }

                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("success", true)
                            put("message", "Photo captured successfully. The image is attached for visual analysis.")
                        }.toString()
                    ),
                    UIMessagePart.Image(
                        url = compressedForAI
                    )
                )
            } catch (e: Exception) {
                listOf(UIMessagePart.Text(
                    buildJsonObject {
                        put("success", false)
                        put("error", "Camera capture failed: ${e.message}")
                    }.toString()
                ))
            }
        }
    )
}
