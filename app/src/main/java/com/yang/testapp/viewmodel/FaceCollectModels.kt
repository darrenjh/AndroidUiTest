package com.yang.testapp.viewmodel

import androidx.annotation.StringRes
import com.yang.testapp.R

/**
 * 人脸采集页面的 UI 状态，Activity 只根据该状态刷新控件。
 */
data class FaceCollectUiState(
    @StringRes val promptResId: Int = R.string.face_collect_prompt_idle,
    @StringRes val statusResId: Int = R.string.face_collect_status_opening,
    val statusArgs: List<Any> = emptyList(),
    @StringRes val buttonTextResId: Int = R.string.face_collect_start,
    val startEnabled: Boolean = false
)

/**
 * 单个采集动作，包含提示文案和图片文件后缀。
 */
data class FaceCollectAction(
    @StringRes val promptResId: Int,
    val fileName: String
)

/**
 * ViewModel 发给相机层的一次抓帧命令。
 */
data class FaceCaptureRequest(
    val requestId: Long,
    val actionFileName: String
)
