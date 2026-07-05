package org.jellyfin.mobile.player.ui

import android.content.res.Configuration
import android.media.AudioManager
import android.provider.Settings
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
import android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.core.content.getSystemService
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.postDelayed
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout
import com.google.android.exoplayer2.ui.PlayerView
import org.jellyfin.mobile.R
import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.databinding.FragmentPlayerBinding
import org.jellyfin.mobile.utils.Constants
import org.jellyfin.mobile.utils.brightness
import org.jellyfin.mobile.utils.dip
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.math.abs
import com.google.android.exoplayer2.ui.R as ExoplayerR

class PlayerGestureHelper(
    private val fragment: PlayerFragment,
    private val playerBinding: FragmentPlayerBinding,
    private val playerLockScreenHelper: PlayerLockScreenHelper,
) : KoinComponent {
    private val appPreferences: AppPreferences by inject()
    private val audioManager: AudioManager by lazy { fragment.requireActivity().getSystemService()!! }
    private val playerView: PlayerView by playerBinding::playerView
    private val gestureIndicatorOverlayLayout: LinearLayout by playerBinding::gestureOverlayLayout
    private val gestureIndicatorOverlayImage: ImageView by playerBinding::gestureOverlayImage
    private val gestureIndicatorOverlayProgress: ProgressBar by playerBinding::gestureOverlayProgress

    /**
     * The ExoPlayer content frame (holds the video surface). Freeform zoom scales and translates
     * this view; subtitles live in a separate overlay and are left untouched.
     */
    private val contentFrame: View by lazy { playerView.findViewById(ExoplayerR.id.exo_content_frame) }

    init {
        if (appPreferences.exoPlayerRememberBrightness) {
            fragment.requireActivity().window.brightness = appPreferences.exoPlayerBrightness
        }
    }

    /**
     * Tracks whether video content should fill the screen, cutting off unwanted content on the sides.
     * Useful on wide-screen phones to remove black bars from some movies.
     */
    private var isZoomEnabled = false

    /**
     * Freeform (pinch) zoom state, active once the user zooms in beyond the fill-screen mode.
     * [freeformZoomScale] of 1 is the fill baseline; the content frame is scaled and translated
     * (pivot at its top-left) to zoom around the gesture focus and to allow panning.
     */
    private var freeformZoomActive = false
    private var freeformZoomScale = 1f
    private var freeformTranslationX = 0f
    private var freeformTranslationY = 0f
    private var lastFocusX = 0f
    private var lastFocusY = 0f

    /**
     * Tracks a value during a swipe gesture (between multiple onScroll calls).
     * When the gesture starts it's reset to an initial value and gets increased or decreased
     * (depending on the direction) as the gesture progresses.
     */
    private var swipeGestureValueTracker = -1f

    /**
     * Tracks whether the fullscreen/portrait toggle has already been triggered during the
     * current center swipe gesture, so it only fires once per swipe.
     */
    private var swipeGestureFullscreenTriggered = false

    /**
     * True from ACTION_DOWN until ACTION_UP/CANCEL while the gesture started within the system's
     * left back-gesture zone. Such touches are ignored entirely (not routed to any gesture
     * detector), so the player controls can never open as a side effect of a back-navigation
     * swipe that the OS lets fall through to the app.
     */
    private var leftEdgeGestureActive = false

    /**
     * The width of the system's left back-gesture zone, or 0 when gesture navigation is disabled
     * (e.g. 3-button navigation), in which case no touches are suppressed.
     */
    private val leftBackGestureZoneWidth: Int
        get() = ViewCompat.getRootWindowInsets(playerView)
            ?.getInsets(WindowInsetsCompat.Type.systemGestures())
            ?.left
            ?: 0

    /**
     * The region a swipe gesture operates on, locked when the gesture starts.
     *
     * This must not be recomputed on every [GestureDetector.SimpleOnGestureListener.onScroll] call:
     * the fullscreen swipe can rotate the screen mid-gesture, which changes [PlayerView.getMeasuredWidth]
     * while the same swipe is still ongoing. Re-classifying [firstEvent] against the new width would
     * misassign the swipe to a different region (e.g. switch from fullscreen to volume).
     */
    private var swipeGestureRegion = SwipeGestureRegion.NONE

    /**
     * Runnable that hides [playerView] controller
     */
    private val hidePlayerViewControllerAction = Runnable {
        playerView.hideController()
    }

    /**
     * Runnable that hides [gestureIndicatorOverlayLayout]
     */
    private val hideGestureIndicatorOverlayAction = Runnable {
        gestureIndicatorOverlayLayout.isVisible = false
    }

    /**
     * Handles taps when controls are locked
     */
    private val unlockDetector = GestureDetector(
        playerView.context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                playerLockScreenHelper.peekUnlockButton()
                return true
            }
        },
    )

    /**
     * Handles double tap to seek and brightness/volume gestures
     */
    private val gestureDetector = GestureDetector(
        playerView.context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val viewWidth = playerView.measuredWidth
                val viewHeight = playerView.measuredHeight
                val viewCenterX = viewWidth / 2
                val viewCenterY = viewHeight / 2
                val isFastForward = e.x.toInt() > viewCenterX

                // Show ripple effect
                playerView.foreground?.apply {
                    val left = if (isFastForward) viewCenterX else 0
                    val right = if (isFastForward) viewWidth else viewCenterX
                    setBounds(left, viewCenterY - viewCenterX / 2, right, viewCenterY + viewCenterX / 2)
                    setHotspot(e.x, e.y)
                    state = intArrayOf(android.R.attr.state_enabled, android.R.attr.state_pressed)
                    playerView.postDelayed(Constants.DOUBLE_TAP_RIPPLE_DURATION_MS) {
                        state = IntArray(0)
                    }
                }

                // Fast-forward/rewind
                with(fragment) { if (isFastForward) onFastForward() else onRewind() }

                // Cancel previous runnable to not hide controller while seeking
                playerView.removeCallbacks(hidePlayerViewControllerAction)

                // Ensure controller gets hidden after seeking
                playerView.postDelayed(hidePlayerViewControllerAction, Constants.DEFAULT_CONTROLS_TIMEOUT_MS.toLong())
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                playerView.apply {
                    if (!isControllerVisible) showController() else hideController()
                }
                return true
            }

            @Suppress("ReturnCount")
            override fun onScroll(
                firstEvent: MotionEvent?,
                currentEvent: MotionEvent,
                distanceX: Float,
                distanceY: Float,
            ): Boolean {
                // While freeform-zoomed, a one-finger drag pans the viewport.
                if (freeformZoomActive) {
                    freeformTranslationX -= distanceX
                    freeformTranslationY -= distanceY
                    clampFreeformPan()
                    applyFreeformTransform()
                    return true
                }

                val allowSwipeGestures = appPreferences.exoPlayerAllowSwipeGestures
                val allowFullscreenSwipe = appPreferences.exoPlayerAllowFullscreenSwipeGesture
                if (!allowSwipeGestures && !allowFullscreenSwipe) {
                    return false
                }

                // Check whether swipe was started in excluded region
                val exclusionSize = playerView.resources.dip(Constants.SWIPE_GESTURE_EXCLUSION_SIZE_VERTICAL)
                if (
                    firstEvent == null ||
                    firstEvent.y < exclusionSize ||
                    firstEvent.y > playerView.height - exclusionSize
                ) {
                    return false
                }

                // Check whether swipe was oriented vertically
                if (abs(distanceY / distanceX) < 2) {
                    return false
                }

                // Lock the region on the first scroll event of the gesture. The fullscreen swipe
                // can rotate the screen mid-gesture, so the region must not be recomputed afterwards.
                if (swipeGestureRegion == SwipeGestureRegion.NONE) {
                    swipeGestureRegion = determineSwipeGestureRegion(firstEvent.x.toInt(), allowFullscreenSwipe)
                }

                return when (swipeGestureRegion) {
                    SwipeGestureRegion.BRIGHTNESS -> if (allowSwipeGestures) {
                        changeBrightness(distanceY)
                        true
                    } else {
                        false
                    }
                    SwipeGestureRegion.VOLUME -> if (allowSwipeGestures) {
                        changeVolume(distanceY)
                        true
                    } else {
                        false
                    }
                    SwipeGestureRegion.FULLSCREEN -> handleFullscreenSwipe(firstEvent, currentEvent)
                    SwipeGestureRegion.NONE -> false
                }
            }
        },
    )

    /**
     * Handles scale/zoom gesture
     */
    private val zoomGestureDetector = ScaleGestureDetector(
        playerView.context,
        object : ScaleGestureDetector.OnScaleGestureListener {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean = fragment.isLandscape()

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val scaleFactor = detector.scaleFactor
                if (freeformZoomActive) {
                    applyFreeformZoom(scaleFactor, detector.focusX, detector.focusY)
                    lastFocusX = detector.focusX
                    lastFocusY = detector.focusY
                    return true
                }
                if (abs(scaleFactor - Constants.ZOOM_SCALE_BASE) <= Constants.ZOOM_SCALE_THRESHOLD) {
                    return true
                }
                when {
                    // Already filling the screen; zooming further enters freeform zoom.
                    scaleFactor > 1 && isZoomEnabled -> {
                        enterFreeformZoom(detector.focusX, detector.focusY)
                        applyFreeformZoom(scaleFactor, detector.focusX, detector.focusY)
                    }
                    // Fit -> fill
                    scaleFactor > 1 -> {
                        isZoomEnabled = true
                        updateZoomMode(true)
                    }
                    // Fill -> fit
                    isZoomEnabled -> {
                        isZoomEnabled = false
                        updateZoomMode(false)
                    }
                }
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) = Unit
        },
    ).apply { isQuickScaleEnabled = false }

    init {
        @Suppress("ClickableViewAccessibility")
        playerView.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                leftEdgeGestureActive = event.x < leftBackGestureZoneWidth
            }
            if (!leftEdgeGestureActive) {
                if (playerView.useController) {
                    when (event.pointerCount) {
                        1 -> gestureDetector.onTouchEvent(event)
                        2 -> zoomGestureDetector.onTouchEvent(event)
                    }
                } else {
                    unlockDetector.onTouchEvent(event)
                }
            }
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                leftEdgeGestureActive = false
                // Hide gesture indicator after timeout, if shown
                gestureIndicatorOverlayLayout.apply {
                    if (isVisible) {
                        removeCallbacks(hideGestureIndicatorOverlayAction)
                        postDelayed(
                            hideGestureIndicatorOverlayAction,
                            Constants.DEFAULT_CENTER_OVERLAY_TIMEOUT_MS.toLong(),
                        )
                    }
                }
                swipeGestureValueTracker = -1f
                swipeGestureFullscreenTriggered = false
                swipeGestureRegion = SwipeGestureRegion.NONE
            }
            true
        }
    }

    /**
     * Classify which region a swipe starting at [swipeX] belongs to.
     *
     * When the fullscreen swipe gesture is enabled, the screen is split into three vertical regions:
     * brightness (left), fullscreen toggle (center), volume (right). Otherwise it keeps the classic
     * two-way split: brightness (left), volume (right).
     */
    private fun determineSwipeGestureRegion(swipeX: Int, allowFullscreenSwipe: Boolean): SwipeGestureRegion {
        val viewWidth = playerView.measuredWidth
        return if (allowFullscreenSwipe) {
            val regionWidth = viewWidth / FULLSCREEN_SWIPE_REGION_COUNT
            when {
                swipeX < regionWidth -> SwipeGestureRegion.BRIGHTNESS
                swipeX > viewWidth - regionWidth -> SwipeGestureRegion.VOLUME
                else -> SwipeGestureRegion.FULLSCREEN
            }
        } else {
            if (swipeX > viewWidth / 2) SwipeGestureRegion.VOLUME else SwipeGestureRegion.BRIGHTNESS
        }
    }

    /**
     * Adjust the media volume based on a vertical [distanceY] swipe and show the volume indicator.
     */
    private fun changeVolume(distanceY: Float) {
        val distanceFull = playerView.measuredHeight * Constants.FULL_SWIPE_RANGE_SCREEN_RATIO
        val ratioChange = distanceY / distanceFull

        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (swipeGestureValueTracker == -1f) swipeGestureValueTracker = currentVolume.toFloat()

        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val change = ratioChange * maxVolume
        swipeGestureValueTracker += change

        val toSet = swipeGestureValueTracker.toInt().coerceIn(0, maxVolume)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, toSet, 0)

        gestureIndicatorOverlayImage.setImageResource(R.drawable.ic_volume_white_24dp)
        gestureIndicatorOverlayProgress.max = maxVolume
        gestureIndicatorOverlayProgress.progress = toSet
        gestureIndicatorOverlayLayout.isVisible = true
    }

    /**
     * Adjust the screen brightness based on a vertical [distanceY] swipe and show the brightness indicator.
     */
    private fun changeBrightness(distanceY: Float) {
        val distanceFull = playerView.measuredHeight * Constants.FULL_SWIPE_RANGE_SCREEN_RATIO
        val ratioChange = distanceY / distanceFull

        val window = fragment.requireActivity().window
        val brightnessRange = BRIGHTNESS_OVERRIDE_OFF..BRIGHTNESS_OVERRIDE_FULL

        // Initialize on first swipe
        if (swipeGestureValueTracker == -1f) {
            val brightness = window.brightness
            swipeGestureValueTracker = when (brightness) {
                in brightnessRange -> brightness
                else -> {
                    Settings.System.getFloat(
                        fragment.requireActivity().contentResolver,
                        Settings.System.SCREEN_BRIGHTNESS,
                    ) / Constants.SCREEN_BRIGHTNESS_MAX
                }
            }
        }

        swipeGestureValueTracker = (swipeGestureValueTracker + ratioChange).coerceIn(brightnessRange)
        window.brightness = swipeGestureValueTracker
        if (appPreferences.exoPlayerRememberBrightness) {
            appPreferences.exoPlayerBrightness = swipeGestureValueTracker
        }

        gestureIndicatorOverlayImage.setImageResource(R.drawable.ic_brightness_white_24dp)
        gestureIndicatorOverlayProgress.max = Constants.PERCENT_MAX
        gestureIndicatorOverlayProgress.progress = (swipeGestureValueTracker * Constants.PERCENT_MAX).toInt()
        gestureIndicatorOverlayLayout.isVisible = true
    }

    /**
     * Trigger the fullscreen/portrait/mini-player transition once a center swipe covers enough
     * vertical distance. Swiping up enables fullscreen (landscape). Swiping down leaves fullscreen
     * (back to portrait); swiping down again while already in portrait shrinks into the mini player.
     */
    private fun handleFullscreenSwipe(firstEvent: MotionEvent, currentEvent: MotionEvent): Boolean {
        if (swipeGestureFullscreenTriggered) {
            return true
        }

        // Positive when swiping up, negative when swiping down
        val verticalDistance = firstEvent.y - currentEvent.y
        val triggerDistance = playerView.measuredHeight * Constants.FULLSCREEN_SWIPE_RANGE_SCREEN_RATIO
        if (abs(verticalDistance) >= triggerDistance) {
            swipeGestureFullscreenTriggered = true
            when {
                verticalDistance > 0 -> fragment.setFullscreenBySwipe(fullscreen = true)
                fragment.isLandscape() -> fragment.setFullscreenBySwipe(fullscreen = false)
                else -> fragment.enterMiniPlayer()
            }
        }
        return true
    }

    fun handleConfiguration(newConfig: Configuration) {
        // Leaving landscape resets freeform zoom along with the fill mode.
        if (!fragment.isLandscape(newConfig) && freeformZoomActive) {
            exitFreeformZoom()
        }
        // Portrait videos must always be letterboxed (fit) after a rotation - filling the screen
        // would crop away most of the frame in landscape.
        if (!fragment.isCurrentVideoLandscape) {
            isZoomEnabled = false
            if (freeformZoomActive) {
                exitFreeformZoom()
            }
        }
        updateZoomMode(fragment.isLandscape(newConfig) && isZoomEnabled)
    }

    private fun updateZoomMode(enabled: Boolean) {
        playerView.resizeMode = if (enabled) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
    }

    private fun enterFreeformZoom(focusX: Float, focusY: Float) {
        freeformZoomActive = true
        freeformZoomScale = 1f
        freeformTranslationX = 0f
        freeformTranslationY = 0f
        lastFocusX = focusX
        lastFocusY = focusY
        contentFrame.pivotX = 0f
        contentFrame.pivotY = 0f
    }

    /**
     * Apply a pinch step in freeform zoom: scale around the gesture focus (keeping that point
     * fixed) and pan by any focus movement. Drops back to fill mode once zoomed fully out.
     */
    private fun applyFreeformZoom(scaleFactor: Float, focusX: Float, focusY: Float) {
        val newScale = (freeformZoomScale * scaleFactor).coerceIn(1f, MAX_FREEFORM_ZOOM)
        val appliedFactor = newScale / freeformZoomScale
        val focusDeltaX = focusX - lastFocusX
        val focusDeltaY = focusY - lastFocusY

        // Keep the focus point stationary while scaling (pivot at the content frame's top-left).
        freeformTranslationX = focusX * (1 - appliedFactor) + appliedFactor * freeformTranslationX + focusDeltaX
        freeformTranslationY = focusY * (1 - appliedFactor) + appliedFactor * freeformTranslationY + focusDeltaY
        freeformZoomScale = newScale

        if (freeformZoomScale <= 1f) {
            exitFreeformZoom()
            return
        }
        clampFreeformPan()
        applyFreeformTransform()
    }

    private fun exitFreeformZoom() {
        freeformZoomActive = false
        freeformZoomScale = 1f
        freeformTranslationX = 0f
        freeformTranslationY = 0f
        contentFrame.scaleX = 1f
        contentFrame.scaleY = 1f
        contentFrame.translationX = 0f
        contentFrame.translationY = 0f
    }

    /**
     * Constrain the pan so the zoomed content always covers the player viewport (no gaps).
     */
    private fun clampFreeformPan() {
        val minTranslationX = contentFrame.width * (1 - freeformZoomScale)
        val minTranslationY = contentFrame.height * (1 - freeformZoomScale)
        freeformTranslationX = freeformTranslationX.coerceIn(minTranslationX, 0f)
        freeformTranslationY = freeformTranslationY.coerceIn(minTranslationY, 0f)
    }

    private fun applyFreeformTransform() {
        contentFrame.scaleX = freeformZoomScale
        contentFrame.scaleY = freeformZoomScale
        contentFrame.translationX = freeformTranslationX
        contentFrame.translationY = freeformTranslationY
    }

    /**
     * The region a vertical swipe gesture controls, locked for the duration of a single gesture.
     */
    private enum class SwipeGestureRegion {
        NONE,
        BRIGHTNESS,
        VOLUME,
        FULLSCREEN,
    }

    companion object {
        /**
         * Number of equal-width vertical regions the player is split into when the fullscreen
         * swipe gesture is enabled: brightness (left), fullscreen toggle (center), volume (right).
         */
        private const val FULLSCREEN_SWIPE_REGION_COUNT = 3

        /**
         * Maximum freeform zoom magnification (relative to the fill-screen baseline).
         */
        private const val MAX_FREEFORM_ZOOM = 4f
    }
}
