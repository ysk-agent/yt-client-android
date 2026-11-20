package app.ytclient

import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Rational
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
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
 * is left alone.
 *
 * v0.1.11: Picture-in-Picture and the playback-rate reset fix.
 * PiP: pressing Home / switching away while a video plays enters PiP
 * with a single play/pause RemoteAction; leaving via Back still
 * finishes the activity. While in PiP the WebView is not paused, so
 * playback continues in the small window.
 *
 * v0.1.12: PiP entry switches to the Android 12+ auto-enter mechanism.
 * On the user's device (Android 15) the manual enterPictureInPictureMode
 * call from onUserLeaveHint proved unreliable: the home transition's
 * recents/gesture machinery starts pausing the activity before the call
 * lands, and the platform returned false even with the player playing
 * (device logcat, 2026-11-20). With auto-enter, the PiP params carry
 * setAutoEnterEnabled(isPlaying()) — refreshed on every playing-state
 * flip through buildPipParams() — and the system itself performs the
 * PiP transition as part of the leave, so there is no call to race.
 * The manual call in onUserLeaveHint remains as the only path on
 * API 26–30 and as a harmless fallback on 31+.
 * Rate fix: the embed player silently resets its playbackRate to 1x
 * while the app is backgrounded, even though its settings UI keeps
 * showing the user's chosen rate. The app therefore tracks the rate the
 * player reports WHILE THIS ACTIVITY IS IN THE FOREGROUND (that is the
 * rate the user actually chose) and re-applies it with setPlaybackRate
 * whenever the activity returns to the foreground. Rate reports that
 * arrive while backgrounded — the spurious 1x reset among them — never
 * overwrite the tracked value.
 */
class PlayerActivity : AppCompatActivity() {
    private var webView: WebView? = null
    private var blockedCount = 0
    private val blockedHosts = LinkedHashMap<String, Int>()

    private var videoId: String = ""

    /** True between onResume and onPause. Rate reports only count as the
     *  user's choice while this is true. */
    @Volatile private var resumed = false
    private var hasResumedOnce = false

    /** Last playerState reported by the probe: -1 unstarted, 0 ended,
     *  1 playing, 2 paused, 3 buffering, 5 cued. */
    @Volatile private var lastPlayerState = -1

    /** The playback rate the user chose, as last reported by the player
     *  while this activity was in the foreground. Null until the player
     *  reports any rate. */
    @Volatile private var userRate: Double? = null

    /** CI hook (ci_set_rate intent extra): rate to apply once playback
     *  starts, so the launch test can drive the rate fix scenario. */
    private var pendingCiRate: Double? = null

    private val pipToggleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_PIP_TOGGLE) togglePlayPause()
        }
    }
    private var pipReceiverRegistered = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        videoId = intent.getStringExtra("video_id") ?: ""
        intent.getStringExtra("title")?.takeIf { it.isNotBlank() }?.let { title = it }
        pendingCiRate = intent.getStringExtra("ci_set_rate")?.toDoubleOrNull()
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

        val filter = IntentFilter(ACTION_PIP_TOGGLE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(pipToggleReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(pipToggleReceiver, filter)
        }
        pipReceiverRegistered = true
        setPictureInPictureParams(buildPipParams())

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
     * YtProbe bridge so the player state — ready, playing, rate changes,
     * or an error code — is observable in logcat. The wrapper is
     * cross-origin to the iframe, so DOM inspection of the player is
     * impossible by design; these events are the probe channel.
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

    /**
     * The one command path to the player: a postMessage command to the
     * embed iframe (playVideo / pauseVideo / setPlaybackRate …). Used by
     * the PiP play/pause action, the rate re-apply, and the CI rate hook,
     * so there is exactly one place that knows the wire format.
     */
    private fun sendPlayerCommand(func: String, args: String = "") {
        val wv = webView ?: return
        val js = "(function(){var f=document.getElementById('player');" +
            "if(f&&f.contentWindow){f.contentWindow.postMessage(JSON.stringify(" +
            "{event:'command',func:'$func',args:[$args]}),'https://www.youtube.com');}})()"
        runOnUiThread { wv.evaluateJavascript(js, null) }
    }

    private fun isPlaying(): Boolean = lastPlayerState == 1 || lastPlayerState == 3

    private fun togglePlayPause() {
        if (isPlaying()) sendPlayerCommand("pauseVideo") else sendPlayerCommand("playVideo")
    }

    /** Re-apply the user's chosen rate if the player may have reset it
     *  while we were away. Idempotent: sending the current rate again is
     *  a no-op for the player. */
    private fun maybeReapplyRate(reason: String) {
        val rate = userRate ?: return
        if (rate == 1.0) return
        sendPlayerCommand("setPlaybackRate", rate.toString())
        Log.d(PROBE_TAG, "reapply playbackRate $rate ($reason)")
    }

    /** A playback rate reported by the player. Only foreground reports
     *  are the user's choice; background reports (above all the spurious
     *  reset to 1x) must not clobber [userRate]. */
    private fun onRateObserved(rate: Double) {
        if (!resumed) return
        if (userRate != rate) {
            userRate = rate
            Log.d(PROBE_TAG, "userRate=$rate (foreground)")
        }
    }

    private fun onPlayerStateObserved(state: Int) {
        val wasPlaying = isPlaying()
        lastPlayerState = state
        if (state == 1 && pendingCiRate != null) {
            val rate = pendingCiRate
            pendingCiRate = null
            Log.d(PROBE_TAG, "ci hook: setPlaybackRate $rate")
            sendPlayerCommand("setPlaybackRate", rate.toString())
        }
        if (isPlaying() != wasPlaying) {
            runOnUiThread { setPictureInPictureParams(buildPipParams()) }
        }
    }

    /** Receives the wrapper's player-event log lines, and picks the two
     *  facts the app itself needs — playback rate and player state — out
     *  of the event stream. */
    private inner class ProbeBridge {
        @JavascriptInterface
        fun log(message: String) {
            Log.d(PROBE_TAG, message.take(500))
            parseProbeEvent(message)
        }
    }

    private fun parseProbeEvent(message: String) {
        val start = message.indexOf('{')
        if (start < 0) return
        try {
            val json = JSONObject(message.substring(start))
            val info = json.optJSONObject("info")
            if (info != null) {
                if (info.has("playbackRate")) {
                    onRateObserved(info.optDouble("playbackRate", 1.0))
                }
                if (info.has("playerState")) {
                    onPlayerStateObserved(info.optInt("playerState", -1))
                }
            } else if (json.optString("event") == "onPlaybackRateChange") {
                // The same fact in its other wire form: info is the rate.
                val rate = json.optDouble("info", Double.NaN)
                if (!rate.isNaN()) onRateObserved(rate)
            }
        } catch (e: Exception) {
            // Probe parsing is best-effort; the raw line is already logged.
        }
    }

    // ---- Picture-in-Picture ----

    private fun buildPipParams(): PictureInPictureParams {
        val playing = isPlaying()
        val action = RemoteAction(
            Icon.createWithResource(
                this,
                if (playing) android.R.drawable.ic_media_pause
                else android.R.drawable.ic_media_play,
            ),
            if (playing) "一時停止" else "再生",
            if (playing) "一時停止" else "再生",
            android.app.PendingIntent.getBroadcast(
                this,
                PIP_REQUEST_CODE,
                Intent(ACTION_PIP_TOGGLE).setPackage(packageName),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                    android.app.PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .setActions(listOf(action))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Auto-enter (v0.1.12): the system enters PiP itself during
            // the home transition while this flag is on, instead of the
            // app racing the transition with a manual call. The flag
            // tracks isPlaying() because every params refresh — the
            // initial set in onCreate and every playing-state flip —
            // goes through this builder.
            builder.setAutoEnterEnabled(playing)
        }
        return builder.build()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Fires when the user leaves for another app (Home, recents) but
        // NOT for Back, which finishes the activity directly — that
        // distinction is exactly the PiP entry condition.
        Log.d(
            PROBE_TAG,
            "userLeaveHint: video=${videoId.isNotBlank()} state=$lastPlayerState playing=${isPlaying()}",
        )
        if (videoId.isBlank() || !isPlaying()) return
        // Fallback only: on API 31+ auto-enter (buildPipParams) normally
        // performs the transition itself; this manual call is the sole
        // path on API 26–30 and a no-op/false once auto-enter has fired.
        val entered = try {
            enterPictureInPictureMode(buildPipParams())
        } catch (e: Exception) {
            Log.d(PROBE_TAG, "pip enter threw: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
        Log.d(PROBE_TAG, "pip enter requested -> $entered")
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        Log.d(PROBE_TAG, "pip mode changed: $isInPictureInPictureMode")
        if (!isInPictureInPictureMode) {
            // Back to full screen: the rate may have been reset in PiP.
            maybeReapplyRate("pip-exit")
        }
    }

    override fun onPause() {
        resumed = false
        // In PiP the activity is paused but must keep playing: pausing
        // the WebView would freeze the small window's playback.
        if (!isInPictureInPictureMode) {
            webView?.onPause()
        }
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        webView?.onResume()
        if (hasResumedOnce) {
            maybeReapplyRate("resume")
        } else {
            hasResumedOnce = true
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "session total: blocked=$blockedCount hosts=$blockedHosts")
        if (pipReceiverRegistered) {
            try {
                unregisterReceiver(pipToggleReceiver)
            } catch (e: Exception) {
                // Already unregistered; teardown continues regardless.
            }
            pipReceiverRegistered = false
        }
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
        const val ACTION_PIP_TOGGLE = "app.ytclient.action.PIP_TOGGLE"
        const val PIP_REQUEST_CODE = 1

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
