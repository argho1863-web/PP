package com.example.phoneagent.agent

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.File

/**
 * System-level actions that do not need the accessibility tree: Play Store, URLs, downloads.
 * The agent then continues through the normal observe/tap loop (e.g. tapping "Install").
 */
class ActionDispatcher(context: Context) {
    private val app = context.applicationContext

    private val packageRegex = Regex("""[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+""")
    private val blockedExtensions = listOf(".apk", ".xapk", ".apks", ".aab")

    /** OPEN_PLAY_STORE(query_or_package): package -> app page, anything else -> market search. */
    fun openPlayStore(ctx: Context, target: String): String {
        val t = target.trim()
        if (t.isEmpty()) return "error: open_play_store needs a query or a package name"
        val isPackage = packageRegex.matches(t)
        val market = if (isPackage) Uri.parse("market://details?id=$t")
        else Uri.parse("market://search?q=${Uri.encode(t)}&c=apps")
        val web = if (isPackage) Uri.parse("https://play.google.com/store/apps/details?id=$t")
        else Uri.parse("https://play.google.com/store/search?q=${Uri.encode(t)}&c=apps")
        val what = if (isPackage) "page for $t" else "search for \"$t\""
        return try {
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW, market).setPackage("com.android.vending").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            "ok: opened Play Store $what. Next: tap the app, tap Install, wait until the button says Open."
        } catch (e: ActivityNotFoundException) {
            try {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, web).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "ok: Play Store app not found, opened the web store $what"
            } catch (e2: Exception) {
                "error: cannot open Play Store: ${e2.message}"
            }
        } catch (e: Exception) {
            "error: cannot open Play Store: ${e.message}"
        }
    }

    /** OPEN_URL(url): http/https only. */
    fun openUrl(ctx: Context, url: String): String {
        val u = Uri.parse(url.trim())
        val scheme = u.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return "error: only http/https URLs are allowed"
        return try {
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW, u).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            "ok: opened ${u.host.orEmpty()}"
        } catch (e: Exception) {
            "error: cannot open URL: ${e.message}"
        }
    }

    /** check_installed(package): lets the agent confirm an install finished without relying on the UI. */
    fun checkInstalled(pkg: String): String {
        val p = pkg.trim()
        if (p.isEmpty()) return "error: check_installed needs a package name"
        return try {
            app.packageManager.getPackageInfo(p, 0)
            "installed: $p is installed"
        } catch (e: PackageManager.NameNotFoundException) {
            "not_installed: $p is not installed"
        }
    }

    /**
     * DOWNLOAD_FILE(url, fileName): DownloadManager -> Downloads folder, then MediaScanner so gallery /
     * editor apps can see the file. Suspends until the download finishes (cancellable with STOP).
     */
    suspend fun downloadFile(url: String, fileNameIn: String?): String {
        val u = Uri.parse(url.trim())
        val scheme = u.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return "error: download_file only supports http/https URLs"

        val rawName = fileNameIn?.takeIf { it.isNotBlank() }
            ?: u.lastPathSegment?.takeIf { it.isNotBlank() }
            ?: "download_${System.currentTimeMillis()}"
        val name = rawName.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(120)
        if (name.isEmpty()) return "error: invalid file name"
        if (blockedExtensions.any { name.lowercase().endsWith(it) }) {
            return "error: downloading app packages is blocked. Install apps with open_play_store instead."
        }

        val dm = app.getSystemService(DownloadManager::class.java) ?: return "error: DownloadManager unavailable"
        val id = try {
            dm.enqueue(
                DownloadManager.Request(u)
                    .setTitle(name)
                    .setDescription("Phone Agent download")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setAllowedOverMetered(true)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            )
        } catch (e: Exception) {
            return "error: cannot start download: ${e.message}"
        }

        val deadline = SystemClock.uptimeMillis() + 10 * 60_000L
        try {
            while (SystemClock.uptimeMillis() < deadline) {
                dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
                    if (c.moveToFirst()) {
                        val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                        if (status == DownloadManager.STATUS_SUCCESSFUL) {
                            val local = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
                            val size = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                            val path = local?.let { Uri.parse(it).path }
                                ?: File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), name).absolutePath
                            MediaScannerConnection.scanFile(app, arrayOf(path), null, null)
                            return "ok: saved to $path (${size / 1024} KB) and added to the media library"
                        }
                        if (status == DownloadManager.STATUS_FAILED) {
                            val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                            return "error: download failed (reason code $reason)"
                        }
                    }
                }
                delay(500)
            }
        } catch (e: CancellationException) {
            runCatching { dm.remove(id) }
            throw e
        }
        runCatching { dm.remove(id) }
        return "error: download timed out after 10 minutes"
    }
}
