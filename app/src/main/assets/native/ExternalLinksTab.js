/*
 * Injects a "Links" tab next to the web client's "Home" / "Favorites" tabs that opens a small
 * overlay menu with configurable external links. The links are configured at build time via the
 * `externalLinks` property (see app/build.gradle.kts) and queried through the NativeInterface
 * bridge; each entry opens in the system browser.
 *
 * Mirrors MediaArchiveTab.js: the tab strip (jellyfin-web maintabsmanager.js) is rebuilt on
 * every view change, so we re-inject on a poll and on hash changes.
 */
(() => {
    'use strict';

    const TAB_LABEL = 'Links';
    const MARKER_CLASS = 'external-links-tab';
    const MENU_ID = 'external-links-menu';
    const POLL_INTERVAL_MS = 750;

    function isHomeRoute() {
        return (window.location.hash || '').toLowerCase().indexOf('/home') !== -1;
    }

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

    function onOutsideClick(e) {
        const menu = document.getElementById(MENU_ID);
        if (menu && !menu.contains(e.target)) closeMenu();
    }

    function closeMenu() {
        const menu = document.getElementById(MENU_ID);
        if (menu) menu.remove();
        document.removeEventListener('click', onOutsideClick, true);
    }

    function openMenu(anchorButton) {
        const links = getLinks();
        if (!links.length) return;

        const menu = document.createElement('div');
        menu.id = MENU_ID;
        menu.style.cssText =
            'position:fixed;z-index:10000;min-width:11em;padding:0.4em 0;' +
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

        // Position below the tab, kept inside the viewport now that the width is known.
        const rect = anchorButton.getBoundingClientRect();
        const left = Math.max(4, Math.min(rect.left, window.innerWidth - menu.offsetWidth - 4));
        menu.style.top = (rect.bottom + 4) + 'px';
        menu.style.left = left + 'px';

        // Deferred so the click that opened the menu doesn't immediately close it again.
        setTimeout(() => document.addEventListener('click', onOutsideClick, true), 0);
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
                if (document.getElementById(MENU_ID)) {
                    closeMenu();
                } else {
                    openMenu(button);
                }
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
        window.addEventListener('hashchange', () => {
            closeMenu();
            setTimeout(injectTab, 50);
        });
        injectTab();
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', start);
    } else {
        start();
    }
})();
