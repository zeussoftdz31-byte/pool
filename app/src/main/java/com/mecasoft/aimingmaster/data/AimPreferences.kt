package com.mecasoft.aimingmaster.data

import android.content.Context
import android.content.SharedPreferences

class AimPreferences private constructor(context: Context) {

    private val sp: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    var isGuidelinesEnabled: Boolean
        get() = sp.getBoolean(KEY_GUIDELINES, true)
        set(value) = sp.edit().putBoolean(KEY_GUIDELINES, value).apply()

    var isCushionShotsEnabled: Boolean
        get() = sp.getBoolean(KEY_CUSHION, true)
        set(value) = sp.edit().putBoolean(KEY_CUSHION, value).apply()

    var isThreeLinesEnabled: Boolean
        get() = sp.getBoolean(KEY_THREE_LINES, true)
        set(value) = sp.edit().putBoolean(KEY_THREE_LINES, value).apply()

    var isInteractiveHandlesEnabled: Boolean
        get() = sp.getBoolean(KEY_HANDLES, true)
        set(value) = sp.edit().putBoolean(KEY_HANDLES, value).apply()

    var cueForceLevel: Int
        get() = sp.getInt(KEY_FORCE, 5)
        set(value) = sp.edit().putInt(KEY_FORCE, value.coerceIn(0, 11)).apply()

    var tableZoom: Float
        get() = sp.getFloat(KEY_ZOOM, 1.0f)
        set(value) = sp.edit().putFloat(KEY_ZOOM, value).apply()

    var tableOffsetX: Float
        get() = sp.getFloat(KEY_OFFSET_X, 0f)
        set(value) = sp.edit().putFloat(KEY_OFFSET_X, value).apply()

    var tableOffsetY: Float
        get() = sp.getFloat(KEY_OFFSET_Y, 0f)
        set(value) = sp.edit().putFloat(KEY_OFFSET_Y, value).apply()

    companion object {
        private const val PREF_NAME = "aiming_master_pro_prefs"
        private const val KEY_GUIDELINES = "guidelines_enabled"
        private const val KEY_CUSHION = "cushion_enabled"
        private const val KEY_THREE_LINES = "three_lines_enabled"
        private const val KEY_HANDLES = "interactive_handles_enabled"
        private const val KEY_FORCE = "cue_force_level"
        private const val KEY_ZOOM = "table_zoom"
        private const val KEY_OFFSET_X = "table_offset_x"
        private const val KEY_OFFSET_Y = "table_offset_y"

        @Volatile
        private var instance: AimPreferences? = null

        fun get(context: Context): AimPreferences {
            return instance ?: synchronized(this) {
                instance ?: AimPreferences(context.applicationContext).also { instance = it }
            }
        }
    }
}
