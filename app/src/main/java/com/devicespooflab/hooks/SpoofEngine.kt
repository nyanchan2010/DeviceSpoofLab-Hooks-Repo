package com.devicespooflab.hooks

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID

data class AppEntry(
    val packageName: String,
    val label: String,
    val isScoped: Boolean,
    val isPinned: Boolean,
    val hasCustomProfile: Boolean
)

data class DevicePreset(
    val title: String,
    val manufacturer: String,
    val brand: String,
    val model: String,
    val device: String,
    val product: String,
    val hardware: String,
    val board: String,
    val bootloader: String,
    val fingerprint: String,
    val baseband: String
)

object SpoofEngine {
    const val MODULE_PKG = "com.devicespooflab.hooks"
    const val BRIDGE_DIR = "/data/local/tmp/devicespooflab"
    private val rng = SecureRandom()

    val PRESETS = listOf(
        DevicePreset(
            title = "Samsung Galaxy S25 Ultra",
            manufacturer = "samsung",
            brand = "samsung",
            model = "SM-S938B",
            device = "pa3q",
            product = "pa3qxxx",
            hardware = "qcom",
            board = "sun",
            bootloader = "S938BXXU1AYA1",
            fingerprint = "samsung/pa3qxxx/pa3q:15/AP3A.240905.015.A2/S938BXXU1AYA1:user/release-keys",
            baseband = "S938BXXU1AYA1"
        ),
        DevicePreset(
            title = "Google Pixel 9 Pro XL",
            manufacturer = "Google",
            brand = "google",
            model = "Komodo",
            device = "komodo",
            product = "komodo",
            hardware = "komodo",
            board = "komodo",
            bootloader = "ripcurrent-15.0-12103544",
            fingerprint = "google/komodo/komodo:15/AP3A.241005.015/12366759:user/release-keys",
            baseband = "g5400c-240718-240919-B-12394201"
        ),
        DevicePreset(
            title = "OnePlus 13",
            manufacturer = "OnePlus",
            brand = "OnePlus",
            model = "CPH2653",
            device = "OP5D0DL1",
            product = "CPH2653",
            hardware = "qcom",
            board = "sun",
            bootloader = "unknown",
            fingerprint = "OnePlus/CPH2653/OP5D0DL1:15/AP3A.240617.008/U.R4T3.1b3421_1:user/release-keys",
            baseband = "Q_V1_P14"
        ),
        DevicePreset(
            title = "Xiaomi 15 Pro",
            manufacturer = "Xiaomi",
            brand = "Xiaomi",
            model = "2410DPN6CC",
            device = "haotian",
            product = "haotian",
            hardware = "qcom",
            board = "sun",
            bootloader = "unknown",
            fingerprint = "Xiaomi/haotian/haotian:15/AQ3A.240812.002/OS2.0.12.0.VOBCNXM:user/release-keys",
            baseband = "MPSS.DE.7.0"
        ),
        DevicePreset(
            title = "Google Pixel 8a",
            manufacturer = "Google",
            brand = "google",
            model = "Pixel 8a",
            device = "akita",
            product = "akita",
            hardware = "akita",
            board = "akita",
            bootloader = "akita-14.5-11984321",
            fingerprint = "google/akita/akita:14/UQ1A.240205.004/11469683:user/release-keys",
            baseband = "g5300q-231218-240202-B-11396366"
        )
    )

    suspend fun readLSPosedScope(context: Context): Set<String> = withContext(Dispatchers.IO) {
        val scoped = mutableSetOf<String>()
        val tempDb = File(context.cacheDir, "lspd_scope_copy.db")
        val tempWal = File(context.cacheDir, "lspd_scope_copy.db-wal")
        val tempShm = File(context.cacheDir, "lspd_scope_copy.db-shm")

        try {
            tempDb.delete()
            tempWal.delete()
            tempShm.delete()

            val copySuccess = runRootCommands(
                "cp /data/adb/lspd/config/modules_config.db ${tempDb.absolutePath} 2>/dev/null || true",
                "cp /data/adb/lspd/config/modules_config.db-wal ${tempWal.absolutePath} 2>/dev/null || true",
                "cp /data/adb/lspd/config/modules_config.db-shm ${tempShm.absolutePath} 2>/dev/null || true",
                "chmod 666 ${tempDb.absolutePath}* 2>/dev/null || true"
            )

            if (copySuccess && tempDb.exists() && tempDb.length() > 0) {
                val db = SQLiteDatabase.openDatabase(
                    tempDb.absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
                )
                db.use { sqlite ->
                    val cursor = sqlite.rawQuery(
                        "SELECT s.app_pkg_name FROM scope s INNER JOIN modules m ON s.mid = m.mid WHERE m.module_pkg_name = ?",
                        arrayOf(MODULE_PKG)
                    )
                    cursor.use { c ->
                        while (c.moveToNext()) {
                            val pkg = c.getString(0)?.trim().orEmpty()
                            if (pkg.isNotEmpty() && pkg != "system" && pkg != MODULE_PKG) {
                                scoped.add(pkg)
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            tempDb.delete()
            tempWal.delete()
            tempShm.delete()
        }
        scoped
    }

    suspend fun loadInstalledApps(
        context: Context,
        pinnedSet: Set<String>
    ): Pair<List<AppEntry>, Set<String>> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val scopedSet = readLSPosedScope(context)
        val profilesDir = File(context.filesDir, "profiles").apply { mkdirs() }

        val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { info ->
                info.packageName != MODULE_PKG &&
                    (scopedSet.contains(info.packageName) ||
                        (info.flags and ApplicationInfo.FLAG_SYSTEM) == 0 ||
                        (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0)
            }
            .map { info ->
                val pkg = info.packageName
                AppEntry(
                    packageName = pkg,
                    label = info.loadLabel(pm).toString(),
                    isScoped = scopedSet.contains(pkg),
                    isPinned = pinnedSet.contains(pkg),
                    hasCustomProfile = File(profilesDir, "$pkg.conf").exists()
                )
            }
            .sortedWith(
                compareByDescending<AppEntry> { it.isScoped }
                    .thenByDescending { it.isPinned }
                    .thenBy { it.label.lowercase(Locale.ROOT) }
            )

        installed to scopedSet
    }

    fun generateRandomProfileMap(preset: DevicePreset): LinkedHashMap<String, String> {
        val imei1 = generateLuhn("35" + randomDigits(6), 15)
        val imei2 = generateLuhn("35" + randomDigits(6), 15)
        val meid = randomHex(14).uppercase(Locale.US)
        val imsi = "310260" + randomDigits(9)
        val iccid = generateLuhn("8901260" + randomDigits(11), 19)
        val eid = generateLuhn("89049032" + randomDigits(23), 32)
        val phone = "+1" + (201 + rng.nextInt(798)) + randomDigits(7)
        val androidId = randomHex(16).lowercase(Locale.US)
        val gsfId = randomHex(16).lowercase(Locale.US)
        val gaid = UUID.randomUUID().toString()
        val appSetId = UUID.randomUUID().toString()
        val drmId = randomHex(64).lowercase(Locale.US)
        val serial = randomHex(10).uppercase(Locale.US)
        val wifiMac = randomMacAddress()
        val wifiBssid = randomMacAddress()
        val wifiSsid = "HomeNet_" + randomHex(4).uppercase(Locale.US)

        return linkedMapOf(
            "preset.title" to preset.title,
            "ro.product.manufacturer" to preset.manufacturer,
            "ro.product.brand" to preset.brand,
            "ro.product.model" to preset.model,
            "ro.product.device" to preset.device,
            "ro.product.name" to preset.product,
            "ro.hardware" to preset.hardware,
            "ro.product.board" to preset.board,
            "ro.bootloader" to preset.bootloader,
            "ro.build.fingerprint" to preset.fingerprint,
            "gsm.version.baseband" to preset.baseband,
            "ro.serialno" to serial,
            "identifier.android_id" to androidId,
            "identifier.imei" to imei1,
            "identifier.imei2" to imei2,
            "identifier.meid" to meid,
            "identifier.imsi" to imsi,
            "identifier.iccid" to iccid,
            "euicc.eid" to eid,
            "identifier.phone_number" to phone,
            "identifier.gsf_id" to gsfId,
            "identifier.gaid" to gaid,
            "identifier.app_set_id" to appSetId,
            "identifier.media_drm_id" to drmId,
            "network.wifi_mac" to wifiMac,
            "network.wifi_bssid" to wifiBssid,
            "network.wifi_ssid" to wifiSsid,
            "sim.operator_numeric" to "310260",
            "sim.operator_name" to "T-Mobile",
            "sim.country_iso" to "us"
        )
    }

    fun readProfileMap(context: Context, targetPkg: String?): LinkedHashMap<String, String> {
        val file = getInternalProfileFile(context, targetPkg)
        if (!file.exists()) {
            val fresh = generateRandomProfileMap(PRESETS.first())
            saveAndSyncProfile(context, targetPkg, fresh)
            return fresh
        }
        val map = LinkedHashMap<String, String>()
        file.readLines(StandardCharsets.UTF_8).forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isNotEmpty() && !trimmed.startsWith("#") && trimmed.contains("=")) {
                val idx = trimmed.indexOf('=')
                map[trimmed.substring(0, idx).trim()] = trimmed.substring(idx + 1).trim()
            }
        }
        return map
    }

    fun saveAndSyncProfile(
        context: Context,
        targetPkg: String?,
        profileMap: Map<String, String>
    ): Boolean {
        val internalFile = getInternalProfileFile(context, targetPkg)
        internalFile.parentFile?.mkdirs()

        val content = buildString {
            append("# DeviceSpoofLab Profile\n")
            for ((k, v) in profileMap) {
                append(k).append('=').append(v.replace("\n", "")).append('\n')
            }
        }

        FileOutputStream(internalFile, false).use { fos ->
            fos.write(content.toByteArray(StandardCharsets.UTF_8))
            fos.flush()
            fos.fd.sync()
        }

        if (targetPkg == null) {
            val legacyGlobal = File(context.filesDir, "device_profile.conf")
            FileOutputStream(legacyGlobal, false).use { fos ->
                fos.write(content.toByteArray(StandardCharsets.UTF_8))
                fos.flush()
                fos.fd.sync()
            }
        }

        val bridgeFileName = if (targetPkg.isNullOrBlank()) "global.conf" else "$targetPkg.conf"
        val bridgeTarget = "$BRIDGE_DIR/$bridgeFileName"

        return runRootCommands(
            "mkdir -p $BRIDGE_DIR",
            "cp ${internalFile.absolutePath} $bridgeTarget",
            "chmod 755 $BRIDGE_DIR",
            "chmod 644 $bridgeTarget",
            "chmod 755 ${context.dataDir.absolutePath}",
            "chmod 755 ${context.filesDir.absolutePath}",
            "chmod -R 755 ${File(context.filesDir, "profiles").absolutePath}",
            "chmod 644 ${internalFile.absolutePath}",
            "sync"
        )
    }

    suspend fun executeOneTapWorkflow(
        context: Context,
        targetPkg: String,
        wipeData: Boolean,
        autoGrantPermissions: Boolean,
        rotatePreset: Boolean,
        selectedPresetIndex: Int,
        perAppIsolation: Boolean,
        onStep: (String) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            onStep("1/4 • Force stopping $targetPkg...")
            val stopped = runRootCommands(
                "am force-stop $targetPkg",
                "killall -9 $targetPkg 2>/dev/null || true"
            )
            if (!stopped) {
                return@withContext Result.failure(IllegalStateException("Root access denied (su failed)."))
            }

            if (wipeData) {
                onStep("2/4 • Wiping app data & cache (pm clear)...")
                runRootCommands("pm clear $targetPkg")

                if (autoGrantPermissions) {
                    onStep("2/4 • Re-granting runtime permissions...")
                    val grantCmds = buildGrantCommands(context.packageManager, targetPkg)
                    if (grantCmds.isNotEmpty()) {
                        runRootCommands(*grantCmds.toTypedArray())
                    }
                }
            }

            onStep("3/4 • Generating & syncing new hardware identity...")
            val chosenPreset = if (rotatePreset) {
                PRESETS[rng.nextInt(PRESETS.size)]
            } else {
                PRESETS.getOrElse(selectedPresetIndex) { PRESETS.first() }
            }

            val freshProfile = generateRandomProfileMap(chosenPreset)
            val profileKey = if (perAppIsolation) targetPkg else null
            saveAndSyncProfile(context, profileKey, freshProfile)

            if (perAppIsolation && !File("$BRIDGE_DIR/global.conf").exists()) {
                saveAndSyncProfile(context, null, freshProfile)
            }

            delay(200)

            onStep("4/4 • Cold launching $targetPkg...")
            var launched = false
            withContext(Dispatchers.Main) {
                val launchIntent = context.packageManager.getLaunchIntentForPackage(targetPkg)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    context.startActivity(launchIntent)
                    launched = true
                }
            }

            if (!launched) {
                return@withContext Result.failure(
                    IllegalStateException("Spoofed (${chosenPreset.title}), but app has no launchable activity.")
                )
            }

            Result.success("Spoofed as ${chosenPreset.title} & launched!")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun buildGrantCommands(pm: PackageManager, pkg: String): List<String> {
        val cmds = mutableListOf<String>()
        try {
            val pkgInfo: PackageInfo = pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS)
            val requested = pkgInfo.requestedPermissions ?: return emptyList()
            for (perm in requested) {
                if (perm.startsWith("android.permission.READ_") ||
                    perm.startsWith("android.permission.WRITE_") ||
                    perm.startsWith("android.permission.ACCESS_") ||
                    perm == "android.permission.CAMERA" ||
                    perm == "android.permission.RECORD_AUDIO" ||
                    perm == "android.permission.POST_NOTIFICATIONS"
                ) {
                    cmds.add("pm grant $pkg $perm 2>/dev/null || true")
                }
            }
        } catch (_: Exception) {
        }
        return cmds
    }

    fun getInternalProfileFile(context: Context, targetPkg: String?): File {
        val dir = File(context.filesDir, "profiles").apply { mkdirs() }
        return if (targetPkg.isNullOrBlank()) {
            File(dir, "global.conf")
        } else {
            File(dir, "$targetPkg.conf")
        }
    }

    fun runRootCommands(vararg commands: String): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("su")
            DataOutputStream(process.outputStream).use { os ->
                for (cmd in commands) {
                    os.writeBytes("$cmd\n")
                }
                os.writeBytes("exit\n")
                os.flush()
            }
            process.waitFor() == 0
        } catch (_: Exception) {
            false
        }
    }

    private fun randomDigits(len: Int): String =
        buildString(len) { repeat(len) { append(rng.nextInt(10)) } }

    private fun randomHex(len: Int): String {
        val chars = "0123456789abcdef"
        return buildString(len) { repeat(len) { append(chars[rng.nextInt(16)]) } }
    }

    private fun randomMacAddress(): String {
        val bytes = ByteArray(6)
        rng.nextBytes(bytes)
        bytes[0] = ((bytes[0].toInt() and 0xFE) or 0x02).toByte()
        return bytes.joinToString(":") { String.format(Locale.US, "%02X", it) }
    }

    private fun generateLuhn(prefix: String, totalLength: Int): String {
        val sb = StringBuilder(prefix)
        while (sb.length < totalLength - 1) {
            sb.append(rng.nextInt(10))
        }
        var sum = 0
        var alt = true
        for (i in sb.length - 1 downTo 0) {
            var n = sb[i] - '0'
            if (alt) {
                n *= 2
                if (n > 9) n -= 9
            }
            sum += n
            alt = !alt
        }
        val checkDigit = (10 - (sum % 10)) % 10
        sb.append(checkDigit)
        return sb.toString()
    }
}
