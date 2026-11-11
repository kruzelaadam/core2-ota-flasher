package cz.adamkruzela.core2ota.ota

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Full communication transcript for firmware-analysis agents.
 * Captures every MDS Whiteboard call (method, URI, contract, response/error)
 * with timestamps. Also mirrored to logcat tag [Core2OtaComm].
 */
class CommSessionLog(
    context: Context,
    sessionLabel: String = "ota",
) {
    private val tag = "Core2OtaComm"
    private val lines = CopyOnWriteArrayList<String>()
    private val startedAt = System.currentTimeMillis()
    private val stampFmt =
        SimpleDateFormat("HH:mm:ss.SSS", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }
    private val fileStamp =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }.format(Date(startedAt))

    val file: File =
        File(
            context.getExternalFilesDir(null) ?: context.filesDir,
            "comm-$sessionLabel-$fileStamp.txt",
        )

    init {
        file.parentFile?.mkdirs()
        header(
            """
            |# Suunto Core 2 OTA — communication log for firmware agent
            |# Started: ${iso(startedAt)}
            |# File: ${file.absolutePath}
            |#
            |# This log records phone↔MDS Whiteboard calls made by the sideload flasher.
            |# Watch-driven pull OTA (same as the Suunto app):
            |#   SUBSCRIBE MDS/EventListener {"Uri":"<serial>/Update/Transfer/Nordic/Request"}
            |#   PUT …/Update/Transfer/Nordic/Version/Info   ← byte-exact ZIP description
            |#   NOTIFY operation=0 INFO | 1 READ | 2 DFU | 3 PROGRESS | 4 FAIL_REASON
            |#   READ lines: type/offset/size + block CRC (hex / unsigned / signed)
            |#   DFU: mcumgr upload of the type-3 Bluetooth ZIP on a separate GATT link
            |# ---------------------------------------------------------------------------
            """.trimMargin(),
        )
    }

    @Synchronized
    fun header(text: String) {
        appendRaw(text)
    }

    fun note(message: String) {
        append("NOTE", message)
    }

    fun event(kind: String, detail: String) {
        append(kind, detail)
    }

    fun request(method: String, uri: String, body: String) {
        append(
            "REQ",
            "$method $uri\nBODY:\n${body.ifBlank { "(empty)" }}",
        )
    }

    fun response(method: String, uri: String, body: String, elapsedMs: Long) {
        append(
            "RSP",
            "$method $uri (${elapsedMs}ms)\nBODY:\n${body.ifBlank { "(empty)" }}",
        )
    }

    fun error(method: String, uri: String, error: Throwable, elapsedMs: Long) {
        append(
            "ERR",
            "$method $uri (${elapsedMs}ms)\n${error.javaClass.simpleName}: ${error.message}\n${error.stackTraceToString()}",
        )
    }

    fun notify(uri: String, body: String) {
        // Transfer status can be chatty — keep full payload for agent analysis.
        append("NOTIFY", "$uri\nBODY:\n${body.ifBlank { "(empty)" }}")
    }

    fun finish(summary: String): File {
        append(
            "END",
            "$summary\nElapsed=${System.currentTimeMillis() - startedAt}ms\nPath=${file.absolutePath}",
        )
        flush()
        return file
    }

    fun snapshotText(): String = lines.joinToString("\n")

    fun flush() {
        runCatching {
            file.writeText(snapshotText(), Charsets.UTF_8)
        }.onFailure { Log.e(tag, "flush failed", it) }
    }

    private fun append(kind: String, detail: String) {
        val ts = stampFmt.format(Date())
        val block = "[$ts] $kind $detail"
        appendRaw(block)
    }

    private fun appendRaw(block: String) {
        lines += block
        // Logcat: split long lines so nothing is truncated silently for adb capture.
        for (chunk in block.chunked(3500)) {
            Log.i(tag, chunk)
        }
        // Keep file roughly current even if process dies mid-flash.
        if (lines.size % 8 == 0) flush()
    }

    private fun iso(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date(ms))
}
