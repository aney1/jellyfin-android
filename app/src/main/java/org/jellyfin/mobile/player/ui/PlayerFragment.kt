package org.jellyfin.mobile.player.ui

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.OrientationEventListener
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
import android.widget.FrameLayout
import android.widget.ImageButton
import androidx.annotation.RequiresApi
import androidx.appcompat.widget.AppCompatTextView
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.setPadding
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.jellyfin.mobile.R
import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.databinding.ExoPlayerControlViewBinding
import org.jellyfin.mobile.databinding.FragmentPlayerBinding
import org.jellyfin.mobile.player.PlayerException
import org.jellyfin.mobile.player.PlayerViewModel
import org.jellyfin.mobile.player.interaction.PlayOptions
import org.jellyfin.mobile.player.ui.playermenuhelper.PlayerMenuHelper
import org.jellyfin.mobile.utils.AndroidVersion
import org.jellyfin.mobile.utils.BackPressInterceptor
import org.jellyfin.mobile.utils.Constants
import org.jellyfin.mobile.utils.Constants.DEFAULT_CONTROLS_TIMEOUT_MS
import org.jellyfin.mobile.utils.Constants.PIP_MAX_RATIONAL
import org.jellyfin.mobile.utils.Constants.PIP_MIN_RATIONAL
import org.jellyfin.mobile.utils.SmartOrientationListener
import org.jellyfin.mobile.utils.brightness
import org.jellyfin.mobile.utils.dip
import org.jellyfin.mobile.utils.extensions.aspectRational
import org.jellyfin.mobile.utils.extensions.getParcelableCompat
import org.jellyfin.mobile.utils.extensions.isLandscape
import org.jellyfin.mobile.utils.extensions.keepScreenOn
import org.jellyfin.mobile.utils.toast
import org.jellyfin.mobile.webapp.WebViewFragment
import org.jellyfin.sdk.model.api.MediaSegmentDto
import org.jellyfin.sdk.model.api.MediaStream
import org.koin.android.ext.android.inject
import kotlin.math.max
import androidx.media3.ui.R as Media3R

@Suppress("TooManyFunctions")
class PlayerFragment : Fragment(), BackPressInterceptor {
    private val appPreferences: AppPreferences by inject()
    private val viewModel: PlayerViewModel by viewModels()
    private var _playerBinding: FragmentPlayerBinding? = null
    private val playerBinding: FragmentPlayerBinding get() = _playerBinding!!
    private val playerView: PlayerView get() = playerBinding.playerView
    private val playerOverlay: View get() = playerBinding.playerOverlay
    private val loadingIndicator: View get() = playerBinding.loadingIndicator
    private var _playerControlsBinding: ExoPlayerControlViewBinding? = null
    private val playerControlsBinding: ExoPlayerControlViewBinding get() = _playerControlsBinding!!
    private val playerControlsView: View get() = playerControlsBinding.root
    private val toolbar: Toolbar get() = playerControlsBinding.toolbar
    private val toolbarTitle: AppCompatTextView get() = playerControlsBinding.toolbarTitle
    private val fullscreenSwitcher: ImageButton get() = playerControlsBinding.fullscreenSwitcher
    private var playerMenus: PlayerMenus? = null

    private lateinit var playerFullscreenHelper: PlayerFullscreenHelper
    lateinit var playerLockScreenHelper: PlayerLockScreenHelper
    lateinit var playerGestureHelper: PlayerGestureHelper

    /**
     * Whether the player is currently shrunk into the in-app floating mini player, where the
     * video plays in a corner over the (still-running) web app so it can be navigated.
     */
    private var isMiniPlayer = false

    /**
     * The layout params used when the player fills the screen, saved so they can be restored
     * when leaving the mini player.
     */
    private var fullPlayerLayoutParams: ViewGroup.LayoutParams? = null

    private val currentVideoStream: MediaStream?
        get() = viewModel.mediaSourceOrNull?.selectedVideoStream

    /**
     * Listener that watches the current device orientation.
     * It makes sure that the orientation sensor can still be used (if enabled)
     * after toggling the orientation through the fullscreen button.
     *
     * If the requestedOrientation was reset directly after setting it in the fullscreenSwitcher click handler,
     * the orientation would get reverted before the user had any chance to rotate the device to the desired position.
     */
    private val orientationListener: OrientationEventListener by lazy { SmartOrientationListener(requireActivity()) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val window = requireActivity().window
        playerFullscreenHelper = PlayerFullscreenHelper(window)

        // Observe ViewModel
        viewModel.player.observe(this) { player ->
            playerView.player = player
            // Automatically close fragment, unless we're in PiP mode
            if (player == null && !(AndroidVersion.isAtLeastN && requireActivity().isInPictureInPictureMode)) {
                parentFragmentManager.popBackStack()
            }
        }
        viewModel.playerState.observe(this) { playerState ->
            val isPlaying = viewModel.playerOrNull?.isPlaying == true
            requireActivity().window.keepScreenOn = isPlaying
            loadingIndicator.isVisible = playerState == Player.STATE_BUFFERING
            // Keep the auto-enter Picture-in-Picture state in sync with playback
            updatePictureInPictureParams()
            if (isMiniPlayer) updateMiniPlayPauseIcon()
        }
        viewModel.decoderType.observe(this) { type ->
            playerMenus?.updatedSelectedDecoder(type)
        }
        viewModel.error.observe(this) { message ->
            val safeMessage = message.ifEmpty { requireContext().getString(R.string.player_error_unspecific_exception) }
            requireContext().toast(safeMessage)
        }
        viewModel.queueManager.currentMediaSource.observe(this) { mediaSource ->
            if (mediaSource.selectedVideoStream?.isLandscape == false) {
                // For portrait videos, immediately enable fullscreen
                playerFullscreenHelper.enableFullscreen()
            } else if (appPreferences.exoPlayerStartLandscapeVideoInLandscape) {
                // Auto-switch to landscape for landscape videos if enabled
                requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }

            // Update title and player menus
            toolbarTitle.text = mediaSource.getName(requireContext())
            playerMenus?.onQueueItemChanged(mediaSource, viewModel.queueManager.hasNext())

            // Refresh the Picture-in-Picture aspect ratio for the new media source
            updatePictureInPictureParams()
        }

        // Handle fragment arguments, extract playback options and start playback
        lifecycleScope.launch {
            val context = requireContext()
            val playOptions = requireArguments().getParcelableCompat<PlayOptions>(Constants.EXTRA_MEDIA_PLAY_OPTIONS)
            if (playOptions == null) {
                context.toast(R.string.player_error_invalid_play_options)
                return@launch
            }
            when (viewModel.queueManager.initializePlaybackQueue(playOptions)) {
                is PlayerException.InvalidPlayOptions -> context.toast(R.string.player_error_invalid_play_options)
                is PlayerException.NetworkFailure -> context.toast(R.string.player_error_network_failure)
                is PlayerException.UnsupportedContent -> context.toast(R.string.player_error_unsupported_content)
                null -> Unit // success
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _playerBinding = FragmentPlayerBinding.inflate(layoutInflater)
        _playerControlsBinding = ExoPlayerControlViewBinding.bind(playerBinding.root.findViewById(R.id.player_controls))
        return playerBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Insets handling
        ViewCompat.setOnApplyWindowInsetsListener(playerBinding.root) { _, insets ->
            // In the mini player the video should fill the tiny window without inset padding
            if (isMiniPlayer) {
                playerView.setPadding(0)
                return@setOnApplyWindowInsetsListener insets
            }

            playerFullscreenHelper.onWindowInsetsChanged(insets)

            val systemInsets = when {
                AndroidVersion.isAtLeastR -> insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars())
                else -> insets.getInsets(WindowInsetsCompat.Type.systemBars())
            }
            if (playerFullscreenHelper.isFullscreen) {
                playerView.setPadding(0)
                playerControlsView.updatePadding(
                    left = max(insets.displayCutout?.safeInsetLeft ?: 0, systemInsets.left),
                    top = max(insets.displayCutout?.safeInsetTop ?: 0, systemInsets.top),
                    right = max(insets.displayCutout?.safeInsetRight ?: 0, systemInsets.right),
                    bottom = max(insets.displayCutout?.safeInsetBottom ?: 0, systemInsets.bottom),
                )
            } else {
                playerView.updatePadding(
                    left = systemInsets.left,
                    top = systemInsets.top,
                    right = systemInsets.right,
                    bottom = systemInsets.bottom,
                )
                playerControlsView.setPadding(0) // Padding is handled by PlayerView
            }
            playerOverlay.updatePadding(
                left = systemInsets.left,
                top = systemInsets.top,
                right = systemInsets.right,
                bottom = systemInsets.bottom,
            )

            // Update fullscreen switcher icon
            val fullscreenDrawable = when {
                playerFullscreenHelper.isFullscreen -> R.drawable.ic_fullscreen_exit_white_32dp
                else -> R.drawable.ic_fullscreen_enter_white_32dp
            }
            fullscreenSwitcher.setImageResource(fullscreenDrawable)

            insets
        }

        // Handle toolbar back button
        toolbar.setNavigationOnClickListener { parentFragmentManager.popBackStack() }

        // Create playback menus
        playerMenus = PlayerMenus(this, playerBinding, playerControlsBinding)

        // Set controller timeout
        suppressControllerAutoHide(false)

        // Disable controller animations
        playerView.setControllerAnimationEnabled(false)

        playerLockScreenHelper = PlayerLockScreenHelper(this, playerBinding, orientationListener)
        playerGestureHelper = PlayerGestureHelper(this, playerBinding, playerLockScreenHelper)

        // Handle fullscreen switcher
        fullscreenSwitcher.setOnClickListener {
            toggleFullscreen()
        }

        setupMiniPlayerControls()
    }

    private fun setupMiniPlayerControls() {
        playerBinding.miniPlayerControls.setOnClickListener {
            expandFromMiniPlayer()
        }
        playerBinding.miniPlayPauseButton.setOnClickListener {
            val player = viewModel.playerOrNull ?: return@setOnClickListener
            if (player.isPlaying) viewModel.pause() else viewModel.play()
            updateMiniPlayPauseIcon()
        }
        playerBinding.miniCloseButton.setOnClickListener {
            parentFragmentManager.popBackStack()
        }
    }

    override fun onStart() {
        super.onStart()
        orientationListener.enable()
    }

    override fun onResume() {
        super.onResume()

        // When returning from another app, fullscreen mode for landscape orientation has to be set again
        if (isLandscape() && !isMiniPlayer) {
            playerFullscreenHelper.enableFullscreen()
        }

        // If playback ended during picture in picture we'll return to the main app when PiP is closed
        if (viewModel.playerOrNull == null) {
            parentFragmentManager.popBackStack()
        }

        // Re-enable auto-enter Picture-in-Picture now that the player is in the foreground again
        updatePictureInPictureParams()
    }

    /**
     * Handle current orientation and update fullscreen state and switcher icon
     */
    private fun updateFullscreenState(configuration: Configuration) {
        // The mini player manages its own (non-fullscreen) window state
        if (isMiniPlayer) {
            return
        }

        // Do not handle any orientation changes while being in Picture-in-Picture mode
        if (AndroidVersion.isAtLeastN && requireActivity().isInPictureInPictureMode) {
            return
        }

        when {
            isLandscape(configuration) -> {
                // Landscape orientation is always fullscreen
                playerFullscreenHelper.enableFullscreen()
            }
            currentVideoStream?.isLandscape != false -> {
                // Disable fullscreen for landscape video in portrait orientation
                playerFullscreenHelper.disableFullscreen()
            }
        }
    }

    /**
     * Toggle fullscreen.
     *
     * If playing a portrait video, this just hides the status and navigation bars.
     * For landscape videos, additionally the screen gets rotated.
     */
    private fun toggleFullscreen() {
        val videoTrack = currentVideoStream
        if (videoTrack == null || videoTrack.isLandscape) {
            val current = resources.configuration.orientation
            requireActivity().requestedOrientation = when (current) {
                Configuration.ORIENTATION_PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            // No need to call playerFullscreenHelper in this case,
            // since the configuration change triggers updateFullscreenState,
            // which does it for us.
        } else {
            playerFullscreenHelper.toggleFullscreen()
        }
    }

    /**
     * Directional variant of [toggleFullscreen] used by the center swipe gesture.
     *
     * Swiping up ([fullscreen] = true) enters fullscreen, rotating to landscape for landscape
     * videos. Swiping down ([fullscreen] = false) leaves fullscreen, rotating back to portrait.
     */
    fun setFullscreenBySwipe(fullscreen: Boolean) {
        val videoTrack = currentVideoStream
        if (videoTrack == null || videoTrack.isLandscape) {
            val isCurrentlyLandscape = isLandscape()
            if (fullscreen == isCurrentlyLandscape) {
                // Already in the requested state
                return
            }
            requireActivity().requestedOrientation = if (fullscreen) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            // The configuration change triggers updateFullscreenState, which updates the
            // fullscreen state and switcher icon for us.
        } else {
            // Portrait video: only toggle the system bars without rotating
            if (fullscreen) playerFullscreenHelper.enableFullscreen() else playerFullscreenHelper.disableFullscreen()
        }
    }

    /**
     * Shrink the player into a floating mini window in the bottom corner so the web app
     * underneath becomes visible and interactive while the video keeps playing.
     */
    fun enterMiniPlayer() {
        if (isMiniPlayer || _playerBinding == null) return
        isMiniPlayer = true

        val root = playerBinding.root
        if (fullPlayerLayoutParams == null) {
            fullPlayerLayoutParams = root.layoutParams
        }
        val miniWidth = resources.dip(MINI_PLAYER_WIDTH_DP)
        val miniMargin = resources.dip(MINI_PLAYER_MARGIN_DP)
        root.layoutParams = FrameLayout.LayoutParams(
            miniWidth,
            miniWidth * MINI_PLAYER_HEIGHT_RATIO / MINI_PLAYER_WIDTH_RATIO,
            Gravity.BOTTOM or Gravity.END,
        ).apply {
            marginEnd = miniMargin
            bottomMargin = miniMargin
        }

        // Hand the system bars and orientation back to the web app
        playerFullscreenHelper.disableFullscreen()
        requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

        // Swap the full controls for the minimal mini overlay
        playerView.useController = false
        playerView.setPadding(0)
        playerOverlay.isVisible = false
        updateMiniPlayPauseIcon()
        playerBinding.miniPlayerControls.isVisible = true
    }

    /**
     * Restore the player to fill the screen again, reversing [enterMiniPlayer].
     */
    fun expandFromMiniPlayer() {
        if (!isMiniPlayer || _playerBinding == null) return
        isMiniPlayer = false

        fullPlayerLayoutParams?.let { playerBinding.root.layoutParams = it }

        playerBinding.miniPlayerControls.isVisible = false
        playerOverlay.isVisible = true
        playerView.useController = true

        // Reapply the full-player system bar / orientation state for the current video
        updateFullscreenState(resources.configuration)
    }

    /**
     * Bring the mini player to the front of the fragment container so it keeps floating above
     * other layered screens (e.g. the Tube Archivist screen) opened on top of the web app.
     */
    fun bringMiniPlayerToFront() {
        if (!isMiniPlayer) return
        val root = _playerBinding?.root ?: return
        root.bringToFront()
        (root.parent as? ViewGroup)?.invalidate()
    }

    private fun updateMiniPlayPauseIcon() {
        val isPlaying = viewModel.playerOrNull?.isPlaying == true
        playerBinding.miniPlayPauseButton.setImageResource(
            if (isPlaying) R.drawable.ic_pause_black_42dp else R.drawable.ic_play_black_42dp,
        )
    }

    override fun onInterceptBackPressed(): Boolean {
        if (isMiniPlayer) {
            // While minimized the user is browsing the web app, so route back there. If the web
            // app can't go back, return false to let the mini player be dismissed (back stack pop).
            val webFragment = parentFragmentManager.fragments.filterIsInstance<WebViewFragment>().firstOrNull()
            return webFragment?.onInterceptBackPressed() ?: false
        }
        return false
    }

    /**
     * If true, the player controls will show indefinitely
     */
    fun suppressControllerAutoHide(suppress: Boolean) {
        playerView.controllerShowTimeoutMs = if (suppress) -1 else DEFAULT_CONTROLS_TIMEOUT_MS
    }

    fun isLandscape(configuration: Configuration = resources.configuration) =
        configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    fun onRewind() = viewModel.rewind()

    fun onFastForward() = viewModel.fastForward()

    fun onSeekByOffset(offsetMs: Long) = viewModel.seekByOffset(offsetMs)

    fun onPreviousChapter() = viewModel.previousChapter()

    fun onNextChapter() = viewModel.nextChapter()

    /**
     * @param callback called if track selection was successful and UI needs to be updated
     */
    fun onAudioTrackSelected(index: Int, callback: TrackSelectionCallback): Job = lifecycleScope.launch {
        if (viewModel.trackSelectionHelper.selectAudioTrack(index)) {
            callback.onTrackSelected(true)
        }
    }

    /**
     * @param callback called if track selection was successful and UI needs to be updated
     */
    fun onSubtitleSelected(index: Int, callback: TrackSelectionCallback): Job = lifecycleScope.launch {
        if (viewModel.trackSelectionHelper.selectSubtitleTrack(index)) {
            callback.onTrackSelected(true)
        }
    }

    /**
     * Toggle subtitles, selecting the first by [MediaStream.index] if there are multiple.
     *
     * @return true if subtitles are enabled now, false if not
     */
    fun toggleSubtitles(callback: TrackSelectionCallback) = lifecycleScope.launch {
        callback.onTrackSelected(viewModel.trackSelectionHelper.toggleSubtitles())
    }

    fun onBitrateChanged(bitrate: Int?, callback: TrackSelectionCallback) = lifecycleScope.launch {
        callback.onTrackSelected(viewModel.changeBitrate(bitrate))
    }

    /**
     * @return true if the playback speed was changed
     */
    fun onSpeedSelected(speed: Float): Boolean {
        return viewModel.setPlaybackSpeed(speed)
    }

    fun onPressSpeedUp(isPressing: Boolean): Boolean {
        return viewModel.setPressSpeedUp(isPressing, Constants.HOLD_SPEEDUP_MULTIPLIER)
    }

    fun onDecoderSelected(type: DecoderType) {
        viewModel.updateDecoderType(type)
    }

    fun onSkipToPrevious() {
        viewModel.skipToPrevious()
    }

    fun onSkipToNext() {
        viewModel.skipToNext()
    }

    fun onSkipMediaSegment(mediaSegmentDto: MediaSegmentDto?) {
        viewModel.skipMediaSegment(mediaSegmentDto)
    }

    fun onPopupDismissed() {
        if (!AndroidVersion.isAtLeastR) {
            updateFullscreenState(resources.configuration)
        }
    }

    fun onUserLeaveHint() {
        // On Android 12+ (API 31+), Picture-in-Picture is entered automatically through
        // setAutoEnterEnabled (see updatePictureInPictureParams). This reliably works with
        // gesture navigation, where onUserLeaveHint is not guaranteed to be called.
        // Only enter manually on older versions that don't support auto-enter.
        if (AndroidVersion.isAtLeastN && !AndroidVersion.isAtLeastS && viewModel.playerOrNull?.isPlaying == true) {
            requireActivity().enterPictureInPicture()
        }
    }

    /**
     * Build the [PictureInPictureParams] describing the current video.
     *
     * @param autoEnter whether the system should automatically enter Picture-in-Picture mode
     * when the user navigates away (API 31+ only). This is what makes PiP work with
     * gesture navigation, where [onUserLeaveHint] is unreliable.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun createPictureInPictureParams(autoEnter: Boolean): PictureInPictureParams =
        PictureInPictureParams.Builder().apply {
            val aspectRational = currentVideoStream?.aspectRational?.let { aspectRational ->
                when {
                    aspectRational < PIP_MIN_RATIONAL -> PIP_MIN_RATIONAL
                    aspectRational > PIP_MAX_RATIONAL -> PIP_MAX_RATIONAL
                    else -> aspectRational
                }
            }
            setAspectRatio(aspectRational)
            val contentFrame: View = playerView.findViewById(Media3R.id.exo_content_frame)
            val contentRect = with(contentFrame) {
                val (x, y) = intArrayOf(0, 0).also(::getLocationInWindow)
                Rect(x, y, x + width, y + height)
            }
            setSourceRectHint(contentRect)
            if (AndroidVersion.isAtLeastS) {
                setAutoEnterEnabled(autoEnter)
            }
        }.build()

    /**
     * Keep the activity's [PictureInPictureParams] up to date so the system can automatically
     * enter Picture-in-Picture mode when playing. Auto-enter is only available on API 31+ and,
     * unlike [onUserLeaveHint], works regardless of the device's navigation mode.
     */
    private fun updatePictureInPictureParams() {
        if (!AndroidVersion.isAtLeastS || _playerBinding == null) return
        // Only auto-enter for an actually playing video. Without the video stream check,
        // audio-only playback (handled by the background service) would also trigger PiP.
        val shouldAutoEnter = currentVideoStream != null && viewModel.playerOrNull?.isPlaying == true
        requireActivity().setPictureInPictureParams(createPictureInPictureParams(autoEnter = shouldAutoEnter))
    }

    /**
     * Disable auto-enter Picture-in-Picture at the activity level. The [PictureInPictureParams]
     * are set on the activity and persist across fragments, so they must be cleared when the
     * player is no longer the foreground surface. Otherwise the app would enter PiP when leaving
     * any other screen.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    private fun disableAutoEnterPictureInPicture() {
        requireActivity().setPictureInPictureParams(
            PictureInPictureParams.Builder().setAutoEnterEnabled(false).build(),
        )
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun Activity.enterPictureInPicture() {
        if (AndroidVersion.isAtLeastO) {
            enterPictureInPictureMode(createPictureInPictureParams(autoEnter = false))
        } else {
            @Suppress("DEPRECATION")
            enterPictureInPictureMode()
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        // Entering system PiP from the mini player: expand back to full so PiP shows just the video
        if (isInPictureInPictureMode && isMiniPlayer) {
            expandFromMiniPlayer()
        }
        playerView.useController = !isInPictureInPictureMode
        if (isInPictureInPictureMode) {
            playerMenus?.dismissPlaybackInfo()
            playerLockScreenHelper.hideUnlockButton()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Handler(Looper.getMainLooper()).post {
            updateFullscreenState(newConfig)
            playerGestureHelper.handleConfiguration(newConfig)
            // A gesture-driven orientation change can leave the controls stuck visible: the
            // interrupted touch cancels their auto-hide timer and it's never re-posted. Re-show
            // while visible to re-arm the timeout so they fade out as expected.
            if (playerView.isControllerFullyVisible) {
                playerView.showController()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        orientationListener.disable()

        // The player is no longer in the foreground, so the activity must not auto-enter PiP
        // until the player is resumed (see updatePictureInPictureParams in onResume).
        if (AndroidVersion.isAtLeastS && requireActivity().isInPictureInPictureMode.not()) {
            disableAutoEnterPictureInPicture()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Detach player from PlayerView
        playerView.player = null

        // Set binding references to null
        _playerBinding = null
        _playerControlsBinding = null
        playerMenus = null
    }

    override fun onDestroy() {
        super.onDestroy()
        with(requireActivity()) {
            // Reset screen orientation
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            playerFullscreenHelper.disableFullscreen()
            // Reset screen brightness
            window.brightness = BRIGHTNESS_OVERRIDE_NONE
        }
    }

    fun setPlayerMenuHelper(menuHelper: PlayerMenuHelper) {
        viewModel.setPlayerMenuHelper(menuHelper)
    }

    companion object {
        private const val MINI_PLAYER_WIDTH_DP = 220
        private const val MINI_PLAYER_MARGIN_DP = 16
        private const val MINI_PLAYER_WIDTH_RATIO = 16
        private const val MINI_PLAYER_HEIGHT_RATIO = 9
    }
}
