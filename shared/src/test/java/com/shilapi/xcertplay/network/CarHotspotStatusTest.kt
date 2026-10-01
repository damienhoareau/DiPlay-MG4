package com.shilapi.xcertplay.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CarHotspotStatusTest {
    @Test fun findsReachableClientOnAndroidHotspotInterface() {
        val clients = CarHotspotStatus.parseArpClients(
            listOf(
                "IP address       HW type     Flags       HW address            Mask     Device",
                "192.168.43.20    0x1         0x2         aa:bb:cc:dd:ee:ff     *        ap0",
            ),
        )

        assertEquals(setOf("aa:bb:cc:dd:ee:ff"), clients)
    }

    @Test fun ignoresIncompleteAndNonHotspotNeighbors() {
        val clients = CarHotspotStatus.parseArpClients(
            listOf(
                "IP address       HW type     Flags       HW address            Mask     Device",
                "192.168.43.20    0x1         0x0         00:00:00:00:00:00     *        ap0",
                "192.168.1.10     0x1         0x2         aa:bb:cc:dd:ee:01     *        wlan0",
            ),
        )

        assertTrue(clients.isEmpty())
    }
}
