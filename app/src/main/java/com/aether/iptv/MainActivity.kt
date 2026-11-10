package com.aether.iptv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.media.AudioManager
import android.media.audiofx.LoudnessEnhancer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val FAV = "★ Favorites"

class MainActivity : Activity() {
    private val gold = c(0xFFD4AF37)
    private val white = Color.WHITE
    private val dim = c(0x99FFFFFF)
    private val defaultUA = "Mozilla/5.0 (Linux; Android 10; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
    private val h = Handler(Looper.getMainLooper())
    private val clock = SimpleDateFormat("HH:mm", Locale.getDefault())

    private lateinit var player: ExoPlayer
    private val http = DefaultHttpDataSource.Factory()
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(10000)
        .setReadTimeoutMs(15000)
    private lateinit var prefs: SharedPreferences

    private var all: List<Channel> = emptyList()
    private var groups: List<String> = listOf("All")
    private var counts: Map<String, Int> = emptyMap()
    private var groupSel = 0
    private var shown: List<Channel> = emptyList()
    private var cur: Channel? = null
    private var menuVisible = false
    private var typed = ""
    private var favs: MutableSet<String> = HashSet()
    private var query = ""
    private var boost = 100            // volume boost in % (100..200), like VLC
    private var enhancer: LoudnessEnhancer? = null
    private var volConsumed = false
    private var settingText = false
    private var clearFlash = Runnable {}
    private var sources: List<String> = emptyList()   // [0] = built-in playlist (TV 1), then user-added
    private var srcSel = 0
    private var loadGen = 0
    private var splash: HelloView? = null
    private lateinit var srcRow: LinearLayout
    private val searchRun = Runnable { refreshList() }

    private lateinit var menu: FrameLayout
    private lateinit var banner: LinearLayout
    private lateinit var bLogo: ImageView
    private lateinit var bNum: TextView
    private lateinit var bName: TextView
    private lateinit var bNow: TextView
    private lateinit var bNext: TextView
    private lateinit var bar: ProgressBar
    private lateinit var statusTv: TextView
    private lateinit var numTv: TextView
    private lateinit var countTv: TextView
    private lateinit var searchEt: EditText
    private lateinit var boostTv: TextView

    // player panel (controls + audio / subtitle track selector)
    private lateinit var panel: FrameLayout
    private lateinit var pTitle: TextView
    private lateinit var pRew: TextView
    private lateinit var pPlay: TextView
    private lateinit var pFwd: TextView
    private lateinit var pLive: TextView
    private lateinit var pBar: ProgressBar
    private lateinit var pTime: TextView
    private lateinit var pAudio: LinearLayout
    private lateinit var pSubs: LinearLayout
    private var panelVisible = false
    private val tickRun = object : Runnable {
        override fun run() {
            updateProgress()
            if (panelVisible) h.postDelayed(this, 500)
        }
    }
    private lateinit var groupsRv: RecyclerView
    private lateinit var chRv: RecyclerView
    private val groupAdapter = GroupAdapter()
    private val chAdapter = ChAdapter()

    private var groupRun = Runnable {}
    private val playRun = Runnable { cur?.let { play(it) } }
    private val hideBannerRun = Runnable {
        banner.animate().alpha(0f).setDuration(250).withEndAction { banner.visibility = View.GONE }.start()
    }
    private val jumpRun = Runnable {
        val n = typed.toIntOrNull()
        typed = ""
        numTv.visibility = View.GONE
        if (n != null) all.getOrNull(n - 1)?.let { tune(it, true) }
    }

    // ---------- helpers ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
    private fun lp(w: Int, hh: Int, wt: Float = 0f) = LinearLayout.LayoutParams(w, hh, wt)

    private fun tv(size: Float, color: Int = white, bold: Boolean = false) = TextView(this).apply {
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
    }

    private fun glass(radius: Int, top: Int, bot: Int, stroke: Int) =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bot)).apply {
            cornerRadius = dp(radius).toFloat()
            setStroke(dp(1), stroke)
        }

    private fun rowBg() = StateListDrawable().apply {
        addState(
            intArrayOf(android.R.attr.state_focused),
            GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(c(0x66D4AF37), c(0x26D4AF37))).apply {
                cornerRadius = dp(14).toFloat(); setStroke(dp(2), gold)
            }
        )
        addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
    }

    private fun fieldBg() = StateListDrawable().apply {
        addState(
            intArrayOf(android.R.attr.state_focused),
            GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(c(0x66D4AF37), c(0x26D4AF37))).apply {
                cornerRadius = dp(14).toFloat(); setStroke(dp(2), gold)
            }
        )
        addState(intArrayOf(), glass(14, c(0x30FFFFFF), c(0x10FFFFFF), c(0x30FFFFFF)))
    }

    // ---------- lifecycle ----------
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        prefs = getSharedPreferences("aether", MODE_PRIVATE)
        favs = HashSet(prefs.getStringSet("favs", emptySet()) ?: emptySet())
        boost = prefs.getInt("boost", 100).coerceIn(100, 200)
        sources = Sources.all(prefs)
        srcSel = prefs.getInt("src", 0).coerceIn(0, sources.size - 1)
        buildPlayer()
        buildUi()
        loadData()
    }

    override fun onStart() {
        super.onStart()
        cur?.let { if (player.playbackState == Player.STATE_IDLE) play(it) }
    }

    override fun onStop() {
        super.onStop()
        player.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        h.removeCallbacksAndMessages(null)
        try { enhancer?.release() } catch (_: Exception) {}
        enhancer = null
        player.release()
    }

    // ---------- player ----------
    private fun buildPlayer() {
        val lc = DefaultLoadControl.Builder().setBufferDurationsMs(12000, 30000, 1500, 3000).build()
        val rf = DefaultRenderersFactory(this).setEnableDecoderFallback(true)
        player = ExoPlayer.Builder(this, rf)
            .setLoadControl(lc)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .build()
        // remembered audio / subtitle choices
        val tsp = player.trackSelectionParameters.buildUpon()
        prefs.getString("aLang", null)?.let { tsp.setPreferredAudioLanguage(it) }
        prefs.getString("sLang", null)?.let { tsp.setPreferredTextLanguage(it) }
        if (prefs.getBoolean("subOff", false)) tsp.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        player.trackSelectionParameters = tsp.build()
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> status("Loading…")
                    Player.STATE_READY -> status(null)
                }
            }

            override fun onTracksChanged(tracks: Tracks) { refreshTracks() }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { updatePlayBtn() }

            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                try { enhancer?.release() } catch (_: Exception) {}
                enhancer = null
                applyBoost()
            }

            override fun onPlayerError(e: PlaybackException) {
                if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    player.seekToDefaultPosition(); player.prepare()
                } else {
                    status("Channel unavailable  •  press ▲ ▼ to switch")
                    showBanner()
                }
            }
        })
    }

    private fun play(ch: Channel) {
        // forget per-stream track overrides (saved language preferences still apply)
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().clearOverrides().build()
        http.setUserAgent(ch.ua ?: defaultUA)
        http.setDefaultRequestProperties(ch.referrer?.let { mapOf("Referer" to it) } ?: emptyMap())
        player.setMediaItem(MediaItem.fromUri(ch.url))
        player.prepare()
        player.playWhenReady = true
        applyBoost()
    }

    private fun tune(ch: Channel, instant: Boolean = false) {
        cur = ch
        prefs.edit().putString("last", ch.url).apply()
        status(null)
        showBanner()
        h.removeCallbacks(playRun)
        if (instant) play(ch) else h.postDelayed(playRun, 350) // debounce fast zapping
    }

    private fun step(dir: Int) {
        if (shown.isEmpty()) return
        val i = cur?.let { shown.indexOf(it) } ?: -1
        val n = if (i < 0) (if (dir > 0) 0 else shown.size - 1) else (i + dir + shown.size) % shown.size
        tune(shown[n])
    }

    // ---------- data ----------
    private fun loadData() {
        status("Loading channels…")
        val gen = ++loadGen
        val url = sources[srcSel]
        val name = Sources.name(srcSel)
        Thread {
            val pf = File(filesDir, Sources.cacheName(url))
            val kind = Net.fetchSource(url, pf, Config.REFRESH_MS)
            if (gen != loadGen) return@Thread
            if (kind == null || (kind == false && url == Config.PLAYLIST)) {
                runOnUiThread {
                    if (gen == loadGen) { hideSplash(); status("Can't load $name. Check internet and try again.") }
                }
                return@Thread
            }
            if (kind == false) {
                // a single direct stream: show it as one channel named TV n
                val one = listOf(Channel(1, name, url, "Live", "", "", null, null))
                runOnUiThread { if (gen == loadGen) setChannels(one) }
                return@Thread
            }
            val list = try { M3U.parse(pf) } catch (e: Exception) { emptyList() }
            runOnUiThread { if (gen == loadGen) setChannels(list) }
            if (Config.EPG_URL.isNotEmpty() && list.isNotEmpty()) {
                try {
                    val ef = File(filesDir, "guide.xml")
                    Net.fetch(Config.EPG_URL, ef, Config.REFRESH_MS)
                    if (ef.exists() && gen == loadGen) {
                        val wanted = list.map { Epg.key(it.tvgId) }.filter { it.isNotEmpty() }.toSet()
                        Epg.load(ef, wanted)
                        runOnUiThread { chAdapter.notifyDataSetChanged(); if (banner.visibility == View.VISIBLE) refreshBanner() }
                    }
                } catch (_: Exception) {}
            }
        }.start()
    }

    // ---------- TV 1 / TV 2 / TV 3 ... sources (top-left) ----------
    private fun hideSplash() { splash?.dismiss { splash = null } }

    private fun styleChips() {
        for (i in 0 until srcRow.childCount) {
            val v = srcRow.getChildAt(i) as? TextView ?: continue
            val idx = v.tag as? Int ?: continue
            v.setTextColor(if (idx == srcSel) gold else white)
        }
    }

    private fun rebuildChips() {
        srcRow.removeAllViews()
        sources.forEachIndexed { i, _ ->
            srcRow.addView(chip(Sources.name(i)).apply {
                tag = i
                setOnClickListener { selectSource(i) }
            }, lp(WRAP_CONTENT, dp(38)).apply { rightMargin = dp(10) })
        }
        srcRow.addView(chip("⚙ Settings").apply {
            setOnClickListener { startActivityForResult(Intent(this@MainActivity, SettingsActivity::class.java), 7) }
        }, lp(WRAP_CONTENT, dp(38)))
        styleChips()
    }

    private fun chip(label: String) = tv(14f, white, true).apply {
        text = label
        gravity = Gravity.CENTER
        setPadding(dp(18), 0, dp(18), 0)
        background = fieldBg()
        isFocusable = true
        isClickable = true
    }

    private fun selectSource(i: Int) {
        if (i == srcSel || i !in sources.indices) return
        srcSel = i
        prefs.edit().putInt("src", i).apply()
        styleChips()
        clearSearch()
        player.stop()
        cur = null
        all = emptyList(); shown = emptyList()
        chAdapter.items = shown
        chAdapter.notifyDataSetChanged()
        loadData()
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req != 7) return
        val active = sources.getOrNull(srcSel)
        sources = Sources.all(prefs)
        val idx = sources.indexOf(active)
        if (idx >= 0) {
            srcSel = idx
            prefs.edit().putInt("src", idx).apply()
            rebuildChips()
        } else {                      // the active stream was removed -> back to TV 1
            srcSel = -1
            rebuildChips()
            selectSource(0)
        }
        srcRow.getChildAt(srcRow.childCount - 1)?.requestFocus()
    }

    private fun setChannels(list: List<Channel>) {
        hideSplash()
        all = list
        counts = list.groupingBy { it.group }.eachCount()
        groups = listOf("All", FAV) + counts.keys.sorted()
        groupAdapter.notifyDataSetChanged()
        applyGroup(0)
        val last = prefs.getString("last", null)
        val start = all.firstOrNull { it.url == last } ?: all.firstOrNull()
        if (start == null) { status("Playlist is empty"); return }
        tune(start, true)
    }

    private fun applyGroup(i: Int) {
        val old = groupSel
        groupSel = i
        refreshList()
        groupAdapter.notifyItemChanged(old)
        groupAdapter.notifyItemChanged(i)
    }

    private fun refreshList() {
        val q = query.trim()
        shown = when {
            q.isNotEmpty() -> all.filter { it.name.contains(q, ignoreCase = true) }
            groupSel == 0 -> all
            groupSel == 1 -> all.filter { it.url in favs }
            else -> all.filter { it.group == groups[groupSel] }
        }
        chAdapter.items = shown
        chAdapter.notifyDataSetChanged()
        updateCount()
    }

    private fun updateCount() {
        val q = query.trim()
        countTv.text = when {
            q.isNotEmpty() -> "Search “$q”  •  ${shown.size} found"
            groupSel == 1 && shown.isEmpty() -> "$FAV  •  empty  (hold OK on a channel to add)"
            else -> "${groups[groupSel]}  •  ${shown.size} channels"
        }
    }

    private fun favCount() = all.count { it.url in favs }

    private fun clearSearch() {
        if (query.isEmpty() && searchEt.text.isEmpty()) return
        h.removeCallbacks(searchRun)
        query = ""
        settingText = true
        searchEt.setText("")
        settingText = false
    }

    private fun toggleFav(ch: Channel) {
        val added = favs.add(ch.url)
        if (!added) favs.remove(ch.url)
        prefs.edit().putStringSet("favs", HashSet(favs)).apply()
        flash(if (added) "★  Added to favourites" else "Removed from favourites")
        groupAdapter.notifyItemChanged(1)
        val i = chAdapter.items.indexOf(ch)
        if (!added && groupSel == 1 && query.isEmpty()) {
            shown = shown.filter { it.url != ch.url }
            chAdapter.items = shown
            if (menuVisible && i >= 0) {
                chAdapter.notifyItemRemoved(i)
                updateCount()
                chRv.post {
                    chRv.findViewHolderForAdapterPosition(minOf(i, shown.size - 1))?.itemView?.requestFocus()
                }
            } else {
                chAdapter.notifyDataSetChanged()
                updateCount()
            }
        } else if (i >= 0) {
            chAdapter.notifyItemChanged(i)
        }
    }

    private fun flash(msg: String) {
        status(msg)
        h.removeCallbacks(clearFlash)
        clearFlash = Runnable { if (statusTv.text == msg) status(null) }
        h.postDelayed(clearFlash, 1500)
    }

    // ---------- volume boost (up to 200 %) ----------
    private fun applyBoost() {
        try {
            val sid = player.audioSessionId
            if (sid == C.AUDIO_SESSION_ID_UNSET) return
            if (boost > 100 && enhancer == null) enhancer = LoudnessEnhancer(sid)
            val e = enhancer ?: return
            // 200 % amplitude = +6 dB = 600 millibel
            e.setTargetGain(if (boost <= 100) 0 else (2000 * Math.log10(boost / 100.0)).toInt())
            e.setEnabled(boost > 100)
        } catch (_: Exception) {}
    }

    private fun updateBoostLabel() { boostTv.text = "Volume boost  $boost%" }

    private fun setBoost(v: Int) {
        boost = v.coerceIn(100, 200)
        prefs.edit().putInt("boost", boost).apply()
        applyBoost()
        updateBoostLabel()
        flash("Volume boost  $boost%" + if (boost == 200) "  (max)" else "")
    }

    /** VOL+ at the system maximum keeps going up to 200 %; VOL- first brings the boost back to 100 %. */
    private fun volumeKey(up: Boolean): Boolean {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val atMax = am.isVolumeFixed ||
            am.getStreamVolume(AudioManager.STREAM_MUSIC) >= am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return if (up) {
            if (!atMax) false else { setBoost(boost + 10); true }
        } else {
            if (boost <= 100) false else { setBoost(boost - 10); true }
        }
    }

    private fun hideIme() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(searchEt.windowToken, 0)
    }

    // ---------- UI ----------
    private fun buildUi() {
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        val pv = PlayerView(this)
        pv.useController = false
        pv.isFocusable = false
        pv.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        pv.setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
        pv.player = player
        pv.subtitleView?.apply { setUserDefaultStyle(); setUserDefaultTextSize() }
        root.addView(pv, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        buildMenu()
        root.addView(menu, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        buildBanner()
        root.addView(banner, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM).apply {
            setMargins(dp(56), 0, dp(56), dp(40))
        })

        buildPanel()
        root.addView(panel, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        statusTv = tv(16f, white, true).apply {
            setPadding(dp(22), dp(10), dp(22), dp(10))
            background = glass(30, c(0xCC0B1020), c(0xAA101830), c(0x55D4AF37))
            visibility = View.GONE
        }
        root.addView(statusTv, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = dp(32)
        })

        numTv = tv(48f, gold, true).apply {
            setPadding(dp(26), dp(12), dp(26), dp(12))
            background = glass(24, c(0xCC0B1020), c(0xAA101830), c(0x66D4AF37))
            visibility = View.GONE
        }
        root.addView(numTv, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            setMargins(0, dp(32), dp(48), 0)
        })
        splash = HelloView(this)
        root.addView(splash, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        setContentView(root)
    }

    private fun buildMenu() {
        menu = FrameLayout(this).apply {
            visibility = View.GONE
            alpha = 0f
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(c(0xF2050810), c(0xD0050810), c(0x00050810))
            )
        }
        // warm gold "light" in the top-left corner
        val glow = View(this).apply {
            background = GradientDrawable().apply {
                gradientType = GradientDrawable.RADIAL_GRADIENT
                colors = intArrayOf(c(0x55D4AF37), c(0x00D4AF37))
                gradientRadius = dp(720).toFloat()
                setGradientCenter(0.1f, 0f)
            }
        }
        menu.addView(glow, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(32), dp(24), dp(32))
        }
        srcRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        col.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(srcRow)
        }, lp(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(12) })
        rebuildChips()
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(tv(26f, gold, true).apply { text = "PLAY_IPTV"; letterSpacing = 0.2f })
        countTv = tv(13f, dim).apply { setPadding(0, dp(4), 0, 0) }
        titles.addView(countTv)
        head.addView(titles, lp(0, WRAP_CONTENT, 1f))
        boostTv = tv(14f, white, true).apply {
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = fieldBg()
            isFocusable = true
            isClickable = true
            setOnClickListener { setBoost(when { boost < 150 -> 150; boost < 200 -> 200; else -> 100 }) }
        }
        updateBoostLabel()
        val playerTv = tv(14f, white, true).apply {
            text = "Audio & Subtitles"
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = fieldBg()
            isFocusable = true
            isClickable = true
            setOnClickListener { openPanel(0) }
        }
        head.addView(playerTv, lp(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = dp(12) })
        head.addView(boostTv, lp(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = dp(18) })
        head.addView(TextClock(this).apply {
            format12Hour = "hh:mm a"; format24Hour = "HH:mm"; textSize = 20f; setTextColor(white)
        })
        col.addView(head, lp(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(14) })

        searchEt = EditText(this).apply {
            hint = "Search channels…"
            setHintTextColor(dim)
            setTextColor(white)
            textSize = 16f
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(dp(16), 0, dp(16), 0)
            background = fieldBg()
            isFocusable = true
            isFocusableInTouchMode = true
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (settingText) return
                    query = s?.toString() ?: ""
                    h.removeCallbacks(searchRun)
                    h.postDelayed(searchRun, 250)
                }
            })
            setOnEditorActionListener { _, _, _ ->
                hideIme()
                chRv.requestFocus()
                true
            }
        }
        col.addView(searchEt, lp(MATCH_PARENT, dp(46)).apply { bottomMargin = dp(12) })

        val lists = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        groupsRv = rv(groupAdapter)
        chRv = rv(chAdapter)
        lists.addView(groupsRv, lp(dp(230), MATCH_PARENT).apply { rightMargin = dp(16) })
        lists.addView(chRv, lp(0, MATCH_PARENT, 1f))
        col.addView(lists, lp(MATCH_PARENT, 0, 1f))
        col.addView(tv(12f, dim).apply {
            text = "Hold OK on a channel = ★ favourite    •    ▲ from the list = search    •    VOL+ at max = boost up to 200%    •    Green / INFO = player"
        }, lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })

        menu.addView(col, FrameLayout.LayoutParams(dp(960), MATCH_PARENT))
    }

    private fun rv(a: RecyclerView.Adapter<*>) = RecyclerView(this).apply {
        layoutManager = LinearLayoutManager(this@MainActivity)
        adapter = a
        itemAnimator = null
        setHasFixedSize(true)
        clipToPadding = false
        setPadding(dp(8), dp(8), dp(8), dp(8))
        background = glass(22, c(0x30FFFFFF), c(0x10FFFFFF), c(0x30FFFFFF))
    }

    private fun buildBanner() {
        banner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(20), dp(28), dp(20))
            background = glass(28, c(0xD00B1020), c(0xA0101830), c(0x66D4AF37))
            visibility = View.GONE
            alpha = 0f
        }
        bLogo = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = glass(18, c(0x28FFFFFF), c(0x10FFFFFF), c(0x22FFFFFF))
        }
        banner.addView(bLogo, lp(dp(92), dp(92)).apply { rightMargin = dp(22) })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        bNum = tv(26f, gold, true)
        bName = tv(26f, white, true).apply { setPadding(dp(16), 0, 0, 0) }
        line.addView(bNum); line.addView(bName, lp(0, WRAP_CONTENT, 1f))
        col.addView(line)
        bNow = tv(16f, white).apply { setPadding(0, dp(8), 0, 0) }
        bNext = tv(14f, dim).apply { setPadding(0, dp(4), 0, 0) }
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(gold)
            progressBackgroundTintList = ColorStateList.valueOf(c(0x33FFFFFF))
        }
        col.addView(bNow); col.addView(bNext)
        col.addView(bar, lp(MATCH_PARENT, dp(4)).apply { topMargin = dp(10) })
        banner.addView(col, lp(0, WRAP_CONTENT, 1f))
    }

    private fun status(msg: String?) {
        if (msg == null) statusTv.visibility = View.GONE
        else { statusTv.text = msg; statusTv.visibility = View.VISIBLE }
    }

    private fun fmt(t: Long) = clock.format(Date(t))

    private fun refreshBanner() {
        val ch = cur ?: return
        Img.load(ch.logo, bLogo)
        bNum.text = ch.num.toString()
        bName.text = ch.name
        val n = Epg.now(ch.tvgId)
        if (n != null) {
            bNow.text = "NOW   ${n.title}    ${fmt(n.start)} – ${fmt(n.stop)}"
            val span = (n.stop - n.start).coerceAtLeast(1)
            bar.progress = (((System.currentTimeMillis() - n.start) * 100) / span).toInt().coerceIn(0, 100)
            bar.visibility = View.VISIBLE
            val x = Epg.next(ch.tvgId)
            bNext.text = if (x != null) "NEXT   ${x.title}    ${fmt(x.start)}" else ""
        } else {
            bNow.text = "${ch.group}  •  Live"
            bNext.text = ""
            bar.visibility = View.GONE
        }
    }

    private fun showBanner() {
        refreshBanner()
        banner.animate().cancel()
        banner.visibility = View.VISIBLE
        banner.animate().alpha(1f).setDuration(120).start()
        h.removeCallbacks(hideBannerRun)
        h.postDelayed(hideBannerRun, 5000)
    }

    private fun showMenu() {
        menuVisible = true
        h.removeCallbacks(hideBannerRun); hideBannerRun.run()
        menu.animate().cancel()
        menu.visibility = View.VISIBLE
        menu.animate().alpha(1f).setDuration(160).start()
        chAdapter.notifyDataSetChanged()
        val i = cur?.let { shown.indexOf(it) } ?: -1
        val pos = if (i < 0) 0 else i
        (chRv.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(pos, dp(120))
        chRv.postDelayed({
            (chRv.findViewHolderForAdapterPosition(pos)?.itemView ?: chRv).requestFocus()
        }, 120)
    }

    private fun hideMenu() {
        menuVisible = false
        h.removeCallbacks(groupRun)
        hideIme()
        menu.animate().cancel()
        menu.animate().alpha(0f).setDuration(140).withEndAction { menu.visibility = View.GONE }.start()
    }

    // ---------- remote control ----------
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val k = e.keyCode
        if (k == KeyEvent.KEYCODE_VOLUME_UP || k == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (e.action == KeyEvent.ACTION_DOWN) volConsumed = volumeKey(k == KeyEvent.KEYCODE_VOLUME_UP)
            return if (volConsumed) true else super.dispatchKeyEvent(e)
        }
        if (e.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(e)
        if (splash != null) return if (k == KeyEvent.KEYCODE_BACK) super.dispatchKeyEvent(e) else true
        if (mediaKey(k)) return true
        val pk = when (k) {
            KeyEvent.KEYCODE_CAPTIONS -> 2                      // subtitle key
            KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK -> 1             // audio-language key
            KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_PROG_GREEN,
            KeyEvent.KEYCODE_PROG_BLUE, KeyEvent.KEYCODE_SETTINGS -> 0
            else -> -1
        }
        if (pk >= 0) {
            if (panelVisible && pk == 0) hidePanel() else openPanel(pk)
            return true
        }
        if (panelVisible) {
            if (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_MENU) { hidePanel(); return true }
            return super.dispatchKeyEvent(e)
        }
        if (k == KeyEvent.KEYCODE_PROG_YELLOW || k == KeyEvent.KEYCODE_STAR || k == KeyEvent.KEYCODE_BOOKMARK) {
            if (favKey()) return true
        }
        if (menuVisible) {
            if (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_MENU) { hideMenu(); return true }
            return super.dispatchKeyEvent(e)
        }
        when (k) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { step(+1); return true }     // next
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { step(-1); return true } // previous
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MENU -> { showMenu(); return true }
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                if (typed.length < 4) typed += (k - KeyEvent.KEYCODE_0).toString()
                numTv.text = typed
                numTv.visibility = View.VISIBLE
                h.removeCallbacks(jumpRun)
                h.postDelayed(jumpRun, 1500)
                return true
            }
        }
        return super.dispatchKeyEvent(e)
    }

    /** Yellow / * / bookmark key: toggle favourite of the focused row (menu) or the playing channel. */
    private fun favKey(): Boolean {
        val f = currentFocus
        if (f is EditText) return false
        if (menuVisible) {
            val vh = (f?.let { chRv.findContainingViewHolder(it) } as? ChVH) ?: return false
            val ch = chAdapter.items.getOrNull(vh.bindingAdapterPosition) ?: return false
            toggleFav(ch)
            return true
        }
        val ch = cur ?: return false
        toggleFav(ch)
        return true
    }

    // ---------- player panel: controls + audio / subtitle selector ----------
    private fun buildPanel() {
        panel = FrameLayout(this).apply {
            visibility = View.GONE
            alpha = 0f
            background = GradientDrawable(
                GradientDrawable.Orientation.RIGHT_LEFT,
                intArrayOf(c(0xF2050810), c(0xD0050810), c(0x00050810))
            )
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(32), dp(48), dp(32))
        }
        col.addView(tv(26f, gold, true).apply { text = "PLAYER"; letterSpacing = 0.2f })
        pTitle = tv(14f, dim).apply { setPadding(0, dp(4), 0, dp(16)) }
        col.addView(pTitle)

        pRew = pbtn("« 10s") { seekBy(-10_000) }
        pPlay = pbtn("Pause") { togglePlay() }
        pFwd = pbtn("10s »") { seekBy(10_000) }
        pLive = pbtn("● Live") { player.seekToDefaultPosition(); player.play() }
        val ctl = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        for (b in listOf(pRew, pPlay, pFwd, pLive)) ctl.addView(b, lp(0, dp(42), 1f).apply { rightMargin = dp(8) })
        col.addView(ctl, lp(MATCH_PARENT, WRAP_CONTENT))

        pBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(gold)
            progressBackgroundTintList = ColorStateList.valueOf(c(0x33FFFFFF))
        }
        col.addView(pBar, lp(MATCH_PARENT, dp(4)).apply { topMargin = dp(16) })
        pTime = tv(13f, dim).apply { setPadding(0, dp(6), 0, dp(10)) }
        col.addView(pTime)

        pAudio = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pSubs = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        inner.addView(section("AUDIO TRACK"))
        inner.addView(pAudio)
        inner.addView(section("SUBTITLES"))
        inner.addView(pSubs)
        col.addView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(inner)
        }, lp(MATCH_PARENT, 0, 1f))
        col.addView(tv(12f, dim).apply { text = "OK = select    •    BACK = close" }, lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })

        panel.addView(col, FrameLayout.LayoutParams(dp(480), MATCH_PARENT, Gravity.END))
    }

    private fun pbtn(label: String, act: () -> Unit) = chip(label).apply { setOnClickListener { act() } }

    private fun section(label: String) = tv(13f, gold, true).apply {
        text = label
        letterSpacing = 0.15f
        setPadding(0, dp(14), 0, dp(6))
    }

    private fun optionRow(label: String, selected: Boolean, onClick: () -> Unit) =
        tv(16f, if (selected) gold else white, selected).apply {
            text = (if (selected) "✓  " else "     ") + label
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(14), 0)
            background = rowBg()
            isFocusable = true
            isClickable = true
            tag = if (selected) "sel" else null
            setOnClickListener { onClick() }
        }

    private fun note(msg: String) = tv(14f, dim).apply { text = msg; setPadding(dp(14), dp(8), dp(14), dp(8)) }

    private fun openPanel(focus: Int) {
        if (splash != null) return
        if (menuVisible) hideMenu()
        h.removeCallbacks(hideBannerRun); hideBannerRun.run()
        if (!panelVisible) {
            panelVisible = true
            panel.animate().cancel()
            panel.visibility = View.VISIBLE
            panel.animate().alpha(1f).setDuration(160).start()
            h.post(tickRun)
        }
        pTitle.text = cur?.let { "${it.num}   ${it.name}" } ?: ""
        updatePlayBtn()
        updateProgress()
        refreshTracks()
        panel.postDelayed({
            val target: View? = when (focus) {
                1 -> firstSel(pAudio)
                2 -> firstSel(pSubs)
                else -> pPlay
            }
            (target?.takeIf { it.isFocusable } ?: pPlay).requestFocus()
        }, 120)
    }

    private fun hidePanel() {
        panelVisible = false
        h.removeCallbacks(tickRun)
        panel.animate().cancel()
        panel.animate().alpha(0f).setDuration(140).withEndAction { panel.visibility = View.GONE }.start()
    }

    private fun firstSel(c: LinearLayout): View? {
        val kids = (0 until c.childCount).map { c.getChildAt(it) }
        return kids.firstOrNull { it.tag == "sel" } ?: kids.firstOrNull { it.isFocusable }
    }

    /** Rebuild one list, keeping D-pad focus on the same row index. */
    private fun fillSection(c: LinearLayout, build: (LinearLayout) -> Unit) {
        val f = currentFocus
        val idx = if (f == null) -1 else c.indexOfChild(f)
        c.removeAllViews()
        build(c)
        if (idx >= 0 && c.childCount > 0) c.getChildAt(idx.coerceAtMost(c.childCount - 1)).requestFocus()
    }

    private fun refreshTracks() {
        if (!panelVisible) return
        val tracks = player.currentTracks
        val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        val text = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        val row = { lp(MATCH_PARENT, dp(48)) }

        fillSection(pAudio) { c ->
            var n = 0
            for (g in audio) for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                val f = g.getTrackFormat(i)
                c.addView(optionRow(audioLabel(f, n++), g.isTrackSelected(i)) { pickAudio(g, i) }, row())
            }
            if (n == 0) c.addView(note("No audio tracks reported yet"))
        }

        fillSection(pSubs) { c ->
            val off = !tracks.isTypeSelected(C.TRACK_TYPE_TEXT)
            c.addView(optionRow("Off", off) { pickSub(null, 0) }, row())
            var n = 0
            for (g in text) for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                val f = g.getTrackFormat(i)
                c.addView(optionRow(subLabel(f, n++), !off && g.isTrackSelected(i)) { pickSub(g, i) }, row())
            }
            if (n == 0) c.addView(note("No subtitle tracks in this stream"))
        }
    }

    private fun pickAudio(g: Tracks.Group, i: Int) {
        val f = g.getTrackFormat(i)
        val b = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i))
        langOf(f)?.let { b.setPreferredAudioLanguage(it); prefs.edit().putString("aLang", it).apply() }
        player.trackSelectionParameters = b.build()
        flash("Audio: " + audioLabel(f, i))
    }

    private fun pickSub(g: Tracks.Group?, i: Int) {
        val b = player.trackSelectionParameters.buildUpon()
        if (g == null) {
            b.clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            prefs.edit().putBoolean("subOff", true).apply()
            flash("Subtitles off")
        } else {
            val f = g.getTrackFormat(i)
            b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i))
            langOf(f)?.let { b.setPreferredTextLanguage(it); prefs.edit().putString("sLang", it).apply() }
            prefs.edit().putBoolean("subOff", false).apply()
            flash("Subtitles: " + subLabel(f, i))
        }
        player.trackSelectionParameters = b.build()
    }

    private fun langOf(f: Format): String? = f.language?.takeIf { it.isNotBlank() && it != "und" }

    private fun langName(l: String?): String? {
        if (l.isNullOrBlank() || l == "und") return null
        val n = Locale.forLanguageTag(l).getDisplayLanguage(Locale.getDefault())
        return if (n.isBlank() || n.equals(l, true)) l.uppercase() else n.replaceFirstChar { it.uppercase() }
    }

    private fun audioLabel(f: Format, idx: Int): String {
        val name = f.label ?: langName(f.language) ?: "Track ${idx + 1}"
        val codec = when (val m = f.sampleMimeType) {
            null -> null
            MimeTypes.AUDIO_AAC -> "AAC"
            MimeTypes.AUDIO_AC3 -> "AC3"
            MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> "E-AC3"
            MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD -> "DTS"
            MimeTypes.AUDIO_OPUS -> "Opus"
            MimeTypes.AUDIO_MPEG -> "MP3"
            MimeTypes.AUDIO_MPEG_L2 -> "MP2"
            else -> m?.substringAfter('/')?.uppercase()
        }
        val ch = when (f.channelCount) {
            Format.NO_VALUE -> null
            1 -> "Mono"
            2 -> "Stereo"
            6 -> "5.1"
            8 -> "7.1"
            else -> "${f.channelCount}ch"
        }
        val extra = listOfNotNull(codec, ch).joinToString(" · ")
        return if (extra.isEmpty()) name else "$name  ($extra)"
    }

    private fun subLabel(f: Format, idx: Int): String {
        val name = f.label ?: langName(f.language) ?: "Subtitle ${idx + 1}"
        val extra = ArrayList<String>()
        val m = f.sampleMimeType ?: ""
        if (m.contains("cea-608") || m.contains("cea-708")) extra += "CC"
        if ((f.roleFlags and C.ROLE_FLAG_CAPTION) != 0) extra += "SDH"
        if ((f.selectionFlags and C.SELECTION_FLAG_FORCED) != 0) extra += "forced"
        return if (extra.isEmpty()) name else "$name  (${extra.joinToString(" · ")})"
    }

    // ---------- playback controls ----------
    private fun mediaKey(k: Int): Boolean = when (k) {
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { togglePlay(); true }
        KeyEvent.KEYCODE_MEDIA_PLAY -> { player.play(); true }
        KeyEvent.KEYCODE_MEDIA_PAUSE -> { player.pause(); true }
        KeyEvent.KEYCODE_MEDIA_REWIND -> { seekBy(-10_000); true }
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seekBy(10_000); true }
        else -> false
    }

    private fun togglePlay() {
        if (player.playbackState == Player.STATE_IDLE) cur?.let { play(it); return }
        if (player.playWhenReady) player.pause() else player.play()
    }

    private fun seekBy(ms: Long) {
        if (!player.isCurrentMediaItemSeekable) { flash("Seeking not available on this stream"); return }
        val dur = player.duration
        var t = player.currentPosition + ms
        t = if (dur != C.TIME_UNSET) t.coerceIn(0L, dur) else t.coerceAtLeast(0L)
        player.seekTo(t)
        flash(if (ms < 0) "« ${-ms / 1000}s" else "${ms / 1000}s »")
        updateProgress()
    }

    private fun updatePlayBtn() {
        if (!::pPlay.isInitialized) return
        pPlay.text = if (player.playWhenReady) "Pause" else "▶ Play"
    }

    private fun durText(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val hr = s / 3600
        val mn = (s % 3600) / 60
        val sc = s % 60
        return if (hr > 0) String.format(Locale.ROOT, "%d:%02d:%02d", hr, mn, sc)
        else String.format(Locale.ROOT, "%02d:%02d", mn, sc)
    }

    private fun updateProgress() {
        if (!::pBar.isInitialized) return
        val dur = player.duration
        val pos = player.currentPosition.coerceAtLeast(0L)
        val seekable = player.isCurrentMediaItemSeekable
        val live = player.isCurrentMediaItemLive || dur == C.TIME_UNSET
        pLive.visibility = if (player.isCurrentMediaItemLive && seekable) View.VISIBLE else View.GONE
        pRew.alpha = if (seekable) 1f else 0.4f
        pFwd.alpha = if (seekable) 1f else 0.4f
        if (live) {
            val off = player.currentLiveOffset
            pTime.text = if (off != C.TIME_UNSET && off > 4000) "● LIVE   (${durText(off)} behind)" else "● LIVE"
            pBar.progress = if (dur != C.TIME_UNSET && dur > 0 && seekable) (pos * 1000 / dur).toInt().coerceIn(0, 1000) else 1000
        } else {
            pTime.text = durText(pos) + " / " + durText(dur)
            pBar.progress = (pos * 1000 / dur.coerceAtLeast(1L)).toInt().coerceIn(0, 1000)
        }
    }

    // ---------- adapters ----------
    inner class GroupVH(v: View, val name: TextView, val cnt: TextView) : RecyclerView.ViewHolder(v)
    inner class ChVH(v: View, val num: TextView, val logo: ImageView, val name: TextView, val now: TextView, val star: TextView) : RecyclerView.ViewHolder(v)

    inner class GroupAdapter : RecyclerView.Adapter<GroupVH>() {
        override fun getItemCount() = groups.size
        override fun onCreateViewHolder(p: ViewGroup, t: Int): GroupVH {
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), 0, dp(14), 0)
                layoutParams = RecyclerView.LayoutParams(MATCH_PARENT, dp(50))
                background = rowBg()
                isFocusable = true
                isClickable = true
            }
            val name = tv(15f)
            val cnt = tv(12f, c(0x88FFFFFF))
            row.addView(name, lp(0, WRAP_CONTENT, 1f))
            row.addView(cnt, lp(WRAP_CONTENT, WRAP_CONTENT))
            return GroupVH(row, name, cnt)
        }

        override fun onBindViewHolder(hd: GroupVH, i: Int) {
            hd.name.text = groups[i]
            hd.cnt.text = (when (i) { 0 -> all.size; 1 -> favCount(); else -> counts[groups[i]] ?: 0 }).toString()
            hd.name.setTextColor(if (i == groupSel) gold else white)
            hd.itemView.setOnClickListener { clearSearch(); applyGroup(i) }
            hd.itemView.setOnFocusChangeListener { _, f ->
                if (f) {
                    h.removeCallbacks(groupRun)
                    groupRun = Runnable { if (i != groupSel || query.isNotEmpty()) { clearSearch(); applyGroup(i) } }
                    h.postDelayed(groupRun, 250)
                }
            }
        }
    }

    inner class ChAdapter : RecyclerView.Adapter<ChVH>() {
        var items: List<Channel> = emptyList()
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(p: ViewGroup, t: Int): ChVH {
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(6), dp(14), dp(6))
                layoutParams = RecyclerView.LayoutParams(MATCH_PARENT, dp(66))
                background = rowBg()
                isFocusable = true
                isClickable = true
            }
            val num = tv(14f, dim)
            val logo = ImageView(this@MainActivity).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(dp(4), dp(4), dp(4), dp(4))
            }
            val col = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            val name = tv(17f, white, true)
            val now = tv(12f, dim).apply { setPadding(0, dp(3), 0, 0) }
            col.addView(name); col.addView(now)
            row.addView(num, lp(dp(54), WRAP_CONTENT))
            row.addView(logo, lp(dp(46), dp(46)).apply { rightMargin = dp(14) })
            row.addView(col, lp(0, WRAP_CONTENT, 1f))
            val star = tv(20f, gold, true)
            row.addView(star, lp(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = dp(8) })
            return ChVH(row, num, logo, name, now, star)
        }

        override fun onBindViewHolder(hd: ChVH, i: Int) {
            val ch = items[i]
            hd.num.text = ch.num.toString()
            hd.name.text = ch.name
            hd.name.setTextColor(if (ch === cur) gold else white)
            Img.load(ch.logo, hd.logo)
            hd.now.text = Epg.now(ch.tvgId)?.title ?: ch.group
            hd.star.text = if (ch.url in favs) "★" else ""
            hd.itemView.setOnClickListener {
                if (query.isNotEmpty()) { clearSearch(); applyGroup(0) }
                tune(ch, true)
                hideMenu()
            }
            hd.itemView.setOnLongClickListener { toggleFav(ch); true }
        }
    }
}
