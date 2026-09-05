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
package com.android.camera.core.camera2

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.camera.viewfinder.core.TransformationInfo
import androidx.camera.viewfinder.core.ViewfinderSurfaceRequest
import androidx.camera.viewfinder.core.camera2.Camera2TransformationInfo
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.Executor
import kotlin.coroutines.resume

private const val TAG = "BaseCamera2Controller"

/**
 * Captures the Camera2 plumbing shared by every Camera2 sample: a background [HandlerThread], camera
 * discovery/open by lens facing, the viewfinder transformation math, tap-to-focus, and
 * cross-API-level capture-session creation.
 *
 * Preview is Compose-first: the controller exposes a [surfaceRequest] and [transformationInfo] as
 * state, and the [Camera2Preview] composable renders them through the Compose `Viewfinder`. When the
 * viewfinder hands back a [Surface], [onCameraOpened] is invoked so the subclass can build its
 * preview (and any extra targets such as an `ImageReader`). Subclasses may override [onCameraPrepared]
 * / [onCameraClosed] to allocate and release their own resources, and [previewSize] to request a
 * different viewfinder resolution.
 */
@Stable
abstract class BaseCamera2Controller(
    protected val context: Context,
    val isFrontCamera: Boolean,
) {
    /** The viewfinder surface request, published once the camera opens; null while closed. */
    var surfaceRequest: ViewfinderSurfaceRequest? by mutableStateOf(null)
        private set

    /** How the Compose `Viewfinder` should rotate/mirror the preview for the current device. */
    var transformationInfo: TransformationInfo by mutableStateOf(TransformationInfo.DEFAULT)
        private set

    protected val cameraManager: CameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private val backgroundThread = HandlerThread("CameraBackground").apply { start() }
    protected val backgroundHandler = Handler(backgroundThread.looper)

    protected var cameraDevice: CameraDevice? = null
    protected var captureSession: CameraCaptureSession? = null
    protected var previewRequestBuilder: CaptureRequest.Builder? = null

    /** Optional callback kept attached to repeating preview requests and tap-to-focus requests. */
    protected open val repeatingCaptureCallback: CameraCaptureSession.CaptureCallback? = null

    protected var cameraId: String = ""
    protected var currentCharacteristics: CameraCharacteristics? = null
    protected var currentDisplayRotation: Int = Surface.ROTATION_0

    private var previewSurface: Surface? = null
    private var surfaceRequestSize: Size? = null
    private var isCameraOpening: Boolean = false
    private var closingCamera: CameraDevice? = null
    private var cameraGeneration: Long = 0L
    private var cameraOpenRetryCount: Int = 0
    private var reopenAfterClose: Boolean = false
    private var reopenDelayMs: Long = 0L
    private var releaseRequested: Boolean = false
    private var clearViewfinderAfterClose: Boolean = false
    private var cameraResourcesPrepared: Boolean = false
    @Volatile private var shouldBeOpen: Boolean = false

    /** The resolution requested for the preview viewfinder. Subclasses may override. */
    protected open val previewSize: Size = Size(1920, 1080)

    /**
     * Selects the camera device to open. Subclasses can override this to prefer a device with a
     * particular capability (for example RAW or logical multi-camera support).
     */
    protected open fun selectCameraId(): String? {
        val targetFacing =
            if (isFrontCamera) {
                CameraCharacteristics.LENS_FACING_FRONT
            } else {
                CameraCharacteristics.LENS_FACING_BACK
            }
        return cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager
                .getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == targetFacing
        }
    }

    /** Updates the preview transform for the current device display rotation (a `Surface.ROTATION_*`). */
    fun updateDisplayRotation(displayRotation: Int) {
        currentDisplayRotation = displayRotation
        recomputeTransformation()
    }

    private fun recomputeTransformation() {
        val characteristics = currentCharacteristics ?: return
        val sensorRotation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val lensFacing =
            characteristics.get(CameraCharacteristics.LENS_FACING)
                ?: CameraCharacteristics.LENS_FACING_BACK
        val sign = if (lensFacing == CameraCharacteristics.LENS_FACING_FRONT) 1 else -1

        val rotationDegrees = currentDisplayRotation.toRotationDegrees()
        val relativeRotation = (sensorRotation - rotationDegrees * sign + 360) % 360

        val baseInfo = Camera2TransformationInfo.createFromCharacteristics(characteristics)
        transformationInfo =
            TransformationInfo(
                sourceRotation = relativeRotation,
                isSourceMirroredHorizontally = baseInfo.isSourceMirroredHorizontally,
                isSourceMirroredVertically = baseInfo.isSourceMirroredVertically,
                cropRectLeft = baseInfo.cropRectLeft,
                cropRectTop = baseInfo.cropRectTop,
                cropRectRight = baseInfo.cropRectRight,
                cropRectBottom = baseInfo.cropRectBottom,
            )
    }

    fun openCamera(delayMs: Long = 0L) {
        shouldBeOpen = true
        releaseRequested = false
        if (delayMs > 0L) {
            backgroundHandler.postDelayed({ openCameraLocked() }, delayMs)
        } else {
            backgroundHandler.post { openCameraLocked() }
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCameraLocked() {
        if (!shouldBeOpen) return
        if (closingCamera != null) {
            // A different camera is still releasing. Keep the desired reopen request queued until
            // that exact CameraDevice reports onClosed.
            reopenAfterClose = true
            return
        }
        if (cameraDevice != null || isCameraOpening) return

        val generation = cameraGeneration
        try {
            val id = selectCameraId() ?: return
            cameraId = id
            val characteristics = cameraManager.getCameraCharacteristics(id)
            currentCharacteristics = characteristics
            releasePreparedResourcesLocked()
            onCameraPrepared(characteristics)
            cameraResourcesPrepared = true
            recomputeTransformation()
            isCameraOpening = true

            cameraManager.openCamera(
                id,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        isCameraOpening = false

                        // An open request cannot be cancelled. If the user changed facing or left
                        // the screen while it was in flight, close this stale device immediately.
                        if (!shouldBeOpen || generation != cameraGeneration) {
                            Log.i(TAG, "Closing stale camera open for ${camera.id}")
                            closingCamera = camera
                            camera.close()
                            return
                        }

                        cameraOpenRetryCount = 0
                        cameraDevice = camera
                        val newSize = previewSize
                        if (surfaceRequest == null || surfaceRequestSize != newSize) {
                            // A new request is only necessary when the stream dimensions change.
                            // Keeping the existing Surface across front/rear switches avoids losing
                            // the viewfinder while Camera2 releases the old device.
                            if (surfaceRequestSize != null && surfaceRequestSize != newSize) {
                                previewSurface = null
                                surfaceRequest = null
                            }
                            surfaceRequestSize = newSize
                            surfaceRequest =
                                ViewfinderSurfaceRequest(newSize.width, newSize.height)
                        }
                        maybeStartSession()
                    }

                    override fun onClosed(camera: CameraDevice) {
                        if (cameraDevice === camera) cameraDevice = null
                        if (closingCamera === camera) closingCamera = null
                        isCameraOpening = false
                        releasePreparedResourcesLocked()

                        if (clearViewfinderAfterClose) {
                            clearViewfinderLocked()
                        }

                        if (releaseRequested) {
                            finishReleaseIfIdle()
                            return
                        }

                        if (shouldBeOpen && reopenAfterClose) {
                            val delay = reopenDelayMs
                            reopenAfterClose = false
                            reopenDelayMs = 0L
                            if (delay > 0L) {
                                backgroundHandler.postDelayed({ openCameraLocked() }, delay)
                            } else {
                                openCameraLocked()
                            }
                        }
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        isCameraOpening = false
                        if (cameraDevice === camera) cameraDevice = null
                        closingCamera = camera
                        camera.close()
                        if (generation == cameraGeneration && shouldBeOpen) {
                            onCameraDisconnected()
                        }
                    }

                    override fun onError(
                        camera: CameraDevice,
                        error: Int,
                    ) {
                        isCameraOpening = false
                        if (cameraDevice === camera) cameraDevice = null

                        val currentAttempt = generation == cameraGeneration && shouldBeOpen
                        val retryable =
                            currentAttempt &&
                                (error == CameraDevice.StateCallback.ERROR_CAMERA_IN_USE ||
                                    error == CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE) &&
                                cameraOpenRetryCount < 3

                        if (retryable) {
                            cameraOpenRetryCount += 1
                            reopenAfterClose = true
                            reopenDelayMs = 300L * cameraOpenRetryCount
                            Log.w(TAG, "Camera busy; retrying after onClosed in ${reopenDelayMs} ms")
                        } else if (currentAttempt) {
                            cameraOpenRetryCount = 0
                            onCameraError(error)
                        }

                        closingCamera = camera
                        camera.close()
                    }
                },
                backgroundHandler,
            )
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to open camera", e)
            isCameraOpening = false
            releasePreparedResourcesLocked()
            if (
                (e.reason == CameraAccessException.CAMERA_IN_USE ||
                    e.reason == CameraAccessException.MAX_CAMERAS_IN_USE) &&
                    scheduleCameraOpenRetry()
            ) {
                return
            }
            cameraOpenRetryCount = 0
            onCameraError(e.reason)
        }
    }

    private fun scheduleCameraOpenRetry(): Boolean {
        if (!shouldBeOpen || cameraOpenRetryCount >= 3) return false
        cameraOpenRetryCount += 1
        val delayMs = 300L * cameraOpenRetryCount
        Log.w(TAG, "Camera busy; retrying open in ${delayMs} ms")
        backgroundHandler.postDelayed({ openCameraLocked() }, delayMs)
        return true
    }

    /**
     * Runs the viewfinder surface session, called by [Camera2Preview] from the Compose `Viewfinder`
     * once a [surface] is available. Starts the preview session and keeps the surface alive until the
     * viewfinder leaves composition, then tears the session down before the surface is released.
     */
    suspend fun runViewfinderSession(surface: Surface) {
        backgroundHandler.post {
            previewSurface = surface
            maybeStartSession()
        }
        try {
            awaitCancellation()
        } finally {
            withContext(NonCancellable) {
                suspendCancellableCoroutine { continuation ->
                    backgroundHandler.post {
                        if (previewSurface === surface) {
                            try {
                                captureSession?.close()
                            } catch (e: Exception) {
                                Log.w(TAG, "Exception closing capture session", e)
                            }
                            captureSession = null
                            previewSurface = null
                        }
                        continuation.resume(Unit)
                    }
                }
            }
        }
    }

    /** Starts the preview session once both the device is open and a viewfinder surface exists. */
    private fun maybeStartSession() {
        val camera = cameraDevice ?: return
        val surface = previewSurface ?: return
        try {
            captureSession?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Exception closing previous session", e)
        }
        captureSession = null
        onCameraOpened(camera, surface)
    }

    /**
     * Rebuilds the capture session while keeping the camera device and Compose viewfinder surface
     * alive. Subclasses use this when an output must be rebound to a different physical camera.
     * Call it from [backgroundHandler] after replacing any ImageReaders used by [onCameraOpened].
     */
    protected fun restartCaptureSession() {
        maybeStartSession()
    }

    /** Allocate per-session resources (e.g. an `ImageReader`) before the device is opened. */
    protected open fun onCameraPrepared(characteristics: CameraCharacteristics) {}

    /** Build and start the preview session for the open [camera] using the viewfinder [surface]. */
    protected abstract fun onCameraOpened(
        camera: CameraDevice,
        surface: Surface,
    )

    /** Release per-session resources allocated in [onCameraPrepared]. */
    protected open fun onCameraClosed() {}

    /** Called when the camera device disconnects unexpectedly. */
    protected open fun onCameraDisconnected() {}

    /** Called when Camera2 reports a device/opening error. */
    protected open fun onCameraError(error: Int) {}

    open fun closeCamera() {
        shouldBeOpen = false
        backgroundHandler.post { closeCameraLocked() }
    }

    /**
     * Reopens the selected camera only after the current CameraDevice reports onClosed.
     * Subclasses call this after changing a camera-facing or device-selection property.
     */
    protected fun restartCameraDevice() {
        backgroundHandler.post {
            if (!shouldBeOpen) return@post
            cameraGeneration += 1L
            cameraOpenRetryCount = 0
            reopenAfterClose = true
            reopenDelayMs = 0L
            clearViewfinderAfterClose = false
            stopActivePipelineLocked()

            val device = cameraDevice
            cameraDevice = null
            if (device != null) {
                closingCamera = device
                // CameraDevice.close() closes its session. Android recommends closing the device
                // directly when changing cameras rather than destroying output Surfaces first.
                device.close()
            } else if (!isCameraOpening && closingCamera == null) {
                releasePreparedResourcesLocked()
                reopenAfterClose = false
                openCameraLocked()
            }
            // An in-flight open cannot be cancelled. Its stale onOpened callback closes it, and the
            // corresponding onClosed callback opens the currently requested camera.
        }
    }

    private fun closeCameraLocked() {
        cameraGeneration += 1L
        cameraOpenRetryCount = 0
        reopenAfterClose = false
        reopenDelayMs = 0L
        clearViewfinderAfterClose = true
        stopActivePipelineLocked()

        val device = cameraDevice
        cameraDevice = null
        if (device != null) {
            closingCamera = device
            device.close()
        } else if (!isCameraOpening && closingCamera == null) {
            releasePreparedResourcesLocked()
            clearViewfinderLocked()
            finishReleaseIfIdle()
        }
        // An in-flight open cannot be cancelled. Its stale onOpened callback closes it and onClosed
        // performs the final resource/viewfinder cleanup.
    }

    private fun stopActivePipelineLocked() {
        captureSession?.let { session ->
            runCatching { session.stopRepeating() }
            runCatching { session.abortCaptures() }
        }
        captureSession = null
        previewRequestBuilder = null
        // Do not close ImageReaders or drop the Compose Surface here. Camera2 can still reference
        // both until CameraDevice.StateCallback.onClosed arrives.
    }

    private fun releasePreparedResourcesLocked() {
        if (!cameraResourcesPrepared) return
        cameraResourcesPrepared = false
        onCameraClosed()
    }

    private fun clearViewfinderLocked() {
        previewSurface = null
        surfaceRequest = null
        surfaceRequestSize = null
        clearViewfinderAfterClose = false
    }

    private fun finishReleaseIfIdle() {
        if (
            releaseRequested &&
                !isCameraOpening &&
                closingCamera == null &&
                cameraDevice == null
        ) {
            backgroundThread.quitSafely()
        }
    }

    open fun release() {
        shouldBeOpen = false
        releaseRequested = true
        backgroundHandler.post { closeCameraLocked() }
    }

    /**
     * Creates a capture session targeting [surfaces], handling the API 28+ [SessionConfiguration]
     * path and the legacy fallback. [onConfigured] runs on the background handler.
     */
    protected fun createCaptureSession(
        camera: CameraDevice,
        surfaces: List<Surface>,
        onConfigured: (CameraCaptureSession) -> Unit,
    ) {
        val stateCallback =
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (!shouldBeOpen || cameraDevice !== camera) {
                        session.close()
                        return
                    }
                    captureSession = session
                    onConfigured(session)
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Failed to configure capture session")
                }
            }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val executor = Executor { command -> backgroundHandler.post(command) }
            val outputConfigurations =
                surfaces.map { surface ->
                    OutputConfiguration(surface).also { configureOutput(it, surface) }
                }
            val sessionConfiguration =
                SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    outputConfigurations,
                    executor,
                    stateCallback,
                )
            camera.createCaptureSession(sessionConfiguration)
        } else {
            @Suppress("DEPRECATION")
            camera.createCaptureSession(surfaces, stateCallback, null)
        }
    }

    /**
     * Hook to customize each [OutputConfiguration] before a capture session is created (the API 28+
     * path). Default is a no-op; HDR samples override it to tag the output with a 10-bit dynamic-range
     * profile via [OutputConfiguration.setDynamicRangeProfile].
     */
    protected open fun configureOutput(
        output: OutputConfiguration,
        surface: Surface,
    ) {}

    /** Whether taps on the preview should trigger AF/AE metering. */
    open fun isTapFocusEnabled(): Boolean = true

    /** Tap-to-focus: meters and triggers AF/AE at the tapped point. */
    open fun focus(
        offset: Offset,
        viewWidth: Float,
        viewHeight: Float,
    ) {
        val session = captureSession ?: return
        val builder = previewRequestBuilder ?: return

        try {
            val characteristics = currentCharacteristics ?: cameraManager.getCameraCharacteristics(cameraId)
            val sensorArraySize =
                characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return

            val y0 = (offset.x / viewWidth) * sensorArraySize.height()
            val x0 = (offset.y / viewHeight) * sensorArraySize.width()
            val halfTouchWidth = 150
            val halfTouchHeight = 150

            val focusArea =
                Rect(
                    (x0 - halfTouchWidth).toInt().coerceAtLeast(0),
                    (y0 - halfTouchHeight).toInt().coerceAtLeast(0),
                    (x0 + halfTouchWidth).toInt().coerceAtMost(sensorArraySize.width()),
                    (y0 + halfTouchHeight).toInt().coerceAtMost(sensorArraySize.height()),
                )

            try {
                session.stopRepeating()
            } catch (e: CameraAccessException) {
                Log.w(TAG, "Exception while stopping repeating", e)
            }

            val rectangle = MeteringRectangle(focusArea, MeteringRectangle.METERING_WEIGHT_MAX)
            builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(rectangle))
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(rectangle))
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)

            session.capture(builder.build(), repeatingCaptureCallback, backgroundHandler)

            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
            session.setRepeatingRequest(builder.build(), repeatingCaptureCallback, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Focus error", e)
        }
    }

    protected companion object {
        fun Int.toRotationDegrees(): Int =
            when (this) {
                Surface.ROTATION_0 -> 0
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> 0
            }
    }
}
