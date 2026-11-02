package org.jellyfin.mobile.offline.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.ContentAlpha
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Scaffold
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PlaylistPlay
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.jellyfin.mobile.R
import org.jellyfin.sdk.model.extensions.ticks
import kotlin.time.Duration

/**
 * Fork: offline. Lists look like the web app's list view: thumbnail, title, details, watch progress.
 */
@Suppress("LongParameterList")
@Composable
fun OfflineScreen(
    state: OfflineViewModel.State,
    openPlaylist: OfflineViewModel.Playlist?,
    serverReachable: Boolean,
    onOpenPlaylist: (OfflineViewModel.Playlist) -> Unit,
    onClosePlaylist: () -> Unit,
    onPlayPlaylist: (List<OfflineViewModel.Entry>, OfflineViewModel.Entry) -> Unit,
    onPlayVideo: (OfflineViewModel.Entry) -> Unit,
    onReconnect: () -> Unit,
    onChangeServer: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = openPlaylist?.name ?: stringResource(R.string.offline_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    if (openPlaylist != null) {
                        IconButton(onClick = onClosePlaylist) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
                        }
                    } else {
                        Icon(Icons.Outlined.CloudOff, contentDescription = null, modifier = Modifier.padding(start = 16.dp))
                    }
                },
                actions = {
                    TextButton(onClick = onReconnect) {
                        Text(stringResource(R.string.offline_reconnect))
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Outlined.MoreVert, contentDescription = null)
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                onClick = {
                                    menuOpen = false
                                    onChangeServer()
                                },
                            ) {
                                Text(stringResource(R.string.offline_change_server))
                            }
                        }
                    }
                },
                backgroundColor = MaterialTheme.colors.primaryVariant,
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            if (serverReachable) {
                ReachableBanner(onReconnect)
            }
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                openPlaylist != null -> EntryList(openPlaylist.entries, onPlayPlaylist)
                else -> Overview(state, onOpenPlaylist, onPlayVideo)
            }
        }
    }
}

@Composable
private fun ReachableBanner(onReconnect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colors.primary.copy(alpha = 0.2f))
            .clickable(onClick = onReconnect)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.offline_server_reachable), modifier = Modifier.weight(1f))
        Text(stringResource(R.string.offline_reconnect), color = MaterialTheme.colors.primary, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Overview(
    state: OfflineViewModel.State,
    onOpenPlaylist: (OfflineViewModel.Playlist) -> Unit,
    onPlayVideo: (OfflineViewModel.Entry) -> Unit,
) {
    if (state.playlists.isEmpty() && state.videos.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.offline_empty), color = MaterialTheme.colors.onBackground.copy(alpha = ContentAlpha.medium))
        }
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        if (state.playlists.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.offline_playlists)) }
            items(state.playlists, key = { "playlist-${it.id}" }) { playlist ->
                PlaylistRow(playlist, onClick = { onOpenPlaylist(playlist) })
            }
        }
        if (state.videos.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.offline_videos)) }
            items(state.videos, key = { "video-${it.position}-${it.itemId}" }) { entry ->
                EntryRow(entry, onClick = { onPlayVideo(entry) })
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.h6,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun PlaylistRow(playlist: OfflineViewModel.Playlist, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumbnail(model = playlist.imageUri, placeholder = R.drawable.ic_local_movies_white_64)
        Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
            Text(playlist.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.subtitle1)
            Text(
                text = stringResource(R.string.offline_playlist_count, playlist.availableCount, playlist.entries.size),
                style = MaterialTheme.typography.body2,
                color = MaterialTheme.colors.onBackground.copy(alpha = ContentAlpha.medium),
            )
        }
        Icon(Icons.Outlined.PlaylistPlay, contentDescription = null)
    }
}

@Composable
private fun EntryList(
    entries: List<OfflineViewModel.Entry>,
    onPlay: (List<OfflineViewModel.Entry>, OfflineViewModel.Entry) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(entries, key = { "${it.position}-${it.itemId}" }) { entry ->
            EntryRow(entry, onClick = { onPlay(entries, entry) })
        }
    }
}

@Composable
private fun EntryRow(entry: OfflineViewModel.Entry, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = entry.available, onClick = onClick)
            .alpha(if (entry.available) 1f else DISABLED_ALPHA)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Thumbnail(model = entry.imageUri, placeholder = R.drawable.ic_local_movies_white_64)
            if (entry.progress != null) {
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(Color.Black.copy(alpha = 0.5f)),
                ) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(entry.progress.coerceIn(0f, 1f))
                            .background(MaterialTheme.colors.primary),
                    )
                }
            }
            if (entry.played) {
                Icon(
                    Icons.Rounded.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colors.primary)
                        .padding(2.dp),
                )
            }
        }
        Column(
            modifier = Modifier.padding(start = 12.dp).weight(1f),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(entry.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.subtitle1)
            val details = listOfNotNull(
                entry.subtitle,
                entry.runTimeTicks?.ticks?.let(::formatRuntime),
                if (entry.available) null else stringResource(R.string.offline_not_downloaded),
            ).joinToString(" · ")
            if (details.isNotEmpty()) {
                Text(
                    details,
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.onBackground.copy(alpha = ContentAlpha.medium),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (entry.available) {
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Rounded.PlayArrow, contentDescription = null)
        }
    }
}

@Composable
private fun Thumbnail(model: Any?, placeholder: Int) {
    AsyncImage(
        model = model,
        placeholder = painterResource(placeholder),
        fallback = painterResource(placeholder),
        error = painterResource(placeholder),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .width(THUMBNAIL_WIDTH.dp)
            .aspectRatio(THUMBNAIL_ASPECT_RATIO)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colors.surface),
    )
}

private fun formatRuntime(duration: Duration): String {
    val minutes = duration.inWholeMinutes
    return if (minutes >= MINUTES_PER_HOUR) "${minutes / MINUTES_PER_HOUR}h ${minutes % MINUTES_PER_HOUR}m" else "${maxOf(minutes, 1)} min"
}

private const val DISABLED_ALPHA = 0.38f
private const val THUMBNAIL_WIDTH = 128
private const val THUMBNAIL_ASPECT_RATIO = 16f / 9f
private const val MINUTES_PER_HOUR = 60
