package com.yang.testapp.activity

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.yang.testapp.audio.VoiceRecorderController
import com.yang.testapp.databinding.ActivityVoiceCollectBinding
import com.yang.testapp.viewmodel.VoiceCollectViewModel
import kotlinx.coroutines.flow.collect

/**
 * Kotlin MVVM 版声纹采集页，只负责页面、权限和生命周期分发。
 */
class VoiceCollectActivity : AppCompatActivity(), VoiceRecorderController.Callback {
    private companion object {
        private const val REQUEST_RECORD_AUDIO_PERMISSION = 3001
    }

    private lateinit var binding: ActivityVoiceCollectBinding
    private lateinit var viewModel: VoiceCollectViewModel
    private lateinit var recorderController: VoiceRecorderController

    /**
     * 初始化 ViewBinding、ViewModel、录音控制器和观察者。
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVoiceCollectBinding.inflate(LayoutInflater.from(this))
        setContentView(binding.root)
        setupImmersiveStatusBar()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        viewModel = ViewModelProvider(this).get(VoiceCollectViewModel::class.java)
        recorderController = VoiceRecorderController(
            context = this,
            callback = this
        )
        bindViews()
        observeViewModel()
    }

    /**
     * 页面恢复后校验麦克风权限。
     */
    override fun onResume() {
        super.onResume()
        prepareRecorderIfPermitted()
    }

    /**
     * 页面暂停时停止采集状态机并释放麦克风。
     */
    override fun onPause() {
        viewModel.stopCollectingWithoutMessage()
        recorderController.stop()
        super.onPause()
    }

    /**
     * 处理麦克风权限结果。
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO_PERMISSION
            && grantResults.isNotEmpty()
            && grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            prepareRecorderIfPermitted()
        } else {
            viewModel.onRecorderPermissionNeeded()
        }
    }

    /**
     * 麦克风准备完成后通知 ViewModel。
     */
    override fun onRecorderReady() {
        viewModel.onRecorderReady()
    }

    /**
     * 麦克风初始化失败后通知 ViewModel。
     */
    override fun onRecorderFailed() {
        viewModel.onRecorderFailed()
    }

    /**
     * 实时音量变化后通知 ViewModel。
     */
    override fun onAudioLevel(level: Float) {
        viewModel.onAudioLevel(level)
    }

    /**
     * PCM 文件保存成功后通知 ViewModel。
     */
    override fun onRecordingFinished(
        filePath: String,
        directoryPath: String,
        averageLevel: Float,
        peakLevel: Float,
        clippedRatio: Float
    ) {
        viewModel.onRecordingFinished(
            filePath = filePath,
            directoryPath = directoryPath,
            averageLevel = averageLevel,
            peakLevel = peakLevel,
            clippedRatio = clippedRatio
        )
    }

    /**
     * PCM 文件保存失败后通知 ViewModel。
     */
    override fun onRecordingFailed() {
        viewModel.onRecordingFailed()
    }

    /**
     * 绑定开始采集按钮。
     */
    private fun bindViews() {
        binding.btnStartCollect.setOnClickListener {
            viewModel.startCollecting()
        }
    }

    /**
     * 使用 StateFlow 观察 UI 状态和单次录音命令。
     */
    private fun observeViewModel() {
        lifecycleScope.launchWhenStarted {
            viewModel.uiState.collect { state ->
                binding.tvPrompt.setText(state.promptResId)
                binding.tvStatus.text = if (state.statusArgs.isEmpty()) {
                    getString(state.statusResId)
                } else {
                    getString(state.statusResId, *state.statusArgs.toTypedArray())
                }
                binding.btnStartCollect.setText(state.buttonTextResId)
                binding.btnStartCollect.isEnabled = state.startEnabled
                binding.voiceLevelView.setRecording(state.recording)
                binding.voiceLevelView.setAudioLevel(state.audioLevel)
            }
        }
        lifecycleScope.launchWhenStarted {
            viewModel.recordRequest.collect { request ->
                if (request == null) {
                    return@collect
                }
                recorderController.startRecording(request.actionFileName, request.durationMs)
                viewModel.onRecordRequestConsumed(request.requestId)
            }
        }
    }

    /**
     * 有权限则准备麦克风，否则发起权限请求。
     */
    private fun prepareRecorderIfPermitted() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            viewModel.onRecorderPermissionNeeded()
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQUEST_RECORD_AUDIO_PERMISSION
            )
            return
        }
        recorderController.prepare()
    }

    /**
     * 配置白底沉浸式状态栏。
     */
    private fun setupImmersiveStatusBar() {
        supportActionBar?.hide()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.WHITE
        }
        var systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            systemUiVisibility = systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            systemUiVisibility = systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        window.decorView.systemUiVisibility = systemUiVisibility
        val statusBarHeight = getStatusBarHeight()
        binding.root.setPadding(
            binding.root.paddingLeft,
            binding.root.paddingTop + statusBarHeight,
            binding.root.paddingRight,
            binding.root.paddingBottom
        )
    }

    /**
     * 读取系统状态栏高度，用于沉浸式页面顶部安全间距。
     */
    private fun getStatusBarHeight(): Int {
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) {
            resources.getDimensionPixelSize(resourceId)
        } else {
            0
        }
    }
}
