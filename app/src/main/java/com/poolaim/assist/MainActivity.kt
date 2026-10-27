package com.poolaim.assist

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.poolaim.assist.databinding.ActivityMainBinding
import com.poolaim.assist.overlay.AimOverlayService
import android.util.Log

/**
 * Aiming Master - Professional Main Activity Dashboard
 * 
 * Features:
 * 1. 3-Step Setup Assistant:
 *    - Overlay Permission (SYSTEM_ALERT_WINDOW)
 *    - Screen Capture AI Engine (MediaProjection)
 *    - Ignore Battery Optimizations (Anti-Freeze & Anti-Close Protection)
 * 2. Instant In-Game Guidelines configuration synchronized with AppSettings:
 *    - 3-Lines Mode (Super Line)
 *    - Cushion Bank Shots
 *    - Ghost Ball Visualization
 *    - Pocket Target Lock
 *    - Auto-Hide in Lobby
 *    - Laser Color & Thickness
 * 3. Quick Game Launcher for 8 Ball Pool.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: AppSettings

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        updateUIState()
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
            updateUIState()

            Toast.makeText(
                this,
                "🎯 تم تشغيل Aiming Master بنجاح! الأيقونة العائمة جاهزة فوق اللعبة 🎱",
                Toast.LENGTH_LONG
            ).show()
        } else {
            Toast.makeText(this, "يلزم السماح بالتقاط الشاشة لتشغيل محرك التعرف البصري الذكي", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Crash guard to log and safely catch runtime errors
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("AimingMaster", "Uncaught exception in thread ${thread.name}", throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }

        settings = AppSettings.get(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initSettingsUI()
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        updateUIState()
    }

    private fun initSettingsUI() {
        // Toggles
        binding.switchThreeLines.isChecked = settings.isThreeLinesEnabled
        binding.switchCushionShots.isChecked = settings.isCushionShotsEnabled
        binding.switchGhostBall.isChecked = settings.isGhostBallEnabled
        binding.switchPocketLock.isChecked = settings.isPocketLockEnabled
        binding.switchAutoMatch.isChecked = settings.isAutoMatchDetectionEnabled

        binding.switchThreeLines.setOnCheckedChangeListener { _, isChecked ->
            settings.isThreeLinesEnabled = isChecked
        }
        binding.switchCushionShots.setOnCheckedChangeListener { _, isChecked ->
            settings.isCushionShotsEnabled = isChecked
        }
        binding.switchGhostBall.setOnCheckedChangeListener { _, isChecked ->
            settings.isGhostBallEnabled = isChecked
        }
        binding.switchPocketLock.setOnCheckedChangeListener { _, isChecked ->
            settings.isPocketLockEnabled = isChecked
        }
        binding.switchAutoMatch.setOnCheckedChangeListener { _, isChecked ->
            settings.isAutoMatchDetectionEnabled = isChecked
        }

        // Thickness Slider
        val currentThick = settings.lineThickness
        binding.sbThickness.progress = currentThick.toInt().coerceIn(1, 8)
        binding.tvThicknessVal.text = String.format("%.1fpx", currentThick)

        binding.sbThickness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val thickness = progress.coerceAtLeast(1).toFloat()
                settings.lineThickness = thickness
                binding.tvThicknessVal.text = String.format("%.1fpx", thickness)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Colors
        fun selectColor(color: Int, name: String) {
            settings.lineColor = color
            Toast.makeText(this, "تم اختيار لون الليزر: $name", Toast.LENGTH_SHORT).show()
        }

        binding.colorCyan.setOnClickListener { selectColor(AppSettings.COLOR_CYAN, "أزرق فيروزي (Cyan)") }
        binding.colorGold.setOnClickListener { selectColor(AppSettings.COLOR_GOLD, "أصفر ذهبي (Gold)") }
        binding.colorGreen.setOnClickListener { selectColor(AppSettings.COLOR_GREEN, "أخضر نيون (Emerald)") }
        binding.colorPurple.setOnClickListener { selectColor(AppSettings.COLOR_PURPLE, "بنفسجي نيون (Violet)") }
        binding.colorWhite.setOnClickListener { selectColor(AppSettings.COLOR_WHITE, "أبيض ليزري (Laser White)") }
    }

    private fun setupListeners() {
        binding.btnGrantOverlay.setOnClickListener {
            requestOverlayPermission()
        }

        binding.btnIgnoreBattery.setOnClickListener {
            requestIgnoreBatteryOptimizations()
        }

        binding.btnStartOverlay.setOnClickListener {
            if (!checkOverlayPermission()) {
                requestOverlayPermission()
                Toast.makeText(this, "يرجى تفعيل إذن الظهور فوق التطبيقات أولاً", Toast.LENGTH_SHORT).show()
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
                Toast.makeText(this, "افتح لعبة 8 Ball Pool على هاتفك وستجد الأيقونة العائمة 🎯 جاهزة فوق الطاولة!", Toast.LENGTH_LONG).show()
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

    private fun checkBatteryOptimization(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            return powerManager?.isIgnoringBatteryOptimizations(packageName) == true
        }
        return true
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

    @SuppressLint("BatteryLife")
    private fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                try {
                    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    startActivity(intent)
                } catch (ex: Exception) {
                    Toast.makeText(this, "يرجى تعطيل توفير الطاقة للتطبيق يدوياً من إعدادات النظام", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun updateUIState() {
        val hasOverlay = checkOverlayPermission()
        val hasBattery = checkBatteryOptimization()
        val isRunning = AimOverlayService.isRunning

        // Overlay status
        if (hasOverlay) {
            binding.tvCheckOverlay.text = "✅"
            binding.btnGrantOverlay.visibility = View.GONE
        } else {
            binding.tvCheckOverlay.text = "❌"
            binding.btnGrantOverlay.visibility = View.VISIBLE
        }

        // Battery optimization status
        if (hasBattery) {
            binding.tvCheckBattery.text = "✅"
            binding.btnIgnoreBattery.visibility = View.GONE
        } else {
            binding.tvCheckBattery.text = "⚡"
            binding.btnIgnoreBattery.visibility = View.VISIBLE
        }

        if (isRunning) {
            binding.tvStatusTitle.text = "🟢 المساعد المباشر يعمل الآن • الكرة العائمة 🎯 نشطة فوق اللعبة"
            binding.tvStatusDot.backgroundTintList = ContextCompat.getColorStateList(this, R.color.accent_green)
            binding.btnStartOverlay.visibility = View.GONE
            binding.btnStopOverlay.visibility = View.VISIBLE
        } else {
            if (hasOverlay) {
                binding.tvStatusTitle.text = "الحالة: جاهز للتشغيل • اضغط على الزر للبدء"
                binding.tvStatusDot.backgroundTintList = ContextCompat.getColorStateList(this, R.color.accent_green)
            } else {
                binding.tvStatusTitle.text = "الحالة: مطلوب منح الصلاحيات"
                binding.tvStatusDot.backgroundTintList = ContextCompat.getColorStateList(this, R.color.accent_orange)
            }
            binding.btnStartOverlay.visibility = View.VISIBLE
            binding.btnStopOverlay.visibility = View.GONE
        }
    }

    private fun stopAimService() {
        val serviceIntent = Intent(this, AimOverlayService::class.java).apply {
            action = AimOverlayService.ACTION_STOP
        }
        startService(serviceIntent)
        updateUIState()
        Toast.makeText(this, "تم إيقاف المساعد بنجاح", Toast.LENGTH_SHORT).show()
    }
}
