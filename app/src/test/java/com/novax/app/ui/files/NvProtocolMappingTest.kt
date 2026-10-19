package com.novax.app.ui.files

import com.novax.app.data.db.SourceType
import org.junit.Assert.assertEquals
import org.junit.Test

/** 行为守卫：NvProtocol ↔ SourceType 映射（Task 4 接线的钉子，改错即红） */
class NvProtocolMappingTest {
    @Test
    fun jellyfin_mapsRoundTrip() {
        assertEquals(SourceType.JELLYFIN, NvProtocol.Jellyfin.toSourceType())
        assertEquals(NvProtocol.Jellyfin, NvProtocol.of(SourceType.JELLYFIN))
    }
}
