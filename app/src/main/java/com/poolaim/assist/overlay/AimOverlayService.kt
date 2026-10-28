package com.poolaim.assist.overlay

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.RectF
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.view.*
import android.view.OrientationEventListener
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.provider.Settings
import androidx.appcompat.view.ContextThemeWrapper
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.NotificationCompat
import com.poolaim.assist.R
import com.poolaim.assist.AppSettings
import com.poolaim.assist.physics.Vector2D
import com.poolaim.assist.vision.ScreenAimTracker
import kotlin.math.*

/**
 * Foreground Service managing:
 * 1. ScreenAimTracker real-time zero-freeze computer vision engine.
 * 2. Full-screen transparent AimCanvasView rendering continuous laser trajectories.
 * 3. Draggable Wizard iOS-style Mod Menu (Default open, draggable header, collapsible floating icon).
 * 4. Orientation change listener (auto-adapts from portrait to landscape when entering 8 Ball Pool).
 * 5. Shake-to-Toggle accelerometer sensor for instant hide/show of the Mod Menu window.
 */
class AimOverlayService : Service(), SensorEventListener {

    private lateinit var windowManager: WindowManager
    private var canvasView: AimCanvasView? = null
    private var modMenuView: View? = null
    private var modMenuParams: WindowManager.LayoutParams? = null

    // Screen tracking
    private var mediaProjection: MediaProjection? = null
    private var aimTracker: ScreenAimTracker? = null

    // Multi-Option Accuracy Engine
    private var engineOptionRay = true
    private var engineOptionMask = true
    private var engineOptionRing = false

    // Display rotation & orientation listeners
    private var displayManager: DisplayManager? = null
    private var displayListener: DisplayManager.DisplayListener? = null
    private var orientationListener: OrientationEventListener? = null
    private var lastRotation = -1

    // Shake sensor
    private var sensorManager: SensorManager? = null
    private var accel = 10f
    private var accelCurrent = SensorManager.GRAVITY_EARTH
    private var accelLast = SensorManager.GRAVITY_EARTH
    private var overlayVisible = true
    private var isModMenuWindowVisible = true

    companion object {
        const val CHANNEL_ID = "pool_aim_assist_channel"
        const val NOTIFICATION_ID = 101
        const val ACTION_STOP = "com.poolaim.assist.ACTION_STOP"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        @Volatile
        var isRunning = false
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            createNotificationChannel()

            // Safe startup notification (MediaProjection type must ONLY be passed in onStartCommand after token is ready!)
            try {
                startForeground(NOTIFICATION_ID, createNotification())
            } catch (e: Throwable) {
                e.printStackTrace()
            }

            // Guard overlay view creation to prevent BadTokenException if permission revoked
            val canOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Settings.canDrawOverlays(this)
            } else {
                true
            }

            if (canOverlay) {
                initCanvasOverlay()
                initModMenu()
            }

            initShakeSensor()
            initDisplayListener()

            isRunning = true
            Toast.makeText(this, "🎯 تم تشغيل Aim Master! نافذة المود منيو جاهزة على الشاشة 🎱", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
            try {
                startForeground(NOTIFICATION_ID, createNotification())
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Pool Aim Live Assist Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Foreground Service for 8 Ball Pool Aim Master Mod Assist"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val stopIntent = Intent(this, AimOverlayService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Aiming Master Pro")
            .setContentText("المساعد المباشر ومحرك التتبع التلقائي يعملان الآن 🎯")
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "إيقاف", stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    @SuppressLint("InflateParams")
    private fun initCanvasOverlay() {
        try {
            val themeWrapper = ContextThemeWrapper(this, R.style.Theme_PoolAimAssist)
            canvasView = AimCanvasView(themeWrapper)
            
            // Sync with persistent AppSettings
            val settings = AppSettings.get(this)
            canvasView?.applySettings(settings)

            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }

            windowManager.addView(canvasView, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    private fun initModMenu() {
        try {
            val themeWrapper = ContextThemeWrapper(this, R.style.Theme_PoolAimAssist)
            val inflater = LayoutInflater.from(themeWrapper)
            modMenuView = inflater.inflate(R.layout.overlay_mod_menu, null)

            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val density = resources.displayMetrics.density
            val displaySize = Point()
            windowManager.defaultDisplay.getRealSize(displaySize)
            val screenW = maxOf(displaySize.x, displaySize.y)
            val screenH = minOf(displaySize.x, displaySize.y)

            // Responsive dimensions for landscape screens: 370dp wide, 240dp high
            val expandedWidth = minOf((370 * density).toInt(), (screenW * 0.70f).toInt())
            val expandedHeight = minOf((245 * density).toInt(), (screenH * 0.85f).toInt())
            val collapsedSize = (54 * density).toInt()

            val params = WindowManager.LayoutParams(
                expandedWidth,
                expandedHeight,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = (24 * density).toInt()
                y = (32 * density).toInt()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            modMenuParams = params

            val layoutCollapsedBadge = modMenuView?.findViewById<View>(R.id.layoutCollapsedBadge)
            val layoutExpandedMenu = modMenuView?.findViewById<View>(R.id.layoutExpandedMenu)
            val layoutHeaderDrag = modMenuView?.findViewById<View>(R.id.layoutHeaderDrag)
            val btnMinimizeMenu = modMenuView?.findViewById<View>(R.id.btnMinimizeMenu)

            // Start EXPANDED by default so user sees menu immediately!
            layoutCollapsedBadge?.visibility = View.GONE
            layoutExpandedMenu?.visibility = View.VISIBLE

            // Draggable Floating Pill / Badge
            var initialX = 0
            var initialY = 0
            var initialTouchX = 0f
            var initialTouchY = 0f
            var isClick = false

            fun setupDragTouch(targetView: View?, onClickAction: () -> Unit) {
                targetView?.setOnTouchListener { _, event ->
                    val p = modMenuParams ?: return@setOnTouchListener false
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            initialX = p.x
                            initialY = p.y
                            initialTouchX = event.rawX
                            initialTouchY = event.rawY
                            isClick = true
                            true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = event.rawX - initialTouchX
                            val dy = event.rawY - initialTouchY
                            if (abs(dx) > 8 || abs(dy) > 8) {
                                isClick = false
                            }
                            p.x = initialX + dx.toInt()
                            p.y = initialY + dy.toInt()
                            modMenuView?.let { windowManager.updateViewLayout(it, p) }
                            true
                        }
                        MotionEvent.ACTION_UP -> {
                            if (isClick) {
                                onClickAction()
                            }
                            true
                        }
                        else -> false
                    }
                }
            }

            // Click Badge -> Expand Menu
            setupDragTouch(layoutCollapsedBadge) {
                layoutCollapsedBadge?.visibility = View.GONE
                layoutExpandedMenu?.visibility = View.VISIBLE
                modMenuParams?.width = expandedWidth
                modMenuParams?.height = WindowManager.LayoutParams.WRAP_CONTENT
                modMenuView?.let { windowManager.updateViewLayout(it, modMenuParams) }
            }

            // Drag Header of Expanded Menu
            setupDragTouch(layoutHeaderDrag) {}

            // Click Minimize -> Collapse into 8-Ball Badge
            btnMinimizeMenu?.setOnClickListener {
                layoutExpandedMenu?.visibility = View.GONE
                layoutCollapsedBadge?.visibility = View.VISIBLE
                modMenuParams?.width = collapsedSize
                modMenuParams?.height = collapsedSize
                modMenuView?.let { windowManager.updateViewLayout(it, modMenuParams) }
            }

            // ========================================================
            // AIMING MASTER VIEWS & CONTROLS
            // ========================================================
            val appSettings = AppSettings.get(this)
            val viewAimingMasterMain = modMenuView?.findViewById<View>(R.id.viewAimingMasterMain)
            val viewCueForcePicker = modMenuView?.findViewById<View>(R.id.viewCueForcePicker)
            val btnTryPremium = modMenuView?.findViewById<View>(R.id.btnTryPremium)
            val cardCueForce = modMenuView?.findViewById<View>(R.id.cardCueForce)
            val layoutSegmentedBarMain = modMenuView?.findViewById<android.widget.LinearLayout>(R.id.layoutSegmentedBarMain)
            val tvCueForceValue = modMenuView?.findViewById<TextView>(R.id.tvCueForceValue)
            val switchCushionShots = modMenuView?.findViewById<SwitchCompat>(R.id.switchCushionShots)
            val switchThreeLines = modMenuView?.findViewById<SwitchCompat>(R.id.switchThreeLines)
            val btnCloseForcePicker = modMenuView?.findViewById<View>(R.id.btnCloseForcePicker)
            val colForceEven = modMenuView?.findViewById<android.widget.LinearLayout>(R.id.colForceEven)
            val colForceOdd = modMenuView?.findViewById<android.widget.LinearLayout>(R.id.colForceOdd)

            btnTryPremium?.setOnClickListener {
                Toast.makeText(this, "⭐ Aiming Master Premium Is Active & Unlocked!", Toast.LENGTH_SHORT).show()
            }

            fun populateSegmentedBar(container: android.widget.LinearLayout?, level: Int, barW: Int, barH: Int, margin: Int) {
                container?.removeAllViews()
                for (i in 0..11) {
                    val seg = View(this).apply {
                        val lp = android.widget.LinearLayout.LayoutParams((barW * density).toInt(), (barH * density).toInt()).apply {
                            marginEnd = (margin * density).toInt()
                        }
                        layoutParams = lp
                        val color = if (i <= level) {
                            if (level == 11 && i == 11) Color.parseColor("#38BDF8") else Color.parseColor("#22C55E")
                        } else {
                            Color.parseColor("#374151")
                        }
                        setBackgroundColor(color)
                    }
                    container?.addView(seg)
                }
            }

            fun updateMainCueForceDisplay() {
                val current = appSettings.cueForceLevel
                tvCueForceValue?.text = current.toString()
                populateSegmentedBar(layoutSegmentedBarMain, current, barW = 4, barH = 14, margin = 2)
            }

            fun populateCueForcePicker() {
                colForceEven?.removeAllViews()
                colForceOdd?.removeAllViews()

                val evens = listOf(0, 2, 4, 6, 8, 10)
                val odds = listOf(1, 3, 5, 7, 9, 11)

                fun addCardToCol(targetCol: android.widget.LinearLayout?, forceVal: Int) {
                    val isSelected = (forceVal == appSettings.cueForceLevel)
                    val card = android.widget.LinearLayout(this).apply {
                        orientation = android.widget.LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        val lp = android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                            (38 * density).toInt()
                        ).apply {
                            bottomMargin = (6 * density).toInt()
                        }
                        layoutParams = lp
                        setPadding((10 * density).toInt(), (4 * density).toInt(), (10 * density).toInt(), (4 * density).toInt())
                        setBackgroundResource(if (isSelected) R.drawable.bg_cue_force_item_selected else R.drawable.bg_cue_force_item)
                        isClickable = true
                        isFocusable = true

                        // Force Text
                        val tv = TextView(this@AimOverlayService).apply {
                            text = "Force $forceVal"
                            setTextColor(Color.parseColor("#F8FAFC"))
                            textSize = 12f
                            typeface = android.graphics.Typeface.DEFAULT_BOLD
                            val tlp = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                            layoutParams = tlp
                        }
                        addView(tv)

                        // Mini Bar
                        val miniBar = android.widget.LinearLayout(this@AimOverlayService).apply {
                            orientation = android.widget.LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                        }
                        populateSegmentedBar(miniBar, forceVal, barW = 3, barH = 10, margin = 1)
                        addView(miniBar)

                        setOnClickListener {
                            appSettings.cueForceLevel = forceVal
                            canvasView?.cueForceLevel = forceVal
                            updateMainCueForceDisplay()
                            viewCueForcePicker?.visibility = View.GONE
                            viewAimingMasterMain?.visibility = View.VISIBLE
                        }
                    }
                    targetCol?.addView(card)
                }

                for (f in evens) addCardToCol(colForceEven, f)
                for (f in odds) addCardToCol(colForceOdd, f)
            }

            // Initial display
            updateMainCueForceDisplay()

            // Open Cue Force Picker
            cardCueForce?.setOnClickListener {
                populateCueForcePicker()
                viewAimingMasterMain?.visibility = View.GONE
                viewCueForcePicker?.visibility = View.VISIBLE
            }

            // Close Cue Force Picker
            btnCloseForcePicker?.setOnClickListener {
                viewCueForcePicker?.visibility = View.GONE
                viewAimingMasterMain?.visibility = View.VISIBLE
            }

            // Toggles
            switchCushionShots?.isChecked = appSettings.isCushionShotsEnabled
            canvasView?.enableBankShots = appSettings.isCushionShotsEnabled
            switchCushionShots?.setOnCheckedChangeListener { _, isChecked ->
                appSettings.isCushionShotsEnabled = isChecked
                canvasView?.enableBankShots = isChecked
                canvasView?.postInvalidate()
            }

            switchThreeLines?.isChecked = appSettings.isThreeLinesEnabled
            canvasView?.enableCueBallPath = appSettings.isThreeLinesEnabled
            switchThreeLines?.setOnCheckedChangeListener { _, isChecked ->
                appSettings.isThreeLinesEnabled = isChecked
                canvasView?.enableCueBallPath = isChecked
                canvasView?.postInvalidate()
            }
            windowManager.addView(modMenuView, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun initDisplayListener() {
        try {
            displayManager = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            displayListener = object : DisplayManager.DisplayListener {
                override fun onDisplayAdded(displayId: Int) {}
                override fun onDisplayRemoved(displayId: Int) {}
                override fun onDisplayChanged(displayId: Int) {
                    handleRotationChange()
                }
            }
            displayManager?.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))

            orientationListener = object : OrientationEventListener(this, SensorManager.SENSOR_DELAY_NORMAL) {
                override fun onOrientationChanged(orientation: Int) {
                    if (orientation == ORIENTATION_UNKNOWN) return
                    val currentRotation = try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            display?.rotation ?: windowManager.defaultDisplay.rotation
                        } else {
                            @Suppress("DEPRECATION")
                            windowManager.defaultDisplay.rotation
                        }
                    } catch (e: Exception) {
                        @Suppress("DEPRECATION")
                        windowManager.defaultDisplay.rotation
                    }

                    if (currentRotation != lastRotation) {
                        lastRotation = currentRotation
                        handleRotationChange(currentRotation)
                    }
                }
            }
            if (orientationListener?.canDetectOrientation() == true) {
                orientationListener?.enable()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun handleRotationChange(rotation: Int = -1) {
        try {
            val rot = if (rotation >= 0) rotation else {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        display?.rotation ?: windowManager.defaultDisplay.rotation
                    } else {
                        @Suppress("DEPRECATION")
                        windowManager.defaultDisplay.rotation
                    }
                } catch (e: Exception) {
                    @Suppress("DEPRECATION")
                    windowManager.defaultDisplay.rotation
                }
            }

            val size = Point()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealSize(size)

            val isLandscape = size.x > size.y

            if (isLandscape) {
                // Game mode: ensure canvas overlay is active and calibrated
                canvasView?.visibility = if (canvasView?.isMasterOverlayEnabled == true) View.VISIBLE else View.GONE
                canvasView?.recalculateTableBounds(size.x, size.y)
                aimTracker?.onOrientationChanged(size.x, size.y, rot)
            } else {
                // Portrait mode: hide overlay cleanly to prevent UI clutter
                canvasView?.visibility = View.GONE
                aimTracker?.onOrientationChanged(size.x, size.y, rot)
            }

            // Reposition mod menu safely within current viewport bounds
            modMenuParams?.let { p ->
                val maxX = maxOf(0, size.x - p.width)
                val maxY = maxOf(0, size.y - p.height)
                p.x = p.x.coerceIn(0, maxX)
                p.y = p.y.coerceIn(0, maxY)
                modMenuView?.let { mv ->
                    if (mv.isAttachedToWindow) {
                        windowManager.updateViewLayout(mv, p)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun initShakeSensor() {
        try {
            sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
            val accelSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            if (accelSensor != null) {
                sensorManager?.registerListener(this, accelSensor, SensorManager.SENSOR_DELAY_UI)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun toggleModMenuWindowVisibility() {
        isModMenuWindowVisible = !isModMenuWindowVisible
        modMenuView?.visibility = if (isModMenuWindowVisible) View.VISIBLE else View.GONE
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        accelLast = accelCurrent
        accelCurrent = sqrt((x * x + y * y + z * z).toDouble()).toFloat()
        val delta = accelCurrent - accelLast
        accel = accel * 0.9f + delta

        // خاصية الهز لإخفاء وإظهار نافذة المود منيو فقط دون التأثير على خطوط وثقوب التصويب
        if (accel > 12f) {
            vibrateFeedback()
            toggleModMenuWindowVisibility()
            accel = 0f
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun vibrateFeedback() {
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(50)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Ensure overlays are attached if permission is granted
        val canOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
        if (canOverlay && canvasView == null) {
            initCanvasOverlay()
        }
        if (canOverlay && modMenuView == null) {
            initModMenu()
        }

        // Initialize MediaProjection and start real-time ScreenAimTracker
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA) as? Intent
        }

        if (resultCode == Activity.RESULT_OK && resultData != null) {
            try {
                val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = mpManager.getMediaProjection(resultCode, resultData)

                // Android 14+ STRICT REQUIREMENT: Call startForeground with FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                // only AFTER getMediaProjection is active!
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        startForeground(
                            NOTIFICATION_ID,
                            createNotification(),
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                        )
                    } else {
                        startForeground(NOTIFICATION_ID, createNotification())
                    }
                } catch (e: Throwable) {
                    e.printStackTrace()
                }

                mediaProjection?.let { mp ->
                    // Handle unexpected stop gracefully
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        mp.registerCallback(object : MediaProjection.Callback() {
                            override fun onStop() {
                                super.onStop()
                                stopSelf()
                            }
                        }, Handler(Looper.getMainLooper()))
                    }

                    aimTracker?.stop()
                    aimTracker = ScreenAimTracker(this, mp, object : ScreenAimTracker.OnAimDataListener {
                        override fun onAimUpdated(
                            cueBallPos: Vector2D,
                            aimAngleRad: Float,
                            powerPercent: Float,
                            targetBallPos: Vector2D?,
                            allBalls: List<Vector2D>,
                            spinX: Float,
                            spinY: Float,
                            matchDetected: Boolean,
                            tableBounds: RectF?,
                            isAiming: Boolean
                        ) {
                            canvasView?.updateAutoAim(
                                cue = cueBallPos,
                                angleRad = aimAngleRad,
                                power = powerPercent,
                                target = targetBallPos,
                                allBalls = allBalls,
                                spinX = spinX,
                                spinY = spinY,
                                matchActive = matchDetected,
                                detectedBounds = tableBounds,
                                isAiming = isAiming
                            )
                        }

                        override fun onMatchStateChanged(isMatch: Boolean) {
                            canvasView?.setMatchActive(isMatch)
                        }
                    })
                    if (canvasView?.isUserCalibrationSaved == true) {
                        aimTracker?.setCalibratedTableBounds(canvasView?.tableBounds)
                    }
                    aimTracker?.enableRayContinuity = engineOptionRay
                    aimTracker?.enableBallMaskExclusion = engineOptionMask
                    aimTracker?.isManualRingMode = engineOptionRing
                    aimTracker?.start()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        try {
            orientationListener?.disable()
            orientationListener = null

            displayListener?.let { displayManager?.unregisterDisplayListener(it) }
            displayListener = null

            aimTracker?.stop()
            aimTracker = null

            mediaProjection?.stop()
            mediaProjection = null

            sensorManager?.unregisterListener(this)

            canvasView?.let { if (it.isAttachedToWindow) windowManager.removeView(it) }
            canvasView = null

            modMenuView?.let { if (it.isAttachedToWindow) windowManager.removeView(it) }
            modMenuView = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
