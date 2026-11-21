package com.motioncontroller.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** `ProcessModelCache`（进程级模型缓存、幂等 close）单测。 */
class ProcessModelCacheTest {

    private class FakeModel(val id: Int) {
        var closes = 0

        fun close() {
            closes++
        }
    }

    @Test
    fun loadsOnlyOnceAndReusesInstance() {
        var loads = 0
        val cache = ProcessModelCache<FakeModel>("fake.onnx") { it.close() }
        val first = cache.getOrLoad {
            loads++
            FakeModel(1)
        }
        val second = cache.getOrLoad {
            loads++
            FakeModel(2)
        }
        assertNotNull(first)
        assertSame(first, second)
        assertEquals("loader 只能执行一次", 1, loads)
        assertTrue(cache.owns(first!!))
    }

    @Test
    fun cachesFailureAndAllowsReloadAfterClose() {
        var loads = 0
        val cache = ProcessModelCache<FakeModel>("fake.onnx") { it.close() }
        val failed = cache.getOrLoad {
            loads++
            throw IllegalStateException("XNNPACK 与 CPU 均失败")
        }
        assertNull(failed)
        assertTrue(cache.lastFailure!!.contains("XNNPACK"))
        // 失败结果被缓存：再次调用不再重复付出失败成本
        assertNull(
            cache.getOrLoad {
                loads++
                FakeModel(0)
            },
        )
        assertEquals(1, loads)

        // 显式清理后可以重新加载
        cache.close()
        val ok = cache.getOrLoad {
            loads++
            FakeModel(9)
        }
        assertNotNull(ok)
        assertEquals(2, loads)
        assertNull(cache.lastFailure)
    }

    @Test
    fun closeIsIdempotentAndClosesExactlyOnce() {
        val model = FakeModel(1)
        var closes = 0
        val cache = ProcessModelCache<FakeModel>("fake.onnx") { m ->
            closes++
            m.close()
        }
        cache.getOrLoad { model }
        cache.close()
        cache.close()
        assertEquals("同一个 native session 只能关闭一次", 1, closes)
        assertEquals(1, model.closes)
        assertFalse("close 后不再持有", cache.owns(model))
        assertNull(cache.valueOrNull)
    }

    @Test
    fun closeWithoutValueDoesNotInvokeCloser() {
        var closerCalls = 0
        val cache = ProcessModelCache<FakeModel>("fake.onnx") {
            closerCalls++
        }
        cache.close()
        assertEquals(0, closerCalls)
    }
}
