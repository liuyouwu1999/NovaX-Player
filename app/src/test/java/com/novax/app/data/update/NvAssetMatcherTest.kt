package com.novax.app.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NvAssetMatcherTest {

    private fun asset(abi: String) = NvReleaseAsset("NovaX-1.0.0.1-$abi.apk", "https://x/$abi.apk")
    private val all = listOf(asset("arm64-v8a"), asset("armeabi-v7a"), asset("x86_64"),
        NvReleaseAsset("SHA256SUMS.txt", "https://x/sums"))

    @Test fun `按设备优先序匹配`() {
        assertEquals("NovaX-1.0.0.1-armeabi-v7a.apk",
            NvAssetMatcher.pick(listOf("armeabi-v7a", "arm64-v8a"), all)!!.name)
        assertEquals("NovaX-1.0.0.1-arm64-v8a.apk",
            NvAssetMatcher.pick(listOf("arm64-v8a"), all)!!.name)
    }
    @Test fun `无匹配与忽略非apk资产`() {
        assertNull(NvAssetMatcher.pick(listOf("mips"), all))
        assertNull(NvAssetMatcher.pick(emptyList(), listOf(all.last())))
    }
}
