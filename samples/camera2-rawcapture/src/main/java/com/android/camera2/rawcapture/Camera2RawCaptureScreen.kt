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

import android.os.Environment
import android.os.StatFs
import android.view.HapticFeedbackConstants
import android.view.OrientationEventListener
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size as layoutSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Grid3x3
import androidx.compose.material.icons.filled.GridOff
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.android.camera.core.camera2.Camera2Preview
import com.android.camera.core.permissions.CameraPermissions
import com.android.camera.coreui.controls.ShutterButton
import com.android.camera.coreui.overlay.FocusIndicator
import com.android.camera.coreui.overlay.RuleOfThirdsGrid
import com.android.camera.coreui.scaffold.CameraApi
import com.android.camera.coreui.scaffold.CameraSampleScaffold
import com.android.camera.coreui.state.LoadingView
import com.android.camera.coreui.state.UnsupportedView
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun Camera2RawCaptureScreen(
    viewModel: Camera2RawCaptureViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { viewModel.initialize() }

    val previewState = uiState as? Camera2RawCaptureUiState.Previewing
    LaunchedEffect(previewState?.message) {
        when (val message = previewState?.message) {
            is CaptureMessage.Saved -> {
                snackbarHostState.showSnackbar(
                    "${message.format.displayName} guardado en DCIM/Unprocess",
                )
                viewModel.consumeMessage()
            }

            is CaptureMessage.Failed -> {
                snackbarHostState.showSnackbar(message.text)
                viewModel.consumeMessage()
            }

            null -> Unit
        }
    }

    CameraSampleScaffold(
        permissions = CameraPermissions.PHOTO,
        api = CameraApi.CAMERA2,
        showApiBadge = false,
        rationale = stringResource(R.string.rawcapture_permission_rationale),
        deniedMessage = stringResource(R.string.rawcapture_permission_denied),
        grantButtonText = stringResource(R.string.rawcapture_grant_permission),
        settingsButtonText = stringResource(R.string.rawcapture_open_settings),
    ) {
        when (val state = uiState) {
            Camera2RawCaptureUiState.Initial -> LoadingView()

            Camera2RawCaptureUiState.Unsupported -> {
                UnsupportedView(message = stringResource(R.string.rawcapture_unsupported))
            }

            is Camera2RawCaptureUiState.Previewing -> {
                PreviewingContent(
                    state = state,
                    onCameraFacingChanged = viewModel::setCameraFacing,
                    onFormatSelected = viewModel::setCaptureFormat,
                    onFlashSelected = viewModel::setFlashEnabled,
                    onExposureModeSelected = viewModel::setExposureMode,
                    onAutoMeteringModeSelected = viewModel::setAutoMeteringMode,
                    onAutoExposureCompensationChanged = viewModel::setAutoExposureCompensationEv,
                    onAutoExposureLockedChanged = viewModel::setAutoExposureLocked,
                    onManualIsoChanged = viewModel::setManualIso,
                    onManualExposureTimeChanged = viewModel::setManualExposureTimeNs,
                    onManualFocusEnabledChanged = viewModel::setManualFocusEnabled,
                    onManualFocusDistanceChanged = viewModel::setManualFocusDistanceDiopters,
                    onFlashCompensationChanged = viewModel::setFlashExposureCompensationEv,
                    onLensPresetChanged = viewModel::setLensPreset,
                    onDigitalZoomChanged = viewModel::setDigitalZoom,
                    onCaptureStarted = viewModel::onCaptureStarted,
                    onCaptureSaved = viewModel::onCaptureSaved,
                    onCaptureFailed = viewModel::onCaptureFailed,
                    onUnsupported = viewModel::setUnsupported,
                    onOpenLastCapture = viewModel::openLastCapture,
                    snackbarHostState = snackbarHostState,
                )
            }

            is Camera2RawCaptureUiState.Editing -> {
                CaptureViewerContent(
                    capture = state.capture,
                    onBack = viewModel::backToCamera,
                )
            }
        }
    }
}

@Composable
private fun BoxScope.PreviewingContent(
    state: Camera2RawCaptureUiState.Previewing,
    onCameraFacingChanged: (CameraFacing) -> Unit,
    onFormatSelected: (CaptureFormat) -> Unit,
    onFlashSelected: (Boolean) -> Unit,
    onExposureModeSelected: (ExposureMode) -> Unit,
    onAutoMeteringModeSelected: (AutoMeteringMode) -> Unit,
    onAutoExposureCompensationChanged: (Float) -> Unit,
    onAutoExposureLockedChanged: (Boolean) -> Unit,
    onManualIsoChanged: (Int) -> Unit,
    onManualExposureTimeChanged: (Long) -> Unit,
    onManualFocusEnabledChanged: (Boolean) -> Unit,
    onManualFocusDistanceChanged: (Float) -> Unit,
    onFlashCompensationChanged: (Float) -> Unit,
    onLensPresetChanged: (Float) -> Unit,
    onDigitalZoomChanged: (Float) -> Unit,
    onCaptureStarted: () -> Unit,
    onCaptureSaved: (android.net.Uri, Int, CaptureFormat) -> Unit,
    onCaptureFailed: (String) -> Unit,
    onUnsupported: () -> Unit,
    onOpenLastCapture: () -> Unit,
    snackbarHostState: SnackbarHostState,
) {
    val context = LocalContext.current
    val view = LocalView.current
    var cameraInfo by remember { mutableStateOf<RawCameraInfo?>(null) }
    var focusTap by remember { mutableStateOf<Offset?>(null) }
    var showGrid by rememberSaveable { mutableStateOf(true) }
    var manualControlsExpanded by rememberSaveable { mutableStateOf(true) }
    var autoControlsExpanded by rememberSaveable { mutableStateOf(false) }
    var showDiagnostics by rememberSaveable { mutableStateOf(false) }
    var meteringInfo by remember { mutableStateOf<RawMeteringInfo?>(null) }
    var liveDigitalZoom by remember { mutableFloatStateOf(state.digitalZoom) }

    val controller =
        rememberCamera2RawCaptureController(
            context = context,
            cameraFacing = state.cameraFacing,
            onCaptureSaved = { uri, rotation, format ->
                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                onCaptureSaved(uri, rotation, format)
            },
            onCaptureStarted = onCaptureStarted,
            onCaptureFailed = onCaptureFailed,
            onCameraInfo = { info ->
                if (info.cameraFacing == state.cameraFacing) cameraInfo = info
            },
            onMeteringInfo = { info -> meteringInfo = info },
            onUnsupported = onUnsupported,
        )

    DisposableEffect(controller, context) {
        // The Activity is portrait-locked so the shooting UI never reflows when the phone turns.
        // Track physical device orientation separately so DNG/JPEG orientation metadata still
        // follows how the phone was actually held at the moment of capture.
        val orientationListener =
            object : OrientationEventListener(context) {
                override fun onOrientationChanged(orientation: Int) {
                    if (orientation == OrientationEventListener.ORIENTATION_UNKNOWN) return
                    val snapped = (((orientation + 45) / 90) * 90) % 360
                    controller.updatePhysicalDeviceOrientation(snapped)
                }
            }
        if (orientationListener.canDetectOrientation()) orientationListener.enable()
        onDispose {
            orientationListener.disable()
            controller.release()
        }
    }

    LaunchedEffect(state.cameraFacing) {
        cameraInfo = null
        meteringInfo = null
        focusTap = null
        liveDigitalZoom = DEFAULT_DIGITAL_ZOOM
    }

    LaunchedEffect(cameraInfo) {
        cameraInfo?.let { info ->
            if (state.lensPreset !in info.availableLensPresets) {
                onLensPresetChanged(info.activeLensPreset)
            }
            val clampedZoom = state.digitalZoom.coerceIn(1f, info.maxDigitalZoom)
            liveDigitalZoom = clampedZoom
            if (clampedZoom != state.digitalZoom) onDigitalZoomChanged(clampedZoom)
            if (!info.manualExposureAvailable && state.exposureMode == ExposureMode.MANUAL) {
                onExposureModeSelected(ExposureMode.AUTO)
            }
            val clampedIso = state.manualIso.coerceIn(info.sensitivityMin, info.sensitivityMax)
            if (clampedIso != state.manualIso) onManualIsoChanged(clampedIso)
            val clampedExposure =
                state.manualExposureTimeNs.coerceIn(
                    info.exposureTimeMinNs,
                    info.exposureTimeMaxNs,
                )
            if (clampedExposure != state.manualExposureTimeNs) {
                onManualExposureTimeChanged(clampedExposure)
            }
            val autoMinEv = maxOf(-2f, info.autoCompensationMinEv)
            val autoMaxEv = minOf(2f, info.autoCompensationMaxEv)
            if (autoMaxEv > autoMinEv) {
                val autoEv = state.autoExposureCompensationEv.coerceIn(autoMinEv, autoMaxEv)
                if (autoEv != state.autoExposureCompensationEv) {
                    onAutoExposureCompensationChanged(autoEv)
                }
            }
            if (!info.manualFocusAvailable && state.manualFocusEnabled) {
                onManualFocusEnabledChanged(false)
            }
            val focusDistance =
                state.manualFocusDistanceDiopters.coerceIn(0f, info.minimumFocusDistanceDiopters)
            if (focusDistance != state.manualFocusDistanceDiopters) {
                onManualFocusDistanceChanged(focusDistance)
            }

            val flashMinEv = info.flashCompensationMinEv.coerceAtMost(0f)
            val flashMaxEv = minOf(0f, info.flashCompensationMaxEv)
            if (flashMaxEv > flashMinEv) {
                val flashEv =
                    state.flashExposureCompensationEv.coerceIn(flashMinEv, flashMaxEv)
                if (flashEv != state.flashExposureCompensationEv) {
                    onFlashCompensationChanged(flashEv)
                }
            }
        }
    }
    LaunchedEffect(state.cameraFacing, controller) {
        controller.setCameraFacing(state.cameraFacing)
    }
    LaunchedEffect(state.lensPreset, controller) { controller.setLensPreset(state.lensPreset) }
    LaunchedEffect(state.selectedFormat, controller) {
        controller.setCaptureFormat(state.selectedFormat)
    }
    LaunchedEffect(state.flashEnabled, controller) {
        controller.setFlashEnabled(state.flashEnabled)
    }
    LaunchedEffect(state.exposureMode, controller) {
        controller.setExposureMode(state.exposureMode)
    }
    LaunchedEffect(state.autoMeteringMode, controller) {
        controller.setAutoMeteringMode(state.autoMeteringMode)
    }
    LaunchedEffect(state.autoExposureCompensationEv, controller) {
        controller.setAutoExposureCompensationEv(state.autoExposureCompensationEv)
    }
    LaunchedEffect(state.autoExposureLocked, controller) {
        controller.setAutoExposureLocked(state.autoExposureLocked)
    }
    LaunchedEffect(state.manualIso, controller) {
        controller.setManualIso(state.manualIso)
    }
    LaunchedEffect(state.manualExposureTimeNs, controller) {
        controller.setManualExposureTimeNs(state.manualExposureTimeNs)
    }
    LaunchedEffect(state.manualFocusEnabled, controller) {
        controller.setManualFocusEnabled(state.manualFocusEnabled)
    }
    LaunchedEffect(state.manualFocusDistanceDiopters, controller) {
        controller.setManualFocusDistanceDiopters(state.manualFocusDistanceDiopters)
    }
    LaunchedEffect(state.flashExposureCompensationEv, controller) {
        controller.setFlashExposureCompensationEv(state.flashExposureCompensationEv)
    }
    LaunchedEffect(state.digitalZoom, controller) {
        liveDigitalZoom = state.digitalZoom
        controller.setDigitalZoom(state.digitalZoom)
    }
    LaunchedEffect(cameraInfo?.flashAvailable) {
        if (cameraInfo?.flashAvailable == false && state.flashEnabled) {
            onFlashSelected(false)
        }
    }

    // Keep the complete RAW frame visible and keep the same control layout in every orientation.
    // Only the preview aspect follows display rotation so a landscape capture is framed correctly.
    Box(modifier = Modifier.fillMaxSize().background(Color.Black))

    BoxWithConstraints(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(top = 18.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        val streamWidth = cameraInfo?.previewWidth ?: 1440
        val streamHeight = cameraInfo?.previewHeight ?: 1080
        // Always use the portrait viewfinder geometry. The Activity itself is portrait-locked,
        // so turning the device no longer changes the UI or preview-frame layout.
        val displayedAspect = streamHeight.toFloat() / streamWidth.toFloat()
        val frameWidth = minOf(maxWidth, maxHeight * displayedAspect)
        val frameHeight = frameWidth / displayedAspect

        Box(
            modifier =
                Modifier
                    .layoutSize(frameWidth, frameHeight)
                    .background(Color.Black)
                    .clipToBounds()
                    .pointerInput(
                        cameraInfo?.activePhysicalCameraId,
                        cameraInfo?.maxDigitalZoom,
                        state.isSaving,
                    ) {
                        if (!state.isSaving) {
                            detectTransformGestures { _, _, zoomChange, _ ->
                                if (zoomChange == 1f) return@detectTransformGestures
                                val maximum = cameraInfo?.maxDigitalZoom ?: 1f
                                val updated =
                                    (liveDigitalZoom * zoomChange).coerceIn(1f, maximum)
                                liveDigitalZoom = updated
                                controller.setDigitalZoom(updated)
                                onDigitalZoomChanged(updated)
                            }
                        }
                    },
        ) {
            Camera2Preview(
                controller = controller,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            // Camera2 physical-output crop keys are not reflected by Pixel's
                            // viewfinder surface on every lens. Mirror the requested centered crop
                            // in Compose so the live frame always matches the still capture.
                            scaleX = liveDigitalZoom
                            scaleY = liveDigitalZoom
                            transformOrigin = TransformOrigin.Center
                        },
                onFocusTap = { focusTap = it },
            )

            if (showGrid) RuleOfThirdsGrid()
            FocusIndicator(tapOffset = focusTap)
        }
    }

    CaptureTopBar(
        cameraInfo = cameraInfo,
        diagnosticsSelected = showDiagnostics,
        onToggleDiagnostics = {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            showDiagnostics = !showDiagnostics
        },
    )

    val showExposureTools =
        (state.exposureMode == ExposureMode.AUTO && autoControlsExpanded) ||
            (state.exposureMode == ExposureMode.MANUAL &&
                cameraInfo?.manualExposureAvailable == true && manualControlsExpanded)

    val controlShelfShape = RoundedCornerShape(32.dp)
    Column(
        modifier =
            Modifier
                .align(Alignment.BottomCenter)
                .widthIn(max = 760.dp)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 10.dp, end = 10.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AnimatedVisibility(
            visible = showExposureTools,
            enter =
                fadeIn(animationSpec = tween(110)) +
                    slideInVertically(
                        animationSpec =
                            spring(
                                dampingRatio = Spring.DampingRatioNoBouncy,
                                stiffness = Spring.StiffnessMediumLow,
                            ),
                        initialOffsetY = { height -> height / 4 },
                    ),
            exit =
                fadeOut(animationSpec = tween(180)) +
                    slideOutVertically(
                        animationSpec = tween(210),
                        targetOffsetY = { height -> height / 3 },
                    ),
        ) {
            ExposureAdjustmentPanel(
                cameraInfo = cameraInfo,
                mode = state.exposureMode,
                autoMeteringMode = state.autoMeteringMode,
                autoExposureCompensationEv = state.autoExposureCompensationEv,
                autoExposureLocked = state.autoExposureLocked,
                meteringInfo = meteringInfo,
                flashEnabled = state.flashEnabled,
                flashCompensationEv = state.flashExposureCompensationEv,
                manualIso = state.manualIso,
                manualExposureTimeNs = state.manualExposureTimeNs,
                manualFocusEnabled = state.manualFocusEnabled,
                manualFocusDistanceDiopters = state.manualFocusDistanceDiopters,
                enabled = !state.isSaving,
                onAutoMeteringModeSelected = onAutoMeteringModeSelected,
                onAutoExposureCompensationChanged = onAutoExposureCompensationChanged,
                onAutoExposureLockedChanged = onAutoExposureLockedChanged,
                onFlashCompensationChanged = onFlashCompensationChanged,
                onManualIsoChanged = onManualIsoChanged,
                onManualExposureTimeChanged = onManualExposureTimeChanged,
                onManualFocusEnabledChanged = onManualFocusEnabledChanged,
                onManualFocusDistanceChanged = onManualFocusDistanceChanged,
            )
        }

        if (showExposureTools) Spacer(modifier = Modifier.height(9.dp))

        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(controlShelfShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.94f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            AnimatedVisibility(
                visible =
                    state.selectedFormat != CaptureFormat.JPEG &&
                        state.digitalZoom > 1.001f &&
                        cameraInfo?.croppedRawAvailable == false,
            ) {
                Text(
                    text = stringResource(R.string.rawcapture_dng_crop_unavailable),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    modifier =
                        Modifier
                            .clip(MaterialTheme.shapes.large)
                            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.94f))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FormatSelector(
                    selectedFormat = state.selectedFormat,
                    enabled = !state.isSaving,
                    modifier = Modifier.weight(0.95f),
                    onFormatSelected = { format ->
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        controller.setCaptureFormat(format)
                        onFormatSelected(format)
                    },
                )
                ExposureModeSelector(
                    cameraInfo = cameraInfo,
                    modifier = Modifier.weight(1.05f),
                    mode = state.exposureMode,
                    autoControlsExpanded = autoControlsExpanded,
                    manualControlsExpanded = manualControlsExpanded,
                    enabled = !state.isSaving,
                    onModeSelected = { mode ->
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        if (mode == ExposureMode.MANUAL) {
                            manualControlsExpanded = true
                        } else {
                            autoControlsExpanded = true
                        }
                        onExposureModeSelected(mode)
                    },
                    onSelectedAutoTapped = {
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        autoControlsExpanded = !autoControlsExpanded
                    },
                    onSelectedManualTapped = {
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        manualControlsExpanded = !manualControlsExpanded
                    },
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FlashButton(
                    enabled =
                        cameraInfo?.flashAvailable == true &&
                            state.exposureMode == ExposureMode.AUTO &&
                            !state.isSaving,
                    flashOn = state.flashEnabled,
                    onClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        onFlashSelected(!state.flashEnabled)
                    },
                )

                if ((cameraInfo?.availableLensPresets?.size ?: 0) > 1) {
                    LensSelector(
                        cameraInfo = cameraInfo,
                        selectedZoom = state.lensPreset,
                        enabled = !state.isSaving,
                        onZoomSelected = { ratio ->
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            liveDigitalZoom = DEFAULT_DIGITAL_ZOOM
                            controller.setDigitalZoom(DEFAULT_DIGITAL_ZOOM)
                            onLensPresetChanged(ratio)
                        },
                    )
                }

                GridButton(
                    enabled = showGrid,
                    onClick = { showGrid = !showGrid },
                )
            }

            Box(modifier = Modifier.fillMaxWidth()) {
                LastCaptureButton(
                    enabled = state.lastCapture != null && !state.isSaving,
                    onClick = onOpenLastCapture,
                    modifier = Modifier.align(Alignment.CenterStart),
                )

                ShutterButton(
                    onClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                        controller.capture()
                    },
                    enabled = !state.isSaving,
                    modifier = Modifier.align(Alignment.Center),
                )

                if ((cameraInfo?.availableCameraFacings?.size ?: 0) > 1) {
                    CameraFacingButton(
                        facing = state.cameraFacing,
                        enabled = !state.isSaving,
                        onClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            val nextFacing =
                                if (state.cameraFacing == CameraFacing.BACK) {
                                    CameraFacing.FRONT
                                } else {
                                    CameraFacing.BACK
                                }
                            onCameraFacingChanged(nextFacing)
                        },
                        modifier = Modifier.align(Alignment.CenterEnd),
                    )
                }
            }
        }
    }

    AnimatedVisibility(
        visible = showDiagnostics && cameraInfo != null,
        modifier =
            Modifier
                .align(Alignment.CenterStart)
                .padding(start = 10.dp, bottom = 190.dp),
    ) {
        DiagnosticsOverlay(
            cameraInfo = cameraInfo,
            meteringInfo = meteringInfo,
            exposureMode = state.exposureMode,
            digitalZoom = liveDigitalZoom,
        )
    }

    AnimatedVisibility(
        visible = state.isSaving,
        modifier = Modifier.align(Alignment.Center),
    ) {
        Row(
            modifier =
                Modifier
                    .clip(MaterialTheme.shapes.extraLarge)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.96f))
                    .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.layoutSize(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text =
                    when (state.selectedFormat) {
                        CaptureFormat.DNG -> stringResource(R.string.rawcapture_saving_dng)
                        CaptureFormat.JPEG -> stringResource(R.string.rawcapture_saving_jpeg)
                        CaptureFormat.DNG_JPEG -> "Guardando DNG + JPEG…"
                    },
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }

    SnackbarHost(
        hostState = snackbarHostState,
        modifier =
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 196.dp, start = 16.dp, end = 16.dp),
    )
}

@Composable
private fun currentLocale(): Locale {
    val configuration = LocalConfiguration.current
    return configuration.locales[0]
}

@Composable
private fun BoxScope.CaptureTopBar(
    cameraInfo: RawCameraInfo?,
    diagnosticsSelected: Boolean,
    onToggleDiagnostics: () -> Unit,
) {
    val locale = currentLocale()
    val status =
        cameraInfo?.let { info ->
            val megapixels = info.rawWidth.toLong() * info.rawHeight / 1_000_000f
            String.format(locale, "%.1f MP", megapixels)
        } ?: "RAW"

    Text(
        text = status,
        color =
            if (diagnosticsSelected) MaterialTheme.colorScheme.onSecondaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        modifier =
            Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 26.dp, end = 14.dp)
                .clip(MaterialTheme.shapes.extraLarge)
                .background(
                    if (diagnosticsSelected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.94f)
                    else MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.78f),
                )
                .clickable(onClick = onToggleDiagnostics)
                .padding(horizontal = 11.dp, vertical = 6.dp),
    )
}

@Composable
private fun ExposureModeSelector(
    cameraInfo: RawCameraInfo?,
    modifier: Modifier = Modifier,
    mode: ExposureMode,
    autoControlsExpanded: Boolean,
    manualControlsExpanded: Boolean,
    enabled: Boolean,
    onModeSelected: (ExposureMode) -> Unit,
    onSelectedAutoTapped: () -> Unit,
    onSelectedManualTapped: () -> Unit,
) {
    val manualAvailable = cameraInfo?.manualExposureAvailable == true
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.extraLarge)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ExposureMode.entries.forEach { exposureMode ->
            val selected = mode == exposureMode
            val optionEnabled =
                enabled && (exposureMode == ExposureMode.AUTO || manualAvailable)
            val label =
                when {
                    exposureMode == ExposureMode.AUTO && selected && autoControlsExpanded -> "AUTO ▼"
                    exposureMode == ExposureMode.AUTO && selected -> "AUTO ▲"
                    exposureMode == ExposureMode.AUTO -> "AUTO"
                    selected && manualControlsExpanded -> "MANUAL ▼"
                    selected -> "MANUAL ▲"
                    else -> "MANUAL"
                }
            Text(
                text = label,
                maxLines = 1,
                softWrap = false,
                color =
                    when {
                        selected -> MaterialTheme.colorScheme.onPrimaryContainer
                        optionEnabled -> MaterialTheme.colorScheme.onSurface
                        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.34f)
                    },
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelMedium,
                modifier =
                    Modifier
                        .weight(1f)
                        .clip(if (selected) MaterialTheme.shapes.large else MaterialTheme.shapes.extraLarge)
                        .background(
                            if (selected) MaterialTheme.colorScheme.primaryContainer
                            else Color.Transparent,
                        )
                        .clickable(enabled = optionEnabled) {
                            when {
                                selected && exposureMode == ExposureMode.AUTO -> onSelectedAutoTapped()
                                selected && exposureMode == ExposureMode.MANUAL -> onSelectedManualTapped()
                                else -> onModeSelected(exposureMode)
                            }
                        }
                        .padding(horizontal = 6.dp, vertical = 9.dp),
            )
        }
    }
}

@Composable
private fun ExposureAdjustmentPanel(
    cameraInfo: RawCameraInfo?,
    mode: ExposureMode,
    autoMeteringMode: AutoMeteringMode,
    autoExposureCompensationEv: Float,
    autoExposureLocked: Boolean,
    meteringInfo: RawMeteringInfo?,
    flashEnabled: Boolean,
    flashCompensationEv: Float,
    manualIso: Int,
    manualExposureTimeNs: Long,
    manualFocusEnabled: Boolean,
    manualFocusDistanceDiopters: Float,
    enabled: Boolean,
    onAutoMeteringModeSelected: (AutoMeteringMode) -> Unit,
    onAutoExposureCompensationChanged: (Float) -> Unit,
    onAutoExposureLockedChanged: (Boolean) -> Unit,
    onFlashCompensationChanged: (Float) -> Unit,
    onManualIsoChanged: (Int) -> Unit,
    onManualExposureTimeChanged: (Long) -> Unit,
    onManualFocusEnabledChanged: (Boolean) -> Unit,
    onManualFocusDistanceChanged: (Float) -> Unit,
) {
    val locale = currentLocale()
    val manualAvailable = cameraInfo?.manualExposureAvailable == true
    // The parent AnimatedVisibility owns expanded/collapsed state. Keep the panel contents alive
    // during the exit transition so collapse can actually animate instead of popping away.
    val showAutoAdjustment = mode == ExposureMode.AUTO
    val showFlashAdjustment = mode == ExposureMode.AUTO && flashEnabled
    val showManualAdjustment = mode == ExposureMode.MANUAL && manualAvailable
    if (!showAutoAdjustment && !showFlashAdjustment && !showManualAdjustment) return

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.large)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.58f))
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.08f),
                    shape = MaterialTheme.shapes.large,
                )
                .padding(horizontal = 12.dp, vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (showAutoAdjustment) {
            meteringInfo?.let { info ->
                val iso = info.autoIso
                val shutter = info.autoExposureTimeNs
                if (iso != null && shutter != null) {
                    Text(
                        text = "ISO $iso · ${formatShutterTime(shutter, locale)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Spacer(modifier = Modifier.height(3.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AutoMeteringMode.entries.forEach { meteringMode ->
                    val selected = autoMeteringMode == meteringMode
                    val label =
                        when (meteringMode) {
                            AutoMeteringMode.STANDARD -> "STANDARD"
                            AutoMeteringMode.HIGHLIGHT -> "HIGHLIGHT"
                            AutoMeteringMode.SPOT -> "SPOT"
                        }
                    Text(
                        text = label,
                        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier =
                            Modifier
                                .weight(1f)
                                .clip(if (selected) MaterialTheme.shapes.large else MaterialTheme.shapes.extraLarge)
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceContainerHighest,
                                )
                                .clickable(enabled = enabled && !autoExposureLocked) {
                                    onAutoMeteringModeSelected(meteringMode)
                                }
                                .padding(horizontal = 5.dp, vertical = 7.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.height(3.dp))
            if (cameraInfo?.aeLockAvailable == true) {
                Spacer(modifier = Modifier.height(4.dp))
                val lockEnabled = enabled && !flashEnabled
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.extraLarge)
                            .background(
                                if (autoExposureLocked) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceContainerHighest,
                            )
                            .clickable(enabled = lockEnabled) {
                                onAutoExposureLockedChanged(!autoExposureLocked)
                            }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = if (autoExposureLocked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                        contentDescription = null,
                        tint = if (autoExposureLocked) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.layoutSize(16.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (autoExposureLocked) "AE LOCKED" else "AE LOCK",
                        color = if (autoExposureLocked) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            val minEv = maxOf(-2f, cameraInfo?.autoCompensationMinEv ?: -2f)
            val maxEv = minOf(2f, cameraInfo?.autoCompensationMaxEv ?: 2f)
            val step = cameraInfo?.autoCompensationStepEv?.takeIf { it > 0f } ?: 0.333f
            val evValues = remember(minEv, maxEv, step) { exposureCompensationStops(minEv, maxEv, step) }
            if (evValues.size > 1) {
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = String.format(locale, "EV  %+.1f", autoExposureCompensationEv),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth(),
                )
                SnappyFloatSlider(
                    values = evValues,
                    value = autoExposureCompensationEv,
                    enabled = enabled && !autoExposureLocked,
                    onValueChanged = onAutoExposureCompensationChanged,
                )
            }
        }

        if (showFlashAdjustment) {
            if (showAutoAdjustment) Spacer(modifier = Modifier.height(3.dp))
            val minEv = cameraInfo?.flashCompensationMinEv?.coerceAtMost(0f) ?: -2f
            val maxEv = minOf(0f, cameraInfo?.flashCompensationMaxEv ?: 0f).coerceAtLeast(minEv)
            val supportsCompensation = maxEv > minEv
            val sliderMin = if (supportsCompensation) minEv else -2f
            val sliderMax = if (supportsCompensation) maxEv else 0f
            val step = cameraInfo?.flashCompensationStepEv?.takeIf { it > 0f } ?: 0.333f
            val flashStops =
                remember(sliderMin, sliderMax, step) {
                    exposureCompensationStops(sliderMin, sliderMax, step)
                }
            Text(
                text = String.format(locale, "FLASH  %+.1f EV", flashCompensationEv),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth(),
            )
            SnappyFloatSlider(
                values = flashStops,
                value = flashCompensationEv,
                enabled = enabled && supportsCompensation,
                onValueChanged = onFlashCompensationChanged,
            )
        }

        if (showManualAdjustment) {
            val minIso = cameraInfo?.sensitivityMin ?: DEFAULT_MANUAL_ISO
            val maxIso = cameraInfo?.sensitivityMax ?: DEFAULT_MANUAL_ISO
            val minExposure = cameraInfo?.exposureTimeMinNs ?: DEFAULT_MANUAL_EXPOSURE_TIME_NS
            val maxExposure = cameraInfo?.exposureTimeMaxNs ?: DEFAULT_MANUAL_EXPOSURE_TIME_NS
            val isoStops = remember(minIso, maxIso) { photographicIsoStops(minIso, maxIso) }
            val shutterStops = remember(minExposure, maxExposure) { photographicShutterStops(minExposure, maxExposure) }

            Spacer(modifier = Modifier.height(3.dp))
            SliderLabelValue(label = "ISO", value = manualIso.toString())
            SnappyIntSlider(
                values = isoStops,
                value = manualIso,
                enabled = enabled && isoStops.size > 1,
                onValueChanged = onManualIsoChanged,
            )

            SliderLabelValue(label = "SHUTTER", value = formatShutterTime(manualExposureTimeNs, locale))
            SnappyLongSlider(
                values = shutterStops,
                value = manualExposureTimeNs,
                enabled = enabled && shutterStops.size > 1,
                onValueChanged = onManualExposureTimeChanged,
            )

            if (cameraInfo?.manualFocusAvailable == true) {
                Spacer(modifier = Modifier.height(3.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf(false to "AF", true to "MF").forEach { (manual, label) ->
                        val selected = manualFocusEnabled == manual
                        Text(
                            text = label,
                            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelSmall,
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .clip(if (selected) MaterialTheme.shapes.large else MaterialTheme.shapes.extraLarge)
                                    .background(
                                        if (selected) MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surfaceContainerHighest,
                                    )
                                    .clickable(enabled = enabled) { onManualFocusEnabledChanged(manual) }
                                    .padding(vertical = 6.dp),
                        )
                    }
                }
                if (manualFocusEnabled) {
                    val maxFocus = cameraInfo.minimumFocusDistanceDiopters.coerceAtLeast(0f)
                    val focusStops =
                        remember(maxFocus) {
                            if (maxFocus <= 0f) listOf(0f)
                            else List(33) { index -> maxFocus * index / 32f }
                        }
                    SliderLabelValue(label = "FOCUS", value = formatFocusDistance(manualFocusDistanceDiopters, locale))
                    SnappyFloatSlider(
                        values = focusStops,
                        value = manualFocusDistanceDiopters,
                        enabled = enabled && maxFocus > 0f,
                        onValueChanged = onManualFocusDistanceChanged,
                    )
                }
            }
        }
    }
}

@Composable
private fun SliderLabelValue(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = value,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun MinimalSnappySlider(
    itemCount: Int,
    selectedIndex: Int,
    enabled: Boolean,
    onIndexChanged: (Int) -> Unit,
) {
    val view = LocalView.current
    var lastHapticIndex by remember(itemCount) { mutableStateOf(selectedIndex) }
    LaunchedEffect(selectedIndex) { lastHapticIndex = selectedIndex }

    val activeColor =
        MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else 0.38f)
    val inactiveColor =
        MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = if (enabled) 1f else 0.55f)
    val thumbColor =
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = if (enabled) 1f else 0.45f)

    Canvas(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(30.dp)
                .pointerInput(itemCount, enabled) {
                    if (!enabled || itemCount <= 1) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        fun updateFromX(x: Float) {
                            val width = this.size.width.toFloat().coerceAtLeast(1f)
                            val index =
                                ((x / width).coerceIn(0f, 1f) * (itemCount - 1))
                                    .roundToInt()
                                    .coerceIn(0, itemCount - 1)
                            if (index != lastHapticIndex) {
                                lastHapticIndex = index
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                onIndexChanged(index)
                            }
                        }
                        updateFromX(down.position.x)
                        drag(down.id) { change ->
                            updateFromX(change.position.x)
                            change.consume()
                        }
                    }
                },
    ) {
        val inset = 8.dp.toPx()
        val startX = inset
        val endX = (this.size.width - inset).coerceAtLeast(startX)
        val fraction =
            if (itemCount <= 1) 0f
            else selectedIndex.coerceIn(0, itemCount - 1).toFloat() / (itemCount - 1)
        val thumbX = startX + (endX - startX) * fraction
        val y = this.size.height / 2f
        val trackWidth = 4.dp.toPx()

        drawLine(
            color = inactiveColor,
            start = Offset(startX, y),
            end = Offset(endX, y),
            strokeWidth = trackWidth,
            cap = StrokeCap.Round,
        )
        if (thumbX > startX) {
            drawLine(
                color = activeColor,
                start = Offset(startX, y),
                end = Offset(thumbX, y),
                strokeWidth = trackWidth,
                cap = StrokeCap.Round,
            )
        }
        drawCircle(
            color = thumbColor,
            radius = 7.dp.toPx(),
            center = Offset(thumbX, y),
        )
    }
}

@Composable
private fun SnappyFloatSlider(
    values: List<Float>,
    value: Float,
    enabled: Boolean,
    onValueChanged: (Float) -> Unit,
) {
    val safeValues = values.distinct().sorted()
    val selectedIndex =
        safeValues.indices.minByOrNull { index -> abs(safeValues[index] - value) } ?: 0
    MinimalSnappySlider(
        itemCount = safeValues.size,
        selectedIndex = selectedIndex,
        enabled = enabled && safeValues.size > 1,
        onIndexChanged = { index -> safeValues.getOrNull(index)?.let(onValueChanged) },
    )
}

@Composable
private fun SnappyIntSlider(
    values: List<Int>,
    value: Int,
    enabled: Boolean,
    onValueChanged: (Int) -> Unit,
) {
    val safeValues = values.distinct().sorted()
    val selectedIndex =
        safeValues.indices.minByOrNull { index -> kotlin.math.abs(safeValues[index] - value) } ?: 0
    MinimalSnappySlider(
        itemCount = safeValues.size,
        selectedIndex = selectedIndex,
        enabled = enabled && safeValues.size > 1,
        onIndexChanged = { index -> safeValues.getOrNull(index)?.let(onValueChanged) },
    )
}

@Composable
private fun SnappyLongSlider(
    values: List<Long>,
    value: Long,
    enabled: Boolean,
    onValueChanged: (Long) -> Unit,
) {
    val safeValues = values.distinct().sorted()
    val selectedIndex =
        safeValues.indices.minByOrNull { index ->
            kotlin.math.abs(safeValues[index].toDouble() - value.toDouble())
        } ?: 0
    MinimalSnappySlider(
        itemCount = safeValues.size,
        selectedIndex = selectedIndex,
        enabled = enabled && safeValues.size > 1,
        onIndexChanged = { index -> safeValues.getOrNull(index)?.let(onValueChanged) },
    )
}

private fun exposureCompensationStops(
    minEv: Float,
    maxEv: Float,
    stepEv: Float,
): List<Float> {
    if (maxEv <= minEv) return listOf(minEv)
    val safeStep = stepEv.coerceAtLeast(0.1f)
    val values = mutableListOf<Float>()
    var index = kotlin.math.ceil(minEv / safeStep).toInt()
    val maxIndex = kotlin.math.floor(maxEv / safeStep).toInt()
    while (index <= maxIndex) {
        values += (index * safeStep).coerceIn(minEv, maxEv)
        index += 1
    }
    values += minEv
    values += maxEv
    values += 0f.coerceIn(minEv, maxEv)
    return values.distinctBy { (it * 1000f).roundToInt() }.sorted()
}

private fun photographicIsoStops(minIso: Int, maxIso: Int): List<Int> {
    if (maxIso <= minIso) return listOf(minIso)
    val standard =
        listOf(
            25, 32, 40, 50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800,
            1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400, 8000, 10000, 12800,
            16000, 20000, 25600, 32000, 40000, 51200, 64000, 80000, 102400, 128000,
        )
    return (standard.filter { it in minIso..maxIso } + minIso + maxIso).distinct().sorted()
}

private fun photographicShutterStops(minNs: Long, maxNs: Long): List<Long> {
    if (maxNs <= minNs) return listOf(minNs)
    val denominators =
        listOf(
            16000, 12800, 10000, 8000, 6400, 5000, 4000, 3200, 2500, 2000, 1600,
            1250, 1000, 800, 640, 500, 400, 320, 250, 200, 160, 125, 100, 80, 60,
            50, 40, 30, 25, 20, 15, 13, 10, 8, 6, 5, 4, 3, 2,
        )
    val fastStops = denominators.map { denominator -> 1_000_000_000L / denominator }
    val slowStops =
        listOf(
            400_000_000L,
            500_000_000L,
            600_000_000L,
            800_000_000L,
            1_000_000_000L,
        )
    return ((fastStops + slowStops).filter { it in minNs..maxNs } + minNs + maxNs)
        .distinct()
        .sorted()
}

private fun formatFocusDistance(diopters: Float, locale: Locale): String {
    if (diopters <= 0.01f) return "∞"
    val metres = 1f / diopters
    return if (metres < 1f) {
        "~${(metres * 100f).roundToInt()} cm"
    } else {
        String.format(locale, "~%.1f m", metres)
    }
}

private fun formatShutterTime(exposureTimeNs: Long, locale: Locale): String {
    val seconds = exposureTimeNs / 1_000_000_000.0
    return if (seconds >= 0.8) {
        String.format(locale, "%.1f s", seconds)
    } else {
        val denominator = (1.0 / seconds.coerceAtLeast(1e-9)).roundToInt().coerceAtLeast(1)
        "1/$denominator s"
    }
}

@Composable
private fun FormatSelector(
    selectedFormat: CaptureFormat,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onFormatSelected: (CaptureFormat) -> Unit,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.extraLarge)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        CaptureFormat.entries.forEach { format ->
            val selected = selectedFormat == format
            val label =
                when (format) {
                    CaptureFormat.DNG -> "DNG"
                    CaptureFormat.JPEG -> "JPEG"
                    CaptureFormat.DNG_JPEG -> "BOTH"
                }
            Text(
                text = label,
                maxLines = 1,
                softWrap = false,
                color =
                    if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelMedium,
                modifier =
                    Modifier
                        .weight(1f)
                        .clip(if (selected) MaterialTheme.shapes.large else MaterialTheme.shapes.extraLarge)
                        .background(
                            if (selected) MaterialTheme.colorScheme.primaryContainer
                            else Color.Transparent,
                        )
                        .clickable(enabled = enabled && !selected) { onFormatSelected(format) }
                        .padding(horizontal = 5.dp, vertical = 9.dp),
            )
        }
    }
}

@Composable
private fun LensSelector(
    cameraInfo: RawCameraInfo?,
    selectedZoom: Float,
    enabled: Boolean,
    onZoomSelected: (Float) -> Unit,
) {
    val locale = currentLocale()
    val presets = cameraInfo?.availableLensPresets ?: listOf(1f)

    Row(
        modifier =
            Modifier
                .clip(MaterialTheme.shapes.extraLarge)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        presets.forEach { ratio ->
            val selected = abs(selectedZoom - ratio) < 0.05f
            val buttonScale by animateFloatAsState(
                targetValue = if (selected) 1.025f else 1f,
                animationSpec =
                    spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                    ),
                label = "lensScale",
            )
            val horizontalPadding by animateDpAsState(
                targetValue = if (selected) 13.dp else 12.dp,
                animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy),
                label = "lensPadding",
            )
            val verticalPadding by animateDpAsState(
                targetValue = 8.dp,
                animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy),
                label = "lensVerticalPadding",
            )
            val containerColor by animateColorAsState(
                targetValue =
                    if (selected) MaterialTheme.colorScheme.primaryContainer
                    else Color.Transparent,
                animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy),
                label = "lensColor",
            )
            val contentColor by animateColorAsState(
                targetValue =
                    if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurface,
                animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy),
                label = "lensContentColor",
            )

            Text(
                text = zoomLabel(ratio, locale),
                color = contentColor,
                textAlign = TextAlign.Center,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                style = MaterialTheme.typography.labelLarge,
                modifier =
                    Modifier
                        .graphicsLayer {
                            scaleX = buttonScale
                            scaleY = buttonScale
                        }
                        .clip(if (selected) MaterialTheme.shapes.large else CircleShape)
                        .background(containerColor)
                        .clickable(enabled = enabled && !selected) { onZoomSelected(ratio) }
                        .padding(horizontal = horizontalPadding, vertical = verticalPadding),
            )
        }
    }
}

@Composable
private fun LastCaptureButton(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .layoutSize(50.dp)
                .clip(MaterialTheme.shapes.extraLarge)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Image,
            contentDescription = stringResource(R.string.rawcapture_open_last),
            tint =
                MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.34f),
            modifier = Modifier.layoutSize(22.dp),
        )
    }
}

@Composable
private fun FlashButton(
    enabled: Boolean,
    flashOn: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container =
        if (flashOn && enabled) MaterialTheme.colorScheme.tertiaryContainer
        else MaterialTheme.colorScheme.surfaceContainerHigh
    val content =
        when {
            !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.30f)
            flashOn -> MaterialTheme.colorScheme.onTertiaryContainer
            else -> MaterialTheme.colorScheme.onSurface
        }
    Box(
        modifier =
            modifier
                .layoutSize(48.dp)
                .clip(if (flashOn) MaterialTheme.shapes.large else CircleShape)
                .background(container)
                .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (flashOn) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
            contentDescription =
                stringResource(
                    if (flashOn) R.string.rawcapture_flash_disable
                    else R.string.rawcapture_flash_enable,
                ),
            tint = content,
            modifier = Modifier.layoutSize(23.dp),
        )
    }
}

@Composable
private fun CameraFacingButton(
    facing: CameraFacing,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .layoutSize(46.dp)
                .clip(MaterialTheme.shapes.extraLarge)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Cameraswitch,
            contentDescription =
                stringResource(
                    if (facing == CameraFacing.BACK) R.string.rawcapture_switch_to_front
                    else R.string.rawcapture_switch_to_back,
                ),
            tint =
                MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.30f),
            modifier = Modifier.layoutSize(22.dp),
        )
    }
}

@Composable
private fun DiagnosticsOverlay(
    cameraInfo: RawCameraInfo?,
    meteringInfo: RawMeteringInfo?,
    exposureMode: ExposureMode,
    digitalZoom: Float,
) {
    val locale = currentLocale()
    val info = cameraInfo ?: return
    val freeStorage =
        remember(info.rawWidth, info.rawHeight, locale) {
            runCatching {
                val stat = StatFs(Environment.getExternalStorageDirectory().absolutePath)
                val freeBytes = stat.availableBytes
                val freeGb = freeBytes / 1_073_741_824.0
                val roughDngBytes = info.rawWidth.toLong() * info.rawHeight.toLong() * 2L
                val roughShots =
                    if (roughDngBytes > 0L) (freeBytes / roughDngBytes).coerceAtLeast(0L) else 0L
                String.format(locale, "%.1f GB free · ~%,d RAW", freeGb, roughShots)
            }.getOrNull()
        }

    val exposureLine =
        if (exposureMode == ExposureMode.AUTO) {
            val iso = meteringInfo?.autoIso
            val shutter = meteringInfo?.autoExposureTimeNs
            if (iso != null && shutter != null) {
                "AUTO  ISO $iso · ${formatShutterTime(shutter, locale)}"
            } else {
                "AUTO"
            }
        } else {
            "MANUAL"
        }
    val opticsLine =
        buildString {
            meteringInfo?.focalLengthMm?.let {
                append(String.format(locale, "%.1f mm", it))
            }
            meteringInfo?.aperture?.let {
                if (isNotEmpty()) append(" · ")
                append(String.format(locale, "f/%.1f", it))
            }
            if (isNotEmpty()) append(" · ")
            append(String.format(locale, "%.1f×", info.activeLensPreset * digitalZoom))
        }

    Column(
        modifier =
            Modifier
                .width(230.dp)
                .clip(MaterialTheme.shapes.large)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f))
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                    shape = MaterialTheme.shapes.large,
                )
                .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = "RAW CAMERA INFO",
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = exposureLine,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
        )
        Text(
            text = opticsLine,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
        )
        Text(
            text = "${info.rawWidth}×${info.rawHeight} RAW · ${info.rawWidth * info.rawHeight / 1_000_000} MP",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
        )
        Text(
            text =
                "logical ${info.cameraId} · physical ${info.activePhysicalCameraId ?: "direct"}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
        )
        Text(
            text =
                "ISO ${info.sensitivityMin}–${info.sensitivityMax} · cropped RAW " +
                    if (info.croppedRawAvailable) "yes" else "no",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
        )
        if (freeStorage != null) {
            Text(
                text = freeStorage,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun GridButton(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .layoutSize(48.dp)
                .clip(if (enabled) MaterialTheme.shapes.large else CircleShape)
                .background(
                    if (enabled) MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerHigh,
                )
                .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (enabled) Icons.Filled.Grid3x3 else Icons.Filled.GridOff,
            contentDescription = stringResource(R.string.rawcapture_toggle_grid),
            tint =
                if (enabled) MaterialTheme.colorScheme.onSecondaryContainer
                else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.layoutSize(23.dp),
        )
    }
}

private fun zoomLabel(ratio: Float, locale: Locale): String =
    if (ratio % 1f == 0f) {
        "${ratio.toInt()}×"
    } else {
        String.format(locale, "%.1f×", ratio)
    }
