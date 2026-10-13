package dev.screenshoteditor.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for the retention rule used to delete old capture and share files. */
class TempCacheTest {

    private val now = 1_800_000_000_000L

    @Test
    fun isExpired_exactlyMaxAge_isKept() {
        // The rule is "older than" 24h, so a file exactly 24h old survives one more cleanup.
        assertFalse(TempCache.isExpired(now - TempCache.MAX_AGE_MS, now))
    }

    @Test
    fun isExpired_oneMillisecondOverMaxAge_isDeleted() {
        assertTrue(TempCache.isExpired(now - TempCache.MAX_AGE_MS - 1, now))
    }

    @Test
    fun isExpired_futureTimestamp_isKept() {
        // A clock moved backwards must not cause fresh files to be deleted.
        assertFalse(TempCache.isExpired(now + 60_000L, now))
    }
}
