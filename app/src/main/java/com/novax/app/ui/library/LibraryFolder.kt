package com.novax.app.ui.library

import com.novax.app.data.NvPaths

data class LibraryFolder(val sourceId: Long, val path: String) {
    fun contains(itemSourceId: Long, itemPath: String): Boolean =
        sourceId == itemSourceId && NvPaths.isChildOf(path, itemPath)
}
