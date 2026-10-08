// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay

import android.app.DownloadManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

internal object OtaUpdater {
    private const val RELEASES = "https://api.github.com/repos/fatihdonmezdev/MG4-Wireless-Carplay/releases?per_page=20"
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    data class Release(val version: String, val url: String, val fileName: String, val sha256: String)

    fun check(context: Context, callback: (Result<Release?>) -> Unit) = worker.execute {
        val result = runCatching {
            val connection = URL(RELEASES).openConnection() as HttpURLConnection
            connection.connectTimeout = 7_000
            connection.readTimeout = 7_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "MG4CPlay/${version(context)}")
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()
            select(JSONArray(body), version(context))
        }
        main.post { callback(result) }
    }

    fun downloadAndInstall(context: Context, release: Release, status: (String) -> Unit) {
        val dm = context.getSystemService(DownloadManager::class.java)
            ?: return status("DownloadManager unavailable")
        val request = DownloadManager.Request(Uri.parse(release.url))
            .setTitle("MG4CPlay ${release.version}")
            .setDescription("Downloading update")
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, release.fileName)
        val id = runCatching { dm.enqueue(request) }.getOrElse { return status("Download failed: ${it.javaClass.simpleName}") }
        status("Downloading ${release.version}…")
        poll(context, dm, id, release, status)
    }

    private fun poll(context: Context, dm: DownloadManager, id: Long, release: Release, status: (String) -> Unit) {
        main.postDelayed(object : Runnable {
            override fun run() {
                val state = dm.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
                    if (!cursor.moveToFirst()) return@use -1
                    val downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    if (total > 0 && downloaded >= 0) {
                        val percent = (downloaded * 100 / total).coerceIn(0, 100)
                        status("Downloading… $percent% (${downloaded / 1_048_576} / ${total / 1_048_576} MB)")
                    }
                    cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                }
                when (state) {
                    DownloadManager.STATUS_SUCCESSFUL -> verifyAndInstall(context, dm, id, release, status)
                    DownloadManager.STATUS_FAILED -> status("Download failed")
                    else -> main.postDelayed(this, 500)
                }
            }
        }, 500)
    }

    private fun verifyAndInstall(context: Context, dm: DownloadManager, id: Long, release: Release, status: (String) -> Unit) {
        status("Verifying update…")
        worker.execute {
            val result = runCatching {
                val digest = dm.openDownloadedFile(id).use { pfd ->
                    pfd.fileDescriptor.let { fd -> java.io.FileInputStream(fd).use(::sha256) }
                }
                check(digest.equals(release.sha256, ignoreCase = true)) { "SHA-256 mismatch" }
                val uri = dm.getUriForDownloadedFile(id) ?: error("Downloaded APK unavailable")
                val staged = java.io.File(context.cacheDir, "mg4cplay-update.apk")
                context.contentResolver.openInputStream(uri)!!.use { input -> staged.outputStream().use(input::copyTo) }
                verifyPackage(context, staged.absolutePath)
                install(context, staged)
            }
            main.post { status(result.fold({ "Update verified; installation started" }, { "Update rejected: ${it.message}" })) }
        }
    }

    private fun verifyPackage(context: Context, path: String) {
        val pm = context.packageManager
        val archive = pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES)
            ?: error("Invalid APK")
        check(archive.packageName == context.packageName) { "Package name mismatch" }
        val installed = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES)
        @Suppress("DEPRECATION")
        fun certificates(info: android.content.pm.PackageInfo): Set<String> {
            val signers = info.signingInfo?.apkContentsSigners?.toList()
                ?.takeIf { it.isNotEmpty() }
                ?: info.signatures?.toList().orEmpty()
            check(signers.isNotEmpty()) { "Signing certificate unavailable" }
            return signers.map { signer ->
                MessageDigest.getInstance("SHA-256").digest(signer.toByteArray())
                    .joinToString("") { "%02x".format(it) }
            }.toSet()
        }
        val expected = certificates(installed)
        val actual = certificates(archive)
        check(expected == actual) { "Signing certificate mismatch" }
    }

    private fun install(context: Context, apk: java.io.File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply { setAppPackageName(context.packageName) }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            apk.inputStream().use { input -> session.openWrite("MG4CPlay.apk", 0, apk.length()).use { out -> input.copyTo(out); session.fsync(out) } }
            val intent = Intent(context, OtaInstallReceiver::class.java).setAction("com.shihab.diplay.OTA_RESULT")
            val pending = PendingIntent.getBroadcast(context, sessionId, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            session.commit(pending.intentSender)
        }
    }

    private fun select(releases: JSONArray, current: String): Release? {
        for (i in 0 until releases.length()) {
            val release = releases.getJSONObject(i)
            if (release.optBoolean("draft") || release.optBoolean("prerelease")) continue
            val version = release.optString("tag_name").trimStart('v', 'V')
            if (compare(version, current) <= 0) continue
            val assets = release.optJSONArray("assets") ?: continue
            for (j in 0 until assets.length()) {
                val asset = assets.getJSONObject(j)
                val name = asset.optString("name")
                if (!name.startsWith("MG4CPlay-") || !name.endsWith(".apk")) continue
                val digest = asset.optString("digest").removePrefix("sha256:")
                if (!digest.matches(Regex("[a-fA-F0-9]{64}"))) continue
                return Release(version, asset.getString("browser_download_url"), name, digest)
            }
        }
        return null
    }

    private fun compare(left: String, right: String): Int {
        fun parts(value: String) = Regex("\\d+").findAll(value).map { it.value.toIntOrNull() ?: 0 }.toList()
        val a = parts(left); val b = parts(right)
        for (i in 0 until maxOf(a.size, b.size)) {
            val result = (a.getOrElse(i) { 0 }).compareTo(b.getOrElse(i) { 0 })
            if (result != 0) return result
        }
        return 0
    }

    private fun sha256(input: java.io.InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        while (true) { val read = input.read(buffer); if (read < 0) break; digest.update(buffer, 0, read) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun version(context: Context) = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
}

class OtaInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION") val confirmation = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            confirmation?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (confirmation != null) context.startActivity(confirmation)
            return
        }
        Toast.makeText(context, if (status == PackageInstaller.STATUS_SUCCESS) "MG4CPlay updated" else "Update install failed", Toast.LENGTH_LONG).show()
    }
}
