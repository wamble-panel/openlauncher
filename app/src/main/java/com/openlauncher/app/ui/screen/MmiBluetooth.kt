package com.openlauncher.app.ui.screen

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.openlauncher.app.model.AppInfo
import com.openlauncher.app.util.BtConnect
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.Brush
import kotlinx.coroutines.delay

internal data class BtDevice(val address: String, val name: String, val isPhone: Boolean, val connected: Boolean, val bonding: Boolean)

/** Live view of the Android Bluetooth stack: paired phones, nearby devices, discoverability. */
@SuppressLint("MissingPermission")
internal class BluetoothState(private val context: Context) {
    val adapter: BluetoothAdapter? = runCatching { BluetoothAdapter.getDefaultAdapter() }.getOrNull()
    var enabled by mutableStateOf(false)
    var name by mutableStateOf("")
    var paired by mutableStateOf<List<BtDevice>>(emptyList())
    var nearby by mutableStateOf<List<BtDevice>>(emptyList())
    var scanning by mutableStateOf(false)
    var discoverable by mutableStateOf(false)
    private val connected = HashSet<String>()
    private val found = LinkedHashMap<String, BluetoothDevice>()

    fun refresh() {
        val a = adapter ?: return
        enabled = runCatching { a.isEnabled }.getOrDefault(false)
        name = runCatching { a.name }.getOrNull().orEmpty()
        discoverable = runCatching { a.scanMode == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE }.getOrDefault(false)
        scanning = runCatching { a.isDiscovering }.getOrDefault(false)
        val bonded = runCatching { a.bondedDevices.toList() }.getOrDefault(emptyList())
        paired = bonded.map { it.toBt() }.sortedByDescending { it.connected }
        val bondedAddresses = bonded.map { it.address }.toSet()
        nearby = found.values.filter { it.address !in bondedAddresses }.map { it.toBt() }
    }

    private fun BluetoothDevice.toBt(): BtDevice {
        val isConnected = address in connected ||
            runCatching { javaClass.getMethod("isConnected").invoke(this) as Boolean }.getOrDefault(false)
        val major = runCatching { bluetoothClass?.majorDeviceClass }.getOrNull()
        return BtDevice(
            address = address,
            name = runCatching { name }.getOrNull()?.takeIf { it.isNotBlank() } ?: address,
            isPhone = major == BluetoothClass.Device.Major.PHONE,
            connected = isConnected,
            bonding = runCatching { bondState == BluetoothDevice.BOND_BONDING }.getOrDefault(false),
        )
    }

    val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> device?.let { found[it.address] = it }
                BluetoothDevice.ACTION_ACL_CONNECTED -> device?.let { connected += it.address }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> device?.let { connected -= it.address }
            }
            refresh()
        }
    }

    val filter = IntentFilter().apply {
        addAction(BluetoothDevice.ACTION_FOUND)
        addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
        addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        addAction(BluetoothDevice.ACTION_NAME_CHANGED)
        addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        addAction(BluetoothAdapter.ACTION_SCAN_MODE_CHANGED)
        addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
        addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
    }

    fun startScan() {
        found.clear()
        runCatching { adapter?.cancelDiscovery() }
        runCatching { adapter?.startDiscovery() }
        refresh()
    }

    fun stopScan() {
        runCatching { adapter?.cancelDiscovery() }
    }

    fun pair(address: String) {
        stopScan()
        runCatching { adapter?.getRemoteDevice(address)?.createBond() }
        refresh()
    }

    fun forget(address: String) {
        runCatching {
            val d = adapter?.getRemoteDevice(address) ?: return
            d.javaClass.getMethod("removeBond").invoke(d)
        }
        refresh()
    }

    fun requestOn() {
        context.launchFirst(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
    }

    fun makeDiscoverable() {
        context.launchFirst(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300))
    }
}

private fun bluetoothPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE)
} else {
    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
}

private fun hasPermissions(context: Context) =
    bluetoothPermissions().all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

@Composable
internal fun rememberBluetooth(): BluetoothState {
    val context = LocalContext.current
    val state = remember(context) { BluetoothState(context) }
    DisposableEffect(state) {
        runCatching { context.registerReceiver(state.receiver, state.filter) }
        state.refresh()
        onDispose {
            state.stopScan()
            runCatching { context.unregisterReceiver(state.receiver) }
        }
    }
    return state
}

/**
 * MMI Bluetooth: this unit's name, paired phones (tap to forget), and a
 * "Pair new phone" mode that makes the car visible and lists nearby phones.
 */
@Composable
internal fun MmiBluetoothPage(apps: List<AppInfo>, onLaunchPackage: (String) -> Unit, startPairing: Boolean = false) {
    val context = LocalContext.current
    val bt = rememberBluetooth()
    var granted by remember { mutableStateOf(hasPermissions(context)) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = hasPermissions(context)
        bt.refresh()
    }
    LaunchedEffect(Unit) { if (!granted) ask.launch(bluetoothPermissions()) }
    var pairing by remember { mutableStateOf(startPairing) }
    LaunchedEffect(startPairing) {
        if (startPairing && bt.adapter != null) {
            if (!bt.enabled) bt.requestOn()
            bt.makeDiscoverable()
            bt.startScan()
        }
    }
    var selected by remember { mutableStateOf<String?>(null) }
    val vendorApp = remember(apps) { apps.findByLabel("bluetooth", "bt phone", "bt music") }

    if (bt.adapter == null) {
        Column(Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 16.dp)) {
            Text("Bluetooth", color = MmiInk, fontFamily = MmiFont, fontWeight = FontWeight.Light, fontSize = 30.sp)
            Spacer(Modifier.height(10.dp))
            Text(
                "This unit runs Bluetooth in its own module, so phones are paired in the unit's Bluetooth app.",
                color = MmiDim, fontFamily = MmiFont, fontSize = 18.sp,
            )
            Spacer(Modifier.height(20.dp))
            ChromePill(vendorApp?.appName ?: "Open Bluetooth", {
                if (vendorApp != null) onLaunchPackage(vendorApp.packageName)
                else context.launchFirst(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            }, MmiLine.BLUETOOTH, Modifier.width(300.dp))
        }
        return
    }

    Row(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 6.dp)) {
        // Left: this car and the paired phones.
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MmiLineIcon(MmiLine.BLUETOOTH, if (bt.enabled) MmiCyan else MmiDim, Modifier.size(30.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(bt.name.ifEmpty { "This car" }, color = MmiInk, fontFamily = MmiFont, fontSize = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        when {
                            !bt.enabled -> "Bluetooth is off"
                            bt.discoverable -> "Visible to phones"
                            else -> "On"
                        },
                        color = MmiDim, fontFamily = MmiFont, fontSize = 15.sp,
                    )
                }
                if (!bt.enabled) ChromePill("Turn on", bt::requestOn)
            }
            Spacer(Modifier.height(12.dp))
            Text("PAIRED PHONES", color = MmiCyan, fontFamily = MmiFont, fontSize = 13.sp, letterSpacing = 2.sp)
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                if (bt.paired.isEmpty()) {
                    item { Text("No phones paired yet.", color = MmiDim, fontFamily = MmiFont, fontSize = 17.sp, modifier = Modifier.padding(vertical = 12.dp)) }
                }
                items(bt.paired, key = { it.address }) { d ->
                    DeviceRow(d, selected = selected == d.address, onClick = { selected = if (selected == d.address) null else d.address }) {
                        if (selected == d.address) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (!d.connected) ChromePill("Connect", { BtConnect.connect(d.address); selected = null })
                                ChromePill("Forget", { bt.forget(d.address); selected = null })
                            }
                        }
                    }
                }
            }
            Text(
                "Phones reconnect by themselves when you get in. To switch phones, connect from the phone's Bluetooth menu.",
                color = MmiDim, fontFamily = MmiFont, fontSize = 14.sp, modifier = Modifier.padding(vertical = 6.dp),
            )
        }
        Spacer(Modifier.width(32.dp))
        // Right: pairing.
        Column(Modifier.weight(1f).fillMaxHeight().mmiPanel().padding(16.dp)) {
            if (!pairing) {
                Text("Pair a new phone", color = MmiInk, fontFamily = MmiFont, fontWeight = FontWeight.Light, fontSize = 26.sp)
                Spacer(Modifier.height(10.dp))
                Text(
                    "Makes the car visible for 5 minutes and searches for nearby phones.",
                    color = MmiDim, fontFamily = MmiFont, fontSize = 16.sp,
                )
                Spacer(Modifier.weight(1f))
                ChromePill("Pair new phone", {
                    if (!granted) ask.launch(bluetoothPermissions())
                    if (!bt.enabled) bt.requestOn()
                    pairing = true
                    bt.makeDiscoverable()
                    bt.startScan()
                }, MmiLine.PLUS, Modifier.fillMaxWidth())
                if (vendorApp != null) {
                    Spacer(Modifier.height(10.dp))
                    ChromePill(vendorApp.appName, { onLaunchPackage(vendorApp.packageName) }, MmiLine.OPEN, Modifier.fillMaxWidth())
                }
            } else {
                PairingPanel(bt, onClose = { pairing = false; bt.stopScan() })
            }
        }
    }
}

@Composable
private fun ColumnScope.PairingPanel(bt: BluetoothState, onClose: () -> Unit) {
    // Discovery stops on its own after ~12 s; keep searching while this panel is open.
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000)
            if (!bt.scanning) bt.startScan()
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Pairing", color = MmiInk, fontFamily = MmiFont, fontWeight = FontWeight.Light, fontSize = 26.sp, modifier = Modifier.weight(1f))
        Box(Modifier.size(40.dp).pressable(onClose), contentAlignment = Alignment.Center) {
            MmiLineIcon(MmiLine.CLOSE, MmiInk, Modifier.size(18.dp))
        }
    }
    Text(
        "On your phone open Bluetooth and choose \"${bt.name.ifEmpty { "this car" }}\" — or pick your phone below. Confirm the same code on both screens.",
        color = MmiDim, fontFamily = MmiFont, fontSize = 15.sp,
    )
    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("NEARBY", color = MmiCyan, fontFamily = MmiFont, fontSize = 13.sp, letterSpacing = 2.sp)
        Spacer(Modifier.width(10.dp))
        if (bt.scanning) SearchingDots()
    }
    LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
        val list = bt.nearby.sortedByDescending { it.isPhone }
        if (list.isEmpty()) {
            item { Text(if (bt.scanning) "Searching…" else "Nothing found yet.", color = MmiDim, fontFamily = MmiFont, fontSize = 16.sp, modifier = Modifier.padding(vertical = 10.dp)) }
        }
        items(list, key = { it.address }) { d ->
            DeviceRow(d, selected = false, onClick = { bt.pair(d.address) }) {
                if (d.bonding) Text("Pairing…", color = MmiCyan, fontFamily = MmiFont, fontSize = 15.sp)
            }
        }
    }
}

@Composable
private fun DeviceRow(d: BtDevice, selected: Boolean, onClick: () -> Unit, trailing: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(58.dp).pressable(onClick)
            .background(if (selected) Color(0x2232C6F0) else Color.Transparent)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MmiLineIcon(if (d.isPhone) MmiLine.CALL else MmiLine.BLUETOOTH, if (d.connected) MmiCyan else MmiDim, Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(d.name, color = MmiInk, fontFamily = MmiFont, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (d.connected) Text("Connected", color = MmiCyan, fontFamily = MmiFont, fontSize = 13.sp)
        }
        trailing()
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF1E2125)))
}

@Composable
private fun SearchingDots() {
    val t = rememberInfiniteTransition(label = "search")
    val phase by t.animateFloat(0f, 3f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "dots")
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        repeat(3) { i ->
            Box(
                Modifier.size(6.dp).graphicsLayer { alpha = if (phase.toInt() == i) 1f else 0.3f }
                    .clip(CircleShape).background(MmiCyan),
            )
        }
    }
}

/** First connected paired phone, for the Phone page header. */
@Composable
internal fun rememberConnectedPhone(): String? {
    val bt = rememberBluetooth()
    return bt.paired.firstOrNull { it.connected }?.name
}

/**
 * MIB "Telephone": with no phone connected, "No device is connected to …" and a big
 * "Connect mobile device" button (plus saved phones to reconnect); with a phone,
 * its name in the header and Search, Call list, Favorites, Contacts, Voicemail and
 * Dial number tiles.
 */
@Composable
internal fun MmiBluetoothHub(
    onTelephone: () -> Unit,
    onMusic: () -> Unit,
    onContacts: () -> Unit,
    onDevices: () -> Unit,
    onConnectNew: () -> Unit,
    onCallList: () -> Unit = onContacts,
) {
    val context = LocalContext.current
    val bt = rememberBluetooth()
    val connectedPhone = bt.paired.firstOrNull { it.connected }
    var connecting by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(connecting) {
        if (connecting != null) {
            delay(12_000)
            connecting = null
        }
    }
    val addBox: @Composable RowScope.() -> Unit = {
        MibBox(onDevices) {
            Box {
                MmiLineIcon(MmiLine.PHONE, Color.White, Modifier.size(34.dp))
                MmiLineIcon(MmiLine.PLUS, Color.White, Modifier.size(16.dp).align(Alignment.TopEnd))
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(start = 10.dp)) {
        if (connectedPhone != null) {
            MibHeader("Telephone", connectedPhone.name, leading = addBox)
            Column(
                Modifier.fillMaxSize().padding(horizontal = 80.dp, vertical = 22.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    MibTile("Search", MmiLine.SEARCH, Modifier.weight(1f).fillMaxHeight(), onClick = onContacts)
                    MibTile("Call list", MmiLine.HISTORY, Modifier.weight(1f).fillMaxHeight(), onClick = onCallList)
                    MibTile("Favorites", MmiLine.STAR, Modifier.weight(1f).fillMaxHeight(), onClick = onContacts)
                }
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    MibTile("Contacts", MmiLine.CONTACTS, Modifier.weight(1f).fillMaxHeight(), onClick = onContacts)
                    MibTile("Voicemail", MmiLine.VOICEMAIL, Modifier.weight(1f).fillMaxHeight()) {
                        if (!context.launchFirst(Intent(Intent.ACTION_CALL, android.net.Uri.parse("voicemail:")))) onTelephone()
                    }
                    MibTile("Dial number", MmiLine.DIALPAD, Modifier.weight(1f).fillMaxHeight(), onClick = onTelephone)
                }
            }
            return@Column
        }

        MibHeader("Telephone", "Note", leading = addBox)
        Column(Modifier.fillMaxSize().padding(horizontal = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.weight(0.5f))
            Text(
                "No device is connected to ${bt.name.ifEmpty { "MMI" }}.",
                color = Color.White, fontFamily = MmiFont, fontSize = 28.sp, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.weight(0.4f))
            Column(
                Modifier.width(320.dp).pressable(onConnectNew).padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MmiLineIcon(MmiLine.PLUS, Color.White.copy(alpha = 0.8f), Modifier.size(40.dp))
                    Box {
                        MmiLineIcon(MmiLine.PHONE, Color.White, Modifier.size(120.dp))
                        MmiLineIcon(MmiLine.BLUETOOTH, Color.White, Modifier.size(46.dp).align(Alignment.Center))
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("Connect mobile device", color = Color.White, fontFamily = MmiFont, fontSize = 22.sp)
            }
            Spacer(Modifier.weight(0.4f))
            // Saved phones: one tap reconnects.
            if (bt.paired.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().height(64.dp), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)) {
                    bt.paired.take(4).forEach { d ->
                        Row(
                            Modifier.width(240.dp).fillMaxHeight()
                                .border(1.dp, Brush.verticalGradient(listOf(Color(0x10FFFFFF), Color(0x88FFFFFF))), androidx.compose.ui.graphics.RectangleShape)
                                .pressable {
                                    connecting = d.address
                                    if (!BtConnect.connect(d.address)) connecting = null
                                }
                                .padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            MmiLineIcon(MmiLine.PHONE, if (connecting == d.address) MibAccent else Color.White, Modifier.size(26.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(
                                if (connecting == d.address) "Connecting…" else d.name,
                                color = if (connecting == d.address) MibAccent else Color.White,
                                fontFamily = MmiFont, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.weight(0.4f))
        }
    }
}

@Composable
private fun HubRow(label: String, highlighted: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(3.dp)
    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(vertical = 3.dp).pressable(onClick)
            .clip(shape)
            .background(Color(0xFF111316))
            .border(if (highlighted) 2.dp else 1.dp, if (highlighted) MmiCyan else Color(0xFF2A2E34), shape)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = MmiInk, fontFamily = MmiFont, fontSize = 20.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        MmiLineIcon(MmiLine.ARROW, MmiDim, Modifier.size(18.dp))
    }
}

@Composable
private fun HubTile(label: String, icon: MmiLine, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(3.dp)
    Column(
        modifier.height(150.dp).pressable(onClick).clip(shape)
            .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFF111316), Color(0xFF16191D))))
            .border(1.dp, androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFF2A2E34), Color(0xFFB8BEC6))), shape)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        MmiLineIcon(icon, MmiInk, Modifier.size(44.dp))
        Spacer(Modifier.height(12.dp))
        Text(label, color = MmiInk, fontFamily = MmiFont, fontSize = 18.sp, textAlign = TextAlign.Center)
    }
}
