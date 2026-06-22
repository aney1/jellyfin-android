package org.jellyfin.mobile.player.ui

import android.widget.Button
import androidx.core.view.isVisible
import org.jellyfin.mobile.R
import org.jellyfin.sdk.model.api.MediaSegmentDto

/**
 * Drives the single overlay button used for media segments. It can offer to skip the current
 * segment, or — after a segment was skipped or finished — to replay it from the start.
 */
class SkipMediaSegmentButton(
    private val button: Button,
    private val onSkip: (mediaSegment: MediaSegmentDto) -> Unit,
    private val onReplay: (mediaSegment: MediaSegmentDto) -> Unit,
) {
    private var mediaSegment: MediaSegmentDto? = null
    private var mode = Mode.HIDDEN

    private enum class Mode { HIDDEN, SKIP, REPLAY }

    init {
        button.setOnClickListener {
            val segment = mediaSegment ?: return@setOnClickListener
            when (mode) {
                Mode.SKIP -> onSkip(segment)
                Mode.REPLAY -> onReplay(segment)
                Mode.HIDDEN -> Unit
            }
        }
    }

    fun showSkipSegmentButton(mediaSegmentDto: MediaSegmentDto) {
        mediaSegment = mediaSegmentDto
        mode = Mode.SKIP
        button.setText(R.string.skip_media_segment)
        button.isVisible = true
    }

    fun showReplaySegmentButton(mediaSegmentDto: MediaSegmentDto) {
        mediaSegment = mediaSegmentDto
        mode = Mode.REPLAY
        button.setText(R.string.replay_media_segment)
        button.isVisible = true
    }

    fun hideSkipSegmentButton() {
        mediaSegment = null
        mode = Mode.HIDDEN
        button.isVisible = false
    }
}
