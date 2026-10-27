package com.poolaim.assist

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.poolaim.assist.databinding.ActivityMainBinding
import com.poolaim.assist.overlay.AimOverlayService
import org.opencv.android.OpenCVLoader
import android.util.Log

/**
 * Main Launcher Activity.
 * - Handles Android Overlay Permissions (SYSTEM_ALERT_WINDOW).
 * - Requests MediaProjection screen capture permission for real-time automatic cue & ball tracking.
 * - Starts / Stops the Live Automatic Aim Assist Service.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var isServiceRunning = false

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        updatePermissionStatus()
    }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, AimOverlayService::class.java).apply {
                putExtra(AimOverlayService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(AimOverlayService.EXTRA_RESULT_DATA, result.data)
            }
            ContextCompat.startForegroundService(this, serviceIntent)
            isServiceRunning = true

            binding.btnStartOverlay.visibility = View.GONE
            binding.btnStopOverlay.visibility = View.VISIBLE
            binding.tvOverlayStatus.text = "🟢 المساعد المباشر ومحرك التتبع يعملان بنجاح • المود منيو 🎯 ظاهر الآن"
            binding.tvOverlayStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_green))

            Toast.makeText(
                this,
                "🎯 تم تشغيل Aim Master! نافذة المود منيو ظاهرة الآن فوق الشاشة. يمكنك فتح اللعبة في أي وقت 🎱",
                Toast.LENGTH_LONG
            ).show()
        } else {
            Toast.makeText(this, "يلزم السماح ببث الشاشة لتتبع حركة العصا والكرات تلقائياً", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Global crash guard to prevent silent closes and record exact errors
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("AimMasterCrash", "Uncaught exception in thread ${thread.name}", throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize OpenCV 4.9.0 local native engine safely
        try {
            if (OpenCVLoader.initLocal()) {
                Log.i("AimMasterOpenCV", "OpenCV 4.9.0 initialized successfully via initLocal()")
            } else {
                Log.w("AimMasterOpenCV", "OpenCV initLocal returned false")
            }
        } catch (e: Throwable) {
            Log.e("AimMasterOpenCV", "Error loading OpenCV", e)
        }

        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
    }

    private fun setupListeners() {
        binding.btnGrantPermission.setOnClickListener {
            requestOverlayPermission()
        }

        binding.btnStartOverlay.setOnClickListener {
            if (!checkOverlayPermission()) {
                requestOverlayPermission()
                Toast.makeText(this, "يرجى منح صلاحية الظهور فوق التطبيقات أولاً", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            requestScreenCapture()
        }

        binding.btnStopOverlay.setOnClickListener {
            stopAimService()
        }

        binding.btnLaunchGame.setOnClickListener {
            val pm = packageManager
            val launchIntent = pm.getLaunchIntentForPackage("com.miniclip.eightballpool")
            if (launchIntent != null) {
                startActivity(launchIntent)
            } else {
                Toast.makeText(this, "افتح لعبة 8 Ball Pool على هاتفك وستجد نافذة المود منيو 🎯 جاهزة ومباشرة فوق اللعبة!", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun checkOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
        }
    }

    private fun requestScreenCapture() {
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    private fun updatePermissionStatus() {
        if (AimOverlayService.isRunning) {
            binding.tvOverlayStatus.text = "🟢 المساعد المباشر ومحرك التتبع يعملان بنجاح • المود منيو 🎯 ظاهر الآن"
            binding.tvOverlayStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_green))
            binding.btnGrantPermission.visibility = View.GONE
            binding.btnStartOverlay.visibility = View.GONE
            binding.btnStopOverlay.visibility = View.VISIBLE
            binding.btnLaunchGame.visibility = View.VISIBLE
            return
        }

        binding.btnLaunchGame.visibility = View.GONE

        val hasPermission = checkOverlayPermission()
        if (hasPermission) {
            binding.tvOverlayStatus.text = "الحالة: جاهز للتشغيل • التتبع التلقائي لعصا البلياردو والكرات"
            binding.tvOverlayStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_green))
            binding.btnGrantPermission.visibility = View.GONE
            binding.btnStartOverlay.visibility = View.VISIBLE
            binding.btnStartOverlay.isEnabled = true
            binding.btnStopOverlay.visibility = View.GONE
        } else {
            binding.tvOverlayStatus.text = "الحالة: مطلوب إذن الظهور فوق التطبيقات"
            binding.tvOverlayStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_orange))
            binding.btnGrantPermission.visibility = View.VISIBLE
            binding.btnStartOverlay.visibility = View.VISIBLE
            binding.btnStartOverlay.isEnabled = false
            binding.btnStopOverlay.visibility = View.GONE
        }
    }

    private fun stopAimService() {
        val serviceIntent = Intent(this, AimOverlayService::class.java).apply {
            action = AimOverlayService.ACTION_STOP
        }
        startService(serviceIntent)
        isServiceRunning = false

        updatePermissionStatus()
        Toast.makeText(this, "تم إيقاف المساعد بنجاح", Toast.LENGTH_SHORT).show()
    }
}
