package org.jellyfin.mobile.offline.playlist

import android.content.Context
import android.text.InputType
import android.webkit.JavascriptInterface
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.jellyfin.mobile.R
import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.downloads.DownloadStatus
import org.jellyfin.mobile.offline.OfflinePreferences
import org.jellyfin.mobile.offline.data.OfflineDao
import org.jellyfin.mobile.offline.data.PlaylistDownloadMode
import org.jellyfin.mobile.offline.data.PlaylistEntity
import org.jellyfin.mobile.offline.sync.OfflineDownloads
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber
import java.util.UUID

/**
 * Fork: offline. JavaScript bridge (`window.NativeOffline`) behind the download controls that
 * PlaylistDownloadInjector.js adds to the web app's playlist page.
 *
 * Playlists are only stored once the user does something with them (download mode, limits, a download), merely
 * visiting a playlist page stores nothing.
 */
@Suppress("unused", "TooManyFunctions")
class OfflineBridge(private val fragment: Fragment) : KoinComponent {
    private val preferences: OfflinePreferences by inject()
    private val appPreferences: AppPreferences by inject()
    private val offlineDao: OfflineDao by inject()
    private val playlistSync: PlaylistSync by inject()
    private val offlineDownloads: OfflineDownloads by inject()

    private val scope get() = fragment.lifecycleScope

    @JavascriptInterface
    fun isEnabled(): Boolean = preferences.playlistControls

    /**
     * A playlist page was shown: a stored playlist is refreshed, and downloads new videos in automatic mode.
     */
    @JavascriptInterface
    fun onPlaylistShown(playlistId: String, name: String?) {
        launch(playlistId, name, create = false) { playlist ->
            name?.let { playlistSync.setName(playlist, it) }
            playlistSync.sync(playlist, download = playlist.mode == PlaylistDownloadMode.AUTO, enqueuer = offlineDownloads.userEnqueuer)
        }
    }

    /**
     * Download state of the playlist and of the given videos (comma separated ids of the rows on the page), as JSON.
     */
    @JavascriptInterface
    fun getState(playlistId: String, itemIds: String): String = runBlocking(Dispatchers.IO) {
        val id = playlistId.toUUIDOrNull()
        val serverId = appPreferences.currentServerId
        if (id == null || serverId == null) return@runBlocking "{}"
        val items = itemIds.split(',').mapNotNull { it.trim().toUUIDOrNull() }
        val playlist = offlineDao.getPlaylist(serverId, id)
        val excluded = if (playlist != null) offlineDao.getExclusions(serverId, id).map { it.itemId }.toSet() else emptySet()
        val downloads = offlineDownloads.downloadsOf(serverId, items)

        buildJsonObject {
            put("mode", (playlist?.mode ?: PlaylistDownloadMode.OFF).name)
            put("total", items.size)
            put("downloaded", items.count { downloads[it]?.status == DownloadStatus.DOWNLOADED })
            put("pending", items.count { downloads[it]?.status in PENDING })
            putJsonObject("items") {
                for (item in items) {
                    putJsonObject(item.hex()) {
                        put("status", downloads[item]?.status?.name?.lowercase() ?: "none")
                        put("excluded", item in excluded)
                    }
                }
            }
        }.toString()
    }

    @JavascriptInterface
    fun downloadNew(playlistId: String, name: String?) {
        launch(playlistId, name, create = true) { playlist ->
            playlistSync.sync(playlist, download = true, enqueuer = offlineDownloads.userEnqueuer)
        }
    }

    @JavascriptInterface
    fun openSettings(playlistId: String, name: String?) {
        val id = playlistId.toUUIDOrNull() ?: return
        scope.launch {
            val serverId = appPreferences.currentServerId ?: return@launch
            showModeDialog(id, name, playlistSync.getPlaylist(serverId, id))
        }
    }

    @JavascriptInterface
    fun openItemMenu(playlistId: String, name: String?, itemId: String, itemName: String?) {
        val playlist = playlistId.toUUIDOrNull() ?: return
        val item = itemId.toUUIDOrNull() ?: return
        scope.launch {
            val serverId = appPreferences.currentServerId ?: return@launch
            showItemDialog(playlist, name, item, itemName, serverId)
        }
    }

    private fun launch(playlistId: String, name: String?, create: Boolean, block: suspend (PlaylistEntity) -> Unit) {
        val id = playlistId.toUUIDOrNull() ?: return
        scope.launch {
            try {
                resolvePlaylist(id, name, create)?.let { block(it) }
            } catch (e: Exception) {
                Timber.e(e, "Playlist download action failed")
            }
        }
    }

    private suspend fun resolvePlaylist(playlistId: UUID, name: String?, create: Boolean): PlaylistEntity? {
        val serverId = appPreferences.currentServerId ?: return null
        val userId = appPreferences.currentUserId ?: return null
        return when {
            create -> playlistSync.getOrCreatePlaylist(serverId, userId, playlistId, name)
            else -> playlistSync.getPlaylist(serverId, playlistId)
        }
    }

    private fun showModeDialog(playlistId: UUID, name: String?, playlist: PlaylistEntity?) {
        val context = fragment.context ?: return
        val modes = PlaylistDownloadMode.entries
        val labels = arrayOf(
            context.getString(R.string.offline_mode_off),
            context.getString(R.string.offline_mode_semi),
            context.getString(R.string.offline_mode_auto),
        )
        AlertDialog.Builder(context)
            .setTitle(R.string.offline_mode_title)
            .setSingleChoiceItems(labels, modes.indexOf(playlist?.mode ?: PlaylistDownloadMode.OFF)) { dialog, which ->
                dialog.dismiss()
                val mode = modes[which]
                launchAction(playlistId, name) { stored ->
                    playlistSync.setMode(stored, mode)
                    // Semi-automatic only downloads on request, the mode change itself only refreshes
                    playlistSync.sync(stored.copy(mode = mode), download = mode == PlaylistDownloadMode.AUTO, enqueuer = offlineDownloads.userEnqueuer)
                }
            }
            .setNeutralButton(R.string.offline_limits) { _, _ -> showLimitsDialog(playlistId, name, playlist) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showLimitsDialog(playlistId: UUID, name: String?, playlist: PlaylistEntity?) {
        val context = fragment.context ?: return
        val padding = context.resources.getDimensionPixelSize(R.dimen.offline_dialog_padding)
        val maxItemsInput = numberInput(context, R.string.offline_limit_items_hint, playlist?.maxItems)
        val maxRuntimeInput = numberInput(context, R.string.offline_limit_runtime_hint, playlist?.maxRuntimeMinutes)
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
            addView(maxItemsInput)
            addView(maxRuntimeInput)
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.offline_limits)
            .setMessage(R.string.offline_limits_message)
            .setView(layout)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val maxItems = maxItemsInput.text.toString().toIntOrNull()?.takeIf { it > 0 }
                val maxRuntime = maxRuntimeInput.text.toString().toIntOrNull()?.takeIf { it > 0 }
                launchAction(playlistId, name) { stored ->
                    playlistSync.setLimits(stored, maxItems, maxRuntime)
                    if (stored.mode == PlaylistDownloadMode.AUTO) {
                        playlistSync.sync(stored, download = true, enqueuer = offlineDownloads.userEnqueuer)
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun launchAction(playlistId: UUID, name: String?, block: suspend (PlaylistEntity) -> Unit) {
        scope.launch {
            try {
                resolvePlaylist(playlistId, name, create = true)?.let { block(it) }
            } catch (e: Exception) {
                Timber.e(e, "Playlist download action failed")
            }
        }
    }

    private fun numberInput(context: Context, hint: Int, value: Int?) = EditText(context).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        setHint(hint)
        setText(value?.toString().orEmpty())
    }

    private suspend fun showItemDialog(playlistId: UUID, name: String?, itemId: UUID, itemName: String?, serverId: Long) {
        val context = fragment.context ?: return
        val playlist = playlistSync.getPlaylist(serverId, playlistId)
        val download = offlineDownloads.downloadsOf(serverId, listOf(itemId))[itemId]
            ?.takeIf { it.status != DownloadStatus.CANCELLED }
        val excluded = playlist != null && offlineDao.getExclusions(serverId, playlistId).any { it.itemId == itemId }
        val autoDownloads = playlist != null && playlist.mode != PlaylistDownloadMode.OFF

        val actions = buildList {
            if (download == null || download.status == DownloadStatus.ERROR) add(R.string.offline_action_download)
            if (download != null) add(if (autoDownloads) R.string.offline_action_delete_exclude else R.string.offline_action_delete)
            if (!excluded) add(R.string.offline_action_exclude) else add(R.string.offline_action_include)
        }
        AlertDialog.Builder(context)
            .setTitle(itemName ?: context.getString(R.string.offline_item_title))
            .setItems(actions.map(context::getString).toTypedArray()) { _, which ->
                launchAction(playlistId, name) { stored ->
                    when (actions[which]) {
                        R.string.offline_action_download -> playlistSync.downloadItem(stored, itemId, offlineDownloads.userEnqueuer)
                        R.string.offline_action_delete, R.string.offline_action_delete_exclude -> playlistSync.deleteDownload(stored, itemId)
                        R.string.offline_action_exclude -> playlistSync.setExcluded(stored, itemId, excluded = true)
                        R.string.offline_action_include -> playlistSync.setExcluded(stored, itemId, excluded = false)
                    }
                }
            }
            .show()
    }

    private fun UUID.hex() = toString().replace("-", "")

    private companion object {
        val PENDING = setOf(DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING)
    }
}
