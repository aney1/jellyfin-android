package org.jellyfin.mobile.player.ui

import android.content.res.Configuration
import android.media.AudioManager
import android.provider.Settings
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
import android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.core.content.getSystemService
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
                if (abs(scaleFactor - Constants.ZOOM_SCALE_BASE) > Constants.ZOOM_SCALE_THRESHOLD) {
                    isZoomEnabled = scaleFactor > 1
                    updateZoomMode(isZoomEnabled)
                }
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) = Unit
        },
    ).apply { isQuickScaleEnabled = false }

    init {
        @Suppress("ClickableViewAccessibility")
        playerView.setOnTouchListener { _, event ->
            if (playerView.useController) {
                when (event.pointerCount) {
                    1 -> gestureDetector.onTouchEvent(event)
                    2 -> zoomGestureDetector.onTouchEvent(event)
                }
            } else {
                unlockDetector.onTouchEvent(event)
            }
            if (event.action == MotionEvent.ACTION_UP) {
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
     * Trigger the fullscreen/portrait toggle once a center swipe covers enough vertical distance.
     * Swiping up enables fullscreen (landscape), swiping down returns to portrait.
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
            fragment.setFullscreenBySwipe(fullscreen = verticalDistance > 0)
        }
        return true
    }

    fun handleConfiguration(newConfig: Configuration) {
        updateZoomMode(fragment.isLandscape(newConfig) && isZoomEnabled)
    }

    private fun updateZoomMode(enabled: Boolean) {
        playerView.resizeMode = if (enabled) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
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
    }
}
