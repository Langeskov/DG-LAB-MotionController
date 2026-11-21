package com.motioncontroller.ui.i18n

import com.motioncontroller.ui.model.CheckStatus
import com.motioncontroller.ui.model.ControlPhase
import com.motioncontroller.ui.model.MappingSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * i18n 层单测（纯 JVM）：
 *  - Zh/En 键一一对应、无空串（反射遍历 [UiStrings] 的 String 属性）；
 *  - 模板占位符 zh/en 一致；
 *  - [LanguageMode] 解析与 SYSTEM 语言判定；
 *  - CJK 排版小工具与 [fmt]。
 */
class UiStringsTest {

    /** [UiStrings] 上全部文案属性的 getter（零参、返回 String）。 */
    private fun stringGetters(): List<java.lang.reflect.Method> =
        UiStrings::class.java.methods
            .filter {
                it.parameterCount == 0 &&
                    it.returnType == String::class.java &&
                    it.name.startsWith("get")
            }
            .sortedBy { it.name }

    private fun valuesOf(impl: UiStrings): Map<String, String> =
        stringGetters().associate { it.name to (it.invoke(impl) as String) }

    @Test
    fun `zh 与 en 属性一一对应且无空串`() {
        val getters = stringGetters()
        // 键数量护栏：文案迁移误删时能第一时间发现
        assertTrue("UiStrings 键数量异常 ${getters.size}", getters.size >= 200)
        getters.forEach { getter ->
            val zh = getter.invoke(ZhUiStrings) as String
            val en = getter.invoke(EnUiStrings) as String
            assertTrue("ZH 空串 ${getter.name}", zh.isNotBlank())
            assertTrue("EN 空串 ${getter.name}", en.isNotBlank())
        }
    }

    @Test
    fun `实现不包含接口之外的公开文案键`() {
        val interfaceNames = stringGetters().map { it.name }.toSet()
        listOf(ZhUiStrings, EnUiStrings).forEach { impl ->
            val extra = impl.javaClass.methods
                .filter {
                    it.parameterCount == 0 &&
                        it.returnType == String::class.java &&
                        it.name.startsWith("get")
                }
                .map { it.name }
                .filterNot { it in interfaceNames }
            assertTrue("${impl.javaClass.simpleName} 存在接口外的键 $extra", extra.isEmpty())
        }
    }

    @Test
    fun `模板占位符在 zh 与 en 一致`() {
        val token = Regex("\\{\\d}")
        val zh = valuesOf(ZhUiStrings)
        val en = valuesOf(EnUiStrings)
        stringGetters().map { it.name }.forEach { key ->
            val zhTokens = token.findAll(zh.getValue(key)).map { it.value }.sorted().toList()
            val enTokens = token.findAll(en.getValue(key)).map { it.value }.sorted().toList()
            assertEquals("占位符不一致 $key", zhTokens, enTokens)
        }
    }

    @Test
    fun `LanguageMode 解析容错回退 SYSTEM`() {
        assertEquals(LanguageMode.SYSTEM, LanguageMode.fromStored(null))
        assertEquals(LanguageMode.SYSTEM, LanguageMode.fromStored(""))
        assertEquals(LanguageMode.SYSTEM, LanguageMode.fromStored("   "))
        assertEquals(LanguageMode.SYSTEM, LanguageMode.fromStored("KLINGON"))
        assertEquals(LanguageMode.SYSTEM, LanguageMode.fromStored("system"))
        assertEquals(LanguageMode.ZH, LanguageMode.fromStored("ZH"))
        assertEquals(LanguageMode.ZH, LanguageMode.fromStored("zh"))
        assertEquals(LanguageMode.EN, LanguageMode.fromStored("en"))
    }

    @Test
    fun `SYSTEM 按系统语言判定 zh 优先`() {
        assertEquals(LanguageMode.ZH, LanguageMode.SYSTEM.resolved(Locale.SIMPLIFIED_CHINESE))
        assertEquals(LanguageMode.ZH, LanguageMode.SYSTEM.resolved(Locale.forLanguageTag("zh-Hant")))
        assertEquals(LanguageMode.EN, LanguageMode.SYSTEM.resolved(Locale.US))
        assertEquals(LanguageMode.EN, LanguageMode.SYSTEM.resolved(Locale.JAPANESE))
        // 显式模式不受系统语言影响
        assertEquals(LanguageMode.ZH, LanguageMode.ZH.resolved(Locale.US))
        assertEquals(LanguageMode.EN, LanguageMode.EN.resolved(Locale.SIMPLIFIED_CHINESE))
    }

    @Test
    fun `uiStringsFor 返回对应文案对象`() {
        assertSame(ZhUiStrings, uiStringsFor(LanguageMode.ZH, Locale.US))
        assertSame(EnUiStrings, uiStringsFor(LanguageMode.EN, Locale.SIMPLIFIED_CHINESE))
        assertSame(ZhUiStrings, uiStringsFor(LanguageMode.SYSTEM, Locale.forLanguageTag("zh-CN")))
        assertSame(EnUiStrings, uiStringsFor(LanguageMode.SYSTEM, Locale.US))
    }

    @Test
    fun `CJK 判定与丝印大小写`() {
        assertTrue("运动控制".hasCjk())
        assertTrue("DG-LAB 连接".hasCjk())
        assertFalse("DG-LAB LINK".hasCjk())
        assertFalse("".hasCjk())
        // 中文不做 uppercase（汉字恒等），拉丁保持丝印大写
        assertEquals("运动控制", "运动控制".uiCase())
        assertEquals("DG-LAB LINK", "dg-lab link".uiCase())
    }

    @Test
    fun `fmt 按序填充占位符且不解析百分号`() {
        assertEquals("等待A · 1", fmt("等待{0} · {1}", "A", 1))
        assertEquals("100% ok", fmt("100% ok"))
        assertEquals("x", fmt("{0}", "x"))
    }

    @Test
    fun `枚举映射在两种语言下均非空`() {
        CheckStatus.entries.forEach { status ->
            assertTrue(ZhUiStrings.checkStatus(status).isNotBlank())
            assertTrue(EnUiStrings.checkStatus(status).isNotBlank())
        }
        ControlPhase.entries.forEach { phase ->
            assertTrue(ZhUiStrings.controlPhase(phase).isNotBlank())
            assertTrue(EnUiStrings.controlPhase(phase).isNotBlank())
        }
        MappingSource.entries.forEach { source ->
            assertTrue(ZhUiStrings.mappingSource(source).isNotBlank())
            assertTrue(EnUiStrings.mappingSource(source).isNotBlank())
        }
    }

    @Test
    fun `中文文案为第一语言且英文保留设备丝印`() {
        assertEquals("开始控制", ZhUiStrings.startControl)
        assertEquals("急停", ZhUiStrings.emergencyStop)
        // 英文数据层保留 V1 字面量（大小写不限），丝印大写由组件层 uiCase() 渲染
        assertEquals("START CONTROL", EnUiStrings.startControl.uppercase())
        assertEquals("EMERGENCY STOP", EnUiStrings.emergencyStop.uppercase())
    }
}
