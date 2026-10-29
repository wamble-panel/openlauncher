package com.openlauncher.app.ui.screen

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class Contact(val name: String, val number: String, val label: String)
private data class RecentCall(val name: String, val number: String, val type: Int, val at: Long)

/** Calls straight from MMI when allowed (calls go out over the connected phone), else via the dialer. */
internal fun Context.placeCall(number: String) {
    val uri = Uri.parse("tel:" + Uri.encode(number))
    val direct = ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
    if (!(direct && launchFirst(Intent(Intent.ACTION_CALL, uri)))) launchFirst(Intent(Intent.ACTION_DIAL, uri))
}

private fun Context.readContacts(): List<Contact> = runCatching {
    contentResolver.query(
        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
        arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE,
            ContactsContract.CommonDataKinds.Phone.LABEL,
        ),
        null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE NOCASE ASC",
    )?.use { c ->
        val out = ArrayList<Contact>(c.count)
        while (c.moveToNext()) {
            val number = c.getString(1) ?: continue
            val label = ContactsContract.CommonDataKinds.Phone.getTypeLabel(resources, c.getInt(2), c.getString(3)).toString()
            out += Contact(c.getString(0) ?: number, number, label)
        }
        out.distinctBy { it.name + it.number.filter(Char::isDigit).takeLast(9) }
    }.orEmpty()
}.getOrDefault(emptyList())

private fun Context.readRecents(): List<RecentCall> = runCatching {
    contentResolver.query(
        CallLog.Calls.CONTENT_URI,
        arrayOf(CallLog.Calls.CACHED_NAME, CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE),
        null, null, CallLog.Calls.DATE + " DESC",
    )?.use { c ->
        val out = ArrayList<RecentCall>()
        while (c.moveToNext() && out.size < 60) {
            val number = c.getString(1) ?: continue
            out += RecentCall(c.getString(0)?.takeIf { it.isNotBlank() } ?: number, number, c.getInt(2), c.getLong(3))
        }
        out
    }.orEmpty()
}.getOrDefault(emptyList())

/**
 * Contacts and recent calls inside MMI. Phones paired over the Android
 * Bluetooth stack sync their phonebook and call history here; tap to call.
 */
@Composable
internal fun MmiContactsPage() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val needed = arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.READ_CALL_LOG, Manifest.permission.CALL_PHONE)
    var granted by remember { mutableStateOf(needed.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
    }
    LaunchedEffect(Unit) { if (!granted) ask.launch(needed) }
    var tab by remember { mutableIntStateOf(0) }
    var contacts by remember { mutableStateOf<List<Contact>?>(null) }
    var recents by remember { mutableStateOf<List<RecentCall>>(emptyList()) }
    LaunchedEffect(granted) {
        if (!granted) return@LaunchedEffect
        contacts = withContext(Dispatchers.IO) { context.readContacts() }
        recents = withContext(Dispatchers.IO) { context.readRecents() }
    }

    Row(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 6.dp)) {
        Column(Modifier.width(200.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ChromePill("Contacts", { tab = 0 }, MmiLine.CONTACTS, Modifier.fillMaxWidth())
            ChromePill("Recent calls", { tab = 1 }, MmiLine.HISTORY, Modifier.fillMaxWidth())
            Spacer(Modifier.weight(1f))
            if (!granted) ChromePill("Allow access", { ask.launch(needed) }, MmiLine.OPEN, Modifier.fillMaxWidth())
        }
        Spacer(Modifier.width(24.dp))
        Box(Modifier.weight(1f).fillMaxHeight()) {
            when {
                !granted -> Hint("Allow contacts and calls so your phone's contacts show here.")
                tab == 0 -> {
                    val list = contacts
                    when {
                        list == null -> Hint("Loading…")
                        list.isEmpty() -> Hint("No contacts yet. Connect your phone and allow it to share contacts when it asks.")
                        else -> ContactList(list) { context.placeCall(it) }
                    }
                }
                recents.isEmpty() -> Hint("No recent calls.")
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(recents) { r ->
                        PersonRow(
                            r.name,
                            when (r.type) {
                                CallLog.Calls.MISSED_TYPE -> "Missed"
                                CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
                                else -> "Incoming"
                            } + " · " + DateUtils.getRelativeTimeSpanString(r.at),
                            missed = r.type == CallLog.Calls.MISSED_TYPE,
                        ) { context.placeCall(r.number) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContactList(list: List<Contact>, onCall: (String) -> Unit) {
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val letters = remember(list) {
        list.mapIndexedNotNull { i, c -> c.name.firstOrNull()?.uppercaseChar()?.let { it to i } }
            .distinctBy { it.first }
    }
    Row(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).fillMaxHeight(), state = state) {
            items(list) { c -> PersonRow(c.name, "${c.label} · ${c.number}", missed = false) { onCall(c.number) } }
        }
        // Letter strip: jump through a long phonebook without scrolling.
        Column(Modifier.width(36.dp).fillMaxHeight(), verticalArrangement = Arrangement.SpaceEvenly) {
            letters.take(28).forEach { (letter, index) ->
                Text(
                    letter.toString(), color = MmiDim, fontFamily = MmiFont, fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth().pressable { scope.launch { state.scrollToItem(index) } },
                )
            }
        }
    }
}

@Composable
private fun PersonRow(name: String, detail: String, missed: Boolean, onCall: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(62.dp).pressable(onCall).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(38.dp).clip(CircleShape).background(Color(0xFF1B2028)),
            contentAlignment = Alignment.Center,
        ) {
            Text(name.firstOrNull()?.uppercase() ?: "?", color = MmiInk, fontFamily = MmiFont, fontSize = 17.sp)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = if (missed) Color(0xFFFF6B6B) else MmiInk, fontFamily = MmiFont, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, color = MmiDim, fontFamily = MmiFont, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        MmiLineIcon(MmiLine.CALL, MmiCyan, Modifier.size(24.dp))
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF1E2125)))
}

@Composable
private fun Hint(text: String) {
    Text(text, color = MmiDim, fontFamily = MmiFont, fontSize = 18.sp, modifier = Modifier.padding(top = 20.dp))
}
