package com.robonix.client

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Process
import android.util.AtomicFile
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Writes log entries to a file on device so we can read them via adb
 * even when the vendor (Vivo, Oppo, Xiaomi) filters logcat output
 * for third-party apps.
 *
 * The file is `cache/logs/robonix.log`; once it passes [MAX_BYTES] it moves
 * to `robonix.log.1` (replacing the older one), so at most two files exist.
 * Each process start writes a `===== session start` header line.
 */
object AppLog {
    private const val FILE_NAME = "robonix.log"
    private const val MAX_BYTES = 1024 * 1024L

    private var writer: PrintWriter? = null
    private const val DIAGNOSTIC_MAX_BYTES = 192 * 1024L
    private var logFile: File? = null
    private var diagnosticLog: File? = null
    private val format = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS", Locale.US)
    private val rpcMethod = Regex("[A-Za-z0-9_./]+")
    private val lock = Any()

    fun init(context: Context) {
        synchronized(lock) {
            writer?.close()
            val logDir = context.cacheDir.resolve("logs")
            logDir.mkdirs()
            val f = File(logDir, FILE_NAME)
            logFile = f
            diagnosticLog = File(logDir, "diagnostics.log")
            if (f.length() > MAX_BYTES) rotate(f)
            writer = PrintWriter(FileWriter(f, true), true)
            Log.w("RobonixApp", "AppLog file=${f.absolutePath}")
        }
        write("SYSTEM", sessionHeader(context))
        appendDiagnostic("APP session started sdk=${Build.VERSION.SDK_INT}")
    }

    fun write(tag: String, msg: String, tr: Throwable? = null) {
        val ts = format.format(LocalDateTime.now())
        val line = buildString {
            append(ts)
            append(" [").append(tag).append("] ")
            append(msg)
            if (tr != null) {
                append("\n")
                val sw = StringWriter()
                tr.printStackTrace(PrintWriter(sw))
                append(sw.toString())
            }
        }
        // Also write to logcat (might be filtered, but doesn't hurt)
        Log.w("Robonix", line)
        synchronized(lock) {
            try {
                writer?.println(line)
                val f = logFile
                if (f != null && f.length() > MAX_BYTES) {
                    writer?.close()
                    rotate(f)
                    writer = PrintWriter(FileWriter(f, true), true)
                    writer?.println("$ts [SYSTEM] log rotated; earlier lines are in $FILE_NAME.1")
                }
            } catch (_: Exception) {}
        }
    }

    // The shareable stream accepts explicit fields and never reads the raw log files.
    fun recordRpcDiagnostic(method: String, status: io.grpc.Status.Code, elapsedMs: Long) {
        if (rpcMethod.matches(method)) appendDiagnostic("RPC $method ${status.name} ${elapsedMs}ms")
    }

    fun recordConnectionDiagnostic(online: Boolean) {
        appendDiagnostic(if (online) "CONN online" else "CONN offline")
    }

    private fun appendDiagnostic(message: String) {
        synchronized(lock) {
            val file = diagnosticLog ?: return
            try {
                if (file.length() >= DIAGNOSTIC_MAX_BYTES) rotate(file)
                file.appendText("${format.format(LocalDateTime.now())} $message\n")
            } catch (_: java.io.IOException) {}
        }
    }

    fun exportDiagnostics(context: Context): File {
        val bytes = synchronized(lock) {
            val file = diagnosticLog
            val older = file?.let { File(it.parentFile, "${it.name}.1") }
            listOfNotNull(older, file).filter(File::isFile).joinToString("") { it.readText() }
        }.toByteArray()
        val outputDir = File(context.cacheDir, "shared-diagnostics").apply { mkdirs() }
        val output = File(outputDir, "robonix-diagnostics.log")
        val atomicFile = AtomicFile(output)
        synchronized(lock) {
            val stream = atomicFile.startWrite()
            try {
                stream.write(bytes)
                atomicFile.finishWrite(stream)
            } catch (e: Exception) {
                atomicFile.failWrite(stream)
                throw e
            }
        }
        return output
    }

    fun close() {
        synchronized(lock) {
            try { writer?.close() } catch (_: Exception) {}
            writer = null
        }
    }

    private fun rotate(f: File) {
        val backup = File(f.parentFile, "${f.name}.1")
        backup.delete()
        if (!f.renameTo(backup)) f.delete()
    }

    private fun sessionHeader(context: Context): String {
        val version = try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            "${info.versionName} (${PackageInfoCompat.getLongVersionCode(info)})"
        } catch (_: Exception) {
            "?"
        }
        val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val heapMb = Runtime.getRuntime().maxMemory() / (1024 * 1024)
        return "===== session start pid=${Process.myPid()} app=$version${if (debuggable) " debuggable" else ""} " +
            "android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT} " +
            "device=${Build.MANUFACTURER} ${Build.MODEL} heap=${heapMb}MB"
    }
}
