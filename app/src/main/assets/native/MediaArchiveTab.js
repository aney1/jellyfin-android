/*
 * Injects a "MA" tab next to the web client's "Home" / "Favorites" tabs that opens Media Archive
 * in a native in-app WebView (see MediaArchiveFragment). Media Archive sends
 * X-Frame-Options: DENY, so it can't be embedded as an iframe — it's opened as a layered screen
 * instead, via the NativeInterface bridge.
 *
 * Mirrors HomeShortcutTab.js: the tab strip (jellyfin-web maintabsmanager.js) is rebuilt on every
 * view change, so we re-inject on a poll and on hash changes.
 */
(() => {
    'use strict';

    const TAB_LABEL = 'MA';
    const MARKER_CLASS = 'ta-shortcut-tab';
    const POLL_INTERVAL_MS = 750;

    function isHomeRoute() {
        return (window.location.hash || '').toLowerCase().indexOf('/home') !== -1;
    }

    function openMediaArchive() {
        try {
            if (window.NativeInterface && window.NativeInterface.openMediaArchive) {
                window.NativeInterface.openMediaArchive();
            }
        } catch (e) {
            console.error('[TA] Failed to open Media Archive', e);
        }
    }

    function buildTab(templateButton) {
        // Wrap in a plain span so emby-tabs' swipe navigation (which walks siblings carrying the
        // emby-tab-button class) never lands on our tab.
        const wrapper = document.createElement('span');
        wrapper.className = MARKER_CLASS + '-wrapper';

        const button = document.createElement('button');
        button.type = 'button';
        button.className = templateButton.className;
        button.classList.remove('emby-tab-button-active');
        button.classList.add(MARKER_CLASS);
        button.removeAttribute('data-index');

        const foreground = document.createElement('div');
        foreground.className = 'emby-button-foreground';
        foreground.textContent = TAB_LABEL;
        button.appendChild(foreground);

        // Capture phase + stopPropagation so the bubble-phase emby-tabs click handler never runs.
        button.addEventListener(
            'click',
            (e) => {
                e.preventDefault();
                e.stopPropagation();
                openMediaArchive();
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
