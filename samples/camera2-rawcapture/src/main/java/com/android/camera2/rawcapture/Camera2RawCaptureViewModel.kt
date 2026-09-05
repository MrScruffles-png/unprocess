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
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class Camera2RawCaptureViewModel : ViewModel() {
        private val _uiState =
            MutableStateFlow<Camera2RawCaptureUiState>(Camera2RawCaptureUiState.Initial)
        val uiState: StateFlow<Camera2RawCaptureUiState> = _uiState.asStateFlow()

        fun initialize() {
            if (_uiState.value is Camera2RawCaptureUiState.Initial) {
                _uiState.value = Camera2RawCaptureUiState.Previewing()
            }
        }


        fun setCameraFacing(facing: CameraFacing) {
            val current = previewing() ?: return
            if (!current.isSaving && current.cameraFacing != facing) {
                _uiState.value =
                    current.copy(
                        cameraFacing = facing,
                        flashEnabled = false,
                        lensPreset = 1f,
                        digitalZoom = DEFAULT_DIGITAL_ZOOM,
                        manualFocusEnabled = false,
                        manualFocusDistanceDiopters = DEFAULT_MANUAL_FOCUS_DISTANCE_DIOPTERS,
                        autoExposureLocked = false,
                        message = null,
                    )
            }
        }

        fun setCaptureFormat(format: CaptureFormat) {
            val current = previewing() ?: return
            if (!current.isSaving && current.selectedFormat != format) {
                _uiState.value = current.copy(selectedFormat = format, message = null)
            }
        }

        fun setFlashEnabled(enabled: Boolean) {
            val current = previewing() ?: return
            if (!current.isSaving && current.exposureMode == ExposureMode.AUTO) {
                _uiState.value =
                    current.copy(
                        flashEnabled = enabled,
                        autoExposureLocked = if (enabled) false else current.autoExposureLocked,
                        message = null,
                    )
            }
        }

        fun setExposureMode(mode: ExposureMode) {
            val current = previewing() ?: return
            if (!current.isSaving && current.exposureMode != mode) {
                _uiState.value =
                    current.copy(
                        exposureMode = mode,
                        flashEnabled = if (mode == ExposureMode.MANUAL) false else current.flashEnabled,
                        autoExposureLocked =
                            if (mode == ExposureMode.MANUAL) false else current.autoExposureLocked,
                        message = null,
                    )
            }
        }


        fun setAutoMeteringMode(mode: AutoMeteringMode) {
            val current = previewing() ?: return
            if (!current.isSaving && current.autoMeteringMode != mode) {
                _uiState.value = current.copy(autoMeteringMode = mode, message = null)
            }
        }

        fun setAutoExposureCompensationEv(ev: Float) {
            val current = previewing() ?: return
            if (!current.isSaving) {
                _uiState.value = current.copy(autoExposureCompensationEv = ev, message = null)
            }
        }

        fun setAutoExposureLocked(locked: Boolean) {
            val current = previewing() ?: return
            if (!current.isSaving && current.exposureMode == ExposureMode.AUTO && !current.flashEnabled) {
                _uiState.value = current.copy(autoExposureLocked = locked, message = null)
            }
        }

        fun setManualIso(iso: Int) {
            val current = previewing() ?: return
            if (!current.isSaving) {
                _uiState.value = current.copy(manualIso = iso, message = null)
            }
        }

        fun setManualExposureTimeNs(exposureTimeNs: Long) {
            val current = previewing() ?: return
            if (!current.isSaving) {
                _uiState.value =
                    current.copy(manualExposureTimeNs = exposureTimeNs, message = null)
            }
        }


        fun setManualFocusEnabled(enabled: Boolean) {
            val current = previewing() ?: return
            if (!current.isSaving) {
                _uiState.value = current.copy(manualFocusEnabled = enabled, message = null)
            }
        }

        fun setManualFocusDistanceDiopters(distance: Float) {
            val current = previewing() ?: return
            if (!current.isSaving) {
                _uiState.value =
                    current.copy(manualFocusDistanceDiopters = distance.coerceAtLeast(0f), message = null)
            }
        }

        fun setFlashExposureCompensationEv(ev: Float) {
            val current = previewing() ?: return
            if (!current.isSaving) {
                _uiState.value =
                    current.copy(flashExposureCompensationEv = ev, message = null)
            }
        }

        fun setLensPreset(preset: Float) {
            val current = previewing() ?: return
            if (!current.isSaving && current.lensPreset != preset) {
                _uiState.value =
                    current.copy(
                        lensPreset = preset,
                        digitalZoom = DEFAULT_DIGITAL_ZOOM,
                        autoExposureLocked = false,
                        message = null,
                    )
            }
        }

        fun setDigitalZoom(zoom: Float) {
            val current = previewing() ?: return
            if (!current.isSaving) {
                _uiState.value = current.copy(digitalZoom = zoom.coerceAtLeast(1f), message = null)
            }
        }

        fun onCaptureStarted() {
            val current = previewing() ?: return
            _uiState.value = current.copy(isSaving = true, message = null)
        }

        fun onCaptureSaved(
            uri: Uri,
            rotationDegrees: Int,
            format: CaptureFormat,
        ) {
            val current = previewing()
            _uiState.value =
                Camera2RawCaptureUiState.Previewing(
                    isSaving = false,
                    selectedFormat = current?.selectedFormat ?: format,
                    cameraFacing = current?.cameraFacing ?: CameraFacing.BACK,
                    flashEnabled = current?.flashEnabled ?: false,
                    exposureMode = current?.exposureMode ?: ExposureMode.AUTO,
                    autoMeteringMode = current?.autoMeteringMode ?: AutoMeteringMode.STANDARD,
                    autoExposureCompensationEv = current?.autoExposureCompensationEv ?: DEFAULT_AUTO_EXPOSURE_COMPENSATION_EV,
                    autoExposureLocked = current?.autoExposureLocked ?: false,
                    manualIso = current?.manualIso ?: DEFAULT_MANUAL_ISO,
                    manualExposureTimeNs =
                        current?.manualExposureTimeNs ?: DEFAULT_MANUAL_EXPOSURE_TIME_NS,
                    manualFocusEnabled = current?.manualFocusEnabled ?: false,
                    manualFocusDistanceDiopters = current?.manualFocusDistanceDiopters ?: DEFAULT_MANUAL_FOCUS_DISTANCE_DIOPTERS,
                    flashExposureCompensationEv =
                        current?.flashExposureCompensationEv
                            ?: DEFAULT_FLASH_EXPOSURE_COMPENSATION_EV,
                    lensPreset = current?.lensPreset ?: 1f,
                    digitalZoom = current?.digitalZoom ?: DEFAULT_DIGITAL_ZOOM,
                    lastCapture = LastCapture(uri, rotationDegrees, format),
                    message = CaptureMessage.Saved(format),
                )
        }

        fun onCaptureFailed(message: String) {
            val current = previewing()
            _uiState.value =
                Camera2RawCaptureUiState.Previewing(
                    isSaving = false,
                    selectedFormat = current?.selectedFormat ?: CaptureFormat.DNG,
                    cameraFacing = current?.cameraFacing ?: CameraFacing.BACK,
                    flashEnabled = current?.flashEnabled ?: false,
                    exposureMode = current?.exposureMode ?: ExposureMode.AUTO,
                    autoMeteringMode = current?.autoMeteringMode ?: AutoMeteringMode.STANDARD,
                    autoExposureCompensationEv = current?.autoExposureCompensationEv ?: DEFAULT_AUTO_EXPOSURE_COMPENSATION_EV,
                    autoExposureLocked = current?.autoExposureLocked ?: false,
                    manualIso = current?.manualIso ?: DEFAULT_MANUAL_ISO,
                    manualExposureTimeNs =
                        current?.manualExposureTimeNs ?: DEFAULT_MANUAL_EXPOSURE_TIME_NS,
                    manualFocusEnabled = current?.manualFocusEnabled ?: false,
                    manualFocusDistanceDiopters = current?.manualFocusDistanceDiopters ?: DEFAULT_MANUAL_FOCUS_DISTANCE_DIOPTERS,
                    flashExposureCompensationEv =
                        current?.flashExposureCompensationEv
                            ?: DEFAULT_FLASH_EXPOSURE_COMPENSATION_EV,
                    lensPreset = current?.lensPreset ?: 1f,
                    digitalZoom = current?.digitalZoom ?: DEFAULT_DIGITAL_ZOOM,
                    lastCapture = current?.lastCapture,
                    message = CaptureMessage.Failed(message),
                )
        }

        fun consumeMessage() {
            val current = previewing() ?: return
            if (current.message != null) _uiState.value = current.copy(message = null)
        }

        fun openLastCapture() {
            val current = previewing() ?: return
            val capture = current.lastCapture ?: return
            _uiState.value =
                Camera2RawCaptureUiState.Editing(
                    capture = capture,
                    selectedFormat = current.selectedFormat,
                    cameraFacing = current.cameraFacing,
                    flashEnabled = current.flashEnabled,
                    exposureMode = current.exposureMode,
                    autoMeteringMode = current.autoMeteringMode,
                    autoExposureCompensationEv = current.autoExposureCompensationEv,
                    autoExposureLocked = current.autoExposureLocked,
                    manualIso = current.manualIso,
                    manualExposureTimeNs = current.manualExposureTimeNs,
                    manualFocusEnabled = current.manualFocusEnabled,
                    manualFocusDistanceDiopters = current.manualFocusDistanceDiopters,
                    flashExposureCompensationEv = current.flashExposureCompensationEv,
                    lensPreset = current.lensPreset,
                    digitalZoom = current.digitalZoom,
                )
        }

        fun backToCamera() {
            val editing = _uiState.value as? Camera2RawCaptureUiState.Editing
            _uiState.value =
                Camera2RawCaptureUiState.Previewing(
                    selectedFormat = editing?.selectedFormat ?: CaptureFormat.DNG,
                    cameraFacing = editing?.cameraFacing ?: CameraFacing.BACK,
                    flashEnabled = editing?.flashEnabled ?: false,
                    exposureMode = editing?.exposureMode ?: ExposureMode.AUTO,
                    autoMeteringMode = editing?.autoMeteringMode ?: AutoMeteringMode.STANDARD,
                    autoExposureCompensationEv = editing?.autoExposureCompensationEv ?: DEFAULT_AUTO_EXPOSURE_COMPENSATION_EV,
                    autoExposureLocked = editing?.autoExposureLocked ?: false,
                    manualIso = editing?.manualIso ?: DEFAULT_MANUAL_ISO,
                    manualExposureTimeNs =
                        editing?.manualExposureTimeNs ?: DEFAULT_MANUAL_EXPOSURE_TIME_NS,
                    manualFocusEnabled = editing?.manualFocusEnabled ?: false,
                    manualFocusDistanceDiopters = editing?.manualFocusDistanceDiopters ?: DEFAULT_MANUAL_FOCUS_DISTANCE_DIOPTERS,
                    flashExposureCompensationEv =
                        editing?.flashExposureCompensationEv
                            ?: DEFAULT_FLASH_EXPOSURE_COMPENSATION_EV,
                    lensPreset = editing?.lensPreset ?: 1f,
                    digitalZoom = editing?.digitalZoom ?: DEFAULT_DIGITAL_ZOOM,
                    lastCapture = editing?.capture,
                )
        }

        fun setUnsupported() {
            _uiState.value = Camera2RawCaptureUiState.Unsupported
        }

        private fun previewing(): Camera2RawCaptureUiState.Previewing? =
            _uiState.value as? Camera2RawCaptureUiState.Previewing
    }
