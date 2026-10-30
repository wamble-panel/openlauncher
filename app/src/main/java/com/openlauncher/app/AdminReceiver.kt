package com.openlauncher.app

import android.app.admin.DeviceAdminReceiver

/**
 * Lets MMI become the unit's device owner (one ADB command, see tools/setup):
 * Android then allows it to install its own updates with no confirmation.
 */
class AdminReceiver : DeviceAdminReceiver()
