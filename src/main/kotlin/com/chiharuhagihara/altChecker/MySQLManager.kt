package com.chiharuhagihara.altChecker

import org.bukkit.plugin.java.JavaPlugin
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.util.logging.Level

class MySQLManager(
    private val plugin: JavaPlugin,
    private val host: String,
    private val user: String,
    private val pass: String,
    private val port: String,
    private val db: String
) {
    private var connection: Connection? = null

    init {
        this.connect()
        this.createTableIfNeeded()
        this.migrateLegacySchemaIfNeeded()
    }

    fun connect(): Boolean {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver")

            connection = DriverManager.getConnection(
                "jdbc:mysql://$host:$port/$db?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                user,
                pass
            )

            plugin.logger.log(Level.INFO, "Connected to MySQL server.")
            return true
        } catch (e: Exception) {
            plugin.logger.log(Level.SEVERE, "Could not connect to MySQL server. ${e.message}")
        }
        return false
    }

    private fun createTableIfNeeded() {
        val sql = """
            CREATE TABLE IF NOT EXISTS player_ips (
                uuid VARCHAR(36) NOT NULL,
                player_name VARCHAR(16) NOT NULL,
                ip_address VARCHAR(45) NOT NULL,
                first_seen TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                last_seen TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                UNIQUE KEY uq_uuid_ip (uuid, ip_address),
                INDEX idx_ip_address (ip_address),
                INDEX idx_player_name_last_seen (player_name, last_seen)
            )
        """.trimIndent()

        try {
            connection?.createStatement()?.use { statement ->
                statement.execute(sql)
            }
        } catch (e: SQLException) {
            plugin.logger.log(Level.SEVERE, "Could not initialize player_ips table. ${e.message}")
        }
    }

    private fun migrateLegacySchemaIfNeeded() {
        val alterAddColumns = """
            ALTER TABLE player_ips
            ADD COLUMN IF NOT EXISTS first_seen TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            ADD COLUMN IF NOT EXISTS last_seen TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
        """.trimIndent()

        val addUniqueIndex = "CREATE UNIQUE INDEX uq_uuid_ip ON player_ips (uuid, ip_address)"

        val dropLegacyPrimary = "ALTER TABLE player_ips DROP PRIMARY KEY"

        val backfillSeenFromLegacy = """
            UPDATE player_ips
            SET
                first_seen = COALESCE(first_seen, updated_at, CURRENT_TIMESTAMP),
                last_seen = COALESCE(last_seen, updated_at, CURRENT_TIMESTAMP)
        """.trimIndent()

        try {
            connection?.createStatement()?.use { statement ->
                statement.execute(alterAddColumns)

                try {
                    statement.execute(dropLegacyPrimary)
                } catch (_: SQLException) {
                    // Legacy primary key may already be removed.
                }

                try {
                    statement.execute(addUniqueIndex)
                } catch (_: SQLException) {
                    // Index may already exist.
                }

                try {
                    statement.execute(backfillSeenFromLegacy)
                } catch (_: SQLException) {
                    // Legacy updated_at may not exist in fresh schema.
                }
            }
        } catch (e: SQLException) {
            plugin.logger.log(Level.SEVERE, "Could not migrate player_ips schema. ${e.message}")
        }
    }

    fun upsertPlayerIp(uuid: String, playerName: String, ipAddress: String): Boolean {
        val updateNameSql = "UPDATE player_ips SET player_name = ? WHERE uuid = ?"
        val sql = """
            INSERT INTO player_ips (uuid, player_name, ip_address)
            VALUES (?, ?, ?)
            ON DUPLICATE KEY UPDATE
                player_name = VALUES(player_name),
                last_seen = CURRENT_TIMESTAMP
        """.trimIndent()

        val updatedNames = executePrepared(updateNameSql) { statement ->
            statement.setString(1, playerName)
            statement.setString(2, uuid)
        }

        val upserted = executePrepared(sql) { statement ->
            statement.setString(1, uuid)
            statement.setString(2, playerName)
            statement.setString(3, ipAddress)
        }

        return updatedNames && upserted
    }

    fun findPlayersByIp(ipAddress: String): List<String> {
        val sql = "SELECT player_name FROM player_ips WHERE ip_address = ? ORDER BY last_seen DESC"
        return queryPrepared(sql, { statement ->
            statement.setString(1, ipAddress)
        }) { rs ->
            val names = mutableListOf<String>()
            while (rs.next()) {
                names.add(rs.getString("player_name"))
            }
            names.distinct()
        } ?: emptyList()
    }

    fun findIpsByPlayerName(playerName: String): List<String> {
        val sql = """
            SELECT ip_address, MAX(last_seen) AS latest_seen
            FROM player_ips
            WHERE player_name = ?
            GROUP BY ip_address
            ORDER BY latest_seen DESC
        """.trimIndent()

        return queryPrepared(sql, { statement ->
            statement.setString(1, playerName)
        }) { rs ->
            val ips = mutableListOf<String>()
            while (rs.next()) {
                ips.add(rs.getString("ip_address"))
            }
            ips
        } ?: emptyList()
    }

    fun findPlayersByIps(ipAddresses: List<String>): List<String> {
        if (ipAddresses.isEmpty()) {
            return emptyList()
        }

        val placeholders = ipAddresses.joinToString(",") { "?" }
        val sql = """
            SELECT player_name, MAX(last_seen) AS latest_seen
            FROM player_ips
            WHERE ip_address IN ($placeholders)
            GROUP BY player_name
            ORDER BY latest_seen DESC
        """.trimIndent()

        return queryPrepared(sql, { statement ->
            ipAddresses.forEachIndexed { index, ip ->
                statement.setString(index + 1, ip)
            }
        }) { rs ->
            val players = mutableListOf<String>()
            while (rs.next()) {
                players.add(rs.getString("player_name"))
            }
            players
        } ?: emptyList()
    }

    private fun executePrepared(
        sql: String,
        bind: (PreparedStatement) -> Unit
    ): Boolean {
        val conn = connection
        if (conn == null) {
            plugin.logger.log(Level.SEVERE, "MySQL connection is not available.")
            return false
        }

        return try {
            conn.prepareStatement(sql).use { statement ->
                bind(statement)
                statement.executeUpdate()
            }
            true
        } catch (e: SQLException) {
            plugin.logger.log(Level.SEVERE, "SQL execute error. ${e.message}")
            false
        }
    }

    private fun <T> queryPrepared(
        sql: String,
        bind: (PreparedStatement) -> Unit,
        read: (ResultSet) -> T
    ): T? {
        val conn = connection
        if (conn == null) {
            plugin.logger.log(Level.SEVERE, "MySQL connection is not available.")
            return null
        }

        return try {
            conn.prepareStatement(sql).use { statement ->
                bind(statement)
                statement.executeQuery().use(read)
            }
        } catch (e: SQLException) {
            plugin.logger.log(Level.SEVERE, "SQL query error. ${e.message}")
            null
        }
    }

    val isConnected: Boolean
        get() = connection != null

    fun close() {
        try {
            connection?.close()
            connection = null
        } catch (e: SQLException) {
            plugin.logger.log(Level.SEVERE, "Could not close MySQL connection. ${e.message}")
        }
    }
}