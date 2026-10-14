package nl.babingo

import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class LifeRegenerationTest {
	private val config = LivesConfig(3, BigInteger.TEN)

	@Test
	fun `timer starts only below the cap`() {
		assertEquals(LifeRegeneration(2, 100_000), LifeRegeneration(3).withLives(2, 3, 100_000))
		assertEquals(LifeRegeneration(3), LifeRegeneration(2, 100_000).withLives(3, 3, 105_000))
	}

	@Test
	fun `regeneration happens at the exact second boundary`() {
		val state = LifeRegeneration(1, 100_000)
		assertEquals(state, state.regenerate(config, 109_999))
		assertEquals(BigInteger.ONE, state.secondsUntilNextLife(config, 109_999))
		assertEquals(LifeRegeneration(2, 110_000), state.regenerate(config, 110_000))
	}

	@Test
	fun `catch up multiple intervals and stop at the cap`() {
		assertEquals(LifeRegeneration(3), LifeRegeneration(0, 100_000).regenerate(config, 135_000))
		assertEquals(LifeRegeneration(3), LifeRegeneration(0, 100_000).regenerate(config, Long.MAX_VALUE))
		assertNull(LifeRegeneration(3).secondsUntilNextLife(config, 135_000))
	}

	@Test
	fun `catch up preserves partial interval progress`() {
		val result = LifeRegeneration(0, 100_000).regenerate(config, 125_000)
		assertEquals(LifeRegeneration(2, 120_000), result)
		assertEquals(BigInteger.valueOf(5), result.secondsUntilNextLife(config, 125_000))
		assertEquals(LifeRegeneration(3), result.regenerate(config, 130_000))
	}

	@Test
	fun `another death or partial grant keeps the countdown`() {
		val state = LifeRegeneration(2, 100_000)
		assertEquals(LifeRegeneration(1, 100_000), state.withLives(1, 3, 105_000))
		assertEquals(LifeRegeneration(2, 100_000), state.withLives(1, 3, 105_000).withLives(2, 3, 108_000))
		assertEquals(LifeRegeneration(2, 110_000), state.withLives(1, 3, 105_000).regenerate(config, 110_000))
	}

	@Test
	fun `reaching cap clears progress and next loss starts a new interval`() {
		val full = LifeRegeneration(2, 100_000).regenerate(config, 110_000)
		assertEquals(LifeRegeneration(2, 200_000), full.withLives(2, 3, 200_000))
	}

	@Test
	fun `legacy below-cap records begin counting at load time`() {
		assertEquals(LifeRegeneration(0, 100_000), LifeRegeneration(0).regenerate(config, 100_000))
	}

	@Test
	fun `lowered cap clamps lives and clears the timer`() {
		assertEquals(LifeRegeneration(3), LifeRegeneration(5, 100_000).regenerate(config, 105_000))
	}

	@Test
	fun `increased cap begins a countdown for previously full players`() {
		assertEquals(LifeRegeneration(2, 100_000), LifeRegeneration(2).regenerate(config, 100_000))
	}

	@Test
	fun `backward clock does not grant lives or discard progress`() {
		val state = LifeRegeneration(1, 100_000)
		assertEquals(state, state.regenerate(config, 90_000))
		assertEquals(BigInteger.valueOf(20), state.secondsUntilNextLife(config, 90_000))
		assertEquals(LifeRegeneration(2, 110_000), state.regenerate(config, 110_000))
	}

	@Test
	fun `intervals larger than long are supported without overflow`() {
		val interval = BigInteger.TEN.pow(100)
		val hugeConfig = LivesConfig(3, interval)
		val state = LifeRegeneration(0, 100_000)
		assertEquals(state, state.regenerate(hugeConfig, Long.MAX_VALUE))
		val elapsedWholeSeconds = BigInteger.valueOf(Long.MAX_VALUE).subtract(BigInteger.valueOf(100_000))
			.divide(BigInteger.valueOf(1000))
		assertEquals(
			interval.subtract(elapsedWholeSeconds),
			state.secondsUntilNextLife(hugeConfig, Long.MAX_VALUE)
		)
	}

	@Test
	fun `one second interval and largest life cap do not overflow`() {
		val largeConfig = LivesConfig(Int.MAX_VALUE, BigInteger.ONE)
		assertEquals(LifeRegeneration(Int.MAX_VALUE), LifeRegeneration(0, 0).regenerate(largeConfig, Long.MAX_VALUE))
	}

	@Test
	fun `interval changes use persisted baseline`() {
		val state = LifeRegeneration(0, 100_000)
		assertEquals(LifeRegeneration(2, 110_000), state.regenerate(LivesConfig(3, BigInteger.valueOf(5)), 112_000))
	}

	@Test
	fun `life changes outside cap are rejected`() {
		assertFailsWith<IllegalArgumentException> { LifeRegeneration(1).withLives(4, 3, 100) }
		assertFailsWith<IllegalArgumentException> { LifeRegeneration(1).withLives(-1, 3, 100) }
	}

	@Test
	fun `one second interval never regenerates early between whole seconds`() {
		val shortConfig = LivesConfig(3, BigInteger.ONE)
		val state = LifeRegeneration(1, 100_999)
		assertEquals(state, state.regenerate(shortConfig, 101_000))
		assertEquals(state, state.regenerate(shortConfig, 101_998))
		assertEquals(BigInteger.ONE, state.secondsUntilNextLife(shortConfig, 101_998))
		assertEquals(LifeRegeneration(2, 101_999), state.regenerate(shortConfig, 101_999))
	}

	@Test
	fun `zero interval never regenerates and clears saved timers`() {
		val disabled = LivesConfig(3, BigInteger.ZERO)
		assertEquals(LifeRegeneration(0), LifeRegeneration(0, 100_000).regenerate(disabled, Long.MAX_VALUE))
		assertEquals(LifeRegeneration(1), LifeRegeneration(3).withLives(1, 3, 100_000, regenerationEnabled = false))
		assertNull(LifeRegeneration(0, 100_000).secondsUntilNextLife(disabled, 200_000))
	}
}
