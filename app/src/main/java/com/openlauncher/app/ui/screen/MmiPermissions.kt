package com.openlauncher.app.ui.screen

import android.Manifest
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.openlauncher.app.service.MediaListenerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Runtime permissions MMI uses, granted together in one request (or all at once with root). */
internal fun runtimePermissions(): List<String> = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        add(Manifest.permission.BLUETOOTH_CONNECT)
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_ADVERTISE)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    add(Manifest.permission.READ_CONTACTS)
    add(Manifest.permission.READ_CALL_LOG)
    add(Manifest.permission.CALL_PHONE)
    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) add(Manifest.permission.READ_EXTERNAL_STORAGE)
}

private class PermissionItem(val label: String, val detail: String, val granted: Boolean, val open: () -> Unit)

private fun Context.isGranted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

internal fun Context.isDefaultHome(): Boolean {
    val home = packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
    return home?.activityInfo?.packageName == packageName
}

internal fun Context.allPermissionsGranted(): Boolean =
    runtimePermissions().all { isGranted(it) } &&
        packageName in NotificationManagerCompat.getEnabledListenerPackages(this) &&
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()) &&
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.System.canWrite(this)) &&
        isDefaultHome()


/** Shell commands that grant everything; used with root here and by tools/setup for ADB. */
internal fun grantCommands(context: Context): List<String> {
    val pkg = context.packageName
    val listener = ComponentName(context, MediaListenerService::class.java).flattenToString()
    return buildList {
        runtimePermissions().forEach { add("pm grant $pkg $it") }
        add("appops set $pkg MANAGE_EXTERNAL_STORAGE allow")
        add("appops set $pkg WRITE_SETTINGS allow")
        add("cmd notification allow_listener $listener")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add("cmd role add-role-holder android.app.role.HOME $pkg 0")
        add("cmd package set-home-activity $pkg/.MainActivity")
        add("dumpsys deviceidle whitelist +$pkg")
    }
}

private suspend fun grantWithRoot(context: Context): Boolean = withContext(Dispatchers.IO) {
    // Some commands don't exist on every Android version; the grant counts if root ran the script.
    com.openlauncher.app.util.Root.run(grantCommands(context).joinToString("; ") { "$it >/dev/null 2>&1" }, requireSuccess = false) != null
}

/**
 * Every permission MMI needs on one page: what it is for, whether it is on,
 * and a tap to switch it on. With root, one button grants them all.
 */
@Composable
internal fun MmiPermissionsPage() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    val root = remember { com.openlauncher.app.util.Root.available() }
    var rootResult by remember { mutableStateOf<String?>(null) }

    val items = remember(refresh) {
        val pkgUri = Uri.parse("package:${context.packageName}")
        buildList {
            add(PermissionItem(
                "Location, Bluetooth, contacts, calls", "Navigation, pairing phones, calling from MMI",
                runtimePermissions().all { context.isGranted(it) },
            ) { ask.launch(runtimePermissions().toTypedArray()) })
            add(PermissionItem(
                "Media access", "Song info and controls for Bluetooth music, navigation cards",
                context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context),
            ) { context.launchFirst(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"), Intent(Settings.ACTION_SETTINGS)) })
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) add(PermissionItem(
                "All files access", "Finds map files on USB sticks and from your phone",
                Environment.isExternalStorageManager(),
            ) {
                context.launchFirst(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkgUri),
                    Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
                )
            })
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) add(PermissionItem(
                "Brightness control", "The Display page brightness slider",
                Settings.System.canWrite(context),
            ) { context.launchFirst(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, pkgUri), Intent(Settings.ACTION_SETTINGS)) })
            add(PermissionItem(
                "Default home screen", "The unit always starts in MMI",
                context.isDefaultHome(),
            ) {
                val roleIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    runCatching { context.getSystemService(RoleManager::class.java).createRequestRoleIntent(RoleManager.ROLE_HOME) }.getOrNull()
                } else null
                context.launchFirst(*listOfNotNull(roleIntent, Intent(Settings.ACTION_HOME_SETTINGS), Intent(Settings.ACTION_SETTINGS)).toTypedArray())
            })
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 48.dp, vertical = 8.dp)) {
        val missing = items.count { !it.granted }
        Text(
            if (missing == 0) "Everything is allowed." else "$missing still to allow. Tap each one, switch MMI on, then come back.",
            color = if (missing == 0) RouteGreenUi else MmiInk, fontFamily = MmiFont, fontSize = 18.sp,
        )
        if (root && missing > 0) {
            Spacer(Modifier.height(10.dp))
            ChromePill("Grant everything (root)", {
                scope.launch {
                    rootResult = if (grantWithRoot(context)) "Done." else "Root was refused."
                    refresh++
                }
            }, MmiLine.STAR, Modifier.width(320.dp))
            rootResult?.let { Text(it, color = MmiDim, fontFamily = MmiFont, fontSize = 15.sp) }
        }
        Spacer(Modifier.height(8.dp))
        items.forEach { item ->
            Row(
                Modifier.fillMaxWidth().height(64.dp).pressable(item.open).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(12.dp).clip(RoundedCornerShape(6.dp)).background(if (item.granted) RouteGreenUi else Color(0xFFE57373)))
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.label, color = MmiInk, fontFamily = MmiFont, fontSize = 19.sp)
                    Text(item.detail, color = MmiDim, fontFamily = MmiFont, fontSize = 14.sp)
                }
                Text(if (item.granted) "On" else "Allow", color = if (item.granted) MmiDim else MmiCyan, fontFamily = MmiFont, fontSize = 16.sp)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF1E2125)))
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "No root? Run tools/setup/install-mmi from a computer with the screen connected over ADB — it installs MMI and allows everything at once.",
            color = MmiDim, fontFamily = MmiFont, fontSize = 14.sp,
        )
    }
}

private val RouteGreenUi = Color(0xFF4CD964)

/** Intent that asks Android to make MMI the home app (role dialog on 10+, home chooser before). */
internal fun Context.homeRequestIntents(): List<Intent> {
    val role = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        runCatching {
            val rm = getSystemService(RoleManager::class.java)
            if (rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME)) rm.createRequestRoleIntent(RoleManager.ROLE_HOME) else null
        }.getOrNull()
    } else null
    return listOfNotNull(role, Intent(Settings.ACTION_HOME_SETTINGS), Intent(Settings.ACTION_SETTINGS))
}
