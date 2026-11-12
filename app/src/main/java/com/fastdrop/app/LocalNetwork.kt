package com.fastdrop.app

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Android 17 (API 37) "local network protection".
 * Source: https://developer.android.com/privacy-and-security/local-network-permission
 *
 *  - Permission: android.permission.ACCESS_LOCAL_NETWORK, a runtime permission in the NEARBY_DEVICES
 *    group (a user who already granted another permission of that group is not asked again).
 *  - Enforced only for apps with targetSdk >= 37. Below that, apps holding INTERNET get an implicit
 *    grant, and the docs say NOT to declare or request it before targeting 37.
 *  - Covers outgoing / incoming TCP and UDP unicast, multicast and broadcast on local addresses:
 *    i.e. all of FastDrop (TCP 53530, UDP discovery). TCP failures look like timeouts, UDP like EPERM.
 *  - Strings are literals because compileSdk 36 doesn't know the constant.
 *
 * Everything here is inert while targetSdk < 37 (the current setting): [needed] is false.
 */
object LocalNetwork {
    const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

    /** True when Android enforces the permission for this install (device API 37+ AND our targetSdk 37+). */
    fun needed(ctx: Context): Boolean =
        Build.VERSION.SDK_INT >= 37 && ctx.applicationInfo.targetSdkVersion >= 37

    @android.annotation.TargetApi(23)
    fun granted(ctx: Context): Boolean =
        !needed(ctx) || ctx.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** Permissions to request before discovery / sockets (empty when nothing is needed or it's granted). */
    fun toRequest(ctx: Context): Array<String> =
        if (needed(ctx) && !granted(ctx)) arrayOf(PERMISSION) else emptyArray()
}
