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

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.android.camera.coreui.controls.ScrimIconButton
import com.android.camera.coreui.overlay.ViewfinderTitleChip
import com.android.camera.coreui.state.LoadingView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

private const val MAX_PREVIEW_DIMENSION = 4096
private const val MAX_GALLERY_ZOOM = 8f

/** Displays the most recent file without applying or offering any image adjustments. */
@Composable
fun BoxScope.CaptureViewerContent(
    capture: LastCapture,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val isDng = capture.format == CaptureFormat.DNG
    BackHandler(onBack = onBack)

    val decoded by
        produceState<DecodeResult>(
            DecodeResult.Loading,
            capture.uri,
            capture.rotationDegrees,
        ) {
            value = decodeCapture(context, capture.uri, capture.rotationDegrees)
        }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        when (val result = decoded) {
            DecodeResult.Loading -> LoadingView()
            DecodeResult.Failed -> {
                Text(
                    text =
                        stringResource(
                            if (isDng) {
                                R.string.rawcapture_decode_failed
                            } else {
                                R.string.rawcapture_jpeg_decode_failed
                            },
                        ),
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(32.dp),
                )
            }

            is DecodeResult.Success -> {
                ZoomableCaptureImage(
                    image = result.image,
                    contentDescription = stringResource(R.string.rawcapture_captured_description),
                )
            }
        }
    }

    ScrimIconButton(
        onClick = onBack,
        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
        contentDescription = stringResource(R.string.rawcapture_back_to_camera),
        size = 34.dp,
        iconSize = 18.dp,
        modifier =
            Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(16.dp),
    )

    ViewfinderTitleChip(
        text =
            stringResource(
                if (isDng) {
                    R.string.rawcapture_editor_title
                } else {
                    R.string.rawcapture_jpeg_viewer_title
                },
            ),
        modifier =
            Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 44.dp),
    )
}


@Composable
private fun ZoomableCaptureImage(
    image: androidx.compose.ui.graphics.ImageBitmap,
    contentDescription: String,
) {
    var scale by remember(image) { mutableFloatStateOf(1f) }
    var translation by remember(image) { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().clipToBounds(),
        contentAlignment = Alignment.Center,
    ) {
        val viewportWidthPx = with(density) { maxWidth.toPx() }
        val viewportHeightPx = with(density) { maxHeight.toPx() }
        val transformState =
            rememberTransformableState { zoomChange, panChange, _ ->
                val newScale = (scale * zoomChange).coerceIn(1f, MAX_GALLERY_ZOOM)
                val maxX = viewportWidthPx * (newScale - 1f) / 2f
                val maxY = viewportHeightPx * (newScale - 1f) / 2f
                translation =
                    if (newScale <= 1.001f) {
                        Offset.Zero
                    } else {
                        Offset(
                            x = (translation.x + panChange.x).coerceIn(-maxX, maxX),
                            y = (translation.y + panChange.y).coerceIn(-maxY, maxY),
                        )
                    }
                scale = newScale
            }

        Image(
            bitmap = image,
            contentDescription = contentDescription,
            modifier =
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = translation.x
                        translationY = translation.y
                    }
                    .pointerInput(image) {
                        detectTapGestures(
                            onDoubleTap = {
                                scale = 1f
                                translation = Offset.Zero
                            },
                        )
                    }
                    .transformable(transformState),
            contentScale = ContentScale.Fit,
        )
    }
}

private sealed interface DecodeResult {
    data object Loading : DecodeResult
    data object Failed : DecodeResult

    data class Success(
        val image: androidx.compose.ui.graphics.ImageBitmap,
    ) : DecodeResult
}

private suspend fun decodeCapture(
    context: Context,
    uri: Uri,
    rotationDegrees: Int,
): DecodeResult =
    withContext(Dispatchers.IO) {
        val decoded = decodeDownsampled(context, uri) ?: return@withContext DecodeResult.Failed
        val upright =
            if (rotationDegrees == 0) {
                decoded
            } else {
                val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                Bitmap
                    .createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                    .also { if (it !== decoded) decoded.recycle() }
            }
        DecodeResult.Success(upright.asImageBitmap())
    }

private fun decodeDownsampled(
    context: Context,
    uri: Uri,
): Bitmap? {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    runCatching {
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sampleSize = 1
    while (max(bounds.outWidth, bounds.outHeight) / sampleSize > MAX_PREVIEW_DIMENSION) {
        sampleSize *= 2
    }
    val options =
        BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
    return runCatching {
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }.getOrNull()
}
