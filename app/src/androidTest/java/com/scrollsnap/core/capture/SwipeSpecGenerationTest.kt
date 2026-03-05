package com.scrollsnap.core.capture

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SwipeSpecGenerationTest {

    @Test
    fun defaultAndShortSwipeStayWithinScreenAndShorterDistance() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dm = context.resources.displayMetrics

        val normal = defaultSwipeSpec(context)
        val shorter = shortSwipeSpec(context)

        assertEquals(dm.widthPixels / 2, normal.startX)
        assertEquals(dm.widthPixels / 2, shorter.startX)
        assertTrue(normal.startY in 0 until dm.heightPixels)
        assertTrue(normal.endY in 0 until dm.heightPixels)
        assertTrue(shorter.startY in 0 until dm.heightPixels)
        assertTrue(shorter.endY in 0 until dm.heightPixels)

        val normalDistance = normal.startY - normal.endY
        val shortDistance = shorter.startY - shorter.endY
        assertTrue(normalDistance > 0)
        assertTrue(shortDistance > 0)
        assertTrue(shortDistance < normalDistance)
    }
}
