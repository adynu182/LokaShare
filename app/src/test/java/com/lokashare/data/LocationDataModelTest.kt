package com.lokashare.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationDataModelTest {

    @Test
    fun `payload includes unique event identifiers`() {
        val payload = LocationDataModel(
            deviceId = "device-1",
            userName = "user",
            deviceModel = "Pixel",
            latitude = -6.2,
            longitude = 106.8,
            accuracy = 5f,
            battery = 80,
            isCharging = false,
            localTimestamp = 1_700_000_000_000L,
            source = "online"
        )

        val map = payload.toFirestoreMap()

        assertTrue(map.containsKey("eventId"))
        assertTrue(map.containsKey("clientId"))
        assertFalse((map["eventId"] as? String).isNullOrBlank())
        assertFalse((map["clientId"] as? String).isNullOrBlank())
    }
}
