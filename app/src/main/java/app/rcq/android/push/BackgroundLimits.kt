package app.rcq.android.push

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import app.rcq.android.R

/**
 * Whether Android (or the phone maker on top of it) is allowed to put RCQ to
 * sleep with the screen off, and the ways to ask it not to.
 *
 * ⚠⚠ #1044, and #1028 before it: "with the screen off, notifications either do
 * not come or come without sound; the alert fires when the screen is turned
 * on. IQOO Z10 and Huawei Pura 80 do it, a Xiaomi Note 13S does not."
 *
 * A sideloaded RCQ is its own push service. Every wake rides one WebSocket held
 * by a foreground service ([app.rcq.android.push.embedded.PushSocketService]),
 * and on stock Android that is enough: Doze keeps the network of a process in
 * the foreground-service state, which is why the emulator and most phones get
 * their messages with the screen off. vivo/iQOO (OriginOS) and Huawei/Honor go
 * further than Doze: with the screen off they freeze or cut the network of any
 * app that is not on their own allow-list, foreground service or not. The
 * socket then sits unread until the screen comes on, the process thaws, the
 * backlog is read, and the notification and its sound arrive together at that
 * moment, which is exactly what was reported. No sound player, wake lock or
 * channel setting inside RCQ can run while the process is frozen, so the only
 * fix available to the app is to ask to be left alone.
 *
 * RCQ never asked. There was no battery-optimisation request anywhere in the
 * app, so every one of these phones kept RCQ in its default, restricted state.
 * This object is the asking: the standard exemption, which several of these
 * ROMs honour at least in part, and a best-effort door to the maker's own
 * screen for the ones that keep a separate list.
 */
object BackgroundLimits {

    /** Makers whose own power managers are known to freeze background apps
     *  beyond what Doze does. Only these get the home-screen nudge; everybody
     *  else still sees the state in Settings → Notifications. */
    enum class Vendor { HUAWEI, VIVO, OPPO, XIAOMI, OTHER_STRICT }

    fun vendor(): Vendor? {
        val m = (Build.MANUFACTURER.orEmpty() + " " + Build.BRAND.orEmpty()).lowercase()
        return when {
            "huawei" in m || "honor" in m -> Vendor.HUAWEI
            "vivo" in m || "iqoo" in m -> Vendor.VIVO
            "oppo" in m || "realme" in m || "oneplus" in m -> Vendor.OPPO
            "xiaomi" in m || "redmi" in m || "poco" in m -> Vendor.XIAOMI
            "meizu" in m || "asus" in m || "tecno" in m || "infinix" in m -> Vendor.OTHER_STRICT
            else -> null
        }
    }

    /** True when Android lets RCQ run unrestricted in the background. */
    fun exempt(ctx: Context): Boolean = runCatching {
        ctx.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(ctx.packageName) ?: true
    }.getOrDefault(true)

    /** The home-screen nudge: a maker known for it, and RCQ still restricted. */
    fun nudgeWanted(ctx: Context): Boolean = vendor() != null && !exempt(ctx)

    /** Where to go on this maker's phone once the standard exemption is given,
     *  or null where the standard one is the whole story. */
    fun vendorHintRes(): Int? = when (vendor()) {
        Vendor.HUAWEI -> R.string.bg_limits_hint_huawei
        Vendor.VIVO -> R.string.bg_limits_hint_vivo
        Vendor.OPPO, Vendor.XIAOMI, Vendor.OTHER_STRICT -> R.string.bg_limits_hint_other
        null -> null
    }

    /** Ask for the exemption. The sideload build holds
     *  REQUEST_IGNORE_BATTERY_OPTIMIZATIONS and gets the one-tap system
     *  dialog; the Play build does not carry the permission (see the play
     *  manifest) and opens the system list instead. False when neither could
     *  be opened. */
    fun requestExemption(ctx: Context): Boolean {
        val direct = !app.rcq.android.BuildConfig.PLAY_STORE && launch(
            ctx,
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")),
        )
        return direct || launch(ctx, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) ||
            openAppDetails(ctx)
    }

    /** The maker's own background / auto-launch screen, best effort.
     *
     *  ⚠ These component names are not an API. They are the ones the makers'
     *  own security apps have shipped for years (the same list auto-start
     *  helper libraries carry), they move between OS versions, and some are
     *  not exported. Each is simply tried; the first that opens wins, and the
     *  app's own info page, where every one of these ROMs keeps a per-app
     *  battery entry, is the answer when none does. */
    fun openVendorSettings(ctx: Context): Boolean {
        val candidates = when (vendor()) {
            Vendor.HUAWEI -> listOf(
                "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
                "com.hihonor.systemmanager" to "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            )
            // ⚠ The battery screen FIRST (#1044 review). What keeps a process
            // unfrozen with the screen off on OriginOS/Funtouch is "high
            // background power consumption" under Battery, and the first cut
            // opened the auto-start list, which is a different switch, while
            // the text beside the button named the battery one. Auto-start is
            // the fallback, and the text names both.
            Vendor.VIVO -> listOf(
                "com.vivo.abe" to "com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity",
                "com.iqoo.powersaving" to "com.iqoo.powersaving.PowerSavingManagerActivity",
                "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
                "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
                "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
            )
            Vendor.OPPO -> listOf(
                "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
                "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
                "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
            )
            Vendor.XIAOMI -> listOf(
                "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
            )
            else -> emptyList()
        }
        for ((pkg, cls) in candidates) {
            if (launch(ctx, Intent().setComponent(ComponentName(pkg, cls)))) return true
        }
        return openAppDetails(ctx)
    }

    private fun openAppDetails(ctx: Context): Boolean =
        launch(ctx, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))

    private fun launch(ctx: Context, intent: Intent): Boolean = runCatching {
        ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)
}
