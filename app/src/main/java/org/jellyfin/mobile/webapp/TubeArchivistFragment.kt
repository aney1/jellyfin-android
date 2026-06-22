package org.jellyfin.mobile.webapp

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.jellyfin.mobile.BuildConfig
import org.jellyfin.mobile.R
import org.jellyfin.mobile.databinding.FragmentTubeArchivistBinding
import org.jellyfin.mobile.utils.BackPressInterceptor
import org.jellyfin.mobile.utils.applyDefault

/**
 * Hosts Tube Archivist in an in-app [android.webkit.WebView], layered over the web app like the
 * player. It is a top-level load (not an iframe) so Tube Archivist's `X-Frame-Options: DENY`
 * doesn't apply. A small close button returns to the web app.
 */
class TubeArchivistFragment : Fragment(), BackPressInterceptor {
    private var binding: FragmentTubeArchivistBinding? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return FragmentTubeArchivistBinding.inflate(inflater, container, false).also { binding = it }.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = binding ?: return

        // Keep the content (and close button) within the system bars
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        binding.tubeArchivistWebView.apply {
            settings.applyDefault()
            // Keep navigation inside this WebView instead of handing off to an external browser,
            // and stop the pull-to-refresh spinner once the page has loaded.
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    this@TubeArchivistFragment.binding?.swipeRefreshLayout?.isRefreshing = false
                }
            }
            installLocaleWorkaround(this)
            loadUrl(BuildConfig.TUBE_ARCHIVIST_URL)
        }

        // Pull-to-refresh reloads only the Tube Archivist page. Only arm it when scrolled to the
        // top so it doesn't hijack normal scrolling.
        binding.swipeRefreshLayout.apply {
            setColorSchemeResources(R.color.jellyfin_accent)
            setOnChildScrollUpCallback { _, _ -> binding.tubeArchivistWebView.scrollY > 0 }
            setOnRefreshListener {
                binding.tubeArchivistWebView.reload()
            }
        }

        binding.closeButton.setOnClickListener {
            parentFragmentManager.popBackStack()
        }
    }

    /**
     * Tube Archivist's frontend crashes with "Invalid language tag: null" when it passes a
     * null/empty locale to the JavaScript Intl APIs — which happens in a WebView (unlike a real
     * browser) when its stored locale is missing. Inject a document-start shim that coerces such
     * values to a valid tag before Tube Archivist's bundle runs, so its pages render.
     */
    private fun installLocaleWorkaround(webView: WebView) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        // This WebView only ever loads Tube Archivist, so allow the shim on all origins.
        WebViewCompat.addDocumentStartJavaScript(webView, LOCALE_WORKAROUND_SCRIPT, setOf("*"))
    }

    override fun onInterceptBackPressed(): Boolean {
        val webView = binding?.tubeArchivistWebView ?: return false
        return if (webView.canGoBack()) {
            webView.goBack()
            true
        } else {
            false
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }

    private companion object {
        /**
         * Runs before Tube Archivist's scripts. Wraps the Intl constructors so a null/empty (or
         * the literal string "null"/"undefined") locale is replaced with a valid fallback instead
         * of throwing "Invalid language tag".
         */
        val LOCALE_WORKAROUND_SCRIPT = """
            (function () {
                var FALLBACK = 'en-US';
                function bad(loc) {
                    return loc === null || loc === undefined || loc === 'null' || loc === 'undefined' ||
                        (typeof loc === 'string' && loc.length === 0);
                }

                // The WebView reports navigator.language as "null"; override it with a valid tag so
                // Tube Archivist reads a usable value at the source.
                try {
                    Object.defineProperty(navigator, 'language', { configurable: true, get: function () { return FALLBACK; } });
                    Object.defineProperty(navigator, 'languages', { configurable: true, get: function () { return [FALLBACK]; } });
                } catch (e) {}

                // Intl constructors/functions take the locale as the first argument.
                var names = ['DateTimeFormat', 'NumberFormat', 'Collator', 'PluralRules',
                    'RelativeTimeFormat', 'ListFormat', 'Locale', 'DisplayNames', 'Segmenter'];
                names.forEach(function (name) {
                    var Orig = Intl[name];
                    if (typeof Orig !== 'function') return;
                    function Wrapped() {
                        var args = Array.prototype.slice.call(arguments);
                        if (args.length === 0 || bad(args[0])) { args[0] = FALLBACK; }
                        if (new.target) return Reflect.construct(Orig, args, new.target);
                        return Orig.apply(this, args);
                    }
                    Wrapped.prototype = Orig.prototype;
                    try { Object.setPrototypeOf(Wrapped, Orig); } catch (e) {}
                    Intl[name] = Wrapped;
                });
                if (typeof Intl.getCanonicalLocales === 'function') {
                    var origCanon = Intl.getCanonicalLocales;
                    Intl.getCanonicalLocales = function (loc) { return origCanon(bad(loc) ? FALLBACK : loc); };
                }

                // toLocale* methods take the locale as the first argument too.
                function wrapArg0(proto, method) {
                    if (!proto || typeof proto[method] !== 'function') return;
                    var orig = proto[method];
                    Object.defineProperty(proto, method, {
                        configurable: true, writable: true,
                        value: function () {
                            var args = Array.prototype.slice.call(arguments);
                            if (args.length >= 1 && bad(args[0])) { args[0] = FALLBACK; }
                            return orig.apply(this, args);
                        },
                    });
                }
                wrapArg0(Number.prototype, 'toLocaleString');
                wrapArg0(Date.prototype, 'toLocaleString');
                wrapArg0(Date.prototype, 'toLocaleDateString');
                wrapArg0(Date.prototype, 'toLocaleTimeString');
                if (typeof BigInt !== 'undefined') wrapArg0(BigInt.prototype, 'toLocaleString');

                // String.prototype.localeCompare takes the locale as the second argument.
                var origCompare = String.prototype.localeCompare;
                if (typeof origCompare === 'function') {
                    Object.defineProperty(String.prototype, 'localeCompare', {
                        configurable: true, writable: true,
                        value: function (that, locales, options) {
                            if (bad(locales)) { locales = FALLBACK; }
                            return origCompare.call(this, that, locales, options);
                        },
                    });
                }
            })();
        """.trimIndent()
    }
}
