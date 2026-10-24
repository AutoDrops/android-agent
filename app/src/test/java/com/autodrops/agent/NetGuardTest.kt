package com.autodrops.agent

import org.junit.Assert.*
import org.junit.Test

class NetGuardTest {
    @Test fun recognizesPrivateRanges() {
        listOf("10.0.0.5", "10.255.1.1", "172.16.0.1", "172.31.255.254", "192.168.1.44", "127.0.0.1", "169.254.1.1")
            .forEach { assertTrue("$it should be private", NetGuard.isPrivateV4(it)) }
    }
    @Test fun rejectsPublicRanges() {
        listOf("8.8.8.8", "1.1.1.1", "172.15.0.1", "172.32.0.1", "11.0.0.1", "192.167.0.1", "203.0.113.5")
            .forEach { assertFalse("$it should be public", NetGuard.isPrivateV4(it)) }
    }
    @Test fun rejectsMalformed() {
        listOf("999.1.1.1", "1.2.3", "abc", "", "192.168.1").forEach { assertFalse(NetGuard.isPrivateV4(it)) }
    }
    @Test fun ensureLocalRejectsPublicIp() {
        val err = NetGuard.ensureLocal("8.8.8.8", "192.168.1.10", true)
        assertNotNull(err); assertTrue(err!!.contains("публічна") || err.contains("Відхилено"))
    }
    @Test fun ensureLocalAllowsSameSubnet() {
        assertNull(NetGuard.ensureLocal("192.168.1.44", "192.168.1.10", true))
    }
    @Test fun ensureLocalRejectsOtherSubnetWhenRequired() {
        assertNotNull(NetGuard.ensureLocal("192.168.5.44", "192.168.1.10", true))
    }
    @Test fun ensureLocalAllowsAnyPrivateWhenSubnetNotRequired() {
        assertNull(NetGuard.ensureLocal("10.1.2.3", "192.168.1.10", false))
    }
    @Test fun samePrefixWorks() {
        assertTrue(NetGuard.samePrefix24("192.168.1.5", "192.168.1.200"))
        assertFalse(NetGuard.samePrefix24("192.168.1.5", "192.168.2.5"))
    }
}
