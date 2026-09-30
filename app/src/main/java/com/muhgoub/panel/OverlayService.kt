package com.muhgoub.panel

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

/**
 * خدمة اللوحة العايمة.
 * الكود مستقل تماماً: كل الواجهة (خلفيات، أزرار، أيقونة) بتتبني برمجياً
 * أو بأيقونات أندرويد القياسية، ومفيش أي نداء على layout/drawable/color من مجلد res.
 */
class OverlayService : Service() {

    companion object {
        const val CHANNEL_ID = "panel_overlay"
        const val NOTIF_ID = 1

        private const val KEY_HIDE_CAPTURE = "hide_capture"
        private const val KEY_CACHE_ACTIVE = "perf_cache_active"
        private const val KEY_CACHE_LEVEL = "perf_cache_level"
        private const val KEY_GROUP1 = "grp1"
        private const val KEY_GROUP2 = "grp2"

        private val CHECK_LABELS = listOf(
            "حبل 1", "حبل٢",
            "كين٣", "عمو٤",
            "جين٥", "كين٦",
            "تفعيل رقم ٧", "تفعيل رقم ٨",
            "تفعيل رقم ٩", "تفعيل رقم ١٠",
            "تفعيل رقم ١١", "تفعيل رقم ١٢"
        )
        private val GROUP1 = listOf("محجوب", "تامر", "Off")
        private val GROUP2 = listOf("احمد", "كريم", "عمو", "Off")

        private val TEAL = Color.parseColor("#2DD9D0")
        private val CYAN = Color.parseColor("#00E5FF")
        private val PANEL_BG = Color.parseColor("#D0282828")
        private val PANEL_STROKE = Color.parseColor("#555555")
        private val ITEM_BG = Color.parseColor("#803A3A3A")
        private val ITEM_BG_ON = Color.parseColor("#332DD9D0")
        private val ITEM_STROKE = Color.parseColor("#606060")
        private val PILL_STROKE = Color.parseColor("#9A9A9A")
        private val BUBBLE_BG = Color.parseColor("#E6282828")
        private val OFF_TEXT = Color.parseColor("#BDBDBD")
    }

    private lateinit var wm: WindowManager
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var bubbleParams: WindowManager.LayoutParams

    private var rootView: View? = null
    private var bubble: View? = null
    private var statusView: TextView? = null
    private var panelShown = false
    private var snapAnim: ValueAnimator? = null

    // SharedPreferences القياسية مباشرة (بدون كلاس Prefs خارجي)
    private lateinit var sp: SharedPreferences

    // Listener صريح (object) بدل lambda مجهولة
    private val prefListener = object : SharedPreferences.OnSharedPreferenceChangeListener {
        override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
            if (key == KEY_HIDE_CAPTURE) applySecureFlag()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        sp = getSharedPreferences("panel_prefs", Context.MODE_PRIVATE)
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForeground(NOTIF_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (rootView == null) showOverlay()
        return START_STICKY
    }

    override fun onDestroy() {
        sp.unregisterOnSharedPreferenceChangeListener(prefListener)
        snapAnim?.cancel()
        rootView?.animate()?.cancel()
        rootView?.let {
            try {
                wm.removeView(it)
            } catch (e: Exception) {
            }
        }
        bubble?.let {
            try {
                wm.removeView(it)
            } catch (e: Exception) {
            }
        }
        rootView = null
        bubble = null
        statusView = null
        panelShown = false
        super.onDestroy()
    }

    // ---------- notification ----------

    @Suppress("DEPRECATION")
    private fun buildNotification(): Notification {
        val builder: Notification.Builder
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "MUHGOUB", NotificationManager.IMPORTANCE_LOW)
            )
            builder = Notification.Builder(this, CHANNEL_ID)
        } else {
            builder = Notification.Builder(this)
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT
        )
        return builder
            .setContentTitle("MUHGOUB")
            .setContentText("لوحة التحكم تعمل")
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    // ---------- helpers ----------

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    private fun mm(v: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_MM, v, resources.displayMetrics
    ).toInt()

    @Suppress("DEPRECATION")
    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

    // خلفية برمجية (مستطيل بحواف دائرية أو دايرة) بدل ملفات drawable
    private fun makeShape(
        fill: Int,
        stroke: Int,
        strokeDp: Int,
        radiusDp: Int,
        oval: Boolean = false
    ): GradientDrawable {
        val d = GradientDrawable()
        d.setShape(if (oval) GradientDrawable.OVAL else GradientDrawable.RECTANGLE)
        d.setColor(fill)
        if (strokeDp > 0) d.setStroke(dp(strokeDp), stroke)
        if (!oval) d.cornerRadius = dp(radiusDp).toFloat()
        return d
    }

    // ---------- overlay window ----------

    private fun showOverlay() {
        ensureDefaults()

        val dm = resources.displayMetrics
        val view = buildPanelView()

        val w = mm(60f).coerceAtMost(dm.widthPixels) // 6 سم
        val h = mm(70f).coerceAtMost(dm.heightPixels) // 7 سم
        params = WindowManager.LayoutParams(
            w, h, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = (dm.widthPixels - w) / 2
        params.y = dp(120)

        if (sp.getBoolean(KEY_HIDE_CAPTURE, false)) {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_SECURE
        }

        createBubble()
        updatePerformanceCache()

        // الترتيب مهم: القايمة الأول ثم الأيقونة، فالأيقونة تفضل فوق القايمة دايماً
        wm.addView(view, params)
        rootView = view
        panelShown = true
        bubble?.let { wm.addView(it, bubbleParams) }
        sp.registerOnSharedPreferenceChangeListener(prefListener)
    }

    private fun buildPanelView(): View {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.layoutDirection = View.LAYOUT_DIRECTION_RTL
        root.background = makeShape(PANEL_BG, PANEL_STROKE, 1, 20)

        // ----- الهيدر (قابل للسحب) -----
        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.layoutDirection = View.LAYOUT_DIRECTION_LTR
        header.setPadding(dp(10), 0, dp(10), 0)
        header.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(52)
        )

        val close = ImageView(this)
        close.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
        close.setColorFilter(Color.WHITE)
        close.setPadding(dp(4), dp(4), dp(4), dp(4))
        close.isClickable = true
        close.isFocusable = true
        close.contentDescription = "close"
        close.layoutParams = LinearLayout.LayoutParams(dp(34), dp(34))
        close.setOnClickListener { hidePanel() }

        val dot = View(this)
        dot.background = makeShape(TEAL, TEAL, 0, 0, true)
        val dotLp = LinearLayout.LayoutParams(dp(8), dp(8))
        dotLp.marginStart = dp(8)
        dot.layoutParams = dotLp

        val title = TextView(this)
        title.text = "MUHGOUB"
        title.gravity = Gravity.CENTER
        title.setTextColor(Color.WHITE)
        title.textSize = 15f
        title.setTypeface(null, Typeface.BOLD)
        title.layoutParams = LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        )

        val logo = ImageView(this)
        logo.setImageResource(android.R.drawable.sym_def_app_icon)
        logo.contentDescription = "MUHGOUB"
        logo.layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))

        header.addView(close)
        header.addView(dot)
        header.addView(title)
        header.addView(logo)
        setupDrag(header)

        // ----- المحتوى -----
        val content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(dp(6), dp(6), dp(6), dp(6))

        buildChecks(content)
        addSpace(content, 14)
        buildGroup(content, KEY_GROUP1, GROUP1)
        addSpace(content, 10)
        buildGroup(content, KEY_GROUP2, GROUP2)
        addSpace(content, 10)

        val status = TextView(this)
        status.gravity = Gravity.CENTER
        status.setTextColor(TEAL)
        status.textSize = 13f
        status.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        content.addView(status)
        statusView = status

        val scroll = ScrollView(this)
        scroll.overScrollMode = View.OVER_SCROLL_NEVER
        scroll.isVerticalScrollBarEnabled = false
        scroll.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        )
        scroll.addView(
            content,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        root.addView(header)
        root.addView(scroll)
        return root
    }

    private fun applySecureFlag() {
        if (!::params.isInitialized) return
        val hide = sp.getBoolean(KEY_HIDE_CAPTURE, false)
        params.flags = if (hide) {
            params.flags or WindowManager.LayoutParams.FLAG_SECURE
        } else {
            params.flags and WindowManager.LayoutParams.FLAG_SECURE.inv()
        }
        rootView?.let {
            try {
                wm.updateViewLayout(it, params)
            } catch (e: Exception) {
            }
        }

        if (::bubbleParams.isInitialized) {
            bubbleParams.flags = if (hide) {
                bubbleParams.flags or WindowManager.LayoutParams.FLAG_SECURE
            } else {
                bubbleParams.flags and WindowManager.LayoutParams.FLAG_SECURE.inv()
            }
            bubble?.let {
                try {
                    wm.updateViewLayout(it, bubbleParams)
                } catch (e: Exception) {
                }
            }
        }
    }

    // ---------- floating bubble (always on top of the panel) ----------

    private fun createBubble() {
        val dm = resources.displayMetrics
        val size = dp(52)
        bubbleParams = WindowManager.LayoutParams(
            size, size, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        bubbleParams.gravity = Gravity.TOP or Gravity.START
        // تبدأ في الركن العلوي الأيمن فوق القايمة
        bubbleParams.x = dm.widthPixels - size - dp(6)
        bubbleParams.y = dp(60)

        if (sp.getBoolean(KEY_HIDE_CAPTURE, false)) {
            bubbleParams.flags = bubbleParams.flags or WindowManager.LayoutParams.FLAG_SECURE
        }

        val b = ImageView(this)
        b.setImageResource(android.R.drawable.sym_def_app_icon) // أيقونة أندرويد قياسية
        b.background = makeShape(BUBBLE_BG, CYAN, 2, 0, true) // خلفية شفافة برمجية
        b.scaleType = ImageView.ScaleType.FIT_CENTER
        b.setPadding(dp(10), dp(10), dp(10), dp(10))
        b.alpha = 0.9f
        b.contentDescription = "MUHGOUB"
        b.isClickable = true
        b.setOnClickListener { togglePanel() }
        setupBubbleTouch(b)
        bubble = b
    }

    private fun togglePanel() {
        if (panelShown) hidePanel() else showPanel()
    }

    private fun showPanel() {
        val panel = rootView ?: return
        if (panelShown) return
        panelShown = true

        // القايمة ترجع تستقبل اللمس، وتظهر بحركة fade + تكبير خفيف
        params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        try {
            wm.updateViewLayout(panel, params)
        } catch (e: Exception) {
        }

        panel.animate().cancel()
        panel.visibility = View.VISIBLE
        panel.alpha = 0f
        panel.scaleX = 0.92f
        panel.scaleY = 0.92f
        panel.animate()
            .alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(200)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun hidePanel() {
        val panel = rootView ?: return
        if (!panelShown) return
        panelShown = false

        panel.animate().cancel()
        panel.animate()
            .alpha(0f).scaleX(0.92f).scaleY(0.92f)
            .setDuration(160)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                if (!panelShown && rootView != null) {
                    panel.visibility = View.GONE
                    // نافذة شفافة لا تستقبل اللمس، فمتحجبش اللي تحتها
                    params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    try {
                        wm.updateViewLayout(panel, params)
                    } catch (e: Exception) {
                    }
                }
            }
            .start()
    }

    private fun setupBubbleTouch(b: View) {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false
        b.setOnTouchListener { v, e ->
            val dm = resources.displayMetrics
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    snapAnim?.cancel()
                    startX = bubbleParams.x
                    startY = bubbleParams.y
                    touchX = e.rawX
                    touchY = e.rawY
                    moved = false
                    v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(90).start()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - touchX
                    val dy = e.rawY - touchY
                    if (!moved && (abs(dx) > slop || abs(dy) > slop)) moved = true
                    if (moved) {
                        // تفضل جوه حدود الشاشة أثناء السحب
                        bubbleParams.x = (startX + dx).toInt()
                            .coerceIn(0, (dm.widthPixels - bubbleParams.width).coerceAtLeast(0))
                        bubbleParams.y = (startY + dy).toInt()
                            .coerceIn(0, (dm.heightPixels - bubbleParams.height).coerceAtLeast(0))
                        try {
                            wm.updateViewLayout(v, bubbleParams)
                        } catch (ex: Exception) {
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(140)
                        .setInterpolator(OvershootInterpolator()).start()
                    if (moved) {
                        snapToEdge(v)
                    } else {
                        v.performClick()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
                    true
                }
                else -> false
            }
        }
    }

    // بعد ما تسيب الأيقونة تنزلق بنعومة لأقرب حافة
    private fun snapToEdge(v: View) {
        val dm = resources.displayMetrics
        val margin = dp(6)
        val left = margin
        val right = (dm.widthPixels - bubbleParams.width - margin).coerceAtLeast(left)
        val target = if (bubbleParams.x + bubbleParams.width / 2 < dm.widthPixels / 2) left else right

        snapAnim?.cancel()
        val anim = ValueAnimator.ofInt(bubbleParams.x, target)
        anim.duration = 220
        anim.interpolator = DecelerateInterpolator()
        anim.addUpdateListener {
            bubbleParams.x = it.animatedValue as Int
            try {
                wm.updateViewLayout(v, bubbleParams)
            } catch (ex: Exception) {
            }
        }
        snapAnim = anim
        anim.start()
    }

    private fun setupDrag(header: View) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        header.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = e.rawX
                    touchY = e.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (e.rawX - touchX).toInt()
                    params.y = startY + (e.rawY - touchY).toInt()
                    rootView?.let {
                        try {
                            wm.updateViewLayout(it, params)
                        } catch (ex: Exception) {
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    // ---------- content: checkboxes ----------

    private fun buildChecks(container: LinearLayout) {
        for (r in 0 until CHECK_LABELS.size / 2) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            for (c in 0..1) {
                val idx = r * 2 + c
                row.addView(createCheckItem(CHECK_LABELS[idx], "chk_$idx"))
            }
            container.addView(row)
        }
    }

    private fun createCheckItem(label: String, key: String): View {
        val item = RelativeLayout(this)
        item.isClickable = true
        item.isFocusable = true
        val itemLp = LinearLayout.LayoutParams(0, dp(46), 1f)
        itemLp.setMargins(dp(4), dp(4), dp(4), dp(4))
        item.layoutParams = itemLp

        val box = CheckBox(this)
        box.buttonTintList = ColorStateList.valueOf(TEAL)
        box.isClickable = false
        box.isFocusable = false
        box.minWidth = 0
        box.minHeight = 0
        box.scaleX = 0.85f
        box.scaleY = 0.85f
        val boxLp = RelativeLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        boxLp.addRule(RelativeLayout.ALIGN_PARENT_LEFT)
        boxLp.addRule(RelativeLayout.CENTER_VERTICAL)
        boxLp.leftMargin = dp(4)
        box.layoutParams = boxLp

        val tv = TextView(this)
        tv.text = label
        tv.maxLines = 1
        tv.setTextColor(Color.WHITE)
        tv.textSize = 16f
        val tvLp = RelativeLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        tvLp.addRule(RelativeLayout.CENTER_IN_PARENT)
        tv.layoutParams = tvLp

        item.addView(box)
        item.addView(tv)

        val ctx = this
        fun render() {
            val on = sp.getBoolean(key, false)
            box.isChecked = on
            item.background = if (on) {
                makeShape(ITEM_BG_ON, TEAL, 2, 14)
            } else {
                makeShape(ITEM_BG, ITEM_STROKE, 2, 14)
            }
        }
        render()

        item.setOnClickListener {
            val newState = !sp.getBoolean(key, false)
            sp.edit().putBoolean(key, newState).apply() // حفظ في SharedPreferences
            render() // تحديث الواجهة حياً
            updatePerformanceCache()
            toast(newState)
        }
        return item
    }

    // ---------- content: pill groups ----------

    private fun buildGroup(container: LinearLayout, key: String, labels: List<String>) {
        val offIndex = labels.lastIndex
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )

        val pills = ArrayList<TextView>()
        for (label in labels) {
            val pill = TextView(this)
            pill.text = label
            pill.gravity = Gravity.CENTER
            pill.textSize = 16f
            pill.maxLines = 1
            pill.isClickable = true
            pill.isFocusable = true
            val lp = LinearLayout.LayoutParams(0, dp(52), 1f)
            lp.setMargins(dp(4), dp(4), dp(4), dp(4))
            pill.layoutParams = lp
            pills.add(pill)
        }

        val ctx = this
        fun render() {
            val sel = sp.getInt(key, offIndex)
            for (i in pills.indices) {
                val pill = pills[i]
                val active = i == sel && i != offIndex
                pill.background = if (active) {
                    makeShape(TEAL, TEAL, 2, 18)
                } else {
                    makeShape(Color.TRANSPARENT, PILL_STROKE, 2, 18)
                }
                pill.setTextColor(if (i == offIndex) OFF_TEXT else Color.WHITE)
            }
        }

        for (i in pills.indices) {
            val pill = pills[i]
            pill.setOnClickListener {
                val old = sp.getInt(key, offIndex)
                val newSel = if (i == old) offIndex else i
                sp.edit().putInt(key, newSel).apply() // حفظ في SharedPreferences
                render() // تحديث الواجهة حياً
                updatePerformanceCache()
                toast(newSel != offIndex)
            }
            row.addView(pill)
        }
        render()
        container.addView(row)
    }

    private fun addSpace(container: LinearLayout, heightDp: Int) {
        container.addView(View(this), LinearLayout.LayoutParams(1, dp(heightDp)))
    }

    // ---------- state / performance cache ----------

    // بيكتب القيم الافتراضية (كلها Off) في SharedPreferences أول مرة بس
    private fun ensureDefaults() {
        val ed = sp.edit()
        for (i in CHECK_LABELS.indices) {
            if (!sp.contains("chk_$i")) ed.putBoolean("chk_$i", false)
        }
        if (!sp.contains(KEY_GROUP1)) ed.putInt(KEY_GROUP1, GROUP1.lastIndex)
        if (!sp.contains(KEY_GROUP2)) ed.putInt(KEY_GROUP2, GROUP2.lastIndex)
        ed.apply()
    }

    // معالجة الأداء: بتحسب الحالة الحالية، تخزنها في الكاش، وتحدّث سطر الحالة في اللوحة
    private fun updatePerformanceCache() {
        var active = 0
        for (i in CHECK_LABELS.indices) {
            if (sp.getBoolean("chk_$i", false)) active++
        }
        var level = active
        if (sp.getInt(KEY_GROUP1, GROUP1.lastIndex) != GROUP1.lastIndex) level++
        if (sp.getInt(KEY_GROUP2, GROUP2.lastIndex) != GROUP2.lastIndex) level++

        sp.edit().putInt(KEY_CACHE_ACTIVE, active).apply()
        sp.edit().putInt(KEY_CACHE_LEVEL, level).apply()

        statusView?.text = "المفعّل: $active / ${CHECK_LABELS.size}   |   المستوى: $level"
    }

    private fun toast(on: Boolean) {
        Toast.makeText(this, if (on) "تم التفعيل" else "تم القفل", Toast.LENGTH_SHORT).show()
    }
}
