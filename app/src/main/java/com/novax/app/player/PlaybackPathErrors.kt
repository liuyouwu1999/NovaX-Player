package com.novax.app.player

import java.io.FileNotFoundException

/** Inspect causes without treating genuine container or decoder errors as path failures. */
internal fun isInaccessiblePlaybackPath(error: Throwable): Boolean {
    val visited = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
    var cause: Throwable? = error
    while (cause != null && visited.add(cause)) {
        if (cause is FileNotFoundException) return true
        val message = cause.message.orEmpty()
        if (listOf("STATUS_OBJECT_NAME_NOT_FOUND", "STATUS_OBJECT_PATH_NOT_FOUND",
                "STATUS_BAD_NETWORK_NAME", "STATUS_ACCESS_DENIED", "SMB 源未配置共享名")
                .any { message.contains(it, ignoreCase = true) }) return true
        cause = cause.cause
    }
    return false
}
