package com.walletfast

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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
    private val TEXT = 0xFFEEF1EE.toInt()
    private val SUB = 0xFF93A196.toInt()
    private val ACCENT = 0xFF3DDB9D.toInt()
    private val DANGER = 0xFFFF6B52.toInt()
    private val BORDER = 0xFF2A322D.toInt()
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private val NUM = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
    private val TR = Locale.forLanguageTag("tr-TR")

    private val accs = ArrayList<Acc>()
    private val tpls = ArrayList<Tpl>()
    private val txs = ArrayList<Tx>()
    private var active = 0
    private var edit = false
    private var autoClose = false
    private var lastTap = 0L
    private lateinit var file: File

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        file = File(filesDir, "telefon.jsonl")
        loadCfg()
        loadTx()
        render()
    }

    // ---------- veri ----------
    private fun defaults() {
        accs.clear(); tpls.clear()
        for (i in 1..5) accs.add(Acc("Hesap $i", 0.0))
        tpls.add(Tpl("Otobüs", -10.0))
        tpls.add(Tpl("Yemekhane", -50.0))
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
            if (accs.size != 5) defaults()
        } catch (e: Exception) {
            defaults()
        }
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
            Toast.makeText(this, "Kayıt yazılamadı", Toast.LENGTH_SHORT).show()
        }
    }

    private fun addTx(label: String, amt: Double) {
        val tx = Tx(UUID.randomUUID().toString(), System.currentTimeMillis(), active, label, amt)
        txs.add(tx)
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
        return Math.round(s * 100.0) / 100.0
    }

    // ---------- biçim ----------
    private fun num(n: Double): String {
        val a = Math.abs(n)
        return if (a % 1.0 == 0.0) String.format(TR, "%,d", a.toLong()) else String.format(TR, "%,.2f", a)
    }

    private fun fmt(n: Double) = (if (n < 0) "-" else "") + num(n) + " ₺"
    private fun sgn(n: Double) = (if (n > 0) "+" else "") + fmt(n)
    private fun raw(n: Double) = if (n % 1.0 == 0.0) n.toLong().toString() else String.format(Locale.US, "%.2f", n)
    private fun parse(s: String) = s.trim().replace(',', '.').toDoubleOrNull()

    // ---------- yardımcılar ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun tv(text: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun box(fill: Int, stroke: Int = 0) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(14).toFloat()
        if (stroke != 0) setStroke(dp(2), stroke)
    }

    private fun lp(w: Int, h: Int, wt: Float = 0f) = LinearLayout.LayoutParams(w, h, wt)

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

    private fun field(hint: String, value: String, type: Int) = EditText(this).apply {
        this.hint = hint
        setText(value)
        inputType = type
        setTextColor(TEXT)
        setHintTextColor(SUB)
    }

    private fun form(vararg v: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(8), dp(20), 0)
        v.forEach { addView(it) }
    }

    // ---------- arayüz ----------
    private fun render() {
        setContentView(buildUi())
    }

    private fun buildUi(): View {
        val bals = DoubleArray(5) { balOf(it) }
        val total = bals.sum()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(28))
        }

        // üst satır
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val tcol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tcol.addView(tv("Toplam bakiye", 12f, SUB))
        tcol.addView(tv(fmt(total), 30f, if (total < 0) DANGER else TEXT, true))
        top.addView(tcol, lp(0, WRAP, 1f))
        val eb = tv(if (edit) "Bitti" else "Düzenle", 15f, ACCENT, true).apply {
            setPadding(dp(12), dp(10), dp(4), dp(10))
            setOnClickListener { edit = !edit; render() }
        }
        top.addView(eb)
        root.addView(top)

        // hesaplar
        val hs = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, dp(4))
        }
        for (i in 0 until 5) {
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(10), dp(16), dp(10))
                minimumWidth = dp(100)
                background = box(CARD, if (i == active) ACCENT else BORDER)
                setOnClickListener {
                    if (edit) editAcc(i) else { active = i; saveCfg(); render() }
                }
            }
            tile.addView(tv(accs[i].name, 12f, SUB))
            tile.addView(tv(fmt(bals[i]), 15f, if (bals[i] < 0) DANGER else TEXT, true))
            row.addView(tile, lp(WRAP, WRAP).apply { setMargins(0, 0, dp(8), 0) })
        }
        hs.addView(row)
        root.addView(hs)

        // şablonlar
        root.addView(tv("Şablonlar", 13f, SUB, true).apply { setPadding(dp(2), dp(18), 0, dp(6)) })
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
                setPadding(dp(8), dp(22), dp(8), dp(22))
                background = box(CARD, BORDER)
                setOnClickListener {
                    if (edit) {
                        editTpl(idx)
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
            c.addView(tv(t.label, 18f, TEXT, true).apply { gravity = Gravity.CENTER })
            c.addView(tv(sgn(t.amt), 14f, if (t.amt < 0) DANGER else ACCENT))
            cell(c)
        }
        val add = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(22), dp(8), dp(22))
            background = box(BG, BORDER)
            setOnClickListener { editTpl(-1) }
        }
        add.addView(tv("+ Şablon", 16f, SUB, true))
        cell(add)
        root.addView(grid)

        // geçmiş
        root.addView(tv("Son işlemler", 13f, SUB, true).apply { setPadding(dp(2), dp(18), 0, dp(6)) })
        if (txs.isEmpty()) root.addView(tv("Henüz işlem yok", 14f, SUB))
        val df = SimpleDateFormat("dd.MM HH:mm", TR)
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
            r.addView(tv("Geri al", 13f, SUB).apply {
                setPadding(dp(14), dp(10), dp(6), dp(10))
                setOnClickListener { undo(tx) }
            })
            root.addView(r, lp(MATCH, WRAP).apply { setMargins(0, dp(4), 0, 0) })
        }

        // ayarlar (düzenleme modunda)
        if (edit) {
            val sw = Switch(this).apply {
                text = "Şablona dokununca kaydet ve uygulamayı kapat"
                setTextColor(TEXT)
                isChecked = autoClose
                setPadding(0, dp(20), 0, dp(8))
                setOnCheckedChangeListener { _, v -> autoClose = v; saveCfg() }
            }
            root.addView(sw)
        }

        return ScrollView(this).apply {
            setBackgroundColor(BG)
            addView(root)
        }
    }

    // ---------- diyaloglar ----------
    private fun editAcc(i: Int) {
        val a = accs[i]
        val cur = balOf(i)
        val n = field("İsim", a.name, InputType.TYPE_CLASS_TEXT)
        val bf = field("Güncel bakiye", raw(cur), NUM)
        AlertDialog.Builder(this)
            .setTitle("Hesap " + (i + 1))
            .setView(form(n, bf))
            .setPositiveButton("Kaydet") { _, _ ->
                val nm = n.text.toString().trim()
                if (nm.isNotEmpty()) a.name = nm
                val v = parse(bf.text.toString())
                if (v != null) a.open = v - (cur - a.open)
                saveCfg(); render()
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    private fun editTpl(idx: Int) {
        val t = if (idx >= 0) tpls[idx] else null
        val l = field("İsim (örn: Otobüs)", t?.label ?: "", InputType.TYPE_CLASS_TEXT)
        val a = field("Tutar (örn: 10)", if (t != null) raw(Math.abs(t.amt)) else "", NUM)
        val cb = CheckBox(this).apply {
            text = "Gelir (bakiyeye eklenir)"
            setTextColor(TEXT)
            isChecked = t != null && t.amt > 0
        }
        val b = AlertDialog.Builder(this)
            .setTitle(if (t == null) "Yeni şablon" else "Şablonu düzenle")
            .setView(form(l, a, cb))
            .setPositiveButton("Kaydet") { _, _ ->
                val lb = l.text.toString().trim()
                val am = parse(a.text.toString())
                if (lb.isEmpty() || am == null || am == 0.0) {
                    Toast.makeText(this, "İsim ve tutar gir", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val v = if (cb.isChecked) Math.abs(am) else -Math.abs(am)
                if (t == null) tpls.add(Tpl(lb, v)) else { t.label = lb; t.amt = v }
                saveCfg(); render()
            }
            .setNegativeButton("Vazgeç", null)
        if (t != null) b.setNeutralButton("Sil") { _, _ -> tpls.removeAt(idx); saveCfg(); render() }
        b.show()
    }
}
