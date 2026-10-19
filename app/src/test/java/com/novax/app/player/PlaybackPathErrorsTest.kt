package com.novax.app.player

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.io.FileNotFoundException

class PlaybackPathErrorsTest {
    @Test fun wrappedMissingFile_isPathError() {
        assertTrue(isInaccessiblePlaybackPath(IOException("load", FileNotFoundException("missing"))))
    }
    @Test fun smbMissingShareOrDenied_isPathError() {
        for (status in listOf("STATUS_OBJECT_NAME_NOT_FOUND", "STATUS_OBJECT_PATH_NOT_FOUND", "STATUS_BAD_NETWORK_NAME", "STATUS_ACCESS_DENIED")) {
            assertTrue(isInaccessiblePlaybackPath(IOException("load", IOException(status))))
        }
    }
    @Test fun malformedContainerAndTimeout_areNotPathErrors() {
        assertFalse(isInaccessiblePlaybackPath(IOException("None of the available extractors could read the stream")))
        assertFalse(isInaccessiblePlaybackPath(IOException("Read timed out")))
    }
}
