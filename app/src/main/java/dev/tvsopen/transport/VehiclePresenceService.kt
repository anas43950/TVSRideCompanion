// Wakes the app when the saved vehicle comes into range.
//
// Android's Companion Device Manager watches for an associated Bluetooth device
// on the app's behalf and binds this service when it appears — even if the app
// was swiped away. It is also what lets a background app start its
// `connectedDevice` foreground service, which Android 12+ otherwise refuses.
//
// The association itself is made once, with the rider's consent, from
// MainActivity (a system dialog). This service only reacts.

package dev.tvsopen.transport

import android.Manifest
import android.companion.CompanionDeviceService
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import dev.tvsopen.log.Log
import dev.tvsopen.prefs.RidePrefs
import dev.tvsopen.protocol.VehicleModel

@RequiresApi(Build.VERSION_CODES.S)
class VehiclePresenceService : CompanionDeviceService() {

    // The address overloads are the ones every API level from 31 calls: on 33+
    // the AssociationInfo overloads forward to them for Bluetooth devices.
    @Deprecated("Superseded by the AssociationInfo overload on API 33+")
    override fun onDeviceAppeared(address: String) {
        Log.enableFileSink(java.io.File(filesDir, "logs"))
        val prefs = RidePrefs.load(this)
        val saved = prefs.lastAddress
        when {
            !prefs.autoConnect -> Log.i(TAG, "vehicle appeared; auto-connect is off")
            saved == null || !address.equals(saved, ignoreCase = true) ->
                Log.i(TAG, "a companion device appeared that is not the saved vehicle; ignoring")
            !hasBlePermission() ->
                Log.w(TAG, "vehicle appeared, but Bluetooth permission is not granted")
            else -> connect(saved, prefs)
        }
    }

    @Deprecated("Superseded by the AssociationInfo overload on API 33+")
    override fun onDeviceDisappeared(address: String) {
        // Nothing to do: the link drops on its own, and the ride service then
        // keeps a background connect request waiting for the next appearance.
        Log.i(TAG, "vehicle out of range")
    }

    private fun connect(address: String, prefs: RidePrefs) {
        val model = prefs.selectedModel(address) ?: prefs.lastModelId ?: VehicleModel.NTORQ.name
        Log.i(TAG, "vehicle appeared; connecting")
        runCatching {
            // It is advertising right now, so a direct connect is fastest.
            startForegroundService(
                Intent(this, RideService::class.java)
                    .setAction(RideService.ACTION_CONNECT)
                    .putExtra(RideService.EXTRA_ADDRESS, address)
                    .putExtra(RideService.EXTRA_MODEL, model),
            )
        }.onFailure { Log.w(TAG, "could not start the ride service: ${it.message}") }
    }

    private fun hasBlePermission(): Boolean =
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT).all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    private companion object {
        const val TAG = "VehiclePresence"
    }
}
