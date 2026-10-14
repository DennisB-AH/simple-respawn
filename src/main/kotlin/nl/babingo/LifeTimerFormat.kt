package nl.babingo

import java.math.BigInteger

internal fun formatLifeCountdown(seconds: BigInteger): String {
	require(seconds.signum() >= 0) { "Countdown seconds cannot be negative." }
	val secondsPerMinute = BigInteger.valueOf(60)
	val (totalMinutes, remainingSeconds) = seconds.divideAndRemainder(secondsPerMinute)
	val (hours, remainingMinutes) = totalMinutes.divideAndRemainder(secondsPerMinute)
	return when {
		hours.signum() > 0 -> "${hours}h ${remainingMinutes}m ${remainingSeconds}s"
		totalMinutes.signum() > 0 -> "${remainingMinutes}m ${remainingSeconds}s"
		else -> "${remainingSeconds}s"
	}
}
