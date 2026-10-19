package com.novax.app.data.smb

import com.novax.app.data.NvPaths

/**
 * SMB「未选共享」源的虚拟根目录约定（纯函数，可单测）。
 *
 * [com.novax.app.data.db.SourceEntity.shareOrPath] 为空时，list(源根) 返回共享枚举
 * （[SmbClient.listShares]）作为虚拟根目录：每个共享映射为目录条目，
 * path = "/@share/<共享名>/"。UI 点击该条目后把共享名写回 Room（此后按普通共享源
 * 浏览）；同时兜底支持直接列 "/@share/<名>/子路径"。
 */
object SmbShareRoot {

    data class Target(val share: String, val path: String)

    /** Only unconfigured sources interpret the virtual namespace. */
    fun resolve(configuredShare: String, path: String): Target {
        val normalized = NvPaths.normalize(path)
        if (configuredShare.isNotBlank()) return Target(configuredShare, normalized)
        val share = shareNameOf(normalized)
            ?: throw java.io.FileNotFoundException("文件路径无法访问，请检查源配置")
        return Target(share, NvPaths.normalize(normalized.removePrefix("$PREFIX/$share")))
    }

    fun virtualPath(share: String, path: String, isDirectory: Boolean): String {
        val virtual = "$PREFIX/$share${NvPaths.normalize(path)}"
        return if (isDirectory) NvPaths.asDirectory(virtual) else NvPaths.normalize(virtual)
    }

    /** 虚拟共享层路径前缀（统一路径表示，见 [NvPaths]） */
    const val PREFIX = "/@share"

    /** 共享名 → 虚拟目录条目路径（目录条目以 "/" 结尾，与真实目录条目一致） */
    fun entryPath(shareName: String): String =
        NvPaths.asDirectory(NvPaths.join(PREFIX, shareName))

    /**
     * 统一路径 → 共享名："/@share/<名>[/子路径]" → "<名>"；
     * 非虚拟前缀或共享名为空返回 null。
     */
    fun shareNameOf(path: String): String? {
        val n = NvPaths.normalize(path)
        if (!n.startsWith("$PREFIX/")) return null
        val name = n.removePrefix("$PREFIX/").substringBefore('/')
        return name.takeIf { it.isNotBlank() }
    }
}
