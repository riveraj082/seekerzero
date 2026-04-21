package dev.seekerzero.app.ssh

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * One row per (host, port, key_type) fingerprint the user has TOFU-
 * accepted. Stored in the same Room database as chat (chat.db);
 * a single file on device keeps things simple.
 *
 * `fingerprint` is the sshj Buffer-encoded SHA-256 hash of the
 * server's public key, hex-encoded. Using the raw bytes would also
 * work; hex is easier to log and compare visually.
 */
@Entity(
    tableName = "ssh_known_hosts",
    primaryKeys = ["host", "port", "key_type"]
)
data class KnownHostEntity(
    val host: String,
    val port: Int,
    val key_type: String,
    val fingerprint: String,
    val first_seen_ms: Long
)

@Dao
interface KnownHostDao {

    @Query("SELECT * FROM ssh_known_hosts WHERE host = :host AND port = :port AND key_type = :keyType LIMIT 1")
    suspend fun find(host: String, port: Int, keyType: String): KnownHostEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: KnownHostEntity)

    @Query("DELETE FROM ssh_known_hosts WHERE host = :host AND port = :port")
    suspend fun forget(host: String, port: Int)

    @Query("SELECT * FROM ssh_known_hosts ORDER BY first_seen_ms DESC")
    suspend fun all(): List<KnownHostEntity>
}
