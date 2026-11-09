package dev.aaa1115910.biliapi.http.util

import datacenter.hakase.protobuf.AndroidDeviceInfo
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

private const val RSA_PUBLIC_KEY =
    "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDOCQs2X+3WRvTcieZ7bncZuNDE" +
        "O0JvE/QCJQPUf6Csa8uc7fD+13TLBFe6qf31MljfX2SxZLvyLN2pWlH+TCeuApH6" +
        "EjKint1meowkdcvulu1Y34FzBFbT8/o4mVS9QYvQACi29pXil56XTlXy2KxhqWWq" +
        "Ahxx37YGj/aRiMpbZwIDAQAB"

/** 按文档 §5.2 生成指纹上报 body：AES 加密设备信息，RSA 加密 AES 密钥。 */
@OptIn(ExperimentalEncodingApi::class)
fun encryptFingerprint(deviceInfo: AndroidDeviceInfo): String {
    val aesKey = ByteArray(16).also { SecureRandom().nextBytes(it) }
    val aesKeyHex = aesKey.toHexUpper()
    val content = aesEncrypt(deviceInfo.toByteArray(), aesKey).toHexUpper()
    val key = rsaEncrypt(aesKeyHex.toByteArray(Charsets.UTF_8)).toHexUpper()
    return """{"key":"$key","content":"$content"}"""
}

/** 游客注册 `device_info` 的 best-effort JSON；字段与 `AndroidDeviceInfo` 对齐。 */
fun DeviceInfoSnapshot.toGuestDeviceInfoJson(): String = buildJsonObject {
    put("sdkver", sdkVer)
    put("app_id", appId)
    put("app_version", appVersion)
    put("app_version_code", appVersionCode)
    put("mid", mid)
    put("chid", chid)
    put("fts", fts)
    put("buvid_local", buvidLocal)
    put("first", 0)
    put("proc", proc)
    put("osver", osVer)
    put("t", timestamp)
    put("cpu_count", cpuCount)
    put("model", model)
    put("brand", brand)
    put("screen", screen)
    put("emu", emu)
    put("network", network)
    put("mem", mem)
    put("os", os)
    put("adid", adid)
    put("guid", guid)
    put("battery", battery)
    put("battery_state", batteryState)
    put("build_id", buildId)
    put("country_iso", countryIso)
    put("free_memory", freeMemory)
    put("total_space", totalSpace)
    put("kernel_version", kernelVersion)
    put("languages", languages)
    put("cpu_freq", cpuFreq)
    put("cpu_vendor", cpuVendor)
    put("sim", sim)
    put("oid", oid)
    put("is_root", isRoot)
    put("str_app_id", appId)
    put("str_battery", battery.toString())
    put("battery_present", true)
}.toString()

@OptIn(ExperimentalEncodingApi::class)
private fun rsaEncrypt(data: ByteArray): ByteArray {
    val keyBytes = Base64.decode(RSA_PUBLIC_KEY)
    val publicKey = KeyFactory.getInstance("RSA")
        .generatePublic(X509EncodedKeySpec(keyBytes))
    return Cipher.getInstance("RSA/ECB/PKCS1Padding").run {
        init(Cipher.ENCRYPT_MODE, publicKey)
        doFinal(data)
    }
}

private fun aesEncrypt(data: ByteArray, key: ByteArray): ByteArray {
    val secretKey = SecretKeySpec(key, "AES")
    return Cipher.getInstance("AES/ECB/PKCS5Padding").run {
        init(Cipher.ENCRYPT_MODE, secretKey)
        doFinal(data)
    }
}

private fun ByteArray.toHexUpper(): String =
    joinToString("") { "%02X".format(it.toInt() and 0xFF) }
