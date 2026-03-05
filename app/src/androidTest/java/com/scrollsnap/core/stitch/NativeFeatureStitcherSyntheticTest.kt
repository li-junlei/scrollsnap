package com.scrollsnap.core.stitch

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeFeatureStitcherSyntheticTest {

    @Test
    fun syntheticSelfTest_reportsBoundedError() {
        val message = NativeFeatureStitcher().runSyntheticSelfTest()
        val error = Regex("error=(\\d+) px").find(message)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
        assertTrue("Cannot parse self-test message: $message", error != null)
        assertTrue("Overlap estimation error too large: $message", error!! <= 80)
    }
}
