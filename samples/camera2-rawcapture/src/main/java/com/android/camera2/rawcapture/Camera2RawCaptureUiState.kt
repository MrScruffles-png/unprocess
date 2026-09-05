/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.camera2.rawcapture

import android.net.Uri

const val DEFAULT_MANUAL_ISO = 100
const val DEFAULT_MANUAL_EXPOSURE_TIME_NS = 16_666_667L // 1/60 s
const val DEFAULT_FLASH_EXPOSURE_COMPENSATION_EV = 0f
const val DEFAULT_DIGITAL_ZOOM = 1f
const val DEFAULT_AUTO_EXPOSURE_COMPENSATION_EV = 0f
const val DEFAULT_MANUAL_FOCUS_DISTANCE_DIOPTERS = 0f

enum class CaptureFormat(
    val extension: String,
    val displayName: String,
) {
    DNG("dng", "DNG"),
    JPEG("jpg", "JPEG (RAW)"),
    DNG_JPEG("dng", "DNG + JPEG"),
}

enum class ExposureMode {
    AUTO,
    MANUAL,
}

enum class AutoMeteringMode {
    STANDARD,
    HIGHLIGHT,
    SPOT,
}

enum class CameraFacing {
    BACK,
    FRONT,
}

data class LastCapture(
    val uri: Uri,
    val rotationDegrees: Int,
    val format: CaptureFormat,
)

sealed interface CaptureMessage {
    data class Saved(
        val format: CaptureFormat,
    ) : CaptureMessage

    data class Failed(
        val text: String,
    ) : CaptureMessage
}

sealed interface Camera2RawCaptureUiState {
    data object Initial : Camera2RawCaptureUiState

    data class Previewing(
        val isSaving: Boolean = false,
        val selectedFormat: CaptureFormat = CaptureFormat.DNG,
        val cameraFacing: CameraFacing = CameraFacing.BACK,
        val flashEnabled: Boolean = false,
        val exposureMode: ExposureMode = ExposureMode.AUTO,
        val autoMeteringMode: AutoMeteringMode = AutoMeteringMode.STANDARD,
        val autoExposureCompensationEv: Float = DEFAULT_AUTO_EXPOSURE_COMPENSATION_EV,
        val autoExposureLocked: Boolean = false,
        val manualIso: Int = DEFAULT_MANUAL_ISO,
        val manualExposureTimeNs: Long = DEFAULT_MANUAL_EXPOSURE_TIME_NS,
        val manualFocusEnabled: Boolean = false,
        val manualFocusDistanceDiopters: Float = DEFAULT_MANUAL_FOCUS_DISTANCE_DIOPTERS,
        val flashExposureCompensationEv: Float = DEFAULT_FLASH_EXPOSURE_COMPENSATION_EV,
        val lensPreset: Float = 1f,
        val digitalZoom: Float = DEFAULT_DIGITAL_ZOOM,
        val lastCapture: LastCapture? = null,
        val message: CaptureMessage? = null,
    ) : Camera2RawCaptureUiState

    data class Editing(
        val capture: LastCapture,
        val selectedFormat: CaptureFormat,
        val cameraFacing: CameraFacing,
        val flashEnabled: Boolean,
        val exposureMode: ExposureMode,
        val autoMeteringMode: AutoMeteringMode,
        val autoExposureCompensationEv: Float,
        val autoExposureLocked: Boolean,
        val manualIso: Int,
        val manualExposureTimeNs: Long,
        val manualFocusEnabled: Boolean,
        val manualFocusDistanceDiopters: Float,
        val flashExposureCompensationEv: Float,
        val lensPreset: Float,
        val digitalZoom: Float,
    ) : Camera2RawCaptureUiState

    data object Unsupported : Camera2RawCaptureUiState
}
