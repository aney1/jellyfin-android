(() => {
    const scripts = [
        '/native/nativeshell.js',
        '/native/EventEmitter.js'
    ];
    // The injected tabs are toggleable in the app settings, queried through the native bridge.
    try {
        if (window.NativeInterface && NativeInterface.isHomeShortcutEnabled()) {
            scripts.push('/native/HomeShortcutTab.js');
        }
        if (window.NativeInterface && NativeInterface.isTubeArchivistTabEnabled()) {
            scripts.push('/native/TubeArchivistTab.js');
        }
    } catch (e) {
        // Bridge unavailable - skip the optional tabs
    }
    scripts.push(document.currentScript.src.concat('?deferred=true&ts=', Date.now()));
    for (const script of scripts) {
        const scriptElement = document.createElement('script');
        scriptElement.src = script;
        scriptElement.charset = 'utf-8';
        scriptElement.setAttribute('defer', '');
        document.body.appendChild(scriptElement);
    }
    document.currentScript.remove();
})();
