package com.castpro.zero.tv

import org.junit.Assert.*
import org.junit.Test

class DecoderCsdTrackerTests {
    @Test fun liveDecoderWithoutCsdMustRequeueBeforeGateCanOpen() {
        val tracker = DecoderCsdTracker()
        tracker.onDecoderCreated()
        assertEquals(CsdAction.QUEUE_NOW, tracker.actionForControlCsd(true, true))
        tracker.onCsdQueued()
        assertEquals(CsdAction.NONE, tracker.actionForControlCsd(true, true))
    }

    @Test fun noDecoderConfiguresInsteadOfOpeningGateDirectly() {
        val tracker = DecoderCsdTracker()
        assertEquals(CsdAction.CONFIGURE, tracker.actionForControlCsd(false, true))
        tracker.onDecoderCreated()
        // The freshly created decoder still has no parameter sets of its own.
        assertEquals(CsdAction.QUEUE_NOW, tracker.actionForControlCsd(true, true))
    }

    @Test fun recreationAndStopForgetPreviouslyQueuedCsd() {
        val tracker = DecoderCsdTracker()
        tracker.onDecoderCreated()
        tracker.onCsdQueued()
        assertEquals(CsdAction.NONE, tracker.actionForControlCsd(true, true))
        tracker.onDecoderStopped()
        assertEquals(CsdAction.CONFIGURE, tracker.actionForControlCsd(false, true))
        tracker.onDecoderCreated()
        assertEquals(CsdAction.QUEUE_NOW, tracker.actionForControlCsd(true, true))
    }

    @Test fun invalidCsdIsRejectedEvenWhenDecoderIsWaiting() {
        val tracker = DecoderCsdTracker()
        tracker.onDecoderCreated()
        assertEquals(CsdAction.REJECT, tracker.actionForControlCsd(true, false))
    }
}
