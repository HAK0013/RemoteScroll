package com.remotescroll.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.view.InputDevice
import android.view.KeyEvent
import androidx.core.content.ContextCompat

/**
 * Lets the user pick which paired Bluetooth devices may control TikTok, so for example
 * the play button on a pair of earbuds doesn't like a video.
 *
 * How a press is matched to a device: the key event names its input device, and a remote
 * that connects as a Bluetooth keyboard (HID) shows up under its Bluetooth name. Remotes
 * that only send media commands (AVRCP) arrive without a usable device, so those presses
 * can't be told apart and are always let through.
 */
object RemoteFilter {

    private const val PREFS = "remote_scroll"
    private const val KEY_SELECTED = "selected_remotes"

    /** Names the user ticked. Empty = every device may control. */
    fun selected(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_SELECTED, emptySet()).orEmpty().toSet()

    fun setSelected(context: Context, names: Set<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putStringSet(KEY_SELECTED, names).apply()
    }

    fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    /** Names of paired Bluetooth devices, sorted. Empty without permission or Bluetooth. */
    @SuppressLint("MissingPermission") // checked by hasPermission()
    fun pairedNames(context: Context): List<String> {
        if (!hasPermission(context)) return emptyList()
        return try {
            context.getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices
                ?.mapNotNull { it.name?.takeIf { n -> n.isNotBlank() } }
                ?.distinct()
                ?.sortedBy { it.lowercase() }
                .orEmpty()
        } catch (e: SecurityException) {
            emptyList()
        }
    }

    /** Name of the input device that sent [event], or null if it has none. */
    fun deviceName(event: KeyEvent): String? =
        InputDevice.getDevice(event.deviceId)?.name?.takeIf { it.isNotBlank() }

    /**
     * False only when the press clearly came from a paired device the user did not tick.
     * Presses whose device can't be identified are allowed (see the class comment).
     */
    fun isAllowed(context: Context, event: KeyEvent): Boolean {
        val chosen = selected(context)
        if (chosen.isEmpty()) return true
        val name = deviceName(event) ?: return true
        if (chosen.any { it.equals(name, ignoreCase = true) }) return true
        val isPairedDevice = pairedNames(context).any { it.equals(name, ignoreCase = true) }
        return !isPairedDevice
    }
}
