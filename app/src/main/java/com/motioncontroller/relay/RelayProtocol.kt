package com.motioncontroller.relay

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom

/**
 * V4 中继的帧常量、关闭码与握手参数解析。
 *
 * 依据：AGENTS.md 第 6 节 + dglab-websocket-server/v4-server.ts（只作行为参考，代码独立编写）。
 */
object RelayProtocol {

    const val TYPE_HELLO = "hello"
    const val TYPE_CONTROLLER_ATTACHED = "controller_attached"
    const val TYPE_CONTROLLER_DISCONNECTED = "controller_disconnected"
    const val TYPE_MESSAGE = "message"
    const val TYPE_HEARTBEAT = "heartbeat"
    const val TYPE_PING = "ping"
    const val TYPE_PONG = "pong"
    const val TYPE_ERROR = "error"

    const val ERROR_CONTROLLER_NOT_FOUND = "controller_not_found"

    /** APP 侧关闭码：控制器（本机内嵌）断开 */
    const val CLOSE_CONTROLLER_DISCONNECTED = 4000

    /** 找不到 tid 对应的控制器 */
    const val CLOSE_CONTROLLER_NOT_FOUND = 4001

    /** 控制器空闲超时（本实现只记录，不主动断） */
    const val CLOSE_IDLE_TIMEOUT = 4002

    /** 无 tid 的外来控制端尝试：本实现控制端在进程内，直接拒绝 */
    const val CLOSE_CONTROLLER_REJECTED = 4010

    /** 握手帧无法送达（连接已坏）：直接关闭，不发布给控制器 */
    const val CLOSE_HANDSHAKE_FAILED = 1011

    /** 业务心跳广播间隔 */
    const val DEFAULT_HEARTBEAT_MS = 30_000L

    /** 控制器无 APP 接入的 idle 判定时间 */
    const val DEFAULT_IDLE_TIMEOUT_MS = 5 * 60_000L

    /** 协议日志保留条数 */
    const val MAX_LOG_LINES = 200

    fun hello(clientId: String): JsonObject =
        jsonObject("type" to jsonOf(TYPE_HELLO), "clientId" to jsonOf(clientId))

    fun controllerAttached(targetId: String): JsonObject =
        jsonObject("type" to jsonOf(TYPE_CONTROLLER_ATTACHED), "clientId" to jsonOf(targetId))

    fun controllerDisconnected(targetId: String): JsonObject =
        jsonObject("type" to jsonOf(TYPE_CONTROLLER_DISCONNECTED), "clientId" to jsonOf(targetId))

    fun heartbeat(): JsonObject = jsonObject("type" to jsonOf(TYPE_HEARTBEAT))

    fun pong(ts: Long): JsonObject =
        jsonObject("type" to jsonOf(TYPE_PONG), "ts" to jsonOf(ts))

    fun error(code: String, message: String? = null): JsonObject =
        jsonObject(
            "type" to jsonOf(TYPE_ERROR),
            "code" to jsonOf(code),
            "message" to message?.let { jsonOf(it) },
        )

    /**
     * 服务端 → APP 的应用消息。
     *
     * 按参考实现 `dglab-webSocket-server/v4-server.ts`：转发给 APP 的外层帧**只有**
     * `{"type":"message","data":...}`，不带 `clientId`（`clientId` 只用于控制端下发的目标标识，
     * 由服务端消化）。真机上 DG-LAB APP 对多余字段很敏感，曾因带 clientId 直接 reset（见 AGENTS 14.3）。
     */
    fun messageToApp(data: JsonElement): JsonObject =
        jsonObject(
            "type" to jsonOf(TYPE_MESSAGE),
            "data" to data,
        )

    /**
     * 从握手资源描述符里取 query 参数，例如 `GET /?tid=deadbeef HTTP/1.1` → `deadbeef`。
     *
     * 不依赖 Android 的 `Uri`，纯 JVM 可测。V4 查询参数是 `tid`；同时容忍参考实现里的 `targetId`。
     */
    fun queryParam(resourceDescriptor: String?, name: String): String? {
        if (resourceDescriptor.isNullOrBlank()) return null
        val queryStart = resourceDescriptor.indexOf('?')
        if (queryStart < 0) return null
        val queryEnd = resourceDescriptor.indexOf(' ', queryStart).let { if (it < 0) resourceDescriptor.length else it }
        if (queryEnd <= queryStart + 1) return null
        val query = resourceDescriptor.substring(queryStart + 1, queryEnd)
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            val key = if (eq < 0) pair else pair.substring(0, eq)
            if (key != name) continue
            val rawValue = if (eq < 0) "" else pair.substring(eq + 1)
            return try {
                URLDecoder.decode(rawValue, StandardCharsets.UTF_8.name())
            } catch (_: Throwable) {
                rawValue
            }
        }
        return null
    }

    /** tid：优先 `tid`，其次 `targetId`（参考实现两种都接受） */
    fun targetIdFromDescriptor(resourceDescriptor: String?): String? =
        queryParam(resourceDescriptor, "tid")?.takeIf { it.isNotBlank() }
            ?: queryParam(resourceDescriptor, "targetId")?.takeIf { it.isNotBlank() }

    fun describeFrame(data: JsonElement?): String {
        val obj = data as? JsonObject ?: return "-"
        val t = obj.stringOrNull("t") ?: "-"
        val m = obj.stringOrNull("m")
        val reqId = obj.stringOrNull("reqId")
        val ev = obj.stringOrNull("ev")
        return buildString {
            append("t=").append(t)
            if (m != null) append(" m=").append(m)
            if (ev != null) append(" ev=").append(ev)
            if (reqId != null) append(" reqId=").append(reqId)
        }
    }
}

/**
 * 8 位 hex clientId 生成器。进程内唯一（控制器 targetId 与 APP clientId 共用同一个空间）。
 *
 * 审计：原来的 `used` HashSet 只增不减（每次重连都留一条，长时间运行无界增长）。
 * 32 位随机空间碰撞概率可忽略（万级连接约 1e-3），直接靠随机 + 调用方复用即可；
 * 不需要维护全量历史。
 */
internal object ClientIdGenerator {

    private val random = SecureRandom()

    @Synchronized
    fun next(): String {
        val bytes = ByteArray(4)
        random.nextBytes(bytes)
        return buildString(8) {
            for (byte in bytes) {
                val value = byte.toInt() and 0xFF
                append(HEX[value ushr 4])
                append(HEX[value and 0x0F])
            }
        }
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
