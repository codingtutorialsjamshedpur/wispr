#!/usr/bin/env python3
# Wispr v4.1.1 fix: real update check + "Check now" refresh + resizable / movable mini window.
# Run from the repo root (the folder with settings.gradle.kts):  python3 wispr-fix/apply_fix.py
import os, re, sys, shutil, subprocess

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.getcwd()
PKG = "app/src/main/java/com/deepugupta/wispr"
WEB = "app/src/main/assets/web"
MARK = "WISPR-FIX-411"
NEW_VERSION = "4.1.1"

def die(m): print("\n[X] " + m); sys.exit(1)
def ok(m): print("[OK] " + m)

if not os.path.isfile(os.path.join(ROOT, "settings.gradle.kts")):
    die("Run this from the repo root (the folder that has settings.gradle.kts).")

def rd(p): return open(os.path.join(ROOT, p), encoding="utf-8").read()
def wr(p, s): open(os.path.join(ROOT, p), "w", encoding="utf-8", newline="\n").write(s)

def flex(anchor):
    """Regex for an anchor that ignores indentation / whitespace differences."""
    a = anchor.strip()
    parts = re.split(r"\s+", a)
    rx = r"\s+".join(re.escape(x) for x in parts)
    if re.match(r"\w", a): rx = r"(?<![\w.])" + rx
    if re.search(r"\w$", a): rx = rx + r"(?!\w)"
    return rx

def sub1(src, anchor, repl, name, count=1):
    rx = re.compile(flex(anchor), re.S)
    n = len(rx.findall(src))
    if n != count: die(f"{name}: expected {count} match(es), found {n}. The file changed; send it to me and I'll redo the patch.")
    return rx.sub(lambda m: repl, src)

def blob_sha(path):
    try: return subprocess.check_output(["git", "hash-object", path], cwd=ROOT, text=True).strip()
    except Exception: return ""

# ---------------------------------------------------------------- web pages
ORIG = {"index.html": "477a3331d0dfd7f198b662315b734dd248be5764", "panel.html": "57766a34acfb0fc4e3ae6da14cb99f07a9181541"}
for f, sha in ORIG.items():
    dst = os.path.join(WEB, f); src = os.path.join(HERE, "files", f)
    cur = blob_sha(dst); new = blob_sha(src)
    if cur == new: ok(f + " already updated"); continue
    if cur != sha: die(f"{dst} is not the v4.1.0 file I patched (it was edited). Not overwriting it.")
    shutil.copyfile(src, os.path.join(ROOT, dst)); ok(f + " updated")

# ---------------------------------------------------------------- Updater.kt
p = PKG + "/Updater.kt"; s = rd(p)
if MARK in s:
    ok("Updater.kt already patched")
else:
    s = sub1(s, 'private fun sp(ctx: Context) = ctx.getSharedPreferences("wispr_update", Context.MODE_PRIVATE)',
'''// WISPR-FIX-411: remembered so release checks can compare against the installed APK.
    @Volatile private var appCtx: Context? = null
    private fun sp(ctx: Context): android.content.SharedPreferences {
        if (appCtx == null) appCtx = ctx.applicationContext
        return ctx.getSharedPreferences("wispr_update", Context.MODE_PRIVATE)
    }''', "Updater.sp")

    s = sub1(s, 'fun isNewer(v: String): Boolean = v.isNotBlank() && compare(v, BuildConfig.VERSION_NAME) > 0',
'''/** Installed version without the "-build.N" of test builds (Actions "Run workflow"), so 4.1.0-build.17 counts as 4.1.0. */
    fun localVersion(): String = BuildConfig.VERSION_NAME.trim().replace(Regex("-build\\\\.\\\\d+.*$"), "")

    fun isNewer(v: String): Boolean = v.isNotBlank() && compare(v, localVersion()) > 0

    /** SHA-256 of the APK that is installed right now (cached until the app is updated). */
    fun installedSha(ctx: Context): String = try {
        val stamp = ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime
        val p = sp(ctx)
        val cachedSha = p.getString("selfSha", "") ?: ""
        if (p.getLong("selfShaAt", -1L) == stamp && cachedSha.length == 64) cachedSha
        else sha256(File(ctx.applicationInfo.sourceDir)).also { p.edit().putLong("selfShaAt", stamp).putString("selfSha", it).apply() }
    } catch (e: Exception) { "" }

    /** A REAL update: not the very APK we are running (same SHA-256), and a higher version. */
    fun hasUpdate(ctx: Context, rel: Release): Boolean {
        if (rel.version.isBlank()) return false
        val mine = installedSha(ctx)
        if (rel.sha256.length == 64 && mine.length == 64 && rel.sha256.equals(mine, ignoreCase = true)) return false
        return compare(rel.version, localVersion()) > 0
    }

    private fun isNewerRel(rel: Release): Boolean =
        appCtx?.let { hasUpdate(it, rel) } ?: (rel.version.isNotBlank() && compare(rel.version, localVersion()) > 0)''', "Updater.isNewer")

    rx = re.compile(r"isNewer\(\s*(it|rel)\.version\s*\)")
    n = len(rx.findall(s))
    if n < 3: die(f"Updater: expected 3+ release checks, found {n}.")
    s = rx.sub(lambda m: "isNewerRel(" + m.group(1) + ")", s)
    ok(f"Updater: {n} release checks now use the SHA-256 + version check")

    s = sub1(s, 'private fun fetchLatest(ctx: Context): Release {',
'''private fun fetchLatest(ctx: Context): Release = withSha(fetchLatestRaw(ctx))

    /** GitHub sometimes gives no digest (and the web fallback never does): read it from SHA256SUMS.txt. */
    private fun withSha(rel: Release): Release =
        if (rel.sha256.length == 64) rel
        else runCatching { fetchSum(rel) }.getOrDefault("").let { if (it.length == 64) rel.copy(sha256 = it) else rel }

    private fun fetchLatestRaw(ctx: Context): Release {''', "Updater.fetchLatest")

    s = sub1(s, 'fun checkNow(ctx: Context) { val app = ctx.applicationContext; Engine.io { check(app, force = true, background = false) } }',
'''fun checkNow(ctx: Context) {
        val app = ctx.applicationContext
        // Fresh question to GitHub: no ETag shortcut, no old error/backoff, no stale "live" state.
        sp(app).edit().remove("etag").remove("retryAt").apply()
        live = null
        Engine.io { check(app, force = true, background = false) }
    }''', "Updater.checkNow")

    s = sub1(s, '''cleanup(ctx, keep = null)
                emit(ctx, if (force) JSONObject().put("state", "uptodate").put("msg", "You're on the latest version.") else null)''',
'''cleanup(ctx, keep = null)
                p.edit().remove("skip").remove("notified").apply()
                Notif.cancelUpdate(ctx)
                emit(ctx, if (force) JSONObject().put("state", "uptodate").put("msg", "\\u2713 You're on the latest version (v${localVersion()})") else null)''', "Updater.uptodate")

    s = sub1(s, '''emit(ctx, null)
            // Background:''',
'''emit(ctx, if (force) JSONObject().put("msg", "Update found: v${rel.version}") else null)
            // Background:''', "Updater.found")
    wr(p, s); ok("Updater.kt patched")

# ---------------------------------------------------------------- Panel.kt
p = PKG + "/Panel.kt"; s = rd(p)
if MARK in s:
    ok("Panel.kt already patched")
else:
    s = sub1(s, "import android.view.WindowManager",
"""import android.view.WindowManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout""", "Panel.imports")

    s = sub1(s, "private var shown = false",
"""private var shown = false
    // WISPR-FIX-411: the window is a frame = move bar on top + page + resize corner.
    private var root: FrameLayout? = null
    private var tX = 0f; private var tY = 0f; private var sX = 0; private var sY = 0; private var sW = 0; private var sH = 0
    private var lastTap = 0L""", "Panel.fields")

    s = sub1(s, "web = w\n", """web = w
        val frame = FrameLayout(svc)
        frame.addView(w, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT).apply { topMargin = dp(GRIP_DP) })
        frame.addView(PanelGrip(svc, false).apply { setOnTouchListener(moveTouch) },
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(GRIP_DP), Gravity.TOP))
        frame.addView(PanelGrip(svc, true).apply { setOnTouchListener(sizeTouch) },
            FrameLayout.LayoutParams(dp(34f), dp(34f), Gravity.BOTTOM or Gravity.END))
        root = frame
""", "Panel.frame")

    s = sub1(s, "lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL", "lp.gravity = Gravity.TOP or Gravity.START", "Panel.gravity")
    s = sub1(s, "wm.addView(w, lp)", "wm.addView(frame, lp)", "Panel.addView")
    s = sub1(s, """} catch (e: Exception) {
            web = null""", """} catch (e: Exception) {
            web = null
            root = null""", "Panel.addFail")

    rx = re.compile(r"private fun fit\(\) \{.*?\n\s*\}\s*\n(?=\s*private fun relayout)", re.S)
    if len(rx.findall(s)) != 1: die("Panel.fit not found")
    s = rx.sub(lambda m: '''private fun geom() = svc.getSharedPreferences("panel_geom", Context.MODE_PRIVATE)

    /** Your saved size and place (small by default). With the keyboard open it moves up so the text box stays visible. */
    private fun fit() {
        val sw = svc.screenW()
        val sh = svc.screenH()
        val g = geom()
        val top = dp(28f)
        val minW = minOf(dp(MIN_W), sw)
        val minH = dp(MIN_H)
        val w = g.getInt("w", 0).let { if (it <= 0) minOf(sw - dp(24f), dp(DEF_W)) else it }.coerceIn(minW, sw)
        var hh = g.getInt("h", 0).let { if (it <= 0) minOf((sh * 0.55f).toInt(), dp(DEF_H)) else it }
            .coerceIn(minH, maxOf(minH, sh - top - dp(8f)))
        val x = g.getInt("x", Int.MIN_VALUE).let { if (it == Int.MIN_VALUE) (sw - w) / 2 else it }.coerceIn(0, maxOf(0, sw - w))
        var y = g.getInt("y", Int.MIN_VALUE).let { if (it == Int.MIN_VALUE) top else it }.coerceIn(0, maxOf(0, sh - hh - dp(8f)))
        val ime = svc.imeTopNow()
        if (ime > dp(200f)) {
            val limit = ime - dp(6f)
            if (y + hh > limit) {
                y = maxOf(top, limit - hh)
                if (y + hh > limit) hh = maxOf(dp(200f), limit - y)
            }
        }
        lp.width = w; lp.height = hh; lp.x = x; lp.y = y
    }

    private fun saveGeom() {
        geom().edit().putInt("w", lp.width).putInt("h", lp.height).putInt("x", lp.x).putInt("y", lp.y).apply()
    }

    private fun resetGeom() {
        geom().edit().clear().apply()
        fit(); relayout()
        svc.toast("Wispr window reset to default size")
    }

    /** Header size button: small / medium / large (cycle). */
    private fun preset(name: String): String {
        val sw = svc.screenW(); val sh = svc.screenH()
        val order = listOf("Small", "Medium", "Large")
        val cur = geom().getString("preset", "Medium") ?: "Medium"
        val pick = if (name == "cycle") order[(order.indexOf(cur) + 1) % order.size] else order.firstOrNull { it.equals(name, true) } ?: "Medium"
        val (w, hh) = when (pick) {
            "Small" -> minOf(sw - dp(24f), dp(MIN_W + 20f)) to minOf((sh * 0.42f).toInt(), dp(320f))
            "Large" -> (sw - dp(12f)) to minOf((sh * 0.78f).toInt(), dp(700f))
            else -> minOf(sw - dp(24f), dp(DEF_W)) to minOf((sh * 0.55f).toInt(), dp(DEF_H))
        }
        lp.width = w; lp.height = hh
        lp.x = ((sw - w) / 2).coerceAtLeast(0)
        lp.y = lp.y.coerceIn(0, maxOf(0, sh - hh - dp(8f)))
        saveGeom(); geom().edit().putString("preset", pick).apply()
        relayout()
        return pick
    }

    /** Drag the bar on top to move the window. Double-tap it to reset size and place. */
    @SuppressLint("ClickableViewAccessibility")
    private val moveTouch = View.OnTouchListener { _, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { tX = e.rawX; tY = e.rawY; sX = lp.x; sY = lp.y }
            MotionEvent.ACTION_MOVE -> {
                lp.x = (sX + (e.rawX - tX).toInt()).coerceIn(0, maxOf(0, svc.screenW() - lp.width))
                lp.y = (sY + (e.rawY - tY).toInt()).coerceIn(0, maxOf(0, svc.screenH() - dp(80f)))
                relayout()
            }
            MotionEvent.ACTION_UP -> {
                if (Math.hypot((e.rawX - tX).toDouble(), (e.rawY - tY).toDouble()) > dp(6f)) saveGeom()
                else {
                    val now = SystemClock.uptimeMillis()
                    if (now - lastTap < 350L) { lastTap = 0L; resetGeom() } else lastTap = now
                }
            }
        }
        true
    }

    /** Drag the orange corner to make the window as small or big as you like. */
    @SuppressLint("ClickableViewAccessibility")
    private val sizeTouch = View.OnTouchListener { _, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { tX = e.rawX; tY = e.rawY; sW = lp.width; sH = lp.height }
            MotionEvent.ACTION_MOVE -> {
                val sw = svc.screenW(); val sh = svc.screenH()
                lp.width = (sW + (e.rawX - tX).toInt()).coerceIn(minOf(dp(MIN_W), sw), maxOf(dp(MIN_W), sw - lp.x))
                lp.height = (sH + (e.rawY - tY).toInt()).coerceIn(dp(MIN_H), maxOf(dp(MIN_H), sh - lp.y))
                relayout()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> saveGeom()
        }
        true
    }

''', s)

    s = sub1(s, "wm.updateViewLayout(w, lp)", "wm.updateViewLayout(root ?: w, lp)", "Panel.relayout", count=1)
    s = sub1(s, "if (shown) runCatching { wm.removeView(w) }", """if (shown) runCatching { wm.removeView(root ?: w) }
        runCatching { root?.removeAllViews() }
        root = null""", "Panel.close")

    s = sub1(s, "@JavascriptInterface fun sdk(): Int = Build.VERSION.SDK_INT",
"""@JavascriptInterface fun sdk(): Int = Build.VERSION.SDK_INT

        /** "small" / "medium" / "large" / "cycle". Returns the size it picked. */
        @JavascriptInterface
        fun sizePreset(name: String): String {
            val r = java.util.concurrent.atomic.AtomicReference("Medium")
            val done = java.util.concurrent.CountDownLatch(1)
            h.post { runCatching { r.set(preset(name)) }; done.countDown() }
            runCatching { done.await(800, java.util.concurrent.TimeUnit.MILLISECONDS) }
            return r.get()
        }""", "Panel.bridge")

    s = sub1(s, "private const val HOST = \"appassets.androidplatform.net\"",
"""private const val HOST = "appassets.androidplatform.net"
        private const val GRIP_DP = 22f
        private const val DEF_W = 340f
        private const val DEF_H = 430f
        private const val MIN_W = 250f
        private const val MIN_H = 220f""", "Panel.consts")

    s = s.rstrip() + '''

/** WISPR-FIX-411: orange move bar (top) and resize corner (bottom right) of the mini window. */
private class PanelGrip(ctx: Context, private val corner: Boolean) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF26B0F.toInt()
        strokeCap = Paint.Cap.ROUND
        strokeWidth = if (corner) 2.6f * d else 5f * d
    }

    init { contentDescription = if (corner) "Resize the Wispr window" else "Move the Wispr window" }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val w = width.toFloat(); val h = height.toFloat()
        if (corner) {
            val m = 8f * d
            c.drawLine(w - m, h - 18f * d, w - 18f * d, h - m, paint)
            c.drawLine(w - m, h - 12f * d, w - 12f * d, h - m, paint)
        } else {
            c.drawLine(w / 2f - 22f * d, h / 2f, w / 2f + 22f * d, h / 2f, paint)
        }
    }
}
'''
    wr(p, s); ok("Panel.kt patched (resizable + movable, smaller by default, stays above the keyboard)")

# ---------------------------------------------------------------- version + notes
gp = "gradle.properties"
if os.path.isfile(gp):
    g = rd(gp)
    if re.search(r"^wispr\.version=", g, re.M):
        g = re.sub(r"^wispr\.version=.*$", "wispr.version=" + NEW_VERSION, g, flags=re.M)
    else:
        g = g.rstrip() + "\nwispr.version=" + NEW_VERSION + "\n"
    wr(gp, g); ok("gradle.properties -> wispr.version=" + NEW_VERSION)

rn = "RELEASE_NOTES.md"
notes = open(os.path.join(HERE, "RELEASE_NOTES_4.1.1.md"), encoding="utf-8").read()
cur = rd(rn) if os.path.isfile(rn) else ""
if "4.1.1" not in cur:
    wr(rn, notes.rstrip() + "\n\n" + cur); ok("RELEASE_NOTES.md updated")

print("\nAll done. Now commit + push (apply.sh does it for you).")
