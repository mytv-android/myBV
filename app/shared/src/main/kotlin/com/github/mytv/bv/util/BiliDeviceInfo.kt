package com.github.mytv.bv.util

import android.app.ActivityManager
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.StatFs
import android.provider.Settings
import com.github.mytv.bv.BVApp
import dev.aaa1115910.biliapi.http.util.AppRequestContext
import dev.aaa1115910.biliapi.http.util.BiliAppConf
import dev.aaa1115910.biliapi.http.util.DeviceInfoSnapshot
import java.io.File
import java.util.Locale

/** 按文档 §5.2 采集 AndroidDeviceInfo 需要的设备字段。 */
object BiliDeviceInfo {
    fun snapshot(): DeviceInfoSnapshot {
        val context = BVApp.context
        val memory = runCatching {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            ActivityManager.MemoryInfo().also { manager.getMemoryInfo(it) }
        }.getOrNull()
        val metrics = context.resources.displayMetrics
        val battery = runCatching {
            val manager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        }.getOrDefault(0)
        val totalSpace = runCatching { StatFs(context.filesDir.path).totalBytes }.getOrDefault(0L)
        val cpuFreq = runCatching {
            File("/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq")
                .readText()
                .trim()
                .toLong()
        }.getOrDefault(0L)

        return DeviceInfoSnapshot(
            sdkVer = Build.VERSION.SDK_INT.toString(),
            appId = BiliAppConf.APP_ID.toString(),
            appVersion = BiliAppConf.APP_VERSION_NAME,
            appVersionCode = BiliAppConf.APP_BUILD_CODE.toString(),
            mid = Prefs.uid.takeIf { it > 0 }?.toString().orEmpty(),
            chid = BiliAppConf.CHANNEL,
            fts = Prefs.firstLaunchTime,
            buvidLocal = AppRequestContext.fpLocal.orEmpty(),
            proc = context.packageName,
            osVer = Build.VERSION.RELEASE,
            timestamp = System.currentTimeMillis(),
            cpuCount = Runtime.getRuntime().availableProcessors(),
            model = Build.MODEL,
            brand = Build.BRAND,
            screen = "${metrics.widthPixels},${metrics.heightPixels},${metrics.densityDpi}",
            emu = if (Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("Emulator")) {
                "1"
            } else {
                "000"
            },
            network = when (BiliAppConf.currentNetwork) {
                2 -> "WIFI"
                1 -> "CELLULAR"
                else -> "OTHERNET"
            },
            mem = memory?.totalMem ?: Runtime.getRuntime().totalMemory(),
            os = "android",
            adid = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ANDROID_ID
            ).orEmpty(),
            guid = Prefs.displayId,
            battery = battery,
            batteryState = "BATTERY_STATUS_UNKNOWN",
            buildId = Build.DISPLAY,
            countryIso = Locale.getDefault().country,
            freeMemory = memory?.availMem ?: Runtime.getRuntime().freeMemory(),
            totalSpace = totalSpace,
            kernelVersion = System.getProperty("os.version").orEmpty(),
            languages = Locale.getDefault().language,
            cpuFreq = cpuFreq,
            cpuVendor = System.getProperty("os.arch").orEmpty(),
            sim = "",
            oid = "",
            isRoot = false,
        )
    }
}
