package app.iosvolume.module

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView

/**
 * Settings screen of the module. It only sends broadcasts to SystemUI, where
 * VolumeInjector applies (and stores) the values. Nothing here touches SystemUI directly.
 */
class SettingsActivity : Activity() {

    private val actionApply = "app.iosvolume.module.APPLY"
    private val actionPreview = "app.iosvolume.module.PREVIEW"
    private val hideMs = intArrayOf(1500, 2400, 4000)

    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.layoutDirection = View.LAYOUT_DIRECTION_RTL
        root.setPadding(dp(22), dp(28), dp(22), dp(28))

        val title = TextView(this)
        title.text = "iOS Volume"
        title.textSize = 26f
        title.typeface = Typeface.DEFAULT_BOLD
        root.addView(title)

        val sub = TextView(this)
        sub.text = "تغییرها همان لحظه روی گوشی اعمال می‌شود"
        sub.textSize = 14f
        sub.alpha = 0.7f
        sub.setPadding(0, dp(4), 0, dp(8))
        root.addView(sub)

        addGroup(root, "تم", listOf("آیفون کلاسیک", "شیشه‌ای (Liquid Glass)", "تیره با پر آبی"), "style", 0)
        addGroup(root, "سمت صفحه", listOf("چپ", "راست"), "side", 0)
        addGroup(root, "مدت نمایش", listOf("کوتاه (۱٫۵ ثانیه)", "معمولی (۲٫۴ ثانیه)", "بلند (۴ ثانیه)"), "hide", 1)
        addGroup(root, "اندازه", listOf("کوچک", "معمولی", "بزرگ"), "size", 1)
        addGroup(root, "رنگ پر (فقط تم تیره)", listOf("آبی", "سبز", "نارنجی", "صورتی", "بنفش", "سفید"), "accent", 0)
        addGroup(root, "لرزش موقع کشیدن", listOf("روشن", "خاموش"), "haptic", 0)
        addGroup(root, "آیکون بر اساس نوع صدا (زنگ، آلارم)", listOf("روشن", "خاموش"), "icons", 0)

        val preview = Button(this)
        preview.text = "پیش‌نمایش"
        preview.setOnClickListener {
            push()
            sendBroadcast(Intent(actionPreview).setPackage("com.android.systemui"))
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(24)
        root.addView(preview, lp)

        val note = TextView(this)
        note.text = "اگر پیش‌نمایش چیزی نشان نداد، ماژول را در LSPosed فعال کنید (SystemUI در محدوده باشد) و گوشی را یک بار ریبوت کنید."
        note.textSize = 12f
        note.alpha = 0.6f
        note.setPadding(0, dp(16), 0, 0)
        root.addView(note)

        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
    }

    private fun addGroup(root: LinearLayout, label: String, options: List<String>, key: String, def: Int) {
        val d = resources.displayMetrics.density

        val t = TextView(this)
        t.text = label
        t.textSize = 16f
        t.typeface = Typeface.DEFAULT_BOLD
        t.setPadding(0, (18 * d).toInt(), 0, (4 * d).toInt())
        root.addView(t)

        val group = RadioGroup(this)
        group.orientation = RadioGroup.VERTICAL
        val saved = prefs.getInt(key, def).coerceIn(0, options.size - 1)
        val buttons = ArrayList<RadioButton>()
        for (i in options.indices) {
            val rb = RadioButton(this)
            rb.id = View.generateViewId()
            rb.text = options[i]
            rb.textSize = 15f
            group.addView(rb)
            buttons.add(rb)
        }
        buttons[saved].isChecked = true
        group.setOnCheckedChangeListener { g, checkedId ->
            val idx = buttons.indexOfFirst { it.id == checkedId }
            if (idx >= 0) {
                prefs.edit().putInt(key, idx).apply()
                push()
            }
        }
        root.addView(group)
    }

    /** Sends the current choices to SystemUI. */
    private fun push() {
        val i = Intent(actionApply).setPackage("com.android.systemui")
        i.putExtra("style", prefs.getInt("style", 0))
        i.putExtra("side", prefs.getInt("side", 0))
        i.putExtra("hide", hideMs[prefs.getInt("hide", 1).coerceIn(0, hideMs.size - 1)])
        i.putExtra("size", prefs.getInt("size", 1))
        i.putExtra("accent", prefs.getInt("accent", 0))
        i.putExtra("haptic", if (prefs.getInt("haptic", 0) == 0) 1 else 0)
        i.putExtra("icons", if (prefs.getInt("icons", 0) == 0) 1 else 0)
        sendBroadcast(i)
    }
}
