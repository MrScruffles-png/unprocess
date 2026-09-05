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

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.media.ExifInterface
import android.media.Image
import android.media.ImageReader
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.roundToInt

private const val TAG = "Camera2RawCapture"
private const val CAPTURE_TIMEOUT_MILLIS = 15_000L
private const val FLASH_PRECAPTURE_FALLBACK_MILLIS = 3_000L
private const val MAX_USER_DIGITAL_ZOOM = 10f
private const val AUTO_AE_SETTLE_FALLBACK_MILLIS = 900L
private const val HIGHLIGHT_PROTECTION_EV = -1.0f

/** Hardware information shown by the simplified Pixel-oriented interface. */
data class RawCameraInfo(
    val cameraId: String,
    val cameraFacing: CameraFacing,
    val availableCameraFacings: Set<CameraFacing>,
    val rawWidth: Int,
    val rawHeight: Int,
    val previewWidth: Int,
    val previewHeight: Int,
    val availableLensPresets: List<Float>,
    val activeLensPreset: Float,
    val activePhysicalCameraId: String?,
    val isLogicalMultiCamera: Boolean,
    val flashAvailable: Boolean,
    val aeLockAvailable: Boolean,
    val manualExposureAvailable: Boolean,
    val manualFocusAvailable: Boolean,
    val minimumFocusDistanceDiopters: Float,
    val sensitivityMin: Int,
    val sensitivityMax: Int,
    val exposureTimeMinNs: Long,
    val exposureTimeMaxNs: Long,
    val autoCompensationMinEv: Float,
    val autoCompensationMaxEv: Float,
    val autoCompensationStepEv: Float,
    val flashCompensationMinEv: Float,
    val flashCompensationMaxEv: Float,
    val flashCompensationStepEv: Float,
    val maxDigitalZoom: Float,
    val croppedRawAvailable: Boolean,
)

/** Live exposure information from the preview pipeline. */
data class RawMeteringInfo(
    val autoIso: Int?,
    val autoExposureTimeNs: Long?,
    val aeState: Int?,
    val manualDeltaEv: Float?,
    val focalLengthMm: Float?,
    val aperture: Float?,
)

private data class PhysicalRawCamera(
    val id: String,
    val characteristics: CameraCharacteristics,
    val rawSize: Size,
    val focalLength: Float,
    val maxDigitalZoom: Float,
    val croppedRawAvailable: Boolean,
)

private data class LensRoute(
    val preset: Float,
    val physicalCameraId: String?,
    val characteristics: CameraCharacteristics,
    val rawSize: Size,
    val maxDigitalZoom: Float,
    val croppedRawAvailable: Boolean,
)

@Composable
fun rememberCamera2RawCaptureController(
    context: Context,
    cameraFacing: CameraFacing,
    onCaptureSaved: (uri: Uri, rotationDegrees: Int, format: CaptureFormat) -> Unit,
    onCaptureStarted: () -> Unit,
    onCaptureFailed: (message: String) -> Unit,
    onCameraInfo: (RawCameraInfo) -> Unit,
    onMeteringInfo: (RawMeteringInfo) -> Unit,
    onUnsupported: () -> Unit,
): Camera2RawCaptureController {
    val latestOnCaptureSaved by rememberUpdatedState(onCaptureSaved)
    val latestOnCaptureStarted by rememberUpdatedState(onCaptureStarted)
    val latestOnCaptureFailed by rememberUpdatedState(onCaptureFailed)
    val latestOnCameraInfo by rememberUpdatedState(onCameraInfo)
    val latestOnMeteringInfo by rememberUpdatedState(onMeteringInfo)
    val latestOnUnsupported by rememberUpdatedState(onUnsupported)

    return remember(context) {
        Camera2RawCaptureController(
            context = context,
            initialFacing = cameraFacing,
            onCaptureSaved = { uri, rotation, format ->
                latestOnCaptureSaved(uri, rotation, format)
            },
            onCaptureStarted = { latestOnCaptureStarted() },
            onCaptureFailed = { latestOnCaptureFailed(it) },
            onCameraInfo = { latestOnCameraInfo(it) },
            onMeteringInfo = { latestOnMeteringInfo(it) },
            onUnsupported = { latestOnUnsupported() },
        )
    }
}

/**
 * Pixel-oriented Camera2 controller with one capture source: RAW_SENSOR.
 *
 * DNG writes that RAW image directly through [DngCreator]. JPEG first writes the same RAW image to
 * a temporary DNG and asks Android's basic DNG decoder to render it before JPEG compression. It does
 * not request an ImageFormat.JPEG stream from the Pixel camera HAL, so Google's computational JPEG
 * pipeline is never used by this app.
 *
 * Lens presets are physical-camera routes. Preview and RAW output are explicitly assigned to the
 * selected ultra-wide, wide, or tele camera using [OutputConfiguration.setPhysicalCameraId].
 */
@Stable
class Camera2RawCaptureController(
    context: Context,
    initialFacing: CameraFacing,
    private val onCaptureSaved: (uri: Uri, rotationDegrees: Int, format: CaptureFormat) -> Unit,
    private val onCaptureStarted: () -> Unit,
    private val onCaptureFailed: (message: String) -> Unit,
    private val onCameraInfo: (RawCameraInfo) -> Unit,
    private val onMeteringInfo: (RawMeteringInfo) -> Unit,
    private val onUnsupported: () -> Unit,
) : com.android.camera.core.camera2.BaseCamera2Controller(
    context,
    isFrontCamera = initialFacing == CameraFacing.FRONT,
) {
    private var requestedFacing: CameraFacing = initialFacing
    private val mainHandler = Handler(Looper.getMainLooper())

    private var rawReader: ImageReader? = null
    private var logicalCharacteristics: CameraCharacteristics? = null
    private var lensRoutes: Map<Float, LensRoute> = emptyMap()
    private var activeRoute: LensRoute? = null
    private var selectedLensPreset = 1f
    private var selectedFormat = CaptureFormat.DNG
    private var flashEnabled = false
    private var flashAvailable = false
    private var exposureMode = ExposureMode.AUTO
    private var autoMeteringMode = AutoMeteringMode.STANDARD
    private var autoExposureCompensationEv = DEFAULT_AUTO_EXPOSURE_COMPENSATION_EV
    private var autoExposureLocked = false
    private var manualIso = DEFAULT_MANUAL_ISO
    private var manualExposureTimeNs = DEFAULT_MANUAL_EXPOSURE_TIME_NS
    private var manualFocusEnabled = false
    private var manualFocusDistanceDiopters = DEFAULT_MANUAL_FOCUS_DISTANCE_DIOPTERS
    private var flashExposureCompensationEv = DEFAULT_FLASH_EXPOSURE_COMPENSATION_EV
    private var digitalZoom = DEFAULT_DIGITAL_ZOOM
    private var requestedPreviewSize = Size(1440, 1080)
    private var activePreviewSurface: Surface? = null
    private var sensorOrientation: Int = 90
    private var lensFacing: Int = CameraCharacteristics.LENS_FACING_BACK
    @Volatile private var physicalDeviceOrientationDegrees: Int = 0

    private var isCapturing = false
    private var captureLensPreset = 1f
    private var captureFormat = CaptureFormat.DNG
    private var captureRotationDegrees = 0
    private var captureDigitalZoom = DEFAULT_DIGITAL_ZOOM
    private var captureCroppedRawAvailable = false
    private var captureCharacteristics: CameraCharacteristics? = null

    // DngCreator needs the RAW image and the CaptureResult from the same physical sensor.
    private var pendingRawImage: Image? = null
    private var pendingRawResult: CaptureResult? = null
    private var pendingRawCropZoom = 1f

    private var latestAeState: Int? = null
    private var latestAutoIso: Int? = null
    private var latestAutoExposureTimeNs: Long? = null
    private var latestFocalLengthMm: Float? = null
    private var latestAperture: Float? = null
    private var lastMeteringNotifyMs = 0L
    private var pendingAutoSettleAction: (() -> Unit)? = null

    /**
     * Updates the phone's physical orientation independently from Activity orientation. The UI is
     * intentionally portrait-locked, but capture metadata should still follow how the device was
     * held when the shutter was pressed.
     */
    fun updatePhysicalDeviceOrientation(degrees: Int) {
        physicalDeviceOrientationDegrees = (((degrees % 360) + 360) % 360 / 90) * 90
    }

    override val repeatingCaptureCallback: CameraCaptureSession.CaptureCallback =
        object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult,
            ) {
                val physicalId = activeRoute?.physicalCameraId
                val meteringResult =
                    if (physicalId != null) physicalCaptureResult(result, physicalId) else result
                processPreviewResult(
                    meteringResult,
                    logicalAeState = result.get(CaptureResult.CONTROL_AE_STATE),
                )
            }

            override fun onCaptureProgressed(
                session: CameraCaptureSession,
                request: CaptureRequest,
                partialResult: CaptureResult,
            ) {
                // Partial results do not expose per-physical-camera metadata. They are still useful
                // for AE state transitions, but keep ISO/shutter references from total results.
                latestAeState = partialResult.get(CaptureResult.CONTROL_AE_STATE) ?: latestAeState
                val pending = pendingAutoSettleAction
                if (pending != null && aeReadyForStill(latestAeState)) {
                    pendingAutoSettleAction = null
                    backgroundHandler.removeCallbacks(autoAeSettleTimeout)
                    pending()
                }
            }
        }

    override val previewSize: Size
        get() = requestedPreviewSize

    private val captureTimeout =
        Runnable {
            if (isCapturing) {
                resetPendingCapture()
                notifyFailure("La cámara ha tardado demasiado en guardar la fotografía.")
            }
        }

    private val autoAeSettleTimeout =
        Runnable {
            val action = pendingAutoSettleAction ?: return@Runnable
            pendingAutoSettleAction = null
            Log.i(TAG, "AE settle timeout; capturing with latest available metering")
            action()
        }

    private fun processPreviewResult(
        result: CaptureResult,
        logicalAeState: Int? = null,
    ) {
        val aeState = logicalAeState ?: result.get(CaptureResult.CONTROL_AE_STATE)
        latestAeState = aeState

        if (exposureMode == ExposureMode.AUTO) {
            result.get(CaptureResult.SENSOR_SENSITIVITY)?.let { latestAutoIso = it }
            result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { latestAutoExposureTimeNs = it }
        }
        result.get(CaptureResult.LENS_FOCAL_LENGTH)?.let { latestFocalLengthMm = it }
        result.get(CaptureResult.LENS_APERTURE)?.let { latestAperture = it }

        val pending = pendingAutoSettleAction
        if (pending != null && aeReadyForStill(aeState)) {
            pendingAutoSettleAction = null
            backgroundHandler.removeCallbacks(autoAeSettleTimeout)
            pending()
        }

        val now = SystemClock.elapsedRealtime()
        if (now - lastMeteringNotifyMs >= 180L) {
            lastMeteringNotifyMs = now
            notifyMeteringInfo(
                RawMeteringInfo(
                    autoIso = latestAutoIso,
                    autoExposureTimeNs = latestAutoExposureTimeNs,
                    aeState = latestAeState,
                    manualDeltaEv = manualExposureDeltaEv(),
                    focalLengthMm = latestFocalLengthMm,
                    aperture = latestAperture,
                ),
            )
        }
    }

    private fun aeReadyForStill(aeState: Int?): Boolean =
        aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
            aeState == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED ||
            aeState == CaptureResult.CONTROL_AE_STATE_LOCKED

    private fun waitForAutoExposure(action: () -> Unit) {
        if (aeReadyForStill(latestAeState)) {
            action()
            return
        }
        pendingAutoSettleAction = action
        backgroundHandler.removeCallbacks(autoAeSettleTimeout)
        backgroundHandler.postDelayed(autoAeSettleTimeout, AUTO_AE_SETTLE_FALLBACK_MILLIS)
    }

    private fun manualExposureDeltaEv(): Float? {
        val autoIso = latestAutoIso ?: return null
        val autoTime = latestAutoExposureTimeNs ?: return null
        if (autoIso <= 0 || autoTime <= 0L || manualIso <= 0 || manualExposureTimeNs <= 0L) return null
        val manualProduct = manualIso.toDouble() * manualExposureTimeNs.toDouble()
        val autoProduct = autoIso.toDouble() * autoTime.toDouble()
        if (manualProduct <= 0.0 || autoProduct <= 0.0) return null
        return log2(manualProduct / autoProduct).toFloat().coerceIn(-9.9f, 9.9f)
    }

    override fun selectCameraId(): String? {
        val targetFacing =
            if (requestedFacing == CameraFacing.FRONT) {
                CameraCharacteristics.LENS_FACING_FRONT
            } else {
                CameraCharacteristics.LENS_FACING_BACK
            }
        val selected = rawCameraCandidates(targetFacing).maxByOrNull { it.second }?.first
        if (selected == null) notifyUnsupported()
        return selected
    }

    private fun rawCameraCandidates(targetFacing: Int): List<Pair<String, Long>> =
        cameraManager.cameraIdList.mapNotNull { id ->
            val characteristics =
                runCatching { cameraManager.getCameraCharacteristics(id) }.getOrNull()
                    ?: return@mapNotNull null
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            val capabilities =
                characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                    ?: IntArray(0)
            if (
                facing != targetFacing ||
                    !capabilities.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW)
            ) {
                return@mapNotNull null
            }

            val streamMap =
                characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?: return@mapNotNull null
            val rawPixels =
                streamMap
                    .getOutputSizes(ImageFormat.RAW_SENSOR)
                    ?.maxOfOrNull { it.width.toLong() * it.height }
                    ?: 0L
            if (rawPixels == 0L) return@mapNotNull null

            val physicalCount = characteristics.physicalCameraIds.size.toLong()
            val logicalBonus =
                if (
                    capabilities.contains(
                        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA,
                    )
                ) {
                    1_000_000_000_000L + physicalCount * 10_000_000_000L
                } else {
                    0L
                }
            id to (logicalBonus + rawPixels)
        }

    private fun availableRawFacings(): Set<CameraFacing> =
        buildSet {
            if (rawCameraCandidates(CameraCharacteristics.LENS_FACING_BACK).isNotEmpty()) {
                add(CameraFacing.BACK)
            }
            if (rawCameraCandidates(CameraCharacteristics.LENS_FACING_FRONT).isNotEmpty()) {
                add(CameraFacing.FRONT)
            }
        }

    override fun onCameraPrepared(characteristics: CameraCharacteristics) {
        logicalCharacteristics = characteristics
        latestAeState = null
        latestAutoIso = null
        latestAutoExposureTimeNs = null
        lensFacing =
            characteristics.get(CameraCharacteristics.LENS_FACING)
                ?: CameraCharacteristics.LENS_FACING_BACK
        val availableAeModes =
            characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES) ?: IntArray(0)
        flashAvailable =
            characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                availableAeModes.contains(CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH)

        val capabilities =
            characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        if (!capabilities.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW)) {
            notifyUnsupported()
            return
        }

        lensRoutes = discoverLensRoutes(characteristics)
        Log.i(
            TAG,
            "RAW lens routes: " +
                lensRoutes.values.joinToString { route ->
                    "${route.preset}x=physical:${route.physicalCameraId ?: "logical"} " +
                        "maxCrop:${route.maxDigitalZoom} croppedRaw:${route.croppedRawAvailable}"
                },
        )
        if (lensRoutes.isEmpty()) {
            notifyUnsupported()
            return
        }

        activeRoute = lensRoutes[1f] ?: lensRoutes.values.minByOrNull { kotlin.math.abs(it.preset - 1f) }
        selectedLensPreset = activeRoute?.preset ?: 1f
        clampManualSettingsToRoute()
        requestedPreviewSize = choosePreviewSize(characteristics, lensRoutes.values)
        Log.i(
            TAG,
            "Preview stream ${requestedPreviewSize.width}x${requestedPreviewSize.height} " +
                "matched to the RAW frame",
        )
        prepareRawReaderForActiveRoute()
        notifyActiveCameraInfo()
    }

    private fun discoverLensRoutes(logical: CameraCharacteristics): Map<Float, LensRoute> {
        val physical =
            if (requestedFacing == CameraFacing.BACK) {
                logical.physicalCameraIds
                .mapNotNull { id ->
                    val characteristics =
                        runCatching { cameraManager.getCameraCharacteristics(id) }.getOrNull()
                            ?: return@mapNotNull null
                    val streamMap =
                        characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                            ?: return@mapNotNull null
                    val rawSize =
                        streamMap
                            .getOutputSizes(ImageFormat.RAW_SENSOR)
                            ?.maxByOrNull { it.width.toLong() * it.height }
                            ?: return@mapNotNull null
                    val focalLength =
                        characteristics
                            .get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                            ?.minOrNull()
                            ?: return@mapNotNull null
                    PhysicalRawCamera(
                        id = id,
                        characteristics = characteristics,
                        rawSize = rawSize,
                        focalLength = focalLength,
                        maxDigitalZoom = supportedMaxDigitalZoom(characteristics),
                        croppedRawAvailable =
                            supportsCroppedRaw(logical) || supportsCroppedRaw(characteristics),
                    )
                }.groupBy { (it.focalLength * 1_000f).roundToInt() }
                .values
                .map { sameFocalLength ->
                    sameFocalLength.maxBy { camera ->
                        camera.rawSize.width.toLong() * camera.rawSize.height
                    }
                }.sortedBy { it.focalLength }
            } else {
                emptyList()
            }

        if (physical.isNotEmpty()) {
            val routes = linkedMapOf<Float, LensRoute>()
            val ultra = physical.first()
            val wide = physical[physical.size / 2]
            val tele = physical.last()

            if (ultra.id != wide.id) routes[0.5f] = ultra.toRoute(0.5f)
            routes[1f] = wide.toRoute(1f)
            if (tele.id != wide.id) routes[5f] = tele.toRoute(5f)
            return routes
        }

        // Fallback for devices that advertise RAW but do not expose physical IDs separately.
        val streamMap =
            logical.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return emptyMap()
        val rawSize =
            streamMap
                .getOutputSizes(ImageFormat.RAW_SENSOR)
                ?.maxByOrNull { it.width.toLong() * it.height }
                ?: return emptyMap()
        val zoomRange = logical.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
        val routes = linkedMapOf<Float, LensRoute>()
        // Without exposed physical camera IDs, only advertise the uncropped main sensor.
        if (zoomRange == null || 1f in zoomRange) {
            routes[1f] =
                LensRoute(
                    preset = 1f,
                    physicalCameraId = null,
                    characteristics = logical,
                    rawSize = rawSize,
                    maxDigitalZoom = supportedMaxDigitalZoom(logical),
                    croppedRawAvailable = supportsCroppedRaw(logical),
                )
        }
        return routes
    }

    /**
     * Selects one efficient PRIVATE preview size shared by the logical camera and every physical
     * RAW route. The aspect ratio is matched to the RAW sensor so the viewfinder shows the same
     * composition that will be written to the DNG/JPEG file instead of a cropped 16:9 slice.
     */
    private fun choosePreviewSize(
        logical: CameraCharacteristics,
        routes: Collection<LensRoute>,
    ): Size {
        fun previewSizes(characteristics: CameraCharacteristics): Set<Size> =
            characteristics
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.PRIVATE)
                ?.toSet()
                .orEmpty()

        val allSizeSets =
            buildList {
                previewSizes(logical).takeIf { it.isNotEmpty() }?.let(::add)
                routes
                    .map { it.characteristics }
                    .distinct()
                    .forEach { characteristics ->
                        previewSizes(characteristics).takeIf { it.isNotEmpty() }?.let(::add)
                    }
            }

        val commonSizes =
            allSizeSets
                .drop(1)
                .fold(allSizeSets.firstOrNull().orEmpty()) { common, sizes -> common intersect sizes }

        val active = activeRoute
        val targetRaw = active?.rawSize ?: Size(4, 3)
        val targetRatio = targetRaw.width.toDouble() / targetRaw.height.toDouble()
        val fallbackSizes = active?.let { previewSizes(it.characteristics) }.orEmpty()
        val candidates = commonSizes.ifEmpty { fallbackSizes }.ifEmpty { setOf(Size(1440, 1080)) }
        val previewBounded =
            candidates.filter { size ->
                maxOf(size.width, size.height) <= 1920 &&
                    minOf(size.width, size.height) <= 1080
            }
        val pool = previewBounded.ifEmpty { candidates.toList() }

        return pool
            .sortedWith(
                compareBy<Size> { size ->
                    kotlin.math.abs(size.width.toDouble() / size.height.toDouble() - targetRatio)
                }.thenByDescending { size -> size.width.toLong() * size.height },
            ).first()
    }

    private fun PhysicalRawCamera.toRoute(preset: Float) =
        LensRoute(
            preset = preset,
            physicalCameraId = id,
            characteristics = characteristics,
            rawSize = rawSize,
            maxDigitalZoom = maxDigitalZoom,
            croppedRawAvailable = croppedRawAvailable,
        )

    private fun supportedMaxDigitalZoom(characteristics: CameraCharacteristics): Float {
        val scalerMaximum =
            characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        val zoomRatioMaximum =
            characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.upper ?: scalerMaximum
        return minOf(MAX_USER_DIGITAL_ZOOM, maxOf(1f, scalerMaximum, zoomRatioMaximum))
    }

    private fun supportsCroppedRaw(characteristics: CameraCharacteristics): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        val useCases =
            characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_STREAM_USE_CASES)
                ?: return false
        return useCases.contains(CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_CROPPED_RAW.toLong())
    }

    private fun prepareRawReaderForActiveRoute() {
        rawReader?.close()
        rawReader = null
        val route = activeRoute ?: return
        sensorOrientation =
            route.characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)
                ?: logicalCharacteristics?.get(CameraCharacteristics.SENSOR_ORIENTATION)
                ?: 90

        rawReader =
            ImageReader.newInstance(
                route.rawSize.width,
                route.rawSize.height,
                ImageFormat.RAW_SENSOR,
                2,
            ).apply {
                setOnImageAvailableListener(
                    { reader ->
                        val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                        if (!isCapturing) {
                            image.close()
                            return@setOnImageAvailableListener
                        }
                        pendingRawImage?.close()
                        pendingRawImage = image
                        tryWriteCapture()
                    },
                    backgroundHandler,
                )
            }
    }

    override fun configureOutput(output: OutputConfiguration, surface: Surface) {
        val route = activeRoute ?: return
        route.physicalCameraId?.let { output.setPhysicalCameraId(it) }
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                surface == rawReader?.surface &&
                route.croppedRawAvailable
        ) {
            output.setStreamUseCase(
                CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_CROPPED_RAW.toLong(),
            )
        }
    }

    override fun onCameraOpened(camera: CameraDevice, surface: Surface) {
        activePreviewSurface = surface
        val raw = rawReader
        if (raw == null) {
            notifyUnsupported()
            return
        }

        val route = activeRoute ?: return
        previewRequestBuilder =
            createRouteCaptureRequest(camera, CameraDevice.TEMPLATE_PREVIEW, route).apply {
                addTarget(surface)
                applyAutomaticControls(this)
                // Live crop is rendered in Compose. The RAW still request receives the actual
                // Camera2 crop, avoiding HAL-dependent preview behaviour and double zoom.
            }

        createCaptureSession(camera, listOf(surface, raw.surface)) {
            startRepeatingRequest()
        }
    }

    private fun startRepeatingRequest() {
        try {
            val builder = previewRequestBuilder ?: return
            val session = captureSession ?: return
            applyPreviewExposureControls(builder)
            builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
            builder.set(
                CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE,
            )
            session.setRepeatingRequest(builder.build(), repeatingCaptureCallback, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to start repeating request", e)
            notifyFailure("No se pudo iniciar la cámara.")
        }
    }

    /**
     * Switches front/rear on the same controller and camera thread.
     *
     * Keeping one controller avoids overlapping CameraDevice owners. BaseCamera2Controller closes
     * the current device and waits for CameraDevice.StateCallback.onClosed before opening the new
     * camera ID, so a stale front/rear callback cannot steal the newly selected camera.
     */
    fun setCameraFacing(facing: CameraFacing) {
        backgroundHandler.post {
            if (isCapturing || requestedFacing == facing) return@post
            requestedFacing = facing
            selectedLensPreset = 1f
            digitalZoom = DEFAULT_DIGITAL_ZOOM
            flashEnabled = false
            autoExposureLocked = false
            // Keep the current route and request state valid until the old CameraDevice has
            // actually closed. Clearing these early leaves a still-visible preview with no active
            // route, which is why manual ISO/shutter stopped responding while a switch was stuck.
            notifySwitchingCamera()
            restartCameraDevice()
        }
    }

    /** Selects a real rear camera route. Front RAW capture exposes a single 1× route. */
    fun setLensPreset(preset: Float) {
        backgroundHandler.post {
            if (isCapturing) return@post
            val route = lensRoutes[preset] ?: return@post
            val previous = activeRoute
            selectedLensPreset = route.preset
            autoExposureLocked = false
            Log.i(
                TAG,
                "Selecting ${route.preset}x: physicalCameraId=${route.physicalCameraId}, " +
                    "maxDigitalZoom=${route.maxDigitalZoom}",
            )

            if (previous?.physicalCameraId == route.physicalCameraId) {
                activeRoute = route
                digitalZoom = DEFAULT_DIGITAL_ZOOM
                clampManualSettingsToRoute()
                updatePreviewRequest()
                notifyActiveCameraInfo()
                return@post
            }

            try {
                captureSession?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Exception closing session for lens switch", e)
            }
            captureSession = null
            previewRequestBuilder = null
            activeRoute = route
            latestAeState = null
            latestAutoIso = null
            latestAutoExposureTimeNs = null
            digitalZoom = DEFAULT_DIGITAL_ZOOM
            clampManualSettingsToRoute()
            prepareRawReaderForActiveRoute()
            notifyActiveCameraInfo()
            restartCaptureSession()
        }
    }

    private fun updatePreviewRequest() {
        try {
            val builder = previewRequestBuilder ?: return
            val session = captureSession ?: return
            applyPreviewExposureControls(builder)
            session.setRepeatingRequest(builder.build(), repeatingCaptureCallback, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.w(TAG, "Failed to update preview request", e)
        } catch (e: RuntimeException) {
            // Some camera HALs reject otherwise-valid 3A combinations with
            // IllegalArgumentException/IllegalStateException. Never let a metering preference
            // terminate the app; keep the last working repeating request instead.
            Log.w(TAG, "Preview request rejected; keeping previous metering state", e)
        }
    }

    /** Switches the next capture between direct DNG and JPEG rendered from that same RAW frame. */
    fun setCaptureFormat(format: CaptureFormat) {
        backgroundHandler.post {
            if (!isCapturing) selectedFormat = format
        }
    }

    /**
     * Enables or disables flash illumination for the next still capture.
     *
     * This only changes the light emitted while exposing the RAW frame. It never requests a
     * processed JPEG stream and does not modify the captured sensor data afterwards.
     */
    fun setFlashEnabled(enabled: Boolean) {
        backgroundHandler.post {
            if (!isCapturing) {
                flashEnabled = enabled && exposureMode == ExposureMode.AUTO
                if (flashEnabled) autoExposureLocked = false
            }
        }
    }

    fun setExposureMode(mode: ExposureMode) {
        backgroundHandler.post {
            if (isCapturing) return@post
            exposureMode =
                if (
                    mode == ExposureMode.MANUAL &&
                        activeRoute != null &&
                        !manualExposureAvailableForActiveRoute()
                ) {
                    ExposureMode.AUTO
                } else {
                    mode
                }
            if (exposureMode == ExposureMode.MANUAL) {
                flashEnabled = false
                autoExposureLocked = false
            }
            updatePreviewRequest()
        }
    }

    fun setAutoMeteringMode(mode: AutoMeteringMode) {
        backgroundHandler.post {
            if (isCapturing) return@post
            if (autoMeteringMode == mode) return@post
            autoMeteringMode = mode
            resetTapMetering()
            if (exposureMode == ExposureMode.AUTO) updatePreviewRequest()
        }
    }

    fun setAutoExposureCompensationEv(ev: Float) {
        backgroundHandler.post {
            if (isCapturing) return@post
            autoExposureCompensationEv = ev
            if (exposureMode == ExposureMode.AUTO) updatePreviewRequest()
        }
    }

    fun setAutoExposureLocked(locked: Boolean) {
        backgroundHandler.post {
            if (isCapturing || exposureMode != ExposureMode.AUTO) return@post
            val available =
                logicalCharacteristics?.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) == true
            autoExposureLocked = locked && available && !flashEnabled
            updatePreviewRequest()
        }
    }

    fun setManualIso(iso: Int) {
        backgroundHandler.post {
            if (isCapturing) return@post
            manualIso = iso
            clampManualSettingsToRoute()
            if (exposureMode == ExposureMode.MANUAL) updatePreviewRequest()
        }
    }

    fun setManualExposureTimeNs(exposureTimeNs: Long) {
        backgroundHandler.post {
            if (isCapturing) return@post
            manualExposureTimeNs = exposureTimeNs
            clampManualSettingsToRoute()
            if (exposureMode == ExposureMode.MANUAL) updatePreviewRequest()
        }
    }

    fun setManualFocusEnabled(enabled: Boolean) {
        backgroundHandler.post {
            if (isCapturing) return@post
            // Preserve the requested state even if Compose delivers it before the camera route has
            // finished opening. onCameraPrepared() clamps it once capabilities are known.
            manualFocusEnabled = enabled
            if (activeRoute != null) clampManualSettingsToRoute()
            if (exposureMode == ExposureMode.MANUAL) updatePreviewRequest()
            if (activeRoute != null) notifyActiveCameraInfo()
        }
    }

    fun setManualFocusDistanceDiopters(distance: Float) {
        backgroundHandler.post {
            if (isCapturing) return@post
            manualFocusDistanceDiopters = distance.coerceAtLeast(0f)
            clampManualSettingsToRoute()
            if (exposureMode == ExposureMode.MANUAL && manualFocusEnabled) updatePreviewRequest()
        }
    }

    override fun isTapFocusEnabled(): Boolean =
        !(exposureMode == ExposureMode.MANUAL && manualFocusEnabled)

    private fun resetTapMetering() {
        val builder = previewRequestBuilder ?: return
        val session = captureSession ?: return
        val logical = logicalCharacteristics ?: return
        try {
            // AE/AF regions are logical-camera 3A controls. Do not mirror them into the physical
            // request: several Pixel HAL versions reject physical 3A-region overrides even when
            // the key appears in getAvailablePhysicalCameraRequestKeys().
            if ((logical.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0) > 0) {
                builder.set(CaptureRequest.CONTROL_AF_REGIONS, null)
            }
            if ((logical.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0) > 0) {
                builder.set(CaptureRequest.CONTROL_AE_REGIONS, null)
            }
            builder.set(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
            )
            builder.set(
                CaptureRequest.CONTROL_AF_TRIGGER,
                CameraMetadata.CONTROL_AF_TRIGGER_CANCEL,
            )
            runCatching { session.capture(builder.build(), repeatingCaptureCallback, backgroundHandler) }
                .onFailure { Log.w(TAG, "Camera rejected 3A reset capture", it) }
            builder.set(
                CaptureRequest.CONTROL_AF_TRIGGER,
                CameraMetadata.CONTROL_AF_TRIGGER_IDLE,
            )
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not reset tap metering regions", e)
        }
    }

    override fun focus(
        offset: Offset,
        viewWidth: Float,
        viewHeight: Float,
    ) {
        val session = captureSession ?: return
        val builder = previewRequestBuilder ?: return
        val logical = logicalCharacteristics ?: return
        if (viewWidth <= 0f || viewHeight <= 0f) return

        // Camera2 defines AE/AF region coordinates in the logical camera's active-array
        // coordinate system. Using a physical sensor's active-array coordinates in a logical
        // request can produce out-of-range regions and can crash vendor camera HALs.
        val activeArray =
            logical.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return

        // Keep the established viewfinder orientation mapping. Spot mode uses a tighter,
        // maximum-weight AE region; Standard/Highlight use a broader subject-weighted region.
        val sensorY =
            activeArray.top + (offset.x / viewWidth).coerceIn(0f, 1f) * activeArray.height()
        val sensorX =
            activeArray.left + (offset.y / viewHeight).coerceIn(0f, 1f) * activeArray.width()
        val spot = autoMeteringMode == AutoMeteringMode.SPOT && exposureMode == ExposureMode.AUTO
        val afHalf = if (spot) 90 else 150
        val aeHalf = if (spot) 100 else 300
        val aeWeight = if (spot) MeteringRectangle.METERING_WEIGHT_MAX else 700

        fun region(half: Int): Rect {
            val left = (sensorX - half).roundToInt().coerceIn(activeArray.left, activeArray.right - 1)
            val top = (sensorY - half).roundToInt().coerceIn(activeArray.top, activeArray.bottom - 1)
            val right =
                (sensorX + half).roundToInt().coerceIn(left + 1, activeArray.right)
            val bottom =
                (sensorY + half).roundToInt().coerceIn(top + 1, activeArray.bottom)
            return Rect(left, top, right, bottom)
        }

        val af = arrayOf(MeteringRectangle(region(afHalf), MeteringRectangle.METERING_WEIGHT_MAX))
        val ae = arrayOf(MeteringRectangle(region(aeHalf), aeWeight))
        try {
            runCatching { session.stopRepeating() }
            if ((logical.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0) > 0) {
                builder.set(CaptureRequest.CONTROL_AF_REGIONS, af)
            }
            if (
                exposureMode == ExposureMode.AUTO &&
                    (logical.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0) > 0
            ) {
                builder.set(CaptureRequest.CONTROL_AE_REGIONS, ae)
            }
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            builder.set(
                CaptureRequest.CONTROL_AF_TRIGGER,
                CameraMetadata.CONTROL_AF_TRIGGER_START,
            )
            session.capture(builder.build(), repeatingCaptureCallback, backgroundHandler)
            builder.set(
                CaptureRequest.CONTROL_AF_TRIGGER,
                CameraMetadata.CONTROL_AF_TRIGGER_IDLE,
            )
            session.setRepeatingRequest(builder.build(), repeatingCaptureCallback, backgroundHandler)
        } catch (e: Exception) {
            // A metering tap must never take down the camera UI. If a vendor HAL rejects a
            // region, clear it and resume the normal repeating preview.
            Log.w(TAG, "Tap metering failed; restoring standard 3A request", e)
            resetTapMetering()
            updatePreviewRequest()
        }
    }

    fun setFlashExposureCompensationEv(ev: Float) {
        backgroundHandler.post {
            if (!isCapturing) flashExposureCompensationEv = ev
        }
    }

    /** Applies a centered sensor crop to preview and the next capture. */
    fun setDigitalZoom(zoom: Float) {
        backgroundHandler.post {
            if (isCapturing) return@post
            val route = activeRoute ?: return@post
            val clamped = zoom.coerceIn(1f, route.maxDigitalZoom)
            if (kotlin.math.abs(clamped - digitalZoom) < 0.001f) return@post
            digitalZoom = clamped
            updatePreviewRequest()
            notifyActiveCameraInfo()
        }
    }

    /** Issues one full-resolution RAW capture. The selected output format is applied after capture. */
    fun capture() {
        backgroundHandler.post {
            if (isCapturing) return@post
            val device = cameraDevice ?: return@post
            val session = captureSession ?: return@post
            val target = rawReader?.surface ?: return@post
            val route = activeRoute ?: return@post

            isCapturing = true
            captureFormat = selectedFormat
            captureLensPreset = selectedLensPreset
            captureRotationDegrees = calculateCaptureRotationDegrees()
            captureDigitalZoom = digitalZoom.coerceIn(1f, route.maxDigitalZoom)
            captureCroppedRawAvailable = route.croppedRawAvailable
            captureCharacteristics = route.characteristics
            pendingRawImage?.close()
            pendingRawImage = null
            pendingRawResult = null
            pendingRawCropZoom = 1f
            backgroundHandler.removeCallbacks(captureTimeout)
            backgroundHandler.postDelayed(captureTimeout, CAPTURE_TIMEOUT_MILLIS)
            notifyCaptureStarted()

            val useManualExposure =
                exposureMode == ExposureMode.MANUAL && manualExposureAvailableForActiveRoute()
            if (flashEnabled && flashAvailable && !useManualExposure) {
                runFlashPrecapture(device, session, target, route)
            } else if (!useManualExposure) {
                waitForAutoExposure {
                    submitStillCapture(
                        device = device,
                        session = session,
                        target = target,
                        route = route,
                        useFlash = false,
                        useManualExposure = false,
                    )
                }
            } else {
                submitStillCapture(
                    device = device,
                    session = session,
                    target = target,
                    route = route,
                    useFlash = false,
                    useManualExposure = true,
                )
            }
        }
    }

    /**
     * Runs the AE metering sequence required before a flash still capture.
     *
     * Pixel devices can ignore an ON_ALWAYS_FLASH still request when it is submitted without this
     * precapture phase. Keeping the preview request in ON_ALWAYS_FLASH until AE leaves PRECAPTURE
     * lets the HAL meter the preflash before the RAW exposure is made.
     */
    private fun runFlashPrecapture(
        device: CameraDevice,
        session: CameraCaptureSession,
        target: Surface,
        route: LensRoute,
    ) {
        val preview = activePreviewSurface
        if (preview == null) {
            submitStillCapture(
                device,
                session,
                target,
                route,
                useFlash = true,
                useManualExposure = false,
            )
            return
        }

        val triggerTag = "unprocess-flash-precapture"
        var stillSubmitted = false
        var triggerProcessed = false
        var precaptureSeen = false
        var consecutiveReadyFrames = 0
        var lastProcessedFrameNumber = -1L

        fun submitOnce(reason: String) {
            if (stillSubmitted || !isCapturing) return
            stillSubmitted = true
            Log.i(
                TAG,
                "Scene-dependent flash metering complete ($reason); submitting RAW still",
            )
            submitStillCapture(
                device,
                session,
                target,
                route,
                useFlash = true,
                useManualExposure = false,
            )
        }

        val callback =
            object : CameraCaptureSession.CaptureCallback() {
                private fun process(request: CaptureRequest, result: CaptureResult) {
                    if (stillSubmitted || !isCapturing) return

                    // Partial and total results can describe the same frame. Count each frame once.
                    val frameNumber = result.frameNumber
                    if (frameNumber <= lastProcessedFrameNumber) return
                    lastProcessedFrameNumber = frameNumber

                    if (request.tag == triggerTag) {
                        triggerProcessed = true
                        consecutiveReadyFrames = 0
                        return
                    }
                    if (!triggerProcessed) return

                    val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                    val awbState = result.get(CaptureResult.CONTROL_AWB_STATE)

                    if (aeState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
                        precaptureSeen = true
                        consecutiveReadyFrames = 0
                        return
                    }

                    val aeReady =
                        aeState == null ||
                            aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
                            aeState == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED ||
                            aeState == CaptureResult.CONTROL_AE_STATE_LOCKED
                    val awbReady =
                        awbState == null ||
                            awbState == CaptureResult.CONTROL_AWB_STATE_CONVERGED ||
                            awbState == CaptureResult.CONTROL_AWB_STATE_LOCKED

                    if (!aeReady || !awbReady) {
                        consecutiveReadyFrames = 0
                        return
                    }

                    // PRECAPTURE is transient and Camera2 explicitly allows a HAL to omit it. When
                    // it was reported, the first settled frame after it is enough. Otherwise require
                    // three consecutive settled frames after the trigger so the trigger's old AE
                    // result cannot be mistaken for completed flash metering.
                    consecutiveReadyFrames += 1
                    if (precaptureSeen || consecutiveReadyFrames >= 3) {
                        submitOnce(
                            if (precaptureSeen) {
                                "preflash complete; AE=$aeState AWB=$awbState"
                            } else {
                                "PRECAPTURE omitted; 3 settled frames; AE=$aeState AWB=$awbState"
                            },
                        )
                    }
                }

                override fun onCaptureProgressed(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    partialResult: CaptureResult,
                ) {
                    process(request, partialResult)
                }

                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult,
                ) {
                    process(request, result)
                }

                override fun onCaptureFailed(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    failure: CaptureFailure,
                ) {
                    if (request.tag == triggerTag) {
                        triggerProcessed = true
                        submitOnce("precapture request failed")
                    }
                }
            }

        try {
            val triggerRequest =
                createRouteCaptureRequest(device, CameraDevice.TEMPLATE_PREVIEW, route).apply {
                    addTarget(preview)
                    applyAutomaticControls(this)
                    applyRouteZoom(this)
                    set(
                        CaptureRequest.CONTROL_AE_MODE,
                        CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH,
                    )
                    applyFlashExposureCompensation(this)
                    set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                    set(
                        CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                        CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START,
                    )
                    setTag(triggerTag)
                }
            session.capture(triggerRequest.build(), callback, backgroundHandler)

            val meteringBuilder = previewRequestBuilder
            if (meteringBuilder != null) {
                applyAutomaticControls(meteringBuilder)
                applyRouteZoom(meteringBuilder)
                meteringBuilder.set(
                    CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH,
                )
                applyFlashExposureCompensation(meteringBuilder)
                meteringBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                meteringBuilder.set(
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE,
                )
                meteringBuilder.setTag(null)
                session.setRepeatingRequest(meteringBuilder.build(), callback, backgroundHandler)
            }

            backgroundHandler.postDelayed(
                { submitOnce("timeout fallback") },
                FLASH_PRECAPTURE_FALLBACK_MILLIS,
            )
        } catch (e: CameraAccessException) {
            Log.w(TAG, "Flash precapture failed; attempting the flash still directly", e)
            submitOnce("CameraAccessException")
        }
    }

    private fun submitStillCapture(
        device: CameraDevice,
        session: CameraCaptureSession,
        target: Surface,
        route: LensRoute,
        useFlash: Boolean,
        useManualExposure: Boolean,
    ) {
        if (!isCapturing) return
        try {
            runCatching { session.stopRepeating() }
            val captureBuilder =
                createRouteCaptureRequest(device, CameraDevice.TEMPLATE_STILL_CAPTURE, route).apply {
                    addTarget(target)
                    if (useManualExposure) {
                        applyManualControls(this, route)
                    } else {
                        applyAutomaticControls(this)
                        applyStillFlash(this, useFlash)
                        if (useFlash) {
                            applyFlashExposureCompensation(this)
                            if (
                                logicalCharacteristics?.get(
                                    CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE,
                                ) == true
                            ) {
                                set(CaptureRequest.CONTROL_AE_LOCK, true)
                            }
                        }
                    }
                    applyRouteZoom(this)
                    set(
                        CaptureRequest.CONTROL_CAPTURE_INTENT,
                        CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE,
                    )
                    set(
                        CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                        CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE,
                    )
                }
            val physicalId = route.physicalCameraId
            session.capture(
                captureBuilder.build(),
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult,
                    ) {
                        if (!isCapturing) return
                        val flashState = result.get(CaptureResult.FLASH_STATE)
                        Log.i(
                            TAG,
                            "RAW still completed: flashRequested=$useFlash, " +
                                "manual=$useManualExposure, flashState=$flashState, " +
                                "iso=${result.get(CaptureResult.SENSOR_SENSITIVITY)}, " +
                                "exposureNs=${result.get(CaptureResult.SENSOR_EXPOSURE_TIME)}",
                        )
                        val selectedResult =
                            if (physicalId == null) {
                                result
                            } else {
                                physicalCaptureResult(result, physicalId)
                            }
                        pendingRawResult = selectedResult
                        pendingRawCropZoom =
                            resolveRawCropZoom(
                                characteristics = route.characteristics,
                                primaryResult = selectedResult,
                                fallbackResult = result,
                            )
                        restoreNormalPreview()
                        tryWriteCapture()
                    }

                    override fun onCaptureFailed(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        failure: CaptureFailure,
                    ) {
                        if (!isCapturing) return
                        restoreNormalPreview()
                        resetPendingCapture()
                        notifyFailure(
                            "La captura ${captureFormat.displayName} ha fallado. Inténtalo de nuevo.",
                        )
                    }
                },
                backgroundHandler,
            )
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to capture ${captureFormat.displayName}", e)
            restoreNormalPreview()
            resetPendingCapture()
            notifyFailure("No se pudo realizar la captura ${captureFormat.displayName}.")
        }
    }

    private fun restoreNormalPreview() {
        try {
            val builder = previewRequestBuilder ?: return
            val session = captureSession ?: return

            // Cancel any implicit AE lock left by the flash precapture sequence before resuming.
            applyAutomaticControls(builder)
            builder.set(CaptureRequest.CONTROL_AE_LOCK, false)
            builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
            builder.set(
                CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_CANCEL,
            )
            runCatching { session.capture(builder.build(), null, backgroundHandler) }

            applyPreviewExposureControls(builder)
            // applyPreviewExposureControls() restores the user's AE-lock state after the
            // one-shot cancellation request above.
            builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
            builder.set(
                CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE,
            )
            session.setRepeatingRequest(builder.build(), repeatingCaptureCallback, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.w(TAG, "Failed to restore preview after still capture", e)
        }
    }



    private fun physicalCaptureResult(
        result: TotalCaptureResult,
        physicalCameraId: String,
    ): CaptureResult =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            result.getPhysicalCameraTotalResults()[physicalCameraId] ?: result
        } else {
            @Suppress("DEPRECATION")
            result.getPhysicalCameraResults()[physicalCameraId] ?: result
        }

    private fun resolveRawCropZoom(
        characteristics: CameraCharacteristics,
        primaryResult: CaptureResult,
        fallbackResult: CaptureResult,
    ): Float {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return 1f
        val crop =
            primaryResult.get(CaptureResult.SCALER_RAW_CROP_REGION)
                ?: fallbackResult.get(CaptureResult.SCALER_RAW_CROP_REGION)
                ?: return 1f
        val fullArray =
            characteristics.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)
                ?: characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                ?: return 1f
        if (crop.width() <= 0 || crop.height() <= 0) return 1f
        val horizontal = fullArray.width().toFloat() / crop.width().toFloat()
        val vertical = fullArray.height().toFloat() / crop.height().toFloat()
        return minOf(horizontal, vertical).coerceAtLeast(1f)
    }

    private fun applyPreviewExposureControls(builder: CaptureRequest.Builder) {
        val route = activeRoute
        if (
            exposureMode == ExposureMode.MANUAL &&
                route != null &&
                manualExposureAvailableForActiveRoute()
        ) {
            applyManualControls(builder, route)
        } else {
            applyAutomaticControls(builder)
        }
    }

    private fun applyAutomaticControls(builder: CaptureRequest.Builder) {
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        setRouteRequestKey(
            builder,
            CaptureRequest.CONTROL_AF_MODE,
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
        )
        setRouteRequestKey(
            builder,
            CaptureRequest.CONTROL_AE_MODE,
            CaptureRequest.CONTROL_AE_MODE_ON,
        )
        val aeLockAvailable =
            logicalCharacteristics?.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) == true
        builder.set(
            CaptureRequest.CONTROL_AE_LOCK,
            autoExposureLocked && aeLockAvailable && !flashEnabled,
        )
        applyAutoExposureCompensation(builder)
        ensureSpotMeteringRegion(builder)
        setRouteRequestKey(
            builder,
            CaptureRequest.CONTROL_AWB_MODE,
            CaptureRequest.CONTROL_AWB_MODE_AUTO,
        )
    }

    private fun applyManualControls(
        builder: CaptureRequest.Builder,
        route: LensRoute,
    ) {
        val characteristics = route.characteristics
        val sensitivityRange =
            characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val exposureRange =
            characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val maxFrameDuration =
            characteristics.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION)
                ?: Long.MAX_VALUE
        val iso =
            sensitivityRange?.let { manualIso.coerceIn(it.lower, it.upper) } ?: manualIso
        val exposure =
            exposureRange?.let {
                val maximum = minOf(it.upper, 1_000_000_000L, maxFrameDuration)
                manualExposureTimeNs.coerceIn(it.lower, maximum)
            } ?: manualExposureTimeNs.coerceAtMost(maxFrameDuration)

        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        if (manualFocusEnabled && manualFocusAvailableForActiveRoute()) {
            setRouteRequestKey(
                builder,
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_OFF,
                route,
            )
            setRouteRequestKey(
                builder,
                CaptureRequest.LENS_FOCUS_DISTANCE,
                manualFocusDistanceDiopters,
                route,
            )
        } else {
            setRouteRequestKey(
                builder,
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
                route,
            )
        }
        setRouteRequestKey(
            builder,
            CaptureRequest.CONTROL_AE_MODE,
            CaptureRequest.CONTROL_AE_MODE_OFF,
            route,
        )
        setRouteRequestKey(
            builder,
            CaptureRequest.CONTROL_AWB_MODE,
            CaptureRequest.CONTROL_AWB_MODE_AUTO,
            route,
        )
        builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
        setRouteRequestKey(builder, CaptureRequest.SENSOR_SENSITIVITY, iso, route)
        setRouteRequestKey(builder, CaptureRequest.SENSOR_EXPOSURE_TIME, exposure, route)
        setRouteRequestKey(
            builder,
            CaptureRequest.SENSOR_FRAME_DURATION,
            max(exposure, 33_333_333L).coerceAtMost(maxFrameDuration),
            route,
        )
    }

    /**
     * Applies a request value to both the logical request and, when supported, the physical sensor
     * that owns the preview/RAW outputs. Pixel logical-camera requests can otherwise leave ISO and
     * shutter under AE control on the selected physical lens.
     */
    private fun <T : Any> setRouteRequestKey(
        builder: CaptureRequest.Builder,
        key: CaptureRequest.Key<T>,
        value: T,
        route: LensRoute? = activeRoute,
    ) {
        builder.set(key, value)
        val physicalId = route?.physicalCameraId ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val physicalKeys =
            logicalCharacteristics
                ?.getAvailablePhysicalCameraRequestKeys()
                .orEmpty()
        if (!physicalKeys.contains(key)) return
        runCatching { builder.setPhysicalCameraKey(key, value, physicalId) }
            .onFailure { error ->
                Log.w(TAG, "Physical request key ${key.name} rejected for $physicalId", error)
            }
    }

    /**
     * SPOT should behave differently as soon as it is selected, not only after a tap.
     * If there is no existing user-selected AE region, seed a tight maximum-weight region in the
     * centre of the logical active array. A later preview tap replaces this region.
     */
    private fun ensureSpotMeteringRegion(builder: CaptureRequest.Builder) {
        if (autoMeteringMode != AutoMeteringMode.SPOT || exposureMode != ExposureMode.AUTO) return
        val logical = logicalCharacteristics ?: return
        if ((logical.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0) <= 0) return
        val existing = builder.get(CaptureRequest.CONTROL_AE_REGIONS)
        if (!existing.isNullOrEmpty()) return
        val activeArray = logical.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
        if (activeArray.width() <= 1 || activeArray.height() <= 1) return

        val halfSize =
            (minOf(activeArray.width(), activeArray.height()) * 0.04f)
                .roundToInt()
                .coerceAtLeast(48)
        val centerX = activeArray.centerX()
        val centerY = activeArray.centerY()
        val left = (centerX - halfSize).coerceIn(activeArray.left, activeArray.right - 1)
        val top = (centerY - halfSize).coerceIn(activeArray.top, activeArray.bottom - 1)
        val right = (centerX + halfSize).coerceIn(left + 1, activeArray.right)
        val bottom = (centerY + halfSize).coerceIn(top + 1, activeArray.bottom)
        builder.set(
            CaptureRequest.CONTROL_AE_REGIONS,
            arrayOf(
                MeteringRectangle(
                    Rect(left, top, right, bottom),
                    MeteringRectangle.METERING_WEIGHT_MAX,
                ),
            ),
        )
    }

    private fun applyAutoExposureCompensation(builder: CaptureRequest.Builder) {
        val highlightBias =
            if (autoMeteringMode == AutoMeteringMode.HIGHLIGHT) HIGHLIGHT_PROTECTION_EV else 0f
        applyExposureCompensation(builder, autoExposureCompensationEv + highlightBias)
    }

    private fun applyExposureCompensation(
        builder: CaptureRequest.Builder,
        requestedEv: Float,
    ) {
        val characteristics = logicalCharacteristics ?: return
        val range =
            characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: return
        val step =
            characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
                ?.toFloat()
                ?.takeIf { it > 0f }
                ?: return
        val compensationSteps =
            (requestedEv / step).roundToInt().coerceIn(range.lower, range.upper)
        // Exposure compensation belongs to the logical AE algorithm. Mirroring it into an
        // individual physical-camera request can make Pixel's logical multi-camera HAL reject the
        // request when switching metering modes.
        builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, compensationSteps)
    }

    private fun applyFlashExposureCompensation(builder: CaptureRequest.Builder) {
        val highlightBias =
            if (autoMeteringMode == AutoMeteringMode.HIGHLIGHT) HIGHLIGHT_PROTECTION_EV else 0f
        applyExposureCompensation(
            builder,
            autoExposureCompensationEv + highlightBias + flashExposureCompensationEv,
        )
    }

    private fun manualExposureAvailableForActiveRoute(): Boolean {
        val characteristics = activeRoute?.characteristics ?: return false
        val routeCapabilities =
            characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                ?: IntArray(0)
        val logicalCapabilities =
            logicalCharacteristics?.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                ?: IntArray(0)
        return routeCapabilities.contains(
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR,
        ) &&
            logicalCapabilities.contains(
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR,
            ) &&
            characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) != null &&
            characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) != null
    }

    private fun manualFocusAvailableForActiveRoute(): Boolean {
        val characteristics = activeRoute?.characteristics ?: return false
        val minimumFocusDistance =
            characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        val afModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: IntArray(0)
        return minimumFocusDistance > 0f && afModes.contains(CaptureRequest.CONTROL_AF_MODE_OFF)
    }

    private fun clampManualFocusToRoute() {
        val maxDiopters =
            activeRoute?.characteristics
                ?.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
                ?.coerceAtLeast(0f)
                ?: 0f
        manualFocusDistanceDiopters = manualFocusDistanceDiopters.coerceIn(0f, maxDiopters)
        if (!manualFocusAvailableForActiveRoute()) {
            manualFocusEnabled = false
            manualFocusDistanceDiopters = 0f
        }
    }

    private fun clampManualSettingsToRoute() {
        val characteristics = activeRoute?.characteristics ?: return
        characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let { range ->
            manualIso = manualIso.coerceIn(range.lower, range.upper)
        }
        characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let { range ->
            val maxFrameDuration =
                characteristics.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION)
                    ?: Long.MAX_VALUE
            val maximum = minOf(range.upper, 1_000_000_000L, maxFrameDuration)
            manualExposureTimeNs = manualExposureTimeNs.coerceIn(range.lower, maximum)
        }
        clampManualFocusToRoute()
        if (!manualExposureAvailableForActiveRoute() && exposureMode == ExposureMode.MANUAL) {
            exposureMode = ExposureMode.AUTO
        }
    }

    private fun applyStillFlash(
        builder: CaptureRequest.Builder,
        enabled: Boolean,
    ) {
        if (enabled && flashAvailable) {
            builder.set(
                CaptureRequest.CONTROL_AE_MODE,
                CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH,
            )
            builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
        } else {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
        }
    }

    private fun createRouteCaptureRequest(
        device: CameraDevice,
        template: Int,
        route: LensRoute,
    ): CaptureRequest.Builder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && route.physicalCameraId != null) {
            device.createCaptureRequest(template, setOf(route.physicalCameraId))
        } else {
            device.createCaptureRequest(template)
        }

    private fun applyRouteZoom(builder: CaptureRequest.Builder) {
        val route = activeRoute ?: return
        val relativeZoom = digitalZoom.coerceIn(1f, route.maxDigitalZoom)
        val physicalId = route.physicalCameraId
        val physicalKeys: Set<CaptureRequest.Key<*>> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                logicalCharacteristics
                    ?.getAvailablePhysicalCameraRequestKeys()
                    .orEmpty()
                    .toSet()
            } else {
                emptySet()
            }

        if (physicalId != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (physicalKeys.contains(CaptureRequest.CONTROL_ZOOM_RATIO)) {
                val applied =
                    runCatching {
                        builder.setPhysicalCameraKey(
                            CaptureRequest.CONTROL_ZOOM_RATIO,
                            relativeZoom,
                            physicalId,
                        )
                    }.onFailure { error ->
                        Log.w(TAG, "Physical zoomRatio rejected for $physicalId", error)
                    }.isSuccess
                if (applied) return
            }

            if (physicalKeys.contains(CaptureRequest.SCALER_CROP_REGION)) {
                route.characteristics
                    .get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                    ?.let { activeArray ->
                        val applied =
                            runCatching {
                                builder.setPhysicalCameraKey(
                                    CaptureRequest.SCALER_CROP_REGION,
                                    centeredCrop(activeArray, relativeZoom),
                                    physicalId,
                                )
                            }.onFailure { error ->
                                Log.w(TAG, "Physical cropRegion rejected for $physicalId", error)
                            }.isSuccess
                        if (applied) return
                    }
            }
        }

        val logicalZoom =
            if (physicalId == null) {
                relativeZoom
            } else {
                route.preset * relativeZoom
            }
        val logical = logicalCharacteristics ?: return
        val logicalRange = logical.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
        if (logicalRange != null) {
            builder.set(
                CaptureRequest.CONTROL_ZOOM_RATIO,
                logicalZoom.coerceIn(logicalRange.lower, logicalRange.upper),
            )
        } else {
            logical.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.let { activeArray ->
                builder.set(
                    CaptureRequest.SCALER_CROP_REGION,
                    centeredCrop(activeArray, logicalZoom.coerceAtLeast(1f)),
                )
            }
        }
    }

    private fun centeredCrop(activeArray: Rect, zoom: Float): Rect {
        val safeZoom = zoom.coerceAtLeast(1f)
        val cropWidth = (activeArray.width() / safeZoom).roundToInt().coerceAtLeast(2)
        val cropHeight = (activeArray.height() / safeZoom).roundToInt().coerceAtLeast(2)
        val left = activeArray.left + (activeArray.width() - cropWidth) / 2
        val top = activeArray.top + (activeArray.height() - cropHeight) / 2
        return Rect(left, top, left + cropWidth, top + cropHeight)
    }

    /** Writes either a DNG or a basic JPEG rendering once image and metadata are both available. */
    private fun tryWriteCapture() {
        val image = pendingRawImage ?: return
        val result = pendingRawResult ?: return
        val characteristics = captureCharacteristics ?: return
        val actualRawCropZoom = pendingRawCropZoom
        pendingRawImage = null
        pendingRawResult = null
        pendingRawCropZoom = 1f

        val effectiveZoom = captureLensPreset * captureDigitalZoom
        val lensLabel = formatZoom(effectiveZoom)
        val baseFileName = newBaseFileName(lensLabel)

        try {
            val uri =
                when (captureFormat) {
                    CaptureFormat.DNG ->
                        saveDng(
                            characteristics = characteristics,
                            result = result,
                            image = image,
                            fileName = "$baseFileName.dng",
                            rotationDegrees = captureRotationDegrees,
                            physicalLensLabel = formatZoom(captureLensPreset),
                            digitalZoom = captureDigitalZoom,
                            croppedRawAvailable = captureCroppedRawAvailable,
                            actualRawCropZoom = actualRawCropZoom,
                        )

                    CaptureFormat.JPEG ->
                        saveRawRenderedJpeg(
                            characteristics = characteristics,
                            result = result,
                            image = image,
                            fileName = "$baseFileName.jpg",
                            rotationDegrees = captureRotationDegrees,
                            requestedDigitalZoom = captureDigitalZoom,
                            actualRawCropZoom = actualRawCropZoom,
                        )

                    CaptureFormat.DNG_JPEG ->
                        saveDngAndRawRenderedJpeg(
                            characteristics = characteristics,
                            result = result,
                            image = image,
                            baseFileName = baseFileName,
                            rotationDegrees = captureRotationDegrees,
                            physicalLensLabel = formatZoom(captureLensPreset),
                            requestedDigitalZoom = captureDigitalZoom,
                            croppedRawAvailable = captureCroppedRawAvailable,
                            actualRawCropZoom = actualRawCropZoom,
                        )
                }

            finishCapture()
            if (uri != null) {
                notifySaved(uri, captureRotationDegrees, captureFormat)
            } else {
                notifyFailure("No se pudo guardar el archivo ${captureFormat.displayName}.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write ${captureFormat.displayName}", e)
            finishCapture()
            notifyFailure("No se pudo guardar el archivo ${captureFormat.displayName}.")
        } finally {
            image.close()
        }
    }

    private fun saveDng(
        characteristics: CameraCharacteristics,
        result: CaptureResult,
        image: Image,
        fileName: String,
        rotationDegrees: Int,
        physicalLensLabel: String,
        digitalZoom: Float,
        croppedRawAvailable: Boolean,
        actualRawCropZoom: Float,
    ): Uri? {
        val resolver = context.contentResolver
        val values = pendingMediaValues(fileName, "image/x-adobe-dng")
        val uri =
            resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { output ->
                DngCreator(characteristics, result).use { dng ->
                    val cropDescription =
                        if (digitalZoom > 1.001f) {
                            if (croppedRawAvailable) {
                                " · requested sensor crop ${formatZoom(digitalZoom)}" +
                                    " · reported RAW crop ${formatZoom(actualRawCropZoom)}"
                            } else {
                                " · requested crop ${formatZoom(digitalZoom)} (full RAW retained)"
                            }
                        } else {
                            ""
                        }
                    dng.setDescription("Unprocess RAW · physical lens $physicalLensLabel$cropDescription")
                    dng.setOrientation(exifOrientation(rotationDegrees))
                    dng.writeImage(output, image)
                }
            } ?: error("Could not open DNG output stream")
            publishMedia(uri)
            uri
        } catch (e: Exception) {
            Log.e(TAG, "DNG write failed", e)
            resolver.delete(uri, null, null)
            null
        }
    }

    /**
     * Reuses the original Unprocess approach: RAW_SENSOR -> temporary DNG -> Android's basic DNG
     * decoder -> JPEG. This necessarily performs demosaicing, colour conversion and JPEG compression,
     * but it bypasses the Pixel camera app/HAL JPEG enhancement pipeline.
     */
    private fun saveRawRenderedJpeg(
        characteristics: CameraCharacteristics,
        result: CaptureResult,
        image: Image,
        fileName: String,
        rotationDegrees: Int,
        requestedDigitalZoom: Float,
        actualRawCropZoom: Float,
    ): Uri? {
        val tempDng = File.createTempFile("unprocess_", ".dng", context.cacheDir)
        var bitmap: Bitmap? = null
        try {
            FileOutputStream(tempDng).use { output ->
                DngCreator(characteristics, result).use { dng ->
                    dng.writeImage(output, image)
                }
            }

            val decodedBitmap =
                BitmapFactory.decodeFile(tempDng.absolutePath)
                    ?: error("Android could not decode the temporary DNG")
            val remainingCropZoom =
                (requestedDigitalZoom / actualRawCropZoom.coerceAtLeast(1f)).coerceAtLeast(1f)
            val croppedBitmap = cropBitmapForZoom(decodedBitmap, remainingCropZoom)
            if (croppedBitmap !== decodedBitmap) decodedBitmap.recycle()
            bitmap = croppedBitmap

            val resolver = context.contentResolver
            val values = pendingMediaValues(fileName, "image/jpeg")
            val uri =
                resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            return try {
                resolver.openOutputStream(uri)?.use { output ->
                    check(croppedBitmap.compress(Bitmap.CompressFormat.JPEG, 100, output)) {
                        "JPEG encoder failed"
                    }
                } ?: error("Could not open JPEG output stream")

                resolver.openFileDescriptor(uri, "rw")?.use { descriptor ->
                    ExifInterface(descriptor.fileDescriptor).apply {
                        setAttribute(
                            ExifInterface.TAG_ORIENTATION,
                            exifOrientation(rotationDegrees).toString(),
                        )
                        setAttribute(
                            ExifInterface.TAG_IMAGE_DESCRIPTION,
                            "Unprocess JPEG rendered from RAW_SENSOR" +
                                " · requested crop ${formatZoom(requestedDigitalZoom)}" +
                                " · reported RAW crop ${formatZoom(actualRawCropZoom)}" +
                                " · residual bitmap crop ${formatZoom(remainingCropZoom)}",
                        )
                        saveAttributes()
                    }
                }
                publishMedia(uri)
                uri
            } catch (e: Exception) {
                Log.e(TAG, "RAW-rendered JPEG write failed", e)
                resolver.delete(uri, null, null)
                null
            }
        } finally {
            bitmap?.recycle()
            tempDng.delete()
        }
    }

    private fun saveDngAndRawRenderedJpeg(
        characteristics: CameraCharacteristics,
        result: CaptureResult,
        image: Image,
        baseFileName: String,
        rotationDegrees: Int,
        physicalLensLabel: String,
        requestedDigitalZoom: Float,
        croppedRawAvailable: Boolean,
        actualRawCropZoom: Float,
    ): Uri? {
        val resolver = context.contentResolver
        val tempDng = File.createTempFile("unprocess_pair_", ".dng", context.cacheDir)
        var dngUri: Uri? = null
        var jpegUri: Uri? = null
        var bitmap: Bitmap? = null

        try {
            FileOutputStream(tempDng).use { output ->
                DngCreator(characteristics, result).use { dng ->
                    val cropDescription =
                        if (requestedDigitalZoom > 1.001f) {
                            if (croppedRawAvailable) {
                                " · requested sensor crop ${formatZoom(requestedDigitalZoom)}" +
                                    " · reported RAW crop ${formatZoom(actualRawCropZoom)}"
                            } else {
                                " · requested crop ${formatZoom(requestedDigitalZoom)} (full RAW retained)"
                            }
                        } else {
                            ""
                        }
                    dng.setDescription(
                        "Unprocess RAW · physical lens $physicalLensLabel$cropDescription",
                    )
                    dng.setOrientation(exifOrientation(rotationDegrees))
                    dng.writeImage(output, image)
                }
            }

            dngUri =
                resolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    pendingMediaValues("$baseFileName.dng", "image/x-adobe-dng"),
                ) ?: return null
            resolver.openOutputStream(dngUri)?.use { output ->
                tempDng.inputStream().use { input -> input.copyTo(output) }
            } ?: error("Could not open paired DNG output stream")

            val decodedBitmap =
                BitmapFactory.decodeFile(tempDng.absolutePath)
                    ?: error("Android could not decode the temporary DNG")
            val remainingCropZoom =
                (requestedDigitalZoom / actualRawCropZoom.coerceAtLeast(1f)).coerceAtLeast(1f)
            val croppedBitmap = cropBitmapForZoom(decodedBitmap, remainingCropZoom)
            if (croppedBitmap !== decodedBitmap) decodedBitmap.recycle()
            bitmap = croppedBitmap

            jpegUri =
                resolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    pendingMediaValues("$baseFileName.jpg", "image/jpeg"),
                ) ?: error("Could not create paired JPEG")
            resolver.openOutputStream(jpegUri)?.use { output ->
                check(croppedBitmap.compress(Bitmap.CompressFormat.JPEG, 100, output)) {
                    "JPEG encoder failed"
                }
            } ?: error("Could not open paired JPEG output stream")

            resolver.openFileDescriptor(jpegUri, "rw")?.use { descriptor ->
                ExifInterface(descriptor.fileDescriptor).apply {
                    setAttribute(
                        ExifInterface.TAG_ORIENTATION,
                        exifOrientation(rotationDegrees).toString(),
                    )
                    setAttribute(
                        ExifInterface.TAG_IMAGE_DESCRIPTION,
                        "Unprocess JPEG rendered from the same RAW_SENSOR frame as paired DNG" +
                            " · requested crop ${formatZoom(requestedDigitalZoom)}" +
                            " · reported RAW crop ${formatZoom(actualRawCropZoom)}" +
                            " · residual bitmap crop ${formatZoom(remainingCropZoom)}",
                    )
                    saveAttributes()
                }
            }

            publishMedia(dngUri)
            publishMedia(jpegUri)
            // The gallery preview uses the JPEG because BitmapFactory can display it reliably.
            return jpegUri
        } catch (e: Exception) {
            Log.e(TAG, "Paired DNG+JPEG write failed", e)
            dngUri?.let { resolver.delete(it, null, null) }
            jpegUri?.let { resolver.delete(it, null, null) }
            return null
        } finally {
            bitmap?.recycle()
            tempDng.delete()
        }
    }

    private fun cropBitmapForZoom(source: Bitmap, zoom: Float): Bitmap {
        val safeZoom = zoom.coerceAtLeast(1f)
        if (safeZoom <= 1.001f) return source
        val cropWidth = (source.width / safeZoom).roundToInt().coerceIn(1, source.width)
        val cropHeight = (source.height / safeZoom).roundToInt().coerceIn(1, source.height)
        val left = (source.width - cropWidth) / 2
        val top = (source.height - cropHeight) / 2
        return Bitmap.createBitmap(source, left, top, cropWidth, cropHeight)
    }

    private fun pendingMediaValues(fileName: String, mimeType: String) =
        ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_DCIM}/Unprocess",
            )
            put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

    private fun publishMedia(uri: Uri) {
        val values = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
        context.contentResolver.update(uri, values, null, null)
    }

    private fun newBaseFileName(lensLabel: String): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val facingLabel = if (requestedFacing == CameraFacing.FRONT) "front" else "rear"
        return "UNPROCESS_${timestamp}_${facingLabel}_${lensLabel}"
    }

    private fun calculateCaptureRotationDegrees(): Int {
        // OrientationEventListener reports the phone's *physical* rotation, which uses the
        // opposite convention from Display.getRotation(). Keep the portrait UI locked, but
        // use the Camera2 sensor-orientation formula for metadata so 90°/270° captures are
        // not rotated by an extra 180°.
        val deviceDegrees = physicalDeviceOrientationDegrees
        return if (lensFacing == CameraCharacteristics.LENS_FACING_FRONT) {
            (sensorOrientation - deviceDegrees + 360) % 360
        } else {
            (sensorOrientation + deviceDegrees) % 360
        }
    }

    private fun exifOrientation(degrees: Int): Int =
        when (degrees) {
            90 -> ExifInterface.ORIENTATION_ROTATE_90
            180 -> ExifInterface.ORIENTATION_ROTATE_180
            270 -> ExifInterface.ORIENTATION_ROTATE_270
            else -> ExifInterface.ORIENTATION_NORMAL
        }

    private fun formatZoom(ratio: Float): String =
        if (ratio < 1f) {
            String.format(Locale.US, "%.1fx", ratio).replace('.', '_')
        } else if (ratio % 1f == 0f) {
            "${ratio.toInt()}x"
        } else {
            String.format(Locale.US, "%.1fx", ratio).replace('.', '_')
        }

    private fun notifyActiveCameraInfo() {
        val route = activeRoute ?: return
        val capabilities =
            logicalCharacteristics?.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                ?: IntArray(0)
        notifyCameraInfo(
            RawCameraInfo(
                cameraId = cameraId,
                cameraFacing = requestedFacing,
                availableCameraFacings = availableRawFacings(),
                rawWidth = route.rawSize.width,
                rawHeight = route.rawSize.height,
                previewWidth = requestedPreviewSize.width,
                previewHeight = requestedPreviewSize.height,
                availableLensPresets = lensRoutes.keys.sorted(),
                activeLensPreset = route.preset,
                activePhysicalCameraId = route.physicalCameraId,
                isLogicalMultiCamera =
                    capabilities.contains(
                        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA,
                    ),
                flashAvailable = flashAvailable,
                aeLockAvailable =
                    logicalCharacteristics?.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) == true,
                manualExposureAvailable = manualExposureAvailableForActiveRoute(),
                manualFocusAvailable = manualFocusAvailableForActiveRoute(),
                minimumFocusDistanceDiopters =
                    route.characteristics
                        .get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
                        ?.coerceAtLeast(0f) ?: 0f,
                sensitivityMin =
                    route.characteristics
                        .get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                        ?.lower ?: DEFAULT_MANUAL_ISO,
                sensitivityMax =
                    route.characteristics
                        .get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                        ?.upper ?: DEFAULT_MANUAL_ISO,
                exposureTimeMinNs =
                    route.characteristics
                        .get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                        ?.lower ?: DEFAULT_MANUAL_EXPOSURE_TIME_NS,
                exposureTimeMaxNs =
                    minOf(
                        route.characteristics
                            .get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                            ?.upper ?: DEFAULT_MANUAL_EXPOSURE_TIME_NS,
                        route.characteristics
                            .get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION)
                            ?: Long.MAX_VALUE,
                        1_000_000_000L,
                    ),
                autoCompensationMinEv = flashCompensationRangeEv().first,
                autoCompensationMaxEv = flashCompensationRangeEv().second,
                autoCompensationStepEv = flashCompensationStepEv(),
                flashCompensationMinEv = flashCompensationRangeEv().first,
                flashCompensationMaxEv = flashCompensationRangeEv().second,
                flashCompensationStepEv = flashCompensationStepEv(),
                maxDigitalZoom = route.maxDigitalZoom,
                croppedRawAvailable = route.croppedRawAvailable,
            ),
        )
    }

    private fun flashCompensationStepEv(): Float =
        logicalCharacteristics
            ?.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
            ?.toFloat()
            ?.takeIf { it > 0f }
            ?: 1f

    private fun flashCompensationRangeEv(): Pair<Float, Float> {
        val range =
            logicalCharacteristics?.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
                ?: return 0f to 0f
        val step = flashCompensationStepEv()
        return (range.lower * step) to (range.upper * step)
    }

    private fun finishCapture() {
        backgroundHandler.removeCallbacks(captureTimeout)
        backgroundHandler.removeCallbacks(autoAeSettleTimeout)
        pendingAutoSettleAction = null
        isCapturing = false
        captureCharacteristics = null
    }

    private fun resetPendingCapture() {
        backgroundHandler.removeCallbacks(captureTimeout)
        backgroundHandler.removeCallbacks(autoAeSettleTimeout)
        pendingAutoSettleAction = null
        pendingRawImage?.close()
        pendingRawImage = null
        pendingRawResult = null
        pendingRawCropZoom = 1f
        captureCharacteristics = null
        isCapturing = false
    }

    private fun notifyCaptureStarted() = mainHandler.post { onCaptureStarted() }

    private fun notifySaved(uri: Uri, rotationDegrees: Int, format: CaptureFormat) =
        mainHandler.post { onCaptureSaved(uri, rotationDegrees, format) }

    private fun notifyFailure(message: String) = mainHandler.post { onCaptureFailed(message) }

    private fun notifyCameraInfo(info: RawCameraInfo) = mainHandler.post { onCameraInfo(info) }

    private fun notifyMeteringInfo(info: RawMeteringInfo) =
        mainHandler.post { onMeteringInfo(info) }

    private fun notifySwitchingCamera() {
        Log.i(TAG, "Switching RAW camera to $requestedFacing")
    }

    private fun notifyUnsupported() = mainHandler.post { onUnsupported() }

    override fun onCameraDisconnected() {
        notifyFailure("La cámara se ha desconectado.")
    }

    override fun onCameraError(error: Int) {
        notifyFailure("Camera2 ha devuelto el error $error.")
    }

    override fun onCameraClosed() {
        activePreviewSurface = null
        resetPendingCapture()
        rawReader?.close()
        rawReader = null
    }
}
