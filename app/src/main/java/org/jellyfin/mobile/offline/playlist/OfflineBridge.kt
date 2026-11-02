package org.jellyfin.mobile.offline.playlist

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
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.jellyfin.mobile.R
import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.data.dao.DownloadDao
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
 */
@Suppress("unused", "TooManyFunctions")
class OfflineBridge(private val fragment: Fragment) : KoinComponent {
    private val preferences: OfflinePreferences by inject()
    private val appPreferences: AppPreferences by inject()
    private val offlineDao: OfflineDao by inject()
    private val downloadDao: DownloadDao by inject()
    private val playlistSync: PlaylistSync by inject()
    private val offlineDownloads: OfflineDownloads by inject()

    private val scope get() = fragment.lifecycleScope

    @JavascriptInterface
    fun isEnabled(): Boolean = preferences.playlistControls

    /**
     * The playlist page was shown: refresh its snapshot, and download new videos in automatic mode.
     */
    @JavascriptInterface
    fun onPlaylistShown(playlistId: String) {
        launchWithPlaylist(playlistId, create = true) { playlist ->
            playlistSync.sync(playlist, download = playlist.mode == PlaylistDownloadMode.AUTO, enqueuer = offlineDownloads.userEnqueuer)
        }
    }

    /**
     * Download state of the playlist and each of its videos, as JSON.
     */
    @JavascriptInterface
    fun getState(playlistId: String): String = runBlocking(Dispatchers.IO) {
        val id = playlistId.toUUIDOrNull() ?: return@runBlocking "{}"
        val serverId = appPreferences.currentServerId ?: return@runBlocking "{}"
        val playlist = offlineDao.getPlaylist(serverId, id)
        val entries = offlineDao.getPlaylistEntries(serverId, id)
        val excluded = offlineDao.getExclusions(serverId, id).map { it.itemId }.toSet()
        val downloads = downloadDao.getDownloadsByItemIds(entries.map { it.itemId })
            .filter { it.serverId == serverId }
            .associateBy { it.itemId }

        buildJsonObject {
            put("mode", (playlist?.mode ?: PlaylistDownloadMode.OFF).name)
            put("maxItems", playlist?.maxItems)
            put("maxRuntimeMinutes", playlist?.maxRuntimeMinutes)
            put("total", entries.size)
            put("downloaded", entries.count { downloads[it.itemId]?.status == DownloadStatus.DOWNLOADED })
            put("pending", entries.count { downloads[it.itemId]?.status in PENDING })
            putJsonObject("items") {
                for (entry in entries) {
                    putJsonObject(entry.itemId.hex()) {
                        put("status", downloads[entry.itemId]?.status?.name?.lowercase() ?: "none")
                        put("excluded", entry.itemId in excluded)
                    }
                }
            }
        }.toString()
    }

    @JavascriptInterface
    fun downloadNew(playlistId: String) {
        launchWithPlaylist(playlistId, create = true) { playlist ->
            playlistSync.sync(playlist, download = true, enqueuer = offlineDownloads.userEnqueuer)
        }
    }

    @JavascriptInterface
    fun openSettings(playlistId: String) {
        launchWithPlaylist(playlistId, create = true) { playlist ->
            showModeDialog(playlist)
        }
    }

    @JavascriptInterface
    fun openItemMenu(playlistId: String, itemId: String, itemName: String?) {
        val item = itemId.toUUIDOrNull() ?: return
        launchWithPlaylist(playlistId, create = true) { playlist ->
            showItemDialog(playlist, item, itemName)
        }
    }

    private fun launchWithPlaylist(playlistId: String, create: Boolean, block: suspend (PlaylistEntity) -> Unit) {
        val id = playlistId.toUUIDOrNull() ?: return
        scope.launch {
            try {
                val serverId = appPreferences.currentServerId ?: return@launch
                val userId = appPreferences.currentUserId ?: return@launch
                val playlist = when {
                    create -> playlistSync.getOrCreatePlaylist(serverId, userId, id)
                    else -> offlineDao.getPlaylist(serverId, id)
                } ?: return@launch
                block(playlist)
            } catch (e: Exception) {
                Timber.e(e, "Playlist download action failed")
            }
        }
    }

    private fun showModeDialog(playlist: PlaylistEntity) {
        val context = fragment.context ?: return
        val modes = PlaylistDownloadMode.entries
        val labels = arrayOf(
            context.getString(R.string.offline_mode_off),
            context.getString(R.string.offline_mode_semi),
            context.getString(R.string.offline_mode_auto),
        )
        AlertDialog.Builder(context)
            .setTitle(R.string.offline_mode_title)
            .setSingleChoiceItems(labels, modes.indexOf(playlist.mode)) { dialog, which ->
                dialog.dismiss()
                val updated = playlist.copy(mode = modes[which])
                scope.launch {
                    playlistSync.updateSettings(updated)
                    if (updated.mode != PlaylistDownloadMode.OFF) {
                        playlistSync.sync(updated, download = true, enqueuer = offlineDownloads.userEnqueuer)
                    }
                }
            }
            .setNeutralButton(R.string.offline_limits) { _, _ -> showLimitsDialog(playlist) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showLimitsDialog(playlist: PlaylistEntity) {
        val context = fragment.context ?: return
        val padding = context.resources.getDimensionPixelSize(R.dimen.offline_dialog_padding)
        val maxItemsInput = numberInput(context, R.string.offline_limit_items_hint, playlist.maxItems)
        val maxRuntimeInput = numberInput(context, R.string.offline_limit_runtime_hint, playlist.maxRuntimeMinutes)
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
                val updated = playlist.copy(
                    maxItems = maxItemsInput.text.toString().toIntOrNull()?.takeIf { it > 0 },
                    maxRuntimeMinutes = maxRuntimeInput.text.toString().toIntOrNull()?.takeIf { it > 0 },
                )
                scope.launch {
                    playlistSync.updateSettings(updated)
                    if (updated.mode == PlaylistDownloadMode.AUTO) {
                        playlistSync.sync(updated, download = true, enqueuer = offlineDownloads.userEnqueuer)
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun numberInput(context: android.content.Context, hint: Int, value: Int?) = EditText(context).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        setHint(hint)
        setText(value?.toString().orEmpty())
    }

    private suspend fun showItemDialog(playlist: PlaylistEntity, itemId: UUID, itemName: String?) {
        val context = fragment.context ?: return
        val download = withContext(Dispatchers.IO) { downloadDao.getDownloadByItemId(itemId) }
            ?.takeIf { it.serverId == playlist.serverId && it.status != DownloadStatus.CANCELLED }
        val excluded = offlineDao.getExclusions(playlist.serverId, playlist.playlistId).any { it.itemId == itemId }

        val actions = buildList {
            if (download == null || download.status == DownloadStatus.ERROR) add(R.string.offline_action_download)
            if (download != null) add(R.string.offline_action_delete)
            if (!excluded) add(R.string.offline_action_exclude) else add(R.string.offline_action_include)
        }
        AlertDialog.Builder(context)
            .setTitle(itemName ?: context.getString(R.string.offline_item_title))
            .setItems(actions.map(context::getString).toTypedArray()) { _, which ->
                scope.launch {
                    when (actions[which]) {
                        R.string.offline_action_download ->
                            playlistSync.downloadItem(playlist, itemId, offlineDownloads.userEnqueuer)
                        R.string.offline_action_delete -> playlistSync.deleteDownload(playlist.serverId, itemId)
                        R.string.offline_action_exclude -> playlistSync.setExcluded(playlist, itemId, excluded = true)
                        R.string.offline_action_include -> playlistSync.setExcluded(playlist, itemId, excluded = false)
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
