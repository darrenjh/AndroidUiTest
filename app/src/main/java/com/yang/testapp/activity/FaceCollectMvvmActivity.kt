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
import com.yang.testapp.R
import com.yang.testapp.camera.FaceCameraController
import com.yang.testapp.databinding.ActivityFaceCollectMvvmBinding
import com.yang.testapp.viewmodel.FaceCollectViewModel
import kotlinx.coroutines.flow.collect

/**
 * Kotlin MVVM 版人脸采集页，只负责页面、权限和生命周期分发。
 */
class FaceCollectMvvmActivity : AppCompatActivity(), FaceCameraController.Callback {
    private companion object {
        private const val REQUEST_CAMERA_PERMISSION = 2001
    }

    private lateinit var binding: ActivityFaceCollectMvvmBinding
    private lateinit var viewModel: FaceCollectViewModel
    private lateinit var cameraController: FaceCameraController

    /**
     * 初始化 ViewBinding、ViewModel、相机控制器和观察者。
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFaceCollectMvvmBinding.inflate(LayoutInflater.from(this))
        setContentView(binding.root)
        setupImmersiveStatusBar()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        viewModel = ViewModelProvider(this).get(FaceCollectViewModel::class.java)
        cameraController = FaceCameraController(
            activity = this,
            textureView = binding.textureView,
            frameView = binding.faceFrameOverlay,
            callback = this
        )
        bindViews()
        observeViewModel()
    }

    /**
     * 页面恢复后校验权限并打开相机。
     */
    override fun onResume() {
        super.onResume()
        startCameraIfPermitted()
    }

    /**
     * 页面暂停时停止采集状态机并释放相机。
     */
    override fun onPause() {
        viewModel.stopCollectingWithoutMessage()
        cameraController.stop()
        super.onPause()
    }

    /**
     * 处理相机权限结果。
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA_PERMISSION
            && grantResults.isNotEmpty()
            && grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            startCameraIfPermitted()
        } else {
            viewModel.onCameraPermissionNeeded()
        }
    }

    /**
     * 相机准备完成后通知 ViewModel。
     */
    override fun onCameraReady(faceDetectionSupported: Boolean) {
        viewModel.onCameraReady(faceDetectionSupported)
    }

    /**
     * 相机失败后通知 ViewModel。
     */
    override fun onCameraFailed() {
        viewModel.onCameraFailed()
    }

    /**
     * 人脸状态变化后通知 ViewModel。
     */
    override fun onFaceState(detected: Boolean, inside: Boolean, tooClose: Boolean) {
        viewModel.onFaceState(detected, inside, tooClose)
    }

    /**
     * 当前帧保存成功后通知 ViewModel。
     */
    override fun onFrameCaptured(filePath: String, directoryPath: String) {
        viewModel.onFrameCaptured(filePath, directoryPath)
    }

    /**
     * 当前帧保存失败后通知 ViewModel。
     */
    override fun onFrameCaptureFailed() {
        viewModel.onFrameCaptureFailed()
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
     * 使用 StateFlow 观察 UI 状态和单次抓帧命令。
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
            }
        }
        lifecycleScope.launchWhenStarted {
            viewModel.captureRequest.collect { request ->
                if (request == null) {
                    return@collect
                }
                cameraController.captureCurrentFrame(request.actionFileName)
                viewModel.onCaptureRequestConsumed(request.requestId)
            }
        }
    }

    /**
     * 有权限则启动相机，否则发起权限请求。
     */
    private fun startCameraIfPermitted() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            viewModel.onCameraPermissionNeeded()
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                REQUEST_CAMERA_PERMISSION
            )
            return
        }
        viewModel.onCameraOpening()
        cameraController.start()
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
