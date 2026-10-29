package com.openlauncher.app.util

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper

/**
 * Car-side phone connection: remembers the last connected phone and reconnects
 * it after start-up, and lets the MMI "Connect" a saved phone. Android has no
 * public API for the car side of hands-free/music, so this uses the stack's own
 * HFP-client and A2DP-sink connect calls where the unit allows them; phones
 * also reconnect to a known car by themselves.
 */
@SuppressLint("MissingPermission")
object BtConnect {
    private const val A2DP_SINK = 11
    private const val HEADSET_CLIENT = 16
    private val proxies = HashMap<Int, BluetoothProfile>()
    private var app: Context? = null
    private val adapter: BluetoothAdapter? get() = runCatching { BluetoothAdapter.getDefaultAdapter() }.getOrNull()

    fun init(context: Context) {
        if (app != null) return
        val ctx = context.applicationContext
        app = ctx
        val a = adapter ?: return
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                proxies[profile] = proxy
            }
            override fun onServiceDisconnected(profile: Int) {
                proxies.remove(profile)
            }
        }
        runCatching { a.getProfileProxy(ctx, listener, HEADSET_CLIENT) }
        runCatching { a.getProfileProxy(ctx, listener, A2DP_SINK) }
        runCatching {
            ctx.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    @Suppress("DEPRECATION")
                    val d: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    d?.address?.let { remember(it) }
                }
            }, IntentFilter(BluetoothDevice.ACTION_ACL_CONNECTED))
        }
        // Give the stack and the phone a moment to reconnect on their own first.
        Handler(Looper.getMainLooper()).postDelayed({ autoConnect() }, 12_000)
    }

    private fun remember(address: String) {
        app?.getSharedPreferences("mmi_bt", Context.MODE_PRIVATE)?.edit()?.putString("last", address)?.apply()
    }

    fun isConnected(device: BluetoothDevice): Boolean =
        runCatching { device.javaClass.getMethod("isConnected").invoke(device) as Boolean }.getOrDefault(false)

    /** Asks the unit to connect calls and music to [address]. True if the stack accepted the request. */
    fun connect(address: String): Boolean {
        val device = runCatching { adapter?.getRemoteDevice(address) }.getOrNull() ?: return false
        var accepted = false
        proxies.values.forEach { proxy ->
            runCatching {
                val ok = proxy.javaClass.getMethod("connect", BluetoothDevice::class.java).invoke(proxy, device) as Boolean
                if (ok) accepted = true
            }
        }
        if (accepted) remember(address)
        return accepted
    }

    private fun autoConnect() {
        val a = adapter ?: return
        val bonded = runCatching { a.bondedDevices.toList() }.getOrDefault(emptyList())
        if (bonded.isEmpty() || bonded.any { isConnected(it) }) return
        val last = app?.getSharedPreferences("mmi_bt", Context.MODE_PRIVATE)?.getString("last", null)
        val target = bonded.firstOrNull { it.address == last } ?: return
        connect(target.address)
    }
}
