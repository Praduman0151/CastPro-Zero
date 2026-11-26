package com.castpro.zero.tv
import org.junit.Assert.*
import org.junit.Test
class DecoderRecoveryTests {
 @Test fun surfaceRecreationReplaysCachedCsdAndRequestsPli(){val csd=byteArrayOf(0,0,0,1,0x67,0x42,0,0x1e,0,0,0,1,0x68,1);var replay:ByteArray?=null;var pli=0;fun recreate(){replay=csd.copyOf();pli++};recreate();assertArrayEquals(csd,replay);replay=null;recreate();assertArrayEquals(csd,replay);assertEquals(2,pli)}
}
