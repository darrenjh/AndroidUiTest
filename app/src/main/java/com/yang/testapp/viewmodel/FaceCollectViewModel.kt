package com.yang.testapp.viewmodel

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import com.yang.testapp.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Collections

/**
 * Kotlin MVVM 版人脸采集状态机，负责动作、提示、超时和抓帧时机。
 */
class FaceCollectViewModel : ViewModel() {
    private companion object {
        private const val FACE_STABLE_MS = 900L
        private const val ACTION_MIN_VISIBLE_MS = 1600L
        private const val ACTION_TIMEOUT_MS = 12000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val pendingActions = ArrayList<FaceCollectAction>()
    private val savedFiles = ArrayList<String>()
    private var currentAction: FaceCollectAction? = null
    private var collecting = false
    private var cameraSupportsFaceDetection = false
    private var faceInsideFrame = false
    private var faceReadySinceMs = 0L
    private var actionStartedMs = 0L
    private var captureScheduled = false
    private var nextCaptureRequestId = 1L

    private val _uiState = MutableStateFlow(FaceCollectUiState())
    val uiState: StateFlow<FaceCollectUiState> = _uiState

    private val _captureRequest = MutableStateFlow<FaceCaptureRequest?>(null)
    val captureRequest: StateFlow<FaceCaptureRequest?> = _captureRequest

    private val actionTimeoutRunnable = Runnable {
        if (collecting) {
            failCollect(R.string.face_collect_status_failed_timeout)
        }
    }

    private val captureFrameRunnable = Runnable {
        captureScheduled = false
        val action = currentAction
        if (collecting && faceInsideFrame && action != null) {
            _captureRequest.value = FaceCaptureRequest(
                requestId = nextCaptureRequestId++,
                actionFileName = action.fileName
            )
        }
    }

    /**
     * 相机准备打开时刷新页面状态。
     */
    fun onCameraOpening() {
        updateUi(
            statusResId = R.string.face_collect_status_opening,
            startEnabled = false
        )
    }

    /**
     * 相机权限缺失时刷新页面状态。
     */
    fun onCameraPermissionNeeded() {
        updateUi(
            statusResId = R.string.face_collect_status_permission,
            startEnabled = false
        )
    }

    /**
     * 相机预览可用后记录人脸框检测能力。
     */
    fun onCameraReady(faceDetectionSupported: Boolean) {
        cameraSupportsFaceDetection = faceDetectionSupported
        updateUi(
            statusResId = if (faceDetectionSupported) {
                R.string.face_collect_status_ready
            } else {
                R.string.face_collect_status_unsupported
            },
            startEnabled = faceDetectionSupported
        )
    }

    /**
     * 相机启动或预览失败时停止采集并展示失败状态。
     */
    fun onCameraFailed() {
        failCollect(R.string.face_collect_status_capture_failed)
    }

    /**
     * 点击开始后创建随机动作序列。
     */
    fun startCollecting() {
        if (!cameraSupportsFaceDetection) {
            updateUi(statusResId = R.string.face_collect_status_unsupported)
            return
        }
        savedFiles.clear()
        pendingActions.clear()
        val actions = arrayListOf(
            FaceCollectAction(R.string.face_collect_prompt_look_forward, "look_forward"),
            FaceCollectAction(R.string.face_collect_prompt_turn_left, "turn_left"),
            FaceCollectAction(R.string.face_collect_prompt_turn_right, "turn_right"),
            FaceCollectAction(R.string.face_collect_prompt_raise_head, "raise_head")
        )
        Collections.shuffle(actions)
        pendingActions.addAll(actions.take(3))
        collecting = true
        updateUi(
            statusResId = R.string.face_collect_status_no_face,
            buttonTextResId = R.string.face_collect_collecting,
            startEnabled = false
        )
        startNextAction()
    }

    /**
     * 相机层回传人脸位置后更新 UI，并在满足条件时调度抓帧。
     */
    fun onFaceState(detected: Boolean, inside: Boolean, tooClose: Boolean) {
        faceInsideFrame = inside
        val idlePromptResId = if (!collecting && inside) {
            R.string.face_collect_prompt_ready
        } else {
            R.string.face_collect_prompt_idle
        }
        if (!detected) {
            faceReadySinceMs = 0L
            cancelScheduledCapture()
            updateFaceStateUi(idlePromptResId, R.string.face_collect_status_no_face)
            return
        }
        if (tooClose) {
            faceReadySinceMs = 0L
            cancelScheduledCapture()
            updateFaceStateUi(idlePromptResId, R.string.face_collect_status_too_close)
            return
        }
        if (!inside) {
            faceReadySinceMs = 0L
            cancelScheduledCapture()
            updateFaceStateUi(idlePromptResId, R.string.face_collect_status_outside)
            return
        }
        if (faceReadySinceMs == 0L) {
            faceReadySinceMs = SystemClock.elapsedRealtime()
        }
        updateFaceStateUi(
            idlePromptResId,
            if (collecting) {
                R.string.face_collect_status_inside
            } else {
                R.string.face_collect_status_ready
            }
        )
        scheduleCaptureIfReady()
    }

    /**
     * 未采集时同步顶部提示，采集中保留当前随机动作提示。
     */
    private fun updateFaceStateUi(promptResIdWhenIdle: Int, statusResId: Int) {
        if (collecting) {
            updateUi(statusResId = statusResId)
        } else {
            updateUi(
                promptResId = promptResIdWhenIdle,
                statusResId = statusResId
            )
        }
    }

    /**
     * 图片保存成功后进入下一个动作或结束流程。
     */
    fun onFrameCaptured(filePath: String, directoryPath: String) {
        savedFiles.add(filePath)
        if (pendingActions.isEmpty()) {
            finishCollecting(directoryPath)
        } else {
            startNextAction()
        }
    }

    /**
     * 图片抓取失败后停止采集。
     */
    fun onFrameCaptureFailed() {
        failCollect(R.string.face_collect_status_capture_failed)
    }

    /**
     * Activity 消费抓帧命令后清空状态，避免重建页面时重复抓帧。
     */
    fun onCaptureRequestConsumed(requestId: Long) {
        if (_captureRequest.value?.requestId == requestId) {
            _captureRequest.value = null
        }
    }

    /**
     * 页面退出时静默停止状态机。
     */
    fun stopCollectingWithoutMessage() {
        collecting = false
        currentAction = null
        clearTimers()
        captureScheduled = false
        faceReadySinceMs = 0L
    }

    /**
     * ViewModel 销毁时清理延迟任务。
     */
    override fun onCleared() {
        clearTimers()
        super.onCleared()
    }

    /**
     * 切换到下一个随机动作。
     */
    private fun startNextAction() {
        clearTimers()
        captureScheduled = false
        faceReadySinceMs = 0L
        val action = pendingActions.removeAt(0)
        currentAction = action
        actionStartedMs = SystemClock.elapsedRealtime()
        updateUi(
            promptResId = action.promptResId,
            statusResId = R.string.face_collect_status_no_face,
            buttonTextResId = R.string.face_collect_collecting,
            startEnabled = false
        )
        handler.postDelayed(actionTimeoutRunnable, ACTION_TIMEOUT_MS)
        if (faceInsideFrame) {
            faceReadySinceMs = SystemClock.elapsedRealtime()
        }
        scheduleCaptureIfReady()
    }

    /**
     * 采集完成后恢复开始按钮并展示保存目录。
     */
    private fun finishCollecting(directoryPath: String) {
        collecting = false
        currentAction = null
        clearTimers()
        updateUi(
            promptResId = R.string.face_collect_prompt_idle,
            statusResId = R.string.face_collect_status_done,
            statusArgs = listOf(savedFiles.size, directoryPath),
            buttonTextResId = R.string.face_collect_retry,
            startEnabled = true
        )
    }

    /**
     * 采集失败后恢复开始按钮。
     */
    private fun failCollect(statusResId: Int) {
        collecting = false
        currentAction = null
        clearTimers()
        captureScheduled = false
        faceReadySinceMs = 0L
        updateUi(
            promptResId = R.string.face_collect_prompt_idle,
            statusResId = statusResId,
            buttonTextResId = R.string.face_collect_retry,
            startEnabled = true
        )
    }

    /**
     * 人脸稳定且动作提示展示足够久后发起抓帧命令。
     */
    private fun scheduleCaptureIfReady() {
        if (!collecting || currentAction == null || !faceInsideFrame || captureScheduled) {
            return
        }
        val now = SystemClock.elapsedRealtime()
        val faceElapsed = now - faceReadySinceMs
        val actionElapsed = now - actionStartedMs
        val delay = maxOf(
            0L,
            FACE_STABLE_MS - faceElapsed,
            ACTION_MIN_VISIBLE_MS - actionElapsed
        )
        captureScheduled = true
        handler.postDelayed(captureFrameRunnable, delay)
    }

    /**
     * 取消已经调度但尚未执行的抓帧任务。
     */
    private fun cancelScheduledCapture() {
        if (!captureScheduled) {
            return
        }
        captureScheduled = false
        handler.removeCallbacks(captureFrameRunnable)
    }

    /**
     * 清理超时和抓帧延迟任务。
     */
    private fun clearTimers() {
        handler.removeCallbacks(actionTimeoutRunnable)
        handler.removeCallbacks(captureFrameRunnable)
    }

    /**
     * 合并 UI 状态，避免 Activity 持有业务规则。
     */
    private fun updateUi(
        promptResId: Int = _uiState.value.promptResId,
        statusResId: Int = _uiState.value.statusResId,
        statusArgs: List<Any> = emptyList(),
        buttonTextResId: Int = _uiState.value.buttonTextResId,
        startEnabled: Boolean = _uiState.value.startEnabled
    ) {
        _uiState.value = FaceCollectUiState(
            promptResId = promptResId,
            statusResId = statusResId,
            statusArgs = statusArgs,
            buttonTextResId = buttonTextResId,
            startEnabled = startEnabled
        )
    }
}
