package com.motioncontroller.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `CoalescingLatestQueue`（推理队列深度 1、drop oldest）单测。
 *
 * 使用契约与 [MotionPipeline.drainInferenceQueue] 相同：`offer→executor.execute(drain)`，
 * drain 循环 `poll()` + `exitDrain()`。这里用同步 drain 模拟单线程 executor。
 */
class CoalescingLatestQueueTest {

    /** 同步执行一次 drain（与 MotionPipeline 的 drain 循环同构），返回实际处理的任务。 */
    private fun drainAll(queue: CoalescingLatestQueue<Int>): List<Int> {
        val processed = ArrayList<Int>()
        while (true) {
            val job = queue.poll()
            if (job == null) {
                if (queue.exitDrain()) return processed
            } else {
                processed.add(job)
            }
        }
    }

    @Test
    fun keepsOnlyLatestWhenTasksArriveWhileQueued() {
        val queue = CoalescingLatestQueue<Int>()
        // 首次 offer 需要投递 drain，后续只覆盖 pending（drop oldest）
        assertTrue(queue.offer(1))
        assertFalse(queue.offer(2))
        assertFalse(queue.offer(3))
        assertFalse(queue.offer(4))
        assertTrue(queue.hasPending)

        assertEquals(listOf(4), drainAll(queue))
        assertFalse(queue.hasPending)
    }

    @Test
    fun offerDuringDrainDoesNotScheduleSecondDrain() {
        val queue = CoalescingLatestQueue<Int>()
        assertTrue(queue.offer(1))
        // drain 已开始：取走 1 后、退出前又有新任务 2 到达
        assertEquals(1, queue.poll())
        assertFalse("drain 仍持有调度权", queue.offer(2))
        assertEquals(2, queue.poll())
        assertTrue(queue.exitDrain())
        // 退出之后的新任务需要重新投递 drain
        assertTrue(queue.offer(3))
        assertEquals(listOf(3), drainAll(queue))
    }

    @Test
    fun exitDrainReportsNewPendingTask() {
        val queue = CoalescingLatestQueue<Int>()
        assertTrue(queue.offer(1))
        assertEquals(1, queue.poll())
        assertFalse(queue.offer(2))
        assertFalse("有 pending 时不能退出 drain", queue.exitDrain())
        assertEquals(2, queue.poll())
        assertTrue(queue.exitDrain())
    }

    @Test
    fun clearDropsPendingAndAllowsExit() {
        val queue = CoalescingLatestQueue<Int>()
        assertTrue(queue.offer(7))
        queue.clear()
        assertFalse(queue.hasPending)
        assertNull(queue.poll())
        assertTrue(queue.exitDrain())
    }
}
