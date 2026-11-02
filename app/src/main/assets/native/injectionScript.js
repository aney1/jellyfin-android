(() => {
    const scripts = [
        '/native/nativeshell.js',
        '/native/EventEmitter.js'
    ];
    // The injected navigation entries are toggleable in the app settings, queried through the
    // native bridge. They share NavTabInjector.js, which must run before them.
    const navTabs = [];
    try {
        if (window.NativeInterface && NativeInterface.isHomeShortcutEnabled()) {
            navTabs.push('/native/HomeShortcutTab.js');
        }
        if (window.NativeInterface && NativeInterface.isMediaArchiveTabEnabled()) {
            navTabs.push('/native/MediaArchiveTab.js');
        }
        if (window.NativeInterface && NativeInterface.isExternalLinksMenuEnabled()) {
            navTabs.push('/native/ExternalLinksTab.js');
        }
    } catch (e) {
        // Bridge unavailable - skip the optional entries
    }
    if (navTabs.length) {
        scripts.push('/native/NavTabInjector.js', ...navTabs);
    }
    // Fork: offline - download controls on playlist pages
    try {
        if (window.NativeOffline && NativeOffline.isEnabled()) {
            scripts.push('/native/PlaylistDownloadInjector.js');
        }
    } catch (e) {
        // Bridge unavailable - skip the download controls
    }
    scripts.push(document.currentScript.src.concat('?deferred=true&ts=', Date.now()));
    for (const script of scripts) {
        const scriptElement = document.createElement('script');
        scriptElement.src = script;
        scriptElement.charset = 'utf-8';
        // Dynamically inserted scripts are async by default, which would leave the load order of
        // the helpers, the entries that use them and the web app itself up to the network. The
        // defer attribute does not apply to them, async = false does.
        scriptElement.async = false;
        scriptElement.setAttribute('defer', '');
        document.body.appendChild(scriptElement);
    }
    document.currentScript.remove();
})();
