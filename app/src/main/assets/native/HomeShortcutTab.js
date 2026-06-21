/*
 * Injects an extra tab next to the web client's "Home" / "Favorites" tabs that jumps
 * straight to a specific playlist (a "Smart List" that is updated in place, so its id is
 * stable, but we still resolve it by name at runtime to survive a rare recreate).
 *
 * This lives in the native wrapper because those tabs are part of jellyfin-web, not the
 * Android app. The tab strip (see jellyfin-web maintabsmanager.js) is rebuilt on every
 * view change, so we re-inject on a poll and on hash changes.
 */
(() => {
    'use strict';

    // The exact playlist name created by the Smart Lists plugin (default "[Smart]" suffix).
    const PLAYLIST_NAME = 'YT Watch list [Smart]';
    // Label shown on the injected tab.
    const TAB_LABEL = 'YT Watch list';
    const MARKER_CLASS = 'ytwl-shortcut-tab';
    const POLL_INTERVAL_MS = 750;

    let cachedPlaylistId = null;

    function isHomeRoute() {
        return (window.location.hash || '').toLowerCase().indexOf('/home') !== -1;
    }

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
            console.warn('[YTWL] Playlist not found: ' + PLAYLIST_NAME);
        } catch (e) {
            console.error('[YTWL] Failed to resolve playlist', e);
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
                console.error('[YTWL] Router navigation failed, falling back to hash', e);
            }
            window.location.hash = '#' + route;
        });
    }

    function buildTab(templateButton) {
        // Wrap in a plain span so emby-tabs' swipe navigation (which walks siblings carrying
        // the emby-tab-button class) never lands on our tab.
        const wrapper = document.createElement('span');
        wrapper.className = MARKER_CLASS + '-wrapper';

        const button = document.createElement('button');
        button.type = 'button';
        // Reuse the real tab's classes for identical styling.
        button.className = templateButton.className;
        button.classList.remove('emby-tab-button-active');
        button.classList.add(MARKER_CLASS);
        button.removeAttribute('data-index');

        const foreground = document.createElement('div');
        foreground.className = 'emby-button-foreground';
        foreground.textContent = TAB_LABEL;
        button.appendChild(foreground);

        // Capture phase + stopPropagation so the bubble-phase emby-tabs click handler never
        // runs (which would try to switch to a non-existent tab panel).
        button.addEventListener(
            'click',
            (e) => {
                e.preventDefault();
                e.stopPropagation();
                navigateToPlaylist();
            },
            true,
        );

        wrapper.appendChild(button);
        return wrapper;
    }

    function injectTab() {
        if (!isHomeRoute()) return;
        const header = document.querySelector('.skinHeader .headerTabs');
        if (!header || header.classList.contains('hide')) return;
        const slider = header.querySelector('.emby-tabs-slider');
        if (!slider) return;
        if (slider.querySelector('.' + MARKER_CLASS)) return; // already injected
        const template = slider.querySelector('.emby-tab-button');
        if (!template) return; // wait until the real tabs are rendered
        slider.appendChild(buildTab(template));
    }

    function start() {
        setInterval(injectTab, POLL_INTERVAL_MS);
        window.addEventListener('hashchange', () => setTimeout(injectTab, 50));
        injectTab();
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', start);
    } else {
        start();
    }
})();
