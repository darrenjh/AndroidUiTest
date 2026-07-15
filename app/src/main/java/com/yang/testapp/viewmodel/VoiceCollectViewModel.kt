package com.yang.testapp.viewmodel

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.ViewModel
import com.yang.testapp.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Kotlin MVVM 版声纹采集状态机，负责朗读轮次、音频质量判断和录音命令。
 */
class VoiceCollectViewModel : ViewModel() {
    private companion object {
        private const val RECORD_DURATION_MS = 5000L
        private const val NEXT_ACTION_DELAY_MS = 600L
        private const val MIN_AVERAGE_LEVEL = 0.035f
        private const val MAX_PEAK_LEVEL = 0.98f
        private const val MAX_CLIPPED_RATIO = 0.02f
    }

    private val handler = Handler(Looper.getMainLooper())
    private val pendingActions = ArrayList<VoiceCollectAction>()
    private val savedFiles = ArrayList<String>()
    private var currentAction: VoiceCollectAction? = null
    private var recorderReady = false
    private var collecting = false
    private var recording = false
    private var nextRecordRequestId = 1L

    private val _uiState = MutableStateFlow(VoiceCollectUiState())
    val uiState: StateFlow<VoiceCollectUiState> = _uiState

    private val _recordRequest = MutableStateFlow<VoiceRecordRequest?>(null)
    val recordRequest: StateFlow<VoiceRecordRequest?> = _recordRequest

    private val nextActionRunnable = Runnable {
        if (collecting) {
            startNextAction()
        }
    }

    /**
     * 麦克风权限缺失时刷新页面状态。
     */
    fun onRecorderPermissionNeeded() {
        recorderReady = false
        updateUi(
            statusResId = R.string.voice_collect_status_permission,
            startEnabled = false,
            recording = false,
            audioLevel = 0f
        )
    }

    /**
     * 麦克风可用后允许用户开始声纹采集。
     */
    fun onRecorderReady() {
        recorderReady = true
        if (!collecting) {
            updateUi(
                statusResId = R.string.voice_collect_status_ready,
                startEnabled = true,
                recording = false,
                audioLevel = 0f
            )
        }
    }

    /**
     * 麦克风初始化失败时停止采集。
     */
    fun onRecorderFailed() {
        failCollect(R.string.voice_collect_status_record_failed)
    }

    /**
     * 点击开始后创建固定朗读序列并立即录制第一段。
     */
    fun startCollecting() {
        if (!recorderReady) {
            updateUi(statusResId = R.string.voice_collect_status_permission)
            return
        }
        clearTimers()
        savedFiles.clear()
        pendingActions.clear()
        pendingActions.add(
            VoiceCollectAction(R.string.voice_collect_prompt_digits, "digits")
        )
        pendingActions.add(
            VoiceCollectAction(R.string.voice_collect_prompt_city, "city")
        )
        pendingActions.add(
            VoiceCollectAction(R.string.voice_collect_prompt_verify, "verify")
        )
        collecting = true
        updateUi(
            buttonTextResId = R.string.voice_collect_collecting,
            startEnabled = false,
            audioLevel = 0f
        )
        startNextAction()
    }

    /**
     * 录音层回传音量后刷新动画强度。
     */
    fun onAudioLevel(level: Float) {
        if (!recording) {
            return
        }
        updateUi(audioLevel = level.coerceIn(0f, 1f))
    }

    /**
     * PCM 文件保存完成后判断音频质量，并进入下一段或结束流程。
     */
    fun onRecordingFinished(
        filePath: String,
        directoryPath: String,
        averageLevel: Float,
        peakLevel: Float,
        clippedRatio: Float
    ) {
        recording = false
        updateUi(recording = false, audioLevel = 0f)
        if (!collecting) {
            return
        }
        if (averageLevel < MIN_AVERAGE_LEVEL) {
            failCollect(R.string.voice_collect_status_too_quiet)
            return
        }
        if (peakLevel >= MAX_PEAK_LEVEL || clippedRatio > MAX_CLIPPED_RATIO) {
            failCollect(R.string.voice_collect_status_too_loud)
            return
        }
        savedFiles.add(filePath)
        if (pendingActions.isEmpty()) {
            finishCollecting(directoryPath)
        } else {
            updateUi(
                statusResId = R.string.voice_collect_status_saved,
                statusArgs = listOf(savedFiles.size),
                startEnabled = false
            )
            handler.postDelayed(nextActionRunnable, NEXT_ACTION_DELAY_MS)
        }
    }

    /**
     * 录音失败后停止采集并恢复重试按钮。
     */
    fun onRecordingFailed() {
        failCollect(R.string.voice_collect_status_record_failed)
    }

    /**
     * Activity 消费录音命令后清空状态，避免页面重建时重复录音。
     */
    fun onRecordRequestConsumed(requestId: Long) {
        if (_recordRequest.value?.requestId == requestId) {
            _recordRequest.value = null
        }
    }

    /**
     * 页面退出时静默停止状态机。
     */
    fun stopCollectingWithoutMessage() {
        collecting = false
        recording = false
        currentAction = null
        clearTimers()
        _recordRequest.value = null
        updateUi(recording = false, audioLevel = 0f)
    }

    /**
     * ViewModel 销毁时清理延迟任务。
     */
    override fun onCleared() {
        clearTimers()
        super.onCleared()
    }

    /**
     * 切换到下一句朗读提示并发起录音命令。
     */
    private fun startNextAction() {
        val action = pendingActions.removeAt(0)
        currentAction = action
        recording = true
        updateUi(
            promptResId = action.promptResId,
            statusResId = R.string.voice_collect_status_recording,
            statusArgs = emptyList(),
            buttonTextResId = R.string.voice_collect_collecting,
            startEnabled = false,
            recording = true,
            audioLevel = 0f
        )
        _recordRequest.value = VoiceRecordRequest(
            requestId = nextRecordRequestId++,
            actionFileName = action.fileName,
            durationMs = RECORD_DURATION_MS
        )
    }

    /**
     * 采集完成后恢复开始按钮并展示保存目录。
     */
    private fun finishCollecting(directoryPath: String) {
        collecting = false
        recording = false
        currentAction = null
        clearTimers()
        updateUi(
            promptResId = R.string.voice_collect_prompt_idle,
            statusResId = R.string.voice_collect_status_done,
            statusArgs = listOf(savedFiles.size, directoryPath),
            buttonTextResId = R.string.voice_collect_retry,
            startEnabled = true,
            recording = false,
            audioLevel = 0f
        )
    }

    /**
     * 采集失败后恢复开始按钮。
     */
    private fun failCollect(statusResId: Int) {
        collecting = false
        recording = false
        currentAction = null
        clearTimers()
        _recordRequest.value = null
        updateUi(
            promptResId = R.string.voice_collect_prompt_idle,
            statusResId = statusResId,
            statusArgs = emptyList(),
            buttonTextResId = R.string.voice_collect_retry,
            startEnabled = recorderReady,
            recording = false,
            audioLevel = 0f
        )
    }

    /**
     * 清理下一段录音延迟任务。
     */
    private fun clearTimers() {
        handler.removeCallbacks(nextActionRunnable)
    }

    /**
     * 合并 UI 状态，避免 Activity 持有采集业务规则。
     */
    private fun updateUi(
        promptResId: Int = _uiState.value.promptResId,
        statusResId: Int = _uiState.value.statusResId,
        statusArgs: List<Any> = emptyList(),
        buttonTextResId: Int = _uiState.value.buttonTextResId,
        startEnabled: Boolean = _uiState.value.startEnabled,
        recording: Boolean = _uiState.value.recording,
        audioLevel: Float = _uiState.value.audioLevel
    ) {
        _uiState.value = VoiceCollectUiState(
            promptResId = promptResId,
            statusResId = statusResId,
            statusArgs = statusArgs,
            buttonTextResId = buttonTextResId,
            startEnabled = startEnabled,
            recording = recording,
            audioLevel = audioLevel
        )
    }
}
