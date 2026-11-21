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
    private const val SHRINK_AFTER = 1100L
    private const val HIDE_AFTER = 2600L
    private const val SHOW_MS = 280L
    private const val HIDE_MS = 220L

    // ---- Position (dp) ----
    private const val SLIDE_DP = 14f      // slide distance on show/hide (towards the left edge)
    private const val Y_SHIFT_DP = 36f    // iOS HUD sits slightly above the vertical center

    private lateinit var app: Application
    private lateinit var wm: WindowManager
    private lateinit var audio: AudioManager
    private lateinit var pill: PillView

    private val handler = Handler(Looper.getMainLooper())
    private var shown = false
    private var stream = AudioManager.STREAM_MUSIC

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

    private fun dp(v: Float): Int = (v * app.resources.displayMetrics.density).toInt()

    fun init(application: Application) {
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
                    pill.expand()
                    scheduleHide()
                }
            }
            val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                app.registerReceiver(receiver, filter)
            }
            updateLevel(animate = false)   // start from the real volume, no fill-up animation
            ready = true
            XposedBridge.log("iOSVolume: injector ready")
        } catch (t: Throwable) {
            XposedBridge.log("iOSVolume: init failed: $t")
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
        // LEFT (not START) so it stays on the left edge on RTL devices too
        lp.gravity = Gravity.LEFT or Gravity.CENTER_VERTICAL
        lp.x = 0
        lp.y = -dp(Y_SHIFT_DP)
        lp.title = "iOSPill"
        return lp
    }

    private fun show() {
        pill.animate().cancel()
        if (!shown) {
            pill.alpha = 0f
            pill.translationX = -dp(SLIDE_DP).toFloat()
            pill.scaleX = 0.92f
            pill.scaleY = 0.92f
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
        }
        pill.expand()
        pill.animate()
            .alpha(1f)
            .translationX(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(SHOW_MS)
            .setInterpolator(easeOut)
            .start()
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
            .translationX(-dp(SLIDE_DP).toFloat())
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

    private fun scheduleHide() {
        handler.removeCallbacks(shrinkRunnable)
        handler.removeCallbacks(hideRunnable)
        handler.postDelayed(shrinkRunnable, SHRINK_AFTER)
        handler.postDelayed(hideRunnable, HIDE_AFTER)
    }
}
