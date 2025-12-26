package com.perron.movementcam.ui.record

import androidx.camera.compose.CameraXViewfinder
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

@Composable
fun RecordPreview(
    viewState: RecordPreviewViewState,
    onRecord: () -> Unit,
    modifier: Modifier = Modifier

) {
    Surface(modifier = modifier.fillMaxSize()) {
        Box(modifier = modifier.fillMaxSize()) {
            (viewState as? RecordPreviewViewState.PreviewReady)?.surfaceRequest?.let {
                CameraXViewfinder(surfaceRequest = it)
            }

            Button(
                onClick = onRecord,
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                val recordBtnText = if((viewState as? RecordPreviewViewState.PreviewReady)?.isRecording == true) {
                    "stop"
                } else {
                    "record"
                }
                Text(recordBtnText)
            }
        }
    }
}