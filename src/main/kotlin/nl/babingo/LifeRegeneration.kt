package nl.babingo

import java.math.BigInteger

internal data class LifeRegeneration(
	val remaining: Int,
	// Epoch milliseconds preserve the full interval even when a life is lost between whole seconds.
	val regenerationStartedAt: Long? = null
) {
	fun withLives(amount: Int, maxLives: Int, now: Long, regenerationEnabled: Boolean = true): LifeRegeneration {
		require(amount in 0..maxLives) { "Lives must be between zero and $maxLives." }
		val tracking = regenerationEnabled && amount < maxLives
		return LifeRegeneration(amount, if (tracking) regenerationStartedAt ?: now else null)
	}

	fun regenerate(config: LivesConfig, now: Long): LifeRegeneration {
		val current = withLives(remaining.coerceAtMost(config.maxLives), config.maxLives, now, config.regenerationEnabled)
		val startedAt = current.regenerationStartedAt ?: return current
		val elapsed = BigInteger.valueOf(now).subtract(BigInteger.valueOf(startedAt))
		if (elapsed.signum() <= 0) return current

		val intervalMillis = config.lifeRegenIntervalSeconds.multiply(MILLIS_PER_SECOND)
		val gained = elapsed.divide(intervalMillis)
			.min(BigInteger.valueOf((config.maxLives - current.remaining).toLong())).intValueExact()
		if (gained == 0) return current
		val nextBaseline = BigInteger.valueOf(startedAt)
			.add(intervalMillis.multiply(BigInteger.valueOf(gained.toLong()))).longValueExact()
		return LifeRegeneration(current.remaining + gained)
			.withLives(current.remaining + gained, config.maxLives, nextBaseline)
	}

	fun secondsUntilNextLife(config: LivesConfig, now: Long): BigInteger? {
		if (!config.regenerationEnabled) return null
		val startedAt = regenerationStartedAt ?: return null
		val elapsed = BigInteger.valueOf(now).subtract(BigInteger.valueOf(startedAt))
		val remainingMillis = config.lifeRegenIntervalSeconds.multiply(MILLIS_PER_SECOND)
			.subtract(elapsed).max(BigInteger.ZERO)
		return remainingMillis.add(MILLIS_PER_SECOND.subtract(BigInteger.ONE)).divide(MILLIS_PER_SECOND)
	}
}

private val MILLIS_PER_SECOND = BigInteger.valueOf(1000)
