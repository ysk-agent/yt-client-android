package app.ytclient

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.util.Rational
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.widget.FrameLayout
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
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
 *
 * v0.1.15: four additions.
 * - Back while playing enters PiP (over the Detail screen beneath)
 *   instead of finishing; Back while paused still finishes.
 * - The chosen rate is remembered app-wide (Prefs.playbackRate) and
 *   applied when a newly opened video starts playing.
 * - Per-video resume positions: while playing, the position is polled
 *   every 5 s (getCurrentTime command; the player answers through the
 *   same infoDelivery probe events that carry state and rate) and
 *   saved to Prefs; reopening a video seeks back to it once playback
 *   starts. Ending a video, or reaching its last 10 seconds, clears
 *   the saved point.
 * - The WebView gets the status-bar height as top padding (in PiP it
 *   is dropped again): with targetSdk 35 the activity draws
 *   edge-to-edge, and without the inset the embed's top controls sat
 *   underneath the system status bar.
 *
 * v0.1.16: two of the v0.1.15 mechanisms were wrong, found on-device
 * (logcat of the user's own session, 2026-11-21):
 * - Rate memory erased itself. A fresh player's first infoDelivery
 *   bundles playerState=1 with playbackRate=1 (its default), and the
 *   parser handled the rate first: the default report was persisted
 *   over the remembered rate before the apply step read it back, so
 *   nothing was ever applied. The remembered rate is now a target
 *   held in memory: differing reports while a target is pending are
 *   the not-yet-applied default — they trigger a re-apply (bounded)
 *   and are never persisted; only the player's confirmation of the
 *   target, or a report with no target pending, counts as a choice.
 * - WebView padding does not move web content (the page is composited
 *   over the view's full bounds), so the status-bar inset now shrinks
 *   the WebView's layout bounds instead: a top margin equal to the
 *   status-bar height, removed again in PiP.
 * - Duration actually arrives inside info.progressState, not as a
 *   top-level info field; the parser reads it from there (the
 *   near-end resume-point clearing depends on it).
 *
 * v0.1.20: two playback-environment additions.
 * - A speed action inside PiP. The gear in the PiP window is a system
 *   button whose destination (the OS PiP-permission settings) the app
 *   cannot change, so speed control arrives as a custom RemoteAction
 *   with a gear icon instead: each tap cycles to the next rate in
 *   PIP_RATE_STEPS, the action's title shows the current rate, and the
 *   choice goes through the same remembered-rate path as a foreground
 *   choice (userRate + Prefs), so it survives leaving PiP and opening
 *   the next video.
 * - Audio continues on the lock screen. onPause normally pauses the
 *   WebView, which stops playback when the user locks the device; when
 *   the screen is off or the keyguard is locked, the WebView is left
 *   running exactly as it is in PiP, so playback continues as audio
 *   regardless of whether PiP was active.
 *
 * v0.1.21: the PiP speed action is refined from on-device use. The
 * action itself worked (the rate changed), but its system icon
 * rendered as a wrench rather than a gear, and the rate in its title
 * never appeared on screen (PiP shows icons only). The steps are now
 * 1.0 / 1.5 / 2.0 (Yosuke: three are enough) and the icon is a bitmap
 * with the current rate drawn as text, so the rate is visible at a
 * glance and each tap advances it.
 *
 * v0.1.22: rate desync after PiP -> lock -> return (on-device report:
 * the display showed 1.5x while playback was actually 1x). The return
 * paths (PiP exit, resume) used a single fire-and-forget re-apply,
 * which can land before the player is ready after the lock interval
 * — and the embed's silent background reset to 1x then stands, under
 * a settings UI that still shows the user's rate. Both paths now
 * re-arm the remembered rate as the rate target (armRateTarget), so
 * the player's own reports drive the confirmation loop until it
 * verifiably plays at the target.
 *
 * v0.1.23: lock-screen audio, second attempt — the first (v0.1.20,
 * not pausing the WebView) was necessary but not sufficient. An
 * on-device log (2026-11-22) shows why: ~5 s after PiP entry, at the
 * lock moment, the embed reported playerState 2 (paused) on its own
 * — the page being hidden pauses the player — while the process
 * stayed alive and the rate held at 1.5. So a paused report in a
 * background context (PiP, or screen off / keyguard) while playback
 * is desired (desiredPlaying) is now answered with playVideo, up to
 * 3 attempts per episode; an on-screen pause remains the user's and
 * clears the desire, as does the PiP toggle.
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

    /** The rate this session must establish — the remembered rate, or
     * the CI hook's — until the player confirms it by reporting it
     * back. While non-null, a differing rate report is the player's
     * not-yet-applied default: it triggers a bounded re-apply and is
     * never treated as the user's choice (v0.1.16; in v0.1.15 such a
     * report overwrote the remembered rate before it could be applied,
     * so rate memory never survived into a second video). */
    @Volatile private var rateTarget: Double? = null
    @Volatile private var rateApplyAttempts = 0

    /** Status-bar height in px, applied as the WebView's top layout
     * margin outside PiP so the embed's top controls clear the system
     * bar. (Padding does not work: web content is composited over the
     * WebView's full bounds regardless of padding.) */
    private var statusBarPad = 0

    /** True once this instance has seen its first "playing" state: the
     * moment the resume seek and the remembered rate are applied. */
    private var startedOnce = false

    /** Whether playback is supposed to be running, as far as the user
     *  has expressed it: set by playing states and by the PiP toggle,
     *  cleared by an on-screen pause or by the video ending. The embed
     *  pauses ITSELF when the page is hidden (lock screen tearing down
     *  the PiP window) even though nobody asked it to — on-device log,
     *  2026-11-22: playerState 2 arrived ~5 s after PiP entry, at the
     *  lock moment, with the process fully alive. A paused report in a
     *  background context (PiP or screen off/locked) while this is true
     *  is therefore the player's own doing and is answered with a
     *  bounded auto-resume (v0.1.23). */
    @Volatile private var desiredPlaying = false
    @Volatile private var autoResumeAttempts = 0

    /** Last position / duration (seconds) the player reported through
     * the probe, and the last position actually persisted. */
    @Volatile private var lastPosSec = 0.0
    @Volatile private var lastDurSec = 0.0
    @Volatile private var lastSavedPosSec = 0.0

    private val posHandler = Handler(Looper.getMainLooper())
    private val posPoller = object : Runnable {
        override fun run() {
            if (lastPlayerState == 1) {
                // The player answers getter commands with infoDelivery
                // events on the probe channel (parsed in parseProbeEvent).
                sendPlayerCommand("getCurrentTime")
                sendPlayerCommand("getDuration")
                savePosition("poll")
            }
            posHandler.postDelayed(this, 5000)
        }
    }

    private val pipToggleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_PIP_TOGGLE -> togglePlayPause()
                ACTION_PIP_SPEED -> cyclePlaybackRate()
            }
        }
    }
    private var pipReceiverRegistered = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        videoId = intent.getStringExtra("video_id") ?: ""
        intent.getStringExtra("title")?.takeIf { it.isNotBlank() }?.let { title = it }
        pendingCiRate = intent.getStringExtra("ci_set_rate")?.toDoubleOrNull()
        // The rate to establish for this video: the CI hook wins,
        // otherwise the app-wide remembered rate. Seeded into userRate
        // as well, so the resume re-apply works from the start.
        rateTarget = pendingCiRate ?: Prefs.playbackRate(this).takeIf { it != 1.0 }
        rateTarget?.let { userRate = it }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val sbRes = resources.getIdentifier("status_bar_height", "dimen", "android")
        statusBarPad = if (sbRes > 0) resources.getDimensionPixelSize(sbRes) else 0
        val wv = WebView(this).apply {
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
        // Edge-to-edge (targetSdk 35) would otherwise slide the embed's
        // top controls under the status bar. The WebView's own bounds
        // are moved down by the status-bar height (a margin, not
        // padding — web content ignores padding); PiP removes it again.
        val container = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        container.addView(wv, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ).apply { topMargin = statusBarPad })
        setContentView(container)

        val filter = IntentFilter().apply {
            addAction(ACTION_PIP_TOGGLE)
            addAction(ACTION_PIP_SPEED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(pipToggleReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(pipToggleReceiver, filter)
        }
        pipReceiverRegistered = true
        setPictureInPictureParams(buildPipParams())

        // Back while playing: enter PiP over the Detail screen instead
        // of finishing the player (v0.1.15). The activity is resumed and
        // in the foreground here, so the manual enter call does not race
        // a transition the way the Home path did. Back while paused or
        // ended keeps the plain finish — with one exception (v0.1.17):
        // when this activity is the root of its task there is nothing
        // beneath it to go back to. That happens when a player task is
        // restored on its own after the app was closed (the PiP
        // transition had split the player into its own task), and
        // finishing there exits the app with no way back to the feed.
        // Route to the feed explicitly instead.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (videoId.isNotBlank() && isPlaying()) {
                    val entered = try {
                        enterPictureInPictureMode(buildPipParams())
                    } catch (e: Exception) {
                        Log.d(PROBE_TAG, "back pip enter threw: ${e.message}")
                        false
                    }
                    Log.d(PROBE_TAG, "back -> pip: $entered")
                    if (entered) return
                }
                if (isTaskRoot) {
                    Log.d(PROBE_TAG, "back at task root: routing to feed")
                    startActivity(Intent(this@PlayerActivity, MainActivity::class.java).apply {
                        addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP
                        )
                    })
                }
                finish()
            }
        })

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
        if (isPlaying()) {
            desiredPlaying = false
            sendPlayerCommand("pauseVideo")
        } else {
            desiredPlaying = true
            sendPlayerCommand("playVideo")
        }
    }

    /** PiP speed action: cycle to the next rate step and make it the
     *  remembered rate, exactly as if the user had picked it in the
     *  foreground. The pending rate target (if any) is dropped: this
     *  tap is the newest choice, and the foreground re-apply on PiP
     *  exit works from userRate. The PiP params are refreshed so the
     *  action's title shows the new rate. */
    private fun cyclePlaybackRate() {
        val current = userRate ?: 1.0
        val next = PIP_RATE_STEPS.firstOrNull { it > current + 0.001 }
            ?: PIP_RATE_STEPS.first()
        userRate = next
        rateTarget = null
        Prefs.setPlaybackRate(this, next)
        sendPlayerCommand("setPlaybackRate", next.toString())
        Log.d(PROBE_TAG, "pip speed -> $next (was $current)")
        runOnUiThread { setPictureInPictureParams(buildPipParams()) }
    }

    private fun formatRate(rate: Double): String =
        if (rate == rate.toLong().toDouble()) rate.toLong().toString()
        else rate.toString()

    /** The PiP speed action's icon: the current rate drawn as text.
     *  PiP renders custom actions as icons only — the action title is
     *  an accessibility label and never appears on screen (verified
     *  on-device with v0.1.20, whose system "preferences" icon also
     *  rendered as a wrench, not a gear) — so the rate itself has to
     *  be the picture (v0.1.21, Yosuke: showing the rate is better). */
    private fun rateIcon(rate: Double): Icon {
        val size = 96
        val label = "${formatRate(rate)}x"
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = 46f
            val w = measureText(label)
            if (w > size * 0.94f) textSize = 46f * (size * 0.94f) / w
        }
        val y = size / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(label, size / 2f, y, paint)
        return Icon.createWithBitmap(bmp)
    }

    /** Re-establish the user's rate after a background interval (PiP
     *  exit, lock screen, app switch). A single fire-and-forget send
     *  is not enough: the embed silently resets its rate to 1x while
     *  backgrounded — its settings UI keeps showing the user's rate —
     *  and a send issued during the return transition can land before
     *  the player is ready to honour it, leaving actual playback at
     *  1x under a 1.5x display (on-device report, 2026-11-22: PiP,
     *  then lock, then return). So the remembered rate is re-armed as
     *  the rate target: the player's own rate reports then drive the
     *  confirmation loop in onRateObserved — differing reports trigger
     *  bounded re-applies, an equal report confirms — until the player
     *  is verifiably playing at the target. The immediate send and its
     *  log line keep the "reapply playbackRate" probe vocabulary the
     *  CI rate scenario greps for. */
    private fun armRateTarget(reason: String) {
        val rate = userRate ?: return
        if (rate == 1.0) return
        rateTarget = rate
        rateApplyAttempts = 1
        sendPlayerCommand("setPlaybackRate", rate.toString())
        Log.d(PROBE_TAG, "reapply playbackRate $rate ($reason, target armed)")
    }

    /** A playback rate reported by the player. Only foreground reports
     *  count at all; background reports (above all the spurious reset
     *  to 1x) must not clobber [userRate].
     *
     *  While a [rateTarget] is pending, a report is judged against it:
     *  an equal report confirms the target (the remembered rate is now
     *  actually playing); a differing report is the player's
     *  not-yet-applied default, so the target is re-applied (bounded)
     *  and the report is otherwise ignored — never persisted, never
     *  taken as a choice. With no target pending, a foreground report
     *  IS the user's choice and becomes the remembered rate. */
    private fun onRateObserved(rate: Double) {
        if (!resumed) return
        val target = rateTarget
        if (target != null) {
            when {
                rate == target -> {
                    rateTarget = null
                    userRate = rate
                    // Same "userRate=" marker as the remembered-rate
                    // line below: it is the probe vocabulary for "the
                    // user's rate is now in effect", and the CI rate
                    // scenario greps for it.
                    Log.d(PROBE_TAG, "userRate=$rate (target confirmed)")
                }
                !startedOnce -> {
                    // Playback has not begun yet: this report is the
                    // fresh player's default, sent before the target
                    // could possibly apply. It must not consume the
                    // re-apply budget — the target goes out when
                    // playback starts, and only reports after that are
                    // judged. (Burning the budget here is what the CI
                    // rate scenario caught: five pre-start reports
                    // abandoned the target before the state-1 apply.)
                    Log.d(PROBE_TAG, "rate pre-start report $rate ignored (target $target)")
                }
                rateApplyAttempts < 5 -> {
                    rateApplyAttempts++
                    Log.d(PROBE_TAG, "rate not applied yet (saw $rate, want $target), reapply #$rateApplyAttempts")
                    sendPlayerCommand("setPlaybackRate", target.toString())
                }
                else -> {
                    // The player keeps reporting something else (e.g.
                    // the user picked another rate before ours landed):
                    // accept the report as the choice instead of
                    // fighting it forever.
                    rateTarget = null
                    userRate = rate
                    Prefs.setPlaybackRate(this, rate)
                    Log.d(PROBE_TAG, "rate target abandoned; accepted $rate")
                }
            }
            return
        }
        if (userRate != rate) {
            userRate = rate
            Prefs.setPlaybackRate(this, rate)
            Log.d(PROBE_TAG, "userRate=$rate (foreground, remembered)")
        }
    }

    /** Persists the current resume position for this video. Near the
     * end of the video the saved point is dropped instead, so a video
     * watched to (near) completion restarts from the beginning. */
    private fun savePosition(reason: String) {
        val t = lastPosSec
        if (videoId.isBlank() || t <= 0.0) return
        val dur = lastDurSec
        if (dur > 0 && t >= dur - 10) {
            if (Prefs.position(this, videoId) > 0) {
                Prefs.clearPosition(this, videoId)
                Log.d(PROBE_TAG, "pos cleared (near end $t/$dur) $videoId")
            }
            lastSavedPosSec = t
            return
        }
        if (reason == "poll" && kotlin.math.abs(t - lastSavedPosSec) < 5.0) return
        Prefs.setPosition(this, videoId, t)
        lastSavedPosSec = t
        Log.d(PROBE_TAG, "pos save $videoId t=$t ($reason)")
    }

    private fun onPlayerStateObserved(state: Int) {
        val wasPlaying = isPlaying()
        lastPlayerState = state
        if (state == 1) {
            desiredPlaying = true
            autoResumeAttempts = 0
        }
        if (state == 1 && !startedOnce) {
            startedOnce = true
            // Resume where this video was left (positions under 10 s
            // are treated as "not really started" and ignored).
            val saved = Prefs.position(this, videoId)
            if (saved >= 10.0) {
                Log.d(PROBE_TAG, "pos restore: seekTo $saved for $videoId")
                sendPlayerCommand("seekTo", "$saved,true")
            }
            // Establish the session's rate target (remembered rate or
            // CI hook). Confirmation and any re-applies are driven by
            // onRateObserved from here on.
            rateTarget?.let {
                rateApplyAttempts++
                Log.d(PROBE_TAG, "initial rate apply: $it")
                sendPlayerCommand("setPlaybackRate", it.toString())
            }
        }
        if (state == 0) {
            // Ended: the resume point has served its purpose.
            desiredPlaying = false
            Prefs.clearPosition(this, videoId)
            lastPosSec = 0.0
            lastSavedPosSec = 0.0
            Log.d(PROBE_TAG, "pos cleared (ended) $videoId")
        }
        if (state == 2) {
            savePosition("paused")
            // The embed pauses itself when its page is hidden — the
            // lock screen tearing down the PiP window is the observed
            // case (v0.1.23). A pause reported in a background context
            // while playback is desired is not the user's: answer it
            // with playVideo, bounded per episode (the counter resets
            // on the next playing state). A pause with the player on
            // screen IS the user's, and clears the desire.
            val inPip = isInPictureInPictureMode
            val locked = screenOffOrLocked()
            if (!inPip && !locked) {
                desiredPlaying = false
            } else if (desiredPlaying && autoResumeAttempts < 3) {
                autoResumeAttempts++
                Log.d(
                    PROBE_TAG,
                    "auto-resume: player paused itself in background " +
                        "(pip=$inPip locked=$locked), playVideo attempt $autoResumeAttempts",
                )
                sendPlayerCommand("playVideo")
            }
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
                if (info.has("currentTime")) {
                    val t = info.optDouble("currentTime", Double.NaN)
                    if (!t.isNaN() && t >= 0) lastPosSec = t
                }
                // Duration rides inside progressState in the real
                // payload (verified on-device, 2026-11-21); a top-level
                // field is not sent.
                val durObj = if (info.has("duration")) info else info.optJSONObject("progressState")
                if (durObj != null && durObj.has("duration")) {
                    val d = durObj.optDouble("duration", Double.NaN)
                    if (!d.isNaN() && d > 0) lastDurSec = d
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
        val speedLabel = "速度 ${formatRate(userRate ?: 1.0)}x"
        val speedAction = RemoteAction(
            rateIcon(userRate ?: 1.0),
            speedLabel,
            speedLabel,
            android.app.PendingIntent.getBroadcast(
                this,
                PIP_SPEED_REQUEST_CODE,
                Intent(ACTION_PIP_SPEED).setPackage(packageName),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                    android.app.PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .setActions(listOf(action, speedAction))
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
        // The small window has no status bar to clear: drop the inset.
        webView?.let { wv ->
            val lp = wv.layoutParams as? FrameLayout.LayoutParams
            if (lp != null) {
                lp.topMargin = if (isInPictureInPictureMode) 0 else statusBarPad
                wv.layoutParams = lp
            }
        }
        if (!isInPictureInPictureMode) {
            // Back to full screen: the rate may have been reset in PiP.
            armRateTarget("pip-exit")
        }
    }

    /** True when the user has locked the device (or the screen has
     *  turned off): the pause that follows is not a request to stop
     *  playback, it is the transition to audio-only listening. */
    private fun screenOffOrLocked(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm != null && !pm.isInteractive) return true
        val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        return km?.isKeyguardLocked == true
    }

    override fun onPause() {
        resumed = false
        savePosition("activity-pause")
        // In PiP the activity is paused but must keep playing: pausing
        // the WebView would freeze the small window's playback. The
        // same holds when the device is locked or the screen is off —
        // playback continues as audio (v0.1.20), whether or not PiP
        // was active. Only a foreground pause (leaving to another app
        // with the screen on) pauses the WebView, and only then does
        // the position poller stop.
        val keepPlaying = isInPictureInPictureMode || screenOffOrLocked()
        if (keepPlaying) {
            Log.d(
                PROBE_TAG,
                "onPause: keeping playback (pip=$isInPictureInPictureMode " +
                    "screenOffOrLocked=${screenOffOrLocked()})",
            )
        } else {
            posHandler.removeCallbacks(posPoller)
            webView?.onPause()
        }
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        webView?.onResume()
        posHandler.removeCallbacks(posPoller)
        posHandler.postDelayed(posPoller, 5000)
        if (hasResumedOnce) {
            armRateTarget("resume")
        } else {
            hasResumedOnce = true
        }
    }

    override fun onDestroy() {
        savePosition("destroy")
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
        const val ACTION_PIP_SPEED = "app.ytclient.action.PIP_SPEED"
        const val PIP_REQUEST_CODE = 1
        const val PIP_SPEED_REQUEST_CODE = 2

        /** Rate steps the PiP speed action cycles through (Yosuke,
         *  2026-11-21: three are enough — 1.0 / 1.5 / 2.0). */
        val PIP_RATE_STEPS = listOf(1.0, 1.5, 2.0)

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
