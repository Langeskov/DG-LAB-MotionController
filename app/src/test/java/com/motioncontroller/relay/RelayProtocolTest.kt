package com.motioncontroller.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 中继基础件单测：握手 tid 解析（V4 的关键参数名是 `tid`）、clientId 生成、展示地址选择。
 */
class RelayProtocolTest {

    @Test
    fun parsesTidFromResourceDescriptor() {
        assertEquals("abc123", RelayProtocol.queryParam("GET /?tid=abc123 HTTP/1.1", "tid"))
        assertEquals("abc123", RelayProtocol.queryParam("GET /?foo=1&tid=abc123&bar=2 HTTP/1.1", "tid"))
        assertEquals("abc123", RelayProtocol.queryParam("GET /path?tid=abc123 HTTP/1.1", "tid"))
        assertEquals("abc123", RelayProtocol.queryParam("/?tid=abc123", "tid"))
    }

    @Test
    fun decodesUrlEncodedTid() {
        assertEquals("a b+c", RelayProtocol.queryParam("GET /?tid=a%20b%2Bc HTTP/1.1", "tid"))
    }

    @Test
    fun targetIdFromDescriptorPrefersTidAndFallsBackToTargetId() {
        assertEquals("t1", RelayProtocol.targetIdFromDescriptor("GET /?tid=t1 HTTP/1.1"))
        assertEquals("t2", RelayProtocol.targetIdFromDescriptor("GET /?targetId=t2 HTTP/1.1"))
        assertEquals("t1", RelayProtocol.targetIdFromDescriptor("GET /?targetId=t2&tid=t1 HTTP/1.1"))
        assertNull(RelayProtocol.targetIdFromDescriptor("GET / HTTP/1.1"))
        assertNull(RelayProtocol.targetIdFromDescriptor("GET /?tid= HTTP/1.1"))
        assertNull(RelayProtocol.targetIdFromDescriptor(null))
    }

    @Test
    fun clientIdsAreEightHexCharsAndUnique() {
        val ids = (1..200).map { ClientIdGenerator.next() }
        assertEquals(200, ids.toSet().size)
        for (id in ids) {
            assertTrue("clientId 应为 8 位 hex: $id", id.matches(Regex("[0-9a-f]{8}")))
        }
    }

    @Test
    fun displayHostReplacesWildcardBinding() {
        assertTrue(NetworkAddress.isWildcard("0.0.0.0"))
        assertTrue(NetworkAddress.isWildcard("::"))
        assertFalse(NetworkAddress.isWildcard("127.0.0.1"))
        assertEquals("127.0.0.1", NetworkAddress.displayHostFor("127.0.0.1"))
        val wildcardDisplay = NetworkAddress.displayHostFor("0.0.0.0")
        assertNotNull(wildcardDisplay)
        assertTrue("通配绑定应换成具体地址: $wildcardDisplay", wildcardDisplay != "0.0.0.0")
    }

    @Test
    fun pongAndMessageFramesHaveExpectedShape() {
        val pong = RelayProtocol.pong(1234L)
        assertEquals("pong", pong.stringOrNull("type"))
        assertEquals(1234L, (pong["ts"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLong())

        val error = RelayProtocol.error(RelayProtocol.ERROR_CONTROLLER_NOT_FOUND)
        assertEquals("error", error.stringOrNull("type"))
        assertEquals("controller_not_found", error.stringOrNull("code"))
    }

    @Test
    fun networkInterfaceRankingPrefersWifiOverVpnAndCellular() {
        // 双机二维码必须优先 Wi-Fi / 以太网，VPN / 蜂窝地址排最后
        val wifi = NetworkAddress.interfaceRank("wlan0")
        val ethernet = NetworkAddress.interfaceRank("eth0")
        val hotspot = NetworkAddress.interfaceRank("ap0")
        val unknown = NetworkAddress.interfaceRank("somethingelse0")
        val vpn = NetworkAddress.interfaceRank("tun0")
        val cellular = NetworkAddress.interfaceRank("rmnet_data0")

        assertEquals(0, wifi)
        assertEquals(0, ethernet)
        assertEquals(0, hotspot)
        assertTrue("Wi-Fi 必须优于未知网卡: $wifi vs $unknown", wifi < unknown)
        assertTrue("未知网卡必须优于 VPN: $unknown vs $vpn", unknown < vpn)
        assertTrue("未知网卡必须优于蜂窝: $unknown vs $cellular", unknown < cellular)
        assertEquals("VPN 与蜂窝同级（排最后）", 2, vpn)
        assertEquals(2, cellular)
    }

    @Test
    fun describeThrowableIncludesClassAndCauseChain() {
        val outer = IllegalStateException("outer-failed", IllegalArgumentException("inner-bad"))
        val described = describeThrowable(outer)
        assertTrue("应包含异常类名: $described", described.contains("IllegalStateException"))
        assertTrue("应包含 message: $described", described.contains("outer-failed"))
        assertTrue("应包含 cause 类名: $described", described.contains("IllegalArgumentException"))
        assertTrue("应包含 cause message: $described", described.contains("inner-bad"))
    }
}
