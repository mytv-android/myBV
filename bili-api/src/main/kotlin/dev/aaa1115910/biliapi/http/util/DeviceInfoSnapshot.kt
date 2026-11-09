package dev.aaa1115910.biliapi.http.util

import datacenter.hakase.protobuf.AndroidDeviceInfo
import datacenter.hakase.protobuf.androidDeviceInfo

/**
 * 设备信息快照：应用层用 Android API 采集，bili-api 再转成指纹接口需要的
 * `datacenter.hakase.protobuf.AndroidDeviceInfo`，避免把 protobuf 类型暴露给应用层。
 */
data class DeviceInfoSnapshot(
    val sdkVer: String = "",
    val appId: String = "",
    val appVersion: String = "",
    val appVersionCode: String = "",
    val mid: String = "",
    val chid: String = "",
    val fts: Long = 0L,
    val buvidLocal: String = "",
    val proc: String = "",
    val osVer: String = "",
    val timestamp: Long = 0L,
    val cpuCount: Int = 0,
    val model: String = "",
    val brand: String = "",
    val screen: String = "",
    val emu: String = "000",
    val network: String = "",
    val mem: Long = 0L,
    val os: String = "android",
    val adid: String = "",
    val guid: String = "",
    val battery: Int = 0,
    val batteryState: String = "",
    val buildId: String = "",
    val countryIso: String = "",
    val freeMemory: Long = 0L,
    val totalSpace: Long = 0L,
    val kernelVersion: String = "",
    val languages: String = "",
    val cpuFreq: Long = 0L,
    val cpuVendor: String = "",
    val sim: String = "",
    val oid: String = "",
    val isRoot: Boolean = false,
)

fun DeviceInfoSnapshot.toAndroidDeviceInfo(): AndroidDeviceInfo {
    val info = this
    return androidDeviceInfo {
        sdkver = info.sdkVer
        appId = info.appId
        appVersion = info.appVersion
        appVersionCode = info.appVersionCode
        mid = info.mid
        chid = info.chid
        fts = info.fts
        buvidLocal = info.buvidLocal
        first = 0
        proc = info.proc
        osver = info.osVer
        t = info.timestamp
        cpuCount = info.cpuCount
        model = info.model
        brand = info.brand
        screen = info.screen
        emu = info.emu
        network = info.network
        mem = info.mem
        os = info.os
        adid = info.adid
        guid = info.guid
        battery = info.battery
        batteryState = info.batteryState
        buildId = info.buildId
        countryIso = info.countryIso
        freeMemory = info.freeMemory
        totalSpace = info.totalSpace
        kernelVersion = info.kernelVersion
        languages = info.languages
        cpuFreq = info.cpuFreq
        cpuVendor = info.cpuVendor
        sim = info.sim
        oid = info.oid
        isRoot = info.isRoot
        strAppId = info.appId
        strBattery = info.battery.toString()
        batteryPresent = true
    }
}
