package com.mecasoft.aimingmaster

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.mecasoft.aimingmaster.data.AimPreferences

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: AimPreferences
    private lateinit var btnToggleService: Button
    private lateinit var btnTestOverlay: Button
    private lateinit var tvStatusTitle: TextView
    private lateinit var tvStatusSub: TextView
    private lateinit var tvMainForceNum: TextView
    private lateinit var mainForceBarContainer: LinearLayout

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
            startAimService()
            requestMediaProjection()
        } else {
            Toast.makeText(this, "⚠️ إذن الظهور فوق التطبيقات مطلوب لعرض الأيقونة والخطوط", Toast.LENGTH_LONG).show()
        }
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val startIntent = Intent(this, AimService::class.java).apply {
                putExtra(AimService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(AimService.EXTRA_RESULT_DATA, result.data)
            }
            startService(startIntent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = AimPreferences.get(this)

        btnToggleService = findViewById(R.id.btnToggleService)
        btnTestOverlay = findViewById(R.id.btnTestOverlay)
        tvStatusTitle = findViewById(R.id.tvStatusTitle)
        tvStatusSub = findViewById(R.id.tvStatusSub)
        tvMainForceNum = findViewById(R.id.tvMainForceNum)
        mainForceBarContainer = findViewById(R.id.mainForceBarContainer)

        val switchMainCushion = findViewById<SwitchCompat>(R.id.switchMainCushion)
        val switchMainThreeLine = findViewById<SwitchCompat>(R.id.switchMainThreeLine)
        val cardMainCueForce = findViewById<View>(R.id.cardMainCueForce)
        val btnLaunchPool = findViewById<Button>(R.id.btnLaunchPool)

        switchMainCushion.isChecked = prefs.isCushionShotsEnabled
        switchMainCushion.setOnCheckedChangeListener { _, isChecked ->
            prefs.isCushionShotsEnabled = isChecked
        }

        switchMainThreeLine.isChecked = prefs.isThreeLinesEnabled
        switchMainThreeLine.setOnCheckedChangeListener { _, isChecked ->
            prefs.isThreeLinesEnabled = isChecked
        }

        cardMainCueForce.setOnClickListener { showCueForceSelectionDialog() }

        btnToggleService.setOnClickListener {
            if (AimService.isRunning) {
                stopAimService()
            } else {
                checkPermissionsAndStart()
            }
        }

        btnTestOverlay.setOnClickListener {
            if (!checkOverlayPermission()) {
                requestOverlayPermission()
            } else {
                startAimService()
                Toast.makeText(this, "🎯 تم تفعيل الأيقونة العائمة والخطوط على شاشتك!", Toast.LENGTH_SHORT).show()
            }
        }

        btnLaunchPool.setOnClickListener { launch8BallPool() }

        updateServiceState()
        updateCueForceDisplay()
    }

    override fun onResume() {
        super.onResume()
        updateServiceState()
        updateCueForceDisplay()
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

    private fun checkPermissionsAndStart() {
        if (!checkOverlayPermission()) {
            requestOverlayPermission()
            return
        }
        startAimService()
        requestMediaProjection()
    }

    private fun startAimService() {
        val startIntent = Intent(this, AimService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(startIntent)
        } else {
            startService(startIntent)
        }
        btnToggleService.postDelayed({ updateServiceState() }, 300L)
    }

    private fun stopAimService() {
        val stopIntent = Intent(this, AimService::class.java).apply {
            action = AimService.ACTION_STOP
        }
        startService(stopIntent)
        btnToggleService.postDelayed({ updateServiceState() }, 300L)
    }

    private fun requestMediaProjection() {
        try {
            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projectionLauncher.launch(mpManager.createScreenCaptureIntent())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun updateServiceState() {
        if (AimService.isRunning) {
            btnToggleService.text = "⏹ إيقاف AIMING MASTER"
            btnToggleService.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#EF4444"))
            tvStatusTitle.text = "🟢 المساعد المباشر نشط الآن فوق الشاشة"
            tvStatusTitle.setTextColor(Color.parseColor("#22C55E"))
            tvStatusSub.text = "أيقونة كرة 8 العائمة تظهر على الشاشة. اضغط عليها لفتح خيارات الاستهداف."
        } else {
            btnToggleService.text = "▶ تشغيل AIMING MASTER"
            btnToggleService.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#22C55E"))
            tvStatusTitle.text = "مساعد الاستهداف الذكي جاهز"
            tvStatusTitle.setTextColor(Color.WHITE)
            tvStatusSub.text = "اضغط للبدء ثم انتقل إلى 8 Ball Pool مباشرة"
        }
    }

    private fun updateCueForceDisplay() {
        val force = prefs.cueForceLevel.coerceIn(0, 11)
        tvMainForceNum.text = "Force $force"

        mainForceBarContainer.removeAllViews()
        val density = resources.displayMetrics.density
        for (i in 0 until 11) {
            val cell = View(this).apply {
                val color = when {
                    i == 10 && force == 11 -> Color.parseColor("#3574F2")
                    i < force -> Color.parseColor("#3DDC55")
                    else -> Color.parseColor("#475569")
                }
                background = GradientDrawable().apply {
                    setColor(color)
                    cornerRadius = 2f * density
                }
                layoutParams = LinearLayout.LayoutParams(0, (12 * density).toInt(), 1f).apply {
                    marginEnd = (2 * density).toInt()
                }
            }
            mainForceBarContainer.addView(cell)
        }
    }

    private fun showCueForceSelectionDialog() {
        val options = Array(12) { i -> "Force $i" }
        AlertDialog.Builder(this)
            .setTitle("اختر قوة العصا (Cue Force Level)")
            .setSingleChoiceItems(options, prefs.cueForceLevel.coerceIn(0, 11)) { dialog, which ->
                prefs.cueForceLevel = which
                updateCueForceDisplay()
                dialog.dismiss()
                Toast.makeText(this, "🎯 تم ضبط قوة العصا: Force $which", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun launch8BallPool() {
        val poolPackage = "com.miniclip.eightballpool"
        val launchIntent = packageManager.getLaunchIntentForPackage(poolPackage)
        if (launchIntent != null) {
            startActivity(launchIntent)
        } else {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$poolPackage")))
            } catch (e: Exception) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$poolPackage")))
            }
        }
    }
}
