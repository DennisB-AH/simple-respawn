package nl.babingo

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.jsonObject
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.resources.Identifier
import java.io.IOException
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path

internal data class LivesConfig(
	val maxLives: Int,
	// Zero disables life regeneration.
	val lifeRegenIntervalSeconds: BigInteger = BigInteger.valueOf(DEFAULT_REGEN_INTERVAL_SECONDS),
	val reviveItem: Identifier = Identifier.parse(DEFAULT_REVIVE_ITEM),
	val reviveItemCount: Int = DEFAULT_REVIVE_ITEM_COUNT
) {
	init {
		require(maxLives > 0) { "'maxLives' must be greater than zero." }
		require(lifeRegenIntervalSeconds.signum() >= 0) { "'lifeRegenIntervalSeconds' cannot be negative." }
		require(reviveItemCount > 0) { "'reviveItemCount' must be greater than zero." }
	}

	val regenerationEnabled: Boolean
		get() = lifeRegenIntervalSeconds.signum() > 0

	companion object {
		private val jsonFormat = Json { prettyPrint = true }

		@OptIn(ExperimentalSerializationApi::class)
		fun load(path: Path = FabricLoader.getInstance().configDir.resolve("simplerespawn.json")): LivesConfig {
			if (Files.notExists(path)) {
				write(path, JsonObject(defaults()))
			}

			try {
				val json = jsonFormat.parseToJsonElement(Files.readString(path)).jsonObject
				val value = json["maxLives"]
				require(value is JsonPrimitive && !value.isString) {
					"'maxLives' must be a positive integer."
				}
				val interval = json["lifeRegenIntervalSeconds"]?.let { intervalValue ->
					require(intervalValue is JsonPrimitive && !intervalValue.isString) {
						"'lifeRegenIntervalSeconds' must be a non-negative integer."
					}
					intervalValue.content.toBigDecimal().toBigIntegerExact()
				} ?: BigInteger.valueOf(DEFAULT_REGEN_INTERVAL_SECONDS)
				val reviveItem = json["reviveItem"]?.let { itemValue ->
					require(itemValue is JsonPrimitive && itemValue.isString) {
						"'reviveItem' must be an item id string."
					}
					requireNotNull(Identifier.tryParse(itemValue.content)) {
						"'reviveItem' must be a valid item id."
					}
				} ?: Identifier.parse(DEFAULT_REVIVE_ITEM)
				val reviveItemCount = json["reviveItemCount"]?.let { countValue ->
					require(countValue is JsonPrimitive && !countValue.isString) {
						"'reviveItemCount' must be a positive integer."
					}
					countValue.content.toBigDecimal().intValueExact()
				} ?: DEFAULT_REVIVE_ITEM_COUNT
				val config = LivesConfig(value.content.toBigDecimal().intValueExact(), interval, reviveItem, reviveItemCount)
				val missing = defaults().filterKeys { it !in json }
				if (missing.isNotEmpty()) {
					// Very large numbers must be written as raw literals; kotlinx would encode them as Infinity.
					write(path, JsonObject(json + missing + mapOf(
						"maxLives" to JsonPrimitive(config.maxLives),
						"lifeRegenIntervalSeconds" to JsonUnquotedLiteral(config.lifeRegenIntervalSeconds.toString()),
						"reviveItemCount" to JsonPrimitive(config.reviveItemCount)
					)))
				}
				return config
			} catch (exception: IOException) {
				throw IllegalStateException("Could not read Simple Respawn config at $path.", exception)
			} catch (exception: RuntimeException) {
				throw IllegalStateException("Invalid Simple Respawn config at $path.", exception)
			}
		}

		private fun defaults(): Map<String, JsonElement> = linkedMapOf(
			"maxLives" to JsonPrimitive(DEFAULT_LIVES),
			"lifeRegenIntervalSeconds" to JsonPrimitive(DEFAULT_REGEN_INTERVAL_SECONDS),
			"reviveItem" to JsonPrimitive(DEFAULT_REVIVE_ITEM),
			"reviveItemCount" to JsonPrimitive(DEFAULT_REVIVE_ITEM_COUNT)
		)

		private fun write(path: Path, config: JsonObject) {
			try {
				Files.createDirectories(path.parent)
				Files.writeString(path, jsonFormat.encodeToString(JsonObject.serializer(), config) + System.lineSeparator())
			} catch (exception: IOException) {
				throw IllegalStateException("Could not write Simple Respawn config at $path.", exception)
			}
		}
	}
}

private const val DEFAULT_LIVES = 1
private const val DEFAULT_REGEN_INTERVAL_SECONDS = 0L
private const val DEFAULT_REVIVE_ITEM = "minecraft:totem_of_undying"
private const val DEFAULT_REVIVE_ITEM_COUNT = 1
