package com.novax.app.data.cast

import org.junit.Assert.assertTrue
import org.junit.Test

class CastNetworkTest {
    @Test fun `返回站点内IPv4或null`() {
        val ip = CastNetwork.siteLocalIpv4()
        assertTrue("实际值: $ip", ip == null || Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(ip))
    }
}
