/*
 * Wispr by Deepu Gupta
 * Copyright 2026 Deepu Gupta
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 * SPDX-License-Identifier: Apache-2.0
 */
package com.deepugupta.wispr

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class UpdateError(message: String, val keyMismatch: Boolean = false, val noRelease: Boolean = false) : Exception(message)

/**
 * In-app updates straight from this project's GitHub Releases page.
 *
 * 1. Checks github.com/<owner>/<repo>/releases/latest (REST API with ETag, falls back to the
 *    plain release page if the 60-requests/hour anonymous API limit is hit).
 * 2. Downloads the .apk, checks its SHA-256 (GitHub's asset digest or SHA256SUMS.txt) and makes sure
 *    it is the same app, signed with the SAME key as the installed one, and not older.
 * 3. Installs it with PackageInstaller. On Android 12+ a self-update needs no extra prompt
 *    (USER_ACTION_NOT_REQUIRED); older phones show Android's normal "Update" dialog.
 *
 * The repo is baked in at build time (GitHub Actions passes github.repository), so forks check their own releases.
 */
object Updater {
    const val ACTION_STATUS = "com.deepugupta.wispr.UPDATE_STATUS"
    private const val API = "https://api.github.com/repos/"
    private const val WEB = "https://github.com/"
    private const val APP_GAP_MS = 6L * 60 * 60 * 1000      // app open: at most every 6 h
    private const val BG_GAP_MS = 12L * 60 * 60 * 1000      // bubble/background: at most every 12 h
    private const val RETRY_GAP_MS = 60L * 60 * 1000        // after a failed background check: 1 h
    private const val MAX_APK = 200L * 1024 * 1024
    private val REPO_RE = Regex("^[A-Za-z0-9-]{1,39}/[A-Za-z0-9._-]{1,100}$")

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.MINUTES)
        .retryOnConnectionFailure(true)
        .build()
    private val noRedirect: OkHttpClient = http.newBuilder().followRedirects(false).followSslRedirects(false).build()

    data class Release(
        val version: String, val tag: String, val title: String, val notes: String, val page: String,
        val apkUrl: String, val apkName: String, val size: Long, val sha256: String, val sumsUrl: String
    ) {
        fun toJson(): JSONObject = JSONObject().put("version", version).put("tag", tag).put("title", title)
            .put("notes", notes).put("page", page).put("apkUrl", apkUrl).put("apkName", apkName)
            .put("size", size).put("sha256", sha256).put("sumsUrl", sumsUrl)

        companion object {
            fun from(j: JSONObject) = Release(
                j.optString("version"), j.optString("tag"), j.optString("title"), j.optString("notes"),
                j.optString("page"), j.optString("apkUrl"), j.optString("apkName"), j.optLong("size"),
                j.optString("sha256"), j.optString("sumsUrl")
            )
        }
    }

    /** Pushes state changes to the open app screen (set by MainActivity). */
    @Volatile var listener: ((String) -> Unit)? = null
    /** Set when we sent the user to "Install unknown apps"; MainActivity continues on return. */
    @Volatile var pendingInstall = false
    @Volatile private var live: JSONObject? = null
    @Volatile private var cancel = false
    private val busy = AtomicBoolean(false)

    val repo: String get() = BuildConfig.UPDATE_REPO.trim()
    fun configured(): Boolean = BuildConfig.SELF_UPDATE && REPO_RE.matches(repo)
    fun releasesPage(): String = if (REPO_RE.matches(repo)) "$WEB$repo/releases/latest" else ""

    private fun sp(ctx: Context) = ctx.getSharedPreferences("wispr_update", Context.MODE_PRIVATE)
    private fun mode(ctx: Context): String = Store.get(ctx).str("updates").let { if (it in setOf("auto", "notify", "off")) it else "auto" }

    // ---------------- versions ----------------

    /** Semantic compare: 4.10.0 > 4.9.2, 4.1.0 > 4.1.0-beta.2, "v" prefix ignored. */
    fun compare(a: String, b: String): Int {
        fun core(s: String) = s.trim().removePrefix("v").removePrefix("V").substringBefore('+')
        fun nums(s: String) = core(s).substringBefore('-').split('.').map { p -> p.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
        fun pre(s: String) = core(s).substringAfter('-', "")
        val x = nums(a); val y = nums(b)
        for (i in 0 until maxOf(x.size, y.size, 3)) {
            val c = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        val pa = pre(a); val pb = pre(b)
        return when {
            pa == pb -> 0
            pa.isEmpty() -> 1
            pb.isEmpty() -> -1
            else -> pa.compareTo(pb)
        }
    }

    private fun localVersion(): String = BuildConfig.VERSION_NAME.replace(Regex("-build\\.\\d+$"), "")

    fun isNewer(v: String): Boolean = v.isNotBlank() && compare(v, localVersion()) > 0

    private fun installedSha(ctx: Context): String {
        val key = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime.toString() }.getOrDefault("")
        val p = sp(ctx)
        if (key.isNotEmpty() && p.getString("instKey", null) == key) return p.getString("instSha", "") ?: ""
        val sha = runCatching { sha256(File(ctx.applicationInfo.sourceDir)) }.getOrDefault("")
        p.edit().putString("instKey", key).putString("instSha", sha).apply()
        return sha
    }

    fun hasUpdate(ctx: Context, rel: Release): Boolean {
        if (rel.sha256.length == 64 && rel.sha256.equals(installedSha(ctx), ignoreCase = true)) return false
        return isNewer(rel.version)
    }

    // ---------------- state for the UI ----------------

    private fun cached(ctx: Context): Release? =
        sp(ctx).getString("rel", null)?.let { runCatching { Release.from(JSONObject(it)) }.getOrNull() }

    private fun readyFile(ctx: Context, rel: Release?): File? {
        rel ?: return null
        val p = sp(ctx)
        if (p.getString("readyVer", null) != rel.version) return null
        val f = File(p.getString("readyPath", "") ?: "")
        return f.takeIf { it.isFile && it.length() > 0 }
    }

    private fun base(ctx: Context): JSONObject {
        val p = sp(ctx)
        val rel = cached(ctx)?.takeIf { hasUpdate(ctx, it) }
        val o = JSONObject()
            .put("current", BuildConfig.VERSION_NAME)
            .put("configured", configured())
            .put("repo", repo)
            .put("page", rel?.page?.ifBlank { null } ?: releasesPage())
            .put("mode", mode(ctx))
            .put("lastCheck", p.getLong("last", 0))
            .put("skipped", p.getString("skip", "") ?: "")
        when {
            !configured() -> o.put("state", "notconfigured")
            rel != null -> {
                o.put("latest", rel.version).put("title", rel.title).put("notes", rel.notes.take(4000)).put("size", rel.size)
                o.put("state", if (readyFile(ctx, rel) != null) "ready" else "available")
            }
            p.getLong("last", 0) > 0 -> o.put("state", "uptodate")
            else -> o.put("state", "idle")
        }
        return o
    }

    fun stateJson(ctx: Context): String {
        val b = base(ctx)
        live?.let { l -> l.keys().forEach { k -> b.put(k, l.get(k)) } }
        return b.toString()
    }

    private fun emit(ctx: Context, extra: JSONObject?) {
        live = extra
        runCatching { listener?.invoke(stateJson(ctx)) }
    }

    private fun emit(ctx: Context, state: String, msg: String = "") =
        emit(ctx, JSONObject().put("state", state).apply { if (msg.isNotEmpty()) put("msg", msg) })

    // ---------------- checking ----------------

    /** Cheap: call it as often as you like (keyboard shown, app opened). Network only when the gap has passed. */
    fun maybeCheck(ctx: Context, background: Boolean) {
        val app = ctx.applicationContext
        if (!configured() || mode(app) == "off") return
        if (!due(app, background)) return
        Engine.io { check(app, force = false, background = background) }
    }

    /** A check is due when the normal gap has passed and we're not backing off after a failure. */
    private fun due(ctx: Context, background: Boolean): Boolean {
        val p = sp(ctx)
        val now = System.currentTimeMillis()
        val gap = if (background) BG_GAP_MS else APP_GAP_MS
        return now - p.getLong("last", 0) >= gap && now >= p.getLong("retryAt", 0)
    }

    /** User pressed "Check for updates". */
    fun checkNow(ctx: Context) { val app = ctx.applicationContext; Engine.io { check(app, force = true, background = false) } }

    private fun check(ctx: Context, force: Boolean, background: Boolean) {
        if (force) { live = null; sp(ctx).edit().remove("etag").apply() }
        if (!configured()) { emit(ctx, null); return }
        if (!busy.compareAndSet(false, true)) return
        val p = sp(ctx)
        val now = System.currentTimeMillis()
        try {
            if (!force && !due(ctx, background)) return
            if (force) emit(ctx, "checking")
            val rel = fetchLatest(ctx)
            p.edit().putLong("last", now).remove("retryAt").putString("rel", rel.toJson().toString()).apply()
            if (!hasUpdate(ctx, rel)) {
                cleanup(ctx, keep = null)
                p.edit().remove("skip").remove("notified").apply()
                Notif.cancelUpdate(ctx)
                emit(ctx, if (force) JSONObject().put("state", "uptodate").put("msg", "✓ You're on the latest version (v${BuildConfig.VERSION_NAME})") else null)
                return
            }
            emit(ctx, if (force) JSONObject().put("state", "available").put("msg", "Update found: v${rel.version}") else null)
            // Background: download on Wi-Fi if allowed, then tell the user once per version.
            val m = mode(ctx)
            if (!MainActivity.visible && m != "off" && p.getString("skip", "") != rel.version) {
                var ready = readyFile(ctx, rel) != null
                if (!ready && m == "auto" && !metered(ctx)) {
                    ready = runCatching { download(ctx, rel, quiet = true) }.isSuccess
                }
                val key = rel.version + if (ready) "+ready" else ""
                if (p.getString("notified", "") != key) {
                    Notif.update(ctx, rel.version, rel.title, ready)
                    p.edit().putString("notified", key).apply()
                }
            }
        } catch (e: UpdateError) {
            if (e.noRelease) p.edit().putLong("last", now).remove("rel").apply()
            else p.edit().putLong("retryAt", now + RETRY_GAP_MS).apply()
            emit(ctx, if (force) JSONObject().put("state", "error").put("msg", e.message ?: "Update check failed") else null)
        } catch (e: Exception) {
            p.edit().putLong("retryAt", now + RETRY_GAP_MS).apply()
            emit(ctx, if (force) JSONObject().put("state", "error").put("msg", netMsg(e)) else null)
        } finally {
            busy.set(false)
        }
    }

    private fun netMsg(e: Exception): String =
        if (e is IOException) "Couldn't reach GitHub. Check your internet and try again." else (e.message ?: "Something went wrong")

    private fun metered(ctx: Context): Boolean =
        runCatching { ctx.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered }.getOrDefault(true)

    private fun req(url: String) = Request.Builder().url(url)
        .header("User-Agent", "Wispr-by-Deepu-Gupta/${BuildConfig.VERSION_NAME} (Android ${Build.VERSION.RELEASE})")

    private fun fetchLatest(ctx: Context): Release {
        val r = fetchRelease(ctx)
        if (r.sha256.length == 64) return r
        return r.copy(sha256 = runCatching { fetchSum(r) }.getOrDefault(""))
    }

    private fun fetchRelease(ctx: Context): Release {
        return try {
            fromApi(ctx)
        } catch (e: UpdateError) {
            throw e
        } catch (e: Exception) {
            fromWeb() // API rate limit (60/h per IP, shared on mobile networks) or API hiccup
        }
    }

    private fun fromApi(ctx: Context): Release {
        val p = sp(ctx)
        val cachedJson = p.getString("rel", null)
        val b = req("$API$repo/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
        val etag = p.getString("etag", null)
        if (etag != null && cachedJson != null) b.header("If-None-Match", etag)
        http.newCall(b.build()).execute().use { r ->
            if (r.code == 304 && cachedJson != null) return Release.from(JSONObject(cachedJson))
            if (r.code == 404) throw UpdateError("No release published yet on github.com/$repo", noRelease = true)
            if (!r.isSuccessful) throw IOException("GitHub API ${r.code}")
            val j = JSONObject(r.body.string())
            r.header("ETag")?.let { p.edit().putString("etag", it).apply() }
            return parse(j)
        }
    }

    private fun parse(j: JSONObject): Release {
        val tag = j.optString("tag_name")
        if (tag.isBlank()) throw IOException("Release has no tag")
        val assets = j.optJSONArray("assets")
        var apkUrl = ""; var apkName = ""; var size = 0L; var sha = ""; var sums = ""
        var bestScore = -1
        if (assets != null) for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val name = a.optString("name")
            val url = a.optString("browser_download_url")
            if (name.equals("SHA256SUMS.txt", true)) sums = url
            if (!name.endsWith(".apk", true)) continue
            val score = (if (name.contains("wispr", true)) 2 else 0) + (if (name.contains("release", true) || !name.contains("debug", true)) 1 else 0)
            if (score > bestScore) {
                bestScore = score; apkUrl = url; apkName = name; size = a.optLong("size")
                sha = a.optString("digest").removePrefix("sha256:").takeIf { it.length == 64 } ?: ""
            }
        }
        if (apkUrl.isBlank()) throw UpdateError("The latest release ($tag) has no APK attached yet. Try again in a few minutes.")
        return Release(
            version = tag.removePrefix("v").removePrefix("V"), tag = tag,
            title = j.optString("name").ifBlank { tag }, notes = j.optString("body").take(8000),
            page = j.optString("html_url").ifBlank { "$WEB$repo/releases/tag/$tag" },
            apkUrl = apkUrl, apkName = apkName, size = size, sha256 = sha, sumsUrl = sums
        )
    }

    /** No API needed: github.com/<repo>/releases/latest redirects to .../releases/tag/<tag>. */
    private fun fromWeb(): Release {
        noRedirect.newCall(req("$WEB$repo/releases/latest").head().build()).execute().use { r ->
            val loc = r.header("Location") ?: throw IOException("GitHub ${r.code}")
            val tag = Uri.decode(loc.substringAfter("/releases/tag/", "").substringBefore('?'))
            if (tag.isBlank()) throw UpdateError("No release published yet on github.com/$repo", noRelease = true)
            val name = "Wispr-by-Deepu-Gupta-$tag.apk"
            val dl = "$WEB$repo/releases/download/$tag/"
            return Release(tag.removePrefix("v"), tag, "Wispr $tag", "", "$WEB$repo/releases/tag/$tag", dl + name, name, 0, "", dl + "SHA256SUMS.txt")
        }
    }

    // ---------------- downloading ----------------

    fun cancelDownload() { cancel = true }

    fun skip(ctx: Context, version: String) {
        sp(ctx).edit().putString("skip", version).apply()
        emit(ctx, null)
    }

    /** Update now: download if needed, verify, then install. Call from the app screen. */
    fun start(ctx: Context) {
        val app = ctx.applicationContext
        Engine.io {
            if (!busy.compareAndSet(false, true)) return@io
            try {
                val rel = cached(app)?.takeIf { hasUpdate(app, it) } ?: fetchLatest(app).also {
                    sp(app).edit().putString("rel", it.toJson().toString()).putLong("last", System.currentTimeMillis()).apply()
                }
                if (!hasUpdate(app, rel)) { emit(app, JSONObject().put("state", "uptodate").put("msg", "✓ You're on the latest version (v${BuildConfig.VERSION_NAME})")); return@io }
                val f = readyFile(app, rel)?.takeIf { verifyFile(app, it) } ?: download(app, rel, quiet = false)
                install(app, f, rel.version)
            } catch (e: UpdateError) {
                emit(app, JSONObject().put("state", "error").put("msg", e.message ?: "Update failed").put("keyMismatch", e.keyMismatch))
            } catch (e: Exception) {
                emit(app, JSONObject().put("state", "error").put("msg", if (cancel) "Download cancelled." else netMsg(e)))
            } finally {
                cancel = false
                busy.set(false)
            }
        }
    }

    private fun download(ctx: Context, rel: Release, quiet: Boolean): File {
        val host = Uri.parse(rel.apkUrl).host ?: ""
        if (!(host == "github.com" || host.endsWith(".github.com") || host.endsWith(".githubusercontent.com"))) {
            throw UpdateError("Blocked a download from an unknown site ($host).")
        }
        val dir = File(ctx.filesDir, "updates").apply { mkdirs() }
        val out = File(dir, "wispr-" + rel.version.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".apk")
        val part = File(dir, out.name + ".part")
        val expected = rel.sha256.ifBlank { runCatching { fetchSum(rel) }.getOrDefault("") }.lowercase()
        cancel = false
        if (!quiet) emit(ctx, JSONObject().put("state", "downloading").put("progress", 0))
        val md = MessageDigest.getInstance("SHA-256")
        http.newCall(req(rel.apkUrl).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Download failed (${r.code})")
            val body = r.body
            val total = body.contentLength().takeIf { it > 0 } ?: rel.size
            if (total > MAX_APK) throw UpdateError("The update file is unexpectedly large.")
            var done = 0L; var lastPct = -1; var lastAt = 0L
            body.byteStream().use { input ->
                part.outputStream().use { os ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (cancel) throw IOException("cancelled")
                        val n = input.read(buf)
                        if (n < 0) break
                        os.write(buf, 0, n); md.update(buf, 0, n); done += n
                        if (done > MAX_APK) throw UpdateError("The update file is unexpectedly large.")
                        if (!quiet && total > 0) {
                            val pct = (done * 100 / total).toInt().coerceIn(0, 100)
                            val t = System.currentTimeMillis()
                            if (pct != lastPct && t - lastAt > 120) {
                                lastPct = pct; lastAt = t
                                emit(ctx, JSONObject().put("state", "downloading").put("progress", pct))
                            }
                        }
                    }
                }
            }
        }
        val got = hex(md.digest())
        if (expected.length == 64 && got != expected) {
            part.delete()
            throw UpdateError("The download got corrupted (checksum didn't match). Please try again.")
        }
        if (!quiet) emit(ctx, JSONObject().put("state", "verifying"))
        try { verifyApk(ctx, part) } catch (e: Exception) { part.delete(); throw e }
        out.delete()
        if (!part.renameTo(out)) { part.copyTo(out, overwrite = true); part.delete() }
        cleanup(ctx, keep = out)
        sp(ctx).edit().putString("readyVer", rel.version).putString("readyPath", out.absolutePath).putString("readySha", got).apply()
        return out
    }

    private fun fetchSum(rel: Release): String {
        if (rel.sumsUrl.isBlank()) return ""
        http.newCall(req(rel.sumsUrl).build()).execute().use { r ->
            if (!r.isSuccessful) return ""
            for (line in r.body.string().lines()) {
                val parts = line.trim().split(Regex("\\s+"), limit = 2)
                if (parts.size == 2 && parts[1].trimStart('*') == rel.apkName && parts[0].length == 64) return parts[0].lowercase()
            }
        }
        return ""
    }

    private fun verifyFile(ctx: Context, f: File): Boolean {
        val want = sp(ctx).getString("readySha", "") ?: ""
        val ok = runCatching { sha256(f) == want }.getOrDefault(false)
        if (!ok) f.delete()
        return ok
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i -> val b = ByteArray(64 * 1024); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return hex(md.digest())
    }

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    @Suppress("DEPRECATION")
    private fun code(p: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else p.versionCode.toLong()

    @Suppress("DEPRECATION")
    private fun certs(p: PackageInfo?): Set<String> {
        if (p == null) return emptySet()
        val sigs = if (Build.VERSION.SDK_INT >= 28) {
            val si = p.signingInfo ?: return emptySet()
            if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
        } else p.signatures
        return sigs?.map { hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }?.toSet() ?: emptySet()
    }

    /** Same app + same signing key + not older. The key check is what really keeps a fake APK out. */
    @Suppress("DEPRECATION")
    private fun verifyApk(ctx: Context, f: File) {
        val pm = ctx.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val arc = pm.getPackageArchiveInfo(f.absolutePath, flags) ?: throw UpdateError("The downloaded file isn't a valid APK.")
        if (arc.packageName != ctx.packageName) throw UpdateError("That APK is a different app (${arc.packageName}).")
        val mine = pm.getPackageInfo(ctx.packageName, flags)
        if (code(arc) < code(mine)) throw UpdateError("That APK is older than the app you have.")
        val a = certs(arc); val m = certs(mine)
        if (a.isNotEmpty() && m.isNotEmpty() && a.intersect(m).isEmpty()) throw UpdateError(KEY_MISMATCH, keyMismatch = true)
    }

    const val KEY_MISMATCH = "This update is signed with a different key than the Wispr on your phone, so Android can't install it on top. " +
        "Uninstall Wispr once, then install the new APK from GitHub. Future updates will then work in-app."

    private fun cleanup(ctx: Context, keep: File?) {
        File(ctx.filesDir, "updates").listFiles()?.forEach { if (it != keep) it.delete() }
        if (keep == null) sp(ctx).edit().remove("readyVer").remove("readyPath").remove("readySha").apply()
    }

    // ---------------- installing ----------------

    fun canInstall(ctx: Context): Boolean = ctx.packageManager.canRequestPackageInstalls()

    private fun install(ctx: Context, f: File, version: String) {
        if (!canInstall(ctx)) {
            pendingInstall = true
            emit(ctx, JSONObject().put("state", "needperm").put("msg", "Allow \"Install unknown apps\" for Wispr, then come back."))
            return
        }
        emit(ctx, JSONObject().put("state", "installing"))
        val pi = ctx.packageManager.packageInstaller
        // Drop half-finished sessions from an earlier try.
        runCatching { pi.mySessions.forEach { s -> if (s.appPackageName == ctx.packageName) pi.abandonSession(s.sessionId) } }
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            setSize(f.length())
            setInstallReason(PackageManager.INSTALL_REASON_USER)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            if (Build.VERSION.SDK_INT >= 33) setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
        }
        val id = pi.createSession(params)
        try {
            pi.openSession(id).use { s ->
                s.openWrite("base.apk", 0, f.length()).use { os ->
                    f.inputStream().use { it.copyTo(os, 64 * 1024) }
                    s.fsync(os)
                }
                val i = Intent(ctx, UpdateReceiver::class.java).setAction(ACTION_STATUS).putExtra("ver", version)
                // Must be mutable: Android fills in the install status. Explicit intent, not exported: safe.
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                s.commit(PendingIntent.getBroadcast(ctx, id, i, flags).intentSender)
            }
        } catch (e: Exception) {
            runCatching { pi.abandonSession(id) }
            throw UpdateError("Couldn't start the install: ${e.message ?: "unknown error"}")
        }
    }

    /** Called by UpdateReceiver with Android's install result. */
    fun onInstallResult(ctx: Context, status: Int, message: String?) {
        val msg = when (status) {
            PackageInstaller.STATUS_FAILURE_ABORTED -> "Update cancelled."
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> KEY_MISMATCH
            PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough free space for the update."
            PackageInstaller.STATUS_FAILURE_BLOCKED -> "Android blocked the install (Play Protect or a device policy). Use the GitHub page instead."
            PackageInstaller.STATUS_FAILURE_INVALID -> "The update file was invalid. Try again."
            else -> "Install failed" + (message?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ".")
        }
        val mismatch = status == PackageInstaller.STATUS_FAILURE_CONFLICT || status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE
        emit(ctx, JSONObject().put("state", if (status == PackageInstaller.STATUS_FAILURE_ABORTED) "ready" else "error").put("msg", msg).put("keyMismatch", mismatch))
        if (!MainActivity.visible && status != PackageInstaller.STATUS_FAILURE_ABORTED) Notif.updateFailed(ctx, msg)
    }

    /** The new version is running (MY_PACKAGE_REPLACED). */
    fun onUpdated(ctx: Context) {
        val p = sp(ctx)
        val was = p.getString("readyVer", null)
        cleanup(ctx, keep = null)
        pendingInstall = false
        live = null
        p.edit().remove("notified").apply()
        if (was != null && compare(was, BuildConfig.VERSION_NAME) <= 0) Notif.updated(ctx, BuildConfig.VERSION_NAME)
    }
}
