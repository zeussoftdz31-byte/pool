package com.mecasoft.aimingmaster.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.*
import android.widget.*
import androidx.appcompat.view.ContextThemeWrapper
import androidx.appcompat.widget.SwitchCompat
import com.mecasoft.aimingmaster.R
import com.mecasoft.aimingmaster.data.AimPreferences
import kotlin.math.abs

class AimFloatingManager(
    private val context: Context,
    private val windowManager: WindowManager,
    private val canvasView: AssistLineView
) {
    private val prefs = AimPreferences.get(context)

    // 1. Floating 8-Ball Launcher
    private var ballView: View? = null
    private var ballParams: WindowManager.LayoutParams? = null

    // 2. Floating Aiming Master Menu
    private var menuView: View? = null
    private var menuParams: WindowManager.LayoutParams? = null
    private var isMenuShowing = false

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    fun init() {
        try {
            val themeWrapper = ContextThemeWrapper(context, R.style.Theme_AimingMaster)
            val inflater = LayoutInflater.from(themeWrapper)

            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val density = context.resources.displayMetrics.density
            val ballSize = (56 * density).toInt()

            // Dual-layer safe inflation of floating 8-Ball icon
            ballView = try {
                inflater.inflate(R.layout.overlay_floating_ball, null)
            } catch (e: Throwable) {
                e.printStackTrace()
                buildProgrammaticFloatingBall(density)
            }

            val bParams = WindowManager.LayoutParams(
                ballSize,
                ballSize,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = (16 * density).toInt()
                y = (180 * density).toInt()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            ballParams = bParams

            var initialX = 0
            var initialY = 0
            var touchStartX = 0f
            var touchStartY = 0f
            var isDragging = false
            val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

            ballView?.setOnTouchListener { _, event ->
                val p = ballParams ?: return@setOnTouchListener false
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = p.x
                        initialY = p.y
                        touchStartX = event.rawX
                        touchStartY = event.rawY
                        isDragging = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - touchStartX
                        val dy = event.rawY - touchStartY
                        if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                            isDragging = true
                        }
                        if (isDragging) {
                            p.x = initialX + dx.toInt()
                            p.y = initialY + dy.toInt()
                            ballView?.let { windowManager.updateViewLayout(it, p) }
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!isDragging) {
                            toggleMenu()
                        }
                        true
                    }
                    else -> false
                }
            }

            windowManager.addView(ballView, bParams)
            buildFloatingMenu(inflater, layoutType, density)

        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "⚠️ خطأ في تشغيل الأيقونة: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun buildProgrammaticFloatingBall(density: Float): View {
        val root = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                colors = intArrayOf(Color.parseColor("#4A4E55"), Color.parseColor("#0B0D10"))
                gradientType = GradientDrawable.RADIAL_GRADIENT
                gradientRadius = 28f * density
                setStroke((1 * density).toInt(), Color.parseColor("#3A3D42"))
            }
        }

        val whiteCircle = TextView(context).apply {
            text = "8"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#111111"))
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#F3F4F6"))
            }
        }
        val circleSize = (22 * density).toInt()
        root.addView(whiteCircle, FrameLayout.LayoutParams(circleSize, circleSize).apply {
            gravity = Gravity.CENTER
        })

        val checkBadge = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#22C55E"))
                setStroke((1.5f * density).toInt(), Color.parseColor("#121418"))
            }
        }
        val badgeSize = (14 * density).toInt()
        root.addView(checkBadge, FrameLayout.LayoutParams(badgeSize, badgeSize).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            marginEnd = (2 * density).toInt()
            bottomMargin = (2 * density).toInt()
        })

        return root
    }

    @SuppressLint("InflateParams")
    private fun buildFloatingMenu(inflater: LayoutInflater, layoutType: Int, density: Float) {
        menuView = inflater.inflate(R.layout.overlay_aiming_menu, null)

        val swGuideline = menuView?.findViewById<SwitchCompat>(R.id.switchMenuGuideline)
        val swCushion = menuView?.findViewById<SwitchCompat>(R.id.switchMenuCushion)
        val swThreeLine = menuView?.findViewById<SwitchCompat>(R.id.switchMenuThreeLine)
        val swHandles = menuView?.findViewById<SwitchCompat>(R.id.switchInteractiveHandles)
        val tvForceVal = menuView?.findViewById<TextView>(R.id.tvMenuForceVal)
        val rowForce = menuView?.findViewById<View>(R.id.rowMenuForce)
        val btnClose = menuView?.findViewById<View>(R.id.btnCloseMenu)
        val btnHide = menuView?.findViewById<View>(R.id.btnMenuHide)

        swGuideline?.isChecked = prefs.isGuidelinesEnabled
        swGuideline?.setOnCheckedChangeListener { _, isChecked ->
            prefs.isGuidelinesEnabled = isChecked
            canvasView.visibility = if (isChecked) View.VISIBLE else View.GONE
            canvasView.postInvalidate()
        }

        swCushion?.isChecked = prefs.isCushionShotsEnabled
        swCushion?.setOnCheckedChangeListener { _, isChecked ->
            prefs.isCushionShotsEnabled = isChecked
            canvasView.recomputeTrajectory()
        }

        swThreeLine?.isChecked = prefs.isThreeLinesEnabled
        swThreeLine?.setOnCheckedChangeListener { _, isChecked ->
            prefs.isThreeLinesEnabled = isChecked
            canvasView.recomputeTrajectory()
        }

        swHandles?.isChecked = prefs.isInteractiveHandlesEnabled
        swHandles?.setOnCheckedChangeListener { _, isChecked ->
            prefs.isInteractiveHandlesEnabled = isChecked
            updateCanvasTouchability(isChecked)
            canvasView.postInvalidate()
        }

        tvForceVal?.text = "Force ${prefs.cueForceLevel}"
        rowForce?.setOnClickListener {
            var f = prefs.cueForceLevel + 1
            if (f > 11) f = 0
            prefs.cueForceLevel = f
            tvForceVal?.text = "Force $f"
            canvasView.recomputeTrajectory()
        }

        btnClose?.setOnClickListener { hideMenu() }
        btnHide?.setOnClickListener { hideMenu() }

        menuParams = WindowManager.LayoutParams(
            (280 * density).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }
    }

    fun toggleMenu() {
        if (isMenuShowing) hideMenu() else showMenu()
    }

    fun showMenu() {
        if (isMenuShowing || menuView == null || menuParams == null) return
        try {
            windowManager.addView(menuView, menuParams)
            isMenuShowing = true
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun hideMenu() {
        if (!isMenuShowing || menuView == null) return
        try {
            windowManager.removeView(menuView)
            isMenuShowing = false
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun updateCanvasTouchability(interactive: Boolean) {
        val p = canvasView.layoutParams as? WindowManager.LayoutParams ?: return
        if (interactive) {
            p.flags = p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            p.flags = p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        try {
            windowManager.updateViewLayout(canvasView, p)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun destroy() {
        try {
            if (isMenuShowing && menuView != null) {
                windowManager.removeView(menuView)
            }
            ballView?.let {
                if (it.isAttachedToWindow) windowManager.removeView(it)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
