package com.perron.movementcam.ui.record

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.HighSpeedVideoSessionConfig
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.media3.common.MediaItem
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.LinkedList
import java.util.Locale
import kotlin.math.max
import kotlin.time.Duration.Companion.nanoseconds

class RecordPreviewViewModel: ViewModel() {

    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null

    private val _viewState: MutableStateFlow<RecordPreviewViewState> = MutableStateFlow(
        RecordPreviewViewState.PreviewPreparing
    )
    val viewState: StateFlow<RecordPreviewViewState> = _viewState.asStateFlow()

    private var preview: Preview? = null
    private var amptitudeLinkedList = LinkedList<Double>()
    private var stopPending = false

    fun startCamera(context: Context, lifecycleOwner: LifecycleOwner) {
        val curRecording = recording
        if (curRecording != null) {
            Log.d(TAG, "startCamera: Stopping recording")
            // Stop the current recording session.
            curRecording.stop()
            recording = null
            _viewState.update { current ->
                (current as? RecordPreviewViewState.PreviewReady)?.copy(isRecording = false) ?: current
            }
            stopPending = false
            return
        }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            // Used to bind the lifecycle of cameras to the lifecycle owner
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Preview
            preview = Preview.Builder()
                .build()
                .apply {
                    setSurfaceProvider { surfaceRequest ->
                        _viewState.update {
                            RecordPreviewViewState.PreviewReady(surfaceRequest)
                        }
                    }
                }
            //val videoCapabilities = Recorder.getHighSpeedVideoCapabilities(cameraInfo)

            val recorder = Recorder.Builder()
                //.setQualitySelector(QualitySelector.from(Quality.HIGHEST))
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            // Select back camera as a default
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            val cameraInfo = cameraProvider.getCameraInfo(cameraSelector)
            // Create a builder for the high-speed session
            // TODO remove !! (add proper null safety check)
            val sessionConfigBuilder = HighSpeedVideoSessionConfig.Builder(videoCapture!!).setPreview(preview)
            // Query and apply a supported frame rate. Common supported frame rates include 120 and 240 fps.
            val supportedFrameRateRanges = cameraInfo.getSupportedFrameRateRanges(sessionConfigBuilder.build())
            sessionConfigBuilder.setFrameRateRange(supportedFrameRateRanges.first())

            try {
                // Unbind use cases before rebinding
                cameraProvider.unbindAll()

                // Bind use cases to camera
                cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, sessionConfigBuilder.build())
            } catch(exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun record(context: Context) {
        val curRecording = recording
        if (curRecording != null) {
            // Stop the current recording session.
            Log.d(TAG, "record: Stopping recording")
            curRecording.stop()
            recording = null
            amptitudeLinkedList.clear()
            stopPending = false
            _viewState.update { current ->
                (current as? RecordPreviewViewState.PreviewReady)?.copy(isRecording = false) ?: current
            }
            return
        }
        _viewState.update { current ->
            (current as? RecordPreviewViewState.PreviewReady)?.copy(isRecording = true) ?: current
        }
        // create and start a new recording session
        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US)
            .format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/MovementCam-Video")
            }
        }

        val mediaStoreOutputOptions = MediaStoreOutputOptions
            .Builder(context.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(contentValues)
            .build()

        try {
            // Start the recording using the VideoCapture use case
            videoCapture?.run {
                recording = this.output
                    .prepareRecording(context, mediaStoreOutputOptions)
                    .apply {
                        if (PermissionChecker.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PermissionChecker.PERMISSION_GRANTED) {
                            withAudioEnabled()
                        }
                    }
                    .start(ContextCompat.getMainExecutor(context)) { recordEvent ->
                        // Handle recording events (e.g., Start, Pause, Finalize)
                        when(recordEvent) {
                            is VideoRecordEvent.Start -> {
                                Log.d(TAG, "Starting recording")
                            }
                            is VideoRecordEvent.Status -> {
                                if (stopPending) {
                                    return@start
                                }
                                Log.v(TAG, "Recording status: ${recordEvent.recordingStats}")
                                // Access the RecordingStats and then AudioStats
                                val recordingStats = recordEvent.recordingStats
                                val audioStats = recordingStats.audioStats

                                // Now you can use the audio stats data
                                val audioAmplitude = audioStats.audioAmplitude
                                val recordedDurationSeconds = recordingStats.recordedDurationNanos.nanoseconds.inWholeSeconds

                                // Keep rolling average
                                // when latest amplitude is 30% higher then pause video
                                val isSoundSpike = detectSpike(audioAmplitude)
                                if (recordedDurationSeconds >= 1 && isSoundSpike) {
                                    Log.d(TAG, "Stopping due to sound spike")
                                    stopPending = true
                                    GlobalScope.launch(Dispatchers.Main) {
                                        delay(2000)
                                        Log.d(TAG, "Stopped due to sound spike")
                                        recording?.stop()
                                        recording = null
                                        amptitudeLinkedList.clear()
                                        stopPending = false
                                    }

                                }
                            }
                            is VideoRecordEvent.Finalize -> {
                                if (!recordEvent.hasError()) {
                                    recordEvent.recordingStats.audioStats.audioBytesRecorded
                                    val msg = "Video capture succeeded: " +
                                            "${recordEvent.outputResults.outputUri}"
                                    Toast.makeText(context, msg, Toast.LENGTH_SHORT)
                                        .show()
                                    Log.d(TAG, msg)
                                    val durationMs = recordEvent.recordingStats.recordedDurationNanos.nanoseconds.inWholeMilliseconds
                                    trimVideo(recordEvent.outputResults.outputUri, durationMs, context)
                                } else {
                                    recording?.close()
                                    recording = null
                                    Log.e(TAG, "Video capture ends with error: " +
                                            "${recordEvent.error}")
                                }
                                _viewState.update { current ->
                                    (current as? RecordPreviewViewState.PreviewReady)?.copy(isRecording = false) ?: current
                                }
                            }
                        }
                    }
            }
        } catch(exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }


    private fun detectSpike(audioAmplitude: Double) : Boolean {
        amptitudeLinkedList.add(audioAmplitude)
        if(amptitudeLinkedList.size > 10) {
            amptitudeLinkedList.removeFirst()
        }
        val slowMovingAvg = slowMovingAverage()
        val fastMovingAvg = fastMovingAverage()
        Log.v(TAG, "Slow Average: $slowMovingAvg, Fast Average: $fastMovingAvg")
        val isSpike = fastMovingAvg > slowMovingAvg * 3.0
        //val isSpike = fastMovingAvg > slowMovingAvg * 1.3
        if (isSpike) {
            Log.d(TAG, "Spike Detected: Multiple: ${fastMovingAvg/slowMovingAvg}")
        }
        return isSpike
    }

    private fun slowMovingAverage() : Double {
        return amptitudeLinkedList.average()
    }

    private fun fastMovingAverage() : Double {
        return amptitudeLinkedList.takeLast(3).average()
    }

    private fun trimVideo(videoUri: Uri, durationMs: Long, context: Context) {
        val inputMediaItem = MediaItem.fromUri(videoUri)
        val startMs = max(durationMs - 4000, 0)
        val trimmedMediaItem = inputMediaItem
            .buildUpon()
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(startMs)
                    .setEndPositionMs(durationMs)
                    .build()
            )
            .build()
        //val editedMediaItem = EditedMediaItem.Builder(trimmedMediaItem).setRemoveAudio(true).build()

        Log.d(TAG, "Trimming Video: Start: ${trimmedMediaItem.clippingConfiguration.startPositionMs} End: ${trimmedMediaItem.clippingConfiguration.endPositionMs}")

        @Suppress("BlockingMethodInNonBlockingContext")
        val outputFile = try {
            createExternalCacheFile("transformer-output-" + System.currentTimeMillis() + ".mp4", context)
        } catch (e: IOException) {
            throw IllegalStateException(e)
        }

        val transformerListener: Transformer.Listener =
            object : Transformer.Listener {
                override fun onCompleted(composition: Composition, result: ExportResult) {
                    Log.d(TAG, "Trimming Completed: $result")
                    // copy trimmed file to original location (overwrite original with trimmed)
                    context.contentResolver.openOutputStream(videoUri, "wt")?.use { outputStream ->
                        outputFile.inputStream().use { inputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                }

                override fun onError(composition: Composition, result: ExportResult,
                                     exception: ExportException
                ) {
                    Log.e(TAG, "Trimming Failed: $exception. ${exception.message}")
                }
            }
        val transformer = Transformer.Builder(context)
            .experimentalSetTrimOptimizationEnabled(true)
            .addListener(transformerListener)
            .build()
        transformer.start(trimmedMediaItem, outputFile.absolutePath)
    }

    /** Creates a cache file, resetting it if it already exists.  */
    @Throws(IOException::class)
    private fun createExternalCacheFile(fileName: String?, context: Context): File {
        val file = File(context.externalCacheDir, fileName)
        check(!(file.exists() && !file.delete())) { "Could not delete the previous export output file" }
        check(file.createNewFile()) { "Could not create the export output file" }
        Log.d(TAG, "Created export output file: " + file.absolutePath)
        return file
    }

    companion object {
        private const val TAG = "RecordPreviewViewModel"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
    }
}