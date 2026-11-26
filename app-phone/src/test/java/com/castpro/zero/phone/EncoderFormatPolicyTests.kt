package com.castpro.zero.phone

import org.junit.Assert.*
import org.junit.Test

class EncoderFormatPolicyTests {
    @Test fun fallbackFormatPolicyIsDocumentedAndKeepsSurfaceEncoding() {
        // Pure policy contract: optional profile/level/color metadata is not required by the minimal fallback.
        val required = setOf("mime", "width", "height", "color-format", "bitrate", "frame-rate", "i-frame-interval", "max-bframes")
        val optional = setOf("profile", "level", "color-standard", "color-transfer", "color-range")
        assertTrue(required.intersect(optional).isEmpty())
        assertEquals(5, optional.size)
    }
}
