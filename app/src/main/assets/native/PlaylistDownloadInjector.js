/*
 * Fork: offline. Adds download controls to the web app's playlist page, backed by window.NativeOffline
 * (OfflineBridge.kt):
 *  - a "download settings" button (mode off / semi / automatic, limits) and a "download new" button next to the
 *    play buttons, plus a status line,
 *  - a download state button on every video row (download, delete, don't download).
 *
 * Coupled to jellyfin-web markup (#itemDetailPage, .mainDetailButtons, .listItem[data-playlistid]); when the
 * markup changes nothing is injected and the page keeps working as before.
 */
(() => {
    if (!window.NativeOffline) return;

    // Fast while downloads are running, slow otherwise
    const POLL_FAST_MS = 2000;
    const POLL_SLOW_MS = 10000;
    const ROW_ICONS = {
        none: 'file_download',
        cancelled: 'file_download',
        queued: 'schedule',
        downloading: 'downloading',
        downloaded: 'download_done',
        error: 'error_outline',
        excluded: 'file_download_off',
    };
    const ROW_TITLES = {
        none: 'Download',
        cancelled: 'Download',
        queued: 'Download queued',
        downloading: 'Downloading',
        downloaded: 'Downloaded',
        error: 'Download failed',
        excluded: 'Not downloaded (excluded)',
    };
    const MODE_ICONS = { OFF: 'download_for_offline', SEMI: 'cloud_download', AUTO: 'sync' };
    const MODE_LABELS = { OFF: 'Downloads off', SEMI: 'Semi-automatic downloads', AUTO: 'Automatic downloads' };

    let currentPlaylistId = null;
    let pollTimer = null;
    let pollInterval = 0;
    let scanTimer = null;

    function visibleDetailPage() {
        return [...document.querySelectorAll('.page.itemDetailPage:not(.hide)')].pop() || null;
    }

    function playlistIdOf(page) {
        const row = page.querySelector('#childrenContent .listItem[data-playlistid]');
        return row ? row.getAttribute('data-playlistid') : null;
    }

    function playlistNameOf(page) {
        const title = page.querySelector('h1.itemName');
        return title ? title.textContent.trim() : null;
    }

    function rowsOf(page) {
        return [...page.querySelectorAll('#childrenContent .listItem[data-playlistid]')];
    }

    function rowId(row) {
        return (row.getAttribute('data-id') || '').replace(/-/g, '').toLowerCase();
    }

    function readState(playlistId, rows) {
        try {
            return JSON.parse(window.NativeOffline.getState(playlistId, rows.map(rowId).join(',')));
        } catch (e) {
            return null;
        }
    }

    function stop(event) {
        // The row itself plays the playlist from here on click, don't let our buttons trigger that
        event.preventDefault();
        event.stopPropagation();
    }

    function headerButton(className, icon, title, onClick) {
        const button = document.createElement('button');
        button.setAttribute('is', 'emby-button');
        button.type = 'button';
        button.className = `button-flat detailButton emby-button ${className}`;
        button.title = title;
        button.innerHTML = '<div class="detailButton-content"><span class="material-icons detailButton-icon" aria-hidden="true"></span></div>';
        button.querySelector('.material-icons').classList.add(icon);
        button.addEventListener('click', (event) => {
            stop(event);
            onClick();
        });
        return button;
    }

    function setIcon(element, icon) {
        const span = element.querySelector('.material-icons');
        if (span && !span.classList.contains(icon)) {
            span.className = span.className.split(' ').filter((c) => !c.startsWith('offlineIcon-') && !Object.values(ROW_ICONS).includes(c) && !Object.values(MODE_ICONS).includes(c)).join(' ');
            span.classList.add(icon);
        }
    }

    function renderHeader(page, playlistId, state) {
        const buttons = page.querySelector('.mainDetailButtons');
        if (!buttons) return;

        let modeButton = buttons.querySelector('.offlineModeButton');
        if (!modeButton) {
            modeButton = headerButton('offlineModeButton', MODE_ICONS.OFF, 'Download settings', () => window.NativeOffline.openSettings(currentPlaylistId, playlistNameOf(page)));
            buttons.appendChild(modeButton);
        }
        setIcon(modeButton, MODE_ICONS[state.mode] || MODE_ICONS.OFF);

        let newButton = buttons.querySelector('.offlineNewButton');
        if (!newButton) {
            newButton = headerButton('offlineNewButton', 'downloading', 'Download new videos', () => window.NativeOffline.downloadNew(currentPlaylistId, playlistNameOf(page)));
            buttons.appendChild(newButton);
        }
        newButton.classList.toggle('hide', state.mode === 'OFF');

        let status = page.querySelector('.offlineStatus');
        if (!status) {
            status = document.createElement('div');
            status.className = 'offlineStatus secondary';
            status.style.margin = '0.5em 0';
            buttons.insertAdjacentElement('afterend', status);
        }
        const parts = [MODE_LABELS[state.mode] || MODE_LABELS.OFF, `${state.downloaded || 0} of ${state.total || 0} downloaded`];
        if (state.pending) parts.push(`${state.pending} pending`);
        const text = parts.join(' · ');
        if (status.textContent !== text) status.textContent = text;
    }

    function renderRow(page, row, playlistId, itemState) {
        const container = row.querySelector('.listViewUserDataButtons');
        if (!container) return;
        if (row.getAttribute('data-mediatype') && row.getAttribute('data-mediatype') !== 'Video') return;

        let button = container.querySelector('.offlineRowButton');
        if (!button) {
            button = document.createElement('button');
            button.setAttribute('is', 'paper-icon-button-light');
            button.type = 'button';
            button.className = 'listItemButton paper-icon-button-light offlineRowButton';
            button.innerHTML = '<span class="material-icons" aria-hidden="true"></span>';
            button.addEventListener('click', (event) => {
                stop(event);
                const name = row.querySelector('.listItemBodyText');
                window.NativeOffline.openItemMenu(currentPlaylistId, playlistNameOf(page), row.getAttribute('data-id'), name ? name.textContent.trim() : null);
            });
            const menuButton = container.querySelector('[data-action="menu"]');
            container.insertBefore(button, menuButton);
        }

        let key = 'none';
        if (itemState) key = itemState.excluded && (itemState.status === 'none' || itemState.status === 'cancelled') ? 'excluded' : itemState.status;
        setIcon(button, ROW_ICONS[key] || ROW_ICONS.none);
        button.title = ROW_TITLES[key] || ROW_TITLES.none;
        button.style.color = key === 'downloaded' ? 'var(--theme-primary-color, #00a4dc)' : '';
        button.style.opacity = key === 'excluded' ? '0.5' : '';
    }

    function render() {
        const page = visibleDetailPage();
        const playlistId = page && playlistIdOf(page);
        if (!playlistId || playlistId !== currentPlaylistId) return;
        const rows = rowsOf(page);
        const state = readState(playlistId, rows);
        if (!state || !state.items) return;
        renderHeader(page, playlistId, state);
        rows.forEach((row) => renderRow(page, row, playlistId, state.items[rowId(row)]));
        schedulePoll(state.pending ? POLL_FAST_MS : POLL_SLOW_MS);
    }

    function schedulePoll(interval) {
        if (pollTimer && pollInterval === interval) return;
        clearInterval(pollTimer);
        pollInterval = interval;
        pollTimer = setInterval(render, interval);
    }

    function scan() {
        if (!window.NativeOffline.isEnabled()) return;
        const page = visibleDetailPage();
        const playlistId = page && playlistIdOf(page);
        if (playlistId !== currentPlaylistId) {
            currentPlaylistId = playlistId;
            if (playlistId) window.NativeOffline.onPlaylistShown(playlistId, playlistNameOf(page));
        }
        if (currentPlaylistId) {
            render();
        } else if (pollTimer) {
            clearInterval(pollTimer);
            pollTimer = null;
            pollInterval = 0;
        }
    }

    function scheduleScan() {
        clearTimeout(scanTimer);
        scanTimer = setTimeout(scan, 300);
    }

    const observer = new MutationObserver(scheduleScan);
    function start() {
        observer.observe(document.body, { childList: true, subtree: true, attributes: true, attributeFilter: ['class'] });
        window.addEventListener('hashchange', scheduleScan);
        scheduleScan();
    }

    if (document.body) {
        start();
    } else {
        document.addEventListener('DOMContentLoaded', start);
    }
})();
