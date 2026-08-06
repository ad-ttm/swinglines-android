package com.testtube.swinglines

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Minimal crash reporting, because SeePath ships to one coach with no Play
 * Console behind it and five builds have now been diagnosed by guesswork.
 *
 * Two mechanisms, because they catch different deaths:
 *
 *  1. An uncaught exception handler. Catches ordinary Kotlin and Java crashes
 *     and keeps the full stack trace.
 *  2. A marker file written while a risky screen is open and deleted when it
 *     closes cleanly. If the app starts and finds a marker but NO crash file,
 *     the process died without throwing anything catchable, which means a native
 *     crash in the codec or the low memory killer. That distinction is the whole
 *     question right now and nothing else tells us the answer.
 *
 * Both surface on the next launch as a share sheet, as plain text rather than a
 * file attachment, so Rich can send it straight into WhatsApp without touching
 * a file browser.
 */
object CrashReporter {

    private const val CRASH_FILE = "seepath-crash.txt"
    private const val MARKER_FILE = "seepath-inprogress.txt"
    private const val MAX_CRUMBS = 40

    private val crumbs = ArrayDeque<String>()
    private var startedAt = 0L
    /** device and version line, built once at startup so the crash path, which
     *  may be running out of memory, does not have to allocate to produce it */
    private var staticHeader = ""
    private var markedScreen: String? = null

    /** Short note of what the coach was doing, kept in memory and dumped on crash. */
    @Synchronized
    fun crumb(note: String) {
        val t = (android.os.SystemClock.elapsedRealtime() - startedAt) / 1000
        crumbs.addLast("[${t}s] $note")
        while (crumbs.size > MAX_CRUMBS) crumbs.removeFirst()
    }

    fun install(ctx: Context) {
        startedAt = android.os.SystemClock.elapsedRealtime()
        val app = ctx.applicationContext
        staticHeader = try {
            val version = app.packageManager.getPackageInfo(app.packageName, 0).versionName
            "app v$version\n${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n"
        } catch (_: Throwable) {
            "app version unknown\n"
        }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // Written in stages, cheapest first. If this is an OutOfMemoryError
            // then building a big report is exactly what will fail, so get the
            // essentials on disk before attempting the stack trace.
            val f = File(app.filesDir, CRASH_FILE)
            try {
                f.writeText(
                    "SeePath CRASH report\n" + staticHeader +
                        "Thread: ${thread.name}\n" +
                        "Error: ${error.javaClass.name}: ${error.message}\n\n"
                )
            } catch (_: Throwable) {
            }
            try {
                f.appendText(breadcrumbs())
            } catch (_: Throwable) {
            }
            try {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                f.appendText("\n" + sw.toString())
            } catch (_: Throwable) {
            }
            // let the system do what it would have done, so the process still dies
            previous?.uncaughtException(thread, error)
        }
    }

    /** Call when entering a screen we suspect. Context is included in the report. */
    fun mark(ctx: Context, what: String) {
        markedScreen = what
        crumb("entered $what")
        remark(ctx)
    }

    /**
     * Rewrite the marker with the latest breadcrumbs. Call at each meaningful
     * step while a marked screen is open, so the file on disk always reflects
     * how far the coach actually got before the process died.
     */
    fun remark(ctx: Context) {
        val what = markedScreen ?: return
        try {
            File(ctx.filesDir, MARKER_FILE).writeText("$what\n" + header(ctx, "MARK") + breadcrumbs())
        } catch (_: Throwable) {
        }
    }

    /**
     * Call ONLY when that screen closes cleanly. Deliberately not called from
     * onPause: the camera roll picker pauses the activity, and clearing there
     * wiped the marker during the very flow being investigated.
     */
    fun clearMark(ctx: Context) {
        markedScreen = null
        try {
            File(ctx.filesDir, MARKER_FILE).delete()
        } catch (_: Throwable) {
        }
    }

    /**
     * Anything waiting to be sent, or null. A crash file wins; otherwise a
     * leftover marker means the process was killed without throwing.
     */
    fun pendingReport(ctx: Context): String? {
        try {
            val crash = File(ctx.filesDir, CRASH_FILE)
            if (crash.exists()) return crash.readText()
            val marker = File(ctx.filesDir, MARKER_FILE)
            if (marker.exists()) {
                val was = marker.readText()
                return buildString {
                    append(header(ctx, "HARD KILL"))
                    append("The app died with NO catchable error while this screen was open.\n")
                    append("That means a native crash (video decoder) or the system killing\n")
                    append("the app for memory, not a fault in our own Kotlin code.\n\n")
                    append("State when it died:\n")
                    append(was)
                }
            }
        } catch (_: Throwable) {
        }
        return null
    }

    /**
     * A report when nothing has crashed: still worth sending, because it says
     * which build is actually on the phone, what the device is, how much memory
     * it has, and what the coach was doing.
     */
    fun snapshot(ctx: Context): String {
        return buildString {
            append(header(ctx, "STATUS"))
            append("No crash is stored on this phone right now.\n")
            append("If Compare died but this says no crash, the app was killed\n")
            append("outright rather than throwing an error we can catch.\n\n")
            append(breadcrumbs())
        }
    }

    fun clear(ctx: Context) {
        try {
            File(ctx.filesDir, CRASH_FILE).delete()
            File(ctx.filesDir, MARKER_FILE).delete()
        } catch (_: Throwable) {
        }
    }

    private fun header(ctx: Context, kind: String): String {
        val rt = Runtime.getRuntime()
        val usedMb = (rt.totalMemory() - rt.freeMemory()) / 1048576
        val maxMb = rt.maxMemory() / 1048576
        val sys = try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            "system free ${mi.availMem / 1048576}MB of ${mi.totalMem / 1048576}MB, low=${mi.lowMemory}"
        } catch (_: Throwable) {
            "system memory unknown"
        }
        return "SeePath $kind report\n" + staticHeader +
            "app heap ${usedMb}MB used of ${maxMb}MB, $sys\n\n"
    }

    @Synchronized
    private fun breadcrumbs(): String {
        if (crumbs.isEmpty()) return "What was happening: nothing recorded\n"
        return "What was happening:\n" + crumbs.joinToString("\n") { "  $it" } + "\n"
    }
}
