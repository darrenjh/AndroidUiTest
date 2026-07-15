package com.yang.testapp.viewmodel

import androidx.annotation.StringRes
import com.yang.testapp.R

/**
 * 声纹采集页面的 UI 状态，Activity 只根据该状态刷新控件。
 */
data class VoiceCollectUiState(
    @StringRes val promptResId: Int = R.string.voice_collect_prompt_idle,
    @StringRes val statusResId: Int = R.string.voice_collect_status_ready,
    val statusArgs: List<Any> = emptyList(),
    @StringRes val buttonTextResId: Int = R.string.voice_collect_start,
    val startEnabled: Boolean = false,
    val recording: Boolean = false,
    val audioLevel: Float = 0f
)

/**
 * 单段声纹采集动作，包含朗读提示和 PCM 文件后缀。
 */
data class VoiceCollectAction(
    @StringRes val promptResId: Int,
    val fileName: String
)

/**
 * ViewModel 发给录音层的一次录音命令。
 */
data class VoiceRecordRequest(
    val requestId: Long,
    val actionFileName: String,
    val durationMs: Long
)
