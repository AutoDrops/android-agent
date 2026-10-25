package com.autodrops.agent

import org.junit.Assert.*
import org.junit.Test

/** Unit tests for the v3.10 own-LAN fingerprinting helpers (ARP parse, TTL→OS, banner→version, OUI lookup). */
class NetScanTest {
    @Test fun parsesArpTableSkippingIncomplete() {
        val raw = """
            IP address       HW type     Flags       HW address            Mask     Device
            192.168.1.1      0x1         0x2         aa:bb:cc:dd:ee:ff     *        wlan0
            192.168.1.9      0x1         0x0         00:00:00:00:00:00     *        wlan0
            192.168.1.20     0x1         0x2         B8:27:EB:11:22:33     *        wlan0
        """.trimIndent()
        val m = NetScan.parseArpTable(raw)
        assertEquals(2, m.size)
        assertEquals("aa:bb:cc:dd:ee:ff", m["192.168.1.1"])
        assertEquals("b8:27:eb:11:22:33", m["192.168.1.20"])
        assertFalse(m.containsKey("192.168.1.9"))
    }

    @Test fun ttlMapsToOsFamily() {
        assertTrue(NetScan.guessOsFromTtl(64).contains("Linux"))
        assertTrue(NetScan.guessOsFromTtl(57).contains("Linux"))
        assertTrue(NetScan.guessOsFromTtl(128).contains("Windows"))
        assertTrue(NetScan.guessOsFromTtl(120).contains("Windows"))
        assertTrue(NetScan.guessOsFromTtl(255).contains("мережеве"))
        assertTrue(NetScan.guessOsFromTtl(0).contains("невідомо"))
    }

    @Test fun parsesTtlFromPingOutput() {
        assertEquals(64, NetScan.parseTtl("64 bytes from 192.168.1.1: icmp_seq=1 ttl=64 time=2.1 ms"))
        assertEquals(128, NetScan.parseTtl("Reply from 192.168.1.5: bytes=32 time=1ms TTL=128"))
        assertNull(NetScan.parseTtl("Request timeout"))
    }

    @Test fun parsesServiceBanners() {
        assertTrue(NetScan.parseBanner("SSH-2.0-OpenSSH_8.9p1 Ubuntu-3", 22).contains("OpenSSH_8.9p1"))
        assertTrue(NetScan.parseBanner("HTTP/1.1 200 OK\r\nServer: nginx/1.18.0\r\n", 80).contains("nginx/1.18.0"))
        assertTrue(NetScan.parseBanner("220 ProFTPD 1.3.5 Server ready", 21).startsWith("ftp"))
        assertTrue(NetScan.parseBanner("220 mail.example.com ESMTP Postfix", 25).startsWith("smtp"))
        assertTrue(NetScan.parseBanner("", 9999).contains("порожній"))
    }

    @Test fun normalizesMacOui() {
        assertEquals("AABBCC", NetScan.normalizeOui("aa:bb:cc:dd:ee:ff"))
        assertEquals("B827EB", NetScan.normalizeOui("B8-27-EB-11-22-33"))
        assertEquals("B827EB", NetScan.normalizeOui("b827eb112233"))
        assertNull(NetScan.normalizeOui("zz:zz"))
        assertNull(NetScan.normalizeOui("12"))
    }

    @Test fun looksUpVendorFromTableAndBuiltin() {
        val table = mapOf("AABBCC" to "ACME Networks")
        assertEquals("ACME Networks", NetScan.lookupVendor("aa:bb:cc:00:11:22", table))
        // falls back to bundled table
        assertEquals("Raspberry Pi Foundation", NetScan.lookupVendor("b8:27:eb:11:22:33", emptyMap()))
        assertNull(NetScan.lookupVendor("ff:ff:ff:11:22:33", emptyMap()))
    }

    @Test fun parsesIeeeOuiCsv() {
        val csv = """
            Registry,Assignment,Organization Name,Organization Address
            MA-L,001122,"Acme, Inc.",Somewhere
            MA-L,B827EB,Raspberry Pi Foundation,Cambridge
        """.trimIndent()
        val m = NetScan.parseOuiCsv(csv)
        assertEquals("Acme, Inc.", m["001122"])
        assertEquals("Raspberry Pi Foundation", m["B827EB"])
        assertFalse(m.containsKey("Registry"))
    }

    @Test fun splitCsvRespectsQuotedCommas() {
        assertEquals(listOf("MA-L", "001122", "Acme, Inc.", "Addr"), NetScan.splitCsv("MA-L,001122,\"Acme, Inc.\",Addr"))
    }
}
