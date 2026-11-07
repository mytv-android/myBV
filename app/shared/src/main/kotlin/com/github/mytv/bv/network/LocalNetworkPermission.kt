package com.github.mytv.bv.network

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Android 17（API 37）起的本地网络保护（Local Network Protections）：targetSdk ≥ 37 的应用
 * 默认被阻断本地网络流量，[HttpServer] 这类内置服务的入站 TCP 连接同样要
 * android.permission.ACCESS_LOCAL_NETWORK。缺权限时表现为超时而不是 connection refused，
 * 服务端 start(...) 与端口监听全部照常成功；127.0.0.1 / ::1 是 loopback 不算本地网络，
 * 所以本机永远能访问——这也是最容易误判成「服务正常、是网络问题」的地方。
 *
 * 权限属于 NEARBY_DEVICES 组：用户给过蓝牙等同组权限时直接授予、不再弹窗；授予后按 UID 生效，
 * 已在监听的 socket 立刻恢复可达，不需要重启服务。
 */
object LocalNetworkPermission {
    /**
     * ACCESS_LOCAL_NETWORK 于 API 37 引入。低版本由 INTERNET 隐式授予本地网络访问，
     * 此时申请一个系统不认识的权限只会拿到「直接拒绝」，所以必须按 SDK 判断后再申请。
     */
    const val MIN_SDK = 37

    val isRequired: Boolean
        get() = Build.VERSION.SDK_INT >= MIN_SDK

    /** 不需要该权限的版本视为已授予，调用方不必再分流。 */
    fun isGranted(context: Context): Boolean =
        !isRequired || ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_LOCAL_NETWORK
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * 拒绝过该权限时为 true；连续两次拒绝后系统不再弹窗（返回 false），此时只能引导用户
     * 去系统设置里手动授予，否则局域网功能会一直静默失效。
     */
    fun shouldShowRationale(activity: Activity): Boolean =
        isRequired && activity.shouldShowRequestPermissionRationale(
            Manifest.permission.ACCESS_LOCAL_NETWORK
        )
}
