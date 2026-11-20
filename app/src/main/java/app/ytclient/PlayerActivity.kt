package app.ytclient

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import java.io.ByteArrayInputStream

/**
 * Provisional in-app player (v0.1.9, 仮追加): a full-screen WebView on the
 * YouTube embed player, plus request-level ad blocking.
 *
 * v0.1.10: the embed is no longer loaded with a bare loadUrl(). Since 2025
 * YouTube's embedded player requires a Referer from the embedding context;
 * a direct navigation sends none and the player shows
 * 「動画プレーヤーの設定エラー」 (error 153). Instead a minimal local HTML
 * wrapper is loaded with base URL https://www.youtube.com, and an <iframe>
 * inside it loads the embed — that request carries
 * Referer: https://www.youtube.com/ and plays.
 *
 * The blocking realises what "AdGuard DNS" would do — sinkhole the ad /
 * tracker domains — at the WebView request layer instead of the DNS layer,
 * because an app cannot take over DNS for just itself and the device's
 * VpnService slot is already occupied by Moka VPN. Blocked requests get an
 * empty 204 response; everything else (above all the video stream hosts)
 * is left alone. No PiP, no background playback, no speed control: those
 * stay yt-player's job.
 */
class PlayerActivity : AppCompatActivity() {
    private var webView: WebView? = null
    private var blockedCount = 0
    private val blockedHosts = LinkedHashMap<String, Int>()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val videoId = intent.getStringExtra("video_id") ?: ""
        intent.getStringExtra("title")?.takeIf { it.isNotBlank() }?.let { title = it }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val wv = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(Color.BLACK)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
            }
            webViewClient = AdBlockClient()
        }
        webView = wv
        setContentView(wv)
        if (videoId.isNotBlank()) {
            val embedUrl =
                "https://www.youtube.com/embed/$videoId?autoplay=1&playsinline=1&rel=0"
            wv.loadDataWithBaseURL(
                "https://www.youtube.com",
                wrapperHtml(embedUrl),
                "text/html",
                "UTF-8",
                null,
            )
        }
    }

    /** Minimal black full-viewport page holding only the embed iframe. */
    private fun wrapperHtml(embedUrl: String): String = """
        <!DOCTYPE html>
        <html>
        <head>
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>
          html, body { margin: 0; padding: 0; background: #000; height: 100%; }
          iframe { position: absolute; top: 0; left: 0; width: 100%; height: 100%; border: 0; }
        </style>
        </head>
        <body>
        <iframe src="$embedUrl" frameborder="0" allowfullscreen
          allow="autoplay; encrypted-media; picture-in-picture"></iframe>
        </body>
        </html>
    """.trimIndent()

    override fun onPause() {
        webView?.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
    }

    override fun onDestroy() {
        Log.d(TAG, "session total: blocked=$blockedCount hosts=$blockedHosts")
        webView?.apply {
            stopLoading()
            destroy()
        }
        webView = null
        super.onDestroy()
    }

    private inner class AdBlockClient : WebViewClient() {
        override fun onPageFinished(view: WebView, url: String?) {
            super.onPageFinished(view, url)
            // Error-153 probe: once the wrapper has loaded, look inside the
            // iframe document (same origin as the wrapper's base URL) for
            // the error page signature and log the state, so a working
            // player is distinguishable from the 「設定エラー」 page in
            // logcat / CI. Log-only; never affects playback.
            for (delayMs in PROBE_DELAYS_MS) {
                view.postDelayed({ probePlayerState(view) }, delayMs)
            }
        }

        private fun probePlayerState(view: WebView) {
            if (webView == null) return
            try {
                view.evaluateJavascript(PROBE_JS) { result ->
                    Log.d(PROBE_TAG, "state=$result")
                }
            } catch (e: Exception) {
                Log.d(PROBE_TAG, "state=probe-failed ${e.message}")
            }
        }

        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest,
        ): WebResourceResponse? {
            val host = request.url.host?.lowercase() ?: return null
            val path = request.url.path ?: ""
            if (!isBlocked(host, path)) return null
            blockedCount++
            blockedHosts[host] = (blockedHosts[host] ?: 0) + 1
            Log.d(TAG, "blocked #$blockedCount $host$path")
            // A fresh response per request: its body stream is consumed.
            return WebResourceResponse(
                "text/plain", "UTF-8", 204, "No Content",
                mapOf("Cache-Control" to "no-store"),
                ByteArrayInputStream(ByteArray(0)),
            )
        }
    }

    private companion object {
        const val TAG = "YtAdBlock"
        const val PROBE_TAG = "YtPlayerProbe"

        /** Delays after the wrapper's onPageFinished at which the iframe
         *  state is probed; the embed player settles asynchronously. */
        val PROBE_DELAYS_MS = longArrayOf(4_000L, 10_000L, 20_000L)

        /**
         * Runs in the wrapper page and classifies the iframe's document:
         * "error153" when the error page signature (.ytp-error element or
         * the 設定エラー / エラー 153 text) is present, "player" when the
         * HTML5 player element exists, otherwise "unknown". The result
         * reaches the evaluateJavascript callback JSON-quoted.
         */
        val PROBE_JS = """
            (function(){try{
              var f=document.querySelector('iframe');
              if(!f) return 'no-iframe';
              var d=f.contentDocument;
              if(!d) return 'no-doc';
              if(d.querySelector('.ytp-error')) return 'error153';
              var t=(d.body&&d.body.innerText)||'';
              if(/(設定エラー|エラー\s*153|error\s*153)/i.test(t)) return 'error153';
              if(d.querySelector('.html5-video-player')||d.getElementById('movie_player')) return 'player';
              return 'unknown';
            }catch(e){return 'exc';}})()
        """.trimIndent()

        /**
         * Host suffixes AdGuard DNS is known to sinkhole: ad serving and
         * tracker collection. A request is blocked when its host equals a
         * suffix or sits under it (so pagead2.googlesyndication.com is
         * covered by googlesyndication.com).
         */
        val BLOCKED_HOST_SUFFIXES = listOf(
            "doubleclick.net",
            "googlesyndication.com",
            "googleadservices.com",
            "adservice.google.com",
            "google-analytics.com",
            "googletagmanager.com",
        )

        /**
         * Ad endpoints served from YouTube's own hosts, where the host
         * alone cannot discriminate: matched as path substrings, and only
         * on youtube.com hosts. The embed document itself (/embed/…) does
         * not match any of these.
         */
        val BLOCKED_PATH_MARKERS = listOf(
            "/pagead/",
            "/api/stats/ads",
            "/api/stats/atr",
            "/get_midroll_info",
        )

        /** Playback-critical hosts: never blocked, whatever the path. */
        val PROTECTED_HOST_SUFFIXES = listOf(
            "googlevideo.com",
            "youtubei.googleapis.com",
            "i.ytimg.com",
        )

        fun hostMatches(host: String, suffix: String): Boolean =
            host == suffix || host.endsWith(".$suffix")

        fun isBlocked(host: String, path: String): Boolean {
            if (PROTECTED_HOST_SUFFIXES.any { hostMatches(host, it) }) return false
            if (BLOCKED_HOST_SUFFIXES.any { hostMatches(host, it) }) return true
            if (hostMatches(host, "youtube.com") &&
                BLOCKED_PATH_MARKERS.any { path.contains(it) }
            ) return true
            return false
        }
    }
}
