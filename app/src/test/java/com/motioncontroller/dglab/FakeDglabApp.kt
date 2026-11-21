package com.motioncontroller.dglab

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 单测用的"DG-LAB APP"替身：连到本机中继，收/发 V4 帧。
 *
 * 只用 Java-WebSocket 的 [WebSocketClient]（与中继同一依赖），不引入 MockWebServer。
 */
class FakeDglabApp(uri: URI) : WebSocketClient(uri) {

    val frames = LinkedBlockingQueue<JsonObject>()
    val errors = LinkedBlockingQueue<Throwable>()

    private val closedLatch = CountDownLatch(1)

    @Volatile
    var closeCode: Int = -1
        private set

    @Volatile
    var closeReason: String = ""
        private set

    override fun onOpen(handshakedata: ServerHandshake) = Unit

    override fun onMessage(message: String) {
        val parsed = runCatching { Json.parseToJsonElement(message) }.getOrNull() ?: return
        (parsed as? JsonObject)?.let { frames.add(it) }
    }

    override fun onClose(code: Int, reason: String, remote: Boolean) {
        closeCode = code
        closeReason = reason.ifNullEmpty()
        closedLatch.countDown()
    }

    override fun onError(ex: Exception) {
        errors.add(ex)
    }

    /** 等到第一个满足 [predicate] 的帧（会丢弃中间不匹配的帧） */
    fun awaitFrame(timeoutMs: Long = TIMEOUT_MS, predicate: (JsonObject) -> Boolean): JsonObject {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) throw AssertionError("等待帧超时（${timeoutMs}ms），已收到帧未匹配")
            val frame = frames.poll(remaining, TimeUnit.MILLISECONDS)
                ?: throw AssertionError("等待帧超时（${timeoutMs}ms），队列为空")
            if (predicate(frame)) return frame
        }
    }

    fun pollFrame(timeoutMs: Long): JsonObject? = frames.poll(timeoutMs, TimeUnit.MILLISECONDS)

    fun drainFrames(): List<JsonObject> {
        val out = ArrayList<JsonObject>()
        while (true) out.add(frames.poll() ?: return out)
    }

    fun awaitClosed(timeoutMs: Long = TIMEOUT_MS): Boolean {
        return closedLatch.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    fun sendAppData(data: JsonObject) {
        send(buildJsonObject {
            put("type", "message")
            put("data", data)
        }.toString())
    }

    fun sendEvent(event: String, payload: JsonObject) {
        val data = buildJsonObject {
            put("t", "ev")
            put("ev", event)
            for ((key, value) in payload) put(key, value)
        }
        sendAppData(data)
    }

    /** 回 `{"t":"resp","reqId":...,"result":{}}` */
    fun replyOk(reqId: String?) = reply(reqId, buildJsonObject {})

    /** 回自定义 `result` 对象（例如 `{"reason":"completed"}`） */
    fun reply(reqId: String?, result: JsonObject) {
        val data = buildJsonObject {
            put("t", "resp")
            put("reqId", reqId ?: "")
            put("result", result)
        }
        sendAppData(data)
    }

    fun replyReason(reqId: String?, reason: String) =
        reply(reqId, buildJsonObject { put("reason", reason) })

    /** 回错误响应 `{"t":"resp","reqId":...,"error":{"code":"..."}}` */
    fun replyError(reqId: String?, code: String) {
        val data = buildJsonObject {
            put("t", "resp")
            put("reqId", reqId ?: "")
            put("error", buildJsonObject { put("code", code) })
        }
        sendAppData(data)
    }

    fun closeQuietly() {
        runCatching { closeBlocking() }
        runCatching { close() }
    }

    private fun String.ifNullEmpty(): String = if (this == "null") "" else this

    companion object {
        const val TIMEOUT_MS = 5_000L
    }
}

// ───────────────────────── 测试侧的 JsonObject 读取小工具 ─────────────────────────

internal fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

/** 中继包了一层的应用层 data */
internal fun JsonObject.messageData(): JsonObject? =
    if (str("type") == "message") obj("data") else null
