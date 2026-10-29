package com.mecasoft.aimingmaster

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.Point
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.mecasoft.aimingmaster.data.AimPreferences
import com.mecasoft.aimingmaster.ui.AimFloatingManager
import com.mecasoft.aimingmaster.ui.AssistLineView
import com.mecasoft.aimingmaster.vision.AimVisionDetector

class AimService : Service() {

    private lateinit var windowManager: WindowManager
    private var canvasView: AssistLineView? = null
    private var floatingManager: AimFloatingManager? = null
    private var visionDetector: AimVisionDetector? = null
    private var mediaProjection: MediaProjection? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()

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

        val canOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }

        if (canOverlay) {
            initCanvasOverlay()
            canvasView?.let { cv ->
                floatingManager = AimFloatingManager(this, windowManager, cv)
                floatingManager?.init()
            }
        }

        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (floatingManager == null) {
            val canOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Settings.canDrawOverlays(this)
            } else {
                true
            }
            if (canOverlay) {
                initCanvasOverlay()
                canvasView?.let { cv ->
                    floatingManager = AimFloatingManager(this, windowManager, cv)
                    floatingManager?.init()
                }
            }
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

        if (resultCode == Activity.RESULT_OK && resultData != null && mediaProjection == null) {
            try {
                val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = mpManager.getMediaProjection(resultCode, resultData)

                val displaySize = Point()
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.getRealSize(displaySize)
                val metrics = resources.displayMetrics

                val prefs = AimPreferences.get(this)
                visionDetector = AimVisionDetector(prefs) { model ->
                    canvasView?.currentModel = model
                    canvasView?.postInvalidate()
                }
                mediaProjection?.let { mp ->
                    visionDetector?.start(mp, displaySize.x, displaySize.y, metrics.densityDpi)
                }
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }

        return START_STICKY
    }

    private fun initCanvasOverlay() {
        if (canvasView != null) return
        try {
            canvasView = AssistLineView(this)
            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val prefs = AimPreferences.get(this)
            var flagMask = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

            if (!prefs.isInteractiveHandlesEnabled) {
                flagMask = flagMask or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                layoutType,
                flagMask,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            windowManager.addView(canvasView, params)
            canvasView?.recomputeTrajectory()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Aiming Master Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Aiming Master Live Guideline Assistant"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val stopIntent = Intent(this, AimService::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Aiming Master Pro")
            .setContentText("المساعد المباشر وخطوط التوقع تعمل الآن 🎯")
            .setSmallIcon(R.mipmap.ic_launcher)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "إيقاف", stopPending)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        try {
            visionDetector?.stop()
            mediaProjection?.stop()
            mediaProjection = null
            floatingManager?.destroy()
            canvasView?.let { if (it.isAttachedToWindow) windowManager.removeView(it) }
            canvasView = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    companion object {
        const val CHANNEL_ID = "aiming_master_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.mecasoft.aimingmaster.ACTION_STOP"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        @Volatile
        var isRunning = false
    }
}
