package com.novax.app.player

enum class BufferLevel(val key: String, val forwardMs: Int, val maxMs: Int) {
    LOW("low", 15_000, 30_000), MEDIUM("medium", 50_000, 120_000), HIGH("high", 120_000, 180_000);
    companion object {
        fun from(key: String?) = entries.firstOrNull { it.key == key } ?: MEDIUM
    }
}
