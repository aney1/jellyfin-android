/*
 * Injects a "TA" navigation entry that opens Media Archive in a native in-app WebView (see
 * MediaArchiveFragment). Media Archive sends X-Frame-Options: DENY, so it can't be embedded as an
 * iframe - it's opened as a layered screen instead, via the NativeInterface bridge.
 *
 * Locating and building the entry is handled by NavTabInjector.js, which knows about both the 12.0
 * MUI navigation and the older emby-tabs strip.
 */
(() => {
    'use strict';

    const TAB_LABEL = 'TA';
    const MARKER_CLASS = 'ta-shortcut-tab';
    // Material "video library".
    const ICON_PATH = 'M4 6H2v14c0 1.1.9 2 2 2h14v-2H4zm16-4H8c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h12c1.1 0 '
        + '2-.9 2-2V4c0-1.1-.9-2-2-2m-9 12V6l5.5 4z';

    function openMediaArchive() {
        try {
            if (window.NativeInterface && window.NativeInterface.openMediaArchive) {
                window.NativeInterface.openMediaArchive();
            }
        } catch (e) {
            console.error('[TA] Failed to open Media Archive', e);
        }
    }

    if (window.JellyfinNavTabs) {
        window.JellyfinNavTabs.register({
            label: TAB_LABEL,
            marker: MARKER_CLASS,
            iconPath: ICON_PATH,
            onSelect: openMediaArchive,
        });
    } else {
        console.error('[TA] NavTabInjector unavailable, entry not injected');
    }
})();
