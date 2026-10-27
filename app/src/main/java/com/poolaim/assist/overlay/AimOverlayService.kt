package com.poolaim.assist.overlay

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.view.*
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

    // Display rotation listener
    private var displayManager: DisplayManager? = null
    private var displayListener: DisplayManager.DisplayListener? = null

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
                modMenuParams?.height = expandedHeight
                modMenuView?.let { windowManager.updateViewLayout(it, modMenuParams) }
            }

            // Drag Header of Expanded Menu
            setupDragTouch(layoutHeaderDrag) {}

            // Click Minimize -> Collapse into 🎯 Badge
            btnMinimizeMenu?.setOnClickListener {
                layoutExpandedMenu?.visibility = View.GONE
                layoutCollapsedBadge?.visibility = View.VISIBLE
                modMenuParams?.width = collapsedSize
                modMenuParams?.height = collapsedSize
                modMenuView?.let { windowManager.updateViewLayout(it, modMenuParams) }
            }

            // ========================================================
            // WIZARD iOS TAB NAVIGATION
            // ========================================================
            val btnTabVisuals = modMenuView?.findViewById<View>(R.id.btnTabVisuals)
            val btnTabAimMenu = modMenuView?.findViewById<View>(R.id.btnTabAimMenu)
            val btnTabSettings = modMenuView?.findViewById<View>(R.id.btnTabSettings)

            val tvTabVisualsText = modMenuView?.findViewById<TextView>(R.id.tvTabVisualsText)
            val tvTabAimText = modMenuView?.findViewById<TextView>(R.id.tvTabAimText)
            val tvTabSettingsText = modMenuView?.findViewById<TextView>(R.id.tvTabSettingsText)

            val layoutContentVisuals = modMenuView?.findViewById<View>(R.id.layoutContentVisuals)
            val layoutContentAim = modMenuView?.findViewById<View>(R.id.layoutContentAim)
            val layoutContentSettings = modMenuView?.findViewById<View>(R.id.layoutContentSettings)
            val tvCurrentTabTitle = modMenuView?.findViewById<TextView>(R.id.tvCurrentTabTitle)

            fun switchTab(tabIndex: Int) {
                btnTabVisuals?.setBackgroundResource(0)
                btnTabAimMenu?.setBackgroundResource(0)
                btnTabSettings?.setBackgroundResource(0)

                tvTabVisualsText?.setTextColor(Color.parseColor("#94A3B8"))
                tvTabAimText?.setTextColor(Color.parseColor("#94A3B8"))
                tvTabSettingsText?.setTextColor(Color.parseColor("#94A3B8"))

                layoutContentVisuals?.visibility = View.GONE
                layoutContentAim?.visibility = View.GONE
                layoutContentSettings?.visibility = View.GONE

                when (tabIndex) {
                    0 -> { // Visuals (Active by default)
                        btnTabVisuals?.setBackgroundResource(R.drawable.bg_tab_active)
                        tvTabVisualsText?.setTextColor(Color.parseColor("#38BDF8"))
                        layoutContentVisuals?.visibility = View.VISIBLE
                        tvCurrentTabTitle?.text = "Visuals"
                    }
                    1 -> { // Aim Menu
                        btnTabAimMenu?.setBackgroundResource(R.drawable.bg_tab_active)
                        tvTabAimText?.setTextColor(Color.parseColor("#38BDF8"))
                        layoutContentAim?.visibility = View.VISIBLE
                        tvCurrentTabTitle?.text = "Aim"
                    }
                    2 -> { // Settings
                        btnTabSettings?.setBackgroundResource(R.drawable.bg_tab_active)
                        tvTabSettingsText?.setTextColor(Color.parseColor("#38BDF8"))
                        layoutContentSettings?.visibility = View.VISIBLE
                        tvCurrentTabTitle?.text = "Settings"
                    }
                }
            }

            btnTabVisuals?.setOnClickListener { switchTab(0) }
            btnTabAimMenu?.setOnClickListener { switchTab(1) }
            btnTabSettings?.setOnClickListener { switchTab(2) }

            // ========================================================
            // TAB 1: VISUALS (Table Zoom, Pocket Size, D-Pad, Auto-Fit)
            // ========================================================
            val seekTableZoom = modMenuView?.findViewById<SeekBar>(R.id.seekTableZoom)
            val tvTableZoomVal = modMenuView?.findViewById<TextView>(R.id.tvTableZoomVal)
            val seekPocketSize = modMenuView?.findViewById<SeekBar>(R.id.seekPocketSize)
            val tvPocketSizeVal = modMenuView?.findViewById<TextView>(R.id.tvPocketSizeVal)
            val btnCalibLeft = modMenuView?.findViewById<View>(R.id.btnCalibLeft)
            val btnCalibRight = modMenuView?.findViewById<View>(R.id.btnCalibRight)
            val btnCalibUp = modMenuView?.findViewById<View>(R.id.btnCalibUp)
            val btnCalibDown = modMenuView?.findViewById<View>(R.id.btnCalibDown)
            val btnResetCalib = modMenuView?.findViewById<View>(R.id.btnResetCalib)
            val btnAutoFitDpi = modMenuView?.findViewById<View>(R.id.btnAutoFitDpi)
            val switchMasterAim = modMenuView?.findViewById<SwitchCompat>(R.id.switchMasterAim)
            val switchShowCushions = modMenuView?.findViewById<SwitchCompat>(R.id.switchShowCushions)

            val initialZoom = canvasView?.tableZoom ?: 1.0f
            seekTableZoom?.progress = ((initialZoom - 0.35f) * 100f).toInt().coerceIn(0, 135)
            tvTableZoomVal?.text = "${(initialZoom * 100).toInt()}%"

            seekTableZoom?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    val zoom = 0.35f + (progress / 100f)
                    tvTableZoomVal?.text = "${(zoom * 100).toInt()}%"
                    canvasView?.setTableZoomValue(zoom)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })

            val initialPocketScale = canvasView?.pocketScale ?: 1.0f
            seekPocketSize?.progress = ((initialPocketScale - 0.40f) * 100f).toInt().coerceIn(0, 210)
            tvPocketSizeVal?.text = "${(initialPocketScale * 100).toInt()}%"

            seekPocketSize?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    val pScale = 0.40f + (progress / 100f)
                    tvPocketSizeVal?.text = "${(pScale * 100).toInt()}%"
                    canvasView?.setPocketScaleValue(pScale)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })

            // Table Position D-Pad adjustments
            btnCalibLeft?.setOnClickListener { canvasView?.adjustCalibration(-8f, 0f, 0f, 0f, 0f, 0f) }
            btnCalibRight?.setOnClickListener { canvasView?.adjustCalibration(8f, 0f, 0f, 0f, 0f, 0f) }
            btnCalibUp?.setOnClickListener { canvasView?.adjustCalibration(0f, -8f, 0f, 0f, 0f, 0f) }
            btnCalibDown?.setOnClickListener { canvasView?.adjustCalibration(0f, 8f, 0f, 0f, 0f, 0f) }

            btnResetCalib?.setOnClickListener {
                canvasView?.resetCalibration()
                seekTableZoom?.progress = 65
                seekPocketSize?.progress = 60
                switchShowCushions?.isChecked = true
                tvTableZoomVal?.text = "100%"
                tvPocketSizeVal?.text = "100%"
                Toast.makeText(this, "تمت إعادة ضبط الطاولة والجيوب بنجاح", Toast.LENGTH_SHORT).show()
            }

            btnAutoFitDpi?.setOnClickListener {
                val size = Point()
                windowManager.defaultDisplay.getRealSize(size)
                canvasView?.autoFitToScreen(size.x, size.y)
                seekTableZoom?.progress = 65
                seekPocketSize?.progress = 60
                tvTableZoomVal?.text = "100%"
                tvPocketSizeVal?.text = "100%"
                Toast.makeText(this, "🎯 تم ضبط الأبعاد والمقاييس تلقائياً حسب الشاشة", Toast.LENGTH_SHORT).show()
            }

            switchShowCushions?.isChecked = canvasView?.showTableCushions ?: true
            switchShowCushions?.setOnCheckedChangeListener { _, isChecked ->
                canvasView?.showTableCushions = isChecked
                canvasView?.saveCalibration()
                canvasView?.postInvalidate()
            }

            switchMasterAim?.setOnCheckedChangeListener { _, isChecked ->
                canvasView?.let {
                    it.isMasterOverlayEnabled = isChecked
                    it.visibility = if (isChecked) View.VISIBLE else View.GONE
                    it.postInvalidate()
                }
                aimTracker?.isAutoTrackingEnabled = isChecked
            }

            // ========================================================
            // TAB 2: AIM MENU CONTROLS
            // ========================================================
            val switchAutoAim = modMenuView?.findViewById<SwitchCompat>(R.id.switchAutoAim)
            val switchCueBallPath = modMenuView?.findViewById<SwitchCompat>(R.id.switchCueBallPath)
            val switchBankShots = modMenuView?.findViewById<SwitchCompat>(R.id.switchBankShots)
            val seekAimPower = modMenuView?.findViewById<SeekBar>(R.id.seekAimPower)
            val tvAimPowerVal = modMenuView?.findViewById<TextView>(R.id.tvAimPowerVal)

            switchAutoAim?.setOnCheckedChangeListener { _, isChecked ->
                canvasView?.let {
                    it.isMasterOverlayEnabled = isChecked
                    it.visibility = if (isChecked) View.VISIBLE else View.GONE
                    it.postInvalidate()
                }
                aimTracker?.isAutoTrackingEnabled = isChecked
            }

            switchCueBallPath?.setOnCheckedChangeListener { _, isChecked ->
                canvasView?.enableCueBallPath = isChecked
                canvasView?.postInvalidate()
            }

            switchBankShots?.setOnCheckedChangeListener { _, isChecked ->
                canvasView?.enableBankShots = isChecked
                canvasView?.postInvalidate()
            }

            val initialPower = canvasView?.shotPowerPercent ?: 0.85f
            seekAimPower?.progress = (initialPower * 100).toInt()
            tvAimPowerVal?.text = String.format("%.2f", initialPower)

            seekAimPower?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    val p = (progress / 100f).coerceIn(0.15f, 1.0f)
                    tvAimPowerVal?.text = String.format("%.2f", p)
                    canvasView?.shotPowerPercent = p
                    canvasView?.postInvalidate()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })

            // ========================================================
            // TAB 3: SETTINGS & EXIT
            // ========================================================
            val btnCloseMod = modMenuView?.findViewById<View>(R.id.btnCloseMod)
            btnCloseMod?.setOnClickListener { stopSelf() }

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
                    updateDisplayOrientation()
                }
            }
            displayManager?.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun updateDisplayOrientation() {
        try {
            val size = Point()
            windowManager.defaultDisplay.getRealSize(size)
            canvasView?.recalculateTableBounds(size.x, size.y)
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
                            matchDetected: Boolean
                        ) {
                            canvasView?.updateAutoAim(cueBallPos, aimAngleRad, powerPercent, targetBallPos, matchDetected)
                        }

                        override fun onMatchStateChanged(isMatch: Boolean) {
                            canvasView?.setMatchActive(isMatch)
                        }
                    })
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
            displayListener?.let { displayManager?.unregisterDisplayListener(it) }

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
