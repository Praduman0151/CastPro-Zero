package com.castpro.zero.phone

import com.castpro.zero.phone.capture.BitrateOptions
import org.junit.Assert.assertEquals
import org.junit.Test

class BitrateOptionsTests {
    @Test fun offersOnly10_20_30MbpsWithDefault20() {
        assertEquals(listOf(10, 20, 30), BitrateOptions.optionsMbps)
        assertEquals(20, BitrateOptions.DEFAULT_MBPS)
        assertEquals(10_000_000, BitrateOptions.toBps(10))
        assertEquals(20_000_000, BitrateOptions.toBps(20))
        assertEquals(30_000_000, BitrateOptions.toBps(30))
    }

    @Test fun unsupportedBitrateFallsBackToDefault() {
        assertEquals(BitrateOptions.toBps(BitrateOptions.DEFAULT_MBPS), BitrateOptions.toBps(999))
    }
}
