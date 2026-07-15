package com.yang.testapp.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ActivityCompat
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import kotlin.math.sqrt

/**
 * 声纹录音控制器，负责麦克风录制、实时音量计算和 16-bit PCM 文件落盘。
 */
class VoiceRecorderController(
    private val context: Context,
    private val callback: Callback
) {
    interface Callback {
        /**
         * 麦克风权限和录音能力可用。
         */
        fun onRecorderReady()

        /**
         * 麦克风初始化或录音过程失败。
         */
        fun onRecorderFailed()

        /**
         * 实时音量回调，取值范围为 0 到 1。
         */
        fun onAudioLevel(level: Float)

        /**
         * 当前段 PCM 文件保存完成。
         */
        fun onRecordingFinished(
            filePath: String,
            directoryPath: String,
            averageLevel: Float,
            peakLevel: Float,
            clippedRatio: Float
        )

        /**
         * 当前段录音失败。
         */
        fun onRecordingFailed()
    }

    private companion object {
        private const val TAG = "VoiceRecorderController"
        private const val SAMPLE_RATE_HZ = 16000
        private const val LEVEL_UPDATE_INTERVAL_MS = 50
        private const val CLIP_SAMPLE_THRESHOLD = 32000
        private const val LEVEL_NORMALIZE_GAIN = 5.0
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var recorderThread: HandlerThread? = null
    private var recorderHandler: Handler? = null
    private var audioRecord: AudioRecord? = null

    @Volatile
    private var recording = false

    /**
     * 校验录音权限和缓冲区能力。
     */
    fun prepare() {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            mainHandler.post { callback.onRecorderFailed() }
            return
        }
        val minBufferSize = getMinBufferSize()
        if (minBufferSize <= 0) {
            mainHandler.post { callback.onRecorderFailed() }
            return
        }
        mainHandler.post { callback.onRecorderReady() }
    }

    /**
     * 开始录制指定时长的原始 PCM 文件。
     */
    fun startRecording(actionFileName: String, durationMs: Long) {
        if (recording) {
            return
        }
        startRecorderThread()
        recorderHandler?.post {
            recordToPcmFile(actionFileName, durationMs)
        } ?: mainHandler.post {
            callback.onRecordingFailed()
        }
    }

    /**
     * 停止当前录音并释放录音线程。
     */
    fun stop() {
        recording = false
        stopAudioRecord()
        stopRecorderThread()
        mainHandler.post { callback.onAudioLevel(0f) }
    }

    /**
     * 启动后台录音线程。
     */
    private fun startRecorderThread() {
        if (recorderThread != null) {
            return
        }
        val thread = HandlerThread("VoiceCollectRecorder")
        thread.start()
        recorderThread = thread
        recorderHandler = Handler(thread.looper)
    }

    /**
     * 停止后台录音线程。
     */
    private fun stopRecorderThread() {
        val thread = recorderThread ?: return
        thread.quitSafely()
        if (Thread.currentThread() != thread) {
            try {
                thread.join()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        recorderThread = null
        recorderHandler = null
    }

    /**
     * 在后台线程录制 PCM，并同步计算音量统计。
     */
    @SuppressLint("MissingPermission")
    private fun recordToPcmFile(actionFileName: String, durationMs: Long) {
        val outputFile = try {
            createOutputFile(actionFileName)
        } catch (e: IOException) {
            Log.e(TAG, "Create PCM output file failed", e)
            mainHandler.post { callback.onRecordingFailed() }
            return
        }

        var outputStream: FileOutputStream? = null
        var totalSquares = 0.0
        var totalSamples = 0L
        var clippedSamples = 0L
        var peakAbs = 0
        try {
            val minBufferSize = getMinBufferSize()
            if (minBufferSize <= 0) {
                throw IOException("Invalid min buffer size: $minBufferSize")
            }
            val readBufferSamples = SAMPLE_RATE_HZ * LEVEL_UPDATE_INTERVAL_MS / 1000
            val recorderBufferSizeInBytes = maxOf(minBufferSize, readBufferSamples * 4)
            val sampleBuffer = ShortArray(readBufferSamples)
            val byteBuffer = ByteArray(sampleBuffer.size * 2)
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                recorderBufferSizeInBytes
            )
            audioRecord = recorder
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                throw IOException("AudioRecord is not initialized")
            }

            outputStream = FileOutputStream(outputFile)
            recorder.startRecording()
            recording = true
            val startMs = SystemClock.elapsedRealtime()
            while (recording && SystemClock.elapsedRealtime() - startMs < durationMs) {
                val readCount = recorder.read(sampleBuffer, 0, sampleBuffer.size)
                if (readCount <= 0) {
                    continue
                }
                writeLittleEndianPcm(outputStream, sampleBuffer, readCount, byteBuffer)
                val batchLevel = collectAudioStats(sampleBuffer, readCount) { squareSum, batchPeak, batchClipped ->
                    totalSquares += squareSum
                    totalSamples += readCount.toLong()
                    peakAbs = maxOf(peakAbs, batchPeak)
                    clippedSamples += batchClipped
                }
                mainHandler.post { callback.onAudioLevel(batchLevel) }
            }
            stopAudioRecord()

            if (totalSamples == 0L) {
                throw IOException("No PCM samples recorded")
            }
            outputStream.flush()
            outputStream.close()
            outputStream = null
            val averageRms = sqrt(totalSquares / totalSamples)
            val averageLevel = normalizeLevel(averageRms)
            val peakLevel = (peakAbs / 32768f).coerceIn(0f, 1f)
            val clippedRatio = clippedSamples.toFloat() / totalSamples.toFloat()
            mainHandler.post {
                callback.onAudioLevel(0f)
                callback.onRecordingFinished(
                    outputFile.absolutePath,
                    getCollectDirectory().absolutePath,
                    averageLevel,
                    peakLevel,
                    clippedRatio
                )
            }
        } catch (e: IOException) {
            Log.e(TAG, "Record PCM failed", e)
            outputFile.delete()
            stopAudioRecord()
            mainHandler.post { callback.onRecordingFailed() }
        } catch (e: RuntimeException) {
            Log.e(TAG, "Record PCM failed", e)
            outputFile.delete()
            stopAudioRecord()
            mainHandler.post { callback.onRecordingFailed() }
        } finally {
            try {
                outputStream?.close()
            } catch (e: IOException) {
                Log.e(TAG, "Close PCM output failed", e)
            }
            recording = false
            audioRecord = null
        }
    }

    /**
     * 将 Short 采样按 little-endian 顺序写入 raw PCM。
     */
    private fun writeLittleEndianPcm(
        outputStream: FileOutputStream,
        samples: ShortArray,
        sampleCount: Int,
        byteBuffer: ByteArray
    ) {
        for (index in 0 until sampleCount) {
            val value = samples[index].toInt()
            val byteIndex = index * 2
            byteBuffer[byteIndex] = (value and 0xFF).toByte()
            byteBuffer[byteIndex + 1] = (value shr 8 and 0xFF).toByte()
        }
        outputStream.write(byteBuffer, 0, sampleCount * 2)
    }

    /**
     * 统计一批 PCM 采样的 RMS、峰值和削波数量。
     */
    private fun collectAudioStats(
        samples: ShortArray,
        sampleCount: Int,
        collector: (Double, Int, Long) -> Unit
    ): Float {
        var squareSum = 0.0
        var peak = 0
        var clipped = 0L
        for (index in 0 until sampleCount) {
            val sample = samples[index].toInt()
            val absSample = kotlin.math.abs(sample)
            val normalized = sample / 32768.0
            squareSum += normalized * normalized
            peak = maxOf(peak, absSample)
            if (absSample >= CLIP_SAMPLE_THRESHOLD) {
                clipped++
            }
        }
        collector(squareSum, peak, clipped)
        return normalizeLevel(sqrt(squareSum / sampleCount))
    }

    /**
     * 将 RMS 映射到适合 UI 展示的 0 到 1 音量值。
     */
    private fun normalizeLevel(rms: Double): Float {
        return (rms * LEVEL_NORMALIZE_GAIN).toFloat().coerceIn(0f, 1f)
    }

    /**
     * 读取系统推荐的最小录音缓冲区大小。
     */
    private fun getMinBufferSize(): Int {
        return AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
    }

    /**
     * 创建当前朗读动作对应的 PCM 文件。
     */
    private fun createOutputFile(actionFileName: String): File {
        val directory = getCollectDirectory()
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Can not create directory: ${directory.absolutePath}")
        }
        return File(
            directory,
            String.format(Locale.US, "voice_%d_%s.pcm", System.currentTimeMillis(), actionFileName)
        )
    }

    /**
     * 获取应用私有声纹采集目录。
     */
    private fun getCollectDirectory(): File {
        val musicDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
            ?: context.filesDir
        return File(musicDir, "voice_collect")
    }

    /**
     * 停止并释放 AudioRecord。
     */
    private fun stopAudioRecord() {
        val recorder = audioRecord ?: return
        try {
            if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                recorder.stop()
            }
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Stop recorder failed", e)
        } finally {
            recorder.release()
            audioRecord = null
        }
    }
}
