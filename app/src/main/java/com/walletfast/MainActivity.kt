package com.walletfast

import android.annotation.SuppressLint
import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
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
import android.graphics.Path
import android.view.animation.OvershootInterpolator
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
        var img: String? = null,   // file name inside filesDir/tpl
        var tplOnly: Boolean = false   // true = only templates can use it (hidden from Income / Expense / Transfer lists)
    )

    private class Tpl(
        var label: String,
        var amt: Double,
        var acc: Int = -1,
        var color: Int = 0,
        var img: String? = null,
        var ask: Int = 0,
        var cat: String = ""       // category id, "" = none
    )

    // to >= 0 means a transfer: amt (always positive) leaves account `acc` and arrives in account `to`
    private class Tx(val id: String, val t: Long, val acc: Int, val label: String, val amt: Double, val to: Int = -1, val cat: String = "")

    // a category the user made himself (built-in ones are in EXP_CATS / INC_CATS). kind: 0 = income, 1 = expense
    private class Cat(val id: String, var name: String, val kind: Int)
    private class Style(var color: Int, var img: String?)
    private class Look(val bmp: Bitmap?, val styled: Boolean, val light: Boolean, val tc: Int, val sub: Int)

    // everything the config JSON holds (also used by backups)
    private class Cfg(
        val accs: List<Acc>, val tpls: List<Tpl>, val active: Int,
        val nid: Int, val autoClose: Boolean, val vibrate: Boolean, val lang: String,
        val cats: List<Cat>
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

    private val BG = 0xFF070708.toInt()
    private val CARD = 0xFF131315.toInt()
    private val KEY = 0xFF1D1D20.toInt()
    private val TEXT = 0xFFF5F5F7.toInt()
    private val SUB = 0xFF8C8C93.toInt()
    private val ACCENT = 0xFF4ADE80.toInt()
    private val DANGER = 0xFFFF5A5F.toInt()
    private val BORDER = 0xFF27272B.toInt()   // artık kartlarda çizgi olarak çizilmiyor (box'a bak)
    private val GHOST = 0xFF2E2E33.toInt()    // sadece "+ New" butonlarının ince çizgisi
    private val PILL = 0x2E4ADE80          // ACCENT at ~18% opacity (subtle nav pill)
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
    private val MENU_ICON = 0xFF8C8C93.toInt()   // settings icon, drawn at 80% opacity

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
    private val cats = ArrayList<Cat>()    // categories made by the user
    private var nid = 2            // next free account id
    private var active = 0         // id of the active account
    private var tab = 0            // 0 = Templates (home), 1 = Accounts
    private var screen = 0         // 0 = main (tabs), 1 = Settings, 2 = Import & Export (full-screen pages)
    private var picking = false    // a file picker is open: don't reset the screen when we come back
    private var enter = 0          // transition played by the next render(): 1 = Settings opening, 2 = Settings closing, 3 = quick fade
    private var edit = false
    private var autoClose = false
    private var vibrate = true     // Vibration setting (Edit mode)
    private var lang = "tr"        // "tr" or "en", Turkish is the default

    private fun loc(): Locale = if (lang == "tr") Locale.forLanguageTag("tr-TR") else Locale.US

    // English text -> Turkish text. Anything not in this list is shown as it is.
    private val TR = mapOf(
        "Total Balance" to "Toplam Bakiye",
        "Active account" to "Aktif hesap",
        "Active" to "Aktif",
        "Templates" to "Şablonlar",
        "Accounts" to "Hesaplar",
        "Account" to "Hesap",
        "Template" to "Şablon",
        "+ New Template" to "+ Yeni Şablon",
        "+ New Account" to "+ Yeni Hesap",
        "Recent Activity" to "Son Hareketler",
        "No transactions yet" to "Henüz işlem yok",
        "Show less" to "Daha az göster",
        "Expenses" to "Giderler",
        "Incomes" to "Gelirler",
        "Undo" to "Geri al",
        "Edit" to "Düzenle",
        "Done" to "Bitti",
        "Delete" to "Sil",
        "Cancel" to "İptal",
        "Save" to "Kaydet",
        "Import" to "İçe aktar",
        "Name" to "Ad",
        "Amount" to "Tutar",
        "Look" to "Görünüm",
        "Charged from" to "Çekilecek hesap",
        "Expense" to "Gider",
        "Income" to "Gelir",
        "Negative" to "Eksi",
        "Positive" to "Artı",
        "Image ✓" to "Görsel ✓",
        "Choose image" to "Görsel seç",
        "Select Account" to "Hesap Seç",
        "Edit Account" to "Hesabı Düzenle",
        "New Account" to "Yeni Hesap",
        "Edit Template" to "Şablonu Düzenle",
        "New Template" to "Yeni Şablon",
        "Account name" to "Hesap adı",
        "Current balance" to "Mevcut bakiye",
        "Starting balance" to "Başlangıç bakiyesi",
        "Enter a name" to "Bir ad gir",
        "Enter an amount" to "Bir tutar gir",
        "Note (optional)" to "Not (isteğe bağlı)",
        "From" to "Nereden",
        "To" to "Nereye",
        "Transfer needs two accounts" to "Transfer için iki hesap gerekir",
        "Transaction" to "İşlem",
        "Category" to "Kategori",
        "Category name" to "Kategori adı",
        "Date" to "Tarih",
        "Today" to "Bugün",
        "Yesterday" to "Dün",
        "Edit Transaction" to "İşlemi Düzenle",
        "Template only" to "Sadece şablon",
        "Hidden from the Income and Expense lists" to "Gelir ve Gider listelerinde görünmez",
        "Template only account" to "Sadece şablon hesabı",
        "Ask amount on tap" to "Dokununca tutarı sor",
        "Type the price every time, e.g. coffee or cigarettes" to "Fiyatı her seferinde gir, örn. kahve veya sigara",
        "Default amount (optional)" to "Varsayılan tutar (isteğe bağlı)",
        "+ New" to "+ Yeni",
        "Add" to "Ekle",
        "Groceries" to "Market",
        "Food & Drink" to "Yeme-İçme",
        "Transport" to "Ulaşım",
        "Shopping" to "Alışveriş",
        "Bills" to "Faturalar",
        "Subscriptions" to "Abonelikler",
        "Other" to "Diğer",
        "Salary" to "Maaş",
        "Scholarship / Allowance" to "Burs / Harçlık",
        "Extra income" to "Ek gelir",
        "Investment return" to "Yatırım getirisi",
        "Gift" to "Hediye",
        "Refund" to "İade",
        "Settings" to "Ayarlar",
        "Reorder categories" to "Kategorileri sırala",
        "Change the order of your categories" to "Kategorilerinin sırasını değiştir",
        "Hold a category and drag it to move it" to "Taşımak için bir kategoriye basılı tutup sürükle",
        "Delete category?" to "Kategori silinsin mi?",
        "No categories" to "Kategori yok",
        "Language" to "Dil",
        "Vibration" to "Titreşim",
        "Save & close app on template tap" to "Şablona dokununca kaydet ve uygulamayı kapat",
        "Edit templates & accounts" to "Şablonları ve hesapları düzenle",
        "Reorder, rename, change look or delete" to "Sırala, yeniden adlandır, görünümü değiştir veya sil",
        "Import & Export" to "İçe ve Dışa Aktar",
        "Back up or restore your data" to "Verilerini yedekle veya geri yükle",
        "Export data" to "Verileri dışa aktar",
        "Save a backup file" to "Yedek dosyası kaydet",
        "Import data" to "Verileri içe aktar",
        "Restore from a backup file" to "Yedek dosyasından geri yükle",
        "A backup keeps your accounts, templates, transactions and photos in one file. Importing replaces everything that is on this phone right now." to
                "Yedek dosyası hesaplarını, şablonlarını, işlemlerini ve fotoğraflarını tek dosyada tutar. İçe aktarmak şu an telefondaki her şeyi değiştirir.",
        "Tap a template to edit it · hold and drag to reorder" to "Düzenlemek için bir şablona dokun · sıralamak için basılı tutup sürükle",
        "Tap an account to rename it, set its balance, change its look or delete it · hold and drag to reorder" to
                "Yeniden adlandırmak, bakiyesini ayarlamak, görünümünü değiştirmek veya silmek için bir hesaba dokun · sıralamak için basılı tutup sürükle",
        "Delete account?" to "Hesap silinsin mi?",
        "Import this backup?" to "Bu yedek içe aktarılsın mı?",
        "Couldn't save entry" to "Kayıt kaydedilemedi",
        "Couldn't delete account" to "Hesap silinemedi",
        "Delete month?" to "Ay silinsin mi?",
        "Couldn't delete month" to "Ay silinemedi",
        "Hold a month to delete it" to "Silmek için bir aya basılı tut",
        "No gallery app found" to "Galeri uygulaması bulunamadı",
        "Couldn't load image" to "Görsel yüklenemedi",
        "No file manager found" to "Dosya yöneticisi bulunamadı",
        "Backup saved" to "Yedek kaydedildi",
        "Couldn't save backup" to "Yedek kaydedilemedi",
        "File is too big" to "Dosya çok büyük",
        "This is not a valid Wallet Fast backup" to "Bu geçerli bir Wallet Fast yedeği değil",
        "Couldn't import" to "İçe aktarılamadı",
        "Data imported" to "Veriler içe aktarıldı",
        "transaction" to "işlem",
        "month" to "ay",
        "account" to "hesap",
        "template" to "şablon"
    )

    private fun s(en: String): String = if (lang == "tr") (TR[en] ?: en) else en
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
            over != null -> closeOver()
            sheet != null -> if (sheet === addMenu) closeAddMenu() else closeSheet()
            screen == 2 || screen == 3 -> { screen = 1; enter = 3; render() }
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
        cats.clear(); cats.addAll(builtinCats())
        accs.add(Acc(0, "${s("Account")} 1", 0.0))
        accs.add(Acc(1, "${s("Account")} 2", 0.0))
        nid = 2
        active = 0
        tpls.add(Tpl(if (lang == "tr") "Otobüs" else "Bus", -10.0))
        tpls.add(Tpl(if (lang == "tr") "Yemekhane" else "Cafeteria", -50.0))
    }

    private fun accOf(id: Int): Acc? {
        for (a in accs) if (a.id == id) return a
        return null
    }

    private fun activeAcc(): Acc = accOf(active) ?: accs[0]

    // first free "Account N" name
    private fun newAccName(): String {
        val used = accs.map { it.name }.toSet()
        val p = s("Account")
        var n = 1
        while ("$p $n" in used) n++
        return "$p $n"
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
                    x.optString("img", "").ifEmpty { null },
                    x.optBoolean("tpo", false)
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
        // at least one normal account must exist (quick add and the active account need it)
        if (list.all { it.tplOnly }) list[0].tplOnly = false
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
                    x.optString("img", "").ifEmpty { null },
                    x.optInt("ask", 0),
                    x.optString("cat", "")
                )
            )
        }
        val cl = ArrayList<Cat>()
        val ca = o.optJSONArray("cats")
        if (ca != null) {
            for (i in 0 until ca.length()) {
                try {
                    val x = ca.getJSONObject(i)
                    val cid = x.getString("id")
                    if (cid.isEmpty() || cl.any { it.id == cid }) continue
                    cl.add(Cat(cid, x.getString("n"), if (x.optInt("k", 1) == 0) 0 else 1))
                } catch (e: Exception) {
                }
            }
        }
        // older files hold only the user's own categories: the built-in ones are added in front of them
        if (o.optInt("cv", 0) < 1) cl.addAll(0, builtinCats())
        val known = HashSet<String>()
        for (c in cl) known.add(c.id)
        for (tp in tl) if (tp.cat !in known) tp.cat = ""
        var act = o.optInt("active", 0)
        if (act !in ids || list.any { it.id == act && it.tplOnly }) act = (list.firstOrNull { !it.tplOnly } ?: list[0]).id
        return Cfg(
            list, tl, act,
            Math.max(o.optInt("nid", 0), top + 1),
            o.optBoolean("ac", false),
            o.optBoolean("vib", true),
            o.optString("lang", "tr").let { if (it == "en") "en" else "tr" },
            cl
        )
    }

    private fun applyCfg(c: Cfg) {
        accs.clear(); accs.addAll(c.accs)
        tpls.clear(); tpls.addAll(c.tpls)
        active = c.active
        nid = c.nid
        autoClose = c.autoClose
        vibrate = c.vibrate
        lang = c.lang
        cats.clear(); cats.addAll(c.cats)
    }

    private fun cfgJson(): JSONObject {
        val a = JSONArray()
        accs.forEach {
            a.put(
                JSONObject().put("id", it.id).put("n", it.name).put("o", it.open)
                    .put("col", it.color).put("img", it.img ?: "").put("tpo", it.tplOnly)
            )
        }
        val t = JSONArray()
        tpls.forEach {
            t.put(
                JSONObject().put("l", it.label).put("a", it.amt).put("c", it.acc).put("ask", it.ask).put("cat", it.cat)
                    .put("col", it.color).put("img", it.img ?: "")
            )
        }
        return JSONObject().put("accs", a).put("tpls", t).put("active", active)
            .put("nid", nid).put("ac", autoClose).put("vib", vibrate).put("lang", lang).put("cv", 1).put("cats", catsJson())
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
                            val to = o.optInt("to", -1)
                            if (acc > maxId) maxId = acc
                            if (to > maxId) maxId = to
                            m[o.getString("id")] = Tx(
                                o.getString("id"), o.getLong("t"),
                                acc, o.getString("l"), o.getDouble("a"), to, o.optString("cat", "")
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
        for (x in m.values) if (accOf(x.acc) != null && (x.to < 0 || accOf(x.to) != null)) txs.add(x)
    }

    private fun append(o: JSONObject) {
        try {
            file.appendText(o.toString() + "\n")
        } catch (e: Exception) {
            snack("Couldn't save entry")
        }
    }

    private fun addTx(label: String, amt: Double, acc: Int, to: Int = -1, cat: String = "", t: Long = System.currentTimeMillis()) {
        val tx = Tx(UUID.randomUUID().toString(), t, acc, label, amt, to, cat)
        txs.add(tx)
        newTxId = tx.id
        val o = JSONObject().put("k", "tx").put("id", tx.id).put("t", tx.t).put("acc", tx.acc).put("l", label).put("a", amt)
        if (to >= 0) o.put("to", to)
        if (cat.isNotEmpty()) o.put("cat", cat)
        append(o)
    }

    private fun openTxEditor(tx: Tx) =
        openQuickAdd(if (tx.to >= 0) 2 else if (tx.amt > 0) 0 else 1, tx)

    // an edited transaction replaces its own line in transactions.jsonl (same id, so undo lines still point at it)
    private fun updateTx(nw: Tx): Boolean {
        if (!file.exists()) return false
        return try {
            val tmp = File(filesDir, "transactions.jsonl.tmp")
            tmp.bufferedWriter().use { w ->
                file.forEachLine { line ->
                    if (line.isBlank()) return@forEachLine
                    var out = line
                    try {
                        val o = JSONObject(line)
                        if (o.getString("k") == "tx" && o.getString("id") == nw.id) {
                            val n = JSONObject().put("k", "tx").put("id", nw.id).put("t", nw.t).put("acc", nw.acc)
                                .put("l", nw.label).put("a", nw.amt)
                            if (nw.to >= 0) n.put("to", nw.to)
                            if (nw.cat.isNotEmpty()) n.put("cat", nw.cat)
                            out = n.toString()
                        }
                    } catch (e: Exception) {
                    }
                    w.write(out)
                    w.newLine()
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
            if (x.to >= 0) {                       // transfer: out of one account, into the other
                val from = m[x.acc]
                val dest = m[x.to]
                if (from != null) m[x.acc] = from - x.amt
                if (dest != null) m[x.to] = dest + x.amt
            } else {
                val c = m[x.acc]
                if (c != null) m[x.acc] = c + x.amt
            }
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
                    if (o.getString("k") == "tx" && (o.getInt("acc") == accId || o.optInt("to", -1) == accId)) gone.add(o.getString("id"))
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
                            "tx" -> if (o.getInt("acc") == accId || o.optInt("to", -1) == accId) keep = false
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

    // the last normal (not template-only) account can't be deleted
    private fun canDelete(a: Acc): Boolean = accs.size > 1 && (a.tplOnly || accs.any { it !== a && !it.tplOnly })

    private fun deleteAccount(a: Acc) {
        if (!canDelete(a)) return
        if (!purgeAccountTx(a.id)) {
            snack("Couldn't delete account")
            return
        }
        txs.removeAll { it.acc == a.id || it.to == a.id }
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
        val n = txs.count { it.acc == a.id || it.to == a.id }
        if (n == 0) {
            deleteAccount(a)
            return
        }
        val what = cnt(n, "transaction")
        val msg = if (lang == "tr")
            "\u201C${a.name}\u201D ve içindeki $what kalıcı olarak silinecek. Bu işlem geri alınamaz."
        else
            "\u201C${a.name}\u201D and its $what will be permanently deleted. This can't be undone."
        openConfirm("Delete account?", msg, "Delete") { deleteAccount(a) }
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

    // Turkish: 500₺ (symbol after) / English: ₺500 (symbol before)
    private fun money(sign: String, body: String) =
        if (lang == "tr") "$sign$body₺" else "$sign₺$body"

    private fun fmt(n: Double) = money(if (n < 0) "-" else "", num(n))
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

    private fun tv(text: String, size: Float, color: Int, bold: Boolean = false, user: Boolean = false) = TextView(this).apply {
        this.text = if (user) text else s(text)
        textSize = size
        setTextColor(color)
        typeface = if (bold) fontBold else fontReg
    }

    private fun box(fill: Int, stroke: Int = 0) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(18).toFloat()
        if (stroke != 0 && stroke != BORDER) setStroke(dp(2), stroke)
    }

    private fun section(text: String) = tv(text, 11f, SUB, true).apply {
        this.text = this.text.toString().uppercase(loc())
        letterSpacing = 0.14f
        setPadding(dp(2), dp(22), 0, dp(8))
    }

    private fun tap(fill: Int, stroke: Int = 0): Drawable =
        RippleDrawable(ColorStateList.valueOf(0x26FFFFFF), box(fill, stroke), null)

    // varsayılan şablon kartı: çizgisiz, üstten alta çok hafif gradient
    private fun glass(): Drawable = RippleDrawable(
        ColorStateList.valueOf(0x26FFFFFF),
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF1B1B1E.toInt(), 0xFF111113.toInt()))
            .apply { cornerRadius = dp(18).toFloat() },
        null
    )

    // card with a photo: cropped image + dark scrim so the text stays readable
    private fun imgBg(b: Bitmap, stroke: Int = 0): Drawable {
        val scrim = GradientDrawable().apply {
            setColor(0x8C000000.toInt())
            cornerRadius = dp(18).toFloat()
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
            outline.setRoundRect(0, 0, view.width, view.height, dp(18).toFloat())
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
    // ---------- edit mode: wobble + delete cross ----------
    private val jig = HashMap<View, ObjectAnimator>()

    // iPhone-style wobble: tiny rotation back and forth (GPU property, no redraw)
    private fun jiggle(v: View, amp: Float) {
        stopJiggle(v)
        val sign = if (Math.random() < 0.5) -1f else 1f
        val a = ObjectAnimator.ofFloat(v, View.ROTATION, -amp * sign, amp * sign).apply {
            duration = 100L + (Math.random() * 50).toLong()
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = easeIO
        }
        jig[v] = a
        v.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(x: View) {}
            override fun onViewDetachedFromWindow(x: View) {
                x.removeOnAttachStateChangeListener(this)
                stopJiggle(x)
            }
        })
        a.start()
    }

    private fun stopJiggle(v: View) {
        jig.remove(v)?.cancel()
    }

    // small red circle with a white cross (top-right corner of a card in edit mode)
    private fun delBadge(onClick: () -> Unit): View {
        val host = FrameLayout(this)   // 32dp touch area around a 20dp circle
        val dot = object : View(this@MainActivity) {
            private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = WHITE
                style = Paint.Style.STROKE
                strokeWidth = dp(2).toFloat()
                strokeCap = Paint.Cap.ROUND
            }

            override fun onDraw(c: Canvas) {
                val m = width * 0.3f
                c.drawLine(m, m, width - m, height - m, p)
                c.drawLine(width - m, m, m, height - m, p)
            }
        }
        dot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(DANGER)
        }
        host.addView(dot, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
        host.setOnClickListener { onClick() }
        pressFx(host, 0.85f)
        return host
    }

    private fun deleteTpl(t: Tpl) {
        val i = tpls.indexOf(t)
        if (i < 0) return
        t.img?.let { deleteImg(it) }
        tpls.removeAt(i)
        lastTapIdx = -1
        saveCfg()
        render()
    }

    // ---------- month delete (edit mode, Recent Activity) ----------

    // rewrites transactions.jsonl without these transactions and the undo lines that point at them
    private fun purgeTx(ids: Set<String>): Boolean {
        if (!file.exists()) return true
        return try {
            val tmp = File(filesDir, "transactions.jsonl.tmp")
            tmp.bufferedWriter().use { w ->
                file.forEachLine { line ->
                    if (line.isBlank()) return@forEachLine
                    var keep = true
                    try {
                        val o = JSONObject(line)
                        when (o.getString("k")) {
                            "tx" -> if (o.getString("id") in ids) keep = false
                            "undo" -> if (o.getString("ref") in ids) keep = false
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

    private fun requestDeleteMonth(key: Int, title: String, list: List<Tx>) {
        val what = cnt(list.size, "transaction")
        val msg = if (lang == "tr")
            "\u201C$title\u201D içindeki $what kalıcı olarak silinecek. Hesap bakiyeleri değişmeyecek. Bu işlem geri alınamaz."
        else
            "The $what in \u201C$title\u201D will be permanently deleted. Account balances won't change. This can't be undone."
        val copy = list.toList()
        openConfirm("Delete month?", msg, "Delete") { deleteMonth(key, copy) }
    }
    private fun deleteMonth(key: Int, list: List<Tx>) {
        val ids = HashSet<String>()
        for (x in list) ids.add(x.id)
        if (!purgeTx(ids)) {
            snack("Couldn't delete month")
            return
        }
        // the history is gone but the money did not go anywhere:
        // the month's total is folded into each account's starting balance, so balances stay the same
        val delta = HashMap<Int, Double>()
        for (x in list) {
            if (x.to >= 0) {
                delta[x.acc] = (delta[x.acc] ?: 0.0) - x.amt
                delta[x.to] = (delta[x.to] ?: 0.0) + x.amt
            } else {
                delta[x.acc] = (delta[x.acc] ?: 0.0) + x.amt
            }
        }
        for (a in accs) a.open = r2(a.open + (delta[a.id] ?: 0.0))
        txs.removeAll { it.id in ids }
        fold.remove("m$key")
        shown.remove(key)
        saveCfg()
        render()
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
            FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = if (screen == 0 && !edit) dp(112) else dp(88) }
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
        setPadding(dp(18), dp(14), dp(18), dp(48))
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
        buildFab(f, nav)
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
            3 -> buildCatOrder()
            else -> if (tab == 0) buildTemplates() else buildAccounts()
        }
        s.addView(v)
        // the bottom nav belongs to the main screen only, Settings pages are full screen
        navHost?.visibility = if (screen == 0) View.VISIBLE else View.GONE
        fab?.visibility = if (screen == 0 && !edit) View.VISIBLE else View.GONE
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
        bar.addView(View(this), lp(dp(76), dp(1)))
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
        col.addView(tv(fmt(total), 46f, if (total < 0) DANGER else TEXT, true).apply { letterSpacing = -0.03f })
        topRow(root, col, false)
        if (edit) root.addView(tv(hint, 12f, SUB).apply { setPadding(dp(2), dp(6), 0, 0) })
    }

    // ---------- drag to reorder (edit mode) ----------
    // Long-press a template card, drag it over another card and release to move it there.
    @SuppressLint("ClickableViewAccessibility")
    private fun dragSort(card: View, from: Int, cards: List<View>, amp: Float, move: (Int, Int) -> Unit) {
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
                        move(from, t)
                        render()
                    }.start()
            } else {
                card.animate().translationX(0f).translationY(0f).scaleX(1f).scaleY(1f)
                    .setDuration(DUR).setInterpolator(ease)
                    .withEndAction { card.translationZ = 0f; jiggle(card, amp) }
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
            stopJiggle(card)   // the lifted card holds still
            card.animate().rotation(0f).scaleX(1.06f).scaleY(1.06f).setDuration(DUR).setInterpolator(ease).start()
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
        acl.addView(tv(cur.name, 15f, TEXT, true, user = true))
        ac.addView(acl, lp(0, WRAP, 1f))
        ac.addView(tv(fmt(curBal), 15f, if (curBal < 0) DANGER else TEXT, true))
        ac.addView(tv("  ›", 18f, SUB))
        root.addView(ac, lp(MATCH, WRAP).apply { setMargins(0, dp(14), 0, 0) })

        // templates
        root.addView(section("Templates"))
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
                    else -> glass()
                }
                if (bmp != null) {
                    clipToOutline = true
                    outlineProvider = roundOutline
                }
                setOnClickListener {
                    if (edit) {
                        openTplEditor(idx)
                    } else if (t.ask != 0) {
                        openAskAmount(t)
                    } else {
                        val now = System.currentTimeMillis()
                        // same template again = likely double tap (250 ms), another template = 100 ms
                        val gap = if (idx == lastTapIdx) 250 else 100
                        if (now - lastTap < gap) return@setOnClickListener
                        lastTap = now
                        lastTapIdx = idx
                        addTx(t.label, t.amt, accOf(t.acc)?.id ?: active, -1, t.cat)
                        buzz()
                        if (autoClose) quit() else render()
                    }
                }
            }
            // edit mode: the card sits inside a holder; the holder wobbles, drags and carries the red cross
            val item: View = if (edit) {
                c.isClickable = false
                val h = FrameLayout(this)
                h.addView(c, FrameLayout.LayoutParams(MATCH, WRAP))
                h.addView(
                    delBadge {
                        stopJiggle(h)
                        h.animate().alpha(0f).scaleX(0.8f).scaleY(0.8f)
                            .setDuration(DUR).setInterpolator(easeOut)
                            .withEndAction { deleteTpl(t) }.start()
                    },
                    FrameLayout.LayoutParams(dp(32), dp(32), Gravity.TOP or Gravity.END)
                )
                h.setOnClickListener { openTplEditor(idx) }
                cards.add(h)
                dragSort(h, idx, cards, 1.4f) { f, to ->
                    val m = tpls.removeAt(f)
                    tpls.add(to, m)
                    lastTapIdx = -1
                    saveCfg()
                }
                jiggle(h, 1.4f)
                h
            } else {
                cards.add(c)
                pressFx(c, 0.96f)
                c.isHapticFeedbackEnabled = false   // sistemin titreşimi yerine kendi Vibration ayarımız çalışsın
                c.setOnLongClickListener {
                    buzz()
                    openTplEditor(idx)
                    true
                }
                c
            }
            c.addView(tv(t.label, 20f, tc, true, user = true).apply { gravity = Gravity.CENTER })
            val amtCol = when {
                t.ask != 0 -> if (light) (if (t.ask == 1) 0xFFC62828.toInt() else 0xFF0B7A55.toInt()) else if (t.ask == 1) DANGER else ACCENT
                t.amt == 0.0 -> if (light) 0xAA121613.toInt() else if (styled) WHITE_SUB else SUB   // dimmer than the name
                light -> if (t.amt < 0) 0xFFC62828.toInt() else 0xFF0B7A55.toInt() // deeper tones on light cards
                t.amt < 0 -> DANGER
                else -> ACCENT
            }
            c.addView(tv(if (t.ask == 1) money("-", "?") else if (t.ask == 2) money("+", "?") else sgn(t.amt), 18f, amtCol, true).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(4), 0, 0)
            })
            val bound = if (t.acc >= 0) accOf(t.acc) else null
            if (bound != null) c.addView(tv(bound.name, 12f, if (light) 0xAA121613.toInt() else if (styled) WHITE_SUB else SUB, false, true).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(6), 0, 0)
            })
            cell(item)
        }

        // even count: slim full-width bar under the grid
        // odd count: square card that fills the empty slot on the bottom right
        val odd = tpls.size % 2 == 1
        val add = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            if (odd) setPadding(dp(8), dp(36), dp(8), dp(36))
            else setPadding(dp(8), dp(11), dp(8), dp(11))
            background = tap(BG, GHOST)
            setOnClickListener { openTplEditor(-1) }
        }
        pressFx(add, if (odd) 0.96f else 0.98f)
        val ap: GridLayout.LayoutParams
        if (odd) {
            add.addView(tv("+", 30f, SUB, true).apply { gravity = Gravity.CENTER })
            add.addView(tv("New Template", 14f, SUB, true).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(2), 0, 0)
            })
            // row weight = 1f -> the card stretches to the same height as the template card on its left
            ap = GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED, 1f),
                GridLayout.spec(GridLayout.UNDEFINED, 1, 1f)
            )
        } else {
            add.addView(tv("+ New Template", 14f, SUB, true).apply { gravity = Gravity.CENTER })
            ap = GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED),
                GridLayout.spec(GridLayout.UNDEFINED, 2, 1f)
            )
        }
        ap.width = 0
        ap.setMargins(dp(4), dp(4), dp(4), dp(4))
        grid.addView(add, ap)
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
            if (x.to >= 0) continue        // a transfer moves money between accounts: not an expense or an income
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
        val xfer = tx.to >= 0
        // quick-add entries can have an empty note: the title then falls back to Income / Expense / Transfer
        val cn = catName(tx.cat)
        // title: the note, else the category, else what kind of entry it is
        val title = when {
            tx.label.isNotEmpty() -> tx.label
            cn != null -> cn
            xfer -> s("Transfer")
            tx.amt < 0 -> s("Expense")
            tx.amt > 0 -> s("Income")
            else -> s("Transaction")
        }
        val tag = if (tx.label.isNotEmpty() && cn != null) cn + " · " else ""
        val from = accOf(tx.acc)?.name ?: ""
        val place = if (xfer) from + " → " + (accOf(tx.to)?.name ?: "") else from
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        info.addView(tv(title, 15f, TEXT, false, user = true))
        info.addView(tv(tag + place + " · " + df.format(Date(tx.t)), 12f, SUB, false, true))
        r.addView(info, lp(0, WRAP, 1f))
        r.addView(
            tv(
                if (xfer) fmt(tx.amt) else sgn(tx.amt), 15f,
                if (xfer) PURPLE else if (tx.amt == 0.0) SUB else if (tx.amt < 0) DANGER else ACCENT, true
            )
        )
        r.addView(tv("Undo", 13f, SUB).apply {
            setPadding(dp(14), dp(10), dp(6), dp(10))
            setOnClickListener {
                isClickable = false // no double undo while the row slides out
                r.animate().alpha(0f).translationX(dp(40).toFloat()).setDuration(UNDO_DUR).setInterpolator(easeOut)
                    .withEndAction { undo(tx) }
                    .start()
            }
        })
        // hold a row to edit it (same sheet as quick add, filled in)
        r.isHapticFeedbackEnabled = false
        r.setOnLongClickListener { buzz(); openTxEditor(tx); true }
        pressFx(r, 0.98f)
        // only the row that was just added fades in; the rest stay still
        if (tx.id == newTxId) {
            r.alpha = 0f
            r.translationY = -dp(8).toFloat()
            r.post { r.animate().alpha(1f).translationY(0f).setDuration(DUR).setInterpolator(ease).start() }
        }
        return r
    }

    private fun buildHistory(root: LinearLayout) {
        root.addView(section("Recent Activity"))
        if (txs.isEmpty()) {
            root.addView(tv("No transactions yet", 14f, SUB))
            newTxId = null
            foldAnim = null
            return
        }
        if (edit) root.addView(tv("Hold a month to delete it", 12f, SUB).apply { setPadding(dp(2), 0, 0, dp(4)) })
        val df = SimpleDateFormat("d MMM, HH:mm", loc())
        val mf = SimpleDateFormat("MMMM yyyy", loc())
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
            val title = mf.format(Date(list[0].t))
            val row = folderRow(fk, title, cnt(list.size, "transaction"), totOf(list), open) {
                fold[fk] = !open
                foldAnim = fk
                if (open) shown.remove(key)       // closing resets it back to the first 15
                render()
            }
            if (edit) {
                // edit mode: hold a month to delete everything in it
                row.isHapticFeedbackEnabled = false
                row.setOnLongClickListener {
                    buzz()
                    requestDeleteMonth(key, title, list)
                    true
                }
            }
            root.addView(row, lp(MATCH, WRAP).apply { setMargins(indent, dp(4), 0, 0) })
            if (open) {
                val lim = shown[key] ?: PAGE
                for (x in list.take(lim)) {
                    root.addView(txRow(x, df), lp(MATCH, WRAP).apply { setMargins(indent, dp(4), 0, 0) })
                }
                if (list.size > lim) {
                    val more = tv(if (lang == "tr") "Daha fazla göster (${list.size - lim} kaldı)" else "Show more (${list.size - lim} left)", 13f, SUB, true).apply {
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
        l.addView(tv(a.name, 16f, lk.tc, true, user = true))
        if (sel) l.addView(tv("Active", 12f, accent))
        else if (a.tplOnly) l.addView(tv("Template only", 12f, lk.sub))
        r.addView(l, lp(0, WRAP, 1f))
        val bc = if (bal < 0) (if (lk.light) 0xFFC62828.toInt() else DANGER) else lk.tc
        r.addView(tv(fmt(bal), balSize, bc, true))
        if (showEdit) r.addView(tv("Edit", 13f, accent, true).apply { setPadding(dp(12), 0, 0, 0) })
        return r
    }

    private fun buildAccounts(): View {
        val bals = balMap()
        val root = page()
        header(
            root, totalOf(bals),
            "Tap an account to rename it, set its balance, change its look or delete it · hold and drag to reorder"
        )

        root.addView(section("Accounts"))
        val rows = ArrayList<View>()
        accs.forEachIndexed { idx, a ->
            val r = accRow(a, bals[a.id] ?: 0.0, a.id == active, CARD, dp(68), 12, 18f, edit)
            r.setOnClickListener {
                if (a.tplOnly) {
                    snack("Template only account")   // can't be the active account
                } else {
                    active = a.id
                    saveCfg()
                    render()
                }
            }
            val item: View = if (edit) {
                r.isClickable = false
                val h = FrameLayout(this)
                h.addView(r, FrameLayout.LayoutParams(MATCH, WRAP))
                // the last normal account can't be deleted, so it gets no cross
                if (canDelete(a)) {
                    h.addView(
                        delBadge { requestDeleteAccount(a) },
                        FrameLayout.LayoutParams(dp(32), dp(32), Gravity.TOP or Gravity.END)
                    )
                }
                h.setOnClickListener { openAccEditor(a) }
                rows.add(h)
                dragSort(h, idx, rows, 0.6f) { f, to ->
                    val m = accs.removeAt(f)
                    accs.add(to, m)
                    saveCfg()
                }
                jiggle(h, 0.6f)
                h
            } else {
                rows.add(r)
                pressFx(r, 0.98f)
                r.isHapticFeedbackEnabled = false
                r.setOnLongClickListener {
                    buzz()
                    openAccEditor(a)
                    true
                }
                r
            }
            root.addView(item, lp(MATCH, WRAP).apply { setMargins(0, dp(4), 0, dp(4)) })
        }

        val add = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            minimumHeight = dp(68)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = tap(BG, GHOST)
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
        col.addView(tv("Settings", 46f, TEXT, true).apply { letterSpacing = -0.03f })
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

        root.addView(
            menuRow("Reorder categories", "Change the order of your categories") { screen = 3; enter = 3; render() },
            lp(MATCH, WRAP).apply { setMargins(0, dp(8), 0, 0) }
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
        // language: Türkçe / English
        val langRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(12), dp(10))
            background = box(CARD, BORDER)
        }
        langRow.addView(tv("Language", 14f, TEXT), lp(0, WRAP, 1f))
        val langSeg = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = box(BG)
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        fun langOpt(code: String, label: String) {
            val on = lang == code
            val o = tv(label, 13f, if (on) BG else SUB, true, true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = if (on) box(ACCENT) else null
                setOnClickListener {
                    if (lang != code) {
                        lang = code
                        saveCfg()
                        shownKey = -1      // rebuild the bottom bar too
                        render()
                    }
                }
            }
            pressFx(o, 0.96f)
            langSeg.addView(o)
        }
        langOpt("tr", "Türkçe")
        langOpt("en", "English")
        langRow.addView(langSeg)
        root.addView(langRow, lp(MATCH, WRAP).apply { setMargins(0, dp(8), 0, 0) })

        // Import & Export is always the last item
        root.addView(
            menuRow("Import & Export", "Back up or restore your data") { screen = 2; enter = 3; render() },
            lp(MATCH, WRAP).apply { setMargins(0, dp(28), 0, 0) }
        )
        return root
    }

    // Settings > Reorder categories: hold a row and drag it
    private fun buildCatOrder(): View {
        val root = page()
        pageTop(root, "Reorder categories") { screen = 1; enter = 3; render() }
        root.addView(
            tv("Hold a category and drag it to move it", 13f, SUB).apply { setPadding(dp(2), 0, dp(2), dp(4)) },
            lp(MATCH, WRAP)
        )
        for (kind in intArrayOf(1, 0)) {
            root.addView(section(if (kind == 1) "Expense" else "Income"))
            val list = cats.filter { it.kind == kind }
            if (list.isEmpty()) {
                root.addView(tv("No categories", 14f, SUB))
                continue
            }
            val rows = ArrayList<View>()
            list.forEachIndexed { idx, c ->
                val r = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = dp(52)
                    setPadding(dp(16), dp(10), dp(16), dp(10))
                    background = box(CARD)
                }
                r.addView(tv(cn(c), 15f, TEXT, true, true), lp(0, WRAP, 1f))
                val h = FrameLayout(this)
                h.addView(r, FrameLayout.LayoutParams(MATCH, WRAP))
                rows.add(h)
                dragSort(h, idx, rows, 0.6f) { f, to -> moveCat(kind, f, to) }
                jiggle(h, 0.6f)
                root.addView(h, lp(MATCH, WRAP).apply { setMargins(0, dp(4), 0, dp(4)) })
            }
        }
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

    private fun cnt(n: Int, w: String) =
        if (lang == "tr") "$n ${s(w)}" else if (n == 1) "1 $w" else "$n ${w}s"

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
            val j = JSONObject().put("id", x.id).put("t", x.t).put("acc", x.acc).put("l", x.label).put("a", x.amt)
            if (x.to >= 0) j.put("to", x.to)
            if (x.cat.isNotEmpty()) j.put("cat", x.cat)
            ta.put(j)
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
            val to = x.optInt("to", -1)
            if (acc !in ids || !seen.add(id)) continue
            if (to >= 0 && (to !in ids || to == acc)) continue     // a transfer needs two different, existing accounts
            list.add(Tx(id, x.getLong("t"), acc, x.getString("l"), x.getDouble("a"), to, x.optString("cat", "")))
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
        val d = if (b.exported > 0L) SimpleDateFormat("d MMM yyyy", loc()).format(Date(b.exported)) else ""
        val msg = if (lang == "tr") {
            (if (d.isNotEmpty()) "$d tarihli dosyada" else "Dosyada") +
                    " ${cnt(b.cfg.accs.size, "account")}, ${cnt(b.cfg.tpls.size, "template")} " +
                    "ve ${cnt(b.txs.size, "transaction")} var. Şu an telefondaki her şey değiştirilecek. " +
                    "Bu işlem geri alınamaz."
        } else {
            "The file" + (if (d.isNotEmpty()) " from $d" else "") +
                    " has ${cnt(b.cfg.accs.size, "account")}, ${cnt(b.cfg.tpls.size, "template")} " +
                    "and ${cnt(b.txs.size, "transaction")}. Everything that is on this phone now will be " +
                    "replaced. This can't be undone."
        }
        openConfirm("Import this backup?", msg, "Import") { applyBackup(b) }
    }

    private fun applyBackup(b: Backup) {
        try {
            // new transaction file goes to a temp file first, the old one stays until everything worked
            val tmp = File(filesDir, "transactions.jsonl.tmp")
            tmp.bufferedWriter().use { w ->
                for (x in b.txs) {
                    val o = JSONObject().put("k", "tx").put("id", x.id).put("t", x.t).put("acc", x.acc)
                        .put("l", x.label).put("a", x.amt)
                    if (x.to >= 0) o.put("to", x.to)
                    if (x.cat.isNotEmpty()) o.put("cat", x.cat)
                    w.write(o.toString())
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

    // ---------- + button and quick add menu ----------
    private val PURPLE = 0xFFA78BFA.toInt()
    private val FAB_OPEN = 0xFFE4E4E7.toInt()      // the + turns light gray (×) while the menu is open
    private val pop = OvershootInterpolator(1.1f)
    private var fab: View? = null
    private var fabBg: GradientDrawable? = null
    private var addMenu: FrameLayout? = null
    private var menuClosing = false

    private class MenuOpt(val label: String, val color: Int, val kind: Int, val dx: Int, val up: Int, val delay: Long)

    // the round + button: sits on the top edge of the bottom bar, in the gap between the two tabs
    private fun buildFab(f: FrameLayout, nav: View) {
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ACCENT)
            setStroke(dp(4), BG)   // dark ring: the button looks cut out of the bar
        }
        val b = object : View(this@MainActivity) {
            private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = BG
                style = Paint.Style.STROKE
                strokeWidth = resources.displayMetrics.density * 2.8f
                strokeCap = Paint.Cap.ROUND
            }

            override fun onDraw(c: Canvas) {
                val m = width * 0.34f
                val mid = width / 2f
                c.drawLine(m, mid, width - m, mid, p)
                c.drawLine(mid, m, mid, height - m, p)
            }
        }
        b.background = bg
        b.setOnClickListener { if (addMenu == null) openAddMenu() else closeAddMenu() }
        pressFx(b, 0.92f)
        val lpf = FrameLayout.LayoutParams(dp(64), dp(64), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        lpf.bottomMargin = dp(32)
        f.addView(b, lpf)
        fab = b
        fabBg = bg
        // keep the middle of the button exactly on the top edge of the bar, whatever the bar's height is
        nav.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val h = bottom - top
            if (h > 0) {
                val m = h - dp(32)
                if (lpf.bottomMargin != m) {
                    lpf.bottomMargin = m
                    b.post { b.layoutParams = lpf }
                }
            }
        }
    }

    // + turns into × (and back); color fades with it
    private fun fabLook(open: Boolean, animate: Boolean) {
        val fb = fab ?: return
        val bg = fabBg ?: return
        val rot = if (open) 45f else 0f
        val to = if (open) FAB_OPEN else ACCENT
        if (!animate) {
            fb.animate().cancel()
            fb.scaleX = 1f
            fb.scaleY = 1f
            fb.rotation = rot
            bg.setColor(to)
            return
        }
        fb.animate().rotation(rot).setDuration(DUR + 60L).setInterpolator(ease).start()
        ValueAnimator.ofObject(ArgbEvaluator(), if (open) ACCENT else FAB_OPEN, to).apply {
            duration = DUR + 60L
            interpolator = ease
            addUpdateListener { bg.setColor(it.animatedValue as Int) }
            start()
        }
    }

    // icons are drawn in code (0 = income: arrow down, 1 = expense: arrow up, 2 = transfer: two arrows)
    private fun menuIcon(kind: Int, tint: Int): View = object : View(this@MainActivity) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = tint
            style = Paint.Style.STROKE
            strokeWidth = resources.displayMetrics.density * 2.6f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        override fun onDraw(c: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val u = width * 0.2f
            val path = Path()
            fun tray() {
                path.moveTo(cx - u, cy + 0.6f * u)
                path.lineTo(cx - u, cy + u)
                path.lineTo(cx + u, cy + u)
                path.lineTo(cx + u, cy + 0.6f * u)
            }
            when (kind) {
                0 -> {
                    path.moveTo(cx, cy - u); path.lineTo(cx, cy + 0.45f * u)
                    path.moveTo(cx - 0.7f * u, cy - 0.25f * u)
                    path.lineTo(cx, cy + 0.45f * u)
                    path.lineTo(cx + 0.7f * u, cy - 0.25f * u)
                    tray()
                }
                1 -> {
                    path.moveTo(cx, cy + 0.2f * u); path.lineTo(cx, cy - u)
                    path.moveTo(cx - 0.7f * u, cy - 0.3f * u)
                    path.lineTo(cx, cy - u)
                    path.lineTo(cx + 0.7f * u, cy - 0.3f * u)
                    tray()
                }
                else -> {
                    path.moveTo(cx - u, cy - 0.5f * u); path.lineTo(cx + u, cy - 0.5f * u)
                    path.moveTo(cx + 0.4f * u, cy - 0.95f * u)
                    path.lineTo(cx + u, cy - 0.5f * u)
                    path.lineTo(cx + 0.4f * u, cy - 0.05f * u)
                    path.moveTo(cx + u, cy + 0.5f * u); path.lineTo(cx - u, cy + 0.5f * u)
                    path.moveTo(cx - 0.4f * u, cy + 0.05f * u)
                    path.lineTo(cx - u, cy + 0.5f * u)
                    path.lineTo(cx - 0.4f * u, cy + 0.95f * u)
                }
            }
            c.drawPath(path, p)
        }
    }

    private fun openAddMenu() {
        val f = frame ?: return
        val fb = fab ?: return
        if (sheet != null) return

        val scrim = FrameLayout(this).apply {
            setBackgroundColor(0xE6070708.toInt())
            alpha = 0f
            setOnClickListener { closeAddMenu() }
        }
        // goes right under the + button, so the button stays on top and turns into the close button
        f.addView(scrim, f.indexOfChild(fb), FrameLayout.LayoutParams(MATCH, MATCH))
        sheet = scrim
        addMenu = scrim
        onSheetClose = {
            addMenu = null
            menuClosing = false
            fabLook(false, false)
        }

        // height of the middle of the + button above the bottom of the screen
        val mid = (fb.layoutParams as FrameLayout.LayoutParams).bottomMargin + dp(32)
        val opts = listOf(
            MenuOpt("Income", ACCENT, 0, -dp(104), dp(88), 25L),
            MenuOpt("Expense", DANGER, 1, 0, dp(150), 0L),
            MenuOpt("Transfer", PURPLE, 2, dp(104), dp(88), 25L)
        )
        for (o in opts) {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                tag = o.up
                // starts hidden and small, exactly behind the + button
                alpha = 0f
                scaleX = 0.4f
                scaleY = 0.4f
                pivotY = dp(30).toFloat()
                translationY = o.up.toFloat()
                setOnClickListener { pickAdd(o.kind) }
            }
            val circle = FrameLayout(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(o.color)
                }
            }
            circle.addView(menuIcon(o.kind, BG), FrameLayout.LayoutParams(MATCH, MATCH))
            item.addView(circle, lp(dp(60), dp(60)))
            item.addView(tv(o.label, 13f, TEXT, true).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, dp(8), 0, 0)
            }, lp(WRAP, dp(28)))
            scrim.addView(
                item,
                FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                    bottomMargin = mid + o.up - dp(58)
                }
            )
            item.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .translationX(o.dx.toFloat()).translationY(0f)
                .setStartDelay(o.delay).setDuration(180L).setInterpolator(pop).start()
        }
        scrim.animate().alpha(1f).setDuration(DUR).setInterpolator(ease).start()
        fabLook(true, true)
    }

    private fun closeAddMenu() {
        val m = addMenu ?: return
        if (menuClosing) return
        menuClosing = true
        fabLook(false, true)
        for (i in 0 until m.childCount) {
            val item = m.getChildAt(i)
            val up = (item.tag as? Int) ?: 0
            item.animate().alpha(0f).scaleX(0.4f).scaleY(0.4f)
                .translationX(0f).translationY(up.toFloat())
                .setStartDelay(0L).setDuration(DOWN_DUR + 30L).setInterpolator(easeOut).start()
        }
        m.animate().alpha(0f).setDuration(DUR).setInterpolator(ease)
            .withEndAction { if (sheet === m) closeSheet() }
            .start()
    }

    private fun pickAdd(kind: Int) {
        if (menuClosing) return
        buzz()
        // the little menu goes away at once and the form opens right behind it (no waiting for the fade-out)
        addMenu?.animate()?.cancel()
        closeSheet()            // runs onSheetClose: resets addMenu and the + button, removes the menu
        openQuickAdd(kind)      // 0 = Income, 1 = Expense, 2 = Transfer
    }

    // ---------- quick add helpers ----------

    // built-in categories: id -> English name (Turkish comes from the TR map). A transaction stores the id.
    private val EXP_CATS = listOf(
        "e_groc" to "Groceries", "e_food" to "Food & Drink", "e_trans" to "Transport",
        "e_shop" to "Shopping", "e_bills" to "Bills", "e_subs" to "Subscriptions", "e_other" to "Other"
    )
    private val INC_CATS = listOf(
        "i_sal" to "Salary", "i_allow" to "Scholarship / Allowance", "i_extra" to "Extra income",
        "i_inv" to "Investment return", "i_gift" to "Gift", "i_ref" to "Refund", "i_other" to "Other"
    )

    // the built-in categories as normal Cat objects (they are put in front of the user's own ones the first time)
    private fun builtinCats(): List<Cat> {
        val l = ArrayList<Cat>()
        for (p in EXP_CATS) l.add(Cat(p.first, p.second, 1))
        for (p in INC_CATS) l.add(Cat(p.first, p.second, 0))
        return l
    }

    // display name: a built-in category that still has its original name follows the app language
    private fun cn(c: Cat): String {
        for (p in EXP_CATS) if (p.first == c.id && p.second == c.name) return s(c.name)
        for (p in INC_CATS) if (p.first == c.id && p.second == c.name) return s(c.name)
        return c.name
    }

    // moves a category inside its own kind (from / to are positions among the categories of that kind)
    private fun moveCat(kind: Int, from: Int, to: Int) {
        val sub = cats.filter { it.kind == kind }.toMutableList()
        if (from !in sub.indices || to !in sub.indices) return
        sub.add(to, sub.removeAt(from))
        var n = 0
        for (i in cats.indices) if (cats[i].kind == kind) cats[i] = sub[n++]
        saveCfg()
    }

    private fun catDelMsg(c: Cat): String =
        if (lang == "tr")
            "\u201C${cn(c)}\u201D kategorisi silinecek. Bu kategoriyi kullanan şablonlardan kalkar ve geçmiş işlemlerde artık görünmez. Bu işlem geri alınamaz."
        else
            "\u201C${cn(c)}\u201D will be deleted. It is removed from the templates that use it and no longer shows on past transactions. This can't be undone."

    // display name of a category id; null when there is none (empty id, or a category that was deleted)
    private fun catName(id: String): String? {
        if (id.isEmpty()) return null
        for (c in cats) if (c.id == id) return cn(c)
        return null
    }

    private fun catsJson(): JSONArray {
        val ca = JSONArray()
        for (c in cats) ca.put(JSONObject().put("id", c.id).put("n", c.name).put("k", c.kind))
        return ca
    }

    private fun chipView(text: String, user: Boolean): TextView {
        val c = tv(text, 12f, SUB, true, user).apply { setPadding(dp(11), dp(7), dp(11), dp(7)) }
        pressFx(c, 0.96f)
        return c
    }

    // small gray label in front of a chip row (fixed 60dp column, so all rows line up)
    private fun rowLabel(text: String, top: Int): TextView =
        tv(text, 12f, SUB).apply { setPadding(dp(2), dp(top), dp(4), 0) }

    // packs chips into rows that are at most maxW wide (in order). Measures every chip, so measuredWidth is valid afterward.
    private fun packRows(chips: List<View>, maxW: Int): List<List<View>> {
        val spec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        val gap = dp(6)
        val rows = ArrayList<ArrayList<View>>()
        var cur = ArrayList<View>()
        var w = 0
        for (c in chips) {
            c.measure(spec, spec)
            val cw = c.measuredWidth + gap
            if (cur.isNotEmpty() && w + cw - gap > maxW) {
                rows.add(cur)
                cur = ArrayList()
                w = 0
            }
            cur.add(c)
            w += cw
        }
        if (cur.isNotEmpty()) rows.add(cur)
        return rows
    }

    // chip rows stacked on top of each other
    private fun rowsView(rows: List<List<View>>): LinearLayout {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for ((i, r) in rows.withIndex()) {
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in r) line.addView(c, lp(WRAP, WRAP).apply { setMargins(0, 0, dp(6), 0) })
            col.addView(line, lp(WRAP, WRAP).apply { setMargins(0, if (i == 0) 0 else dp(6), 0, 0) })
        }
        return col
    }

    // account chips for Income / Expense: up to 2 rows, no scrolling. If they need more than 2 rows,
    // they are split into exactly 2 rows that scroll sideways together.
    private fun accRowsView(chips: List<View>, maxW: Int): View {
        val rows = packRows(chips, maxW)
        if (rows.size <= 2) return rowsView(rows)
        var total = 0
        for (c in chips) total += c.measuredWidth + dp(6)
        val first = ArrayList<View>()
        val second = ArrayList<View>()
        var w = 0
        for (c in chips) {
            if (w < total / 2) first.add(c) else second.add(c)
            w += c.measuredWidth + dp(6)
        }
        if (second.isEmpty() && first.size > 1) second.add(first.removeAt(first.size - 1))
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(rowsView(listOf(first, second)))
        }
    }

    // ---------- date & time field (date chip + time chip, each opens an in-app panel) ----------
    private class DateField(val view: View, val time: () -> Long, val restyle: () -> Unit, val close: () -> Unit)

    private fun dayStart(t: Long): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = t
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private fun shiftDay(t: Long, n: Int): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = t
        c.add(Calendar.DAY_OF_MONTH, n)
        return dayStart(c.timeInMillis)
    }

    // vertical wheel: 3 rows visible, the middle one is selected, snaps to the nearest row
    @SuppressLint("ClickableViewAccessibility")
    private fun wheel(count: Int, init: Int, color: Int, onPick: (Int) -> Unit): View {
        val ih = dp(40)
        val sv = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, ih, 0, ih)
        }
        val items = ArrayList<TextView>()
        for (i in 0 until count) {
            val t = tv(String.format(Locale.US, "%02d", i), 22f, SUB, true, true).apply { gravity = Gravity.CENTER }
            items.add(t)
            col.addView(t, lp(MATCH, ih))
        }
        sv.addView(col)
        var cur = init.coerceIn(0, count - 1)
        fun paintItems() {
            items.forEachIndexed { i, t ->
                t.setTextColor(if (i == cur) color else SUB)
                t.alpha = if (i == cur) 1f else 0.5f
            }
        }
        fun nearest(): Int = Math.round(sv.scrollY / ih.toFloat()).coerceIn(0, count - 1)
        var last = -1
        val settle = object : Runnable {
            override fun run() {
                val y = sv.scrollY
                if (y == last) {
                    val n = nearest()
                    if (y != n * ih) sv.smoothScrollTo(0, n * ih)
                } else {
                    last = y
                    sv.postDelayed(this, 60)
                }
            }
        }
        sv.setOnScrollChangeListener { _, _, _, _, _ ->
            val n = nearest()
            if (n != cur) {
                cur = n
                paintItems()
                onPick(n)
            }
        }
        sv.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    v.removeCallbacks(settle)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    last = -1
                    v.postDelayed(settle, 60)
                }
            }
            false
        }
        paintItems()
        sv.post { sv.scrollTo(0, cur * ih) }
        return sv
    }

    // onOpen(true) = a panel opened, onOpen(false) = it was closed by the user
    private fun dateField(start: Long, accent: () -> Int, fixed: Boolean = false, onOpen: (Boolean) -> Unit): DateField {
        val today = dayStart(System.currentTimeMillis())
        val yesterday = shiftDay(today, -1)
        val st = Calendar.getInstance().apply { timeInMillis = start }
        var day = dayStart(start)
        var hh = st.get(Calendar.HOUR_OF_DAY)
        var mi = st.get(Calendar.MINUTE)
        var touched = false     // false = nothing was changed, the entry gets the exact time of saving
        var panel = 0           // 0 = closed, 1 = calendar, 2 = time wheels
        val mon = Calendar.getInstance()   // month shown in the calendar

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(rowLabel("Date", 0), lp(dp(60), WRAP))
        val cDate = chipView("", true)
        val cTime = chipView("", true)
        row.addView(cDate, lp(WRAP, WRAP).apply { setMargins(0, 0, dp(6), 0) })
        row.addView(cTime, lp(WRAP, WRAP))
        col.addView(row, lp(MATCH, WRAP))
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(10), 0, 0)
        }
        col.addView(body, lp(MATCH, WRAP))

        fun dateText(): String {
            if (day == today) return s("Today")
            if (day == yesterday) return s("Yesterday")
            val y1 = Calendar.getInstance().get(Calendar.YEAR)
            val y2 = Calendar.getInstance().apply { timeInMillis = day }.get(Calendar.YEAR)
            return SimpleDateFormat(if (y1 == y2) "d MMM" else "d MMM yyyy", loc()).format(Date(day))
        }

        fun look() {
            val c = accent()
            cDate.text = dateText()
            cTime.text = String.format(Locale.US, "%02d:%02d", hh, mi)
            listOf(cDate, cTime).forEachIndexed { n, ch ->
                val on = panel == n + 1
                ch.setTextColor(if (on) c else TEXT)
                ch.background = box(if (on) BG else KEY, if (on) c else 0)
            }
        }

        fun close() {
            if (panel == 0) return
            panel = 0
            body.visibility = View.GONE
            body.removeAllViews()
            look()
        }

        fun pickDay(t: Long) {
            day = t
            touched = true
            close()
            onOpen(false)
        }

        fun buildCalendar() {
            body.removeAllViews()
            val c = accent()
            val nowC = Calendar.getInstance()
            val nowKey = nowC.get(Calendar.YEAR) * 12 + nowC.get(Calendar.MONTH)
            val key = mon.get(Calendar.YEAR) * 12 + mon.get(Calendar.MONTH)

            // header:  <  October 2026  >
            val head = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            fun arrow(rot: Float, d: Int, ok: Boolean): View {
                val b = FrameLayout(this)
                b.addView(chev(SUB, rot), FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
                if (ok) {
                    b.setOnClickListener { mon.add(Calendar.MONTH, d); buildCalendar() }
                    pressFx(b, 0.88f)
                } else {
                    b.alpha = 0.25f
                }
                return b
            }
            head.addView(arrow(90f, -1, true), lp(dp(40), dp(36)))
            head.addView(
                tv(SimpleDateFormat("MMMM yyyy", loc()).format(mon.time), 14f, TEXT, true, true)
                    .apply { gravity = Gravity.CENTER },
                lp(0, WRAP, 1f)
            )
            head.addView(arrow(-90f, 1, key < nowKey), lp(dp(40), dp(36)))
            body.addView(head, lp(MATCH, WRAP))

            // weekday letters (week starts on Monday)
            val wd = if (lang == "tr") arrayOf("Pt", "Sa", "Ça", "Pe", "Cu", "Ct", "Pz")
            else arrayOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")
            val wr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (w in wd) wr.addView(tv(w, 11f, SUB, false, true).apply { gravity = Gravity.CENTER }, lp(0, dp(26), 1f))
            body.addView(wr, lp(MATCH, WRAP))

            // days (future days can't be picked)
            val first = (mon.get(Calendar.DAY_OF_WEEK) + 5) % 7
            val dim = mon.getActualMaximum(Calendar.DAY_OF_MONTH)
            val cc = Calendar.getInstance()
            var d = 1 - first
            while (d <= dim) {
                val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                for (i in 0 until 7) {
                    val holder = FrameLayout(this)
                    if (d in 1..dim) {
                        cc.timeInMillis = mon.timeInMillis
                        cc.add(Calendar.DAY_OF_MONTH, d - 1)
                        val t = dayStart(cc.timeInMillis)
                        val on = t == day
                        val future = t > today
                        val cell = tv(d.toString(), 14f, if (on) BG else if (future) BORDER else TEXT, on, true).apply {
                            gravity = Gravity.CENTER
                            if (on) {
                                background = GradientDrawable().apply {
                                    shape = GradientDrawable.OVAL
                                    setColor(c)
                                }
                            } else if (t == today) {
                                background = GradientDrawable().apply {
                                    shape = GradientDrawable.OVAL
                                    setColor(0)
                                    setStroke(dp(1), SUB)
                                }
                            }
                        }
                        if (!future) {
                            holder.setOnClickListener { pickDay(t) }
                            pressFx(holder, 0.9f)
                        }
                        holder.addView(cell, FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER))
                    }
                    line.addView(holder, lp(0, dp(38), 1f))
                    d++
                }
                body.addView(line, lp(MATCH, WRAP))
            }
        }

        fun buildTime() {
            body.removeAllViews()
            val c = accent()
            val ih = dp(40)
            val host = FrameLayout(this)
            host.addView(
                View(this).apply { background = box(KEY) },
                FrameLayout.LayoutParams(MATCH, ih, Gravity.CENTER_VERTICAL).apply { setMargins(dp(40), 0, dp(40), 0) }
            )
            val wr = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            wr.addView(wheel(24, hh, c) { hh = it; touched = true; look() }, lp(dp(72), 3 * ih))
            wr.addView(tv(":", 22f, TEXT, true, true).apply { gravity = Gravity.CENTER }, lp(dp(16), 3 * ih))
            wr.addView(wheel(60, mi, c) { mi = it; touched = true; look() }, lp(dp(72), 3 * ih))
            host.addView(wr, FrameLayout.LayoutParams(MATCH, 3 * ih))
            body.addView(host, lp(MATCH, WRAP))
            val done = tv("Done", 14f, BG, true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(28), dp(10), dp(28), dp(10))
                background = tap(c)
                setOnClickListener { close(); onOpen(false) }
            }
            pressFx(done, 0.96f)
            body.addView(done, lp(WRAP, WRAP).apply { gravity = Gravity.CENTER_HORIZONTAL; setMargins(0, dp(8), 0, 0) })
        }

        fun show(p: Int) {
            val was = panel
            panel = p
            body.visibility = View.VISIBLE
            if (p == 1) {
                mon.timeInMillis = day
                mon.set(Calendar.DAY_OF_MONTH, 1)
                buildCalendar()
            } else {
                buildTime()
            }
            look()
            if (was == 0) onOpen(true)
        }

        fun restyle() {
            look()
            if (panel == 1) buildCalendar() else if (panel == 2) buildTime()
        }

        cDate.setOnClickListener { if (panel == 1) { close(); onOpen(false) } else show(1) }
        cTime.setOnClickListener { if (panel == 2) { close(); onOpen(false) } else show(2) }
        look()

        return DateField(
            col,
            {
                if (!touched) {
                    if (fixed) start else System.currentTimeMillis()
                } else {
                    val ref = if (fixed) st else Calendar.getInstance()   // seconds come from the old entry when editing
                    val c = Calendar.getInstance()
                    c.timeInMillis = day
                    c.set(Calendar.HOUR_OF_DAY, hh)
                    c.set(Calendar.MINUTE, mi)
                    c.set(Calendar.SECOND, ref.get(Calendar.SECOND))
                    c.set(Calendar.MILLISECOND, ref.get(Calendar.MILLISECOND))
                    Math.min(c.timeInMillis, System.currentTimeMillis())   // never in the future
                }
            },
            { restyle() },
            { close() }
        )
    }

    // ---------- quick add: Income / Expense / Transfer ----------
    // One sheet for all three kinds. The number pad is open from the first frame and the account is already
    // the active one, so the usual entry is: type the amount -> Save. Kind, category, account and note are optional extras.
    private fun openQuickAdd(startKind: Int, ed: Tx? = null) {
        val f = frame ?: return
        if (sheet != null) return

        // accounts offered here: template-only ones are left out (unless the entry being edited already uses one)
        val qa = accs.filter { !it.tplOnly || (ed != null && ed.to < 0 && it.id == ed.acc) }
        val qt = accs.toList()   // Transfer: tüm hesaplar

        var kind = startKind                                      // 0 = Income, 1 = Expense, 2 = Transfer
        val buf = StringBuilder(if (ed == null) "" else raw(Math.abs(ed.amt)))
        var acc1 = ed?.acc ?: active                                         // Income / Expense: the account. Transfer: "from"
        var acc2 = if (ed != null && ed.to >= 0) ed.to else (qt.firstOrNull { it.id != acc1 }?.id ?: -1)   // Transfer: "to"
        var padOn = true                                          // true = number pad, false = Android keyboard
        var cat = if (ed != null && ed.cat.isNotEmpty() && catName(ed.cat) != null) ed.cat else ""                                              // chosen category id ("" = none)
        var editing: Cat? = null                                  // own category being renamed / deleted (null = making a new one)

        // width left for chips next to the 60dp label column
        val avail = (if (f.width > 0) f.width else resources.displayMetrics.widthPixels) - dp(36) - dp(60) - dp(8)

        fun kc(k: Int): Int = when (k) {
            0 -> ACCENT
            1 -> DANGER
            else -> PURPLE
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

        if (ed != null) sh.addView(tv("Edit Transaction", 18f, TEXT, true).apply { setPadding(dp(2), 0, 0, dp(10)) })

        // kind switch: Income | Expense | Transfer
        val segs = ArrayList<TextView>()
        val seg = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = box(BG)
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        val kindNames = arrayOf("Income", "Expense", "Transfer")
        for (k in 0..2) {
            val t = tv(kindNames[k], 14f, SUB, true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(10), dp(6), dp(10))
            }
            pressFx(t, 0.96f)
            segs.add(t)
            seg.addView(t, lp(0, WRAP, 1f))
        }
        sh.addView(seg, lp(MATCH, WRAP))

        // date + time, right above the amount (the field itself is created further down, once showPad / toAmount exist)
        val dateHost = LinearLayout(this)
        sh.addView(dateHost, lp(MATCH, WRAP).apply { setMargins(0, dp(10), 0, 0) })

        // amount
        val amtBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val amtTv = tv("0", 34f, SUB, true)
        amtBox.addView(amtTv, lp(0, WRAP, 1f))
        sh.addView(amtBox, lp(MATCH, WRAP).apply { setMargins(0, dp(10), 0, 0) })

        val err = tv("", 12f, DANGER).apply {
            setPadding(dp(2), dp(8), 0, 0)
            visibility = View.GONE
        }
        sh.addView(err)

        // category (Income / Expense): chips wrap onto as many rows as needed + a small inline editor for own categories
        var catChips = ArrayList<TextView>()
        var catIds = ArrayList<String>()
        val catFlow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val catInput = EditText(this).apply {
            hint = s("Category name")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_DONE
            filters = arrayOf<InputFilter>(InputFilter.LengthFilter(18))
            textSize = 14f
            typeface = fontReg
            setTextColor(TEXT)
            setHintTextColor(SUB)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = box(BG, BORDER)
        }
        val catDel = tv("Delete", 13f, DANGER, true).apply {
            setPadding(dp(12), dp(10), dp(8), dp(10))
            visibility = View.GONE
        }
        val catOk = tv("Add", 13f, BG, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(9), dp(14), dp(9))
        }
        pressFx(catOk, 0.96f)
        val catEdit = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        catEdit.addView(catInput, lp(0, WRAP, 1f))
        catEdit.addView(catOk, lp(WRAP, WRAP).apply { setMargins(dp(4), 0, 0, 0) })
        catEdit.addView(catDel, lp(WRAP, WRAP))
        val catHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        catHost.addView(catFlow, lp(MATCH, WRAP))
        catHost.addView(catEdit, lp(MATCH, WRAP).apply { setMargins(0, dp(8), 0, 0) })
        val rowCat = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        rowCat.addView(rowLabel("Category", 7), lp(dp(60), WRAP))
        rowCat.addView(catHost, lp(0, WRAP, 1f))
        sh.addView(rowCat, lp(MATCH, WRAP).apply { setMargins(0, dp(10), 0, 0) })

        // account: Income / Expense = all chips visible (max 2 rows), Transfer = From / To rows (scroll sideways)
        val chipsAcc = ArrayList<TextView>()
        for (a in qa) chipsAcc.add(chipView(a.name, true))
        val accView = accRowsView(chipsAcc, avail)
        val hsAcc = accView as? HorizontalScrollView
        val rowAcc = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        rowAcc.addView(rowLabel("Account", 7), lp(dp(60), WRAP))
        rowAcc.addView(accView, lp(0, WRAP, 1f))
        sh.addView(rowAcc, lp(MATCH, WRAP).apply { setMargins(0, dp(10), 0, 0) })

        val chipsFrom = ArrayList<TextView>()
        val chipsTo = ArrayList<TextView>()

        fun scrollRow(label: String, chips: ArrayList<TextView>): Pair<LinearLayout, HorizontalScrollView> {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(rowLabel(label, 0), lp(dp(60), WRAP))
            val inner = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (a in qt) {
                val chip = chipView(a.name, true)
                chips.add(chip)
                inner.addView(chip, lp(WRAP, WRAP).apply { setMargins(0, 0, dp(6), 0) })
            }
            val hs = HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(inner)
            }
            row.addView(hs, lp(0, WRAP, 1f))
            return Pair(row, hs)
        }

        val (rowFrom, hsFrom) = scrollRow("From", chipsFrom)
        val (rowTo, hsTo) = scrollRow("To", chipsTo)
        for (r in listOf(rowFrom, rowTo)) {
            sh.addView(r, lp(MATCH, WRAP).apply { setMargins(0, dp(10), 0, 0) })
        }
        val hint2 = tv("Transfer needs two accounts", 13f, SUB).apply {
            setPadding(dp(2), dp(14), 0, dp(4))
            visibility = View.GONE
        }
        sh.addView(hint2)

        // note (optional)
        val noteEt = EditText(this).apply {
            hint = s("Note (optional)")
            if (ed != null) setText(ed.label)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_DONE
            filters = arrayOf<InputFilter>(InputFilter.LengthFilter(24))
            textSize = 15f
            typeface = fontReg
            setTextColor(TEXT)
            setHintTextColor(SUB)
            setPadding(dp(14), dp(11), dp(14), dp(11))
        }
        sh.addView(noteEt, lp(MATCH, WRAP).apply { setMargins(0, dp(10), 0, 0) })

        // number pad
        val pad = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        val keyViews = ArrayList<Pair<String, TextView>>()
        val keyRows = listOf(
            listOf("7", "8", "9"),
            listOf("4", "5", "6"),
            listOf("1", "2", "3"),
            listOf(".", "0", "←")
        )
        for (rw in keyRows) {
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (k in rw) {
                val key = tv(k, 22f, TEXT, true).apply {
                    gravity = Gravity.CENTER
                    background = tap(KEY)
                }
                pressFx(key, 0.94f)
                keyViews.add(Pair(k, key))
                line.addView(key, lp(0, dp(46), 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
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
        val saveBtn = tv("Save", 16f, BG, true).apply { gravity = Gravity.CENTER }
        pressFx(saveBtn, 0.97f)
        btns.addView(saveBtn, lp(0, dp(52), 1.5f))
        sh.addView(btns)

        // ----- behavior (order matters: a local function can only use the ones above it) -----

        fun frames() {
            val c = kc(kind)
            amtBox.background = box(BG, if (padOn) c else BORDER)
            noteEt.background = box(BG, if (noteEt.hasFocus()) c else BORDER)
            catInput.background = box(BG, if (catInput.hasFocus()) c else BORDER)
        }

        fun showPad(on: Boolean) {
            padOn = on
            pad.visibility = if (on) View.VISIBLE else View.GONE
            frames()
        }

        fun toAmount() {
            hideKb()
            sh.requestFocus()
            showPad(true)
        }

        // date + time: while a panel (calendar / time wheels) is open the number pad steps aside
        val dateF = dateField(ed?.t ?: System.currentTimeMillis(), { kc(kind) }, ed != null) { opened ->
            if (opened) { hideKb(); sh.requestFocus(); showPad(false) } else toAmount()
        }
        dateHost.addView(dateF.view)

        fun styleChips(chips: List<TextView>, list: List<Acc>, sel: Int, c: Int) {
            chips.forEachIndexed { n, chip ->
                val on = list[n].id == sel
                chip.setTextColor(if (on) c else SUB)
                chip.background = box(if (on) BG else KEY, if (on) c else 0)
            }
        }

        fun styleCats() {
            val c = kc(kind)
            catChips.forEachIndexed { n, chip ->
                val on = catIds[n] == cat
                chip.setTextColor(if (on) c else SUB)
                chip.background = box(if (on) BG else KEY, if (on) c else 0)
            }
        }

        fun closeCatEdit() {
            if (catEdit.visibility != View.VISIBLE) return
            editing = null
            catInput.setText("")
            catEdit.visibility = View.GONE
            toAmount()
        }

        fun openCatEdit(target: Cat?) {
            editing = target
            catInput.setText(target?.let { cn(it) } ?: "")
            catInput.setSelection(catInput.text.length)
            catDel.visibility = if (target != null) View.VISIBLE else View.GONE
            catOk.text = s(if (target != null) "Save" else "Add")
            catEdit.visibility = View.VISIBLE
            catInput.requestFocus()
            catInput.postDelayed({
                getSystemService(InputMethodManager::class.java)
                    ?.showSoftInput(catInput, InputMethodManager.SHOW_IMPLICIT)
            }, 120)
        }

        // (re)builds the category chips for the current kind
        fun fillCats() {
            catFlow.removeAllViews()
            if (kind == 2) return
            val chips = ArrayList<TextView>()
            val ids = ArrayList<String>()
            for (c in cats) {
                if (c.kind != kind) continue
                val chip = chipView(cn(c), true)
                chip.isHapticFeedbackEnabled = false
                chip.setOnLongClickListener { buzz(); openCatEdit(c); true }   // hold = rename / delete
                chips.add(chip)
                ids.add(c.id)
            }
            chips.forEachIndexed { n, chip ->
                chip.setOnClickListener {
                    cat = if (cat == ids[n]) "" else ids[n]                       // tap again = no category
                    closeCatEdit()
                    styleCats()
                }
            }
            val plus = chipView("+ New", false)
            plus.background = box(BG, BORDER)
            plus.setOnClickListener {
                if (catEdit.visibility == View.VISIBLE && editing == null) closeCatEdit() else openCatEdit(null)
            }
            catChips = chips
            catIds = ids
            val all = ArrayList<View>(chips)
            all.add(plus)
            catFlow.addView(rowsView(packRows(all, avail)))
            styleCats()
        }

        // creates / renames an own category (or picks the one with the same name) and closes the editor
        fun commitCat() {
            val nm = catInput.text.toString().trim()
            val target = editing
            if (nm.isNotEmpty()) {
                var same: String? = null
                for (c in cats) if (c.kind == kind && c.id != target?.id && cn(c).equals(nm, true)) same = c.id
                if (same != null) {
                    cat = same
                } else if (target != null) {
                    if (nm != cn(target)) target.name = nm
                    saveCfg()
                } else {
                    val nc = Cat("c_" + UUID.randomUUID().toString().substring(0, 8), nm, kind)
                    cats.add(nc)
                    saveCfg()
                    cat = nc.id
                }
            }
            closeCatEdit()
            fillCats()
        }

        // only the amount text (called on every key press)
        fun amount() {
            val raw = buf.toString()
            val v = raw.toDoubleOrNull() ?: 0.0
            val sign = if (kind == 2 || v == 0.0) "" else if (kind == 1) "-" else "+"
            amtTv.text = money(sign, grp(raw))
            amtTv.setTextColor(if (buf.isEmpty()) SUB else kc(kind))
            err.visibility = View.GONE
        }

        // everything that depends on the kind or on the chosen accounts
        fun paint() {
            val c = kc(kind)
            val two = qt.size >= 2
            // Transfer'den Income/Expense'e geçerken seçili hesap template-only ise normal bir hesaba dön
            if (kind != 2 && qa.none { it.id == acc1 }) acc1 = qa.firstOrNull()?.id ?: acc1
            if (kind == 2 && two && (acc2 == acc1 || accOf(acc2) == null)) {
                acc2 = qt.firstOrNull { it.id != acc1 }?.id ?: -1
            }
            segs.forEachIndexed { k, t ->
                t.setTextColor(if (k == kind) BG else SUB)
                t.background = if (k == kind) box(kc(k)) else null
            }
            rowCat.visibility = if (kind == 2) View.GONE else View.VISIBLE
            rowAcc.visibility = if (kind == 2) View.GONE else View.VISIBLE
            rowFrom.visibility = if (kind == 2 && two) View.VISIBLE else View.GONE
            rowTo.visibility = if (kind == 2 && two) View.VISIBLE else View.GONE
            hint2.visibility = if (kind == 2 && !two) View.VISIBLE else View.GONE
            styleChips(chipsAcc, qa, acc1, c)
            styleChips(chipsFrom, qt, acc1, c)
            styleChips(chipsTo, qt, acc2, c)
            saveBtn.background = tap(c)
            catOk.background = tap(c)
            dateF.restyle()
            frames()
            amount()
        }

        // scroll a sideways-scrolling chip row so the selected account is visible
        fun reveal(hs: HorizontalScrollView?, chips: List<TextView>, list: List<Acc>, sel: Int) {
            val h = hs ?: return
            val n = list.indexOfFirst { it.id == sel }
            if (n < 0) return
            h.post { h.scrollTo(Math.max(0, chips[n].left - dp(40)), 0) }
        }

        fun revealRows() {
            if (kind == 2) {
                reveal(hsFrom, chipsFrom, qt, acc1)
                reveal(hsTo, chipsTo, qt, acc2)
            } else {
                reveal(hsAcc, chipsAcc, qa, acc1)
            }
        }

        // ----- listeners -----
        segs.forEachIndexed { k, t ->
            t.setOnClickListener {
                if (kind != k) {
                    closeCatEdit()
                    kind = k
                    cat = ""
                    paint()
                    fillCats()
                    revealRows()
                }
            }
        }
        chipsAcc.forEachIndexed { n, chip ->
            chip.setOnClickListener { acc1 = qa[n].id; paint() }
        }
        chipsFrom.forEachIndexed { n, chip ->
            chip.setOnClickListener {
                val id = qt[n].id
                if (id == acc2) acc2 = acc1          // picking the "to" account as "from" swaps them
                acc1 = id
                paint()
            }
        }
        chipsTo.forEachIndexed { n, chip ->
            chip.setOnClickListener {
                val id = qt[n].id
                if (id == acc1) acc1 = acc2          // picking the "from" account as "to" swaps them
                acc2 = id
                paint()
            }
        }
        for ((k, key) in keyViews) {
            key.setOnClickListener { press(buf, k); amount() }
            if (k == "←") key.setOnLongClickListener { buf.setLength(0); amount(); true }
        }
        noteEt.setOnFocusChangeListener { _, has -> if (has) { dateF.close(); showPad(false) } else frames() }
        noteEt.setOnEditorActionListener { _, _, _ -> toAmount(); true }
        catInput.setOnFocusChangeListener { _, has -> if (has) showPad(false) else frames() }
        catInput.setOnEditorActionListener { _, _, _ -> commitCat(); true }
        catOk.setOnClickListener { commitCat() }
        val doDelCat: () -> Unit = {
            val target = editing
            if (target != null) {
                cats.remove(target)
                tpls.forEach { if (it.cat == target.id) it.cat = "" }
                if (cat == target.id) cat = ""
                saveCfg()
            }
            closeCatEdit()
            fillCats()
        }
        catDel.setOnClickListener {
            val target = editing
            if (target != null) openConfirmOver("Delete category?", catDelMsg(target), "Delete") {
                tpls.forEach { if (it.cat == target.id) it.cat = "" }
                doDelCat()
            }
        }
        amtBox.setOnClickListener { dateF.close(); toAmount() }

        saveBtn.setOnClickListener {
            if (catEdit.visibility == View.VISIBLE) commitCat()      // a category typed but not added yet still counts
            val v = r2(buf.toString().toDoubleOrNull() ?: 0.0)        // 0 is allowed on purpose
            if (kind == 2 && (qt.size < 2 || acc1 == acc2 || accOf(acc1) == null || accOf(acc2) == null)) {
                err.text = s("Transfer needs two accounts")
                err.visibility = View.VISIBLE
                return@setOnClickListener
            }
            val note = noteEt.text.toString().trim()
            val picked = cat
            val whenMs = dateF.time()
            closeSheet()
            if (ed == null) {
                when (kind) {
                    0 -> addTx(note, v, acc1, -1, picked, whenMs)
                    1 -> addTx(note, if (v == 0.0) 0.0 else -v, acc1, -1, picked, whenMs)
                    else -> addTx(note, v, acc1, acc2, "", whenMs)
                }
            } else {
                val amt = if (kind == 1 && v != 0.0) -v else v
                val nw = Tx(ed.id, whenMs, acc1, note, amt, if (kind == 2) acc2 else -1, if (kind == 2) "" else picked)
                if (updateTx(nw)) {
                    val i = txs.indexOf(ed)
                    if (i >= 0) txs[i] = nw
                } else {
                    snack("Couldn't save entry")
                }
            }
            buzz()
            render()
        }

        // ----- show it -----
        val scroller = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(sh)
        }
        scrim.addView(scroller, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
        scrim.alpha = 0f
        scroller.translationY = dp(48).toFloat()
        f.addView(scrim, FrameLayout.LayoutParams(MATCH, MATCH))
        sheet = scrim
        scrim.animate().alpha(1f).setDuration(DUR).setInterpolator(ease).start()
        scroller.animate().translationY(0f).setDuration(DUR + 60L).setInterpolator(ease).start()

        paint()
        fillCats()
        toAmount()
        revealRows()
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
        for (a in accs.filter { !it.tplOnly }) {
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
        val tpo = booleanArrayOf(a.tplOnly)
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
            onDelete = if (canDelete(a)) ({ requestDeleteAccount(a) }) else null,
            style = st,
            // the switch is hidden for the last normal account (it can't become template-only)
            tplOnlyRef = if (a.tplOnly || accs.any { it !== a && !it.tplOnly }) tpo else null
        ) { nm, v, _ ->
            if (nm.isNotEmpty()) a.name = nm
            a.open = v - (cur - a.open)
            a.tplOnly = tpo[0]
            if (a.tplOnly && active == a.id) active = accs.first { !it.tplOnly }.id
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
        val tpo = booleanArrayOf(false)
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
            defaultNeg = false,
            tplOnlyRef = tpo
        ) { nm, v, _ ->
            val id = nid++
            accs.add(Acc(id, if (nm.isEmpty()) newAccName() else nm, v, st.color, st.img, tpo[0]))
            saveCfg()
            render()
        }
    }

    // Template 1, Template 2 ... (skip = index of the template being edited, -1 for new)
    private fun untitled(skip: Int): String {
        val used = tpls.filterIndexed { i, _ -> i != skip }.map { it.label }.toSet()
        val p = s("Template")
        var n = 1
        while ("$p $n" in used) n++
        return "$p $n"
    }

    private fun openTplEditor(idx: Int) {
        val isNew = idx < 0
        val old = if (isNew) null else tpls[idx]
        val st = if (old == null) Style(0, null) else Style(old.color, old.img)
        val origImg = old?.img
        // [0] = "Ask amount on tap" switch, [1] = Expense (true) / Income (false), filled in when saving
        val askRef = booleanArrayOf(old != null && old.ask != 0, true)
        val catRef = arrayOf(old?.cat ?: "")
        // a special template keeps only a suggested amount (always positive); its sign comes from its kind
        val startAmt: Double? =
            if (old == null) null
            else if (old.ask == 0) old.amt
            else if (old.amt == 0.0) null
            else if (old.ask == 1) -old.amt
            else old.amt
        openEditor(
            title = if (old == null) "New Template" else "Edit Template",
            nameHint = "Name",
            name = old?.label ?: "",
            amountLabel = "Amount",
            amount = startAmt,
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
            accSel = if (old == null) -1 else old.acc.let { if (accOf(it) != null) it else -1 },
            style = st,
            defaultNeg = old == null || old.ask != 2,
            askRef = askRef,
            catRef = catRef
        ) { nm0, v, a ->
            val nm = if (nm0.isEmpty()) untitled(idx) else nm0
            val ask = if (askRef[0]) (if (askRef[1]) 1 else 2) else 0
            val amt = if (ask != 0) Math.abs(v) else v
            // old photo is no longer used -> free the file
            if (origImg != null && origImg != st.img) deleteImg(origImg)
            if (isNew) {
                tpls.add(Tpl(nm, amt, a, st.color, st.img, ask, catRef[0]))
            } else {
                val e = tpls[idx]
                e.label = nm
                e.amt = amt
                e.acc = a
                e.color = st.color
                e.img = st.img
                e.ask = ask
                e.cat = catRef[0]
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

    // ---------- special template: asks for the amount on every tap ----------
    private fun openAskAmount(t: Tpl) {
        val f = frame ?: return
        if (sheet != null) return
        val expense = t.ask == 1
        val c = if (expense) DANGER else ACCENT
        val acc = accOf(t.acc) ?: activeAcc()
        val buf = StringBuilder(if (t.amt > 0.0) raw(t.amt) else "")

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
        }
        sh.addView(
            View(this).apply { background = box(BORDER) },
            lp(dp(40), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; setMargins(0, 0, 0, dp(12)) }
        )
        sh.addView(tv(t.label, 18f, TEXT, true, user = true).apply { setPadding(dp(2), 0, 0, 0) })
        sh.addView(tv(acc.name, 12f, SUB, false, true).apply { setPadding(dp(2), dp(2), 0, dp(10)) })

        val amtBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = box(BG, c)
        }
        val amtTv = tv("0", 34f, SUB, true)
        amtBox.addView(amtTv, lp(0, WRAP, 1f))
        sh.addView(amtBox, lp(MATCH, WRAP))

        val err = tv("", 12f, DANGER).apply {
            setPadding(dp(2), dp(8), 0, 0)
            visibility = View.GONE
        }
        sh.addView(err)

        fun refresh() {
            val txt = buf.toString()
            val v = txt.toDoubleOrNull() ?: 0.0
            val sign = if (v == 0.0) "" else if (expense) "-" else "+"
            amtTv.text = money(sign, grp(txt))
            amtTv.setTextColor(if (buf.isEmpty()) SUB else c)
            err.visibility = View.GONE
        }

        val pad = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        val keyRows = listOf(
            listOf("7", "8", "9"),
            listOf("4", "5", "6"),
            listOf("1", "2", "3"),
            listOf(".", "0", "←")
        )
        for (rw in keyRows) {
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
            background = tap(c)
            setOnClickListener {
                val v = r2(buf.toString().toDoubleOrNull() ?: 0.0)
                if (v == 0.0) {
                    err.text = s("Enter an amount")
                    err.visibility = View.VISIBLE
                    return@setOnClickListener
                }
                closeSheet()
                addTx(t.label, if (expense) -v else v, acc.id, -1, t.cat)
                buzz()
                if (autoClose) quit() else render()
            }
        }
        pressFx(saveBtn, 0.97f)
        btns.addView(saveBtn, lp(0, dp(52), 1.5f))
        sh.addView(btns)

        val scroller = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(sh)
        }
        scrim.addView(scroller, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
        scrim.alpha = 0f
        scroller.translationY = dp(48).toFloat()
        f.addView(scrim, FrameLayout.LayoutParams(MATCH, MATCH))
        sheet = scrim
        scrim.animate().alpha(1f).setDuration(DUR).setInterpolator(ease).start()
        scroller.animate().translationY(0f).setDuration(DUR + 60L).setInterpolator(ease).start()
        refresh()
    }

    private var over: View? = null

    private fun closeOver() {
        over?.let { (it.parent as? ViewGroup)?.removeView(it) }
        over = null
    }

    // same look as openConfirm, but it opens above a sheet that is already open (the category editor)
    private fun openConfirmOver(title: String, msg: String, okLabel: String, onOk: () -> Unit) {
        val f = frame ?: return
        if (over != null) return
        hideKb()
        val scrim = FrameLayout(this).apply {
            setBackgroundColor(0xB3000000.toInt())
            setOnClickListener { closeOver() }
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
            setOnClickListener { closeOver() }
        }
        pressFx(cancelBtn, 0.97f)
        btns.addView(cancelBtn, lp(0, dp(52), 1f).apply { setMargins(0, 0, dp(8), 0) })
        val okBtn = tv(okLabel, 16f, BG, true).apply {
            gravity = Gravity.CENTER
            background = tap(DANGER)
            setOnClickListener {
                closeOver()
                onOk()
            }
        }
        pressFx(okBtn, 0.97f)
        btns.addView(okBtn, lp(0, dp(52), 1.5f))
        sh.addView(btns)
        scrim.addView(sh, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
        f.addView(scrim, FrameLayout.LayoutParams(MATCH, MATCH))
        over = scrim
    }

    private fun closeSheet() {
        closeOver()
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
        tplOnlyRef: BooleanArray? = null,
        askRef: BooleanArray? = null,
        catRef: Array<String>? = null,
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
            hint = s(nameHint)
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
        val amtLabelTv = tv(amountLabel, 12f, SUB).apply { setPadding(dp(2), dp(12), 0, dp(4)) }
        if (askRef != null && askRef[0]) amtLabelTv.text = s("Default amount (optional)")
        sh.addView(amtLabelTv)
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
                val chip = tv(o.second, 13f, SUB, true, user = true).apply {
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

        // category (templates only)
        val catFlow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val catInput = EditText(this).apply {
            hint = s("Category name")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_DONE
            filters = arrayOf<InputFilter>(InputFilter.LengthFilter(18))
            textSize = 14f
            typeface = fontReg
            setTextColor(TEXT)
            setHintTextColor(SUB)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = box(BG, BORDER)
        }
        val catDel = tv("Delete", 13f, DANGER, true).apply {
            setPadding(dp(12), dp(10), dp(8), dp(10))
            visibility = View.GONE
        }
        val catOk = tv("Add", 13f, BG, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(9), dp(14), dp(9))
        }
        pressFx(catOk, 0.96f)
        val catEdit = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        catEdit.addView(catInput, lp(0, WRAP, 1f))
        catEdit.addView(catOk, lp(WRAP, WRAP).apply { setMargins(dp(4), 0, 0, 0) })
        catEdit.addView(catDel, lp(WRAP, WRAP))
        var catCommit: () -> Unit = {}
        var onKind: () -> Unit = {}
        if (catRef != null) {
            sh.addView(tv("Category", 12f, SUB).apply { setPadding(dp(2), dp(12), 0, dp(4)) })
            val catHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            catHost.addView(catFlow, lp(MATCH, WRAP))
            catHost.addView(catEdit, lp(MATCH, WRAP).apply { setMargins(0, dp(8), 0, 0) })
            sh.addView(catHost, lp(MATCH, WRAP))
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
                imgBtn.text = s(if (has) "Image ✓" else "Choose image")
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

        // template only: "Ask amount on tap" switch (special template)
        if (askRef != null) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(10), dp(12), dp(10))
                background = box(BG, BORDER)
            }
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(tv("Ask amount on tap", 14f, TEXT, true))
            col.addView(tv("Type the price every time, e.g. coffee or cigarettes", 12f, SUB).apply { setPadding(0, dp(2), dp(8), 0) })
            row.addView(col, lp(0, WRAP, 1f))
            row.addView(toggle(askRef[0], {
                askRef[0] = it
                amtLabelTv.text = s(if (it) "Default amount (optional)" else amountLabel)
            }), lp(dp(50), dp(28)))
            sh.addView(row, lp(MATCH, WRAP).apply { setMargins(0, dp(12), 0, 0) })
        }

        // account only: "Template only" switch
        if (tplOnlyRef != null) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(10), dp(12), dp(10))
                background = box(BG, BORDER)
            }
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(tv("Template only", 14f, TEXT, true))
            col.addView(tv("Hidden from the Income and Expense lists", 12f, SUB).apply { setPadding(0, dp(2), dp(8), 0) })
            row.addView(col, lp(0, WRAP, 1f))
            row.addView(toggle(tplOnlyRef[0], { tplOnlyRef[0] = it }), lp(dp(50), dp(28)))
            sh.addView(row, lp(MATCH, WRAP).apply { setMargins(0, dp(12), 0, 0) })
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
            amtTv.text = money(sign, grp(buf.toString()))
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
                if (catRef != null && catEdit.visibility == View.VISIBLE) catCommit()
                val nm = nameEt.text.toString().trim()
                val v = buf.toString().toDoubleOrNull() ?: 0.0
                val msg = when {
                    requireName && nm.isEmpty() -> "Enter a name"
                    requireAmount && v == 0.0 -> "Enter an amount"
                    else -> null
                }
                if (msg != null) {
                    err.text = s(msg)
                    err.visibility = View.VISIBLE
                    return@setOnClickListener
                }
                saved = true
                askRef?.let { it[1] = neg }
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

        val cr = catRef ?: arrayOf("")
        var catChips = ArrayList<TextView>()
        var catIds = ArrayList<String>()
        var catEditing: Cat? = null
        var shownKind = if (neg) 1 else 0
        val availC = (if (f.width > 0) f.width else resources.displayMetrics.widthPixels) - dp(36)
        fun catKind(): Int = if (neg) 1 else 0
        fun catColor(): Int = if (neg) DANGER else ACCENT

        fun styleCats() {
            val c = catColor()
            catChips.forEachIndexed { n, chip ->
                val on = catIds[n] == cr[0]
                chip.setTextColor(if (on) c else SUB)
                chip.background = box(if (on) BG else KEY, if (on) c else 0)
            }
            catOk.background = tap(c)
        }

        fun closeCatEdit() {
            if (catEdit.visibility != View.VISIBLE) return
            catEditing = null
            catInput.setText("")
            catEdit.visibility = View.GONE
            toAmount()
        }

        fun openCatEdit(target: Cat?) {
            catEditing = target
            catInput.setText(target?.let { cn(it) } ?: "")
            catInput.setSelection(catInput.text.length)
            catDel.visibility = if (target != null) View.VISIBLE else View.GONE
            catOk.text = s(if (target != null) "Save" else "Add")
            catEdit.visibility = View.VISIBLE
            catInput.requestFocus()
            catInput.postDelayed({
                getSystemService(InputMethodManager::class.java)
                    ?.showSoftInput(catInput, InputMethodManager.SHOW_IMPLICIT)
            }, 120)
        }

        fun fillCats() {
            catFlow.removeAllViews()
            val list = ArrayList<TextView>()
            val ids = ArrayList<String>()
            val k = catKind()
            for (c in cats) {
                if (c.kind != k) continue
                val chip = chipView(cn(c), true)
                chip.isHapticFeedbackEnabled = false
                chip.setOnLongClickListener { buzz(); openCatEdit(c); true }
                list.add(chip)
                ids.add(c.id)
            }
            list.forEachIndexed { n, chip ->
                chip.setOnClickListener {
                    cr[0] = if (cr[0] == ids[n]) "" else ids[n]
                    closeCatEdit()
                    styleCats()
                }
            }
            val plus = chipView("+ New", false)
            plus.background = box(BG, BORDER)
            plus.setOnClickListener {
                if (catEdit.visibility == View.VISIBLE && catEditing == null) closeCatEdit() else openCatEdit(null)
            }
            catChips = list
            catIds = ids
            if (cr[0].isNotEmpty() && cr[0] !in ids) cr[0] = ""
            val all = ArrayList<View>(list)
            all.add(plus)
            catFlow.addView(rowsView(packRows(all, availC)))
            styleCats()
        }

        fun commitCat() {
            val nm = catInput.text.toString().trim()
            val target = catEditing
            val k = catKind()
            if (nm.isNotEmpty()) {
                var same: String? = null
                for (c in cats) if (c.kind == k && c.id != target?.id && cn(c).equals(nm, true)) same = c.id
                if (same != null) {
                    cr[0] = same
                } else if (target != null) {
                    if (nm != cn(target)) target.name = nm
                    saveCfg()
                } else {
                    val nc = Cat("c_" + UUID.randomUUID().toString().substring(0, 8), nm, k)
                    cats.add(nc)
                    saveCfg()
                    cr[0] = nc.id
                }
            }
            closeCatEdit()
            fillCats()
        }

        catInput.setOnFocusChangeListener { _, has ->
            if (has) {
                pad.visibility = View.GONE
                amtBox.background = box(BG, BORDER)
                nameEt.background = box(BG, BORDER)
                catInput.background = box(BG, catColor())
            } else {
                catInput.background = box(BG, BORDER)
            }
        }
        catInput.setOnEditorActionListener { _, _, _ -> commitCat(); true }
        catOk.setOnClickListener { commitCat() }
        val doDelCat: () -> Unit = {
            val target = catEditing
            if (target != null) {
                cats.remove(target)
                tpls.forEach { if (it.cat == target.id) it.cat = "" }
                if (cr[0] == target.id) cr[0] = ""
                saveCfg()
            }
            closeCatEdit()
            fillCats()
        }
        catDel.setOnClickListener {
            val target = catEditing
            if (target != null) openConfirmOver("Delete category?", catDelMsg(target), "Delete") {
                tpls.forEach { if (it.cat == target.id) it.cat = "" }
                doDelCat()
            }
        }
        catCommit = { commitCat() }
        onKind = {
            if (catKind() != shownKind) {
                shownKind = catKind()
                closeCatEdit()
                cr[0] = ""
                fillCats()
            }
        }
        if (catRef != null) fillCats()

        nameEt.setOnFocusChangeListener { _, has -> if (has) showPad(false) }
        nameEt.setOnEditorActionListener { _, _, _ -> toAmount(); true }
        amtBox.setOnClickListener { toAmount() }
        segNeg.setOnClickListener { neg = true; refresh(); onKind(); toAmount() }
        segPos.setOnClickListener { neg = false; refresh(); onKind(); toAmount() }

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