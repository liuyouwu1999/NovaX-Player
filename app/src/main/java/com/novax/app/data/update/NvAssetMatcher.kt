package com.novax.app.data.update

/** spec §2.3：设备 SUPPORTED_ABIS 优先序 × 资产名 `-<abi>.apk` 后缀；契约命名被破坏时安全返回 null */
object NvAssetMatcher {
    private val abiRank = listOf("arm64-v8a", "armeabi-v7a", "x86_64")

    fun pick(deviceAbis: List<String>, assets: List<NvReleaseAsset>): NvReleaseAsset? {
        val byAbi = assets.filter { it.name.endsWith(".apk") }
            .mapNotNull { a -> abiRank.firstOrNull { a.name.endsWith("-$it.apk") }?.let { abi -> abi to a } }
            .toMap()
        for (dev in deviceAbis) byAbi[dev]?.let { return it }
        return null
    }
}
