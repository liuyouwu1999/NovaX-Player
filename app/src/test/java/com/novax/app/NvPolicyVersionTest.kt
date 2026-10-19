package com.novax.app

import com.novax.app.ui.policy.NvPolicyText
import org.junit.Assert.assertEquals
import org.junit.Test

/** spec §1.4/§2：政策升版驱动老用户首启重征求（Review Focus 5） */
class NvPolicyVersionTest {
    @Test fun `本版政策升到 VERSION 2`() { assertEquals(2, NvPolicyText.VERSION) }
}
