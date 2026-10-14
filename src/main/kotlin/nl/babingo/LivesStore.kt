package nl.babingo

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.minecraft.world.level.GameType
import java.io.IOException
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

internal object LivesStore {
	private data class PlayerLives(
		val regeneration: LifeRegeneration,
		val gameModeBeforeElimination: GameType? = null
	)

	private val livesByPlayer = mutableMapOf<UUID, PlayerLives>()
	private var dataFile: Path? = null

	fun load(file: Path, config: LivesConfig, now: Long) {
		val loaded = mutableMapOf<UUID, PlayerLives>()
		if (Files.exists(file)) {
			try {
				val json = JsonParser.parseString(Files.readString(file)).asJsonObject
				for ((uuidText, value) in json.entrySet()) {
					val uuid = UUID.fromString(uuidText)
					val playerLives = if (value.isJsonPrimitive && value.asJsonPrimitive.isNumber) {
						PlayerLives(LifeRegeneration(value.asBigDecimal.intValueExact()))
					} else {
						require(value.isJsonObject) { "Player data for $uuidText must be an object." }
						val playerData = value.asJsonObject
						val count = playerData.get("remainingLives")
						require(count != null && count.isJsonPrimitive && count.asJsonPrimitive.isNumber) {
							"Life count for $uuidText must be an integer."
						}
						val modeValue = playerData.get("gameModeBeforeElimination")
						val mode = if (modeValue == null || modeValue.isJsonNull) {
							null
						} else {
							require(modeValue.isJsonPrimitive && modeValue.asJsonPrimitive.isString) {
								"Saved gamemode for $uuidText must be a string."
							}
							GameType.valueOf(modeValue.asString)
						}
						val timerValue = playerData.get("regenerationStartedAt")
						val startedAt = if (timerValue == null || timerValue.isJsonNull) {
							null
						} else {
							require(timerValue.isJsonPrimitive && timerValue.asJsonPrimitive.isNumber) {
								"Regeneration baseline for $uuidText must be an integer."
							}
							timerValue.asBigDecimal.longValueExact().also {
								require(it >= 0) { "Regeneration baseline for $uuidText cannot be negative." }
							}
						}
						PlayerLives(LifeRegeneration(count.asBigDecimal.intValueExact(), startedAt), mode)
					}
					require(playerLives.regeneration.remaining >= 0) { "Life count for $uuidText cannot be negative." }
					loaded[uuid] = playerLives.copy(
						regeneration = playerLives.regeneration.regenerate(config, now)
					)
				}
			} catch (exception: IOException) {
				throw IllegalStateException("Could not read player lives from $file.", exception)
			} catch (exception: RuntimeException) {
				throw IllegalStateException("Invalid player lives data in $file.", exception)
			}
		}

		livesByPlayer.clear()
		livesByPlayer.putAll(loaded)
		dataFile = file
		if (loaded.isNotEmpty()) save()
	}

	fun remaining(uuid: UUID, config: LivesConfig): Int {
		val existing = livesByPlayer[uuid]
		if (existing == null) {
			livesByPlayer[uuid] = PlayerLives(LifeRegeneration(config.maxLives))
			save()
			return config.maxLives
		}
		return existing.regeneration.remaining
	}

	fun loseLife(uuid: UUID, gameMode: GameType, config: LivesConfig, now: Long): Int {
		val current = livesByPlayer[uuid] ?: PlayerLives(LifeRegeneration(config.maxLives))
		if (current.regeneration.remaining > 0) {
			val remaining = current.regeneration.remaining - 1
			livesByPlayer[uuid] = PlayerLives(
				current.regeneration.withLives(remaining, config.maxLives, now, config.regenerationEnabled),
				if (remaining == 0) current.gameModeBeforeElimination ?: playableGameMode(gameMode)
				else current.gameModeBeforeElimination
			)
			save()
			return remaining
		}
		return current.regeneration.remaining
	}

	fun grant(uuid: UUID, amount: Int, config: LivesConfig, now: Long) {
		val current = livesByPlayer[uuid] ?: PlayerLives(LifeRegeneration(config.maxLives))
		require(amount > 0 && amount <= config.maxLives - current.regeneration.remaining) {
			"Grant would exceed maxLives (${config.maxLives})."
		}
		livesByPlayer[uuid] = current.copy(
			regeneration = current.regeneration.withLives(current.regeneration.remaining + amount, config.maxLives, now, config.regenerationEnabled)
		)
		save()
	}

	fun set(uuid: UUID, amount: Int, gameMode: GameType, config: LivesConfig, now: Long) {
		require(amount in 0..config.maxLives) { "Lives must be between zero and ${config.maxLives}." }
		val current = livesByPlayer[uuid] ?: PlayerLives(LifeRegeneration(config.maxLives))
		if (amount == 0) {
			livesByPlayer[uuid] = PlayerLives(
				current.regeneration.withLives(0, config.maxLives, now, config.regenerationEnabled),
				current.gameModeBeforeElimination ?: playableGameMode(gameMode)
			)
		} else {
			livesByPlayer[uuid] = current.copy(
				regeneration = current.regeneration.withLives(amount, config.maxLives, now, config.regenerationEnabled)
			)
		}
		save()
	}

	fun revive(uuid: UUID, config: LivesConfig, now: Long) {
		val current = livesByPlayer[uuid] ?: PlayerLives(LifeRegeneration(config.maxLives))
		require(current.regeneration.remaining == 0) { "Only eliminated players can be revived." }
		livesByPlayer[uuid] = current.copy(
			regeneration = current.regeneration.withLives(1, config.maxLives, now, config.regenerationEnabled)
		)
		save()
	}

	fun regenerate(config: LivesConfig, now: Long): Map<UUID, Int> {
		val gainedByPlayer = mutableMapOf<UUID, Int>()
		var changed = false
		for ((uuid, current) in livesByPlayer) {
			val regenerated = current.regeneration.regenerate(config, now)
			if (regenerated != current.regeneration) {
				livesByPlayer[uuid] = current.copy(regeneration = regenerated)
				val gained = regenerated.remaining - current.regeneration.remaining
				if (gained > 0) gainedByPlayer[uuid] = gained
				changed = true
			}
		}
		if (changed) save()
		return gainedByPlayer
	}

	fun secondsUntilNextLife(uuid: UUID, config: LivesConfig, now: Long): BigInteger? {
		return livesByPlayer[uuid]?.regeneration?.secondsUntilNextLife(config, now)
	}

	fun restorationGameMode(uuid: UUID, isSpectator: Boolean): GameType? {
		val current = livesByPlayer[uuid] ?: return null
		if (current.regeneration.remaining == 0) return null
		val savedMode = current.gameModeBeforeElimination
		return if (savedMode != null || isSpectator) playableGameMode(savedMode ?: GameType.SURVIVAL) else null
	}

	fun markRestored(uuid: UUID) {
		val current = livesByPlayer[uuid] ?: error("No lives data for restored player $uuid.")
		if (current.gameModeBeforeElimination != null) {
			livesByPlayer[uuid] = current.copy(gameModeBeforeElimination = null)
			save()
		}
	}

	private fun playableGameMode(mode: GameType): GameType {
		return mode.takeUnless { it == GameType.SPECTATOR } ?: GameType.SURVIVAL
	}

	private fun save() {
		val file = dataFile ?: error("Player lives data has not been loaded for this server.")
		val json = JsonObject()
		for ((uuid, lives) in livesByPlayer) {
			val playerData = JsonObject().apply {
				addProperty("remainingLives", lives.regeneration.remaining)
				lives.regeneration.regenerationStartedAt?.let { addProperty("regenerationStartedAt", it) }
				lives.gameModeBeforeElimination?.let { addProperty("gameModeBeforeElimination", it.name) }
			}
			json.add(uuid.toString(), playerData)
		}
		try {
			Files.createDirectories(file.parent)
			Files.writeString(file, json.toString() + System.lineSeparator())
		} catch (exception: IOException) {
			throw IllegalStateException("Could not save player lives to $file.", exception)
		}
	}
}
