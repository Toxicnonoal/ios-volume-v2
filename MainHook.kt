package app.iosvolume.module

import android.app.Application
import android.content.Context
import android.app.Dialog
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Entry point (SystemUI only).
 * Two hooks:
 *  1) Dialog.show      -> AOSP-style volume dialogs
 *  2) WindowManagerImpl.addView -> ROMs (HyperOS/MIUI) that add the volume panel as a plain window.
 *     The stock window is made fully transparent + untouchable, and our pill is shown instead.
 * Every new class/window name is logged once ("iOSVolume: ...") for diagnosis.
 */
class MainHook : IXposedHookLoadPackage {

    private val seen = HashSet<String>()

    private fun logOnce(msg: String) {
        val fresh = synchronized(seen) { seen.add(msg) }
        if (fresh) XposedBridge.log("iOSVolume: $msg")
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "com.android.systemui") return
        hookDialog()
        hookAddView()
        // main SystemUI process only: start the injector early so the settings screen works right away
        if (lpparam.processName == "com.android.systemui") hookAppCreate()
    }

    private fun hookAppCreate() {
        try {
            XposedHelpers.findAndHookMethod(
                Application::class.java, "onCreate",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        try {
                            val ctx = param.thisObject as? Context ?: return
                            if (!VolumeInjector.ready) VolumeInjector.init(ctx)
                        } catch (t: Throwable) {
                            XposedBridge.log("iOSVolume: early init error: $t")
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("iOSVolume: Application hook failed: $t")
        }
    }

    // ------------------------------------------------------------ Dialog.show
    private fun hookDialog() {
        try {
            XposedHelpers.findAndHookMethod(
                Dialog::class.java, "show",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        try {
                            val dialog = param.thisObject as? Dialog ?: return
                            val type = try { dialog.window?.attributes?.type } catch (e: Throwable) { null }
                            logOnce("dialog ${dialog.javaClass.name} type=$type")
                            if (!isVolumeName(dialog.javaClass.name)) return   // class name only: window type 2020 is also used by the power menu
                            logOnce("volume dialog matched: ${dialog.javaClass.name}")
                            val app: Context = dialog.context.applicationContext ?: dialog.context
                            if (!VolumeInjector.ready) VolumeInjector.init(app)
                            if (!VolumeInjector.ready) {
                                logOnce("init NOT ready -> stock dialog kept")
                                return
                            }
                            param.setResult(null)                  // cancel the stock dialog
                            logOnce("stock dialog cancelled, pill takes over")
                            VolumeInjector.requestShow()
                        } catch (t: Throwable) {
                            XposedBridge.log("iOSVolume: dialog hook error: $t")
                        }
                    }
                }
            )
            XposedBridge.log("iOSVolume: hooked Dialog.show in SystemUI")
        } catch (t: Throwable) {
            XposedBridge.log("iOSVolume: Dialog hook failed: $t")
        }
    }

    // ------------------------------------------------------- WindowManager.addView
    private fun hookAddView() {
        try {
            val wmImpl = Class.forName("android.view.WindowManagerImpl")
            XposedHelpers.findAndHookMethod(
                wmImpl, "addView", View::class.java, ViewGroup.LayoutParams::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        try {
                            val view = param.args[0] as? View ?: return
                            val lp = param.args[1] as? WindowManager.LayoutParams ?: return
                            if (view is PillView) return           // our own window
                            val name = view.javaClass.name
                            val title = lp.title?.toString() ?: ""
                            logOnce("addView $name type=${lp.type} title=$title")
                            if (!isVolumeWindow(view, lp)) return

                            val app: Context = view.context.applicationContext ?: view.context
                            if (!VolumeInjector.ready) {
                                if (Looper.myLooper() != Looper.getMainLooper()) {
                                    Handler(Looper.getMainLooper()).post { VolumeInjector.init(app) }
                                    return
                                }
                                VolumeInjector.init(app)
                            }
                            if (!VolumeInjector.ready) {
                                logOnce("addView: init NOT ready -> stock window kept")
                                return
                            }

                            hideStock(lp)   // fully transparent + no touches
                            logOnce("hid stock volume window $name")
                            VolumeInjector.requestShow()
                        } catch (t: Throwable) {
                            XposedBridge.log("iOSVolume: addView hook error: $t")
                        }
                    }
                }
            )
            XposedHelpers.findAndHookMethod(
                wmImpl, "updateViewLayout", View::class.java, ViewGroup.LayoutParams::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        try {
                            if (!VolumeInjector.ready) return
                            val view = param.args[0] as? View ?: return
                            val lp = param.args[1] as? WindowManager.LayoutParams ?: return
                            if (isVolumeWindow(view, lp)) hideStock(lp)
                        } catch (t: Throwable) {
                            XposedBridge.log("iOSVolume: updateViewLayout hook error: $t")
                        }
                    }
                }
            )
            XposedBridge.log("iOSVolume: hooked addView in SystemUI")
        } catch (t: Throwable) {
            XposedBridge.log("iOSVolume: addView hook failed: $t")
        }
    }

    private fun isVolumeWindow(view: View, lp: WindowManager.LayoutParams): Boolean {
        if (view is PillView) return false
        val title = lp.title?.toString() ?: ""
        return isVolumeName(view.javaClass.name) || title.contains("Volume", true)
    }

    private fun hideStock(lp: WindowManager.LayoutParams) {
        lp.alpha = 0f
        lp.flags = lp.flags or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
    }

    private fun isVolumeName(name: String): Boolean {
        if (name.startsWith("app.iosvolume")) return false
        if (name.contains("Warning", ignoreCase = true)) return false
        return name.contains("Volume", ignoreCase = true)
    }
}
