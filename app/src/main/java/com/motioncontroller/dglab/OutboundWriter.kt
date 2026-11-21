package com.motioncontroller.dglab

import kotlinx.serialization.json.JsonObject
import java.util.ArrayDeque
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 控制器 → APP 的单写线程 + 有界队列。
 *
 * 为什么需要它：
 *  - Java-WebSocket 的 [org.java_websocket.WebSocket.send] 只是把帧塞进每条连接**无界**的
 *    `outQueue`（真正写 socket 的是它自己的 worker 线程）。慢 APP 会让 outQueue 无限增长；
 *  - 原来所有 `send` 都在控制器的 `synchronized(lock)` 里调用，UI 线程 / ticker 线程会被
 *    队列锁和慢连接拖住。
 *
 * 现在锁内只做 O(1) 的非阻塞入队，真正的 `server.sendToApp()` 由这里唯一的写线程在锁外执行：
 *  - [capacity]：硬上限，超过直接丢帧（急停/归零等安全帧走 [submitPriority]，不会被普通积压挤掉）；
 *  - [dropThreshold]：`droppable` 帧（周期性 `device.op`）在积压超过该值时直接丢弃并计数，
 *    由调用方限流打日志；避免"慢 APP 把内存吃光"。
 */
internal class OutboundWriter(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val dropThreshold: Int = DEFAULT_DROP_THRESHOLD,
    private val sender: (frame: JsonObject, clientId: String?) -> Boolean,
    private val onDropped: (count: Int, reason: String) -> Unit = { _, _ -> },
    private val onSendFailure: (Throwable) -> Unit = {},
) {

    internal data class Envelope(val frame: JsonObject, val clientId: String?)

    private val lock = ReentrantLock()
    private val notEmpty = lock.newCondition()
    private val queue = ArrayDeque<Envelope>()

    private var thread: Thread? = null
    private var running = false
    private var droppedOps = 0L
    private var droppedFrames = 0L

    private val maxCapacity = capacity.coerceAtLeast(2)
    private val maxDropThreshold = dropThreshold.coerceAtLeast(0)

    val queuedCount: Int get() = lock.withLock { queue.size }

    val droppedOpCount: Long get() = lock.withLock { droppedOps }

    val droppedFrameCount: Long get() = lock.withLock { droppedFrames }

    fun start() {
        lock.withLock {
            if (running) return
            running = true
            val t = Thread(::loop, "V4Writer")
            t.isDaemon = true
            thread = t
            t.start()
        }
    }

    /**
     * 非阻塞入队。
     *
     * @param droppable 周期性指令（`device.op` 的 t=3/7）为 true：积压高时直接丢弃；
     *        握手/查询安全类帧为 false：只有在队列达到硬上限时才丢。
     * @return true 表示已入队（不代表 APP 已收到）；false 表示被丢弃/写线程已停止。
     */
    fun submit(frame: JsonObject, clientId: String?, droppable: Boolean): Boolean {
        var dropReason: String? = null
        val accepted = lock.withLock {
            when {
                !running -> false
                droppable && queue.size >= maxDropThreshold -> {
                    droppedOps++
                    dropReason = "backlog"
                    false
                }
                queue.size >= maxCapacity -> {
                    droppedFrames++
                    dropReason = "full"
                    false
                }
                else -> {
                    queue.addLast(Envelope(frame, clientId))
                    notEmpty.signal()
                    true
                }
            }
        }
        dropReason?.let { onDropped(1, it) }
        return accepted
    }

    /**
     * 安全帧优先入队：清掉积压的普通帧再放进去，保证急停/归零不会排在慢连接后面。
     * @return 实际入队的帧数（写线程未运行时返回 0）
     */
    fun submitPriority(envelopes: List<Envelope>): Int {
        if (envelopes.isEmpty()) return 0
        var cleared = 0
        val accepted = lock.withLock {
            if (!running) {
                0
            } else {
                cleared = queue.size
                if (cleared > 0) {
                    queue.clear()
                    droppedFrames += cleared
                }
                for (envelope in envelopes) queue.addLast(envelope)
                notEmpty.signalAll()
                envelopes.size
            }
        }
        if (cleared > 0) onDropped(cleared, "priority")
        return accepted
    }

    /**
     * 停止写线程：先让线程把已入队的帧（尤其是刚提交的归零帧）尽量写完，最多等 [flushTimeoutMs]。
     * 调用方不应在网络/UI 关键路径上长时间阻塞。
     */
    fun stop(flushTimeoutMs: Long) {
        val current = lock.withLock {
            if (!running && thread == null) return
            running = false
            notEmpty.signalAll()
            thread
        }
        if (current != null && current !== Thread.currentThread()) {
            try {
                current.join(flushTimeoutMs.coerceAtLeast(0L))
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            if (current.isAlive) current.interrupt()
        }
        lock.withLock { if (thread === current) thread = null }
    }

    private fun loop() {
        while (true) {
            val envelope = try {
                lock.withLock {
                    while (queue.isEmpty() && running) notEmpty.await()
                    // running=false 时仍把剩余队列排空再退出（尽力送达归零帧）
                    if (queue.isEmpty()) return
                    queue.removeFirst()
                }
            } catch (_: InterruptedException) {
                if (lock.withLock { !running && queue.isEmpty() }) return
                continue
            }
            val delivered = try {
                sender(envelope.frame, envelope.clientId)
            } catch (t: Throwable) {
                onSendFailure(t)
                false
            }
            if (!delivered) {
                lock.withLock { droppedFrames++ }
                onDropped(1, "send")
            }
        }
    }

    companion object {
        const val DEFAULT_CAPACITY = 256

        /** 积压超过该值就丢弃周期性的 device.op（约 6s 的下发量）。 */
        const val DEFAULT_DROP_THRESHOLD = 32
    }
}
