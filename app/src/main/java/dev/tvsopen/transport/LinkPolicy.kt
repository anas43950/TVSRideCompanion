// Small, pure decisions about the link, kept out of RideService so they can be
// unit-tested on the JVM.

package dev.tvsopen.transport

/**
 * Whether a connect request should leave the current link alone.
 *
 * True when it asks for the vehicle already linked (or being linked). Opening
 * the app while auto-connect had already brought the vehicle in used to tear
 * that link down and rebuild it (seen 2026-11-14 12:40:54). A request for a
 * *different* vehicle, or one arriving while the link is down or only waiting
 * in the background, still goes ahead.
 */
fun shouldReuseLink(
    currentAddress: String?,
    requestedAddress: String,
    state: ConnectionState?,
): Boolean =
    currentAddress != null &&
        currentAddress.equals(requestedAddress, ignoreCase = true) &&
        (state == ConnectionState.CONNECTED || state == ConnectionState.CONNECTING)
