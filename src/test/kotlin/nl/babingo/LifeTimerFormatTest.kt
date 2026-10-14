package nl.babingo

import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LifeTimerFormatTest {
	@Test
	fun `countdown formats seconds minutes and hours with remainders`() {
		for ((seconds, expected) in mapOf(
			0L to "0s",
			1L to "1s",
			59L to "59s",
			60L to "1m 0s",
			90L to "1m 30s",
			3599L to "59m 59s",
			3600L to "1h 0m 0s",
			3690L to "1h 1m 30s",
			86400L to "24h 0m 0s"
		)) {
			assertEquals(expected, formatLifeCountdown(BigInteger.valueOf(seconds)))
		}
	}

	@Test
	fun `hours remain unlimited without narrowing to long`() {
		val hours = BigInteger.TEN.pow(100)
		val seconds = hours.multiply(BigInteger.valueOf(3600)).add(BigInteger.valueOf(90))
		assertEquals("${hours}h 1m 30s", formatLifeCountdown(seconds))
	}

	@Test
	fun `negative countdown is rejected`() {
		assertFailsWith<IllegalArgumentException> { formatLifeCountdown(BigInteger.valueOf(-1)) }
	}
}
