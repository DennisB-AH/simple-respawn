package nl.babingo

import com.google.gson.JsonParser
import net.minecraft.resources.Identifier
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.math.BigInteger
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LivesConfigTest {
	@get:Rule
	val temporaryFolder = TemporaryFolder()

	@Test
	fun `new config uses defaults with regeneration disabled`() {
		val path = temporaryFolder.root.toPath().resolve("simplerespawn.json")
		val config = LivesConfig.load(path)
		assertEquals(LivesConfig(1, BigInteger.ZERO, Identifier.parse("minecraft:totem_of_undying"), 1), config)
		assertFalse(config.regenerationEnabled)
		val json = JsonParser.parseString(Files.readString(path)).asJsonObject
		assertEquals(1, json.get("maxLives").asInt)
		assertEquals(0, json.get("lifeRegenIntervalSeconds").asInt)
		assertEquals("minecraft:totem_of_undying", json.get("reviveItem").asString)
		assertEquals(1, json.get("reviveItemCount").asInt)
	}

	@Test
	fun `missing keys are added while preserving existing values`() {
		val path = temporaryFolder.newFile("config.json").toPath()
		Files.writeString(path, """{"maxLives":3,"lifeRegenIntervalSeconds":60}""")
		assertEquals(LivesConfig(3, BigInteger.valueOf(60)), LivesConfig.load(path))
		val json = JsonParser.parseString(Files.readString(path)).asJsonObject
		assertEquals(60, json.get("lifeRegenIntervalSeconds").asInt)
		assertEquals("minecraft:totem_of_undying", json.get("reviveItem").asString)
		assertEquals(1, json.get("reviveItemCount").asInt)
	}

	@Test
	fun `revive item is configurable`() {
		val path = temporaryFolder.newFile("config.json").toPath()
		Files.writeString(path, """{"maxLives":3,"reviveItem":"minecraft:diamond","reviveItemCount":5}""")
		val config = LivesConfig.load(path)
		assertEquals(Identifier.parse("minecraft:diamond"), config.reviveItem)
		assertEquals(5, config.reviveItemCount)
	}

	@Test
	fun `invalid revive item settings fail explicitly without rewriting config`() {
		val path = temporaryFolder.newFile("config.json").toPath()
		for (entry in listOf(
			""""reviveItem":"Not Valid"""", """"reviveItem":5""", """"reviveItem":null""",
			""""reviveItemCount":0""", """"reviveItemCount":-1""", """"reviveItemCount":1.5""",
			""""reviveItemCount":"1"""", """"reviveItemCount":2147483648"""
		)) {
			val content = """{"maxLives":3,"lifeRegenIntervalSeconds":0,$entry}"""
			Files.writeString(path, content)
			assertFailsWith<IllegalStateException> { LivesConfig.load(path) }
			assertEquals(content, Files.readString(path))
		}
	}

	@Test
	fun `zero interval is valid and disables regeneration`() {
		val path = temporaryFolder.newFile("config.json").toPath()
		Files.writeString(path, """{"maxLives":3,"lifeRegenIntervalSeconds":0}""")
		assertFalse(LivesConfig.load(path).regenerationEnabled)
	}

	@Test
	fun `interval has no long-sized maximum`() {
		val path = temporaryFolder.newFile("config.json").toPath()
		for (digits in listOf(100, 2000, 12000)) {
			val interval = BigInteger.TEN.pow(digits)
			Files.writeString(path, """{"maxLives":3,"lifeRegenIntervalSeconds":$interval}""")
			assertEquals(interval, LivesConfig.load(path).lifeRegenIntervalSeconds)
			assertEquals(interval, LivesConfig.load(path).lifeRegenIntervalSeconds)
		}
	}

	@Test
	fun `invalid intervals fail explicitly without rewriting config`() {
		val path = temporaryFolder.newFile("config.json").toPath()
		for (value in listOf("-1", "1.5", "\"10\"", "true", "null", "[]", "{}")) {
			val content = """{"maxLives":3,"lifeRegenIntervalSeconds":$value}"""
			Files.writeString(path, content)
			val exception = assertFailsWith<IllegalStateException> { LivesConfig.load(path) }
			assertTrue(exception.message.orEmpty().contains("Invalid Simple Respawn config"))
			assertEquals(content, Files.readString(path))
		}
	}

	@Test
	fun `invalid caps fail explicitly`() {
		val path = temporaryFolder.newFile("config.json").toPath()
		for (value in listOf("0", "-1", "1.5", "2147483648", "\"3\"", "null")) {
			Files.writeString(path, """{"maxLives":$value}""")
			assertFailsWith<IllegalStateException> { LivesConfig.load(path) }
		}
		Files.writeString(path, "{}")
		assertFailsWith<IllegalStateException> { LivesConfig.load(path) }
	}
}
