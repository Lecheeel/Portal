package com.system.location.service.core

import com.system.location.service.core.config.ConfigCodec
import com.system.location.service.core.config.ConfigEnvelope
import com.system.location.service.core.config.ConfigException
import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.providers.ProviderKind
import com.system.location.service.core.providers.ProviderProfile
import com.system.location.service.core.routing.AppRoute
import com.system.location.service.core.routing.RoutingConfig
import com.system.location.service.core.routing.RoutingMode
import com.system.location.service.core.scenario.Scenario
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigEnvelopeTest {
    private val scenario = Scenario("point", "point", point = Wgs84(31.2304, 121.4737))
    private val config = ConfigEnvelope(
        revision = 7,
        scenario = scenario,
        routing = RoutingConfig(
            mode = RoutingMode.PER_APP,
            routes = listOf(AppRoute("com.example.map", "point")),
        ),
        updatedAtMillis = 1_790_000_000_000,
    )

    @Test
    fun v2RoundTrips() {
        val decoded = ConfigCodec.decode(ConfigCodec.encode(config))
        assertEquals(config, decoded)
        assertEquals(ConfigEnvelope.CURRENT_SCHEMA_VERSION, decoded.schemaVersion)
    }

    @Test
    fun v1IsMigratedWithDefaults() {
        val v1 = """{"schemaVersion":1,"revision":3,"scenario":{"id":"point","name":"point","point":{"latitude":31.2304,"longitude":121.4737}},"updatedAtMillis":123}"""
        val decoded = ConfigCodec.decode(v1)
        assertEquals(ConfigEnvelope.CURRENT_SCHEMA_VERSION, decoded.schemaVersion)
        assertEquals(3L, decoded.revision)
        assertEquals(123L, decoded.updatedAtMillis)
        assertEquals("point", decoded.scenario.id)
        assertEquals(RoutingMode.GLOBAL, decoded.routing.mode)
        assertEquals(3, decoded.providers.size)
    }

    @Test
    fun unknownFieldsAreIgnored() {
        val encoded = ConfigCodec.encode(config).trimEnd().removeSuffix("}") +
            ",\"futureField\":{\"a\":1}}"
        assertEquals(config, ConfigCodec.decode(encoded))
    }

    @Test
    fun futureVersionAndMissingVersionAreRejected() {
        assertThrows(ConfigException::class.java) { ConfigCodec.decode("""{"schemaVersion":99}""") }
        assertThrows(ConfigException::class.java) { ConfigCodec.decode("""{"revision":1}""") }
        assertThrows(ConfigException::class.java) { ConfigCodec.decode("not-json") }
    }

    @Test
    fun invalidEnvelopeIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { config.copy(revision = -1) }
        assertThrows(IllegalArgumentException::class.java) {
            config.copy(providers = listOf(ProviderProfile.GPS, ProviderProfile.GPS))
        }
        assertThrows(IllegalArgumentException::class.java) {
            config.copy(routing = RoutingConfig(mode = RoutingMode.PER_APP, routes = listOf(AppRoute("a", "s"), AppRoute("A", "t"))))
        }
    }

    @Test
    fun encodedJsonCarriesCurrentVersionAndDefaults() {
        val decoded = ConfigCodec.decode(ConfigCodec.encode(config))
        assertTrue(decoded.providers.any { it.kind == ProviderKind.GPS })
        assertTrue(decoded.routing.routes.isNotEmpty())
    }
}
