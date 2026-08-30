package dev.herdroid.core.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import dev.herdroid.core.model.HardwareSecurityLevel
import dev.herdroid.core.model.SshKeyOrigin

@Entity(
    tableName = "ssh_keys",
    indices = [Index(value = ["name"], unique = true), Index(value = ["alias"], unique = true)],
)
internal data class SshKeyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val alias: String,
    val publicKeyOpenSsh: ByteArray,
    val fingerprint: String,
    val origin: SshKeyOrigin,
    val securityLevel: HardwareSecurityLevel,
    val createdAtEpochMillis: Long,
)

@Entity(
    tableName = "endpoints",
    foreignKeys = [
        ForeignKey(
            entity = SshKeyEntity::class,
            parentColumns = ["id"],
            childColumns = ["keyId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("keyId")],
)
internal data class EndpointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val hostname: String,
    val port: Int,
    val username: String,
    val authType: String,
    val password: ByteArray?,
    val keyId: Long?,
    val herdrPath: String?,
    val cachedBridgeTarget: String? = null,
    val cachedHerdrPath: String? = null,
    val cachedBridgePath: String? = null,
) {
    override fun toString() =
        "EndpointEntity(id=$id, hostname=$hostname, port=$port, username=$username, authType=$authType, credentials=redacted, herdrPath=$herdrPath)"
}

@Entity(
    tableName = "routes",
    foreignKeys = [
        ForeignKey(
            entity = EndpointEntity::class,
            parentColumns = ["id"],
            childColumns = ["targetEndpointId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = EndpointEntity::class,
            parentColumns = ["id"],
            childColumns = ["jumpEndpointId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["targetEndpointId"], unique = true),
        Index(value = ["jumpEndpointId"], unique = true),
    ],
)
internal data class RouteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val targetEndpointId: Long,
    val jumpEndpointId: Long?,
)

@Entity(tableName = "known_hosts", primaryKeys = ["hostname", "port", "algorithm"])
internal data class KnownHostEntity(
    val hostname: String,
    val port: Int,
    val algorithm: String,
    val keyBase64: String,
    val acceptedAtEpochMillis: Long,
)

internal data class RouteWithEndpoints(
    @Embedded val route: RouteEntity,
    @Relation(parentColumn = "targetEndpointId", entityColumn = "id", entity = EndpointEntity::class)
    val target: EndpointWithKey,
    @Relation(parentColumn = "jumpEndpointId", entityColumn = "id", entity = EndpointEntity::class)
    val jump: EndpointWithKey?,
)

internal data class EndpointWithKey(
    @Embedded val endpoint: EndpointEntity,
    @Relation(parentColumn = "keyId", entityColumn = "id")
    val key: SshKeyEntity?,
)
