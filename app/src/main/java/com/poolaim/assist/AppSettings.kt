package com.poolaim.assist

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color

/**
 * Centralized Settings and Persistence Manager for Aiming Master.
 * Synchronizes user preferences seamlessly between:
 * 1. MainActivity (Dashboard)
 * 2. AimOverlayService (In-Game Floating Widget)
 * 3. AimCanvasView (Physics & Rendering Canvas)
 */
class AppSettings(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "aiming_master_prefs"

        const val KEY_GUIDELINES_ENABLED = "guidelines_enabled"
        const val KEY_THREE_LINES = "three_lines_enabled"
        const val KEY_CUSHION_SHOTS = "cushion_shots_enabled"
        const val KEY_CUSHION_BOUNCES = "cushion_bounces_count"
        const val KEY_GHOST_BALL = "ghost_ball_enabled"
        const val KEY_POCKET_LOCK = "pocket_lock_enabled"
        const val KEY_LINE_COLOR = "line_color"
        const val KEY_LINE_THICKNESS = "line_thickness"
        const val KEY_LINE_OPACITY = "line_opacity"
        const val KEY_POCKET_SCALE = "pocket_scale"
        const val KEY_TABLE_OFFSET_X = "table_offset_x"
        const val KEY_TABLE_OFFSET_Y = "table_offset_y"
        const val KEY_AUTO_MATCH_DETECTION = "auto_match_detection"

        // Color Presets
        val COLOR_CYAN = Color.parseColor("#00F5FF")
        val COLOR_GOLD = Color.parseColor("#FFD700")
        val COLOR_GREEN = Color.parseColor("#00E676")
        val COLOR_PURPLE = Color.parseColor("#B026FF")
        val COLOR_WHITE = Color.parseColor("#FFFFFF")

        @Volatile
        private var instance: AppSettings? = null

        fun get(context: Context): AppSettings {
            return instance ?: synchronized(this) {
                instance ?: AppSettings(context.applicationContext).also { instance = it }
            }
        }
    }

    var isGuidelinesEnabled: Boolean
        get() = prefs.getBoolean(KEY_GUIDELINES_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_GUIDELINES_ENABLED, value).apply()

    var isThreeLinesEnabled: Boolean
        get() = prefs.getBoolean(KEY_THREE_LINES, true)
        set(value) = prefs.edit().putBoolean(KEY_THREE_LINES, value).apply()

    var isCushionShotsEnabled: Boolean
        get() = prefs.getBoolean(KEY_CUSHION_SHOTS, true)
        set(value) = prefs.edit().putBoolean(KEY_CUSHION_SHOTS, value).apply()

    var cushionBouncesCount: Int
        get() = prefs.getInt(KEY_CUSHION_BOUNCES, 2)
        set(value) = prefs.edit().putInt(KEY_CUSHION_BOUNCES, value).apply()

    var isGhostBallEnabled: Boolean
        get() = prefs.getBoolean(KEY_GHOST_BALL, true)
        set(value) = prefs.edit().putBoolean(KEY_GHOST_BALL, value).apply()

    var isPocketLockEnabled: Boolean
        get() = prefs.getBoolean(KEY_POCKET_LOCK, true)
        set(value) = prefs.edit().putBoolean(KEY_POCKET_LOCK, value).apply()

    var lineColor: Int
        get() = prefs.getInt(KEY_LINE_COLOR, COLOR_CYAN)
        set(value) = prefs.edit().putInt(KEY_LINE_COLOR, value).apply()

    var lineThickness: Float
        get() = prefs.getFloat(KEY_LINE_THICKNESS, 3.5f)
        set(value) = prefs.edit().putFloat(KEY_LINE_THICKNESS, value).apply()

    var lineOpacity: Int
        get() = prefs.getInt(KEY_LINE_OPACITY, 230)
        set(value) = prefs.edit().putInt(KEY_LINE_OPACITY, value).apply()

    var pocketScale: Float
        get() = prefs.getFloat(KEY_POCKET_SCALE, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_POCKET_SCALE, value).apply()

    var tableOffsetX: Float
        get() = prefs.getFloat(KEY_TABLE_OFFSET_X, 0f)
        set(value) = prefs.edit().putFloat(KEY_TABLE_OFFSET_X, value).apply()

    var tableOffsetY: Float
        get() = prefs.getFloat(KEY_TABLE_OFFSET_Y, 0f)
        set(value) = prefs.edit().putFloat(KEY_TABLE_OFFSET_Y, value).apply()

    var isAutoMatchDetectionEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_MATCH_DETECTION, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_MATCH_DETECTION, value).apply()
}
