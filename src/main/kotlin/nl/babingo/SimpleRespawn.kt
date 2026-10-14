package nl.babingo

import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.EntityArgument
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.permissions.Permissions
import net.minecraft.world.item.Item
import net.minecraft.world.item.Items
import net.minecraft.world.level.GameType
import net.minecraft.world.level.portal.TeleportTransition
import net.minecraft.world.level.storage.LevelResource
import org.slf4j.LoggerFactory
import java.time.Instant

object SimpleRespawn : ModInitializer {
	const val MOD_ID: String = "simplerespawn"

	private val LOGGER = LoggerFactory.getLogger(MOD_ID)

	override fun onInitialize() {
		val config = LivesConfig.load()

		var lastRegenerationCheck: Long? = null
		var reviveItem: Item? = null
		// Resolve at server start so items registered by other mods are available.
		ServerLifecycleEvents.SERVER_STARTING.register { _ ->
			reviveItem = resolveReviveItem(config)
		}
		ServerLifecycleEvents.SERVER_STARTED.register { server ->
			val now = Instant.now().toEpochMilli()
			LivesStore.load(server.getWorldPath(LevelResource.ROOT).resolve("simplerespawn-lives.json"), config, now)
			lastRegenerationCheck = now / 1000
		}
		ServerTickEvents.END_SERVER_TICK.register { server ->
			val now = Instant.now().toEpochMilli()
			if (now / 1000 != lastRegenerationCheck) {
				regenerateLives(server, config, now)
				lastRegenerationCheck = now / 1000
			}
		}
		ServerPlayerEvents.JOIN.register { player ->
			regenerateLives(player.level().server, config, Instant.now().toEpochMilli())
			val lives = LivesStore.remaining(player.uuid, config)
			if (lives == 0) {
				player.setGameMode(GameType.SPECTATOR)
			} else {
				restoreIfNeeded(player)
			}
		}
		ServerLivingEntityEvents.AFTER_DEATH.register { entity, _ ->
			if (entity is ServerPlayer) {
				val now = Instant.now().toEpochMilli()
				regenerateLives(entity.level().server, config, now)
				val lives = LivesStore.loseLife(entity.uuid, entity.gameMode.gameModeForPlayer, config, now)
				entity.sendSystemMessage(
					Component.literal("You have $lives ${if (lives == 1) "life" else "lives"} remaining.")
				)
			}
		}
		ServerPlayerEvents.AFTER_RESPAWN.register { _, newPlayer, _ ->
			regenerateLives(newPlayer.level().server, config, Instant.now().toEpochMilli())
			if (LivesStore.remaining(newPlayer.uuid, config) == 0) {
				newPlayer.setGameMode(GameType.SPECTATOR)
			} else {
				restoreIfNeeded(newPlayer)
			}
		}
		CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
			dispatcher.register(
				Commands.literal("revive")
					.then(
						Commands.argument("player", EntityArgument.player())
							.executes { context ->
								val reviver = context.source.playerOrException
								val target = EntityArgument.getPlayer(context, "player")
								val item = reviveItem ?: error("Revive item has not been resolved.")
								val now = Instant.now().toEpochMilli()
								regenerateLives(context.source.server, config, now)
								fun fail(message: String): Nothing =
									throw SimpleCommandExceptionType(Component.literal(message)).create()
								if (target.uuid == reviver.uuid) fail("You cannot revive yourself.")
								if (!reviver.isAlive || LivesStore.remaining(reviver.uuid, config) == 0) {
									fail("You must be alive to revive another player.")
								}
								if (LivesStore.remaining(target.uuid, config) != 0) {
									fail("${target.name.string} is not eliminated.")
								}
								val stack = reviver.mainHandItem
								val itemName = "${config.reviveItemCount}x ${item.getName(item.defaultInstance).string}"
								if (stack.item != item || stack.count < config.reviveItemCount) {
									fail("Hold $itemName in your main hand to revive a player.")
								}
								stack.shrink(config.reviveItemCount)
								LivesStore.revive(target.uuid, config, now)
								restoreIfNeeded(target)
								target.sendSystemMessage(Component.literal(
									"${reviver.name.string} sacrificed $itemName to revive you. You have 1 life remaining."
								))
								context.source.sendSuccess(
									{ Component.literal("Revived ${target.name.string} by sacrificing $itemName.") },
									true
								)
								1
							}
					)
			)
			dispatcher.register(
				Commands.literal("lives")
					.executes { context ->
						val player = context.source.playerOrException
						val now = Instant.now().toEpochMilli()
						regenerateLives(context.source.server, config, now)
						val lives = LivesStore.remaining(player.uuid, config)
						val countdown = LivesStore.secondsUntilNextLife(player.uuid, config, now)
						val timer = countdown?.let { " Next life in ${formatLifeCountdown(it)}." } ?: ""
						player.sendSystemMessage(Component.literal("You have $lives ${if (lives == 1) "life" else "lives"} remaining.$timer"))
						1
					}
					.then(
						Commands.literal("grant")
							.requires { it.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER) }
							.then(
								Commands.argument("player", EntityArgument.player())
									.then(
										Commands.argument("amount", IntegerArgumentType.integer(1, config.maxLives))
											.executes { context ->
												val player = EntityArgument.getPlayer(context, "player")
												val amount = IntegerArgumentType.getInteger(context, "amount")
												val now = Instant.now().toEpochMilli()
												regenerateLives(context.source.server, config, now)
												if (amount > config.maxLives - LivesStore.remaining(player.uuid, config)) {
													throw SimpleCommandExceptionType(
														Component.literal("Cannot exceed maxLives (${config.maxLives}).")
													).create()
												}
												LivesStore.grant(player.uuid, amount, config, now)
												restoreIfNeeded(player)
												context.source.sendSuccess(
													{ Component.literal("Granted $amount lives to ${player.name.string}.") },
													true
												)
												1
											}
									)
							)
					)
					.then(
						Commands.literal("set")
							.requires { it.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER) }
							.then(
								Commands.argument("player", EntityArgument.player())
									.then(
										Commands.argument("amount", IntegerArgumentType.integer(0, config.maxLives))
											.executes { context ->
												val player = EntityArgument.getPlayer(context, "player")
												val amount = IntegerArgumentType.getInteger(context, "amount")
												val now = Instant.now().toEpochMilli()
												regenerateLives(context.source.server, config, now)
												LivesStore.set(player.uuid, amount, player.gameMode.gameModeForPlayer, config, now)
												if (amount == 0) {
													player.setGameMode(GameType.SPECTATOR)
												} else {
													restoreIfNeeded(player)
												}
												context.source.sendSuccess(
													{ Component.literal("Set ${player.name.string}'s lives to $amount.") },
													true
												)
												1
											}
									)
							)
					)
			)
		}

		if (config.regenerationEnabled) {
			LOGGER.info(
				"Loaded Simple Respawn with a cap of {} lives and regeneration every {} seconds.",
				config.maxLives, config.lifeRegenIntervalSeconds
			)
		} else {
			LOGGER.info("Loaded Simple Respawn with a cap of {} lives and regeneration disabled.", config.maxLives)
		}
	}

	private fun resolveReviveItem(config: LivesConfig): Item {
		val item = BuiltInRegistries.ITEM.getOptional(config.reviveItem).orElseThrow {
			IllegalStateException("Invalid Simple Respawn config: unknown 'reviveItem' ${config.reviveItem}.")
		}
		check(item != Items.AIR) { "Invalid Simple Respawn config: 'reviveItem' cannot be air." }
		check(config.reviveItemCount <= item.defaultMaxStackSize) {
			"Invalid Simple Respawn config: 'reviveItemCount' (${config.reviveItemCount}) exceeds the max stack size " +
				"of ${config.reviveItem} (${item.defaultMaxStackSize})."
		}
		return item
	}

	private fun regenerateLives(server: MinecraftServer, config: LivesConfig, now: Long) {
		for ((uuid, gained) in LivesStore.regenerate(config, now)) {
			val player = server.playerList.getPlayer(uuid) ?: continue
			val lives = LivesStore.remaining(uuid, config)
			player.sendSystemMessage(Component.literal(
				"You regenerated $gained ${if (gained == 1) "life" else "lives"}. You have $lives remaining."
			))
			restoreIfNeeded(player)
		}
	}

	private fun restoreIfNeeded(player: ServerPlayer) {
		// A life can regenerate on the death screen; normal respawn will finish revival.
		if (!player.isAlive) return
		val mode = LivesStore.restorationGameMode(player.uuid, player.isSpectator) ?: return
		respawn(player, mode)
		LivesStore.markRestored(player.uuid)
	}

	private fun respawn(player: ServerPlayer, gameMode: GameType) {
		// Revival is not another death, so do not consume a second respawn anchor charge.
		val transition = player.findRespawnPositionAndUseSpawnBlock(false, TeleportTransition.DO_NOTHING)
		player.teleport(transition)
		player.setGameMode(gameMode)
	}
}
