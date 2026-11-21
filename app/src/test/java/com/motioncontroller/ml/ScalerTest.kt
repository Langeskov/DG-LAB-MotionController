package com.motioncontroller.ml

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * `StandardScaler` 单测：
 *  - 解析 assets 里**真实的** `scaler.json`（当前是占位 `valid=false`）；
 *  - 校验 `(x-mean)/scale`、`scale==0 → 1`、缺字段/脏数据的降级行为。
 */
class ScalerTest {

    /** 依次尝试若干工作目录候选（Gradle 单测的 workingDir 可能是 module 或 repo 根）。 */
    private fun scalerAsset(): File? {
        val candidates = listOf(
            "app/src/main/assets/scaler.json",
            "src/main/assets/scaler.json",
            "../app/src/main/assets/scaler.json",
            "../../app/src/main/assets/scaler.json",
        )
        return candidates.map { File(it) }.firstOrNull { it.isFile }
    }

    @Test
    fun parsesRealAssetScalerJson() {
        val file = scalerAsset()
        assertNotNull(
            "找不到 app/src/main/assets/scaler.json（从 ${File(".").absolutePath} 起）",
            file,
        )
        val scaler = StandardScaler.parse(file!!.readText())
        assertEquals("scaler 维度必须是 24", FeatureExtractor.FEATURE_DIM, scaler.dim)
        assertEquals(24, scaler.mean.size)
        assertEquals(24, scaler.scale.size)
        // 真实 scaler（由 tools/extract_scaler.py 从训练 checkpoint 生成）：valid=true
        assertTrue("真实 scaler 必须标记 valid=true", scaler.valid)
        for (i in 0 until 24) {
            assertFalse("mean[$i] 不能是 NaN", scaler.mean[i].isNaN())
            assertFalse("scale[$i] 不能是 NaN", scaler.scale[i].isNaN())
            assertTrue("scale[$i] 必须 > 0，实际 ${scaler.scale[i]}", scaler.scale[i] > 0.0)
        }
        assertTrue("mean 不能全为 0", scaler.mean.any { it != 0.0 })

        // 标准化语义：x=mean → 0；x=mean+scale → 1
        val atMean = scaler.transform(scaler.mean.copyOf())
        for (i in 0 until 24) assertEquals(0.0, atMean[i], 1e-9)
        val atMeanPlusScale = DoubleArray(24) { scaler.mean[it] + scaler.scale[it] }
        val standardized = scaler.transform(atMeanPlusScale)
        for (i in 0 until 24) assertEquals(1.0, standardized[i], 1e-9)
    }

    @Test
    fun parsesValidScalerAndTransforms() {
        val json = """
            {
              "_comment": "test",
              "valid": true,
              "mean": [${(0 until 24).joinToString(",") { it.toString() }}],
              "scale": [${(0 until 24).joinToString(",") { if (it == 0) "0" else (it * 2).toString() }}]
            }
        """.trimIndent()
        val scaler = StandardScaler.parse(json)
        assertTrue(scaler.valid)
        assertEquals(24, scaler.dim)
        assertEquals(0.0, scaler.mean[0], 0.0)
        assertEquals(4.0, scaler.scale[2], 0.0)

        val row = DoubleArray(24) { 10.0 }
        val out = scaler.transform(row)
        // scale==0 → 按 1 处理：(10-0)/1 = 10
        assertEquals(10.0, out[0], 1e-12)
        // 其余： (10 - d) / (2d)
        assertEquals((10.0 - 2.0) / 4.0, out[2], 1e-12)
        assertEquals((10.0 - 23.0) / 46.0, out[23], 1e-12)
    }

    @Test
    fun identityScalerIsNoOpAndInvalid() {
        val scaler = StandardScaler.identity(24)
        assertFalse(scaler.valid)
        assertEquals(24, scaler.dim)
        val row = DoubleArray(24) { it * 3.0 }
        assertArrayEquals(row, scaler.transform(row), 0.0)
        assertEquals(FeatureExtractor.FEATURE_DIM, StandardScaler.identity().dim)
    }

    @Test
    fun transformAllPreservesShape() {
        val scaler = StandardScaler(DoubleArray(24) { 1.0 }, DoubleArray(24) { 2.0 }, true)
        val rows = Array(3) { DoubleArray(24) { 5.0 } }
        val out = scaler.transformAll(rows)
        assertEquals(3, out.size)
        assertEquals(24, out[0].size)
        assertEquals((5.0 - 1.0) / 2.0, out[2][23], 1e-12)
    }

    @Test
    fun malformedJsonThrows() {
        try {
            StandardScaler.parse("""{"valid": true, "mean": [1,2,3]}""")
            fail("缺 scale 字段应抛异常")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("scale"))
        }
        try {
            StandardScaler.parse("""{"mean": [0], "scale": [1, 2]}""")
            fail("mean/scale 长度不一致应抛异常")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("长度"))
        }
    }

    @Test
    fun missingValidFieldDefaultsToFalse() {
        val json = """
            {"mean": [${(0 until 24).joinToString(",") { "0" }}],
             "scale": [${(0 until 24).joinToString(",") { "1" }}]}
        """.trimIndent()
        assertFalse(StandardScaler.parse(json).valid)
    }

    /** 2026-10-31 audit #2：维度必须是 24，否则启动时就要失败（由 pipeline 降级为 identity）。 */
    @Test
    fun parseRejectsWrongDimension() {
        val json = """
            {"valid": true,
             "mean": [${(0 until 23).joinToString(",") { "0" }}],
             "scale": [${(0 until 23).joinToString(",") { "1" }}]}
        """.trimIndent()
        try {
            StandardScaler.parse(json)
            fail("非 24 维 scaler 应抛异常")
        } catch (e: IllegalArgumentException) {
            assertTrue("错误信息应包含维度：${e.message}", e.message!!.contains("维度"))
        }
    }

    /** scale 必须有限且非负；scale==0 仍按 1 处理（既有策略，由上面用例覆盖）。 */
    @Test
    fun rejectsNegativeOrNonFiniteScale() {
        try {
            StandardScaler(DoubleArray(24), DoubleArray(24) { -1.0 }, true)
            fail("负 scale 应抛异常")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("scale"))
        }
        try {
            StandardScaler(DoubleArray(24), DoubleArray(24) { if (it == 2) Double.POSITIVE_INFINITY else 1.0 }, true)
            fail("Inf scale 应抛异常")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("scale"))
        }
        try {
            StandardScaler(DoubleArray(24) { if (it == 5) Double.NaN else 0.0 }, DoubleArray(24) { 1.0 }, true)
            fail("NaN mean 应抛异常")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("mean"))
        }
    }

    /** 从 JSON 解析出的负 scale 也要被拒（不能只靠构造函数）。 */
    @Test
    fun parseRejectsNegativeScaleFromJson() {
        val scales = (0 until 24).joinToString(",") { if (it == 7) "-0.5" else "1.0" }
        val json = """
            {"valid": true,
             "mean": [${(0 until 24).joinToString(",") { "0" }}],
             "scale": [$scales]}
        """.trimIndent()
        try {
            StandardScaler.parse(json)
            fail("JSON 里的负 scale 应抛异常")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("scale"))
        }
    }
}
