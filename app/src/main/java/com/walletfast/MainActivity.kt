package com.walletfast

import android.annotation.SuppressLint
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class MainActivity : Activity() {

    private class Acc(var name: String, var open: Double)
    private class Tpl(var label: String, var amt: Double)
    private class Tx(val id: String, val t: Long, val acc: Int, val label: String, val amt: Double)

    private val BG = 0xFF121613.toInt()
    private val CARD = 0xFF1A201D.toInt()
    private val KEY = 0xFF252D28.toInt()
    private val TEXT = 0xFFEEF1EE.toInt()
    private val SUB = 0xFF93A196.toInt()
    private val ACCENT = 0xFF3DDB9D.toInt()
    private val DANGER = 0xFFFF6B52.toInt()
    private val BORDER = 0xFF2A322D.toInt()
    private val PILL = 0x2E3DDB9D          // ACCENT at ~18% opacity (subtle nav pill)
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    // animation: very short and subtle, GPU-friendly property animations only
    private val DUR = 120L
    private val DOWN_DUR = 80L
    private val ease = DecelerateInterpolator()

    private val accs = ArrayList<Acc>()
    private val tpls = ArrayList<Tpl>()
    private val txs = ArrayList<Tx>()
    private var active = 0
    private var tab = 0            // 0 = Templates (home), 1 = Accounts
    private var edit = false
    private var autoClose = false
    private var lastTap = 0L
    private var navAnim = false    // play the nav pill fade-in on the next render
    private var newTxId: String? = null // newest history row fades in once
    private var frame: FrameLayout? = null
    private var sheet: View? = null
    private lateinit var file: File
    private lateinit var fontReg: Typeface
    private lateinit var fontBold: Typeface

    // swipe tracking
    private var swX = 0f
    private var swY = 0f
    private var swT = 0L
    private var swArmed = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        fontReg = resources.getFont(R.font.open_sans_regular)
        fontBold = resources.getFont(R.font.open_sans_bold)
        file = File(filesDir, "telefon.jsonl")
        loadCfg()
        loadTx()
        render()
    }

    override fun onRestart() {
        super.onRestart()
        // App always comes back on the Templates tab
        if (sheet == null && (tab != 0 || edit)) {
            tab = 0
            edit = false
            render()
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when {
            sheet != null -> closeSheet()
            tab != 0 -> { tab = 0; edit = false; navAnim = true; render() }
            else -> super.onBackPressed()
        }
    }

    // Swipe left/right anywhere on screen to switch tabs (threshold based, no live drag)
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swX = ev.rawX
                swY = ev.rawY
                swT = ev.eventTime
                swArmed = sheet == null
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> swArmed = false
        }
        val handled = super.dispatchTouchEvent(ev)
        if (ev.actionMasked == MotionEvent.ACTION_UP && swArmed) {
            swArmed = false
            val dx = ev.rawX - swX
            val dy = ev.rawY - swY
            val fast = ev.eventTime - swT < 600
            val far = Math.abs(dx) > dp(80).toFloat()
            val horizontal = Math.abs(dx) > Math.abs(dy) * 2f
            if (sheet == null && fast && far && horizontal) {
                val to = if (dx < 0) 1 else 0   // swipe left -> Accounts, swipe right -> Templates
                if (to != tab) {
                    tab = to
                    edit = false
                    navAnim = true
                    render()
                }
            }
        }
        return handled
    }

    // ---------- data ----------
    private fun defaults() {
        accs.clear(); tpls.clear()
        for (i in 1..5) accs.add(Acc("Account $i", 0.0))
        tpls.add(Tpl("Bus", -10.0))
        tpls.add(Tpl("Cafeteria", -50.0))
    }

    private fun loadCfg() {
        val s = getSharedPreferences("cfg", MODE_PRIVATE).getString("cfg", null)
        if (s == null) { defaults(); return }
        try {
            val o = JSONObject(s)
            accs.clear(); tpls.clear()
            val a = o.getJSONArray("accs")
            for (i in 0 until a.length()) {
                val x = a.getJSONObject(i)
                accs.add(Acc(x.getString("n"), x.getDouble("o")))
            }
            val t = o.getJSONArray("tpls")
            for (i in 0 until t.length()) {
                val x = t.getJSONObject(i)
                tpls.add(Tpl(x.getString("l"), x.getDouble("a")))
            }
            active = o.optInt("active", 0).coerceIn(0, 4)
            autoClose = o.optBoolean("ac", false)
            if (accs.size != 5) { defaults(); return }
        } catch (e: Exception) {
            defaults()
            return
        }
        // old default names "Hesap 1" -> "Account 1"
        var changed = false
        val old = Regex("Hesap [1-5]")
        for (a in accs) {
            if (old.matches(a.name)) {
                a.name = "Account " + a.name.last()
                changed = true
            }
        }
        if (changed) saveCfg()
    }

    private fun saveCfg() {
        val a = JSONArray()
        accs.forEach { a.put(JSONObject().put("n", it.name).put("o", it.open)) }
        val t = JSONArray()
        tpls.forEach { t.put(JSONObject().put("l", it.label).put("a", it.amt)) }
        val o = JSONObject().put("accs", a).put("tpls", t).put("active", active).put("ac", autoClose)
        getSharedPreferences("cfg", MODE_PRIVATE).edit().putString("cfg", o.toString()).apply()
    }

    private fun loadTx() {
        val m = LinkedHashMap<String, Tx>()
        if (file.exists()) {
            file.forEachLine { line ->
                try {
                    val o = JSONObject(line)
                    when (o.getString("k")) {
                        "tx" -> m[o.getString("id")] = Tx(
                            o.getString("id"), o.getLong("t"),
                            o.getInt("acc").coerceIn(0, 4), o.getString("l"), o.getDouble("a")
                        )
                        "undo" -> m.remove(o.getString("ref"))
                        else -> {}
                    }
                } catch (e: Exception) {
                }
            }
        }
        txs.clear()
        txs.addAll(m.values)
    }

    private fun append(o: JSONObject) {
        try {
            file.appendText(o.toString() + "\n")
        } catch (e: Exception) {
            snack("Couldn't save entry")
        }
    }

    private fun addTx(label: String, amt: Double) {
        val tx = Tx(UUID.randomUUID().toString(), System.currentTimeMillis(), active, label, amt)
        txs.add(tx)
        newTxId = tx.id
        append(JSONObject().put("k", "tx").put("id", tx.id).put("t", tx.t).put("acc", tx.acc).put("l", label).put("a", amt))
    }

    private fun undo(tx: Tx) {
        txs.remove(tx)
        append(JSONObject().put("k", "undo").put("ref", tx.id).put("t", System.currentTimeMillis()))
        render()
    }

    private fun balOf(i: Int): Double {
        var s = accs[i].open
        for (x in txs) if (x.acc == i) s += x.amt
        return r2(s)
    }

    // ---------- format ----------
    private fun r2(n: Double) = Math.round(n * 100.0) / 100.0

    private fun num(n: Double): String {
        val a = Math.abs(n)
        return if (a % 1.0 == 0.0) String.format(Locale.US, "%,d", a.toLong()) else String.format(Locale.US, "%,.2f", a)
    }

    private fun fmt(n: Double) = (if (n < 0) "-" else "") + "₺" + num(n)
    private fun sgn(n: Double) = (if (n > 0) "+" else "") + fmt(n)
    private fun raw(n: Double) = if (n % 1.0 == 0.0) n.toLong().toString() else String.format(Locale.US, "%.2f", n)

    // "1234.5" -> "1,234.5" (keeps what the user typed)
    private fun grp(s: String): String {
        if (s.isEmpty()) return "0"
        val dot = s.indexOf('.')
        val ip = if (dot >= 0) s.substring(0, dot) else s
        val fp = if (dot >= 0) s.substring(dot) else ""
        val head = String.format(Locale.US, "%,d", ip.ifEmpty { "0" }.toLong())
        return head + fp
    }

    // ---------- helpers ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun tv(text: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        typeface = if (bold) fontBold else fontReg
    }

    private fun box(fill: Int, stroke: Int = 0) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(14).toFloat()
        if (stroke != 0) setStroke(dp(2), stroke)
    }

    private fun tap(fill: Int, stroke: Int = 0): Drawable =
        RippleDrawable(ColorStateList.valueOf(0x26FFFFFF), box(fill, stroke), null)

    private fun lp(w: Int, h: Int, wt: Float = 0f) = LinearLayout.LayoutParams(w, h, wt)

    // Press feedback: view shrinks slightly while the finger is down, eases back on release/cancel.
    // Returns false so clicks, long clicks and scrolling keep working exactly as before.
    @SuppressLint("ClickableViewAccessibility")
    private fun pressFx(v: View, to: Float = 0.97f) {
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    view.animate().scaleX(to).scaleY(to).setDuration(DOWN_DUR).setInterpolator(ease).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    view.animate().scaleX(1f).scaleY(1f).setDuration(DUR).setInterpolator(ease).start()
            }
            false
        }
    }

    private fun buzz() {
        try {
            val v = getSystemService(Vibrator::class.java)
            v?.vibrate(VibrationEffect.createOneShot(25, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) {
        }
    }

    private fun quit() {
        finishAndRemoveTask()
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    private fun hideKb() {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(window.decorView.windowToken, 0)
    }

    private fun snack(msg: String) {
        val f = frame ?: return
        val t = tv(msg, 14f, TEXT, true).apply {
            setPadding(dp(18), dp(12), dp(18), dp(12))
            background = box(KEY, BORDER)
            alpha = 0f
            translationY = dp(10).toFloat()
        }
        f.addView(
            t,
            FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(88) }
        )
        t.animate().alpha(1f).translationY(0f).setDuration(DUR).setInterpolator(ease).start()
        t.postDelayed({
            t.animate().alpha(0f).translationY(dp(6).toFloat()).setDuration(DUR).setInterpolator(ease)
                .withEndAction { (t.parent as? ViewGroup)?.removeView(t) }
                .start()
        }, 1900)
    }

    private fun page() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(24))
    }

    private fun scroll(v: View) = ScrollView(this).apply {
        isVerticalScrollBarEnabled = false
        addView(v)
    }

    // custom on/off switch (knob slides, track color fades)
    private fun toggle(on: Boolean, change: (Boolean) -> Unit): View {
        var state = on
        val track = FrameLayout(this)
        val trackBg = GradientDrawable().apply {
            setColor(if (on) ACCENT else BORDER)
            cornerRadius = dp(15).toFloat()
        }
        track.background = trackBg
        val knob = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(TEXT)
            }
        }
        val kp = FrameLayout.LayoutParams(dp(22), dp(22)).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }
        track.addView(knob, kp)
        val travel = dp(22).toFloat() // track 50dp - knob 22dp - margins 6dp
        knob.translationX = if (on) travel else 0f
        track.setOnClickListener {
            state = !state
            knob.animate().translationX(if (state) travel else 0f).setDuration(DUR).setInterpolator(ease).start()
            ValueAnimator.ofObject(ArgbEvaluator(), if (state) BORDER else ACCENT, if (state) ACCENT else BORDER).apply {
                duration = DUR
                interpolator = ease
                addUpdateListener { trackBg.setColor(it.animatedValue as Int) }
                start()
            }
            change(state)
        }
        return track
    }

    // ---------- screen ----------
    private fun render() {
        sheet = null
        val f = FrameLayout(this).apply { setBackgroundColor(BG) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(if (tab == 0) buildTemplates() else buildAccounts(), lp(MATCH, 0, 1f))
        col.addView(buildNav(), lp(MATCH, WRAP))
        f.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))
        frame = f
        setContentView(f)
    }

    private fun buildNav(): View {
        val animate = navAnim
        navAnim = false
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        wrap.addView(View(this).apply { setBackgroundColor(BORDER) }, lp(MATCH, dp(1)))
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(BG)
        }
        fun item(idx: Int, icon: Int, label: String) {
            val sel = tab == idx
            val c = if (sel) ACCENT else SUB
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(0, dp(6), 0, dp(6))
                setOnClickListener {
                    if (tab != idx) {
                        tab = idx
                        edit = false
                        navAnim = true
                        render()
                    }
                }
            }
            // soft pill behind the icon + label of the selected tab
            val pill = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(22), dp(5), dp(22), dp(5))
            }
            if (sel) {
                val bg = GradientDrawable().apply {
                    setColor(PILL)
                    cornerRadius = dp(16).toFloat()
                }
                pill.background = bg
                if (animate) {
                    bg.alpha = 0
                    ValueAnimator.ofInt(0, 255).apply {
                        duration = DUR
                        interpolator = ease
                        addUpdateListener { bg.alpha = it.animatedValue as Int }
                        start()
                    }
                }
            }
            pill.addView(ImageView(this).apply {
                setImageResource(icon)
                setColorFilter(c)
            }, lp(dp(24), dp(24)))
            pill.addView(tv(label, 11f, c, sel).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(2), 0, 0)
            }, lp(WRAP, WRAP))
            cell.addView(pill, lp(WRAP, WRAP))
            bar.addView(cell, lp(0, WRAP, 1f))
        }
        item(0, R.drawable.ic_home, "Templates")
        item(1, R.drawable.ic_accounts, "Accounts")
        wrap.addView(bar)
        return wrap
    }

    private fun header(root: LinearLayout, total: Double, hint: String) {
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        col.addView(tv("Total Balance", 15f, SUB))
        col.addView(tv(fmt(total), 46f, if (total < 0) DANGER else TEXT, true))
        top.addView(col, lp(0, WRAP, 1f))
        top.addView(tv(if (edit) "Done" else "Edit", 15f, ACCENT, true).apply {
            setPadding(dp(12), dp(10), dp(4), dp(10))
            setOnClickListener { edit = !edit; render() }
        })
        root.addView(top)
        if (edit) root.addView(tv(hint, 12f, SUB).apply { setPadding(dp(2), dp(6), 0, 0) })
    }

    // ---------- Templates tab ----------
    private fun buildTemplates(): View {
        val bals = DoubleArray(5) { balOf(it) }
        val root = page()
        header(root, r2(bals.sum()), "Tap a template to edit or delete it")

        // which account new entries go to
        val ac = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = tap(CARD, BORDER)
            setOnClickListener { openAccountPicker() }
        }
        pressFx(ac, 0.98f)
        val acl = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        acl.addView(tv("Active account", 11f, SUB))
        acl.addView(tv(accs[active].name, 15f, TEXT, true))
        ac.addView(acl, lp(0, WRAP, 1f))
        ac.addView(tv(fmt(bals[active]), 15f, if (bals[active] < 0) DANGER else TEXT, true))
        ac.addView(tv("  ›", 18f, SUB))
        root.addView(ac, lp(MATCH, WRAP).apply { setMargins(0, dp(14), 0, 0) })

        // templates
        root.addView(tv("Templates", 13f, SUB, true).apply { setPadding(dp(2), dp(18), 0, dp(6)) })
        val grid = GridLayout(this).apply { columnCount = 2 }
        fun cell(v: View) {
            val p = GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f))
            p.width = 0
            p.setMargins(dp(4), dp(4), dp(4), dp(4))
            grid.addView(v, p)
        }
        tpls.forEachIndexed { idx, t ->
            val c = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(36), dp(8), dp(36))
                background = tap(CARD, BORDER)
                setOnClickListener {
                    if (edit) {
                        openTplEditor(idx)
                    } else {
                        val now = System.currentTimeMillis()
                        if (now - lastTap < 350) return@setOnClickListener
                        lastTap = now
                        addTx(t.label, t.amt)
                        buzz()
                        if (autoClose) quit() else render()
                    }
                }
            }
            pressFx(c, 0.96f)
            c.addView(tv(t.label, 23f, TEXT, true).apply { gravity = Gravity.CENTER })
            c.addView(tv(sgn(t.amt), 18f, if (t.amt < 0) DANGER else ACCENT).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(4), 0, 0)
            })
            cell(c)
        }
        val add = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(36), dp(8), dp(36))
            background = tap(BG, BORDER)
            setOnClickListener { openTplEditor(-1) }
        }
        pressFx(add, 0.96f)
        add.addView(tv("+ New Template", 17f, SUB, true).apply { gravity = Gravity.CENTER })
        cell(add)
        root.addView(grid)

        // history
        root.addView(tv("Recent Activity", 13f, SUB, true).apply { setPadding(dp(2), dp(18), 0, dp(6)) })
        if (txs.isEmpty()) root.addView(tv("No transactions yet", 14f, SUB))
        val df = SimpleDateFormat("d MMM, HH:mm", Locale.US)
        for (tx in txs.asReversed().take(15)) {
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(8), dp(8), dp(8))
                background = box(CARD)
            }
            val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            info.addView(tv(tx.label, 15f, TEXT))
            info.addView(tv(accs[tx.acc].name + " · " + df.format(Date(tx.t)), 12f, SUB))
            r.addView(info, lp(0, WRAP, 1f))
            r.addView(tv(sgn(tx.amt), 15f, if (tx.amt < 0) DANGER else ACCENT, true))
            r.addView(tv("Undo", 13f, SUB).apply {
                setPadding(dp(14), dp(10), dp(6), dp(10))
                setOnClickListener {
                    isClickable = false // no double undo while the row slides out
                    r.animate().alpha(0f).translationX(dp(28).toFloat()).setDuration(DUR).setInterpolator(ease)
                        .withEndAction { undo(tx) }
                        .start()
                }
            })
            // only the row that was just added fades in; the rest stay still
            if (tx.id == newTxId) {
                r.alpha = 0f
                r.translationY = -dp(8).toFloat()
                r.post { r.animate().alpha(1f).translationY(0f).setDuration(DUR).setInterpolator(ease).start() }
            }
            root.addView(r, lp(MATCH, WRAP).apply { setMargins(0, dp(4), 0, 0) })
        }
        newTxId = null

        // settings (edit mode)
        if (edit) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(14), dp(14), dp(14))
                background = box(CARD, BORDER)
            }
            row.addView(tv("Save & close app on template tap", 14f, TEXT), lp(0, WRAP, 1f))
            row.addView(toggle(autoClose) { autoClose = it; saveCfg() }, lp(dp(50), dp(28)))
            root.addView(row, lp(MATCH, WRAP).apply { setMargins(0, dp(20), 0, 0) })
        }

        return scroll(root)
    }

    // ---------- Accounts tab ----------
    private fun buildAccounts(): View {
        val bals = DoubleArray(5) { balOf(it) }
        val root = page()
        header(root, r2(bals.sum()), "Tap an account to rename it or set its balance")

        root.addView(tv("Accounts", 13f, SUB, true).apply { setPadding(dp(2), dp(18), 0, dp(6)) })
        for (i in 0 until 5) {
            val sel = i == active
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(68)
                setPadding(dp(16), dp(12), dp(16), dp(12))
                background = tap(CARD, if (sel) ACCENT else BORDER)
                setOnClickListener {
                    if (edit) {
                        openAccEditor(i)
                    } else {
                        active = i
                        saveCfg()
                        render()
                    }
                }
            }
            pressFx(r, 0.98f)
            val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            l.addView(tv(accs[i].name, 16f, TEXT, true))
            if (sel) l.addView(tv("Active", 12f, ACCENT))
            r.addView(l, lp(0, WRAP, 1f))
            r.addView(tv(fmt(bals[i]), 18f, if (bals[i] < 0) DANGER else TEXT, true))
            if (edit) r.addView(tv("Edit", 13f, ACCENT, true).apply { setPadding(dp(12), 0, 0, 0) })
            root.addView(r, lp(MATCH, WRAP).apply { setMargins(0, dp(4), 0, dp(4)) })
        }
        return scroll(root)
    }
    // ---------- account picker ----------
    private fun openAccountPicker() {
        val f = frame ?: return
        if (sheet != null) return

        val scrim = FrameLayout(this).apply {
            setBackgroundColor(0xB3000000.toInt())
            setOnClickListener { closeSheet() }
        }
        val rad = dp(22).toFloat()
        val sh = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(20))
            background = GradientDrawable().apply {
                setColor(CARD)
                cornerRadii = floatArrayOf(rad, rad, rad, rad, 0f, 0f, 0f, 0f)
            }
            isClickable = true // sheete dokununca scrim kapanmasın
        }

        // tutamaç
        sh.addView(
            View(this).apply { background = box(BORDER) },
            lp(dp(40), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; setMargins(0, 0, 0, dp(12)) }
        )

        sh.addView(tv("Select Account", 18f, TEXT, true).apply { setPadding(dp(2), 0, 0, dp(8)) })

        for (i in 0 until 5) {
            val sel = i == active
            val bal = balOf(i)
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(64)
                setPadding(dp(16), dp(10), dp(16), dp(10))
                background = tap(BG, if (sel) ACCENT else BORDER)
                setOnClickListener {
                    active = i
                    saveCfg()
                    render() // sheet'i de kapatır, Templates ekranında kalırız
                }
            }
            pressFx(r, 0.98f)
            val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            l.addView(tv(accs[i].name, 16f, TEXT, true))
            if (sel) l.addView(tv("Active", 12f, ACCENT))
            r.addView(l, lp(0, WRAP, 1f))
            r.addView(tv(fmt(bal), 17f, if (bal < 0) DANGER else TEXT, true))
            sh.addView(r, lp(MATCH, WRAP).apply { setMargins(0, dp(4), 0, dp(4)) })
        }

        val sv = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(sh)
        }
        scrim.addView(sv, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
        f.addView(scrim, FrameLayout.LayoutParams(MATCH, MATCH))
        sheet = scrim
    }
    // ---------- editors ----------

    private fun openAccEditor(i: Int) {
        val a = accs[i]
        val cur = balOf(i)
        openEditor(
            title = "Edit Account",
            nameHint = "Account name",
            name = a.name,
            amountLabel = "Current balance",
            amount = cur,
            negLabel = "Negative",
            posLabel = "Positive",
            startOnName = false,
            requireName = false,
            requireAmount = false,
            onDelete = null
        ) { nm, v ->
            if (nm.isNotEmpty()) a.name = nm
            a.open = v - (cur - a.open)
            saveCfg()
            render()
        }
    }

    private fun openTplEditor(idx: Int) {
        val t = if (idx >= 0) tpls[idx] else null
        openEditor(
            title = if (t == null) "New Template" else "Edit Template",
            nameHint = "Name (e.g. Bus)",
            name = t?.label ?: "",
            amountLabel = "Amount",
            amount = t?.amt,
            negLabel = "Expense",
            posLabel = "Income",
            startOnName = t == null,
            requireName = true,
            requireAmount = true,
            onDelete = if (t == null) null else ({ tpls.removeAt(idx); saveCfg(); render() })
        ) { nm, v ->
            if (t == null) tpls.add(Tpl(nm, v)) else { t.label = nm; t.amt = v }
            saveCfg()
            render()
        }
    }

    private fun press(buf: StringBuilder, k: String) {
        when (k) {
            "←" -> if (buf.isNotEmpty()) buf.setLength(buf.length - 1)
            "." -> if (buf.indexOf(".") < 0) {
                if (buf.isEmpty()) buf.append("0")
                buf.append(".")
            }
            else -> {
                val dot = buf.indexOf(".")
                if (dot >= 0) {
                    if (buf.length - dot - 1 >= 2) return
                } else if (buf.length >= 9) return
                if (buf.toString() == "0") buf.setLength(0)
                buf.append(k)
            }
        }
    }

    private fun closeSheet() {
        hideKb()
        sheet?.let { (it.parent as? ViewGroup)?.removeView(it) }
        sheet = null
    }

    // Bottom sheet: name (Android keyboard) + amount (in-app numpad)
    private fun openEditor(
        title: String,
        nameHint: String,
        name: String,
        amountLabel: String,
        amount: Double?,
        negLabel: String,
        posLabel: String,
        startOnName: Boolean,
        requireName: Boolean,
        requireAmount: Boolean,
        onDelete: (() -> Unit)?,
        onSave: (String, Double) -> Unit
    ) {
        val f = frame ?: return
        if (sheet != null) return

        var neg = (amount ?: -1.0) < 0
        val buf = StringBuilder(if (amount == null) "" else raw(Math.abs(amount)))

        val scrim = FrameLayout(this).apply {
            setBackgroundColor(0xB3000000.toInt())
            setOnClickListener { closeSheet() }
        }
        val rad = dp(22).toFloat()
        val sh = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(16))
            background = GradientDrawable().apply {
                setColor(CARD)
                cornerRadii = floatArrayOf(rad, rad, rad, rad, 0f, 0f, 0f, 0f)
            }
            isClickable = true
            isFocusableInTouchMode = true
        }

        // handle
        sh.addView(
            View(this).apply { background = box(BORDER) },
            lp(dp(40), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; setMargins(0, 0, 0, dp(12)) }
        )

        // title row
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(tv(title, 18f, TEXT, true), lp(0, WRAP, 1f))
        if (onDelete != null) {
            head.addView(tv("Delete", 14f, DANGER, true).apply {
                setPadding(dp(12), dp(8), dp(4), dp(8))
                setOnClickListener { closeSheet(); onDelete() }
            })
        }
        sh.addView(head)

        // name
        sh.addView(tv("Name", 12f, SUB).apply { setPadding(dp(2), dp(12), 0, dp(4)) })
        val nameEt = EditText(this).apply {
            hint = nameHint
            setText(name)
            setSelection(text.length)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            imeOptions = EditorInfo.IME_ACTION_NEXT
            filters = arrayOf<InputFilter>(InputFilter.LengthFilter(24))
            textSize = 17f
            typeface = fontReg
            setTextColor(TEXT)
            setHintTextColor(SUB)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        sh.addView(nameEt, lp(MATCH, WRAP))

        // amount
        sh.addView(tv(amountLabel, 12f, SUB).apply { setPadding(dp(2), dp(12), 0, dp(4)) })
        val amtBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(8), dp(8))
        }
        val amtTv = tv("0", 26f, SUB, true)
        amtBox.addView(amtTv, lp(0, WRAP, 1f))
        val segNeg = tv(negLabel, 13f, SUB, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val segPos = tv(posLabel, 13f, SUB, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val seg = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = box(CARD)
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        seg.addView(segNeg)
        seg.addView(segPos)
        amtBox.addView(seg)
        sh.addView(amtBox, lp(MATCH, WRAP))

        val err = tv("", 12f, DANGER).apply {
            setPadding(dp(2), dp(8), 0, 0)
            visibility = View.GONE
        }
        sh.addView(err)

        // numpad
        val pad = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }

        fun refresh() {
            val v = buf.toString().toDoubleOrNull() ?: 0.0
            val empty = buf.isEmpty()
            val sign = if (neg && v != 0.0) "-" else ""
            amtTv.text = sign + "₺" + grp(buf.toString())
            amtTv.setTextColor(if (empty) SUB else if (neg) DANGER else ACCENT)
            segNeg.setTextColor(if (neg) BG else SUB)
            segPos.setTextColor(if (!neg) BG else SUB)
            segNeg.background = if (neg) box(DANGER) else null
            segPos.background = if (!neg) box(ACCENT) else null
            err.visibility = View.GONE
        }

        val rows = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf(".", "0", "←")
        )
        for (rw in rows) {
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (k in rw) {
                val key = tv(k, 22f, TEXT, true).apply {
                    gravity = Gravity.CENTER
                    background = tap(KEY)
                    setOnClickListener { press(buf, k); refresh() }
                    if (k == "←") setOnLongClickListener { buf.setLength(0); refresh(); true }
                }
                pressFx(key, 0.94f)
                line.addView(key, lp(0, dp(50), 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
            }
            pad.addView(line)
        }
        sh.addView(pad)

        // buttons
        val btns = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        val cancelBtn = tv("Cancel", 16f, SUB, true).apply {
            gravity = Gravity.CENTER
            background = tap(KEY)
            setOnClickListener { closeSheet() }
        }
        pressFx(cancelBtn, 0.97f)
        btns.addView(cancelBtn, lp(0, dp(52), 1f).apply { setMargins(0, 0, dp(8), 0) })
        val saveBtn = tv("Save", 16f, BG, true).apply {
            gravity = Gravity.CENTER
            background = tap(ACCENT)
            setOnClickListener {
                val nm = nameEt.text.toString().trim()
                val v = buf.toString().toDoubleOrNull() ?: 0.0
                val msg = when {
                    requireName && nm.isEmpty() -> "Enter a name"
                    requireAmount && v == 0.0 -> "Enter an amount"
                    else -> null
                }
                if (msg != null) {
                    err.text = msg
                    err.visibility = View.VISIBLE
                    return@setOnClickListener
                }
                closeSheet()
                onSave(nm, if (neg) -v else v)
            }
        }
        pressFx(saveBtn, 0.97f)
        btns.addView(saveBtn, lp(0, dp(52), 1.5f))
        sh.addView(btns)

        // focus handling: name -> Android keyboard, amount -> in-app numpad
        fun showPad(on: Boolean) {
            pad.visibility = if (on) View.VISIBLE else View.GONE
            amtBox.background = box(BG, if (on) ACCENT else BORDER)
            nameEt.background = box(BG, if (on) BORDER else ACCENT)
        }

        fun toAmount() {
            hideKb()
            sh.requestFocus()
            showPad(true)
        }

        fun toName() {
            showPad(false)
            nameEt.requestFocus()
            nameEt.postDelayed({
                getSystemService(InputMethodManager::class.java)
                    ?.showSoftInput(nameEt, InputMethodManager.SHOW_IMPLICIT)
            }, 150)
        }

        nameEt.setOnFocusChangeListener { _, has -> if (has) showPad(false) }
        nameEt.setOnEditorActionListener { _, _, _ -> toAmount(); true }
        amtBox.setOnClickListener { toAmount() }
        segNeg.setOnClickListener { neg = true; refresh(); toAmount() }
        segPos.setOnClickListener { neg = false; refresh(); toAmount() }

        val sv = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(sh)
        }
        scrim.addView(sv, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
        f.addView(scrim, FrameLayout.LayoutParams(MATCH, MATCH))
        sheet = scrim

        refresh()
        if (startOnName) toName() else toAmount()
    }
}
