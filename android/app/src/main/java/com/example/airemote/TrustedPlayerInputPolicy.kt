package com.example.airemote

/**
 * Exactly ONE independently authenticated full-state source may drive the
 * player at a time. Both routes still require a separate, verified effect
 * channel, a signed/verified turn identity and operator authorization.
 *
 * This policy never treats screen OCR or ChatGPT authentication as a source.
 */
enum class TrustedPlayerInputRoute { NONE, SIGNED_PACKET, VERIFIED_CALLBACK, CONFLICT }

object TrustedPlayerInputPolicy {
    fun route(signedPacketConnected: Boolean, verifiedCallbackConnected: Boolean): TrustedPlayerInputRoute =
        when {
            signedPacketConnected && verifiedCallbackConnected -> TrustedPlayerInputRoute.CONFLICT
            signedPacketConnected -> TrustedPlayerInputRoute.SIGNED_PACKET
            verifiedCallbackConnected -> TrustedPlayerInputRoute.VERIFIED_CALLBACK
            else -> TrustedPlayerInputRoute.NONE
        }

    fun mayArm(identityVerified: Boolean, resultProofVerified: Boolean,
               signedPacketConnected: Boolean, verifiedCallbackConnected: Boolean): Boolean =
        identityVerified && resultProofVerified &&
            route(signedPacketConnected, verifiedCallbackConnected) in setOf(
                TrustedPlayerInputRoute.SIGNED_PACKET, TrustedPlayerInputRoute.VERIFIED_CALLBACK)
}
