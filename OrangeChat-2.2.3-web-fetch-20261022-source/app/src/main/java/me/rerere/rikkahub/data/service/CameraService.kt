/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.display.DisplayManager
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Display
import android.view.Surface
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 后台相机服务
 * 允许AI在后台直接拍照，不需要打开相机预览界面
 */
class CameraService(private val context: Context) {
    
    private val cameraManager: CameraManager = 
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    
    /**
     * 相机信息
     */
    data class CameraInfo(
        val cameraId: String,
        val facing: Int, // CameraCharacteristics.LENS_FACING_FRONT 或 BACK
        val resolution: Size,
        val isFlashAvailable: Boolean
    )
    
    /**
     * 拍照结果
     */
    data class CaptureResult(
        val success: Boolean,
        val imageData: ByteArray? = null,
        val bitmap: Bitmap? = null,
        val error: String? = null,
        val timestamp: Long = System.currentTimeMillis()
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is CaptureResult) return false
            return success == other.success && 
                   error == other.error && 
                   timestamp == other.timestamp
        }
        
        override fun hashCode(): Int {
            var result = success.hashCode()
            result = 31 * result + (error?.hashCode() ?: 0)
            result = 31 * result + timestamp.hashCode()
            return result
        }
    }
    
    /**
     * 获取可用的相机列表
     */
    fun getAvailableCameras(): List<CameraInfo> {
        val cameras = mutableListOf<CameraInfo>()
        
        try {
            val cameraIds = cameraManager.cameraIdList
            
            for (cameraId in cameraIds) {
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING) 
                    ?: CameraCharacteristics.LENS_FACING_BACK
                
                val streamConfigMap = characteristics.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
                )
                
                if (streamConfigMap != null) {
                    val sizes = streamConfigMap.getOutputSizes(ImageFormat.JPEG)
                    val maxSize = sizes.maxByOrNull { it.width * it.height } ?: Size(1920, 1080)
                    
                    val flashAvailable = characteristics.get(
                        CameraCharacteristics.FLASH_INFO_AVAILABLE
                    ) ?: false
                    
                    cameras.add(CameraInfo(
                        cameraId = cameraId,
                        facing = facing,
                        resolution = maxSize,
                        isFlashAvailable = flashAvailable
                    ))
                }
            }
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
        
        return cameras
    }
    
    /**
     * 获取后置相机ID
     */
    fun getBackCameraId(): String? {
        return getAvailableCameras()
            .firstOrNull { it.facing == CameraCharacteristics.LENS_FACING_BACK }
            ?.cameraId
    }
    
    /**
     * 获取前置相机ID
     */
    fun getFrontCameraId(): String? {
        return getAvailableCameras()
            .firstOrNull { it.facing == CameraCharacteristics.LENS_FACING_FRONT }
            ?.cameraId
    }
    
    /**
     * 后台拍照
     * 
     * @param useFrontCamera 是否使用前置摄像头
     * @param enableFlash 是否开启闪光灯
     * @return 拍照结果
     */
    suspend fun capturePhoto(
        useFrontCamera: Boolean = false,
        enableFlash: Boolean = false
    ): CaptureResult = suspendCancellableCoroutine { continuation ->
        var cameraDevice: CameraDevice? = null
        var captureSession: CameraCaptureSession? = null
        var imageReader: ImageReader? = null
        var backgroundThread: HandlerThread? = null
        var backgroundHandler: Handler? = null
        
        try {
            // 选择相机
            val cameraId = if (useFrontCamera) {
                getFrontCameraId() ?: getBackCameraId()
            } else {
                getBackCameraId() ?: getFrontCameraId()
            }
            
            if (cameraId == null) {
                continuation.resume(CaptureResult(
                    success = false,
                    error = "No camera available"
                ))
                return@suspendCancellableCoroutine
            }
            
            // 获取相机信息
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val streamConfigMap = characteristics.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
            )
            
            if (streamConfigMap == null) {
                continuation.resume(CaptureResult(
                    success = false,
                    error = "Camera configuration not available"
                ))
                return@suspendCancellableCoroutine
            }
            
            // 选择最大分辨率
            val sizes = streamConfigMap.getOutputSizes(ImageFormat.JPEG)
            val maxSize = sizes.maxByOrNull { it.width * it.height }
                ?: Size(1920, 1080)
            
            // 启动后台线程
            backgroundThread = HandlerThread("CameraBackground").apply { start() }
            backgroundHandler = Handler(backgroundThread.looper)
            
            // 创建ImageReader
            imageReader = ImageReader.newInstance(
                maxSize.width,
                maxSize.height,
                ImageFormat.JPEG,
                1
            ).apply {
                setOnImageAvailableListener({ reader ->
                    try {
                        val image = reader.acquireLatestImage()
                        if (image != null) {
                            val buffer = image.planes[0].buffer
                            val bytes = ByteArray(buffer.remaining())
                            buffer.get(bytes)
                            image.close()
                            
                            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            
                            // 清理资源
                            captureSession?.close()
                            cameraDevice?.close()
                            backgroundThread?.quitSafely()
                            
                            continuation.resume(CaptureResult(
                                success = true,
                                imageData = bytes,
                                bitmap = bitmap
                            ))
                        }
                    } catch (e: Exception) {
                        continuation.resume(CaptureResult(
                            success = false,
                            error = "Failed to process image: ${e.message}"
                        ))
                    }
                }, backgroundHandler)
            }
            
            // 打开相机
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    
                    try {
                        // 创建捕获会话
                        val surfaces = listOf(imageReader!!.surface)
                        
                        camera.createCaptureSession(
                            surfaces,
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: CameraCaptureSession) {
                                    captureSession = session
                                    
                                    try {
                                        // 创建捕获请求
                                        val captureRequestBuilder = camera.createCaptureRequest(
                                            CameraDevice.TEMPLATE_STILL_CAPTURE
                                        ).apply {
                                            addTarget(imageReader!!.surface)
                                            
                                            // 设置闪光灯
                                            if (enableFlash) {
                                                set(
                                                    CaptureRequest.FLASH_MODE,
                                                    CaptureRequest.FLASH_MODE_SINGLE
                                                )
                                            }
                                            
                                            // 设置自动对焦
                                            set(
                                                CaptureRequest.CONTROL_AF_MODE,
                                                CaptureRequest.CONTROL_AF_MODE_AUTO
                                            )
                                            
                                            // 设置自动曝光
                                            set(
                                                CaptureRequest.CONTROL_AE_MODE,
                                                CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH
                                            )
                                            
                                            // 设置方向 - 使用Display.rotation计算正确的JPEG方向
                                            set(
                                                CaptureRequest.JPEG_ORIENTATION,
                                                getJpegOrientation(characteristics, useFrontCamera)
                                            )
                                        }
                                        
                                        // 拍照
                                        session.capture(
                                            captureRequestBuilder.build(),
                                            null,
                                            backgroundHandler
                                        )
                                    } catch (e: CameraAccessException) {
                                        continuation.resume(CaptureResult(
                                            success = false,
                                            error = "Failed to capture: ${e.message}"
                                        ))
                                    }
                                }
                                
                                override fun onConfigureFailed(session: CameraCaptureSession) {
                                    continuation.resume(CaptureResult(
                                        success = false,
                                        error = "Camera session configuration failed"
                                    ))
                                }
                            },
                            backgroundHandler
                        )
                    } catch (e: CameraAccessException) {
                        continuation.resume(CaptureResult(
                            success = false,
                            error = "Failed to create capture session: ${e.message}"
                        ))
                    }
                }
                
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                }
                
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                    continuation.resume(CaptureResult(
                        success = false,
                        error = "Camera error: $error"
                    ))
                }
            }, backgroundHandler)
            
        } catch (e: CameraAccessException) {
            continuation.resume(CaptureResult(
                success = false,
                error = "Camera access error: ${e.message}"
            ))
        } catch (e: SecurityException) {
            continuation.resume(CaptureResult(
                success = false,
                error = "Camera permission not granted"
            ))
        }
        
        // 取消时清理资源
        continuation.invokeOnCancellation {
            captureSession?.close()
            cameraDevice?.close()
            backgroundThread?.quitSafely()
        }
    }
    
    /**
     * 获取JPEG方向
     * 使用Display.rotation获取设备实际旋转角度，结合摄像头传感器方向和镜头朝向计算正确的JPEG方向
     *
     * @param characteristics 摄像头特性
     * @param isFrontCamera 是否为前置摄像头（前置需要镜像处理）
     * @return JPEG方向角度（0/90/180/270）
     */
    private fun getJpegOrientation(
        characteristics: CameraCharacteristics,
        isFrontCamera: Boolean
    ): Int {
        val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)
            ?: return 0

        // 获取设备显示的旋转角度
        // 使用 DisplayManager 获取旋转角度，不要求可视 Context（Activity/WindowContext），
        // 适合后台服务场景。context.display 和 windowManager.defaultDisplay 在后台会抛
        // UnsupportedOperationException: Tried to obtain display from a Context not associated with one.
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val rotation = displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0

        // 将Display rotation转换为角度
        val degrees = when (rotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }

        // 根据摄像头朝向计算JPEG方向
        // 后置摄像头: jpegOrientation = (sensorOrientation - degrees + 360) % 360
        // 前置摄像头(镜像): jpegOrientation = (sensorOrientation + degrees) % 360
        return if (isFrontCamera) {
            (sensorOrientation + degrees) % 360
        } else {
            (sensorOrientation - degrees + 360) % 360
        }
    }
    
    /**
     * 检查是否有相机权限
     */
    fun hasCameraPermission(): Boolean {
        return context.checkSelfPermission(android.Manifest.permission.CAMERA) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
    }
}
