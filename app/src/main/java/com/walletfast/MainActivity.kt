package com.walletfast

import android.annotation.SuppressLint
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.InputFilter
import android.text.InputType
import android.util.Base64
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.AccelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID


class MainActivity : Activity() {

    // id is stable and never reused: transactions and templates point to the id, not to the list position
    private class Acc(
        val id: Int,
        var name: String,
        var open: Double,
        var color: Int = 0,        // 0 = default card look
        var img: String? = null    // file name inside filesDir/tpl
    )

    private class Tpl(
        var label: String,
        var amt: Double,
        var acc: Int = -1,         // account id, -1 = follow the active account
        var color: Int = 0,        // 0 = default card look
        var img: String? = null    // file name inside filesDir/tpl
    )

    private class Tx(val id: String, val t: Long, val acc: Int, val label: String, val amt: Double)
    private class Style(var color: Int, var img: String?)
    private class Look(val bmp: Bitmap?, val styled: Boolean, val light: Boolean, val tc: Int, val sub: Int)

    // everything the config JSON holds (also used by backups)
    private class Cfg(
        val accs: List<Acc>, val tpls: List<Tpl>, val active: Int,
        val nid: Int, val autoClose: Boolean, val vibrate: Boolean
    )

    // a parsed backup file, checked and ready to be applied
    private class Backup(val cfg: Cfg, val txs: List<Tx>, val images: Map<String, ByteArray>, val exported: Long)

    // center-crop bitmap drawable (no intrinsic size, so it never changes the card's height)
    private class CropDrawable(private val b: Bitmap) : Drawable() {
        private val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        override fun draw(c: Canvas) {
            val w = bounds.width().toFloat()
            val h = bounds.height().toFloat()
            if (w <= 0f || h <= 0f) return
            val s = Math.max(w / b.width, h / b.height)
            val dx = (w - b.width * s) / 2f
            val dy = (h - b.height * s) / 2f
            c.save()
            c.clipRect(bounds)
            c.translate(bounds.left + dx, bounds.top + dy)
            c.scale(s, s)
            c.drawBitmap(b, 0f, 0f, p)
            c.restore()
        }

        override fun setAlpha(a: Int) { p.alpha = a }
        override fun setColorFilter(cf: ColorFilter?) { p.colorFilter = cf }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun getOpacity(): Int = PixelFormat.OPAQUE
    }

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

    // template / account color palette (white text stays readable on all of them)
    private val PALETTE = intArrayOf(
        0xFF4A90D9.toInt(), // light blue
        0xFF2D5BA8.toInt(), // blue
        0xFF1B2A5C.toInt(), // navy
        0xFF7A3FA0.toInt(), // purple
        0xFFB5476B.toInt(), // pink
        0xFFB98A1F.toInt(), // amber
        0xFF2A8C9A.toInt(), // teal
        0xFF5A6670.toInt(), // slate
        0xFF2E3237.toInt()  // dark gray (near black)
    )
    private val WHITE = 0xFFFFFFFF.toInt()
    private val WHITE_SUB = 0xCCFFFFFF.toInt()
    private val MENU_ICON = 0xFF8A958D.toInt()   // settings icon, drawn at 80% opacity

    // animation: very short and subtle, GPU-friendly property animations only
    private val DUR = 120L
    private val DOWN_DUR = 80L
    private val ease = DecelerateInterpolator()
    private val easeIO = AccelerateDecelerateInterpolator()
    private val UNDO_DUR = 60L
    private val easeOut = AccelerateInterpolator()

    private val accs = ArrayList<Acc>()
    private val tpls = ArrayList<Tpl>()
    private val txs = ArrayList<Tx>()
    private var nid = 2            // next free account id
    private var active = 0         // id of the active account
    private var tab = 0            // 0 = Templates (home), 1 = Accounts
    private var screen = 0         // 0 = main (tabs), 1 = Settings, 2 = Import & Export (full-screen pages)
    private var picking = false    // a file picker is open: don't reset the screen when we come back
    private var enter = 0          // transition played by the next render(): 1 = Settings opening, 2 = Settings closing, 3 = quick fade
    private var edit = false
    private var autoClose = false
    private var vibrate = true     // Vibration setting (Edit mode)
    private var lastTap = 0L
    private var lastTapIdx = -1    // which template was tapped last (for per-template cooldown)
    private var navAnim = false    // play the nav pill fade-in on the next render
    private var newTxId: String? = null // newest history row fades in once
    private var frame: FrameLayout? = null
    private var sheet: View? = null
    private lateinit var file: File
    private lateinit var fontReg: Typeface
    private lateinit var fontBold: Typeface

    // template / account images
    private val bmpCache = HashMap<String, Bitmap>()
    private var imgCb: ((String?) -> Unit)? = null
    private var onSheetClose: (() -> Unit)? = null

    // swipe tracking
    private var swX = 0f
    private var swY = 0f
    private var swT = 0L
    private var swArmed = false
    private var pullOpen = false
    private var pullClose = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        fontReg = resources.getFont(R.font.open_sans_regular)
        fontBold = resources.getFont(R.font.open_sans_bold)
        file = File(filesDir, "transactions.jsonl")
        migrateStrayFile()
        loadCfg()
        loadTx()
        buildFrame()
        render()
    }

    override fun onRestart() {
        super.onRestart()
        // coming back from our own file picker: stay where we are
        if (picking) {
            picking = false
            return
        }
        // App always comes back on the Templates tab
        if (sheet == null && (tab != 0 || edit || screen != 0)) {
            tab = 0
            edit = false
            screen = 0
            render()
        }
    }

    override fun onResume() {
        super.onResume()
        picking = false   // onRestart (if any) has already run; the picker is closed now
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when {
            sheet != null -> closeSheet()
            screen == 2 -> { screen = 1; enter = 3; render() }
            screen == 1 -> { screen = 0; enter = 2; render() }
            tab != 0 -> { tab = 0; navAnim = true; render() }
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
                swArmed = sheet == null && screen == 0
                // "Hitting the wall": Opening/Closing only works when the top wall
                val s = sv
                pullOpen = sheet == null && screen == 0 && !edit && s != null && !s.canScrollVertically(-1)
                pullClose = sheet == null && screen == 1 && s != null && !s.canScrollVertically(1)
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> {
                swArmed = false
                pullOpen = false
                pullClose = false
            }
        }
        val handled = super.dispatchTouchEvent(ev)
        if (ev.actionMasked == MotionEvent.ACTION_UP && swArmed) {
            swArmed = false
            val dx = ev.rawX - swX
            val dy = ev.rawY - swY
            val fast = ev.eventTime - swT < 600
            val far = Math.abs(dx) > dp(80).toFloat()
            val horizontal = Math.abs(dx) > Math.abs(dy) * 2f
            if (sheet == null && screen == 0 && fast && far && horizontal) {
                val to = if (dx < 0) 1 else 0   // Left -> Accounts, Right -> Templates
                if (to != tab) {
                    tab = to
                    navAnim = true
                    render()
                }
            }
        }
        if (ev.actionMasked == MotionEvent.ACTION_UP && (pullOpen || pullClose)) {
            val open = pullOpen
            val close = pullClose
            pullOpen = false
            pullClose = false
            val dx = ev.rawX - swX
            val dy = ev.rawY - swY
            val fast = ev.eventTime - swT < 700
            val far = Math.abs(dy) > dp(80).toFloat()
            val vertical = Math.abs(dy) > Math.abs(dx) * 2f
            val s = sv
            if (sheet == null && s != null && fast && far && vertical) {
                if (open && dy > 0f && screen == 0 && !edit && !s.canScrollVertically(-1)) {
                    screen = 1          // pull down -> Settings open (with same animation)
                    enter = 1
                    render()
                } else if (close && dy < 0f && screen == 1 && !s.canScrollVertically(1)) {
                    screen = 0          // push up -> Settings close
                    enter = 2
                    render()
                }
            }
        }
        return handled
    }

    // ---------- data ----------
    private fun defaults() {
        accs.clear(); tpls.clear()
        accs.add(Acc(0, "Account 1", 0.0))
        accs.add(Acc(1, "Account 2", 0.0))
        nid = 2
        active = 0
        tpls.add(Tpl("Bus", -10.0))
        tpls.add(Tpl("Cafeteria", -50.0))
    }

    private fun accOf(id: Int): Acc? {
        for (a in accs) if (a.id == id) return a
        return null
    }

    private fun activeAcc(): Acc = accOf(active) ?: accs[0]

    // first free "Account N" name
    private fun newAccName(): String {
        val used = accs.map { it.name }.toSet()
        var n = 1
        while ("Account $n" in used) n++
        return "Account $n"
    }

    private fun loadCfg() {
        val s = getSharedPreferences("cfg", MODE_PRIVATE).getString("cfg", null)
        if (s == null) { defaults(); return }
        try {
            applyCfg(parseCfg(JSONObject(s)))
        } catch (e: Exception) {
            defaults()
        }
    }

    // reads the config JSON (also the "cfg" part of a backup); throws if it is not usable
    private fun parseCfg(o: JSONObject): Cfg {
        val list = ArrayList<Acc>()
        val a = o.getJSONArray("accs")
        for (i in 0 until a.length()) {
            val x = a.getJSONObject(i)
            // old configs have no "id": the list position was the id back then
            list.add(
                Acc(
                    x.optInt("id", i),
                    x.getString("n"),
                    x.getDouble("o"),
                    x.optInt("col", 0),
                    x.optString("img", "").ifEmpty { null }
                )
            )
        }
        if (list.isEmpty()) throw IllegalArgumentException("no accounts")
        val ids = HashSet<Int>()
        var top = 0
        for (ac in list) {
            if (!ids.add(ac.id)) throw IllegalArgumentException("duplicate account id")
            if (ac.id > top) top = ac.id
        }
        val tl = ArrayList<Tpl>()
        val t = o.getJSONArray("tpls")
        for (i in 0 until t.length()) {
            val x = t.getJSONObject(i)
            val c = x.optInt("c", -1)
            tl.add(
                Tpl(
                    x.getString("l"),
                    x.getDouble("a"),
                    if (c in ids) c else -1,
                    x.optInt("col", 0),
                    x.optString("img", "").ifEmpty { null }
                )
            )
        }
        var act = o.optInt("active", 0)
        if (act !in ids) act = list[0].id
        return Cfg(
            list, tl, act,
            Math.max(o.optInt("nid", 0), top + 1),
            o.optBoolean("ac", false),
            o.optBoolean("vib", true)
        )
    }

    private fun applyCfg(c: Cfg) {
        accs.clear(); accs.addAll(c.accs)
        tpls.clear(); tpls.addAll(c.tpls)
        active = c.active
        nid = c.nid
        autoClose = c.autoClose
        vibrate = c.vibrate
    }

    private fun cfgJson(): JSONObject {
        val a = JSONArray()
        accs.forEach {
            a.put(
                JSONObject().put("id", it.id).put("n", it.name).put("o", it.open)
                    .put("col", it.color).put("img", it.img ?: "")
            )
        }
        val t = JSONArray()
        tpls.forEach {
            t.put(
                JSONObject().put("l", it.label).put("a", it.amt).put("c", it.acc)
                    .put("col", it.color).put("img", it.img ?: "")
            )
        }
        return JSONObject().put("accs", a).put("tpls", t).put("active", active)
            .put("nid", nid).put("ac", autoClose).put("vib", vibrate)
    }

    private fun saveCfg() {
        getSharedPreferences("cfg", MODE_PRIVATE).edit().putString("cfg", cfgJson().toString()).apply()
    }

    // One-time rescue: an earlier build of this version wrote to "telefon.jsonl" by mistake.
    // If that file exists, its lines are moved back into transactions.jsonl. Safe to delete later.
    private fun migrateStrayFile() {
        val stray = File(filesDir, "telefon.jsonl")
        if (!stray.exists()) return
        try {
            val txt = stray.readText()
            if (txt.isNotEmpty()) {
                if (file.exists() && file.length() > 0L) {
                    val last = file.readText().last()
                    if (last != '\n') file.appendText("\n")
                }
                file.appendText(if (txt.endsWith("\n")) txt else txt + "\n")
            }
            stray.delete()
        } catch (e: Exception) {
        }
    }

    private fun loadTx() {
        val m = LinkedHashMap<String, Tx>()
        var maxId = -1
        if (file.exists()) {
            file.forEachLine { line ->
                try {
                    val o = JSONObject(line)
                    when (o.getString("k")) {
                        "tx" -> {
                            val acc = o.getInt("acc")
                            if (acc > maxId) maxId = acc
                            m[o.getString("id")] = Tx(
                                o.getString("id"), o.getLong("t"),
                                acc, o.getString("l"), o.getDouble("a")
                            )
                        }
                        "undo" -> m.remove(o.getString("ref"))
                        else -> {}
                    }
                } catch (e: Exception) {
                }
            }
        }
        // never hand out an id that old lines in the file already use
        if (maxId >= nid) nid = maxId + 1
        txs.clear()
        for (x in m.values) if (accOf(x.acc) != null) txs.add(x)
    }

    private fun append(o: JSONObject) {
        try {
            file.appendText(o.toString() + "\n")
        } catch (e: Exception) {
            snack("Couldn't save entry")
        }
    }

    private fun addTx(label: String, amt: Double, acc: Int) {
        val tx = Tx(UUID.randomUUID().toString(), System.currentTimeMillis(), acc, label, amt)
        txs.add(tx)
        newTxId = tx.id
        append(JSONObject().put("k", "tx").put("id", tx.id).put("t", tx.t).put("acc", tx.acc).put("l", label).put("a", amt))
    }

    private fun undo(tx: Tx) {
        txs.remove(tx)
        append(JSONObject().put("k", "undo").put("ref", tx.id).put("t", System.currentTimeMillis()))
        render()
    }

    // balance of every account in one pass over the transactions (account id -> balance)
    private fun balMap(): HashMap<Int, Double> {
        val m = HashMap<Int, Double>()
        for (a in accs) m[a.id] = a.open
        for (x in txs) {
            val c = m[x.acc]
            if (c != null) m[x.acc] = c + x.amt
        }
        for (k in m.keys.toList()) m[k] = r2(m[k] ?: 0.0)
        return m
    }

    private fun balOf(a: Acc): Double = balMap()[a.id] ?: 0.0

    private fun totalOf(bals: Map<Int, Double>): Double {
        var s = 0.0
        for (a in accs) s += bals[a.id] ?: 0.0
        return r2(s)
    }

    // ---------- account delete ----------

    // Rewrites transactions.jsonl without any line that belongs to this account
    // (its transactions and the undo lines that point at them). Returns false if it failed.
    private fun purgeAccountTx(accId: Int): Boolean {
        if (!file.exists()) return true
        return try {
            val gone = HashSet<String>()
            file.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                try {
                    val o = JSONObject(line)
                    if (o.getString("k") == "tx" && o.getInt("acc") == accId) gone.add(o.getString("id"))
                } catch (e: Exception) {
                }
            }
            val tmp = File(filesDir, "transactions.jsonl.tmp")
            tmp.bufferedWriter().use { w ->
                file.forEachLine { line ->
                    if (line.isBlank()) return@forEachLine
                    var keep = true
                    try {
                        val o = JSONObject(line)
                        when (o.getString("k")) {
                            "tx" -> if (o.getInt("acc") == accId) keep = false
                            "undo" -> if (o.getString("ref") in gone) keep = false
                            else -> {}
                        }
                    } catch (e: Exception) {
                    }
                    if (keep) {
                        w.write(line)
                        w.newLine()
                    }
                }
            }
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun deleteAccount(a: Acc) {
        if (accs.size <= 1) return
        if (!purgeAccountTx(a.id)) {
            snack("Couldn't delete account")
            return
        }
        txs.removeAll { it.acc == a.id }
        // templates that charged this account go back to "Active account"
        tpls.forEach { if (it.acc == a.id) it.acc = -1 }
        a.img?.let { deleteImg(it) }
        accs.remove(a)
        if (active == a.id) active = accs[0].id
        saveCfg()
        render()
    }

    // asks first only if the account has any transaction; an empty account is deleted right away
    private fun requestDeleteAccount(a: Acc) {
        val n = txs.count { it.acc == a.id }
        if (n == 0) {
            deleteAccount(a)
            return
        }
        val what = if (n == 1) "1 transaction" else "$n transactions"
        openConfirm(
            "Delete account?",
            "\u201C${a.name}\u201D and its $what will be permanently deleted. This can't be undone.",
            "Delete"
        ) { deleteAccount(a) }
    }

    // ---------- template / account images ----------
    private fun imgDir() = File(filesDir, "tpl").apply { mkdirs() }

    private fun loadBmp(name: String): Bitmap? {
        bmpCache[name]?.let { return it }
        val b = BitmapFactory.decodeFile(File(imgDir(), name).path) ?: return null
        bmpCache[name] = b
        return b
    }

    private fun deleteImg(name: String) {
        bmpCache.remove(name)
        try {
            File(imgDir(), name).delete()
        } catch (e: Exception) {
        }
    }

    private fun pickImage(cb: (String?) -> Unit) {
        imgCb = cb
        val i = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, 77)
        } catch (e: Exception) {
            imgCb = null
            snack("No gallery app found")
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        when (requestCode) {
            77 -> {                                   // photo for a template / account
                val cb = imgCb
                imgCb = null
                if (resultCode != RESULT_OK || uri == null || cb == null) return
                cb(importImage(uri))
            }
            78 -> if (resultCode == RESULT_OK && uri != null) writeBackup(uri)   // export
            79 -> if (resultCode == RESULT_OK && uri != null) readBackup(uri)    // import
        }
    }

    // downscale (~700px), fix EXIF rotation, save as JPEG inside the app (data stays on the phone)
    private fun importImage(uri: Uri): String? {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var s = 1
            while (bounds.outWidth / (s * 2) >= 700 && bounds.outHeight / (s * 2) >= 700) s *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = s }
            var bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                ?: return null
            val ori = try {
                contentResolver.openInputStream(uri)?.use {
                    ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)
                } ?: 1
            } catch (e: Exception) {
                1
            }
            val deg = when (ori) {
                6 -> 90f
                3 -> 180f
                8 -> 270f
                else -> 0f
            }
            if (deg != 0f) {
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(deg) }, true)
            }
            val name = "t_" + UUID.randomUUID().toString() + ".jpg"
            File(imgDir(), name).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            bmpCache[name] = bmp
            return name
        } catch (e: Exception) {
            snack("Couldn't load image")
            return null
        }
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

    // card with a photo: cropped image + dark scrim so the text stays readable
    private fun imgBg(b: Bitmap, stroke: Int = 0): Drawable {
        val scrim = GradientDrawable().apply {
            setColor(0x8C000000.toInt())
            cornerRadius = dp(14).toFloat()
            if (stroke != 0) setStroke(dp(2), stroke)
        }
        return RippleDrawable(
            ColorStateList.valueOf(0x26FFFFFF),
            LayerDrawable(arrayOf(CropDrawable(b), scrim)),
            null
        )
    }

    // perceived brightness: true for colors like the soft white, where dark text is needed
    private fun isLight(c: Int): Boolean {
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        return (0.299 * r + 0.587 * g + 0.114 * b) / 255.0 > 0.62
    }

    // text colors for a card that has a color or a photo
    private fun lookOf(color: Int, img: String?): Look {
        val bmp = img?.let { loadBmp(it) }
        val styled = bmp != null || color != 0
        val light = bmp == null && color != 0 && isLight(color)
        val tc = if (light) BG else if (styled) WHITE else TEXT
        val sub = if (light) 0xAA121613.toInt() else if (styled) WHITE_SUB else SUB
        return Look(bmp, styled, light, tc, sub)
    }

    private val roundOutline = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, dp(14).toFloat())
        }
    }

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
        if (!vibrate) return
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

    private var sv: ScrollView? = null
    private var navHost: FrameLayout? = null
    private var shownKey = -1

    // the frame, scroll area and nav slot are built once and stay on screen
    private fun buildFrame() {
        val f = FrameLayout(this).apply { setBackgroundColor(BG) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val s = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        val nav = FrameLayout(this)
        col.addView(s, lp(MATCH, 0, 1f))
        col.addView(nav, lp(MATCH, WRAP))
        f.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))
        sv = s
        navHost = nav
        frame = f
        setContentView(f)
    }

    // only the content is rebuilt, so the scroll position stays where it was
    private fun render() {
        if (sheet != null) closeSheet()
        val s = sv ?: return
        s.removeAllViews()
        val v = when (screen) {
            1 -> buildSettings()
            2 -> buildBackup()
            else -> if (tab == 0) buildTemplates() else buildAccounts()
        }
        s.addView(v)
        // the bottom nav belongs to the main screen only, Settings pages are full screen
        navHost?.visibility = if (screen == 0) View.VISIBLE else View.GONE
        val key = if (screen == 0) tab else 10 + screen
        if (shownKey != key) {          // screen changed: start at the top, refresh the nav
            s.scrollTo(0, 0)
            if (screen == 0) {
                navHost?.removeAllViews()
                navHost?.addView(buildNav())
            }
            shownKey = key
        }
        when (enter) {
            1, 2 -> cascadeIn(v)
            3 -> fadeIn(v)
        }
        enter = 0
    }

    // page content drops in below the header, one block after the other; the arrow in the header stays put
    private fun cascadeIn(v: View) {
        val g = v as? ViewGroup ?: return
        for (i in 0 until g.childCount) {
            val first = g.getChildAt(i)
            // first row is the header: only its text column moves, the icon does not
            val t = if (i == 0 && first is ViewGroup && first.childCount > 0) first.getChildAt(0) else first
            t.alpha = 0f
            t.translationY = -dp(14).toFloat()
            t.animate().alpha(1f).translationY(0f)
                .setStartDelay(Math.min(i, 5) * 18L)
                .setDuration(200L).setInterpolator(ease).start()
        }
    }

    private fun fadeIn(v: View) {
        v.alpha = 0f
        v.animate().alpha(1f).setDuration(DUR).setInterpolator(ease).start()
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

    // header row shared by the main screen and the Settings page, so the icon sits in exactly the same spot:
    // the arrow points down on the main screen and up while Settings is open, and turns when the page changes
    private fun topRow(root: LinearLayout, col: View, settingsOpen: Boolean) {
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(col, lp(0, WRAP, 1f))
        if (edit && !settingsOpen) {
            top.addView(tv("Done", 15f, ACCENT, true).apply {
                setPadding(dp(12), dp(10), dp(4), dp(10))
                setOnClickListener { edit = false; render() }
            })
        } else {
            val btn = FrameLayout(this).apply {
                setOnClickListener {
                    if (settingsOpen) {
                        screen = 0
                        enter = 2
                    } else {
                        screen = 1
                        enter = 1
                    }
                    render()
                }
            }
            val to = if (settingsOpen) 180f else 0f
            val from = when (enter) {
                1 -> 0f
                2 -> 180f
                else -> to
            }
            val ic = ImageView(this).apply {
                setImageResource(R.drawable.ic_chevron_down)
                setColorFilter(MENU_ICON)
                alpha = 0.8f
                rotation = from
            }
            if (from != to) ic.post { ic.animate().rotation(to).setDuration(220L).setInterpolator(easeIO).start() }
            btn.addView(ic, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))
            pressFx(btn, 0.88f)
            top.addView(btn, lp(dp(48), dp(48)).apply { setMargins(0, 0, -dp(8), 0) })
        }
        root.addView(top)
    }

    private fun header(root: LinearLayout, total: Double, hint: String) {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        col.addView(tv("Total Balance", 15f, SUB))
        col.addView(tv(fmt(total), 46f, if (total < 0) DANGER else TEXT, true))
        topRow(root, col, false)
        if (edit) root.addView(tv(hint, 12f, SUB).apply { setPadding(dp(2), dp(6), 0, 0) })
    }

    // ---------- drag to reorder (edit mode) ----------
    // Long-press a template card, drag it over another card and release to move it there.
    @SuppressLint("ClickableViewAccessibility")
    private fun dragSort(card: View, from: Int, cards: List<View>) {
        val s = sv ?: return
        var dragging = false
        var curX = 0f
        var curY = 0f
        var startX = 0f
        var startY = 0f
        var startScroll = 0
        var target = from
        var speed = 0
        var ticking = false

        // translation is kept in content space, so the card stays under the finger while auto-scrolling
        fun follow() {
            card.translationX = curX - startX
            card.translationY = (curY - startY) + (s.scrollY - startScroll)
        }

        fun hit(): Int {
            val cx = card.left + card.width / 2f + card.translationX
            val cy = card.top + card.height / 2f + card.translationY
            for (i in cards.indices) {
                val v = cards[i]
                if (cx >= v.left && cx <= v.right && cy >= v.top && cy <= v.bottom) return i
            }
            return target
        }

        fun mark(i: Int, on: Boolean) {
            if (i == from) return
            val sc = if (on) 0.94f else 1f
            cards[i].animate().alpha(if (on) 0.55f else 1f).scaleX(sc).scaleY(sc)
                .setDuration(DUR).setInterpolator(ease).start()
        }

        fun update() {
            follow()
            val t = hit()
            if (t != target) {
                mark(target, false)
                target = t
                mark(target, true)
            }
        }

        val tick = object : Runnable {
            override fun run() {
                if (!dragging || speed == 0) { ticking = false; return }
                s.scrollBy(0, speed)
                update()
                card.postOnAnimation(this)
            }
        }

        // finger near the top/bottom edge of the scroll area -> scroll
        fun edges() {
            val loc = IntArray(2)
            s.getLocationOnScreen(loc)
            val zone = dp(90)
            speed = when {
                curY < loc[1] + zone -> -dp(6)
                curY > loc[1] + s.height - zone -> dp(6)
                else -> 0
            }
            if (speed != 0 && !ticking) {
                ticking = true
                card.postOnAnimation(tick)
            }
        }

        fun finish(commit: Boolean) {
            mark(target, false)
            val t = if (commit) target else from
            if (t != from) {
                val tc = cards[t]
                card.animate()
                    .translationX((tc.left - card.left).toFloat())
                    .translationY((tc.top - card.top).toFloat())
                    .scaleX(1f).scaleY(1f)
                    .setDuration(DUR).setInterpolator(ease)
                    .withEndAction {
                        val item = tpls.removeAt(from)
                        tpls.add(t, item)
                        lastTapIdx = -1
                        saveCfg()
                        render()
                    }.start()
            } else {
                card.animate().translationX(0f).translationY(0f).scaleX(1f).scaleY(1f)
                    .setDuration(DUR).setInterpolator(ease)
                    .withEndAction { card.translationZ = 0f }
                    .start()
            }
        }

        card.isHapticFeedbackEnabled = false   // we use our own Vibration setting (buzz)
        card.setOnLongClickListener {
            dragging = true
            swArmed = false                    // a horizontal drag must not switch tabs
            startX = curX
            startY = curY
            startScroll = s.scrollY
            target = from
            card.parent?.requestDisallowInterceptTouchEvent(true)
            (card.parent as? ViewGroup)?.let { g ->
                g.clipChildren = false
                (g.parent as? ViewGroup)?.clipChildren = false
            }
            card.translationZ = dp(12).toFloat()
            card.animate().scaleX(1.06f).scaleY(1.06f).setDuration(DUR).setInterpolator(ease).start()
            buzz()
            true
        }

        card.setOnTouchListener { v, e ->
            curX = e.rawX
            curY = e.rawY
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(DOWN_DUR).setInterpolator(ease).start()
                MotionEvent.ACTION_MOVE ->
                    if (dragging) { update(); edges() }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    if (dragging) {
                        dragging = false
                        speed = 0
                        finish(e.actionMasked == MotionEvent.ACTION_UP)
                    } else {
                        v.animate().scaleX(1f).scaleY(1f).setDuration(DUR).setInterpolator(ease).start()
                    }
            }
            false
        }
    }

    // ---------- Templates tab ----------
    private fun buildTemplates(): View {
        val bals = balMap()
        val root = page()
        header(root, totalOf(bals), "Tap a template to edit it · hold and drag to reorder")

        // which account new entries go to
        val cur = activeAcc()
        val curBal = bals[cur.id] ?: 0.0
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
        acl.addView(tv(cur.name, 15f, TEXT, true))
        ac.addView(acl, lp(0, WRAP, 1f))
        ac.addView(tv(fmt(curBal), 15f, if (curBal < 0) DANGER else TEXT, true))
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
        val cards = ArrayList<View>()
        tpls.forEachIndexed { idx, t ->
            val bmp = t.img?.let { loadBmp(it) }
            val styled = bmp != null || t.color != 0
            val light = bmp == null && t.color != 0 && isLight(t.color)
            val tc = if (light) BG else if (styled) WHITE else TEXT
            val c = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(36), dp(8), dp(36))
                background = when {
                    bmp != null -> imgBg(bmp)
                    t.color != 0 -> tap(t.color)
                    else -> tap(CARD, BORDER)
                }
                if (bmp != null) {
                    clipToOutline = true
                    outlineProvider = roundOutline
                }
                setOnClickListener {
                    if (edit) {
                        openTplEditor(idx)
                    } else {
                        val now = System.currentTimeMillis()
                        // same template again = likely double tap (250 ms), another template = 100 ms
                        val gap = if (idx == lastTapIdx) 250 else 100
                        if (now - lastTap < gap) return@setOnClickListener
                        lastTap = now
                        lastTapIdx = idx
                        addTx(t.label, t.amt, accOf(t.acc)?.id ?: active)
                        buzz()
                        if (autoClose) quit() else render()
                    }
                }
            }
            cards.add(c)
            if (edit) {
                dragSort(c, idx, cards)
            } else {
                pressFx(c, 0.96f)
                c.isHapticFeedbackEnabled = false   // sistemin titreşimi yerine kendi Vibration ayarımız çalışsın
                c.setOnLongClickListener {
                    buzz()
                    openTplEditor(idx)
                    true
                }
            }
            c.addView(tv(t.label, 23f, tc, true).apply { gravity = Gravity.CENTER })
            val amtCol = when {
                light -> if (t.amt < 0) 0xFFC62828.toInt() else 0xFF0B7A55.toInt() // deeper tones on light cards
                t.amt < 0 -> DANGER
                else -> ACCENT
            }
            c.addView(tv(sgn(t.amt), 21f, amtCol, true).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(4), 0, 0)
            })
            val bound = if (t.acc >= 0) accOf(t.acc) else null
            if (bound != null) c.addView(tv(bound.name, 12f, if (light) 0xAA121613.toInt() else if (styled) WHITE_SUB else SUB).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(6), 0, 0)
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

        buildHistory(root)

        return root
    }

    // ---------- history: months and years ----------

    private class Tot(var exp: Double = 0.0, var inc: Double = 0.0)

    // folder key -> opened (true) or closed (false) by the user; lives until the app is closed
    private val fold = HashMap<String, Boolean>()
    private val shown = HashMap<Int, Int>()   // month key -> how many rows are visible
    private var foldAnim: String? = null   // folder whose arrow should turn on the next render
    private val PAGE = 15

    private fun totOf(list: List<Tx>): Tot {
        val t = Tot()
        for (x in list) {
            if (x.amt < 0) t.exp -= x.amt else t.inc += x.amt
        }
        t.exp = r2(t.exp)
        t.inc = r2(t.inc)
        return t
    }

    private fun sumLine(label: String, v: Double, color: Int): View {
        val l = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        l.addView(tv(label, 11f, SUB).apply { setPadding(0, 0, dp(6), 0) })
        l.addView(tv(fmt(v), 13f, if (v == 0.0) SUB else color, true))
        return l
    }

    // a month or year row: title + count on the left, Expenses / Incomes on the right, arrow at the end
    private fun folderRow(fk: String, title: String, sub: String, tot: Tot, open: Boolean, onClick: () -> Unit): View {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(12), dp(10))
            background = tap(CARD, BORDER)
            setOnClickListener { onClick() }
        }
        val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        l.addView(tv(title, 15f, TEXT, true))
        l.addView(tv(sub, 12f, SUB))
        r.addView(l, lp(0, WRAP, 1f))
        val s = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }
        s.addView(sumLine("Expenses", tot.exp, DANGER))
        s.addView(sumLine("Incomes", tot.inc, ACCENT))
        r.addView(s, lp(WRAP, WRAP))

        // the arrow of the folder that was just tapped starts from its old direction and turns
        val to = if (open) 180f else 0f
        val from = if (foldAnim == fk) (if (open) 0f else 180f) else to
        val ic = chev(SUB, from)
        if (from != to) ic.post { ic.animate().rotation(to).setDuration(220L).setInterpolator(easeIO).start() }
        r.addView(ic, lp(dp(20), dp(20)).apply { setMargins(dp(8), 0, 0, 0) })

        pressFx(r, 0.98f)
        return r
    }

    private fun txRow(tx: Tx, df: SimpleDateFormat): View {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(8), dp(8))
            background = box(CARD)
        }
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        info.addView(tv(tx.label, 15f, TEXT))
        info.addView(tv((accOf(tx.acc)?.name ?: "") + " · " + df.format(Date(tx.t)), 12f, SUB))
        r.addView(info, lp(0, WRAP, 1f))
        r.addView(tv(sgn(tx.amt), 15f, if (tx.amt < 0) DANGER else ACCENT, true))
        r.addView(tv("Undo", 13f, SUB).apply {
            setPadding(dp(14), dp(10), dp(6), dp(10))
            setOnClickListener {
                isClickable = false // no double undo while the row slides out
                r.animate().alpha(0f).translationX(dp(40).toFloat()).setDuration(UNDO_DUR).setInterpolator(easeOut)
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
        return r
    }

    private fun buildHistory(root: LinearLayout) {
        root.addView(tv("Recent Activity", 13f, SUB, true).apply { setPadding(dp(2), dp(18), 0, dp(6)) })
        if (txs.isEmpty()) {
            root.addView(tv("No transactions yet", 14f, SUB))
            newTxId = null
            foldAnim = null
            return
        }
        val df = SimpleDateFormat("d MMM, HH:mm", Locale.US)
        val mf = SimpleDateFormat("MMMM yyyy", Locale.US)
        val cal = Calendar.getInstance()
        val curYear = cal.get(Calendar.YEAR)
        val nowKey = curYear * 12 + cal.get(Calendar.MONTH)

        // month key (year * 12 + month) -> its transactions; newest month first
        val months = LinkedHashMap<Int, ArrayList<Tx>>()
        for (x in txs.sortedByDescending { it.t }) {
            cal.timeInMillis = x.t
            months.getOrPut(cal.get(Calendar.YEAR) * 12 + cal.get(Calendar.MONTH)) { ArrayList() }.add(x)
        }

        fun month(key: Int, indent: Int) {
            val list = months[key] ?: return
            val fk = "m$key"
            val open = fold[fk] ?: (key == nowKey)   // the current month starts open, the others closed
            root.addView(
                folderRow(fk, mf.format(Date(list[0].t)), cnt(list.size, "transaction"), totOf(list), open) {
                    fold[fk] = !open
                    foldAnim = fk
                    if (open) shown.remove(key)       // closing resets it back to the first 15
                    render()
                },
                lp(MATCH, WRAP).apply { setMargins(indent, dp(4), 0, 0) }
            )
            if (open) {
                val lim = shown[key] ?: PAGE
                for (x in list.take(lim)) {
                    root.addView(txRow(x, df), lp(MATCH, WRAP).apply { setMargins(indent, dp(4), 0, 0) })
                }
                if (list.size > lim) {
                    val more = tv("Show more (${list.size - lim} left)", 13f, SUB, true).apply {
                        gravity = Gravity.CENTER
                        setPadding(dp(12), dp(12), dp(12), dp(12))
                        background = tap(KEY)
                        setOnClickListener {
                            shown[key] = Int.MAX_VALUE   // show everything in this month
                            render()
                        }
                    }
                    pressFx(more, 0.97f)
                    root.addView(more, lp(MATCH, WRAP).apply { setMargins(indent, dp(4), 0, 0) })
                } else if (list.size > PAGE) {
                    val less = tv("Show less", 13f, SUB, true).apply {
                        gravity = Gravity.CENTER
                        setPadding(dp(12), dp(12), dp(12), dp(12))
                        background = tap(KEY)
                        setOnClickListener {
                            fold[fk] = false             // close the whole month
                            foldAnim = fk                // its arrow turns back down
                            shown.remove(key)
                            render()
                        }
                    }
                    pressFx(less, 0.97f)
                    root.addView(less, lp(MATCH, WRAP).apply { setMargins(indent, dp(4), 0, 0) })
                }
            }
        }

        // this year's months stay on top level, months of earlier years go under a year folder
        val past = LinkedHashMap<Int, ArrayList<Int>>()
        for (k in months.keys) {
            val y = k / 12
            if (y >= curYear) month(k, 0) else past.getOrPut(y) { ArrayList() }.add(k)
        }
        for ((y, keys) in past) {
            val all = ArrayList<Tx>()
            for (k in keys) months[k]?.let { all.addAll(it) }
            val fk = "y$y"
            val open = fold[fk] ?: false
            root.addView(
                folderRow(fk, y.toString(), cnt(keys.size, "month"), totOf(all), open) {
                    fold[fk] = !open
                    foldAnim = fk
                    render()
                },
                lp(MATCH, WRAP).apply { setMargins(0, dp(4), 0, 0) }
            )
            if (open) for (k in keys) month(k, dp(14))
        }
        newTxId = null
        foldAnim = null
    }

    // ---------- Accounts tab ----------

    // one account row, used by the Accounts tab and by the account picker:
    // plain card, solid color, or photo with a dark scrim; the active account gets a green outline
    private fun accRow(
        a: Acc, bal: Double, sel: Boolean, plain: Int,
        minH: Int, vp: Int, balSize: Float, showEdit: Boolean
    ): LinearLayout {
        val lk = lookOf(a.color, a.img)
        val bmp = lk.bmp
        val accent = if (lk.light) 0xFF0B7A55.toInt() else ACCENT
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = minH
            setPadding(dp(16), dp(vp), dp(16), dp(vp))
            background = when {
                bmp != null -> imgBg(bmp, if (sel) ACCENT else 0)
                a.color != 0 -> tap(a.color, if (sel) ACCENT else 0)
                else -> tap(plain, if (sel) ACCENT else BORDER)
            }
            if (bmp != null) {
                clipToOutline = true
                outlineProvider = roundOutline
            }
        }
        val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        l.addView(tv(a.name, 16f, lk.tc, true))
        if (sel) l.addView(tv("Active", 12f, accent))
        r.addView(l, lp(0, WRAP, 1f))
        val bc = if (bal < 0) (if (lk.light) 0xFFC62828.toInt() else DANGER) else lk.tc
        r.addView(tv(fmt(bal), balSize, bc, true))
        if (showEdit) r.addView(tv("Edit", 13f, accent, true).apply { setPadding(dp(12), 0, 0, 0) })
        return r
    }

    private fun buildAccounts(): View {
        val bals = balMap()
        val root = page()
        header(root, totalOf(bals), "Tap an account to rename it, set its balance, change its look or delete it")

        root.addView(tv("Accounts", 13f, SUB, true).apply { setPadding(dp(2), dp(18), 0, dp(6)) })
        for (a in accs) {
            val r = accRow(a, bals[a.id] ?: 0.0, a.id == active, CARD, dp(68), 12, 18f, edit)
            r.setOnClickListener {
                if (edit) {
                    openAccEditor(a)
                } else {
                    active = a.id
                    saveCfg()
                    render()
                }
            }
            pressFx(r, 0.98f)
            root.addView(r, lp(MATCH, WRAP).apply { setMargins(0, dp(4), 0, dp(4)) })
        }

        val add = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            minimumHeight = dp(68)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = tap(BG, BORDER)
            setOnClickListener { openNewAccount() }
        }
        pressFx(add, 0.98f)
        add.addView(tv("+ New Account", 16f, SUB, true).apply { gravity = Gravity.CENTER })
        root.addView(add, lp(MATCH, WRAP).apply { setMargins(0, dp(4), 0, dp(4)) })
        return root
    }

    // ---------- Settings pages (full screen) ----------

    // the chevron icon; rotation turns it into a back arrow (90) or a forward arrow (-90)
    private fun chev(color: Int, rot: Float) = ImageView(this).apply {
        setImageResource(R.drawable.ic_chevron_down)
        setColorFilter(color)
        rotation = rot
    }

    // back arrow + title at the top of a full-screen page
    private fun pageTop(root: LinearLayout, title: String, back: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val b = FrameLayout(this).apply { setOnClickListener { back() } }
        b.addView(chev(TEXT, 90f), FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        pressFx(b, 0.88f)
        row.addView(b, lp(dp(44), dp(44)).apply { setMargins(-dp(8), 0, dp(4), 0) })
        row.addView(tv(title, 22f, TEXT, true))
        root.addView(row, lp(MATCH, WRAP).apply { setMargins(0, dp(4), 0, dp(10)) })
    }

    // tappable menu row with a forward arrow
    private fun menuRow(title: String, sub: String?, onClick: () -> Unit): View {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(64)
            setPadding(dp(16), dp(12), dp(14), dp(12))
            background = tap(CARD, BORDER)
            setOnClickListener { onClick() }
        }
        val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        l.addView(tv(title, 15f, TEXT, true))
        if (sub != null) l.addView(tv(sub, 12f, SUB).apply { setPadding(0, dp(2), 0, 0) })
        r.addView(l, lp(0, WRAP, 1f))
        r.addView(chev(SUB, -90f), lp(dp(20), dp(20)))
        pressFx(r, 0.98f)
        return r
    }

    private fun buildSettings(): View {
        val root = page()
        // same two-line header as the main screen, so the arrow stays exactly where it was tapped
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        col.addView(tv("Wallet Fast", 15f, SUB))
        col.addView(tv("Settings", 46f, TEXT, true))
        topRow(root, col, true)

        // edit mode (reorder / rename / delete) is entered from here
        root.addView(
            menuRow("Edit templates & accounts", "Reorder, rename, change look or delete") {
                edit = true
                screen = 0
                enter = 2
                render()
            },
            lp(MATCH, WRAP).apply { setMargins(0, dp(14), 0, 0) }
        )

        fun setting(label: String, on: Boolean, top: Int, change: (Boolean) -> Unit) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
                background = box(CARD, BORDER)
            }
            row.addView(tv(label, 14f, TEXT), lp(0, WRAP, 1f))
            row.addView(toggle(on, change), lp(dp(50), dp(28)))
            root.addView(row, lp(MATCH, WRAP).apply { setMargins(0, dp(top), 0, 0) })
        }
        setting("Save & close app on template tap", autoClose, 16) { autoClose = it; saveCfg() }
        setting("Vibration", vibrate, 8) { vibrate = it; saveCfg(); if (it) buzz() }

        // Import & Export is always the last item
        root.addView(
            menuRow("Import & Export", "Back up or restore your data") { screen = 2; enter = 3; render() },
            lp(MATCH, WRAP).apply { setMargins(0, dp(28), 0, 0) }
        )
        return root
    }

    private fun buildBackup(): View {
        val root = page()
        pageTop(root, "Import & Export") { screen = 1; enter = 3; render() }
        root.addView(
            tv(
                "A backup keeps your accounts, templates, transactions and photos in one file. " +
                        "Importing replaces everything that is on this phone right now.",
                13f, SUB
            ).apply { setPadding(dp(2), 0, dp(2), dp(14)) },
            lp(MATCH, WRAP)
        )
        root.addView(menuRow("Export data", "Save a backup file") { startExport() }, lp(MATCH, WRAP))
        root.addView(
            menuRow("Import data", "Restore from a backup file") { startImport() },
            lp(MATCH, WRAP).apply { setMargins(0, dp(8), 0, 0) }
        )
        return root
    }

    // ---------- backup: export / import ----------
    // File format: one JSON object {app, version, exported, cfg, tx[], images{name: base64}}.
    // Own format on purpose: it keeps templates, account looks and photos, which other apps can't hold.

    private val IMG_NAME = Regex("t_[A-Za-z0-9-]{1,64}\\.jpg")

    private fun cnt(n: Int, w: String) = if (n == 1) "1 $w" else "$n ${w}s"

    private fun launchPicker(i: Intent, code: Int) {
        picking = true
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, code)
        } catch (e: Exception) {
            picking = false
            snack("No file manager found")
        }
    }

    private fun startExport() {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "wallet-fast-backup-$date.json")
        }
        launchPicker(i, 78)
    }

    private fun startImport() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        launchPicker(i, 79)
    }

    private fun buildBackupJson(): String {
        val o = JSONObject()
            .put("app", "walletfast")
            .put("version", 1)
            .put("exported", System.currentTimeMillis())
            .put("cfg", cfgJson())
        // only live transactions: undone ones and their undo lines are not needed
        val ta = JSONArray()
        for (x in txs) {
            ta.put(JSONObject().put("id", x.id).put("t", x.t).put("acc", x.acc).put("l", x.label).put("a", x.amt))
        }
        o.put("tx", ta)
        val used = HashSet<String>()
        accs.forEach { a -> a.img?.let { used.add(it) } }
        tpls.forEach { t -> t.img?.let { used.add(it) } }
        val io = JSONObject()
        for (n in used) {
            val f = File(imgDir(), n)
            if (f.exists()) io.put(n, Base64.encodeToString(f.readBytes(), Base64.NO_WRAP))
        }
        o.put("images", io)
        return o.toString()
    }

    private fun writeBackup(uri: Uri) {
        try {
            val bytes = buildBackupJson().toByteArray(Charsets.UTF_8)
            contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                ?: throw java.io.IOException("no output stream")
            snack("Backup saved")
        } catch (e: Exception) {
            snack("Couldn't save backup")
        }
    }

    // checks the whole file first; throws if anything is wrong, nothing is changed on the phone
    private fun parseBackup(text: String): Backup {
        val o = JSONObject(text)
        if (o.optString("app") != "walletfast") throw IllegalArgumentException("not a Wallet Fast backup")
        if (o.optInt("version", 0) != 1) throw IllegalArgumentException("unknown version")
        val cfg = parseCfg(o.getJSONObject("cfg"))

        val all = HashMap<String, ByteArray>()
        val io = o.optJSONObject("images")
        if (io != null) {
            val keys = io.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                if (!IMG_NAME.matches(k)) continue          // only our own photo names are accepted
                all[k] = Base64.decode(io.getString(k), Base64.DEFAULT)
            }
        }
        // a look that points to a photo missing from the file falls back to the default look
        val imgs = HashMap<String, ByteArray>()
        for (a in cfg.accs) {
            val n = a.img
            if (n != null) {
                val bytes = all[n]
                if (bytes == null) a.img = null else imgs[n] = bytes
            }
        }
        for (t in cfg.tpls) {
            val n = t.img
            if (n != null) {
                val bytes = all[n]
                if (bytes == null) t.img = null else imgs[n] = bytes
            }
        }

        val ids = HashSet<Int>()
        for (a in cfg.accs) ids.add(a.id)
        val list = ArrayList<Tx>()
        val seen = HashSet<String>()
        val ta = o.optJSONArray("tx") ?: JSONArray()
        for (i in 0 until ta.length()) {
            val x = ta.getJSONObject(i)
            val id = x.getString("id")
            val acc = x.getInt("acc")
            if (acc !in ids || !seen.add(id)) continue
            list.add(Tx(id, x.getLong("t"), acc, x.getString("l"), x.getDouble("a")))
        }
        return Backup(cfg, list, imgs, o.optLong("exported", 0L))
    }

    private fun readBackup(uri: Uri) {
        val b = try {
            val size = try {
                contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            } catch (e: Exception) {
                -1L
            }
            if (size > 64L * 1024L * 1024L) {
                snack("File is too big")
                return
            }
            val text = contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: throw java.io.IOException("no input stream")
            parseBackup(text)
        } catch (e: Exception) {
            snack("This is not a valid Wallet Fast backup")
            return
        }
        val whenTxt = if (b.exported > 0L) {
            " from " + SimpleDateFormat("d MMM yyyy", Locale.US).format(Date(b.exported))
        } else {
            ""
        }
        openConfirm(
            "Import this backup?",
            "The file$whenTxt has ${cnt(b.cfg.accs.size, "account")}, ${cnt(b.cfg.tpls.size, "template")} " +
                    "and ${cnt(b.txs.size, "transaction")}. Everything that is on this phone now will be " +
                    "replaced. This can't be undone.",
            "Import"
        ) { applyBackup(b) }
    }

    private fun applyBackup(b: Backup) {
        try {
            // new transaction file goes to a temp file first, the old one stays until everything worked
            val tmp = File(filesDir, "transactions.jsonl.tmp")
            tmp.bufferedWriter().use { w ->
                for (x in b.txs) {
                    w.write(
                        JSONObject().put("k", "tx").put("id", x.id).put("t", x.t).put("acc", x.acc)
                            .put("l", x.label).put("a", x.amt).toString()
                    )
                    w.newLine()
                }
            }
            for ((name, bytes) in b.images) File(imgDir(), name).writeBytes(bytes)
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        } catch (e: Exception) {
            snack("Couldn't import")
            return
        }
        applyCfg(b.cfg)
        saveCfg()
        loadTx()
        // photos that nothing uses anymore
        val keep = HashSet<String>()
        accs.forEach { a -> a.img?.let { keep.add(it) } }
        tpls.forEach { t -> t.img?.let { keep.add(it) } }
        imgDir().listFiles()?.forEach { f -> if (f.name !in keep) f.delete() }
        bmpCache.clear()
        lastTapIdx = -1
        edit = false
        tab = 0
        screen = 0
        render()
        snack("Data imported")
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
            isClickable = true // taps on the sheet must not close the scrim
        }

        // handle
        sh.addView(
            View(this).apply { background = box(BORDER) },
            lp(dp(40), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; setMargins(0, 0, 0, dp(12)) }
        )

        sh.addView(tv("Select Account", 18f, TEXT, true).apply { setPadding(dp(2), 0, 0, dp(8)) })

        val bals = balMap()
        for (a in accs) {
            val r = accRow(a, bals[a.id] ?: 0.0, a.id == active, BG, dp(64), 10, 17f, false)
            r.setOnClickListener {
                active = a.id
                saveCfg()
                render() // render() also closes the sheet, we stay on the Templates screen
            }
            pressFx(r, 0.98f)
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

    // ---------- confirm sheet ----------
    private fun openConfirm(title: String, msg: String, okLabel: String, onOk: () -> Unit) {
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
            isClickable = true
        }
        sh.addView(
            View(this).apply { background = box(BORDER) },
            lp(dp(40), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; setMargins(0, 0, 0, dp(12)) }
        )
        sh.addView(tv(title, 18f, TEXT, true).apply { setPadding(dp(2), 0, 0, dp(6)) })
        sh.addView(tv(msg, 14f, SUB).apply { setPadding(dp(2), 0, dp(2), dp(14)) })

        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val cancelBtn = tv("Cancel", 16f, SUB, true).apply {
            gravity = Gravity.CENTER
            background = tap(KEY)
            setOnClickListener { closeSheet() }
        }
        pressFx(cancelBtn, 0.97f)
        btns.addView(cancelBtn, lp(0, dp(52), 1f).apply { setMargins(0, 0, dp(8), 0) })
        val okBtn = tv(okLabel, 16f, BG, true).apply {
            gravity = Gravity.CENTER
            background = tap(DANGER)
            setOnClickListener {
                closeSheet()
                onOk()
            }
        }
        pressFx(okBtn, 0.97f)
        btns.addView(okBtn, lp(0, dp(52), 1.5f))
        sh.addView(btns)

        scrim.addView(sh, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
        f.addView(scrim, FrameLayout.LayoutParams(MATCH, MATCH))
        sheet = scrim
    }

    // ---------- editors ----------

    private fun openAccEditor(a: Acc) {
        val cur = balOf(a)
        val st = Style(a.color, a.img)
        val origImg = a.img
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
            // the last remaining account can't be deleted
            onDelete = if (accs.size > 1) ({ requestDeleteAccount(a) }) else null,
            style = st
        ) { nm, v, _ ->
            if (nm.isNotEmpty()) a.name = nm
            a.open = v - (cur - a.open)
            // old photo is no longer used -> free the file
            if (origImg != null && origImg != st.img) deleteImg(origImg)
            a.color = st.color
            a.img = st.img
            saveCfg()
            render()
        }
    }

    private fun openNewAccount() {
        val st = Style(0, null)
        openEditor(
            title = "New Account",
            nameHint = "Account name",
            name = "",
            amountLabel = "Starting balance",
            amount = null,
            negLabel = "Negative",
            posLabel = "Positive",
            startOnName = true,
            requireName = false,
            requireAmount = false,
            onDelete = null,
            style = st,
            defaultNeg = false
        ) { nm, v, _ ->
            val id = nid++
            accs.add(Acc(id, if (nm.isEmpty()) newAccName() else nm, v, st.color, st.img))
            saveCfg()
            render()
        }
    }

    // Untitled, Untitled 1, Untitled 2 ... (skip = index of the template being edited, -1 for new)
    private fun untitled(skip: Int): String {
        val used = tpls.filterIndexed { i, _ -> i != skip }.map { it.label }.toSet()
        if ("Untitled" !in used) return "Untitled"
        var n = 1
        while ("Untitled $n" in used) n++
        return "Untitled $n"
    }

    private fun openTplEditor(idx: Int) {
        val isNew = idx < 0
        val st = if (isNew) Style(0, null) else Style(tpls[idx].color, tpls[idx].img)
        val origImg = if (isNew) null else tpls[idx].img
        openEditor(
            title = if (isNew) "New Template" else "Edit Template",
            nameHint = "Name",
            name = if (isNew) "" else tpls[idx].label,
            amountLabel = "Amount",
            amount = if (isNew) null else tpls[idx].amt,
            negLabel = "Expense",
            posLabel = "Income",
            startOnName = isNew,
            requireName = false,
            requireAmount = false,
            onDelete = if (isNew) null else ({
                origImg?.let { deleteImg(it) }
                tpls.removeAt(idx)
                saveCfg()
                render()
            }),
            accSel = if (isNew) -1 else tpls[idx].acc.let { if (accOf(it) != null) it else -1 },
            style = st
        ) { nm0, v, a ->
            val nm = if (nm0.isEmpty()) untitled(idx) else nm0
            // old photo is no longer used -> free the file
            if (origImg != null && origImg != st.img) deleteImg(origImg)
            if (isNew) {
                tpls.add(Tpl(nm, v, a, st.color, st.img))
            } else {
                val e = tpls[idx]
                e.label = nm
                e.amt = v
                e.acc = a
                e.color = st.color
                e.img = st.img
            }
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
        onSheetClose?.invoke()
        onSheetClose = null
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
        accSel: Int? = null,
        style: Style? = null,
        defaultNeg: Boolean = true,
        onSave: (String, Double, Int) -> Unit
    ) {
        val f = frame ?: return
        if (sheet != null) return

        var neg = if (amount == null) defaultNeg else amount < 0
        val buf = StringBuilder(if (amount == null) "" else raw(Math.abs(amount)))
        var pick = accSel ?: -1   // -1 = follow the active account

        // images picked while this editor is open; the ones that don't end up saved get deleted on close
        var saved = false
        val picked = ArrayList<String>()
        if (style != null) {
            onSheetClose = {
                picked.forEach { if (!(saved && it == style.img)) deleteImg(it) }
            }
        }

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

        // account chips: only shown in the template editor (chipIds holds the account id of each chip, -1 = active)
        val chips = ArrayList<TextView>()
        val chipIds = ArrayList<Int>()
        fun styleChips() {
            chips.forEachIndexed { n, c ->
                val on = chipIds[n] == pick
                c.setTextColor(if (on) ACCENT else SUB)
                c.background = box(if (on) BG else KEY, if (on) ACCENT else 0)
            }
        }
        if (accSel != null) {
            sh.addView(tv("Charged from", 12f, SUB).apply { setPadding(dp(2), dp(12), 0, dp(4)) })
            val chipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val opts = ArrayList<Pair<Int, String>>()
            opts.add(Pair(-1, "Active account"))
            for (a in accs) opts.add(Pair(a.id, a.name))
            for (o in opts) {
                val id = o.first
                val chip = tv(o.second, 13f, SUB, true).apply {
                    setPadding(dp(14), dp(9), dp(14), dp(9))
                    setOnClickListener { pick = id; styleChips() }
                }
                pressFx(chip, 0.96f)
                chips.add(chip)
                chipIds.add(id)
                chipRow.addView(chip, lp(WRAP, WRAP).apply { setMargins(0, 0, dp(6), 0) })
            }
            val hs = HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(chipRow)
            }
            sh.addView(hs, lp(MATCH, WRAP))
            styleChips()
        }

        // look: card color or photo (templates and accounts). Color and photo exclude each other.
        if (style != null) {
            sh.addView(tv("Look", 12f, SUB).apply { setPadding(dp(2), dp(12), 0, dp(4)) })
            val swatches = ArrayList<TextView>()
            val imgBtn = tv("", 13f, SUB, true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(14), 0, dp(14), 0)
            }
            fun styleLook() {
                swatches.forEachIndexed { n, v ->
                    val col = if (n == 0) 0 else PALETTE[n - 1]
                    val on = style.img == null && style.color == col
                    v.background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(if (col == 0) KEY else col)
                        setStroke(dp(2), if (on) TEXT else if (col == 0) BORDER else 0)
                    }
                }
                val has = style.img != null
                imgBtn.text = if (has) "Image ✓" else "Choose image"
                imgBtn.setTextColor(if (has) ACCENT else SUB)
                imgBtn.background = box(if (has) BG else KEY, if (has) ACCENT else 0)
            }
            val swRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            for (n in 0..PALETTE.size) {
                val col = if (n == 0) 0 else PALETTE[n - 1]
                val sw = tv(if (n == 0) "×" else "", 16f, SUB, true).apply {
                    gravity = Gravity.CENTER
                    setOnClickListener {
                        style.color = col
                        style.img = null
                        styleLook()
                    }
                }
                pressFx(sw, 0.92f)
                swatches.add(sw)
                swRow.addView(sw, lp(dp(34), dp(34)).apply { setMargins(0, 0, dp(8), 0) })
                if (n == 0) swRow.addView(imgBtn, lp(WRAP, dp(34)).apply { setMargins(0, 0, dp(8), 0) })
            }
            val hsLook = HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(swRow)
            }
            sh.addView(hsLook, lp(MATCH, WRAP))
            imgBtn.setOnClickListener {
                pickImage { fileName ->
                    if (fileName != null) {
                        picked.add(fileName)
                        style.img = fileName
                        style.color = 0
                        styleLook()
                    }
                }
            }
            pressFx(imgBtn, 0.96f)
            styleLook()
        }

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
            listOf("7", "8", "9"),
            listOf("4", "5", "6"),
            listOf("1", "2", "3"),
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
                saved = true
                closeSheet()
                onSave(nm, if (neg) -v else v, pick)
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