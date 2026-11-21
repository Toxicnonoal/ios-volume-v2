package app.iosvolume.module

import android.app.Application
import android.app.Dialog
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Entry point. Runs inside com.android.systemui only.
 * Intercepts the stock volume dialog (Dialog.show) and shows the iOS pill instead.
 * If anything fails, the stock panel is left alone.
 */
class MainHook : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "com.android.systemui") return
        try {
            XposedHelpers.findAndHookMethod(
                Dialog::class.java, "show",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        try {
                            val dialog = param.thisObject as? Dialog ?: return
                            if (!isVolumeDialog(dialog)) return
                            val app = dialog.context.applicationContext as? Application ?: return
                            if (!VolumeInjector.ready) VolumeInjector.init(app)
                            if (!VolumeInjector.ready) return      // init failed -> keep stock panel
                            param.setResult(null)                  // cancel the stock dialog
                            VolumeInjector.requestShow()
                        } catch (t: Throwable) {
                            XposedBridge.log("iOSVolume: hook error: $t")
                        }
                    }
                }
            )
            XposedBridge.log("iOSVolume: hooked Dialog.show in SystemUI")
        } catch (t: Throwable) {
            XposedBridge.log("iOSVolume: hook failed: $t")
        }
    }

    private fun isVolumeDialog(d: Dialog): Boolean {
        val name = d.javaClass.name
        if (name.contains("Warning", ignoreCase = true)) return false
        if (name.contains("Volume", ignoreCase = true)) return true
        // fallback for ROMs with a differently named class: window type TYPE_VOLUME_OVERLAY
        return try {
            d.window?.attributes?.type == 2020
        } catch (e: Throwable) {
            false
        }
    }
}
