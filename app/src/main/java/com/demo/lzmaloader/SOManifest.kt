package com.demo.lzmaloader

import com.google.gson.annotations.SerializedName

data class SOManifest(
    val version: Int,
    val abi: String,
    val entries: Map<String, SOEntry>
) {
    fun getByLevel(level: Int): List<SOEntry> =
        entries.values.filter { it.level == level }

    fun findEntry(name: String): SOEntry? =
        entries[name] ?: entries["lib$name.so"]

    fun allSorted(): List<SOEntry> =
        entries.values.sortedBy { it.level }
}

data class SOEntry(
    val level: Int,
    @SerializedName("asset_path")      val assetPath: String,
    val compressed: Boolean,
    @SerializedName("orig_size")       val origSize: Long,
    @SerializedName("compressed_size") val compressedSize: Long,
    @SerializedName("orig_md5")        val origMd5: String,
    val deps: List<String> = emptyList()
) {
    // 从 asset_path 反推 SO 名称
    val name: String get() = assetPath
        .substringAfterLast("/")
        .removeSuffix(".lzma")
        .removeSuffix(".gz")

    val compressionRatio: Float get() =
        if (origSize == 0L) 1f
        else compressedSize.toFloat() / origSize.toFloat()

    val savedKB: Int get() =
        ((origSize - compressedSize) / 1024).toInt()
}

// 加载状态，用于UI展示
sealed class LoadState {
    object Pending   : LoadState()
    object Loading   : LoadState()
    data class Done(val ms: Long) : LoadState()
    data class Error(val msg: String) : LoadState()
}
