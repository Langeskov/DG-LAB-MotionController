package com.motioncontroller.dglab

import com.motioncontroller.relay.jsonObject
import com.motioncontroller.relay.jsonOf
import com.motioncontroller.relay.stringOrNull
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * [OutboundWriter] 单测：有界队列 + 单写线程 + device.op 积压丢弃 + 安全帧优先。
 *
 * 慢 APP 无法在单测里真实构造（Java-WebSocket 客户端读得飞快），所以直接测组件语义：
 * 写线程被"卡住"时模拟慢 APP，验证积压不会无限增长、device.op 会被计数丢弃、归零帧能清队优先发。
 */
class OutboundWriterTest {

    @Test
    fun dropsDroppableFramesWhenBacklogExceedsThreshold() {
        val inFlight = CountDownLatch(1)
        val release = CountDownLatch(1)
        val delivered = Collections.synchronizedList(mutableListOf<String>())
        val droppedEvents = AtomicInteger()

        val writer = OutboundWriter(
            capacity = 8,
            dropThreshold = 1,
            sender = { frame, _ ->
                val method = frame.stringOrNull("m") ?: "?"
                if (method == "marker") {
                    inFlight.countDown()
                    release.await(2, TimeUnit.SECONDS)
                }
                delivered.add(method)
                true
            },
            onDropped = { count, _ -> droppedEvents.addAndGet(count) },
        )
        writer.start()
        try {
            assertTrue("marker 应入队", writer.submit(requestFrame("marker"), "c1", droppable = false))
            // 写线程正卡在 marker 上：此时队列为空，是"慢 APP"的确定状态
            assertTrue("写线程应进入 in-flight 状态", inFlight.await(2, TimeUnit.SECONDS))

            assertTrue("积压未超阈值，第一条 op 应入队", writer.submit(requestFrame("device.op-1"), "c1", droppable = true))
            assertFalse("积压已达阈值，后续设备指令必须被丢弃", writer.submit(requestFrame("device.op-2"), "c1", droppable = true))

            assertEquals(1L, writer.droppedOpCount)
            assertTrue("丢弃应回调计数", droppedEvents.get() >= 1)

            release.countDown()
        } finally {
            writer.stop(2_000)
        }
        val ops = delivered.count { it.startsWith("device.op") }
        assertEquals("只有第一条设备指令应真正发出", 1, ops)
    }

    @Test
    fun priorityFramesClearBacklogAndAreDeliveredFirst() {
        val inFlight = CountDownLatch(1)
        val release = CountDownLatch(1)
        val delivered = Collections.synchronizedList(mutableListOf<String>())

        val writer = OutboundWriter(
            capacity = 16,
            dropThreshold = 16,
            sender = { frame, _ ->
                val method = frame.stringOrNull("m") ?: "?"
                if (method == "slow") {
                    inFlight.countDown()
                    release.await(2, TimeUnit.SECONDS)
                }
                delivered.add(method)
                true
            },
        )
        writer.start()
        try {
            writer.submit(requestFrame("slow"), "c1", droppable = false)
            assertTrue(inFlight.await(2, TimeUnit.SECONDS))
            repeat(3) { writer.submit(requestFrame("queued-$it"), "c1", droppable = false) }

            val queued = writer.submitPriority(
                listOf(OutboundWriter.Envelope(requestFrame("zero-op"), "c1")),
            )

            assertEquals("优先帧应入队", 1, queued)
            assertEquals("积压的普通帧应被清掉", 1, writer.queuedCount)
            assertEquals("清队帧应计入丢弃", 3L, writer.droppedFrameCount)
            release.countDown()
        } finally {
            writer.stop(2_000)
        }
        assertEquals("普通积压必须让位给归零帧", listOf("slow", "zero-op"), delivered.toList())
    }

    @Test
    fun stopFlushesQueuedFrames() {
        val delivered = Collections.synchronizedList(mutableListOf<String>())
        val writer = OutboundWriter(
            sender = { frame, _ ->
                delivered.add(frame.stringOrNull("m") ?: "?")
                true
            },
        )
        writer.start()
        repeat(5) { writer.submit(requestFrame("f$it"), "c1", droppable = false) }
        writer.stop(2_000)
        assertEquals("stop 必须把已入队帧尽量写完", 5, delivered.size)
    }

    private fun requestFrame(method: String): JsonObject = jsonObject(
        "t" to jsonOf("req"),
        "m" to jsonOf(method),
    )
}
