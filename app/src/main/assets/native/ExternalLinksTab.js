/*
 * Injects a "Links" navigation entry that opens a small overlay menu with configurable external
 * links. The links are configured at build time via the `externalLinks` property (see
 * app/build.gradle.kts) and queried through the NativeInterface bridge; each entry opens in the
 * system browser.
 *
 * Locating and building the entry is handled by NavTabInjector.js, which knows about both the 12.0
 * MUI navigation and the older emby-tabs strip.
 */
(() => {
    'use strict';

    const TAB_LABEL = 'Links';
    const MARKER_CLASS = 'external-links-tab';
    const MENU_ID = 'external-links-menu';
    // Material "link".
    const ICON_PATH = 'M3.9 12c0-1.71 1.39-3.1 3.1-3.1h4V7H7c-2.76 0-5 2.24-5 5s2.24 5 5 5h4v-1.9H7c-1.71 '
        + '0-3.1-1.39-3.1-3.1M8 13h8v-2H8zm9-6h-4v1.9h4c1.71 0 3.1 1.39 3.1 3.1s-1.39 3.1-3.1 '
        + '3.1h-4V17h4c2.76 0 5-2.24 5-5s-2.24-5-5-5';

    function getLinks() {
        try {
            if (window.NativeInterface && window.NativeInterface.getExternalLinks) {
                return JSON.parse(window.NativeInterface.getExternalLinks());
            }
        } catch (e) {
            console.error('[Links] Failed to read external links', e);
        }
        return [];
    }

    function openLink(url) {
        try {
            if (window.NativeInterface && window.NativeInterface.openUrl) {
                window.NativeInterface.openUrl(url);
            }
        } catch (e) {
            console.error('[Links] Failed to open ' + url, e);
        }
    }

    // The entry the open menu belongs to, so its own clicks can be left to the toggle handler.
    let menuAnchor = null;

    function onOutsideClick(e) {
        const menu = document.getElementById(MENU_ID);
        if (!menu) return;
        // This capture-phase listener runs before the entry's own handler. Closing the menu here
        // would make that handler see no menu and immediately reopen it, so skip those clicks.
        if (menuAnchor && (e.target === menuAnchor || menuAnchor.contains(e.target))) return;
        if (!menu.contains(e.target)) closeMenu();
    }

    function closeMenu() {
        const menu = document.getElementById(MENU_ID);
        if (menu) menu.remove();
        menuAnchor = null;
        document.removeEventListener('click', onOutsideClick, true);
    }

    function openMenu(anchorButton) {
        const links = getLinks();
        if (!links.length) return;

        const menu = document.createElement('div');
        menu.id = MENU_ID;
        // z-index has to clear the MUI drawer/modal layer (1300) the entry can be rendered in.
        menu.style.cssText =
            'position:fixed;z-index:20000;min-width:11em;padding:0.4em 0;' +
            'background:#242424;color:#fff;border-radius:0.4em;' +
            'box-shadow:0 0.2em 1em rgba(0,0,0,0.6);';

        for (const link of links) {
            const item = document.createElement('button');
            item.type = 'button';
            item.textContent = link.name;
            item.style.cssText =
                'display:block;width:100%;padding:0.7em 1.4em;border:0;background:none;' +
                'color:inherit;text-align:left;font:inherit;cursor:pointer;';
            item.addEventListener('click', (e) => {
                e.preventDefault();
                e.stopPropagation();
                closeMenu();
                openLink(link.url);
            });
            menu.appendChild(item);
        }

        document.body.appendChild(menu);
        menuAnchor = anchorButton;

        // Position below the entry, kept inside the viewport now that the size is known.
        const rect = anchorButton.getBoundingClientRect();
        const left = Math.max(4, Math.min(rect.left, window.innerWidth - menu.offsetWidth - 4));
        const top = Math.min(rect.bottom + 4, window.innerHeight - menu.offsetHeight - 4);
        menu.style.top = Math.max(4, top) + 'px';
        menu.style.left = left + 'px';

        // Deferred so the click that opened the menu doesn't immediately close it again.
        setTimeout(() => document.addEventListener('click', onOutsideClick, true), 0);
    }

    function toggleMenu(anchorButton) {
        if (document.getElementById(MENU_ID)) {
            closeMenu();
        } else {
            openMenu(anchorButton);
        }
    }

    if (window.JellyfinNavTabs) {
        window.JellyfinNavTabs.register({
            label: TAB_LABEL,
            marker: MARKER_CLASS,
            iconPath: ICON_PATH,
            onSelect: toggleMenu,
        });
        window.JellyfinNavTabs.onNavigate(closeMenu);
    } else {
        console.error('[Links] NavTabInjector unavailable, entry not injected');
    }
})();
