package com.poolaim.assist.overlay

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.*
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.NotificationCompat
import com.poolaim.assist.R
import com.poolaim.assist.physics.Vector2D
import com.poolaim.assist.vision.ScreenAimTracker
import kotlin.math.*

/**
 * Foreground Service managing:
 * 1. ScreenAimTracker real-time computer vision engine for full automatic cue & ball tracking.
 * 2. Full-screen transparent AimCanvasView rendering continuous laser trajectories,
 *    cushion reflections, and exact friction stop circles.
 * 3. Draggable Wizard Pool 8 Mod Menu (Collapsible floating icon + Expandable Mod Menu).
 * 4. Shake-to-Toggle accelerometer gesture.
 */
class AimOverlayService : Service(), SensorEventListener {

    private lateinit var windowManager: WindowManager
    private var canvasView: AimCanvasView? = null
    private var modMenuView: View? = null

    // Screen tracking
    private var mediaProjection: MediaProjection? = null
    private var aimTracker: ScreenAimTracker? = null

    // Shake sensor
    private var sensorManager: SensorManager? = null
    private var accel = 10f
    private var accelCurrent = SensorManager.GRAVITY_EARTH
    private var accelLast = SensorManager.GRAVITY_EARTH
    private var overlayVisible = true

    companion object {
        const val CHANNEL_ID = "pool_aim_assist_channel"
        const val NOTIFICATION_ID = 101
        const val ACTION_STOP = "com.poolaim.assist.ACTION_STOP"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

            createNotificationChannel()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    createNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIFICATION_ID, createNotification())
            }

            initCanvasOverlay()
            initModMenu()
            initShakeSensor()

            Toast.makeText(this, "🧙‍♂️ تم تشغيل Wizard Mod! انقر الأيقونة العائمة لفتح القائمة 🎱", Toast.LENGTH_SHORT).show()
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
                description = "Foreground Service for 8 Ball Pool Wizard Mod Aim Assist"
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
            .setContentTitle("Pool Wizard Mod v2.0")
            .setContentText("التتبع التلقائي ومحاكاة قوة المضرب تعمل الآن")
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "إيقاف", stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    @SuppressLint("InflateParams")
    private fun initCanvasOverlay() {
        try {
            canvasView = AimCanvasView(this)

            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            // Fullscreen transparent overlay - passes 100% of touches directly through to 8 Ball Pool!
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
            val inflater = LayoutInflater.from(this)
            modMenuView = inflater.inflate(R.layout.overlay_mod_menu, null)

            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val density = resources.displayMetrics.density
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = (16 * density).toInt()
                y = (48 * density).toInt()
            }

            val layoutCollapsedBadge = modMenuView?.findViewById<View>(R.id.layoutCollapsedBadge)
            val layoutExpandedMenu = modMenuView?.findViewById<View>(R.id.layoutExpandedMenu)
            val btnMinimizeMenu = modMenuView?.findViewById<View>(R.id.btnMinimizeMenu)

            val switchPowerSimulation = modMenuView?.findViewById<SwitchCompat>(R.id.switchPowerSimulation)
            val switchBankShots = modMenuView?.findViewById<SwitchCompat>(R.id.switchBankShots)
            val switchCueBallPath = modMenuView?.findViewById<SwitchCompat>(R.id.switchCueBallPath)
            val switch6Pockets = modMenuView?.findViewById<SwitchCompat>(R.id.switch6Pockets)
            val switchPocketGlow = modMenuView?.findViewById<SwitchCompat>(R.id.switchPocketGlow)

            val btnThemeGreen = modMenuView?.findViewById<View>(R.id.btnThemeGreen)
            val btnThemeCyan = modMenuView?.findViewById<View>(R.id.btnThemeCyan)
            val btnThemeGold = modMenuView?.findViewById<View>(R.id.btnThemeGold)
            val btnThemeRed = modMenuView?.findViewById<View>(R.id.btnThemeRed)

            val btnQuickToggleLines = modMenuView?.findViewById<View>(R.id.btnQuickToggleLines)
            val btnToggleVisibility = modMenuView?.findViewById<View>(R.id.btnToggleVisibility)
            val btnCloseMod = modMenuView?.findViewById<View>(R.id.btnCloseMod)

            // Draggable Floating Pill / Menu
            var initialX = 0
            var initialY = 0
            var initialTouchX = 0f
            var initialTouchY = 0f
            var isClick = false

            layoutCollapsedBadge?.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isClick = true
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - initialTouchX
                        val dy = event.rawY - initialTouchY
                        if (abs(dx) > 10 || abs(dy) > 10) {
                            isClick = false
                        }
                        params.x = initialX + dx.toInt()
                        params.y = initialY + dy.toInt()
                        modMenuView?.let { windowManager.updateViewLayout(it, params) }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (isClick) {
                            // Expand the Mod Menu!
                            layoutCollapsedBadge.visibility = View.GONE
                            layoutExpandedMenu?.visibility = View.VISIBLE
                        }
                        true
                    }
                    else -> false
                }
            }

            // Minimize Mod Menu
            btnMinimizeMenu?.setOnClickListener {
                layoutExpandedMenu?.visibility = View.GONE
                layoutCollapsedBadge?.visibility = View.VISIBLE
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

            val layoutContentAim = modMenuView?.findViewById<View>(R.id.layoutContentAim)
            val layoutContentVisuals = modMenuView?.findViewById<View>(R.id.layoutContentVisuals)
            val layoutContentSettings = modMenuView?.findViewById<View>(R.id.layoutContentSettings)
            val tvCurrentTabTitle = modMenuView?.findViewById<TextView>(R.id.tvCurrentTabTitle)

            fun switchTab(tabIndex: Int) {
                btnTabVisuals?.setBackgroundResource(0)
                btnTabAimMenu?.setBackgroundResource(0)
                btnTabSettings?.setBackgroundResource(0)

                tvTabVisualsText?.setTextColor(Color.parseColor("#94A3B8"))
                tvTabAimText?.setTextColor(Color.parseColor("#94A3B8"))
                tvTabSettingsText?.setTextColor(Color.parseColor("#94A3B8"))

                layoutContentAim?.visibility = View.GONE
                layoutContentVisuals?.visibility = View.GONE
                layoutContentSettings?.visibility = View.GONE

                when (tabIndex) {
                    0 -> { // Visuals
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
            // TAB 1: AIM MENU CONTROLS
            // ========================================================
            val switchAutoAim = modMenuView?.findViewById<SwitchCompat>(R.id.switchAutoAim)
            val switchAimToggleIcon = modMenuView?.findViewById<SwitchCompat>(R.id.switchAimToggleIcon)
            val switchAutoPlay = modMenuView?.findViewById<SwitchCompat>(R.id.switchAutoPlay)
            val switchNominatePocket = modMenuView?.findViewById<SwitchCompat>(R.id.switchNominatePocket)
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

            switchAimToggleIcon?.setOnCheckedChangeListener { _, isChecked ->
                layoutCollapsedBadge?.visibility = if (isChecked) View.VISIBLE else View.GONE
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
            // TAB 2: VISUALS (Table Zoom & Pocket Scaling)
            // ========================================================
            val seekTableZoom = modMenuView?.findViewById<SeekBar>(R.id.seekTableZoom)
            val tvTableZoomVal = modMenuView?.findViewById<TextView>(R.id.tvTableZoomVal)
            val seekPocketSize = modMenuView?.findViewById<SeekBar>(R.id.seekPocketSize)
            val tvPocketSizeVal = modMenuView?.findViewById<TextView>(R.id.tvPocketSizeVal)
            val switchShowCushions = modMenuView?.findViewById<SwitchCompat>(R.id.switchShowCushions)
            val switchMasterAim = modMenuView?.findViewById<SwitchCompat>(R.id.switchMasterAim)

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
            // TAB 3: SETTINGS & CALIBRATION
            // ========================================================
            val switchBankShots = modMenuView?.findViewById<SwitchCompat>(R.id.switchBankShots)
            val switchCueBallPath = modMenuView?.findViewById<SwitchCompat>(R.id.switchCueBallPath)
            val btnCalibLeft = modMenuView?.findViewById<View>(R.id.btnCalibLeft)
            val btnCalibRight = modMenuView?.findViewById<View>(R.id.btnCalibRight)
            val btnCalibUp = modMenuView?.findViewById<View>(R.id.btnCalibUp)
            val btnCalibDown = modMenuView?.findViewById<View>(R.id.btnCalibDown)
            val btnResetCalib = modMenuView?.findViewById<View>(R.id.btnResetCalib)
            val btnCloseMod = modMenuView?.findViewById<View>(R.id.btnCloseMod)

            switchBankShots?.setOnCheckedChangeListener { _, isChecked ->
                canvasView?.enableBankShots = isChecked
                canvasView?.postInvalidate()
            }

            switchCueBallPath?.setOnCheckedChangeListener { _, isChecked ->
                canvasView?.enableCueBallPath = isChecked
                canvasView?.postInvalidate()
            }

            btnCalibLeft?.setOnClickListener { canvasView?.adjustCalibration(-5f, 0f, 0f, 0f, 0f, 0f) }
            btnCalibRight?.setOnClickListener { canvasView?.adjustCalibration(5f, 0f, 0f, 0f, 0f, 0f) }
            btnCalibUp?.setOnClickListener { canvasView?.adjustCalibration(0f, -5f, 0f, 0f, 0f, 0f) }
            btnCalibDown?.setOnClickListener { canvasView?.adjustCalibration(0f, 5f, 0f, 0f, 0f, 0f) }

            btnResetCalib?.setOnClickListener {
                canvasView?.resetCalibration()
                seekTableZoom?.progress = 65
                seekPocketSize?.progress = 60
                switchShowCushions?.isChecked = true
                tvTableZoomVal?.text = "100%"
                tvPocketSizeVal?.text = "100%"
                Toast.makeText(this, "تمت إعادة ضبط الطاولة والجيوب بنجاح", Toast.LENGTH_SHORT).show()
            }

            btnCloseMod?.setOnClickListener { stopSelf() }

            windowManager.addView(modMenuView, params)
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

    private var isModMenuWindowVisible = true

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

    private fun toggleOverlayVisibility() {
        overlayVisible = !overlayVisible
        val vis = if (overlayVisible) View.VISIBLE else View.GONE
        canvasView?.visibility = vis
        canvasView?.isMasterOverlayEnabled = overlayVisible
        aimTracker?.isAutoTrackingEnabled = overlayVisible

        val cardMasterSwitch = modMenuView?.findViewById<View>(R.id.cardMasterSwitch)
        val switchMasterAim = modMenuView?.findViewById<SwitchCompat>(R.id.switchMasterAim)
        val tvMasterStatus = modMenuView?.findViewById<TextView>(R.id.tvMasterStatus)
        switchMasterAim?.isChecked = overlayVisible
        if (overlayVisible) {
            tvMasterStatus?.text = "🟢 الخطوط والثقوب مشتعلة الآن (ON)"
            tvMasterStatus?.setTextColor(Color.parseColor("#A7F3D0"))
            cardMasterSwitch?.setBackgroundColor(Color.parseColor("#14532D"))
        } else {
            tvMasterStatus?.text = "🔴 الخطوط والثقوب مطفأة (OFF)"
            tvMasterStatus?.setTextColor(Color.parseColor("#F87171"))
            cardMasterSwitch?.setBackgroundColor(Color.parseColor("#450A0A"))
        }
        canvasView?.postInvalidate()
    }

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

                mediaProjection?.let { mp ->
                    aimTracker?.stop()
                    aimTracker = ScreenAimTracker(this, mp, object : ScreenAimTracker.OnAimDataListener {
                        override fun onAimUpdated(
                            cueBallPos: Vector2D,
                            aimAngleRad: Float,
                            powerPercent: Float,
                            targetBallPos: Vector2D?
                        ) {
                            canvasView?.updateAutoAim(cueBallPos, aimAngleRad, powerPercent, targetBallPos)
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
        try {
            aimTracker?.stop()
            aimTracker = null

            mediaProjection?.stop()
            mediaProjection = null

            sensorManager?.unregisterListener(this)

            canvasView?.let { if (it.isAttachedToWindow) windowManager.removeView(it) }
            modMenuView?.let { if (it.isAttachedToWindow) windowManager.removeView(it) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
