package io.github.wingedpanther.openshelf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadTest {
    @Test fun spaceCoversTheFileAndTheInstallCopy() {
        assertEquals(220_000_000L, spaceNeeded(100_000_000, 0))
    }

    @Test fun spaceCountsWhatIsAlreadyDownloaded() {
        assertEquals(160_000_000L, spaceNeeded(100_000_000, 60_000_000))
        assertEquals(120_000_000L, spaceNeeded(100_000_000, 100_000_000))
        assertEquals(120_000_000L, spaceNeeded(100_000_000, 150_000_000))
    }

    @Test fun secondsLeftRoundsUp() {
        assertEquals(10L, Job.Downloading(500, 1500, 100).secondsLeft)
        assertEquals(1L, Job.Downloading(1499, 1500, 100).secondsLeft)
    }

    @Test fun noTimeLeftWithoutASpeedOrWhenDone() {
        assertNull(Job.Downloading(500, 1500).secondsLeft)
        assertNull(Job.Downloading(1500, 1500, 100).secondsLeft)
    }
}
