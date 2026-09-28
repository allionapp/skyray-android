package com.v2ray.ang.handler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class AdSignalOverrideTest {

    @Test
    fun knownExitsGetTheirLanguageAndZone() {
        assertEquals(Locale("de", "DE") to "Europe/Berlin", AdSignalOverride.geo("DE"))
        assertEquals(Locale("nl", "NL") to "Europe/Amsterdam", AdSignalOverride.geo("nl"))
    }

    @Test
    fun unknownOrMissingExitsAreNeutralNeverTheDevices() {
        assertEquals(Locale("en", "BR") to "Etc/UTC", AdSignalOverride.geo("BR"))
        assertEquals(Locale.forLanguageTag("en") to "Etc/UTC", AdSignalOverride.geo(null))
        assertEquals(Locale.forLanguageTag("en") to "Etc/UTC", AdSignalOverride.geo("unknown"))
    }

    @Test
    fun countryIsReadFromTheProbeLine() {
        assertEquals("DE", AdSignalOverride.countryFromProbe("(DE) 57.129.114.59"))
        assertEquals("NL", AdSignalOverride.countryFromProbe(" (nl) 2001:db8::1"))
        assertNull(AdSignalOverride.countryFromProbe("(unknown) 1.2.3.4"))
        assertNull(AdSignalOverride.countryFromProbe(null))
    }

    @Test
    fun nothingIsAppliedWithoutTheTunnel() {
        val before = Locale.getDefault()
        AdSignalOverride.apply("DE")
        assertEquals(before, Locale.getDefault())
    }
}
