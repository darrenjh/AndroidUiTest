package com.yang.testapp.activity;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.Face;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.yang.testapp.R;
import com.yang.testapp.databinding.ActivityFaceCollectBinding;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * 人脸图片流采集页：只采集 625x625 JPG，不做身份识别或人脸比对。
 */
public class FaceCollectActivity extends AppCompatActivity {
    private static final String TAG = "FaceCollectActivity";
    private static final int REQUEST_CAMERA_PERMISSION = 1001;
    private static final int OUTPUT_IMAGE_SIZE = 625;
    private static final float FRAME_EXTRA_RATIO = 0.04f;
    private static final float CAPTURE_FRAME_EXTRA_RATIO = FRAME_EXTRA_RATIO;
    private static final float MAX_FACE_WIDTH_RATIO = 0.42f;
    private static final float MAX_FACE_HEIGHT_RATIO = 0.54f;
    private static final long FACE_STABLE_MS = 900L;
    private static final long ACTION_MIN_VISIBLE_MS = 1600L;
    private static final long ACTION_TIMEOUT_MS = 12000L;

    private final Random random = new Random();
    private final List<CollectAction> pendingActions = new ArrayList<>();
    private final List<String> savedFiles = new ArrayList<>();

    private ActivityFaceCollectBinding binding;
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private CaptureRequest.Builder previewRequestBuilder;
    private Size previewSize;
    private Rect activeArrayRect;
    private String cameraId;
    private boolean cameraSupportsFaceDetection;
    private int faceDetectMode = CameraMetadata.STATISTICS_FACE_DETECT_MODE_OFF;

    private CollectAction currentAction;
    private boolean collecting;
    private boolean faceInsideFrame;
    private boolean captureScheduled;
    private long faceReadySinceMs;
    private long actionStartedMs;
    private long lastFaceUiUpdateMs;
    private boolean lastFaceDetected;
    private boolean lastFaceInside;
    private boolean lastFaceTooClose;

    private final TextureView.SurfaceTextureListener surfaceTextureListener =
            new TextureView.SurfaceTextureListener() {
                /**
                 * 预览纹理准备好后打开相机。
                 */
                @Override
                public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surfaceTexture, int width, int height) {
                    openCamera();
                }

                /**
                 * 预览尺寸变化时重新计算相机画面填充矩阵。
                 */
                @Override
                public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surfaceTexture, int width, int height) {
                    configureTransform(width, height);
                }

                /**
                 * 系统回收预览纹理时返回 true，由系统释放资源。
                 */
                @Override
                public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surfaceTexture) {
                    return true;
                }

                /**
                 * 预览画面刷新不需要额外处理，采集使用 TextureView 当前帧。
                 */
                @Override
                public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surfaceTexture) {
                }
            };

    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        /**
         * 相机打开成功后创建预览会话。
         */
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            cameraDevice = camera;
            createCameraPreviewSession();
        }

        /**
         * 相机断开时关闭设备并提示用户重新进入页面。
         */
        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            camera.close();
            cameraDevice = null;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    binding.tvStatus.setText(R.string.face_collect_status_capture_failed);
                }
            });
        }

        /**
         * 相机打开失败时释放设备并展示失败状态。
         */
        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            camera.close();
            cameraDevice = null;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    binding.tvStatus.setText(R.string.face_collect_status_capture_failed);
                }
            });
        }
    };

    private final CameraCaptureSession.CaptureCallback captureCallback =
            new CameraCaptureSession.CaptureCallback() {
                /**
                 * 每帧预览结果返回时读取系统人脸框，用于判断是否在圆形取景框内。
                 */
                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                               @NonNull CaptureRequest request,
                                               @NonNull TotalCaptureResult result) {
                    handleCaptureResult(result);
                }
            };

    private final Runnable actionTimeoutRunnable = new Runnable() {
        /**
         * 单个动作超时仍未采到合格帧时判定采集失败。
         */
        @Override
        public void run() {
            if (collecting) {
                failCollect(getString(R.string.face_collect_status_failed_timeout));
            }
        }
    };

    private final Runnable captureFrameRunnable = new Runnable() {
        /**
         * 人脸稳定处于取景框内后保存当前预览帧。
         */
        @Override
        public void run() {
            captureScheduled = false;
            if (collecting && faceInsideFrame) {
                captureCurrentFrame();
            }
        }
    };

    /**
     * 初始化页面、权限和采集按钮。
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityFaceCollectBinding.inflate(LayoutInflater.from(this));
        setContentView(binding.getRoot());
        setupImmersiveStatusBar();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        binding.btnStartCollect.setEnabled(false);
        binding.btnStartCollect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startCollecting();
            }
        });
    }

    /**
     * 配置白底沉浸式状态栏，隐藏 ActionBar 并让内容自然避开状态栏高度。
     */
    private void setupImmersiveStatusBar() {
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.hide();
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(Color.TRANSPARENT);
            getWindow().setNavigationBarColor(Color.WHITE);
        }
        int systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            systemUiVisibility |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            systemUiVisibility |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        getWindow().getDecorView().setSystemUiVisibility(systemUiVisibility);
        int statusBarHeight = getStatusBarHeight();
        View rootView = binding.getRoot();
        rootView.setPadding(
                rootView.getPaddingLeft(),
                rootView.getPaddingTop() + statusBarHeight,
                rootView.getPaddingRight(),
                rootView.getPaddingBottom()
        );
    }

    /**
     * 读取状态栏高度，用于沉浸式页面的顶部安全间距。
     */
    private int getStatusBarHeight() {
        int resourceId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resourceId <= 0) {
            return 0;
        }
        return getResources().getDimensionPixelSize(resourceId);
    }

    /**
     * 页面恢复时启动相机线程并准备预览。
     */
    @Override
    protected void onResume() {
        super.onResume();
        startCameraThread();
        if (binding.textureView.isAvailable()) {
            openCamera();
        } else {
            binding.textureView.setSurfaceTextureListener(surfaceTextureListener);
        }
    }

    /**
     * 页面暂停时关闭相机，防止后台占用摄像头。
     */
    @Override
    protected void onPause() {
        stopCollectingWithoutMessage();
        closeCamera();
        stopCameraThread();
        super.onPause();
    }

    /**
     * 处理相机权限结果，授权后立即打开相机。
     */
    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA_PERMISSION
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            openCamera();
        } else {
            binding.tvStatus.setText(R.string.face_collect_status_permission);
        }
    }

    /**
     * 开始一个随机动作序列，只有人脸稳定在圆形框内才保存图片。
     */
    private void startCollecting() {
        if (!cameraSupportsFaceDetection) {
            binding.tvStatus.setText(R.string.face_collect_status_unsupported);
            return;
        }
        savedFiles.clear();
        pendingActions.clear();
        List<CollectAction> actions = new ArrayList<>(Arrays.asList(
                new CollectAction(getString(R.string.face_collect_prompt_look_forward), "look_forward"),
                new CollectAction(getString(R.string.face_collect_prompt_turn_left), "turn_left"),
                new CollectAction(getString(R.string.face_collect_prompt_turn_right), "turn_right"),
                new CollectAction(getString(R.string.face_collect_prompt_raise_head), "raise_head")
        ));
        Collections.shuffle(actions, random);
        pendingActions.addAll(actions.subList(0, Math.min(3, actions.size())));
        collecting = true;
        binding.btnStartCollect.setEnabled(false);
        binding.btnStartCollect.setText(R.string.face_collect_collecting);
        startNextAction();
    }

    /**
     * 进入下一个随机动作；如果没有剩余动作则结束采集。
     */
    private void startNextAction() {
        binding.getRoot().removeCallbacks(actionTimeoutRunnable);
        binding.textureView.removeCallbacks(captureFrameRunnable);
        captureScheduled = false;
        faceReadySinceMs = 0L;
        if (pendingActions.isEmpty()) {
            finishCollecting();
            return;
        }
        currentAction = pendingActions.remove(0);
        actionStartedMs = SystemClock.elapsedRealtime();
        binding.tvPrompt.setText(currentAction.prompt);
        binding.tvStatus.setText(R.string.face_collect_status_no_face);
        binding.getRoot().postDelayed(actionTimeoutRunnable, ACTION_TIMEOUT_MS);
        if (faceInsideFrame) {
            faceReadySinceMs = SystemClock.elapsedRealtime();
        }
        scheduleCaptureIfReady();
    }

    /**
     * 采集完成后恢复按钮，并展示保存路径。
     */
    private void finishCollecting() {
        collecting = false;
        currentAction = null;
        binding.getRoot().removeCallbacks(actionTimeoutRunnable);
        binding.textureView.removeCallbacks(captureFrameRunnable);
        binding.btnStartCollect.setEnabled(true);
        binding.btnStartCollect.setText(R.string.face_collect_retry);
        binding.tvPrompt.setText(R.string.face_collect_prompt_idle);
        binding.tvStatus.setText(getString(
                R.string.face_collect_status_done,
                savedFiles.size(),
                getCollectDirectory().getAbsolutePath()
        ));
    }

    /**
     * 采集失败时停止状态机，并保留已经落盘的图片供排查。
     */
    private void failCollect(String message) {
        collecting = false;
        currentAction = null;
        binding.getRoot().removeCallbacks(actionTimeoutRunnable);
        binding.textureView.removeCallbacks(captureFrameRunnable);
        captureScheduled = false;
        faceReadySinceMs = 0L;
        binding.btnStartCollect.setEnabled(true);
        binding.btnStartCollect.setText(R.string.face_collect_retry);
        binding.tvPrompt.setText(R.string.face_collect_prompt_idle);
        binding.tvStatus.setText(message);
    }

    /**
     * 页面退出时静默停止采集状态机。
     */
    private void stopCollectingWithoutMessage() {
        collecting = false;
        currentAction = null;
        binding.getRoot().removeCallbacks(actionTimeoutRunnable);
        binding.textureView.removeCallbacks(captureFrameRunnable);
        captureScheduled = false;
        faceReadySinceMs = 0L;
    }

    /**
     * 启动相机后台线程。
     */
    private void startCameraThread() {
        if (cameraThread != null) {
            return;
        }
        cameraThread = new HandlerThread("FaceCollectCamera");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
    }

    /**
     * 停止相机后台线程。
     */
    private void stopCameraThread() {
        if (cameraThread == null) {
            return;
        }
        cameraThread.quitSafely();
        try {
            cameraThread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        cameraThread = null;
        cameraHandler = null;
    }

    /**
     * 校验权限并打开前置相机。
     */
    private void openCamera() {
        if (cameraDevice != null) {
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.CAMERA},
                    REQUEST_CAMERA_PERMISSION
            );
            binding.tvStatus.setText(R.string.face_collect_status_permission);
            return;
        }
        try {
            setUpCameraOutputs();
            if (cameraId == null || previewSize == null) {
                binding.tvStatus.setText(R.string.face_collect_status_capture_failed);
                return;
            }
            configureTransform(binding.textureView.getWidth(), binding.textureView.getHeight());
            CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            if (manager == null) {
                binding.tvStatus.setText(R.string.face_collect_status_capture_failed);
                return;
            }
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {
                return;
            }
            binding.tvStatus.setText(R.string.face_collect_status_opening);
            manager.openCamera(cameraId, stateCallback, cameraHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Open camera failed", e);
            binding.tvStatus.setText(R.string.face_collect_status_capture_failed);
        }
    }

    /**
     * 选择前置相机、预览尺寸和系统人脸检测模式。
     */
    private void setUpCameraOutputs() throws CameraAccessException {
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        if (manager == null) {
            binding.tvStatus.setText(R.string.face_collect_status_capture_failed);
            return;
        }
        String fallbackCameraId = null;
        CameraCharacteristics fallbackCharacteristics = null;
        for (String id : manager.getCameraIdList()) {
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
            Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
            if (fallbackCameraId == null) {
                fallbackCameraId = id;
                fallbackCharacteristics = characteristics;
            }
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                bindCameraInfo(id, characteristics);
                return;
            }
        }
        if (fallbackCameraId != null && fallbackCharacteristics != null) {
            bindCameraInfo(fallbackCameraId, fallbackCharacteristics);
        }
    }

    /**
     * 绑定已选相机的基础能力信息。
     */
    private void bindCameraInfo(String selectedCameraId, CameraCharacteristics characteristics) {
        cameraId = selectedCameraId;
        activeArrayRect = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map != null) {
            previewSize = choosePreviewSize(map.getOutputSizes(SurfaceTexture.class));
        }
        int[] modes = characteristics.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES);
        faceDetectMode = chooseFaceDetectMode(modes);
        cameraSupportsFaceDetection = faceDetectMode != CameraMetadata.STATISTICS_FACE_DETECT_MODE_OFF;
    }

    /**
     * 优先选择接近 4:3 的预览尺寸，减少竖屏采集时的画面裁切和人脸放大感。
     */
    private Size choosePreviewSize(Size[] choices) {
        if (choices == null || choices.length == 0) {
            return new Size(1280, 720);
        }
        Size best = choices[0];
        float bestRatioDiff = Float.MAX_VALUE;
        long bestArea = Long.MAX_VALUE;
        for (Size option : choices) {
            long area = (long) option.getWidth() * option.getHeight();
            if (option.getWidth() >= OUTPUT_IMAGE_SIZE
                    && option.getHeight() >= OUTPUT_IMAGE_SIZE) {
                float ratio = Math.max(option.getWidth(), option.getHeight())
                        / (float) Math.min(option.getWidth(), option.getHeight());
                float ratioDiff = Math.abs(ratio - 4f / 3f);
                if (ratioDiff < bestRatioDiff
                        || (ratioDiff == bestRatioDiff && area < bestArea)) {
                    best = option;
                    bestRatioDiff = ratioDiff;
                    bestArea = area;
                }
            }
        }
        if (bestRatioDiff != Float.MAX_VALUE) {
            return best;
        }
        bestArea = 0L;
        for (Size option : choices) {
            long area = (long) option.getWidth() * option.getHeight();
            if (area > bestArea) {
                best = option;
                bestArea = area;
            }
        }
        return best;
    }

    /**
     * 优先选择完整人脸检测模式，其次使用简单人脸框检测。
     */
    private int chooseFaceDetectMode(int[] modes) {
        if (modes == null) {
            return CameraMetadata.STATISTICS_FACE_DETECT_MODE_OFF;
        }
        int selectedMode = CameraMetadata.STATISTICS_FACE_DETECT_MODE_OFF;
        for (int mode : modes) {
            if (mode == CameraMetadata.STATISTICS_FACE_DETECT_MODE_FULL) {
                return mode;
            }
            if (mode == CameraMetadata.STATISTICS_FACE_DETECT_MODE_SIMPLE) {
                selectedMode = mode;
            }
        }
        return selectedMode;
    }

    /**
     * 创建持续预览会话，并在请求里打开系统人脸框检测。
     */
    private void createCameraPreviewSession() {
        try {
            SurfaceTexture texture = binding.textureView.getSurfaceTexture();
            if (texture == null || previewSize == null) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        binding.tvStatus.setText(R.string.face_collect_status_capture_failed);
                    }
                });
                return;
            }
            texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            Surface surface = new Surface(texture);
            previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewRequestBuilder.addTarget(surface);
            previewRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
            previewRequestBuilder.set(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            );
            previewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            if (cameraSupportsFaceDetection) {
                previewRequestBuilder.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, faceDetectMode);
            }
            cameraDevice.createCaptureSession(
                    Collections.singletonList(surface),
                    new CameraCaptureSession.StateCallback() {
                        /**
                         * 预览会话可用后开始重复请求。
                         */
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            if (cameraDevice == null) {
                                return;
                            }
                            captureSession = session;
                            try {
                                captureSession.setRepeatingRequest(
                                        previewRequestBuilder.build(),
                                        captureCallback,
                                        cameraHandler
                                );
                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        binding.btnStartCollect.setEnabled(true);
                                        binding.tvStatus.setText(cameraSupportsFaceDetection
                                                ? R.string.face_collect_status_ready
                                                : R.string.face_collect_status_unsupported);
                                    }
                                });
                            } catch (CameraAccessException e) {
                                Log.e(TAG, "Start camera preview failed", e);
                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        binding.tvStatus.setText(R.string.face_collect_status_capture_failed);
                                    }
                                });
                            }
                        }

                        /**
                         * 预览会话配置失败时展示失败状态。
                         */
                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    binding.tvStatus.setText(R.string.face_collect_status_capture_failed);
                                }
                            });
                        }
                    },
                    cameraHandler
            );
        } catch (CameraAccessException e) {
            Log.e(TAG, "Create preview session failed", e);
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    binding.tvStatus.setText(R.string.face_collect_status_capture_failed);
                }
            });
        }
    }

    /**
     * 让相机画面只覆盖圆形取景框附近区域，兼顾不露白和头像不过大。
     */
    private void configureTransform(int viewWidth, int viewHeight) {
        if (previewSize == null || viewWidth == 0 || viewHeight == 0) {
            return;
        }
        if (binding.faceFrameOverlay.getWidth() == 0 || binding.faceFrameOverlay.getHeight() == 0) {
            binding.faceFrameOverlay.post(new Runnable() {
                @Override
                public void run() {
                    configureTransform(viewWidth, viewHeight);
                }
            });
            return;
        }
        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        Matrix matrix = new Matrix();
        float centerX = viewWidth / 2f;
        float centerY = viewHeight / 2f;
        RectF targetRect = getExpandedFrameRect(viewWidth, viewHeight, FRAME_EXTRA_RATIO);
        int bufferWidth = previewSize.getWidth();
        int bufferHeight = previewSize.getHeight();
        boolean viewPortrait = viewHeight > viewWidth;
        boolean bufferLandscape = bufferWidth > bufferHeight;
        if (viewPortrait == bufferLandscape) {
            int temp = bufferWidth;
            bufferWidth = bufferHeight;
            bufferHeight = temp;
        }
        float defaultScaleX = viewWidth / (float) bufferWidth;
        float defaultScaleY = viewHeight / (float) bufferHeight;
        float centerCropScale = Math.max(
                targetRect.width() / (float) bufferWidth,
                targetRect.height() / (float) bufferHeight
        );
        matrix.setScale(
                centerCropScale / defaultScaleX,
                centerCropScale / defaultScaleY,
                centerX,
                centerY
        );
        matrix.postTranslate(targetRect.centerX() - centerX, targetRect.centerY() - centerY);
        if (rotation == Surface.ROTATION_180) {
            matrix.postRotate(180, targetRect.centerX(), targetRect.centerY());
        }
        binding.textureView.setTransform(matrix);
    }

    /**
     * 获取取景框外扩区域，预览矩阵和最终裁剪都以它为准。
     */
    private RectF getExpandedFrameRect(int viewWidth, int viewHeight, float extraRatio) {
        RectF frameRect = binding.faceFrameOverlay.getFrameRect();
        if (frameRect.isEmpty()) {
            return new RectF(0, 0, viewWidth, viewHeight);
        }
        float expandX = frameRect.width() * extraRatio;
        float expandY = frameRect.height() * extraRatio;
        frameRect.inset(-expandX, -expandY);
        frameRect.intersect(0, 0, viewWidth, viewHeight);
        return frameRect;
    }

    /**
     * 从预览结果读取人脸框，更新取景框状态和自动采集状态机。
     */
    private void handleCaptureResult(CaptureResult result) {
        if (!cameraSupportsFaceDetection) {
            return;
        }
        Face[] faces = result.get(CaptureResult.STATISTICS_FACES);
        Face bestFace = chooseBestFace(faces);
        boolean detected = bestFace != null;
        boolean tooClose = detected && isFaceTooClose(bestFace);
        boolean inside = detected && !tooClose && isFaceInsideFrame(bestFace);
        publishFaceState(detected, inside, tooClose);
    }

    /**
     * 选择面积最大且分数可用的人脸框作为当前采集目标。
     */
    private Face chooseBestFace(Face[] faces) {
        if (faces == null || faces.length == 0) {
            return null;
        }
        Face best = null;
        long bestArea = 0L;
        for (Face face : faces) {
            if (face == null || face.getScore() < 40) {
                continue;
            }
            Rect bounds = face.getBounds();
            long area = (long) bounds.width() * bounds.height();
            if (area > bestArea) {
                best = face;
                bestArea = area;
            }
        }
        return best;
    }

    /**
     * 根据系统人脸框判断人脸是否位于传感器中央区域，间接对应屏幕圆形取景框。
     */
    private boolean isFaceInsideFrame(Face face) {
        if (activeArrayRect == null || face == null) {
            return false;
        }
        Rect bounds = face.getBounds();
        float centerX = (bounds.centerX() - activeArrayRect.left) / (float) activeArrayRect.width();
        float centerY = (bounds.centerY() - activeArrayRect.top) / (float) activeArrayRect.height();
        float dx = centerX - 0.5f;
        float dy = centerY - 0.5f;
        float centerDistance = (float) Math.sqrt(dx * dx + dy * dy);
        float widthRatio = bounds.width() / (float) activeArrayRect.width();
        float heightRatio = bounds.height() / (float) activeArrayRect.height();
        return centerDistance <= 0.24f
                && widthRatio >= 0.12f
                && widthRatio <= MAX_FACE_WIDTH_RATIO
                && heightRatio >= 0.12f
                && heightRatio <= MAX_FACE_HEIGHT_RATIO;
    }

    /**
     * 判断人脸是否离镜头过近，过近时要求用户后退以降低头像占比。
     */
    private boolean isFaceTooClose(Face face) {
        if (activeArrayRect == null || face == null) {
            return false;
        }
        Rect bounds = face.getBounds();
        float widthRatio = bounds.width() / (float) activeArrayRect.width();
        float heightRatio = bounds.height() / (float) activeArrayRect.height();
        return widthRatio > MAX_FACE_WIDTH_RATIO || heightRatio > MAX_FACE_HEIGHT_RATIO;
    }

    /**
     * 节流更新 UI，避免每帧都刷新文本。
     */
    private void publishFaceState(final boolean detected, final boolean inside, final boolean tooClose) {
        long now = SystemClock.elapsedRealtime();
        if (detected == lastFaceDetected
                && inside == lastFaceInside
                && tooClose == lastFaceTooClose
                && now - lastFaceUiUpdateMs < 250L) {
            return;
        }
        lastFaceDetected = detected;
        lastFaceInside = inside;
        lastFaceTooClose = tooClose;
        lastFaceUiUpdateMs = now;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                updateFaceState(detected, inside, tooClose);
            }
        });
    }

    /**
     * 根据当前人脸位置更新边框颜色、提示语和自动采集调度。
     */
    private void updateFaceState(boolean detected, boolean inside, boolean tooClose) {
        faceInsideFrame = inside;
        binding.faceFrameOverlay.setFaceInside(inside);
        updateIdlePromptByFaceState(inside);
        if (!detected) {
            faceReadySinceMs = 0L;
            cancelScheduledCapture();
            binding.tvStatus.setText(R.string.face_collect_status_no_face);
            return;
        }
        if (tooClose) {
            faceReadySinceMs = 0L;
            cancelScheduledCapture();
            binding.tvStatus.setText(R.string.face_collect_status_too_close);
            return;
        }
        if (!inside) {
            faceReadySinceMs = 0L;
            cancelScheduledCapture();
            binding.tvStatus.setText(R.string.face_collect_status_outside);
            return;
        }
        if (faceReadySinceMs == 0L) {
            faceReadySinceMs = SystemClock.elapsedRealtime();
        }
        binding.tvStatus.setText(collecting
                ? R.string.face_collect_status_inside
                : R.string.face_collect_status_ready);
        scheduleCaptureIfReady();
    }

    /**
     * 未开始采集时同步顶部提示，采集中保留当前随机动作提示。
     */
    private void updateIdlePromptByFaceState(boolean inside) {
        if (collecting) {
            return;
        }
        binding.tvPrompt.setText(inside
                ? R.string.face_collect_prompt_ready
                : R.string.face_collect_prompt_idle);
    }

    /**
     * 人脸稳定达到阈值后调度一次预览帧保存。
     */
    private void scheduleCaptureIfReady() {
        if (!collecting || currentAction == null || !faceInsideFrame || captureScheduled) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        long faceElapsed = now - faceReadySinceMs;
        long actionElapsed = now - actionStartedMs;
        long delay = Math.max(
                Math.max(0L, FACE_STABLE_MS - faceElapsed),
                Math.max(0L, ACTION_MIN_VISIBLE_MS - actionElapsed)
        );
        captureScheduled = true;
        binding.textureView.postDelayed(captureFrameRunnable, delay);
    }

    /**
     * 取消已经排队但尚未执行的采集任务。
     */
    private void cancelScheduledCapture() {
        if (!captureScheduled) {
            return;
        }
        captureScheduled = false;
        binding.textureView.removeCallbacks(captureFrameRunnable);
    }

    /**
     * 从 TextureView 当前图片流帧生成 625x625 JPG。
     */
    private void captureCurrentFrame() {
        Bitmap frame = binding.textureView.getBitmap();
        if (frame == null) {
            failCollect(getString(R.string.face_collect_status_capture_failed));
            return;
        }
        Bitmap square = null;
        Bitmap output = null;
        try {
            square = cropBitmapByFrame(frame);
            output = Bitmap.createScaledBitmap(square, OUTPUT_IMAGE_SIZE, OUTPUT_IMAGE_SIZE, true);
            File outputFile = createOutputFile();
            saveBitmapAsJpg(output, outputFile);
            savedFiles.add(outputFile.getAbsolutePath());
            binding.tvStatus.setText(getString(
                    R.string.face_collect_status_saved,
                    savedFiles.size()
            ));
            startNextAction();
        } catch (IOException | RuntimeException e) {
            Log.e(TAG, "Capture preview frame failed", e);
            failCollect(getString(R.string.face_collect_status_capture_failed));
        } finally {
            frame.recycle();
            if (square != null && !square.isRecycled()) {
                square.recycle();
            }
            if (output != null && !output.isRecycled()) {
                output.recycle();
            }
        }
    }

    /**
     * 按屏幕取景框外扩后裁剪当前预览帧，避免 625x625 图片里人脸过满。
     */
    private Bitmap cropBitmapByFrame(Bitmap frame) {
        int overlayWidth = Math.max(1, binding.faceFrameOverlay.getWidth());
        int overlayHeight = Math.max(1, binding.faceFrameOverlay.getHeight());
        RectF collectRect = getExpandedFrameRect(overlayWidth, overlayHeight, CAPTURE_FRAME_EXTRA_RATIO);
        int left = clamp(Math.round(collectRect.left * frame.getWidth() / overlayWidth), 0, frame.getWidth() - 1);
        int top = clamp(Math.round(collectRect.top * frame.getHeight() / overlayHeight), 0, frame.getHeight() - 1);
        int right = clamp(Math.round(collectRect.right * frame.getWidth() / overlayWidth), left + 1, frame.getWidth());
        int bottom = clamp(Math.round(collectRect.bottom * frame.getHeight() / overlayHeight), top + 1, frame.getHeight());
        int width = right - left;
        int height = bottom - top;
        int side = Math.min(width, height);
        int squareLeft = left + (width - side) / 2;
        int squareTop = top + (height - side) / 2;
        return Bitmap.createBitmap(frame, squareLeft, squareTop, side, side);
    }

    /**
     * 限制数值范围，避免裁剪坐标越界。
     */
    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    /**
     * 创建当前动作对应的 JPG 输出文件。
     */
    private File createOutputFile() throws IOException {
        File directory = getCollectDirectory();
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Can not create directory: " + directory.getAbsolutePath());
        }
        String actionName = currentAction == null ? "unknown" : currentAction.fileName;
        return new File(directory, String.format(
                Locale.US,
                "face_%d_%s.jpg",
                System.currentTimeMillis(),
                actionName
        ));
    }

    /**
     * 获取人脸采集图片的应用私有目录，不需要额外存储权限。
     */
    private File getCollectDirectory() {
        File picturesDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        if (picturesDir == null) {
            picturesDir = getFilesDir();
        }
        return new File(picturesDir, "face_collect");
    }

    /**
     * 将 Bitmap 按 JPG 格式写入文件。
     */
    private void saveBitmapAsJpg(Bitmap bitmap, File outputFile) throws IOException {
        FileOutputStream outputStream = null;
        try {
            outputStream = new FileOutputStream(outputFile);
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, outputStream)) {
                throw new IOException("Compress bitmap failed");
            }
        } finally {
            if (outputStream != null) {
                outputStream.close();
            }
        }
    }

    /**
     * 释放相机会话和相机设备。
     */
    private void closeCamera() {
        if (captureSession != null) {
            captureSession.close();
            captureSession = null;
        }
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
    }

    /**
     * 单个采集动作，包含屏幕提示和文件名后缀。
     */
    private static class CollectAction {
        final String prompt;
        final String fileName;

        /**
         * 创建采集动作配置。
         */
        CollectAction(String prompt, String fileName) {
            this.prompt = prompt;
            this.fileName = fileName;
        }
    }
}
