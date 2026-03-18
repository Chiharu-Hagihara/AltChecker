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
                uuid VARCHAR(36) PRIMARY KEY,
                player_name VARCHAR(16) NOT NULL,
                ip_address VARCHAR(45) NOT NULL,
                updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                INDEX idx_ip_address (ip_address),
                INDEX idx_player_name (player_name)
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

    fun upsertPlayerIp(uuid: String, playerName: String, ipAddress: String): Boolean {
        val sql = """
            INSERT INTO player_ips (uuid, player_name, ip_address)
            VALUES (?, ?, ?)
            ON DUPLICATE KEY UPDATE
                player_name = VALUES(player_name),
                ip_address = VALUES(ip_address)
        """.trimIndent()

        return executePrepared(sql) { statement ->
            statement.setString(1, uuid)
            statement.setString(2, playerName)
            statement.setString(3, ipAddress)
        }
    }

    fun findPlayersByIp(ipAddress: String): List<String> {
        val sql = "SELECT player_name FROM player_ips WHERE ip_address = ? ORDER BY updated_at DESC"
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

    fun findIpByPlayerName(playerName: String): String? {
        val sql = "SELECT ip_address FROM player_ips WHERE player_name = ? LIMIT 1"
        return queryPrepared(sql, { statement ->
            statement.setString(1, playerName)
        }) { rs ->
            if (rs.next()) rs.getString("ip_address") else null
        }
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