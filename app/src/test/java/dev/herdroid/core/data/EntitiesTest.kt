package dev.herdroid.core.data

import dev.herdroid.core.data.db.EndpointEntity
import dev.herdroid.core.data.db.EndpointWithKey
import dev.herdroid.core.data.db.RouteEntity
import dev.herdroid.core.data.db.RouteWithEndpoints
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class EntitiesTest {
    @Test
    fun routeInputOwnsAndClearsBothPasswordBuffers() {
        val targetPassword = "target-secret".encodeToByteArray()
        val jumpPassword = "jump-secret".encodeToByteArray()
        val route = RouteWriteInput(
            0,
            "jumped",
            endpoint("target.example", EndpointAuthenticationInput.Password(targetPassword)),
            endpoint("jump.example", EndpointAuthenticationInput.Password(jumpPassword)),
        )

        route.close()

        assertArrayEquals(ByteArray(targetPassword.size), targetPassword)
        assertArrayEquals(ByteArray(jumpPassword.size), jumpPassword)
    }

    @Test
    fun passwordInputRedactsItsSecret() {
        val password = EndpointAuthenticationInput.Password("password-value".encodeToByteArray())

        assertFalse(password.toString().contains("password-value"))
        password.close()
    }

    @Test
    fun malformedJumpMetadataClosesTheConstructedPasswordTarget() {
        val databasePassword = "target-secret".encodeToByteArray()
        val row = RouteWithEndpoints(
            RouteEntity(1, "broken", 1, 2),
            EndpointWithKey(
                EndpointEntity(1, "target.example", 22, "developer", "password", databasePassword, null, null),
                null,
            ),
            EndpointWithKey(
                EndpointEntity(2, "jump.example", 22, "developer", "hardware_key", null, 99, null),
                null,
            ),
        )
        var constructedTarget: ConnectionEndpointInput? = null
        try {
            val failure = assertThrows(IllegalArgumentException::class.java) {
                row.toConnectionInput { endpoint ->
                    if (constructedTarget == null) constructedTarget = endpoint
                }
            }

            assertEquals("Hardware key metadata is missing", failure.message)
            val authentication = requireNotNull(constructedTarget).authentication as ConnectionAuthenticationInput.Password
            assertArrayEquals(ByteArray(databasePassword.size), authentication.moveToConnector())
        } finally {
            databasePassword.fill(0)
        }
    }

    private fun endpoint(host: String, authentication: EndpointAuthenticationInput) =
        EndpointWriteInput(host, 22, "developer", authentication, null)
}
