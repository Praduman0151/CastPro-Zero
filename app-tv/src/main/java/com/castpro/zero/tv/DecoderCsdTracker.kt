package com.castpro.zero.tv

/** What a received CSD control message must do for the current decoder. */
enum class CsdAction { REJECT, CONFIGURE, QUEUE_NOW, NONE }

/**
 * Remembers whether CSD actually reached the live decoder. A control message alone
 * must never open the decode gate: a prior queue can fail while the decoder stays alive.
 */
class DecoderCsdTracker {
    @Volatile private var csdQueued = false

    fun onDecoderCreated() { csdQueued = false }
    fun onCsdQueued() { csdQueued = true }
    fun onDecoderStopped() { csdQueued = false }

    fun actionForControlCsd(hasDecoder: Boolean, validCsd: Boolean): CsdAction = when {
        !validCsd -> CsdAction.REJECT
        !hasDecoder -> CsdAction.CONFIGURE
        !csdQueued -> CsdAction.QUEUE_NOW
        else -> CsdAction.NONE
    }
}
