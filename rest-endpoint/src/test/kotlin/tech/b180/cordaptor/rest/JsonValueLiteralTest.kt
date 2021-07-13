package tech.b180.cordaptor.rest

import net.corda.core.node.services.Vault
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JsonValueLiteralTest {

    @Test
    fun `test JsonValueLiteral asInstant`() {
        assertEquals(
            Instant.parse("2020-06-01T00:00:00Z"), JsonValueLiteral("\"2020-06-01\"".asJsonValue()).asInstant()
        )
    }

    @Test
    fun `test JsonValueLiteral asList`() {
        assertEquals(
            listOf(JsonValueLiteral("\"ABC\"".asJsonValue()), JsonValueLiteral("\"CBA\"".asJsonValue())), JsonValueLiteral("""{"values": ["ABC", "CBA"]}""".asJsonObject()).asList()
        )
    }

    @Test
    fun `test JsonValueLiteral asVaultStatus`() {
        assertEquals(
            Vault.StateStatus.UNCONSUMED, JsonValueLiteral("\"unconsumed\"".asJsonValue()).asVaultStatus()
        )

        assertEquals(
            Vault.StateStatus.CONSUMED, JsonValueLiteral("\"consumed\"".asJsonValue()).asVaultStatus()
        )

        assertEquals(
            Vault.StateStatus.ALL, JsonValueLiteral("\"all\"".asJsonValue()).asVaultStatus()
        )

        assertFailsWith(Exception::class) {
            JsonValueLiteral("\"123\"".asJsonValue()).asVaultStatus()
        }
    }

    @Test
    fun `test JsonValueLiteral asRelevancyStatus`() {
        assertEquals(
            Vault.RelevancyStatus.RELEVANT, JsonValueLiteral("\"relevant\"".asJsonValue()).asRelevancyStatus()
        )

        assertEquals(
            Vault.RelevancyStatus.NOT_RELEVANT, JsonValueLiteral("\"not_relevant\"".asJsonValue()).asRelevancyStatus()
        )

        assertEquals(
            Vault.RelevancyStatus.ALL, JsonValueLiteral("\"all\"".asJsonValue()).asRelevancyStatus()
        )

        assertFailsWith(Exception::class) {
            JsonValueLiteral("\"123\"".asJsonValue()).asRelevancyStatus()
        }
    }


}