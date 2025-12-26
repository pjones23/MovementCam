package com.perron.movementcam.ui.record

import androidx.camera.core.SurfaceRequest

sealed interface RecordPreviewViewState {
    data class PreviewReady(
        val surfaceRequest: SurfaceRequest,
        val isRecording: Boolean = false
    ): RecordPreviewViewState
    data object PreviewPreparing : RecordPreviewViewState
}