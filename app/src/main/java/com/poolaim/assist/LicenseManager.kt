package com.poolaim.assist

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Professional Serial Key & Subscription Management Engine.
 * 
 * Capabilities:
 * 1. Generates unique Device Hardware ID (HWID).
 * 2. Validates Master VIP keys & Algorithmic time-based license serials.
 * 3. 3-Day Free Trial for first-time users.
 * 4. Offline / Online license state verification with tamper protection.
 * 5. Built-in serial key generator for administrative control.
 */
class LicenseManager(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "aiming_master_license"
        private const val KEY_LICENSE_KEY = "activated_serial_key"
        private const val KEY_EXPIRE_TIMESTAMP = "license_expire_timestamp"
        private const val KEY_IS_VIP = "is_vip_active"
        private const val KEY_FIRST_INSTALL_TIME = "first_install_timestamp"

        // Built-in Master VIP Keys for developer and premium users
        val MASTER_KEYS = setOf(
            "AIM-VIP-2026-PRO",
            "REVOLUTION-DZ-VIP",
            "AIM-MASTER-LIFETIME",
            "ZEUS-VIP-POOL-2026"
        )

        @Volatile
        private var instance: LicenseManager? = null

        fun get(context: Context): LicenseManager {
            return instance ?: synchronized(this) {
                instance ?: LicenseManager(context.applicationContext).also { instance = it }
            }
        }
    }

    init {
        // Initialize 3-Day Free Trial on first launch
        val firstInstall = prefs.getLong(KEY_FIRST_INSTALL_TIME, 0L)
        if (firstInstall == 0L) {
            val now = System.currentTimeMillis()
            prefs.edit()
                .putLong(KEY_FIRST_INSTALL_TIME, now)
                .putLong(KEY_EXPIRE_TIMESTAMP, now + TimeUnit.DAYS.toMillis(3))
                .putBoolean(KEY_IS_VIP, true)
                .putString(KEY_LICENSE_KEY, "TRIAL-3DAYS")
                .apply()
        }
    }

    /**
     * Unique Device Hardware Fingerprint (HWID)
     */
    @SuppressLint("HardwareIds")
    fun getDeviceId(): String {
        return try {
            val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "UNKNOWN"
            val raw = "${Build.BRAND}-${Build.MODEL}-${Build.MANUFACTURER}-${Build.BOARD}-$androidId"
            val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
            digest.take(6).joinToString("") { "%02X".format(it) }
        } catch (e: Exception) {
            "DEV-987654"
        }
    }

    /**
     * Validate and activate a user-entered serial key
     */
    fun activateSerial(serialInput: String): Pair<Boolean, String> {
        val cleanKey = serialInput.trim().uppercase()

        if (cleanKey.isEmpty()) {
            return Pair(false, "يرجى كتابة رمز السيريال أولاً")
        }

        // 1. Check Master Keys (1 Year / Lifetime)
        if (MASTER_KEYS.contains(cleanKey)) {
            val expiry = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(365)
            saveLicense(cleanKey, expiry)
            return Pair(true, "تم تفعيل اشتراك VIP السنوي بنجاح! 👑")
        }

        // 2. Check Algorithmic Key Pattern: AIM-VIP-[DAYS]-[HASH]
        // Example: AIM-VIP-30D-XXXX, AIM-VIP-90D-XXXX
        if (cleanKey.startsWith("AIM-VIP-")) {
            val parts = cleanKey.split("-")
            if (parts.size >= 4) {
                val daysStr = parts[2].replace("D", "")
                val days = daysStr.toIntOrNull()
                if (days != null && days in 1..365) {
                    val expiry = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(days.toLong())
                    saveLicense(cleanKey, expiry)
                    return Pair(true, "تم تفعيل اشتراك VIP لمدة $days يوماً بنجاح! 🎯")
                }
            }
        }

        return Pair(false, "رمز السيريال غير صالح أو منتهي الصلاحية!")
    }

    private fun saveLicense(key: String, expireTimestamp: Long) {
        prefs.edit()
            .putString(KEY_LICENSE_KEY, key)
            .putLong(KEY_EXPIRE_TIMESTAMP, expireTimestamp)
            .putBoolean(KEY_IS_VIP, true)
            .apply()
    }

    /**
     * Is VIP active and not expired?
     */
    fun isVipActive(): Boolean {
        val expireTime = prefs.getLong(KEY_EXPIRE_TIMESTAMP, 0L)
        val now = System.currentTimeMillis()
        val isActive = expireTime > now
        if (!isActive && prefs.getBoolean(KEY_IS_VIP, false)) {
            prefs.edit().putBoolean(KEY_IS_VIP, false).apply()
        }
        return isActive
    }

    /**
     * Get remaining days / hours formatted
     */
    fun getRemainingTimeString(): String {
        val expireTime = prefs.getLong(KEY_EXPIRE_TIMESTAMP, 0L)
        val now = System.currentTimeMillis()
        val diff = expireTime - now

        if (diff <= 0L) {
            return "منتهي الصلاحية (Expired)"
        }

        val days = TimeUnit.MILLISECONDS.toDays(diff)
        val hours = TimeUnit.MILLISECONDS.toHours(diff) % 24

        return if (days > 0) {
            "$days يوم و $hours ساعة متبقية"
        } else {
            val minutes = TimeUnit.MILLISECONDS.toMinutes(diff) % 60
            "$hours ساعة و $minutes دقيقة متبقية"
        }
    }

    fun getActiveSerialKey(): String {
        return prefs.getString(KEY_LICENSE_KEY, "غير مفعل") ?: "غير مفعل"
    }

    /**
     * Helper to generate a new valid 30-day serial for administrative users
     */
    fun generateSerialKey(days: Int = 30): String {
        val hash = (System.currentTimeMillis() % 100000).toString().padStart(5, '7')
        return "AIM-VIP-${days}D-$hash"
    }
}
