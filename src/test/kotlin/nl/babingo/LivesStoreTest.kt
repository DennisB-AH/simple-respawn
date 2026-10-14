package nl.babingo

import com.google.gson.JsonParser
import net.minecraft.world.level.GameType
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class LivesStoreTest {
	@get:Rule
	val temporaryFolder = TemporaryFolder()

	private val config = LivesConfig(3, BigInteger.TEN)
	private val uuid = UUID.fromString("00000000-0000-0000-0000-000000000001")

	private fun load(data: String? = null, now: Long = 100_000): Path {
		val path = temporaryFolder.root.toPath().resolve("lives.json")
		if (data != null) Files.writeString(path, data)
		LivesStore.load(path, config, now)
		return path
	}

	@Test
	fun `new player starts at cap without a timer`() {
		load()
		assertEquals(3, LivesStore.remaining(uuid, config))
		assertNull(LivesStore.secondsUntilNextLife(uuid, config, 100_000))
	}

	@Test
	fun `legacy numeric and object data migrate without retroactive lives`() {
		val other = UUID.fromString("00000000-0000-0000-0000-000000000002")
		val path = load("""{"$uuid":1,"$other":{"remainingLives":0,"gameModeBeforeElimination":"CREATIVE"}}""")
		assertEquals(1, LivesStore.remaining(uuid, config))
		assertEquals(BigInteger.TEN, LivesStore.secondsUntilNextLife(uuid, config, 100_000))
		assertEquals(0, LivesStore.remaining(other, config))
		val json = JsonParser.parseString(Files.readString(path)).asJsonObject
		assertEquals(100_000, json.getAsJsonObject(uuid.toString()).get("regenerationStartedAt").asLong)
		assertEquals("CREATIVE", json.getAsJsonObject(other.toString()).get("gameModeBeforeElimination").asString)
	}

	@Test
	fun `offline and restart catch up retain saved mode until restoration`() {
		val path = load()
		LivesStore.set(uuid, 0, GameType.CREATIVE, config, 100_000)
		LivesStore.load(path, config, 125_000)
		assertEquals(2, LivesStore.remaining(uuid, config))
		assertEquals(BigInteger.valueOf(5), LivesStore.secondsUntilNextLife(uuid, config, 125_000))
		assertEquals(GameType.CREATIVE, LivesStore.restorationGameMode(uuid, false))
		LivesStore.load(path, config, 126_000)
		assertEquals(GameType.CREATIVE, LivesStore.restorationGameMode(uuid, true))
		LivesStore.markRestored(uuid)
		assertNull(LivesStore.restorationGameMode(uuid, false))
		assertFalse(JsonParser.parseString(Files.readString(path)).asJsonObject
			.getAsJsonObject(uuid.toString()).has("gameModeBeforeElimination"))
	}

	@Test
	fun `zero life player revives after one interval`() {
		load()
		LivesStore.set(uuid, 0, GameType.ADVENTURE, config, 100_000)
		assertNull(LivesStore.restorationGameMode(uuid, true))
		assertEquals(emptyMap(), LivesStore.regenerate(config, 109_000))
		assertEquals(mapOf(uuid to 1), LivesStore.regenerate(config, 110_000))
		assertEquals(GameType.ADVENTURE, LivesStore.restorationGameMode(uuid, true))
	}

	@Test
	fun `repeated death keeps timer and original elimination mode`() {
		load()
		assertEquals(2, LivesStore.loseLife(uuid, GameType.CREATIVE, config, 100_000))
		assertEquals(1, LivesStore.loseLife(uuid, GameType.CREATIVE, config, 103_000))
		assertEquals(0, LivesStore.loseLife(uuid, GameType.CREATIVE, config, 105_000))
		assertEquals(BigInteger.valueOf(5), LivesStore.secondsUntilNextLife(uuid, config, 105_000))
		LivesStore.regenerate(config, 110_000)
		// A regained life can be lost before death-screen revival has completed.
		assertEquals(0, LivesStore.loseLife(uuid, GameType.SPECTATOR, config, 111_000))
		LivesStore.regenerate(config, 120_000)
		assertEquals(GameType.CREATIVE, LivesStore.restorationGameMode(uuid, true))
	}

	@Test
	fun `manual revival preserves mode and partial countdown`() {
		load()
		LivesStore.set(uuid, 0, GameType.CREATIVE, config, 100_000)
		LivesStore.grant(uuid, 1, config, 105_000)
		assertEquals(GameType.CREATIVE, LivesStore.restorationGameMode(uuid, true))
		assertEquals(BigInteger.valueOf(5), LivesStore.secondsUntilNextLife(uuid, config, 105_000))
		LivesStore.set(uuid, 3, GameType.SPECTATOR, config, 106_000)
		assertNull(LivesStore.secondsUntilNextLife(uuid, config, 106_000))
		assertEquals(GameType.CREATIVE, LivesStore.restorationGameMode(uuid, true))
	}

	@Test
	fun `grant and set cannot exceed cap and fail without mutation`() {
		val path = load()
		LivesStore.set(uuid, 2, GameType.SURVIVAL, config, 100_000)
		val before = Files.readString(path)
		assertFailsWith<IllegalArgumentException> { LivesStore.grant(uuid, 2, config, 101_000) }
		assertFailsWith<IllegalArgumentException> { LivesStore.grant(uuid, Int.MAX_VALUE, config, 101_000) }
		assertFailsWith<IllegalArgumentException> { LivesStore.set(uuid, 4, GameType.SURVIVAL, config, 101_000) }
		assertEquals(2, LivesStore.remaining(uuid, config))
		assertEquals(before, Files.readString(path))
		LivesStore.grant(uuid, 1, config, 101_000)
		assertEquals(3, LivesStore.remaining(uuid, config))
		assertNull(LivesStore.secondsUntilNextLife(uuid, config, 101_000))
	}

	@Test
	fun `lowered cap persists clamped life count`() {
		val path = load("""{"$uuid":{"remainingLives":5,"regenerationStartedAt":90000}}""")
		assertEquals(3, LivesStore.remaining(uuid, config))
		assertNull(LivesStore.secondsUntilNextLife(uuid, config, 100_000))
		LivesStore.load(path, LivesConfig(1, BigInteger.TEN), 101_000)
		assertEquals(1, LivesStore.remaining(uuid, LivesConfig(1)))
	}

	@Test
	fun `no due intervals cause no persistence changes`() {
		val path = load("""{"$uuid":1}""")
		val before = Files.readString(path)
		assertEquals(emptyMap(), LivesStore.regenerate(config, 109_000))
		assertEquals(before, Files.readString(path))
	}

	@Test
	fun `legacy zero lives without a saved mode use survival`() {
		load("""{"$uuid":0}""")
		LivesStore.regenerate(config, 110_000)
		assertEquals(GameType.SURVIVAL, LivesStore.restorationGameMode(uuid, true))
	}

	@Test
	fun `invalid player records fail rather than reset data`() {
		val path = temporaryFolder.root.toPath().resolve("lives.json")
		for (data in listOf(
			"""{"$uuid":-1}""",
			"""{"$uuid":{"remainingLives":1,"regenerationStartedAt":-1}}""",
			"""{"$uuid":{"remainingLives":1,"regenerationStartedAt":1.5}}""",
			"""{"$uuid":{"remainingLives":1,"gameModeBeforeElimination":"INVALID"}}"""
		)) {
			Files.writeString(path, data)
			assertFailsWith<IllegalStateException> { LivesStore.load(path, config, 100_000) }
			assertEquals(data, Files.readString(path))
		}
	}

	@Test
	fun `revive only restores eliminated players with one life`() {
		load()
		assertFailsWith<IllegalArgumentException> { LivesStore.revive(uuid, config, 100_000) }
		LivesStore.set(uuid, 0, GameType.ADVENTURE, config, 100_000)
		LivesStore.revive(uuid, config, 105_000)
		assertEquals(1, LivesStore.remaining(uuid, config))
		assertEquals(GameType.ADVENTURE, LivesStore.restorationGameMode(uuid, true))
		assertEquals(BigInteger.valueOf(5), LivesStore.secondsUntilNextLife(uuid, config, 105_000))
	}

	@Test
	fun `disabled regeneration keeps players eliminated and drops saved timers`() {
		val disabled = LivesConfig(3, BigInteger.ZERO)
		val path = temporaryFolder.root.toPath().resolve("lives.json")
		Files.writeString(path, """{"$uuid":{"remainingLives":0,"regenerationStartedAt":1000}}""")
		LivesStore.load(path, disabled, Long.MAX_VALUE)
		assertEquals(0, LivesStore.remaining(uuid, disabled))
		assertEquals(emptyMap(), LivesStore.regenerate(disabled, Long.MAX_VALUE))
		assertNull(LivesStore.secondsUntilNextLife(uuid, disabled, Long.MAX_VALUE))
		assertFalse(JsonParser.parseString(Files.readString(path)).asJsonObject
			.getAsJsonObject(uuid.toString()).has("regenerationStartedAt"))
		LivesStore.revive(uuid, disabled, 100_000)
		assertEquals(1, LivesStore.remaining(uuid, disabled))
		assertEquals(0, LivesStore.loseLife(uuid, GameType.SURVIVAL, disabled, 100_000))
		assertNull(LivesStore.secondsUntilNextLife(uuid, disabled, 100_000))
	}
}
