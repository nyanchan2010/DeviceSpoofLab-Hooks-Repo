package com.devicespooflab.hooks.xposed

import android.content.ContentResolver
import android.media.MediaDrm
import android.net.wifi.WifiInfo
import android.os.Build
import android.provider.Settings
import android.telephony.SubscriptionInfo
import android.telephony.TelephonyManager
import com.devicespooflab.hooks.SpoofEngine
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File
import java.net.NetworkInterface
import java.nio.charset.StandardCharsets

class MainHook : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val pkg = lpparam.packageName ?: return
        if (pkg == "android" || pkg.startsWith("com.android.systemui") || pkg == SpoofEngine.MODULE_PKG) {
            return
        }

        val config = loadConfigForPackage(pkg)
        if (config.isEmpty()) return

        runCatching { hookBuildFields(config) }
        runCatching { hookSystemProperties(lpparam.classLoader, config) }
        runCatching { hookAndroidId(config) }
        runCatching { hookTelephony(config) }
        runCatching { hookWifiAndNetwork(config) }
        runCatching { hookMediaDrm(config) }
    }

    private fun loadConfigForPackage(pkg: String): Map<String, String> {
        val candidates = listOf(
            File("${SpoofEngine.BRIDGE_DIR}/$pkg.conf"),
            File("${SpoofEngine.BRIDGE_DIR}/global.conf"),
            File("/data/data/${SpoofEngine.MODULE_PKG}/files/profiles/$pkg.conf"),
            File("/data/data/${SpoofEngine.MODULE_PKG}/files/profiles/global.conf"),
            File("/data/data/${SpoofEngine.MODULE_PKG}/files/device_profile.conf")
        )

        val targetFile = candidates.firstOrNull { it.exists() && it.canRead() && it.length() > 0 }
            ?: return emptyMap()

        val map = HashMap<String, String>()
        runCatching {
            targetFile.readLines(StandardCharsets.UTF_8).forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isNotEmpty() && !trimmed.startsWith("#") && trimmed.contains("=")) {
                    val idx = trimmed.indexOf('=')
                    map[trimmed.substring(0, idx).trim()] = trimmed.substring(idx + 1).trim()
                }
            }
        }
        return map
    }

    private fun hookBuildFields(cfg: Map<String, String>) {
        setBuildField("MANUFACTURER", cfg["ro.product.manufacturer"])
        setBuildField("BRAND", cfg["ro.product.brand"])
        setBuildField("MODEL", cfg["ro.product.model"])
        setBuildField("DEVICE", cfg["ro.product.device"])
        setBuildField("PRODUCT", cfg["ro.product.name"])
        setBuildField("HARDWARE", cfg["ro.hardware"])
        setBuildField("BOARD", cfg["ro.product.board"])
        setBuildField("BOOTLOADER", cfg["ro.bootloader"])
        setBuildField("FINGERPRINT", cfg["ro.build.fingerprint"])
        setBuildField("SERIAL", cfg["ro.serialno"])

        cfg["ro.serialno"]?.let { serial ->
            XposedHelpers.findAndHookMethod(
                Build::class.java,
                "getSerial",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.result = serial
                    }
                }
            )
        }
    }

    private fun setBuildField(fieldName: String, value: String?) {
        if (!value.isNullOrBlank()) {
            runCatching { XposedHelpers.setStaticObjectField(Build::class.java, fieldName, value) }
        }
    }

    private fun hookSystemProperties(classLoader: ClassLoader, cfg: Map<String, String>) {
        val sysPropClass = XposedHelpers.findClassIfExists("android.os.SystemProperties", classLoader) ?: return

        XposedHelpers.findAndHookMethod(
            sysPropClass,
            "get",
            String::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val key = param.args[0] as? String ?: return
                    cfg[key]?.let { param.result = it }
                }
            }
        )

        XposedHelpers.findAndHookMethod(
            sysPropClass,
            "get",
            String::class.java,
            String::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val key = param.args[0] as? String ?: return
                    cfg[key]?.let { param.result = it }
                }
            }
        )
    }

    private fun hookAndroidId(cfg: Map<String, String>) {
        val fakeAndroidId = cfg["identifier.android_id"] ?: return
        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val name = param.args.getOrNull(1) as? String
                if (name == Settings.Secure.ANDROID_ID) {
                    param.result = fakeAndroidId
                }
            }
        }
        XposedHelpers.findAndHookMethod(
            Settings.Secure::class.java,
            "getString",
            ContentResolver::class.java,
            String::class.java,
            hook
        )
        XposedHelpers.findAndHookMethod(
            Settings.System::class.java,
            "getString",
            ContentResolver::class.java,
            String::class.java,
            hook
        )
    }

    private fun hookTelephony(cfg: Map<String, String>) {
        val tmClass = TelephonyManager::class.java

        hookAllVariations(tmClass, "getImei", cfg["identifier.imei"])
        hookAllVariations(tmClass, "getDeviceId", cfg["identifier.imei"])
        hookAllVariations(tmClass, "getMeid", cfg["identifier.meid"])
        hookAllVariations(tmClass, "getSubscriberId", cfg["identifier.imsi"])
        hookAllVariations(tmClass, "getSimSerialNumber", cfg["identifier.iccid"])
        hookAllVariations(tmClass, "getLine1Number", cfg["identifier.phone_number"])
        hookAllVariations(tmClass, "getSimOperator", cfg["sim.operator_numeric"])
        hookAllVariations(tmClass, "getNetworkOperator", cfg["sim.operator_numeric"])
        hookAllVariations(tmClass, "getSimOperatorName", cfg["sim.operator_name"])
        hookAllVariations(tmClass, "getNetworkOperatorName", cfg["sim.operator_name"])
        hookAllVariations(tmClass, "getSimCountryIso", cfg["sim.country_iso"])
        hookAllVariations(tmClass, "getNetworkCountryIso", cfg["sim.country_iso"])

        val subClass = SubscriptionInfo::class.java
        hookAllVariations(subClass, "getIccId", cfg["identifier.iccid"])
        hookAllVariations(subClass, "getNumber", cfg["identifier.phone_number"])
        hookAllVariations(subClass, "getCountryIso", cfg["sim.country_iso"])
    }

    private fun hookWifiAndNetwork(cfg: Map<String, String>) {
        val mac = cfg["network.wifi_mac"]
        val bssid = cfg["network.wifi_bssid"]
        val ssid = cfg["network.wifi_ssid"]

        hookAllVariations(WifiInfo::class.java, "getMacAddress", mac)
        hookAllVariations(WifiInfo::class.java, "getBSSID", bssid)
        if (!ssid.isNullOrBlank()) {
            hookAllVariations(WifiInfo::class.java, "getSSID", "\"$ssid\"")
        }

        if (!mac.isNullOrBlank()) {
            val macBytes = runCatching {
                mac.split(":").map { it.toInt(16).toByte() }.toByteArray()
            }.getOrNull()

            if (macBytes != null) {
                XposedBridge.hookAllMethods(
                    NetworkInterface::class.java,
                    "getHardwareAddress",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            param.result = macBytes
                        }
                    }
                )
            }
        }
    }

    private fun hookMediaDrm(cfg: Map<String, String>) {
        val drmHex = cfg["identifier.media_drm_id"] ?: return
        val drmBytes = runCatching {
            drmHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }.getOrNull() ?: return

        XposedHelpers.findAndHookMethod(
            MediaDrm::class.java,
            "getPropertyByteArray",
            String::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (param.args[0] == MediaDrm.PROPERTY_DEVICE_UNIQUE_ID) {
                        param.result = drmBytes
                    }
                }
            }
        )
    }

    private fun hookAllVariations(clazz: Class<*>, methodName: String, returnVal: String?) {
        if (returnVal.isNullOrBlank()) return
        runCatching {
            XposedBridge.hookAllMethods(
                clazz,
                methodName,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.result = returnVal
                    }
                }
            )
        }
    }
}
