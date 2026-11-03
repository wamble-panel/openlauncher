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
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.draw.drawBehind
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
            Text("Bluetooth", color = MmiInk, fontFamily = MmiFont, fontSize = 30.sp)
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

    // MIB list: while pairing, the phones found nearby; otherwise the saved phones.
    LaunchedEffect(pairing) {
        if (!pairing) return@LaunchedEffect
        // Discovery stops on its own after ~12 s; keep searching while this page is open.
        while (true) {
            delay(15_000)
            if (!bt.scanning) bt.startScan()
        }
    }
    Column(Modifier.fillMaxSize().padding(top = 12.dp)) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            if (pairing) {
                val list = bt.nearby.sortedByDescending { it.isPhone }
                itemsIndexed(list, key = { _, d -> d.address }) { i, d ->
                    PhoneRow(d.name, if (d.bonding) "Pairing…" else "Tap to connect", highlighted = i == 0) { bt.pair(d.address) }
                }
                item { PhoneRow("Searching…", null, highlighted = false, dim = true, spinner = true) {} }
            } else {
                item {
                    PhoneRow("Pair new phone", null, highlighted = false, plus = true) {
                        if (!granted) ask.launch(bluetoothPermissions())
                        if (!bt.enabled) bt.requestOn()
                        pairing = true
                        bt.makeDiscoverable()
                        bt.startScan()
                    }
                }
                itemsIndexed(bt.paired, key = { _, d -> d.address }) { _, d ->
                    PhoneRow(
                        d.name,
                        when {
                            d.connected -> "Connected"
                            selected == d.address -> "Tap again to forget"
                            else -> "Tap to connect"
                        },
                        highlighted = d.connected,
                    ) {
                        when {
                            d.connected -> Unit
                            selected == d.address -> { bt.forget(d.address); selected = null }
                            else -> { BtConnect.connect(d.address); selected = d.address }
                        }
                    }
                }
                if (vendorApp != null) item { PhoneRow(vendorApp.appName, "Open", highlighted = false) { onLaunchPackage(vendorApp.packageName) } }
            }
        }
        Text(
            if (!bt.enabled) "Bluetooth is off" else "Visible to phones as: ${bt.name.ifEmpty { "MMI" }}",
            color = MmiDim, fontFamily = MmiFont, fontSize = 22.sp, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 22.dp),
        )
    }
}

/** MIB phone row: phone outline icon, name, grey status on the right; highlighted = lit left bar. */
@Composable
private fun PhoneRow(
    name: String, status: String?, highlighted: Boolean, dim: Boolean = false, spinner: Boolean = false, plus: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(86.dp)
            .then(
                if (highlighted) Modifier.background(Color(0x14FFFFFF)).border(1.dp, Color(0x66FFFFFF), androidx.compose.ui.graphics.RectangleShape)
                else Modifier
            )
            .drawBehind {
                if (highlighted) drawRect(Color.White, size = androidx.compose.ui.geometry.Size(8.dp.toPx(), size.height))
                else drawLine(Color(0x38FFFFFF), androidx.compose.ui.geometry.Offset(8.dp.toPx(), size.height - 1), androidx.compose.ui.geometry.Offset(size.width, size.height - 1), 1.dp.toPx())
            }
            .pressable(onClick).padding(start = 44.dp, end = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            MmiLineIcon(MmiLine.PHONE, Color.White, Modifier.size(40.dp))
            if (plus) MmiLineIcon(MmiLine.PLUS, Color.White, Modifier.size(20.dp).align(Alignment.TopEnd))
        }
        Spacer(Modifier.width(48.dp))
        Text(name, color = if (dim) MmiDim else Color.White, fontFamily = MmiFont, fontSize = 30.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (spinner) SearchingDots()
        if (status != null) Text(status, color = MmiDim, fontFamily = MmiFont, fontSize = 23.sp)
    }
}

/** Searching: a thin ring with a bright arc turning smoothly, as in the design. */
@Composable
private fun SearchingDots() {
    val t = rememberInfiniteTransition(label = "search")
    val turn by t.animateFloat(0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart), label = "spin")
    androidx.compose.foundation.Canvas(Modifier.size(40.dp).padding(4.dp)) {
        val w = 3.dp.toPx()
        drawArc(Color(0x40FFFFFF), 0f, 360f, false, style = androidx.compose.ui.graphics.drawscope.Stroke(w))
        drawArc(Color.White, turn, 90f, false, style = androidx.compose.ui.graphics.drawscope.Stroke(w, cap = androidx.compose.ui.graphics.StrokeCap.Round))
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
                // Your "Connect mobile device" art: plus, glass phone, Bluetooth mark.
                androidx.compose.foundation.Image(
                    androidx.compose.ui.res.painterResource(com.openlauncher.app.R.drawable.connect_device), null,
                    modifier = Modifier.size(width = 117.dp, height = 140.dp),
                )
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

