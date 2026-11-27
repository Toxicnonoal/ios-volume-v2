package app.iosvolume.module

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.PathInterpolator
import de.robv.android.xposed.XposedBridge
import kotlin.math.roundToInt

object VolumeInjector {

    // ---- Timing (ms) – iOS: HUD appears wide, collapses to a thin bar, then leaves ----
    private const val SHRINK_AFTER = 350L
    private const val DEFAULT_HIDE = 2400L
    private const val SHOW_MS = 260L
    private const val HIDE_MS = 240L

    // ---- Position (dp) ----
    private const val SLIDE_DP = 14f      // slide distance on show/hide (towards the left edge)
    private const val Y_SHIFT_DP = 36f    // iOS HUD sits slightly above the vertical center

    private lateinit var app: Context
    private lateinit var wm: WindowManager
    private lateinit var audio: AudioManager
    private lateinit var pill: PillView

    private val handler = Handler(Looper.getMainLooper())
    private var shown = false
    private var stream = AudioManager.STREAM_MUSIC

    // ---- user settings (changed from the module's settings screen) ----
    private const val ACTION_APPLY = "app.iosvolume.module.APPLY"
    private const val ACTION_PREVIEW = "app.iosvolume.module.PREVIEW"
    private var side = 0                 // 0 = left, 1 = right
    private var hideAfter = DEFAULT_HIDE

    @Volatile
    var ready = false
        private set

    private val easeOut = PathInterpolator(0.22f, 1f, 0.36f, 1f)

    private val shrinkRunnable = Runnable { pill.shrink() }
    private val hideRunnable = Runnable { hide() }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val type = i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1)
            if (type !in intArrayOf(
                    AudioManager.STREAM_MUSIC,
                    AudioManager.STREAM_RING,
                    AudioManager.STREAM_VOICE_CALL,
                    AudioManager.STREAM_NOTIFICATION,
                    AudioManager.STREAM_ALARM
                )
            ) return
            stream = type
            if (!pill.dragging) updateLevel(animate = true)
            show()
        }
    }

    private val settingsReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            try {
                when (i.action) {
                    ACTION_APPLY -> applySettings(
                        i.getIntExtra("style", 0),
                        i.getIntExtra("side", 0),
                        i.getIntExtra("hide", DEFAULT_HIDE.toInt()).toLong(),
                        true
                    )
                    ACTION_PREVIEW -> requestShow()
                }
            } catch (t: Throwable) {
                XposedBridge.log("iOSVolume: settings error: $t")
            }
        }
    }

    private fun applySettings(style: Int, sideValue: Int, hideMs: Long, save: Boolean) {
        val newSide = if (sideValue == 1) 1 else 0
        val sideChanged = newSide != side
        side = newSide
        hideAfter = hideMs.coerceIn(800L, 8000L)
        pill.setStyle(style)
        pill.onRight = side == 1
        if (sideChanged && shown) forceRemove()   // re-added on the new edge at the next show
        if (save) {
            try {
                app.getSharedPreferences("iosvol_settings", 0).edit()
                    .putInt("style", style).putInt("side", side).putLong("hide", hideAfter).apply()
            } catch (t: Throwable) {
                XposedBridge.log("iOSVolume: saving settings failed: $t")
            }
        }
    }

    private fun loadSettings() {
        try {
            val sp = app.getSharedPreferences("iosvol_settings", 0)
            applySettings(sp.getInt("style", 0), sp.getInt("side", 0), sp.getLong("hide", DEFAULT_HIDE), false)
        } catch (t: Throwable) {
            XposedBridge.log("iOSVolume: loading settings failed: $t")
        }
    }

    private fun forceRemove() {
        handler.removeCallbacks(shrinkRunnable)
        handler.removeCallbacks(hideRunnable)
        pill.animate().cancel()
        try {
            wm.removeView(pill)
        } catch (e: Throwable) {
        }
        shown = false
    }

    private fun dir(): Float = if (side == 1) 1f else -1f

    private fun dp(v: Float): Int = (v * app.resources.displayMetrics.density).toInt()

    fun init(application: Context) {
        if (ready) return
        try {
            app = application
            wm = app.getSystemService("window") as WindowManager
            audio = app.getSystemService("audio") as AudioManager
            pill = PillView(app).apply {
                onLevelChanged = { v ->
                    val max = audio.getStreamMaxVolume(stream)
                    audio.setStreamVolume(stream, (max * v).roundToInt(), 0)
                }
                onActivity = {
                    pill.expand()               // touching the bar widens it (iOS)
                    scheduleHide(900L)          // stay wide a bit after the finger leaves
                }
            }
            val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                app.registerReceiver(receiver, filter)
            }
            val sf = IntentFilter()
            sf.addAction(ACTION_APPLY)
            sf.addAction(ACTION_PREVIEW)
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(settingsReceiver, sf, Context.RECEIVER_EXPORTED)
            } else {
                app.registerReceiver(settingsReceiver, sf)
            }
            loadSettings()
            updateLevel(animate = false)   // start from the real volume, no fill-up animation
            ready = true
            XposedBridge.log("iOSVolume: injector ready")
        } catch (t: Throwable) {
            XposedBridge.log("iOSVolume: init failed: $t")
            XposedBridge.log(t)
        }
    }

    /** Called from the Dialog.show hook (already on the thread that showed the dialog). */
    fun requestShow() {
        handler.post {
            if (!ready) return@post
            if (!pill.dragging) updateLevel(animate = true)
            show()
        }
    }

    private fun updateLevel(animate: Boolean) {
        val max = audio.getStreamMaxVolume(stream).coerceAtLeast(1)
        pill.updateLevel(audio.getStreamVolume(stream).toFloat() / max, animate)
    }

    private fun params(type: Int): WindowManager.LayoutParams {
        val lp = WindowManager.LayoutParams(
            dp(PillView.VIEW_W),
            dp(PillView.VIEW_H),
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )
        // LEFT/RIGHT (not START/END) so the side does not flip on RTL devices
        lp.gravity = (if (side == 1) Gravity.RIGHT else Gravity.LEFT) or Gravity.CENTER_VERTICAL
        lp.x = 0
        lp.y = -dp(Y_SHIFT_DP)
        lp.title = "iOSPill"
        return lp
    }

    private fun show() {
        pill.animate().cancel()
        if (!shown) {
            // iOS: pops in already WIDE (slides in from the edge, height grows), then collapses to thin
            pill.snapWide()
            pill.alpha = 0f
            pill.translationX = dir() * dp(SLIDE_DP)
            pill.scaleX = 0.9f
            pill.scaleY = 0.78f
            try {
                wm.addView(pill, params(2020))      // TYPE_VOLUME_OVERLAY
                shown = true
            } catch (t: Throwable) {
                try {
                    wm.addView(pill, params(2038))  // TYPE_APPLICATION_OVERLAY
                    shown = true
                } catch (t2: Throwable) {
                    XposedBridge.log("iOSVolume: addView failed: $t2")
                }
            }
            pill.animate()
                .alpha(1f)
                .translationX(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(SHOW_MS)
                .setInterpolator(easeOut)
                .start()
        } else {
            // already visible (thin): just keep it, the fill animates to the new level - no re-expand
            pill.animate()
                .alpha(1f)
                .translationX(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(120L)
                .setInterpolator(easeOut)
                .start()
        }
        scheduleHide()
    }

    private fun hide() {
        if (!shown) return
        if (pill.dragging) {          // finger still on it – keep it
            scheduleHide()
            return
        }
        pill.animate()
            .alpha(0f)
            .translationX(dir() * dp(SLIDE_DP))
            .scaleX(0.94f)
            .scaleY(0.94f)
            .setDuration(HIDE_MS)
            .setInterpolator(AccelerateInterpolator(1.4f))
            .withEndAction {
                try {
                    wm.removeView(pill)
                } catch (e: Throwable) {
                }
                shown = false
            }
            .start()
    }

    private fun scheduleHide(shrinkAfter: Long = SHRINK_AFTER) {
        handler.removeCallbacks(shrinkRunnable)
        handler.removeCallbacks(hideRunnable)
        handler.postDelayed(shrinkRunnable, shrinkAfter)
        handler.postDelayed(hideRunnable, hideAfter)
    }
}
