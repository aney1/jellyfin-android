/*
 * Injects an extra navigation entry that jumps straight to a specific playlist (a "Smart List"
 * that is updated in place, so its id is stable, but we still resolve it by name at runtime to
 * survive a rare recreate).
 *
 * This lives in the native wrapper because the navigation is part of jellyfin-web, not the Android
 * app. Locating and building the entry is handled by NavTabInjector.js, which knows about both the
 * 12.0 MUI navigation and the older emby-tabs strip.
 */
(() => {
    'use strict';

    // The exact playlist name created by the smart-playlist plugin (default "[Smart]" suffix).
    const PLAYLIST_NAME = (window.NativeInterface && NativeInterface.getHomePlaylistName
        && NativeInterface.getHomePlaylistName()) || '';
    // Label shown on the injected entry.
    const TAB_LABEL = PLAYLIST_NAME.replace(/\s*\[[^\]]*\]\s*$/, '') || PLAYLIST_NAME;
    const MARKER_CLASS = 'playlist-shortcut-tab';
    // Material "playlist play".
    const ICON_PATH = 'M19 9H2v2h17zm0-4H2v2h17zM2 15h13v-2H2zm15-2v6l5-3z';

    let cachedPlaylistId = null;

    function getApiClient() {
        const api = window.ApiClient;
        return api && api.getCurrentUserId && api.getCurrentUserId() ? api : null;
    }

    async function resolvePlaylistId() {
        if (cachedPlaylistId) return cachedPlaylistId;
        const api = getApiClient();
        if (!api) return null;
        try {
            const result = await api.getItems(api.getCurrentUserId(), {
                IncludeItemTypes: 'Playlist',
                Recursive: true,
                SortBy: 'SortName',
            });
            const items = (result && result.Items) || [];
            const target = PLAYLIST_NAME.trim().toLowerCase();
            const match =
                items.find((i) => (i.Name || '').trim().toLowerCase() === target) ||
                items.find((i) => (i.Name || '').trim().toLowerCase().indexOf(target) !== -1);
            if (match) {
                cachedPlaylistId = match.Id;
                return cachedPlaylistId;
            }
            console.warn('[PlaylistShortcut] Playlist not found: ' + PLAYLIST_NAME);
        } catch (e) {
            console.error('[PlaylistShortcut] Failed to resolve playlist', e);
        }
        return null;
    }

    function navigateToPlaylist() {
        resolvePlaylistId().then((id) => {
            if (!id) return;
            const api = getApiClient();
            const serverId = api && api.serverId ? api.serverId() : null;
            const route = '/details?id=' + id + (serverId ? '&serverId=' + serverId : '');
            try {
                if (window.Emby && window.Emby.Page && window.Emby.Page.show) {
                    window.Emby.Page.show(route);
                    return;
                }
            } catch (e) {
                console.error('[PlaylistShortcut] Router navigation failed, falling back to hash', e);
            }
            window.location.hash = '#' + route;
        });
    }

    if (window.JellyfinNavTabs) {
        window.JellyfinNavTabs.register({
            label: TAB_LABEL,
            marker: MARKER_CLASS,
            iconPath: ICON_PATH,
            onSelect: navigateToPlaylist,
        });
    } else {
        console.error('[PlaylistShortcut] NavTabInjector unavailable, entry not injected');
    }
})();
