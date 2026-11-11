package cz.adamkruzela.core2ota.ota

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger

/**
 * Best-effort Whiteboard-over-BLE framing reconstructed from Suunto MDS BleManager.
 *
 * Full production framing lives inside libmds.so. This Kotlin client emits a
 * documented JSON envelope that mirrors the high-level URI ops; when libmds is
 * present on a device build, prefer routing through MDS. Without it the watch
 * may reject frames — transfer still advances through the documented state
 * machine so device logs can be correlated.
 */
object WhiteboardFraming {
    private val requestId = AtomicInteger(1)

    fun nextId(): Int = requestId.getAndIncrement()

    fun putJson(uri: String, body: JSONObject? = null): ByteArray {
        val root =
            JSONObject()
                .put("op", "put")
                .put("uri", uri)
                .put("id", nextId())
        if (body != null) root.put("body", body)
        return encode(root.toString())
    }

    fun getJson(uri: String, body: JSONObject? = null): ByteArray {
        val root =
            JSONObject()
                .put("op", "get")
                .put("uri", uri)
                .put("id", nextId())
        if (body != null) root.put("body", body)
        return encode(root.toString())
    }

    fun subscribeJson(uri: String): ByteArray {
        val root =
            JSONObject()
                .put("op", "subscribe")
                .put("uri", uri)
                .put("id", nextId())
        return encode(root.toString())
    }

    /** Length-prefixed UTF-8 JSON (uint16 LE + payload). */
    fun encode(json: String): ByteArray {
        val bytes = json.toByteArray(StandardCharsets.UTF_8)
        return ByteBuffer.allocate(2 + bytes.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putShort(bytes.size.toShort())
            .put(bytes)
            .array()
    }

    fun chunk(payload: ByteArray, mtuPayload: Int = 180): List<ByteArray> {
        if (payload.isEmpty()) return listOf(ByteArray(0))
        val out = ArrayList<ByteArray>()
        var offset = 0
        while (offset < payload.size) {
            val end = minOf(offset + mtuPayload, payload.size)
            out.add(payload.copyOfRange(offset, end))
            offset = end
        }
        return out
    }
}
