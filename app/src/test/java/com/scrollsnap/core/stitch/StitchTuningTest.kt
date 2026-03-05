package com.scrollsnap.core.stitch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StitchTuningTest {

    @Test
    fun defaultValues_areStable() {
        val tuning = StitchTuning()
        assertEquals(1.04f, tuning.toleranceMultiplier, 0.0001f)
        assertEquals(0.55f, tuning.overlapQuantile, 0.0001f)
        assertEquals(0.0075f, tuning.safetyRatio, 0.0001f)
        assertEquals(5, tuning.minSafetyPx)
    }

    @Test
    fun customValues_areStoredInDataClass() {
        val tuning = StitchTuning(
            toleranceMultiplier = 1.1f,
            overlapQuantile = 0.4f,
            safetyRatio = 0.01f,
            minSafetyPx = 8
        )
        assertTrue(tuning.toleranceMultiplier > 1.0f)
        assertEquals(0.4f, tuning.overlapQuantile, 0.0001f)
        assertEquals(0.01f, tuning.safetyRatio, 0.0001f)
        assertEquals(8, tuning.minSafetyPx)
    }
}
