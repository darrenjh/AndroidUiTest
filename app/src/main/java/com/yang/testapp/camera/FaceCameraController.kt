package com.yang.testapp.camera

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.Face
import android.hardware.camera2.params.StreamConfigurationMap
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import androidx.core.app.ActivityCompat
import com.yang.testapp.view.FaceCollectFrameView
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Collections
import java.util.Locale

/**
 * Camera2 控制器，负责预览、人脸框检测和 625x625 JPG 保存。
 */
class FaceCameraController(
    private val activity: Activity,
    private val textureView: TextureView,
    private val frameView: FaceCollectFrameView,
    private val callback: Callback
) {
    interface Callback {
        /**
         * 相机预览可用后回调人脸检测能力。
         */
        fun onCameraReady(faceDetectionSupported: Boolean)

        /**
         * 相机启动或预览失败。
         */
        fun onCameraFailed()

        /**
         * 系统人脸框更新，用于驱动 ViewModel 状态机。
         */
        fun onFaceState(detected: Boolean, inside: Boolean, tooClose: Boolean)

        /**
         * 当前预览帧保存成功。
         */
        fun onFrameCaptured(filePath: String, directoryPath: String)

        /**
         * 当前预览帧保存失败。
         */
        fun onFrameCaptureFailed()
    }

    private companion object {
        private const val TAG = "FaceCameraController"
        private const val OUTPUT_IMAGE_SIZE = 625
        private const val FRAME_EXTRA_RATIO = 0.04f
        private const val CAPTURE_FRAME_EXTRA_RATIO = FRAME_EXTRA_RATIO
        private const val MAX_FACE_WIDTH_RATIO = 0.42f
        private const val MAX_FACE_HEIGHT_RATIO = 0.54f
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null
    private var previewSize: Size? = null
    private var activeArrayRect: Rect? = null
    private var cameraId: String? = null
    private var cameraSupportsFaceDetection = false
    private var faceDetectMode = CameraMetadata.STATISTICS_FACE_DETECT_MODE_OFF
    private var lastFaceDetected = false
    private var lastFaceInside = false
    private var lastFaceTooClose = false
    private var lastFaceUiUpdateMs = 0L

    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        /**
         * 预览纹理准备好后打开相机。
         */
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            openCamera()
        }

        /**
         * 预览尺寸变化后重新计算预览矩阵。
         */
        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
            configureTransform(width, height)
        }

        /**
         * 返回 true 让系统释放纹理。
         */
        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true

        /**
         * 预览帧刷新不需要额外处理。
         */
        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        /**
         * 相机打开成功后创建预览会话。
         */
        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera
            createCameraPreviewSession()
        }

        /**
         * 相机断开时关闭设备并通知失败。
         */
        override fun onDisconnected(camera: CameraDevice) {
            camera.close()
            cameraDevice = null
            mainHandler.post { callback.onCameraFailed() }
        }

        /**
         * 相机打开失败时关闭设备并通知失败。
         */
        override fun onError(camera: CameraDevice, error: Int) {
            camera.close()
            cameraDevice = null
            mainHandler.post { callback.onCameraFailed() }
        }
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        /**
         * 每帧读取系统人脸框，用于判断人脸是否合格。
         */
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            handleCaptureResult(result)
        }
    }

    /**
     * 启动相机线程并打开前置相机。
     */
    fun start() {
        startCameraThread()
        if (textureView.isAvailable) {
            openCamera()
        } else {
            textureView.surfaceTextureListener = surfaceTextureListener
        }
    }

    /**
     * 停止预览并释放相机。
     */
    fun stop() {
        closeCamera()
        stopCameraThread()
    }

    /**
     * 保存当前预览帧为 625x625 JPG。
     */
    fun captureCurrentFrame(actionFileName: String) {
        val frame = textureView.bitmap
        if (frame == null) {
            callback.onFrameCaptureFailed()
            return
        }
        var square: Bitmap? = null
        var output: Bitmap? = null
        try {
            square = cropBitmapByFrame(frame)
            output = Bitmap.createScaledBitmap(square, OUTPUT_IMAGE_SIZE, OUTPUT_IMAGE_SIZE, true)
            val outputFile = createOutputFile(actionFileName)
            saveBitmapAsJpg(output, outputFile)
            callback.onFrameCaptured(outputFile.absolutePath, getCollectDirectory().absolutePath)
        } catch (e: IOException) {
            Log.e(TAG, "Capture preview frame failed", e)
            callback.onFrameCaptureFailed()
        } catch (e: RuntimeException) {
            Log.e(TAG, "Capture preview frame failed", e)
            callback.onFrameCaptureFailed()
        } finally {
            frame.recycle()
            square?.takeIf { !it.isRecycled }?.recycle()
            output?.takeIf { !it.isRecycled }?.recycle()
        }
    }

    /**
     * 启动相机后台线程。
     */
    private fun startCameraThread() {
        if (cameraThread != null) {
            return
        }
        val thread = HandlerThread("FaceCollectMvvmCamera")
        thread.start()
        cameraThread = thread
        cameraHandler = Handler(thread.looper)
    }

    /**
     * 停止相机后台线程。
     */
    private fun stopCameraThread() {
        val thread = cameraThread ?: return
        thread.quitSafely()
        try {
            thread.join()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        cameraThread = null
        cameraHandler = null
    }

    /**
     * 校验权限后打开相机。
     */
    @SuppressLint("MissingPermission")
    private fun openCamera() {
        if (cameraDevice != null) {
            return
        }
        if (ActivityCompat.checkSelfPermission(activity, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            callback.onCameraFailed()
            return
        }
        try {
            setUpCameraOutputs()
            if (cameraId == null || previewSize == null) {
                callback.onCameraFailed()
                return
            }
            configureTransform(textureView.width, textureView.height)
            val manager = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            manager.openCamera(cameraId!!, stateCallback, cameraHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Open camera failed", e)
            callback.onCameraFailed()
        }
    }

    /**
     * 选择前置相机、预览尺寸和系统人脸框检测模式。
     */
    private fun setUpCameraOutputs() {
        val manager = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        var fallbackCameraId: String? = null
        var fallbackCharacteristics: CameraCharacteristics? = null
        for (id in manager.cameraIdList) {
            val characteristics = manager.getCameraCharacteristics(id)
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            if (fallbackCameraId == null) {
                fallbackCameraId = id
                fallbackCharacteristics = characteristics
            }
            if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
                bindCameraInfo(id, characteristics)
                return
            }
        }
        if (fallbackCameraId != null && fallbackCharacteristics != null) {
            bindCameraInfo(fallbackCameraId, fallbackCharacteristics)
        }
    }

    /**
     * 绑定相机基础能力信息。
     */
    private fun bindCameraInfo(selectedCameraId: String, characteristics: CameraCharacteristics) {
        cameraId = selectedCameraId
        activeArrayRect = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val map: StreamConfigurationMap? =
            characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        previewSize = map?.getOutputSizes(SurfaceTexture::class.java)?.let { choosePreviewSize(it) }
        val modes = characteristics.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES)
        faceDetectMode = chooseFaceDetectMode(modes)
        cameraSupportsFaceDetection =
            faceDetectMode != CameraMetadata.STATISTICS_FACE_DETECT_MODE_OFF
    }

    /**
     * 优先选择接近 4:3 的预览尺寸，降低竖屏人脸放大感。
     */
    private fun choosePreviewSize(choices: Array<Size>): Size {
        if (choices.isEmpty()) {
            return Size(1280, 720)
        }
        var best = choices[0]
        var bestRatioDiff = Float.MAX_VALUE
        var bestArea = Long.MAX_VALUE
        for (option in choices) {
            val area = option.width.toLong() * option.height
            if (option.width >= OUTPUT_IMAGE_SIZE && option.height >= OUTPUT_IMAGE_SIZE) {
                val ratio = maxOf(option.width, option.height) / minOf(option.width, option.height).toFloat()
                val ratioDiff = kotlin.math.abs(ratio - 4f / 3f)
                if (ratioDiff < bestRatioDiff || ratioDiff == bestRatioDiff && area < bestArea) {
                    best = option
                    bestRatioDiff = ratioDiff
                    bestArea = area
                }
            }
        }
        if (bestRatioDiff != Float.MAX_VALUE) {
            return best
        }
        bestArea = 0L
        for (option in choices) {
            val area = option.width.toLong() * option.height
            if (area > bestArea) {
                best = option
                bestArea = area
            }
        }
        return best
    }

    /**
     * 优先选择完整人脸检测模式。
     */
    private fun chooseFaceDetectMode(modes: IntArray?): Int {
        if (modes == null) {
            return CameraMetadata.STATISTICS_FACE_DETECT_MODE_OFF
        }
        var selectedMode = CameraMetadata.STATISTICS_FACE_DETECT_MODE_OFF
        for (mode in modes) {
            if (mode == CameraMetadata.STATISTICS_FACE_DETECT_MODE_FULL) {
                return mode
            }
            if (mode == CameraMetadata.STATISTICS_FACE_DETECT_MODE_SIMPLE) {
                selectedMode = mode
            }
        }
        return selectedMode
    }

    /**
     * 创建持续预览会话。
     */
    private fun createCameraPreviewSession() {
        try {
            val texture = textureView.surfaceTexture
            val size = previewSize
            val device = cameraDevice
            if (texture == null || size == null || device == null) {
                mainHandler.post { callback.onCameraFailed() }
                return
            }
            texture.setDefaultBufferSize(size.width, size.height)
            val surface = Surface(texture)
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            previewRequestBuilder = builder
            builder.addTarget(surface)
            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            builder.set(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            if (cameraSupportsFaceDetection) {
                builder.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, faceDetectMode)
            }
            device.createCaptureSession(
                Collections.singletonList(surface),
                object : CameraCaptureSession.StateCallback() {
                    /**
                     * 会话配置成功后开始重复预览请求。
                     */
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (cameraDevice == null) {
                            return
                        }
                        captureSession = session
                        try {
                            session.setRepeatingRequest(
                                builder.build(),
                                captureCallback,
                                cameraHandler
                            )
                            mainHandler.post {
                                callback.onCameraReady(cameraSupportsFaceDetection)
                            }
                        } catch (e: CameraAccessException) {
                            Log.e(TAG, "Start camera preview failed", e)
                            mainHandler.post { callback.onCameraFailed() }
                        }
                    }

                    /**
                     * 会话配置失败时通知失败。
                     */
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        mainHandler.post { callback.onCameraFailed() }
                    }
                },
                cameraHandler
            )
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Create preview session failed", e)
            mainHandler.post { callback.onCameraFailed() }
        }
    }

    /**
     * 让相机画面只覆盖圆形取景框附近区域，兼顾不露白和头像不过大。
     */
    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        val size = previewSize ?: return
        if (viewWidth == 0 || viewHeight == 0) {
            return
        }
        if (frameView.width == 0 || frameView.height == 0) {
            frameView.post { configureTransform(viewWidth, viewHeight) }
            return
        }
        val matrix = Matrix()
        val centerX = viewWidth / 2f
        val centerY = viewHeight / 2f
        val targetRect = getExpandedFrameRect(viewWidth, viewHeight, FRAME_EXTRA_RATIO)
        var bufferWidth = size.width
        var bufferHeight = size.height
        val viewPortrait = viewHeight > viewWidth
        val bufferLandscape = bufferWidth > bufferHeight
        if (viewPortrait == bufferLandscape) {
            val temp = bufferWidth
            bufferWidth = bufferHeight
            bufferHeight = temp
        }
        val defaultScaleX = viewWidth / bufferWidth.toFloat()
        val defaultScaleY = viewHeight / bufferHeight.toFloat()
        val centerCropScale = maxOf(
            targetRect.width() / bufferWidth.toFloat(),
            targetRect.height() / bufferHeight.toFloat()
        )
        matrix.setScale(
            centerCropScale / defaultScaleX,
            centerCropScale / defaultScaleY,
            centerX,
            centerY
        )
        matrix.postTranslate(targetRect.centerX() - centerX, targetRect.centerY() - centerY)
        textureView.setTransform(matrix)
    }

    /**
     * 获取取景框外扩区域，预览矩阵和最终裁剪都以它为准。
     */
    private fun getExpandedFrameRect(viewWidth: Int, viewHeight: Int, extraRatio: Float): RectF {
        val frameRect = frameView.getFrameRect()
        if (frameRect.isEmpty) {
            return RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        }
        val expandX = frameRect.width() * extraRatio
        val expandY = frameRect.height() * extraRatio
        frameRect.inset(-expandX, -expandY)
        frameRect.intersect(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        return frameRect
    }

    /**
     * 读取系统人脸框并发布人脸状态。
     */
    private fun handleCaptureResult(result: CaptureResult) {
        if (!cameraSupportsFaceDetection) {
            return
        }
        val bestFace = chooseBestFace(result.get(CaptureResult.STATISTICS_FACES))
        val detected = bestFace != null
        val tooClose = bestFace?.let { isFaceTooClose(it) } ?: false
        val inside = bestFace?.let { !tooClose && isFaceInsideFrame(it) } ?: false
        publishFaceState(detected, inside, tooClose)
    }

    /**
     * 选择面积最大且分数可用的人脸框。
     */
    private fun chooseBestFace(faces: Array<Face>?): Face? {
        if (faces.isNullOrEmpty()) {
            return null
        }
        var best: Face? = null
        var bestArea = 0L
        for (face in faces) {
            if (face.score < 40) {
                continue
            }
            val bounds = face.bounds
            val area = bounds.width().toLong() * bounds.height()
            if (area > bestArea) {
                best = face
                bestArea = area
            }
        }
        return best
    }

    /**
     * 判断人脸是否在传感器中央安全区域。
     */
    private fun isFaceInsideFrame(face: Face): Boolean {
        val activeRect = activeArrayRect ?: return false
        val bounds = face.bounds
        val centerX = (bounds.centerX() - activeRect.left) / activeRect.width().toFloat()
        val centerY = (bounds.centerY() - activeRect.top) / activeRect.height().toFloat()
        val dx = centerX - 0.5f
        val dy = centerY - 0.5f
        val centerDistance = kotlin.math.sqrt(dx * dx + dy * dy)
        val widthRatio = bounds.width() / activeRect.width().toFloat()
        val heightRatio = bounds.height() / activeRect.height().toFloat()
        return centerDistance <= 0.24f
                && widthRatio >= 0.12f
                && widthRatio <= MAX_FACE_WIDTH_RATIO
                && heightRatio >= 0.12f
                && heightRatio <= MAX_FACE_HEIGHT_RATIO
    }

    /**
     * 判断人脸是否离镜头过近。
     */
    private fun isFaceTooClose(face: Face): Boolean {
        val activeRect = activeArrayRect ?: return false
        val bounds = face.bounds
        val widthRatio = bounds.width() / activeRect.width().toFloat()
        val heightRatio = bounds.height() / activeRect.height().toFloat()
        return widthRatio > MAX_FACE_WIDTH_RATIO || heightRatio > MAX_FACE_HEIGHT_RATIO
    }

    /**
     * 节流发布人脸状态，避免每帧刷新 UI。
     */
    private fun publishFaceState(detected: Boolean, inside: Boolean, tooClose: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (detected == lastFaceDetected
            && inside == lastFaceInside
            && tooClose == lastFaceTooClose
            && now - lastFaceUiUpdateMs < 250L
        ) {
            return
        }
        lastFaceDetected = detected
        lastFaceInside = inside
        lastFaceTooClose = tooClose
        lastFaceUiUpdateMs = now
        mainHandler.post {
            frameView.setFaceInside(inside)
            callback.onFaceState(detected, inside, tooClose)
        }
    }

    /**
     * 按视觉取景框区域裁剪当前预览帧。
     */
    private fun cropBitmapByFrame(frame: Bitmap): Bitmap {
        val overlayWidth = frameView.width.coerceAtLeast(1)
        val overlayHeight = frameView.height.coerceAtLeast(1)
        val collectRect = getExpandedFrameRect(overlayWidth, overlayHeight, CAPTURE_FRAME_EXTRA_RATIO)
        val left = clamp(
            Math.round(collectRect.left * frame.width / overlayWidth),
            0,
            frame.width - 1
        )
        val top = clamp(
            Math.round(collectRect.top * frame.height / overlayHeight),
            0,
            frame.height - 1
        )
        val right = clamp(
            Math.round(collectRect.right * frame.width / overlayWidth),
            left + 1,
            frame.width
        )
        val bottom = clamp(
            Math.round(collectRect.bottom * frame.height / overlayHeight),
            top + 1,
            frame.height
        )
        val width = right - left
        val height = bottom - top
        val side = minOf(width, height)
        val squareLeft = left + (width - side) / 2
        val squareTop = top + (height - side) / 2
        return Bitmap.createBitmap(frame, squareLeft, squareTop, side, side)
    }

    /**
     * 限制裁剪坐标范围。
     */
    private fun clamp(value: Int, min: Int, max: Int): Int {
        return maxOf(min, minOf(value, max))
    }

    /**
     * 创建当前动作对应的 JPG 文件。
     */
    private fun createOutputFile(actionFileName: String): File {
        val directory = getCollectDirectory()
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Can not create directory: ${directory.absolutePath}")
        }
        return File(
            directory,
            String.format(Locale.US, "face_%d_%s.jpg", System.currentTimeMillis(), actionFileName)
        )
    }

    /**
     * 获取应用私有人脸采集目录。
     */
    private fun getCollectDirectory(): File {
        val picturesDir = activity.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            ?: activity.filesDir
        return File(picturesDir, "face_collect_mvvm")
    }

    /**
     * 将 Bitmap 写成 JPG。
     */
    private fun saveBitmapAsJpg(bitmap: Bitmap, outputFile: File) {
        FileOutputStream(outputFile).use { outputStream ->
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, outputStream)) {
                throw IOException("Compress bitmap failed")
            }
        }
    }

    /**
     * 释放相机会话和设备。
     */
    private fun closeCamera() {
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
    }
}
