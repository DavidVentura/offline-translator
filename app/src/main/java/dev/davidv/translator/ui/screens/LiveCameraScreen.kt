/*
 * Copyright (C) 2024 David V
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package dev.davidv.translator.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.net.Uri
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.davidv.translator.Language
import dev.davidv.translator.LanguageAvailabilityState
import dev.davidv.translator.LanguageMetadata
import dev.davidv.translator.LivePlanarOcrEngine
import dev.davidv.translator.R
import dev.davidv.translator.TranslatorMessage
import dev.davidv.translator.ui.components.LanguageSelector
import dev.davidv.translator.ui.components.LiveGlSurfaceView
import java.io.File
import java.util.concurrent.Executor
import android.util.Size as AndroidSize

private const val TAG = "LiveCameraScreen"
private val TARGET_RESOLUTION = AndroidSize(1080, 1920)
private val PREVIEW_RESOLUTION = AndroidSize(1080, 1920)

/** Minimum change in target focus (diopters, 1/m) before we re-issue a
 *  capture request. A deadband: holding the camera still leaves scale
 *  essentially constant, so without it we'd churn `setCaptureRequestOptions`
 *  every frame and chase sub-pixel scale jitter into focus jitter. */
private const val FOCUS_UPDATE_DEADBAND_DIOPTERS = 0.5f

/** EMA weight applied to the raw tracker `scale` before it drives focus.
 *  Handheld jitter pushes per-frame scale around by a few % even on a
 *  static scene; smoothing it absorbs that without adding meaningful lag
 *  for real zoom-in/out motion. */
private const val FOCUS_SCALE_EMA_ALPHA = 0.25f

/** Minimum interval between focus-distance writes. Caps how often we
 *  rebuild the repeating capture request from the steer path even when
 *  the deadband is repeatedly crossed (slow drift across many frames). */
private const val FOCUS_UPDATE_MIN_INTERVAL_MS = 750L

/** Reference captured when the tracker-driven focus lock is established:
 *  the focus distance ([refDiopters], 1/m) the lens was at and the
 *  tracker scale ([refScale]) at that instant, tagged with the chain
 *  [rootAnchorId] they belong to. Target focus for a later frame with
 *  scale `s` is `refDiopters * s / refScale` — image scale is inversely
 *  proportional to subject distance, and focus distance is in diopters
 *  (1/m), so diopters track scale linearly. */
private data class FocusBaseline(
  val rootAnchorId: Long,
  val refDiopters: Float,
  val refScale: Float,
)

/** Show a small overlay pill in the corner reporting tracker state
 *  (Idle/Acquiring/Locked/Lost + inliers + last acquire's det/rec
 *  counts). Useful while tuning, off in production. Gated on
 *  `BuildConfig.DEBUG` so release builds never show it. */
private val DEBUG_SHOW_TRACKER_STATUS: Boolean = dev.davidv.translator.BuildConfig.DEBUG

@Composable
fun LiveCameraScreen(
  from: Language,
  to: Language,
  isAutoSource: Boolean,
  canSwap: Boolean,
  languageState: LanguageAvailabilityState,
  languageMetadata: Map<Language, LanguageMetadata>,
  onMessage: (TranslatorMessage) -> Unit,
  showAutoOption: Boolean,
  liveOverlayDefaultEnabled: Boolean,
  catalog: dev.davidv.translator.LanguageCatalog,
  onClose: () -> Unit,
  onImageCaptured: (Uri) -> Unit,
) {
  val context = LocalContext.current
  var permissionGranted by remember {
    mutableStateOf(
      ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED,
    )
  }
  var hasAsked by remember { mutableStateOf(permissionGranted) }
  val requestPermission =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      permissionGranted = granted
      hasAsked = true
    }

  LaunchedEffect(Unit) {
    if (!permissionGranted && !hasAsked) {
      requestPermission.launch(Manifest.permission.CAMERA)
    }
  }

  BackHandler { onClose() }

  DisposableEffect(Unit) {
    val activity = context as? Activity
    activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    onDispose {
      activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
  }

  Box(
    modifier =
      Modifier
        .fillMaxSize()
        .background(Color.Black),
  ) {
    when {
      permissionGranted ->
        CameraSurface(
          from = from,
          to = to,
          isAutoSource = isAutoSource,
          canSwap = canSwap,
          languageState = languageState,
          languageMetadata = languageMetadata,
          onMessage = onMessage,
          showAutoOption = showAutoOption,
          liveOverlayDefaultEnabled = liveOverlayDefaultEnabled,
          catalog = catalog,
          onImageCaptured = onImageCaptured,
        )
      hasAsked ->
        PermissionPrompt(
          onRequest = { requestPermission.launch(Manifest.permission.CAMERA) },
          onCancel = onClose,
        )
    }
  }
}

@Composable
private fun PermissionPrompt(
  onRequest: () -> Unit,
  onCancel: () -> Unit,
) {
  Column(
    modifier =
      Modifier
        .fillMaxSize()
        .padding(24.dp),
    verticalArrangement = Arrangement.Center,
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text(
      text = stringResource(R.string.camera_permission_rationale),
      color = Color.White,
    )
    Spacer(modifier = Modifier.size(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      Text(
        text = stringResource(R.string.common_grant),
        color = Color.White,
        modifier =
          Modifier
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.2f))
            .clickable { onRequest() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
      )
      Text(
        text = stringResource(R.string.common_cancel),
        color = Color.White,
        modifier =
          Modifier
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.1f))
            .clickable { onCancel() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
      )
    }
  }
}

@SuppressLint("ClickableViewAccessibility")
@Composable
private fun CameraSurface(
  from: Language,
  to: Language,
  isAutoSource: Boolean,
  canSwap: Boolean,
  languageState: LanguageAvailabilityState,
  languageMetadata: Map<Language, LanguageMetadata>,
  onMessage: (TranslatorMessage) -> Unit,
  showAutoOption: Boolean,
  liveOverlayDefaultEnabled: Boolean,
  catalog: dev.davidv.translator.LanguageCatalog,
  onImageCaptured: (Uri) -> Unit,
) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val mainExecutor: Executor = remember { ContextCompat.getMainExecutor(context) }

  var torchOn by remember { mutableStateOf(false) }
  val hasPaddleOcrModels =
    remember(catalog, languageState, from.code, isAutoSource) {
      if (isAutoSource) {
        languageState.allLanguages().any { language ->
          catalog.installedOcrEngines(language.code).contains("ppocr")
        }
      } else {
        catalog.installedOcrEngines(from.code).contains("ppocr")
      }
    }
  var liveOverlayOn by remember { mutableStateOf(liveOverlayDefaultEnabled && hasPaddleOcrModels) }
  var camera by remember { mutableStateOf<Camera?>(null) }
  val cameraControl = camera?.cameraControl
  var hasFlashUnit by remember { mutableStateOf(false) }
  var isCapturing by remember { mutableStateOf(false) }
  var focusPoint by remember { mutableStateOf<Offset?>(null) }
  val focusScale = remember { Animatable(1.5f) }
  val focusAlpha = remember { Animatable(0f) }
  // Persistent crop centre for the live OCR engine. Defaults to dead centre; a tap
  // moves it so we crop around the user's focus area instead of the middle of the frame.
  var cropFocusNormalized by remember { mutableStateOf(Offset(0.5f, 0.5f)) }

  LaunchedEffect(focusPoint) {
    val point = focusPoint ?: return@LaunchedEffect
    focusScale.snapTo(1.5f)
    focusAlpha.snapTo(1f)
    focusScale.animateTo(1f, tween(durationMillis = 280))
    focusAlpha.animateTo(0f, tween(durationMillis = 420))
    if (focusPoint == point) focusPoint = null
  }

  val imageCapture =
    remember {
      val resolutionSelector =
        ResolutionSelector.Builder()
          .setResolutionStrategy(
            ResolutionStrategy(
              TARGET_RESOLUTION,
              ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
            ),
          )
          .build()
      ImageCapture.Builder()
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
        .setResolutionSelector(resolutionSelector)
        .build()
    }

  // AF state from the Camera2 session capture callback. True while the
  // sensor reports CONTROL_AF_STATE_*_SCAN. The planar engine treats
  // this as a hard "don't try to lock" signal — features from blurred
  // frames during the scan would lock against geometry that's about to
  // shift when focus settles.
  val afScanning = remember { kotlinx.coroutines.flow.MutableStateFlow(false) }

  // LENS_FOCUS_DISTANCE (diopters, 1/m) latched from the most recent
  // capture result whose CONTROL_AF_STATE reports a converged focus
  // (FOCUSED_LOCKED or PASSIVE_FOCUSED). Used to pin focus when the
  // planar tracker reaches Locked — AF excursions blur frames, BRIEF
  // descriptors die, the tracker drops. Pin the focus we have, accept
  // progressive blur as the user approaches the subject.
  val latestFocusedDistance =
    remember { kotlinx.coroutines.flow.MutableStateFlow<Float?>(null) }

  // Tracks whether the tracker-driven focus lock (LENS_FOCUS_DISTANCE +
  // AF_MODE=OFF, installed via Camera2 interop) is currently active.
  // Hoisted out of the LaunchedEffect so tap-to-focus can clear it —
  // otherwise the sticky interop bundle keeps AF_MODE=OFF on every
  // capture request and startFocusAndMetering silently no-ops.
  val focusLockedRef = remember { java.util.concurrent.atomic.AtomicBoolean(false) }

  // Reference scale/distance the focus lock was seeded from, plus the
  // last focus distance we actually pushed to the lens. Both written
  // from the trackerStatus collector and reset by tap-to-focus (all on
  // the main thread, but held in atomics to keep the cross-effect reads
  // honest). `null` baseline = not yet seeded; once seeded we stay in
  // AF_MODE=OFF and steer focus from tracker scale until the next tap.
  val focusBaselineRef =
    remember { java.util.concurrent.atomic.AtomicReference<FocusBaseline?>(null) }
  val heldFocusDiopters =
    remember { java.util.concurrent.atomic.AtomicReference<Float?>(null) }

  // CameraX `Preview` use case. Its output Surface is provided by
  // `LiveGlSurfaceView`'s SurfaceTexture (the GL render thread owns the
  // external-OES texture the SurfaceTexture wraps). No `ImageAnalysis`:
  // per-frame pixels never cross JNI as CPU bytes — the engine renders
  // canonical luma on the GPU from the same external texture the present
  // composite samples.
  val preview =
    remember {
      val resolutionSelector =
        ResolutionSelector.Builder()
          .setResolutionStrategy(
            ResolutionStrategy(
              PREVIEW_RESOLUTION,
              ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
            ),
          )
          .build()
      val builder =
        Preview.Builder()
          .setResolutionSelector(resolutionSelector)
      Camera2Interop.Extender(builder).setSessionCaptureCallback(
        object : CameraCaptureSession.CaptureCallback() {
          override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
          ) {
            val afState = result.get(CaptureResult.CONTROL_AF_STATE) ?: return
            val scanning =
              afState == CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN ||
                afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN
            if (afScanning.value != scanning) afScanning.value = scanning
            // Latch the focus distance once AF has converged; the
            // tracker-state observer below uses it to pin focus when
            // we reach Locked. Skip "inactive" results (no AF activity
            // yet) and "scanning" results (in-flight, value transient).
            if (afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
              afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED
            ) {
              val d = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
              if (d != null) latestFocusedDistance.value = d
            }
          }
        },
      )
      builder.build()
    }
  // Background executor for the CameraX SurfaceProvider's surface-release
  // callback. CameraX cancels surface requests on this executor; the
  // single-thread is plenty since the callback fires at most a handful of
  // times over the Composable's lifetime.
  val cameraSurfaceExecutor =
    remember { java.util.concurrent.Executors.newSingleThreadExecutor() }

  val workerScope = androidx.compose.runtime.rememberCoroutineScope()
  val liveOcrEngine =
    // Keyed on the session handle rather than the catalog: every install-state change publishes
    // a new catalog over the same session, and rebuilding would swap the native pipeline out
    // from under the GL thread mid-frame.
    remember(catalog.planarHandle()) { LivePlanarOcrEngine(catalog.planarHandle(), workerScope) }
  var previewSizePx by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }

  DisposableEffect(Unit) {
    onDispose { cameraSurfaceExecutor.shutdown() }
  }

  LaunchedEffect(from.code, isAutoSource, hasPaddleOcrModels, liveOverlayDefaultEnabled) {
    liveOverlayOn = liveOverlayDefaultEnabled && hasPaddleOcrModels
    if (!liveOverlayOn) liveOcrEngine.clear()
  }

  LaunchedEffect(liveOcrEngine, liveOverlayOn) {
    liveOcrEngine.setOverlayEnabled(liveOverlayOn)
  }

  LaunchedEffect(liveOcrEngine) {
    afScanning.collect { scanning ->
      if (scanning) liveOcrEngine.onAfScanStart() else liveOcrEngine.onAfScanEnd()
    }
  }

  // Pin focus when the tracker first locks, then *steer* it from the
  // tracked plane's scale instead of ever handing control back to AF.
  // Autofocus excursions blur frames mid-track, which kills BRIEF
  // descriptors and drops the tracker — and the old policy re-enabled
  // continuous AF on every drop, so the camera hunted in a loop even on a
  // perfectly still scene. Here AF stays OFF after the first lock: as the
  // user nears/recedes the plane its scale changes, and we move
  // LENS_FOCUS_DISTANCE to match (image scale ∝ 1/distance ∝ diopters).
  // Focus is handed back to AF only on an explicit tap (see the tap
  // handler). A full re-acquire restarts the scale baseline (new chain
  // root) but the camera hasn't teleported, so we re-baseline at the
  // focus distance we're already holding — no hunt, no jump.
  LaunchedEffect(camera, liveOcrEngine) {
    val cam = camera ?: return@LaunchedEffect
    // Closest the lens can focus, in diopters (1/m); 0 = infinity. Caps
    // the steered distance so we never command past the optics.
    val minFocusDiopters =
      runCatching {
        Camera2CameraInfo
          .from(cam.cameraInfo)
          .getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
      }.getOrNull() ?: 10f

    fun applyFocus(diopters: Float) {
      // Explicitly hold AE = ON in the same bundle. Empirically some
      // Camera2 implementations couple AF and AE state; if
      // setCaptureRequestOptions replaces the bundle and AE wasn't named,
      // AE can transiently destabilise. Tiny exposure shifts kill BRIEF
      // descriptor matches (256 sign-bit comparisons; pixels near a
      // comparison's zero crossing flip on sub-1/3-EV shifts), which
      // manifested as sudden inlier collapses on frames where nothing
      // actually moved.
      Log.i(TAG, "applyFocus d=$diopters")
      val opts =
        CaptureRequestOptions.Builder()
          .setCaptureRequestOption(
            CaptureRequest.CONTROL_AF_MODE,
            CameraMetadata.CONTROL_AF_MODE_OFF,
          )
          .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, diopters)
          .setCaptureRequestOption(
            CaptureRequest.CONTROL_AE_MODE,
            CameraMetadata.CONTROL_AE_MODE_ON,
          )
          .build()
      Camera2CameraControl.from(cam.cameraControl).captureRequestOptions = opts
    }

    var smoothedScale = Float.NaN
    var lastSeenRoot = -1L
    var lastFocusUpdateMs = 0L
    liveOcrEngine.trackerStatus.collect { status ->
      if (status.state != uniffi.bindings.PlanarTrackerState.LOCKED) {
        smoothedScale = Float.NaN
        lastSeenRoot = -1L
        return@collect
      }
      val raw = if (status.scale > 0f) status.scale else 1f
      smoothedScale =
        if (smoothedScale.isNaN() || status.rootAnchorId != lastSeenRoot) {
          raw
        } else {
          smoothedScale + FOCUS_SCALE_EMA_ALPHA * (raw - smoothedScale)
        }
      lastSeenRoot = status.rootAnchorId
      val scale = smoothedScale

      val existing = focusBaselineRef.get()
      if (existing == null) {
        // First lock since startup or a tap: seed from whatever distance
        // AF converged to while it was still running. Wait for it.
        val d0 = latestFocusedDistance.value ?: return@collect
        runCatching {
          applyFocus(d0)
          focusBaselineRef.set(FocusBaseline(status.rootAnchorId, d0, scale))
          heldFocusDiopters.set(d0)
          focusLockedRef.set(true)
          Log.i(TAG, "focus pinned d0=$d0 s0=$scale root=${status.rootAnchorId}")
        }.onFailure { Log.w(TAG, "focus pin failed", it) }
        return@collect
      }

      val baseline =
        if (status.rootAnchorId != existing.rootAnchorId) {
          val held = heldFocusDiopters.get() ?: existing.refDiopters
          FocusBaseline(status.rootAnchorId, held, scale).also {
            focusBaselineRef.set(it)
            Log.i(TAG, "focus re-baseline root=${status.rootAnchorId} held=$held s0=$scale")
          }
        } else {
          existing
        }

      val target =
        (baseline.refDiopters * scale / baseline.refScale).coerceIn(0f, minFocusDiopters)
      val prev = heldFocusDiopters.get()
      if (prev == null || kotlin.math.abs(target - prev) > FOCUS_UPDATE_DEADBAND_DIOPTERS) {
        val nowMs = android.os.SystemClock.uptimeMillis()
        if (nowMs - lastFocusUpdateMs >= FOCUS_UPDATE_MIN_INTERVAL_MS) {
          lastFocusUpdateMs = nowMs
          runCatching {
            applyFocus(target)
            heldFocusDiopters.set(target)
          }.onFailure { Log.w(TAG, "focus steer failed", it) }
        }
      }
    }
  }

  // Push language config into the tracker whenever it changes. The
  // pipeline only diffs the call internally if the codes/auto flag
  // actually change, so calling once per real change is safe and cheap.
  LaunchedEffect(liveOcrEngine, from.code, to.code, isAutoSource) {
    liveOcrEngine.setLanguages(from, to, isAutoSource)
  }

  // Tap-to-focus = fresh-start intent. The GL thread doesn't see the
  // tap; bump the tracker generation here so any in-flight async job
  // bails at its next gen-check.
  LaunchedEffect(liveOcrEngine, cropFocusNormalized) {
    liveOcrEngine.resetTracker()
  }

  val liveSurfaceView =
    remember { LiveGlSurfaceView(context).apply { keepScreenOn = true } }

  // Hand the tracker pointer + per-frame result callback to the GL
  // thread. The GL render loop, driven by `SurfaceTexture.onFrameAvailable`
  // from CameraX's `Preview`, calls `LivePipelineJni.processFrameGl`
  // directly with this pointer and forwards the packed result here so
  // the debug pill stays in sync.
  DisposableEffect(liveSurfaceView, liveOcrEngine) {
    liveSurfaceView.pipelinePtr = liveOcrEngine.pipelinePtr
    liveSurfaceView.onFrameResult = { packed -> liveOcrEngine.onFrameResult(packed) }
    onDispose {
      liveSurfaceView.pipelinePtr = 0L
      liveSurfaceView.onFrameResult = null
    }
  }

  DisposableEffect(liveSurfaceView) {
    val scaleDetector =
      ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
          override fun onScale(detector: ScaleGestureDetector): Boolean {
            val cam = camera ?: return true
            val zoomState = cam.cameraInfo.zoomState.value ?: return true
            val target =
              (zoomState.zoomRatio * detector.scaleFactor)
                .coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)
            cam.cameraControl.setZoomRatio(target)
            return true
          }
        },
      )
    val tapDetector =
      GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
          override fun onSingleTapUp(e: MotionEvent): Boolean {
            focusPoint = Offset(e.x, e.y)
            val viewW = liveSurfaceView.width.toFloat().coerceAtLeast(1f)
            val viewH = liveSurfaceView.height.toFloat().coerceAtLeast(1f)
            cropFocusNormalized =
              Offset(
                (e.x / viewW).coerceIn(0f, 1f),
                (e.y / viewH).coerceIn(0f, 1f),
              )
            val cam = camera ?: return true
            // If the tracker-driven lock is currently pinning focus via
            // Camera2 interop (AF_MODE=OFF + steered LENS_FOCUS_DISTANCE),
            // startFocusAndMetering would be silently overridden on the
            // next capture request. Clear the interop bundle first, drop
            // the latched distance and the scale baseline so the focus
            // controller waits for AF to re-converge at the tapped point
            // before re-seeding, and reset the lock flag.
            if (focusLockedRef.getAndSet(false)) {
              runCatching {
                Camera2CameraControl.from(cam.cameraControl).captureRequestOptions =
                  CaptureRequestOptions.Builder().build()
              }.onFailure { Log.w(TAG, "clear interop options failed", it) }
              latestFocusedDistance.value = null
              focusBaselineRef.set(null)
              heldFocusDiopters.set(null)
            }
            // Without a PreviewView there's no built-in
            // MeteringPointFactory, so we hand-build one against the
            // surface dimensions. Normalised coords are in [0, 1] over
            // (viewW × viewH); the factory maps that to the camera's
            // active sensor region under the hood.
            val factory = SurfaceOrientedMeteringPointFactory(viewW, viewH)
            val point = factory.createPoint(e.x, e.y)
            val action =
              FocusMeteringAction
                .Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                .build()
            runCatching { cam.cameraControl.startFocusAndMetering(action) }
              .onFailure { Log.w(TAG, "focus action failed", it) }
            return true
          }
        },
      )
    liveSurfaceView.setOnTouchListener { _, event ->
      scaleDetector.onTouchEvent(event)
      tapDetector.onTouchEvent(event)
      true
    }
    onDispose { liveSurfaceView.setOnTouchListener(null) }
  }

  DisposableEffect(lifecycleOwner, liveSurfaceView) {
    val providerFuture = ProcessCameraProvider.getInstance(context)
    providerFuture.addListener({
      val provider = providerFuture.get()
      try {
        provider.unbindAll()
        // CameraX `Preview` writes camera frames into the GL surface
        // view's SurfaceTexture (GL_TEXTURE_EXTERNAL_OES). The render
        // thread, woken by SurfaceTexture.onFrameAvailable, GPU-renders
        // canonical luma for the tracker and composites the same
        // external texture + overlay into the EGL surface. One camera
        // consumer; no `ImageAnalysis` stream, no per-frame CPU bytes.
        preview.setSurfaceProvider(cameraSurfaceExecutor) { request ->
          liveSurfaceView.provideSurfaceRequest(request, cameraSurfaceExecutor)
        }
        val boundCamera =
          provider.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            imageCapture,
            preview,
          )
        camera = boundCamera
        hasFlashUnit = boundCamera.cameraInfo.hasFlashUnit()
        // Sensor mount angle (CW degrees the sensor is rotated vs the
        // natural display orientation). Plumbed into the UV xform so the
        // composite is upright on devices with a non-90° mount, instead
        // of hard-coding the back-camera-on-portrait-phone case.
        liveSurfaceView.setCameraOrientationDegrees(boundCamera.cameraInfo.sensorRotationDegrees)
      } catch (e: Exception) {
        Log.e(TAG, "Failed to bind camera", e)
      }
    }, mainExecutor)

    onDispose {
      runCatching { providerFuture.get().unbindAll() }
    }
  }

  LaunchedEffect(torchOn, cameraControl) {
    cameraControl?.enableTorch(torchOn && hasFlashUnit)
  }

  Box(
    modifier =
      Modifier
        .fillMaxSize()
        .onSizeChanged { previewSizePx = it },
  ) {
    // Single surface for both camera pixels and overlay. The engine
    // composites them in Rust per analyzer frame and we blit the
    // result; no two-surface drift possible. When the live overlay is
    // off (toggled by the user or unavailable because OCR models for
    // this language aren't installed), the engine still receives
    // frames and composites camera-only — the tracker is held in
    // SUPPRESSED so no acquire/refresh worker runs.
    AndroidView(
      factory = { liveSurfaceView },
      modifier = Modifier.fillMaxSize(),
      update = { },
    )

    if (DEBUG_SHOW_TRACKER_STATUS) {
      val status by liveOcrEngine.trackerStatus.collectAsState()
      TrackerStatusPill(status)
    }

    val indicatorSizeDp = 72.dp
    val indicatorRadiusPx =
      with(androidx.compose.ui.platform.LocalDensity.current) {
        (indicatorSizeDp / 2).toPx()
      }
    focusPoint?.let { pt ->
      Box(
        modifier =
          Modifier
            // pt comes from a raw MotionEvent (origin at the physical
            // left edge), so its placement must ignore layout direction —
            // absoluteOffset + AbsoluteAlignment.TopLeft, or the ring
            // mirrors horizontally under RTL locales.
            .align(AbsoluteAlignment.TopLeft)
            .absoluteOffset {
              IntOffset(
                (pt.x - indicatorRadiusPx).toInt(),
                (pt.y - indicatorRadiusPx).toInt(),
              )
            }
            .size(indicatorSizeDp)
            .scale(focusScale.value)
            .alpha(focusAlpha.value)
            .border(2.dp, Color.White, CircleShape),
      )
    }

    TopLanguagePills(
      from = from,
      to = to,
      isAutoSource = isAutoSource,
      canSwap = canSwap,
      languageState = languageState,
      languageMetadata = languageMetadata,
      onMessage = onMessage,
      showAutoOption = showAutoOption,
      modifier =
        Modifier
          .fillMaxWidth()
          .statusBarsPadding()
          .padding(horizontal = 8.dp, vertical = 4.dp),
    )

    BottomControls(
      torchOn = torchOn,
      hasFlashUnit = hasFlashUnit,
      onTorchToggle = { torchOn = !torchOn },
      liveOverlayOn = liveOverlayOn,
      liveOverlayAvailable = hasPaddleOcrModels,
      onLiveOverlayToggle = {
        if (!hasPaddleOcrModels) {
          Toast
            .makeText(
              context,
              context.getString(R.string.camera_need_ocr_models),
              Toast.LENGTH_SHORT,
            )
            .show()
          return@BottomControls
        }
        liveOverlayOn = !liveOverlayOn
        if (!liveOverlayOn) liveOcrEngine.clear()
      },
      isCapturing = isCapturing,
      onCapture = {
        if (isCapturing) return@BottomControls
        isCapturing = true
        val outputFile = File.createTempFile("camera_capture_", ".jpg", context.cacheDir)
        val outputOptions = ImageCapture.OutputFileOptions.Builder(outputFile).build()
        imageCapture.takePicture(
          outputOptions,
          mainExecutor,
          object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(results: ImageCapture.OutputFileResults) {
              val uri =
                FileProvider.getUriForFile(
                  context,
                  "${context.packageName}.fileprovider",
                  outputFile,
                )
              isCapturing = false
              onImageCaptured(uri)
            }

            override fun onError(exception: ImageCaptureException) {
              Log.e(TAG, "Image capture failed", exception)
              outputFile.delete()
              isCapturing = false
            }
          },
        )
      },
      modifier =
        Modifier
          .align(Alignment.BottomCenter)
          .fillMaxWidth(),
    )
  }
}

@Composable
private fun TopLanguagePills(
  from: Language,
  to: Language,
  isAutoSource: Boolean,
  canSwap: Boolean,
  languageState: LanguageAvailabilityState,
  languageMetadata: Map<Language, LanguageMetadata>,
  onMessage: (TranslatorMessage) -> Unit,
  showAutoOption: Boolean,
  modifier: Modifier = Modifier,
) {
  val fromLanguages = languageState.sourceLanguages().filter { isAutoSource || it != from }
  val toLanguages = languageState.targetLanguages().filter { (isAutoSource || it != from) && it != to }
  val pillShape = RoundedCornerShape(50)
  val pillBackground = Color.Black.copy(alpha = 0.5f)

  val pillWidth = 110.dp
  Row(
    modifier = modifier,
    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      modifier =
        Modifier
          .width(pillWidth)
          .clip(pillShape)
          .background(pillBackground),
    ) {
      LanguageSelector(
        selectedLanguage = from,
        availableLanguages = fromLanguages,
        languageMetadata = languageMetadata,
        onLanguageSelected = { onMessage(TranslatorMessage.FromLang(it)) },
        isAutoSource = isAutoSource,
        showAutoOption = showAutoOption,
        onAutoSelected = { onMessage(TranslatorMessage.EnableAutoSource) },
        textColor = Color.White,
        marquee = false,
      )
    }
    CompositionLocalProvider(LocalContentColor provides Color.White) {
      IconButton(
        onClick = { onMessage(TranslatorMessage.SwapLanguages) },
        enabled = canSwap,
        modifier = Modifier.size(32.dp),
      ) {
        Icon(
          painter = painterResource(id = R.drawable.compare),
          contentDescription = stringResource(R.string.a11y_reverse_translation_direction),
        )
      }
    }
    Box(
      modifier =
        Modifier
          .width(pillWidth)
          .clip(pillShape)
          .background(pillBackground),
    ) {
      LanguageSelector(
        selectedLanguage = to,
        availableLanguages = toLanguages,
        languageMetadata = languageMetadata,
        onLanguageSelected = { onMessage(TranslatorMessage.ToLang(it)) },
        textColor = Color.White,
        marquee = false,
      )
    }
  }
}

@Composable
private fun BottomControls(
  torchOn: Boolean,
  hasFlashUnit: Boolean,
  onTorchToggle: () -> Unit,
  liveOverlayOn: Boolean,
  liveOverlayAvailable: Boolean,
  onLiveOverlayToggle: () -> Unit,
  isCapturing: Boolean,
  onCapture: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier =
      modifier
        .navigationBarsPadding()
        .padding(horizontal = 24.dp, vertical = 24.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    IconButton(
      onClick = onTorchToggle,
      enabled = hasFlashUnit,
    ) {
      Icon(
        painter = painterResource(if (torchOn) R.drawable.flash_on else R.drawable.flash_off),
        contentDescription = if (torchOn) stringResource(R.string.a11y_torch_on) else stringResource(R.string.a11y_torch_off),
        tint = if (hasFlashUnit) Color.White else Color.White.copy(alpha = 0.3f),
      )
    }

    Box(
      modifier =
        Modifier
          .size(72.dp)
          .clip(CircleShape)
          .border(4.dp, Color.White, CircleShape)
          .padding(6.dp)
          .clip(CircleShape)
          .background(if (isCapturing) Color.White.copy(alpha = 0.5f) else Color.White)
          .clickable(enabled = !isCapturing, onClick = onCapture),
    )

    IconButton(onClick = onLiveOverlayToggle) {
      Icon(
        painter = painterResource(R.drawable.auto_awesome),
        contentDescription =
          if (liveOverlayOn) {
            stringResource(
              R.string.a11y_live_overlay_on,
            )
          } else {
            stringResource(R.string.a11y_live_overlay_off)
          },
        tint =
          when {
            !liveOverlayAvailable -> Color.White.copy(alpha = 0.25f)
            liveOverlayOn -> Color.White
            else -> Color.White.copy(alpha = 0.4f)
          },
      )
    }
  }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.TrackerStatusPill(status: dev.davidv.translator.TrackerStatus) {
  val (color, label) =
    when (status.state) {
      uniffi.bindings.PlanarTrackerState.IDLE ->
        androidx.compose.ui.graphics.Color(0xCC444444) to "IDLE"
      uniffi.bindings.PlanarTrackerState.ACQUIRING ->
        androidx.compose.ui.graphics.Color(0xCCB58900) to "ACQUIRING"
      uniffi.bindings.PlanarTrackerState.LOCKED ->
        androidx.compose.ui.graphics.Color(0xCC2AA198) to "LOCKED"
      uniffi.bindings.PlanarTrackerState.LOST ->
        androidx.compose.ui.graphics.Color(0xCCDC322F) to "LOST"
    }
  // Format: STATE | anchor#N inliers | det=D rec_ok=K rec_empty=M pending=P
  val detPart =
    if (status.lastAcquireDet < 0) {
      ""
    } else {
      " | det=${status.lastAcquireDet} rec_ok=${status.lastAcquireRecOk} rec_empty=${status.lastAcquireRecEmpty} pending=${status.lastAcquirePending}"
    }
  val anchorPart =
    if (status.anchorId == 0L) {
      ""
    } else {
      " | a#${status.anchorId} inl=${status.inliers}"
    }
  val text = "$label$anchorPart$detPart"
  androidx.compose.foundation.layout.Box(
    modifier =
      androidx.compose.ui.Modifier
        .align(androidx.compose.ui.Alignment.TopStart)
        .padding(top = 56.dp, start = 8.dp)
        .background(color, shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
        .padding(horizontal = 8.dp, vertical = 4.dp),
  ) {
    androidx.compose.material3.Text(
      text = text,
      color = androidx.compose.ui.graphics.Color.White,
      style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
    )
  }
}
