package app.ytclient

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
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
 * YouTube's embedded player requires a Referer identifying the embedder:
 * a direct navigation sends none and the player shows
 * 「動画プレーヤーの設定エラー」 (error 153). A minimal local HTML wrapper
 * is therefore loaded via loadDataWithBaseURL and an <iframe> inside it
 * loads the embed. The base URL must NOT be https://www.youtube.com
 * itself: a page claiming to be youtube.com hosting a youtube.com iframe
 * is rejected as an invalid embed context ("This video is unavailable",
 * error 152 — verified in CI). YouTube's documented identity form for
 * native apps is https://<applicationId>, so the base URL is
 * https://app.ytclient: the iframe request then carries
 * Referer: https://app.ytclient, a legitimate third-party embedder.
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
            addJavascriptInterface(ProbeBridge(), "YtProbe")
        }
        webView = wv
        setContentView(wv)
        if (videoId.isNotBlank()) {
            val embedUrl = "https://www.youtube.com/embed/$videoId" +
                "?autoplay=1&playsinline=1&rel=0&enablejsapi=1&origin=$BASE_URL"
            wv.loadDataWithBaseURL(
                BASE_URL,
                wrapperHtml(embedUrl),
                "text/html",
                "UTF-8",
                null,
            )
        }
    }

    /**
     * Minimal black full-viewport page holding only the embed iframe, plus
     * a listener for the player's postMessage events (enabled by
     * enablejsapi=1): every event the iframe posts is forwarded to the
     * YtProbe bridge so the player state — ready, playing, or an error
     * code — is observable in logcat. The wrapper is cross-origin to the
     * iframe, so DOM inspection of the player is impossible by design;
     * these events are the probe channel.
     */
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
        <iframe id="player" src="$embedUrl" frameborder="0" allowfullscreen
          allow="autoplay; encrypted-media; picture-in-picture"></iframe>
        <script>
          function ytLog(s) { try { if (window.YtProbe) YtProbe.log(s); } catch (e) {} }
          window.addEventListener('message', function (e) {
            if (e.origin !== 'https://www.youtube.com') return;
            ytLog('msg ' + (typeof e.data === 'string' ? e.data : JSON.stringify(e.data)));
          });
          var f = document.getElementById('player');
          function subscribe() {
            try {
              f.contentWindow.postMessage(
                JSON.stringify({event: 'listening', id: 'ytclient', channel: 'widget'}),
                'https://www.youtube.com');
            } catch (e) {}
          }
          f.addEventListener('load', function () {
            subscribe();
            setTimeout(subscribe, 2000);
            setTimeout(subscribe, 5000);
          });
          ytLog('wrapper ready');
        </script>
        </body>
        </html>
    """.trimIndent()

    /** Receives the wrapper's player-event log lines. Log-only. */
    private inner class ProbeBridge {
        @JavascriptInterface
        fun log(message: String) {
            Log.d(PROBE_TAG, message.take(500))
        }
    }

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

        /**
         * Origin the wrapper page is served from, hence the Referer the
         * embed request carries. YouTube's documented client-identity form
         * for native apps is https://<applicationId>. Deliberately NOT
         * https://www.youtube.com: claiming to be youtube.com itself is
         * rejected with error 152 (see the class doc).
         */
        const val BASE_URL = "https://app.ytclient"

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
