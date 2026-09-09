/*
 * Shared helper used by the fork's injected navigation entries (HomeShortcutTab, MediaArchiveTab,
 * ExternalLinksTab).
 *
 * jellyfin-web 12.0 replaced the legacy `emby-tabs` strip with a MUI navigation that renders in
 * two different shapes depending on viewport width:
 *   - wide   : a `.MuiStack-root` of `<a>` buttons inside `header.MuiAppBar-root`
 *   - narrow : a `.MuiList-root` of `<li>` rows inside the `.MuiDrawer-root` burger menu
 *
 * The old `.skinHeader .headerTabs > .emby-tabs-slider` strip still exists in the 12.0 DOM but is
 * zero-sized and unused, which is why entries appended to it silently vanished after the upgrade.
 * It is still used by servers before 12.0, so it is kept here as a fallback and guarded on being
 * actually rendered.
 *
 * Entries are built by deep-cloning a real neighbouring entry and then relabelling the clone, so
 * they inherit the emotion (`css-*`) classes that change on every jellyfin-web build rather than
 * hardcoding them. Every known container is fed on each pass, so switching layouts (rotating a
 * tablet, resizing) needs no reload.
 *
 * Unlike the pre-12.0 tab strip - which only existed on `/home` - the MUI navigation is global, so
 * the injected entries are no longer gated on the current route.
 */
(() => {
    'use strict';

    const POLL_INTERVAL_MS = 750;

    // { label, marker, iconPath, onSelect }
    const registrations = [];

    function isRendered(element) {
        if (!element) return false;
        const rect = element.getBoundingClientRect();
        return rect.width > 0 && rect.height > 0;
    }

    function findAppBarNav() {
        const bar = document.querySelector('header.MuiAppBar-root');
        if (!bar) return null;
        // Never clone the server/logo entry ("#/"): it is a larger button carrying an <img>
        // instead of an icon. It also renders before the real links do, so requiring a real one
        // doubles as the signal that the navigation has finished rendering.
        const template = Array.from(bar.querySelectorAll('.MuiStack-root > a[href^="#/"]'))
            .find((link) => link.getAttribute('href') !== '#/');
        if (!isRendered(template)) return null;
        return { kind: 'appbar', container: template.parentElement, template };
    }

    function findDrawerNav() {
        const drawer = document.querySelector('.MuiDrawer-root');
        if (!drawer) return null;
        // Not gated on visibility: the drawer stays mounted (and measures zero) while closed.
        const template = Array.from(drawer.querySelectorAll('.MuiList-root > li')).find((row) => {
            const link = row.querySelector('a[href]');
            return link && link.getAttribute('href').indexOf('#/home') === 0;
        });
        if (!template) return null;
        return { kind: 'drawer', container: template.parentElement, template };
    }

    function findLegacyNav() {
        const header = document.querySelector('.skinHeader .headerTabs');
        if (!header || header.classList.contains('hide')) return null;
        const slider = header.querySelector('.emby-tabs-slider');
        // On 12.0 this still exists but never renders - ignore that dead remnant.
        if (!isRendered(slider)) return null;
        const template = slider.querySelector('.emby-tab-button');
        if (!template) return null;
        return { kind: 'legacy', container: slider, template };
    }

    function findNavigations() {
        return [findAppBarNav(), findDrawerNav(), findLegacyNav()].filter(Boolean);
    }

    function clearActiveState(root) {
        const stateClasses = ['Mui-selected', 'Mui-focusVisible', 'emby-tab-button-active'];
        for (const element of [root].concat(Array.from(root.querySelectorAll('*')))) {
            if (element.classList) stateClasses.forEach((name) => element.classList.remove(name));
            element.removeAttribute('aria-current');
            element.removeAttribute('data-index');
        }
    }

    function setIcon(scope, iconPath) {
        const svg = scope.querySelector('svg');
        if (!svg || !iconPath) return;
        svg.removeAttribute('data-testid');
        const paths = Array.from(svg.querySelectorAll('path'));
        paths.forEach((path, index) => {
            if (index === 0) {
                path.setAttribute('d', iconPath);
            } else {
                path.remove();
            }
        });
    }

    function bindSelect(element, registration) {
        // Capture phase + stopPropagation so neither the legacy emby-tabs handler nor React's
        // delegated listeners see the click and try to activate a tab panel that doesn't exist.
        element.addEventListener(
            'click',
            (event) => {
                event.preventDefault();
                event.stopPropagation();
                registration.onSelect(element);
            },
            true,
        );
    }

    function buildAppBarEntry(template, registration) {
        const anchor = template.cloneNode(true);
        clearActiveState(anchor);
        anchor.removeAttribute('href');
        anchor.setAttribute('role', 'button');
        anchor.classList.add(registration.marker);
        setIcon(anchor, registration.iconPath);
        // In a MUI button the label is a bare text node following the icon span.
        Array.from(anchor.childNodes)
            .filter((node) => node.nodeType === Node.TEXT_NODE)
            .forEach((node) => node.remove());
        anchor.appendChild(document.createTextNode(registration.label));
        bindSelect(anchor, registration);
        return anchor;
    }

    function buildDrawerEntry(template, registration) {
        const row = template.cloneNode(true);
        clearActiveState(row);
        const anchor = row.querySelector('a');
        if (!anchor) return null;
        row.classList.add(registration.marker);
        anchor.removeAttribute('href');
        anchor.setAttribute('role', 'button');
        setIcon(row, registration.iconPath);
        const label = row.querySelector('.MuiListItemText-primary');
        if (label) label.textContent = registration.label;
        // Only the server row carries a secondary line (the version) - drop it if we cloned one.
        row.querySelectorAll('.MuiListItemText-secondary').forEach((node) => node.remove());
        bindSelect(anchor, registration);
        return row;
    }

    function buildLegacyEntry(template, registration) {
        // Wrap in a plain span so emby-tabs' swipe navigation (which walks siblings carrying the
        // emby-tab-button class) never lands on our entry.
        const wrapper = document.createElement('span');
        wrapper.className = registration.marker + '-wrapper';

        const button = document.createElement('button');
        button.type = 'button';
        button.className = template.className;
        clearActiveState(button);
        button.classList.add(registration.marker);

        const foreground = document.createElement('div');
        foreground.className = 'emby-button-foreground';
        foreground.textContent = registration.label;
        button.appendChild(foreground);

        bindSelect(button, registration);
        wrapper.appendChild(button);
        return wrapper;
    }

    function buildEntry(navigation, registration) {
        switch (navigation.kind) {
            case 'appbar':
                return buildAppBarEntry(navigation.template, registration);
            case 'drawer':
                return buildDrawerEntry(navigation.template, registration);
            default:
                return buildLegacyEntry(navigation.template, registration);
        }
    }

    function findEntry(container, registration) {
        return Array.from(container.children)
            .find((child) => child.classList && child.classList.contains(registration.marker));
    }

    function isInjected(element) {
        return registrations.some((registration) =>
            element.classList && element.classList.contains(registration.marker));
    }

    /*
     * jellyfin-web fills the navigation in more than one render pass (libraries arrive after the
     * first paint), so entries appended earlier can end up in the middle of the list. Move the
     * whole group back to the end whenever a real entry appears after it.
     */
    function keepTrailing(container) {
        const children = Array.from(container.children);
        const injected = children.filter(isInjected);
        if (!injected.length) return;
        if (children.slice(-injected.length).every(isInjected)) return;
        for (const registration of registrations) {
            const entry = findEntry(container, registration);
            if (entry) container.appendChild(entry);
        }
    }

    function inject() {
        for (const navigation of findNavigations()) {
            for (const registration of registrations) {
                // Already injected into this container.
                if (findEntry(navigation.container, registration)) continue;
                const entry = buildEntry(navigation, registration);
                if (entry) navigation.container.appendChild(entry);
            }
            keepTrailing(navigation.container);
        }
    }

    const listeners = [];

    window.JellyfinNavTabs = {
        /**
         * Adds an entry to every jellyfin-web navigation container that is present.
         *
         * @param registration {{label: string, marker: string, iconPath: string,
         *                       onSelect: function(Element): void}}
         */
        register(registration) {
            registrations.push(registration);
            inject();
        },

        /** Registers a callback invoked on SPA navigation, for dismissing transient UI. */
        onNavigate(callback) {
            listeners.push(callback);
        },
    };

    function start() {
        setInterval(inject, POLL_INTERVAL_MS);
        window.addEventListener('hashchange', () => {
            listeners.forEach((callback) => callback());
            setTimeout(inject, 50);
        });
        inject();
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', start);
    } else {
        start();
    }
})();
