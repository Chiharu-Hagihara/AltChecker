package com.chiharuhagihara.altChecker

import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.plugin.java.JavaPlugin
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import java.lang.Runnable
import java.util.logging.Level

class AltChecker : JavaPlugin(), Listener, CommandExecutor {

    private var mysqlManager: MySQLManager? = null

    private val staffPermission = "altchecker.staff"

    override fun onEnable() {
        saveDefaultConfig()

        val host = config.getString("mysql.host", "127.0.0.1") ?: "127.0.0.1"
        val port = config.getString("mysql.port", "3306") ?: "3306"
        val database = config.getString("mysql.database", "minecraft") ?: "minecraft"
        val username = config.getString("mysql.username", "root") ?: "root"
        val password = config.getString("mysql.password", "") ?: ""

        mysqlManager = MySQLManager(this, host, username, password, port, database)
        if (mysqlManager?.isConnected != true) {
            logger.log(Level.SEVERE, "MySQLに接続できません。AltCheckerの機能は利用できません。")
        }

        server.pluginManager.registerEvents(this, this)
        getCommand("altcheck")?.setExecutor(this)
    }

    override fun onDisable() {
        mysqlManager?.close()
    }

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player
        val ipAddress = normalizeIp(player.address?.address?.hostAddress) ?: return
        val manager = mysqlManager ?: return

        server.scheduler.runTaskAsynchronously(this, Runnable {
            manager.upsertPlayerIp(player.uniqueId.toString(), player.name, ipAddress)
            val relatedPlayers = manager.findPlayersByIp(ipAddress)

            if (relatedPlayers.size > 1) {
                notifyStaff(
                    "[ログイン] ${player.name} と同じIP ($ipAddress) のプレイヤー: ${relatedPlayers.joinToString(", ")}",
                    ignorePlayerName = null
                )
            }
        })
    }

    override fun onCommand(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>
    ): Boolean {
        if (command.name != "altcheck") {
            return false
        }

        if (!sender.hasPermission(staffPermission)) {
            sender.sendMessage(prefixedMessage("権限がありません。", NamedTextColor.RED))
            return true
        }

        if (args.isEmpty()) {
            sender.sendMessage(prefixedMessage("使用方法: /altcheck <プレイヤー名|IP>", NamedTextColor.YELLOW))
            return true
        }

        val query = args[0]
        val manager = mysqlManager
        if (manager == null || !manager.isConnected) {
            sender.sendMessage(prefixedMessage("MySQLに接続されていません。", NamedTextColor.RED))
            return true
        }

        server.scheduler.runTaskAsynchronously(this, Runnable {
            val ipAddress = if (isIpQuery(query)) {
                normalizeIp(query)
            } else {
                manager.findIpByPlayerName(query)?.let { normalizeIp(it) }
            }

            if (ipAddress == null) {
                server.scheduler.runTask(this, Runnable {
                    sender.sendMessage(prefixedMessage("'$query' のIPデータが見つかりません。", NamedTextColor.RED))
                })
                return@Runnable
            }

            val relatedPlayers = manager.findPlayersByIp(ipAddress)
            server.scheduler.runTask(this, Runnable {
                if (relatedPlayers.isEmpty()) {
                    sender.sendMessage(prefixedMessage("IP: $ipAddress に一致するプレイヤーが見つかりません。", NamedTextColor.RED))
                    return@Runnable
                }

                sender.sendMessage(
                    prefixedMessage(
                        "IP: $ipAddress | プレイヤー: ${relatedPlayers.joinToString(", ")}",
                        NamedTextColor.AQUA
                    )
                )
                notifyStaff(
                    "[検索] ${sender.name} が '$query' を検索 -> $ipAddress: ${relatedPlayers.joinToString(", ")}",
                    ignorePlayerName = sender.name
                )
            })
        })

        return true
    }

    private fun notifyStaff(message: String, ignorePlayerName: String?) {
        val formatted = prefixedMessage(message)
        Bukkit.getOnlinePlayers()
            .filter { it.hasPermission(staffPermission) }
            .filter { ignorePlayerName == null || !it.name.equals(ignorePlayerName, ignoreCase = true) }
            .forEach { it.sendMessage(formatted) }
        logger.info(message)
    }

    private fun prefixedMessage(message: String, color: NamedTextColor = NamedTextColor.WHITE): Component {
        return Component.text("[AltChecker] ", NamedTextColor.GOLD)
            .append(Component.text(message, color))
    }

    private fun isIpQuery(input: String): Boolean {
        return input.contains('.') || input.contains(':')
    }

    private fun normalizeIp(input: String?): String? {
        if (input == null) {
            return null
        }

        val value = input.trim().lowercase()
        if (value.startsWith("::ffff:")) {
            return value.removePrefix("::ffff:")
        }
        return value.ifEmpty { null }
    }
}
