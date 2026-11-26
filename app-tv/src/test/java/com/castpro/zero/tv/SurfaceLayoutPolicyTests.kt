package com.castpro.zero.tv

import org.junit.Assert.*
import org.junit.Test

class SurfaceLayoutPolicyTests {
    @Test fun portraitVideoIsLetterboxedWithoutStretching() {
        val fitted = SurfaceLayoutPolicy.fit(1920, 1080, 1080, 1920)
        assertEquals(607, fitted.width)
        assertEquals(1080, fitted.height)
        assertEquals(1080.0 / 1920.0, fitted.width.toDouble() / fitted.height, 0.002)
    }
    @Test fun landscapeVideoFitsWideScreen() {
        val fitted = SurfaceLayoutPolicy.fit(1920, 1080, 1920, 1080)
        assertEquals(1920, fitted.width); assertEquals(1080, fitted.height)
    }
    @Test fun narrowContainerFitsHeightFirst() {
        val fitted = SurfaceLayoutPolicy.fit(800, 1200, 1920, 1080)
        assertEquals(800, fitted.width); assertEquals(450, fitted.height)
    }
}
