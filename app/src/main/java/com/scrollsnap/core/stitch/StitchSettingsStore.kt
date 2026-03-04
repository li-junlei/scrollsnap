package com.scrollsnap.core.stitch

import android.content.Context

data class StitchTuning(
    val toleranceMultiplier: Float = DEFAULT_TOLERANCE_MULTIPLIER,
    val overlapQuantile: Float = DEFAULT_OVERLAP_QUANTILE,
    val safetyRatio: Float = DEFAULT_SAFETY_RATIO,
    val minSafetyPx: Int = DEFAULT_MIN_SAFETY_PX
) {
    companion object {
        const val DEFAULT_TOLERANCE_MULTIPLIER = 1.04f
        const val DEFAULT_OVERLAP_QUANTILE = 0.55f
        const val DEFAULT_SAFETY_RATIO = 0.0075f
        const val DEFAULT_MIN_SAFETY_PX = 5
    }
}

class StitchSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("stitch_settings", Context.MODE_PRIVATE)

    fun getTuning(): StitchTuning {
        return StitchTuning(
            toleranceMultiplier = prefs.getFloat(KEY_TOLERANCE, StitchTuning.DEFAULT_TOLERANCE_MULTIPLIER),
            overlapQuantile = prefs.getFloat(KEY_QUANTILE, StitchTuning.DEFAULT_OVERLAP_QUANTILE),
            safetyRatio = prefs.getFloat(KEY_SAFETY_RATIO, StitchTuning.DEFAULT_SAFETY_RATIO),
            minSafetyPx = prefs.getInt(KEY_MIN_SAFETY_PX, StitchTuning.DEFAULT_MIN_SAFETY_PX)
        )
    }

    fun saveTuning(tuning: StitchTuning) {
        prefs.edit()
            .putFloat(KEY_TOLERANCE, tuning.toleranceMultiplier)
            .putFloat(KEY_QUANTILE, tuning.overlapQuantile)
            .putFloat(KEY_SAFETY_RATIO, tuning.safetyRatio)
            .putInt(KEY_MIN_SAFETY_PX, tuning.minSafetyPx)
            .apply()
    }

    fun resetToDefaults() {
        saveTuning(StitchTuning())
    }

    companion object {
        private const val KEY_TOLERANCE = "tolerance_multiplier"
        private const val KEY_QUANTILE = "overlap_quantile"
        private const val KEY_SAFETY_RATIO = "safety_ratio"
        private const val KEY_MIN_SAFETY_PX = "min_safety_px"
    }
}
