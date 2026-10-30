package com.openlauncher.app.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.tw.ac.IAirConditionController
import com.tw.ac.IAirConditionReceive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Climate from the TS10 climate app's service (com.tw.ac/.AirService, exported
 * without a permission): the CAN box's temperatures, fan, airflow and switches.
 * Changes raise MMI's own climate pop-up; the vendor's pop-up is closed as soon
 * as it announces itself (onShow), unless that's switched off in Settings.
 */
object Climate {
    data class State(
        val on: Boolean = true, val left: Float? = null, val right: Float? = null,
        val leftLo: Boolean = false, val leftHi: Boolean = false, val rightLo: Boolean = false, val rightHi: Boolean = false,
        val fan: Int = 0, val airflow: Int = 0, val ac: Boolean = false, val auto: Boolean = false, val dual: Boolean = false,
        val recirculation: Boolean = false, val frontDefrost: Boolean = false, val rearDefrost: Boolean = false,
        val outdoor: Float? = null,
        /** When a user-visible setting last changed (drives MMI's pop-up). */
        val changedAt: Long = 0L,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    private val main = Handler(Looper.getMainLooper())
    private var controller: IAirConditionController? = null
    private lateinit var app: Context
    /** The first values arrive right after registering: they aren't a change. */
    private var settledAt = Long.MAX_VALUE

    private fun prefs() = app.getSharedPreferences("mmi_car", Context.MODE_PRIVATE)
    fun replaceVendorPopup(): Boolean = prefs().getBoolean("climate_popup", true)
    fun setReplaceVendorPopup(on: Boolean) {
        prefs().edit().putBoolean("climate_popup", on).apply()
    }

    private fun update(change: (State) -> State) {
        val now = System.currentTimeMillis()
        val next = change(_state.value)
        _state.value = if (now > settledAt && next != _state.value) next.copy(changedAt = now) else next
    }

    private fun vendorPopupOpened() {
        if (!replaceVendorPopup()) return
        // Close the vendor's own climate window; MMI shows its pop-up instead.
        main.postDelayed({ runCatching { controller?.airShow(false) } }, 150)
    }

    private val receiver = object : IAirConditionReceive.Stub() {
        override fun onLeftTemp(temp: Float) { update { it.copy(left = temp, leftLo = false, leftHi = false) } }
        override fun onRightTemp(temp: Float) { update { it.copy(right = temp, rightLo = false, rightHi = false) } }
        override fun onWindType(type: Int) { update { it.copy(airflow = type) } }
        override fun onWindLevel(level: Int) { update { it.copy(fan = level) } }
        override fun onLoop(type: Int) { update { it.copy(recirculation = type == 1) } }
        override fun onSwitch(type: Int) { update { it.copy(on = type == 1) } }
        override fun onAc(type: Int) { update { it.copy(ac = type == 1) } }
        override fun onAuto(type: Int) { update { it.copy(auto = type == 1) } }
        override fun onDual(type: Int) { update { it.copy(dual = type == 1) } }
        override fun onBeforeDefrost(type: Int) { update { it.copy(frontDefrost = type == 1) } }
        override fun onPostDefrost(type: Int) { update { it.copy(rearDefrost = type == 1) } }
        override fun onMaxAc(type: Int) {}
        override fun onLeftHeatedSeat(type: Int) {}
        override fun onRightHeatedSeat(type: Int) {}
        override fun onLeftSeatVentilation(type: Int) {}
        override fun onRightSeatVentilation(type: Int) {}
        override fun onAutomaticSteering(type: Int) {}
        override fun onRearviewMirrorHeating(type: Int) {}
        override fun onECO(type: Int) {}
        override fun onFrontWindowHeating(type: Int) {}
        override fun onMAXFRONTLamp(type: Int) {}
        override fun onREARLamp(type: Int) {}
        override fun onAQS(type: Int) {}
        override fun onVolutionInstructions(type: Int) {}
        override fun onRearKey(type: Int) {}
        override fun onAutomaticFrontBlowing(type: Int) {}
        override fun onRearTemp(leftTemp: Float, rightTemp: Float) {}
        override fun onOperatingMode(type: Int) {}
        override fun onCompressorCommissioningInstructionss(type: Int) {}
        override fun onDebugFrequency(type: Int, unit: String?) {}
        override fun onInternalFanCommissioningInstructions(type: Int) {}
        override fun onDebuggingSpeedOfInternalFan(type: Int, unit: String?) {}
        override fun onHighVoltageRequest(type: Int): Int = 0
        override fun onHighVoltageRequestOnPTC(type: Int): Int = 0
        override fun onCoolingFanOnRequest(type: Int): Int = 0
        override fun onCommunicationFail(type: Int) {}
        override fun onOutdoorTemp(temp: Float) { _state.value = _state.value.copy(outdoor = temp) }
        override fun onExtendedInterface(bundle: android.os.Bundle?) {}
        override fun onLeftTempLO(isLO: Boolean) { if (isLO) update { it.copy(leftLo = true) } }
        override fun onLeftTempHI(isHI: Boolean) { if (isHI) update { it.copy(leftHi = true) } }
        override fun onRightTempLO(isLO: Boolean) { if (isLO) update { it.copy(rightLo = true) } }
        override fun onRightTempHI(isHI: Boolean) { if (isHI) update { it.copy(rightHi = true) } }
        override fun onRaerLeftTempLO(isLO: Boolean) {}
        override fun onRaerLeftTempHI(isHI: Boolean) {}
        override fun onRaerRightTempLO(isLO: Boolean) {}
        override fun onRaerRightTempHI(isHI: Boolean) {}
        override fun onUnit(mUnit: String?) {}
        override fun onRightWindType(type: Int) {}
        override fun onSYNC(type: Int) {}
        override fun onRightWindLevel(mLevel: Int) {}
        override fun onPollenClear(`is`: Boolean): Int = 0
        override fun onShow() { vendorPopupOpened() }
        override fun onHide() {}
        override fun onLeftTempNULL(isNULL: Boolean) {}
        override fun onRightTempNULL(isNULL: Boolean) {}
        override fun onRearLeftTempNULL(isNULL: Boolean) {}
        override fun onRearRightTempNULL(isNULL: Boolean) {}
        override fun onAirControl(isControl: Boolean) {}
        override fun onRearWindType(type: Int) {}
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val c = IAirConditionController.Stub.asInterface(binder)
            controller = c
            settledAt = System.currentTimeMillis() + 3000
            runCatching { c.registerAirConditionCallBack(receiver) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            controller = null
            // The climate app restarted: connect again shortly.
            main.postDelayed({ bind() }, 3000)
        }
    }

    fun init(context: Context) {
        app = context.applicationContext
        bind()
    }

    private fun bind() {
        runCatching {
            app.bindService(Intent("com.tw.ac.AirService.Bind").setPackage("com.tw.ac"), connection, Context.BIND_AUTO_CREATE)
        }
    }
}
