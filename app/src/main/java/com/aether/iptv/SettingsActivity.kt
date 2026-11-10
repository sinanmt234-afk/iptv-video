package com.aether.iptv

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/** Add / remove extra IPTV streams. They appear top-left on the main page as TV 2, TV 3 ... */
class SettingsActivity : Activity() {
    private val gold = c(0xFFD4AF37)
    private val dim = c(0x99FFFFFF)
    private lateinit var prefs: SharedPreferences
    private lateinit var listBox: LinearLayout
    private lateinit var input: EditText
    private lateinit var msg: TextView

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun tv(size: Float, color: Int = Color.WHITE, bold: Boolean = false) = TextView(this).apply {
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
    }

    private fun box(top: Int, bot: Int, stroke: Int, r: Int = 14, sw: Int = 1) =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bot)).apply {
            cornerRadius = dp(r).toFloat(); setStroke(dp(sw), stroke)
        }

    private fun focusBg() = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), box(c(0x66D4AF37), c(0x26D4AF37), gold, 14, 2))
        addState(intArrayOf(), box(c(0x30FFFFFF), c(0x10FFFFFF), c(0x30FFFFFF)))
    }

    private fun button(label: String, onClick: () -> Unit) = tv(15f, Color.WHITE, true).apply {
        text = label
        gravity = Gravity.CENTER
        setPadding(dp(24), dp(11), dp(24), dp(11))
        background = focusBg()
        isFocusable = true
        isClickable = true
        setOnClickListener { onClick() }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        prefs = getSharedPreferences("aether", MODE_PRIVATE)

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(tv(26f, gold, true).apply { text = "SETTINGS"; letterSpacing = 0.2f })
        col.addView(tv(13f, dim).apply {
            text = "Add IPTV streams (M3U playlist link or a direct stream link). Added streams show at the top-left of the main page as TV 2, TV 3 …"
            maxLines = 3
            setPadding(0, dp(6), 0, dp(18))
        })

        input = EditText(this).apply {
            hint = "http://… playlist or stream link"
            setHintTextColor(dim)
            setTextColor(Color.WHITE)
            textSize = 16f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_DONE
            setPadding(dp(16), 0, dp(16), 0)
            background = focusBg()
            isFocusable = true
            isFocusableInTouchMode = true
            setOnEditorActionListener { _, _, _ -> addStream(); true }
        }
        col.addView(input, LinearLayout.LayoutParams(MATCH_PARENT, dp(48)).apply { bottomMargin = dp(12) })

        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btns.addView(button("＋  Add stream") { addStream() })
        btns.addView(button("Back") { finish() }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = dp(12) })
        col.addView(btns)

        msg = tv(14f, gold).apply { setPadding(0, dp(12), 0, dp(12)); maxLines = 2 }
        col.addView(msg)

        col.addView(tv(15f, Color.WHITE, true).apply { text = "Your streams"; setPadding(0, dp(6), 0, dp(8)) })
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(listBox)

        val width = minOf(dp(760), resources.displayMetrics.widthPixels - dp(32))
        val holder = FrameLayout(this).apply { setPadding(0, dp(32), 0, dp(32)) }
        holder.addView(col, FrameLayout.LayoutParams(width, WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        val scroll = ScrollView(this).apply {
            setBackgroundColor(c(0xFF050810))
            isFillViewport = true
            addView(holder, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        setContentView(scroll)
        refreshList()
        input.requestFocus()
    }

    private fun say(t: String) { msg.text = t }

    private fun row(name: String, url: String, onRemove: (() -> Unit)?): LinearLayout {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(10), dp(8))
            background = box(c(0x30FFFFFF), c(0x10FFFFFF), c(0x30FFFFFF), 16)
        }
        r.addView(tv(16f, gold, true).apply { text = name }, LinearLayout.LayoutParams(dp(70), WRAP_CONTENT))
        r.addView(tv(14f).apply { text = url; ellipsize = TextUtils.TruncateAt.MIDDLE }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        if (onRemove != null) {
            r.addView(button("Remove") { onRemove() }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = dp(10) })
        }
        r.layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(8) }
        return r
    }

    private fun refreshList() {
        listBox.removeAllViews()
        listBox.addView(row(Sources.name(0), "Default playlist", null))
        val extras = Sources.extras(prefs)
        extras.forEachIndexed { i, u ->
            listBox.addView(row(Sources.name(i + 1), u) { removeStream(i) })
        }
        if (extras.isEmpty()) listBox.addView(tv(13f, dim).apply { text = "No extra streams yet." })
    }

    private fun addStream() {
        val url = Sources.normalize(input.text.toString())
        if (url == null) { say("Enter a valid http:// or https:// link"); return }
        val extras = Sources.extras(prefs)
        if (url == Config.PLAYLIST || url in extras) { say("This stream is already added"); return }
        extras.add(url)
        Sources.save(prefs, extras)
        input.setText("")
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken, 0)
        refreshList()
        say("Added as ${Sources.name(extras.size)}")
        setResult(RESULT_OK)
    }

    private fun removeStream(i: Int) {
        val extras = Sources.extras(prefs)
        if (i !in extras.indices) return
        val url = extras.removeAt(i)
        Sources.save(prefs, extras)
        try { File(filesDir, Sources.cacheName(url)).delete() } catch (_: Exception) {}
        refreshList()
        say("Removed. Remaining streams are renumbered.")
        setResult(RESULT_OK)
        input.requestFocus()
    }
}
