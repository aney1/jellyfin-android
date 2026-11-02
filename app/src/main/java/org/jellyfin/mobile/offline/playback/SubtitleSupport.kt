package org.jellyfin.mobile.offline.playback

import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType

/**
 * Fork: offline. Image based subtitle formats, which are not stored for offline playback.
 */
internal val IMAGE_SUBTITLE_CODECS = setOf("pgs", "pgssub", "sup", "dvdsub", "vobsub", "dvbsub", "idx", "sub")

/**
 * Whether an external subtitle stream is stored with a download for offline playback.
 */
val MediaStream.isDownloadableExternalSubtitle: Boolean
    get() = type == MediaStreamType.SUBTITLE && isExternal && codec?.lowercase() !in IMAGE_SUBTITLE_CODECS
