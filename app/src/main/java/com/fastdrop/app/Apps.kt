package com.fastdrop.app

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File

class AppEntry(
    val pkg: String, val label: String, val version: String, val apks: List<String>,
    val size: Long, val system: Boolean,
)

/** Lists installed apps that can be sent as APK files (split APKs included). */
object Apps {
    fun list(ctx: Context, includeSystem: Boolean): List<AppEntry> {
        val pm = ctx.packageManager
        val found = LinkedHashMap<String, ApplicationInfo>()
        for (cat in arrayOf(Intent.CATEGORY_LAUNCHER, "android.intent.category.LEANBACK_LAUNCHER")) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(cat)
            val list = try { pm.queryIntentActivities(intent, 0) } catch (_: Exception) { emptyList() }
            for (ri in list) {
                val ai = ri.activityInfo?.applicationInfo ?: continue
                if (!found.containsKey(ai.packageName)) found[ai.packageName] = ai
            }
        }
        val out = ArrayList<AppEntry>()
        for ((pkg, ai) in found) {
            if (pkg == ctx.packageName) continue
            val system = isSystem(ai)
            if (system && !includeSystem) continue
            entry(ctx, pm, ai, system)?.let { out += it }
        }
        out.sortBy { it.label.lowercase() }
        out.add(0, self(ctx))
        return out
    }

    /** FastDrop's own APK, so people can share the app itself. */
    fun self(ctx: Context): AppEntry {
        val pm = ctx.packageManager
        val ai = ctx.applicationInfo
        return entry(ctx, pm, ai, false) ?: AppEntry(
            ctx.packageName, "FastDrop", "", listOf(ai.sourceDir), File(ai.sourceDir).length(), false,
        )
    }

    // A system app that was updated from the store counts as a normal app.
    private fun isSystem(ai: ApplicationInfo): Boolean =
        (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
            (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0

    private fun entry(ctx: Context, pm: PackageManager, ai: ApplicationInfo, system: Boolean): AppEntry? {
        val base = ai.sourceDir ?: return null
        val apks = ArrayList<String>()
        apks += base
        ai.splitSourceDirs?.forEach { if (!it.isNullOrEmpty()) apks += it }
        val files = apks.filter { File(it).isFile }
        if (files.isEmpty()) return null
        val label = try { ai.loadLabel(pm).toString() } catch (_: Exception) { ai.packageName }
        return AppEntry(
            pkg = ai.packageName,
            label = label.ifBlank { ai.packageName },
            version = versionOf(pm, ai.packageName),
            apks = files,
            size = files.sumOf { File(it).length() },
            system = system,
        )
    }

    @Suppress("DEPRECATION")
    private fun versionOf(pm: PackageManager, pkg: String): String = try {
        val pi: PackageInfo = pm.getPackageInfo(pkg, 0)
        pi.versionName?.takeIf { it.isNotBlank() }
            ?: if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toString()
            else pi.versionCode.toString()
    } catch (_: Exception) { "" }

    /**
     * Single APK: "<Label> <ver>.apk". Split app: "<Label> <ver>/base.apk" plus one file per split,
     * named after the split ("split_config.arm64_v8a.apk" becomes "config.arm64_v8a.apk").
     */
    fun toSendItems(a: AppEntry): List<SendItem> {
        val tag = AppTag(a.label, a.pkg, a.version)
        val title = safe("${a.label} ${a.version}".trim())
        if (a.apks.size <= 1) {
            val f = File(a.apks.firstOrNull() ?: return emptyList())
            return listOf(SendItem("$title.apk", f.length(), null, f, tag))
        }
        val used = HashSet<String>()
        val out = ArrayList<SendItem>()
        a.apks.forEachIndexed { i, path ->
            val f = File(path)
            var n = if (i == 0) "base" else splitName(f.name, i)
            if (!used.add(n.lowercase())) { n = "${n}_$i"; used.add(n.lowercase()) }
            out += SendItem("$title/$n.apk", f.length(), null, f, tag)
        }
        return out
    }

    private fun splitName(fileName: String, index: Int): String {
        var n = fileName.removeSuffix(".apk")
        if (n.startsWith("split_")) n = n.removePrefix("split_")
        n = safe(n)
        return if (n.isBlank() || n == "base") "split$index" else n
    }

    // Keep names valid on every file system.
    private fun safe(s: String): String =
        s.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ").replace(Regex("\\s+"), " ").trim().trim('.')
            .ifEmpty { "app" }.take(80)
}
