package com.demo.soloader

import android.content.res.AssetManager
import android.util.Log
import org.tukaani.xz.XZInputStream
import java.io.File

object LZMANative {

    private const val TAG = "LZMANative"

    init {
        System.loadLibrary("lzma_loader")
    }

    fun decompress(
        assetMgr: AssetManager,
        assetPath: String,
        dstPath: String,
        origSize: Long
    ): Long {
        Log.i(TAG, "decompress: $assetPath -> $dstPath (orig=${origSize}B)")
        return try {
            assetMgr.open(assetPath).use { raw ->
                XZInputStream(raw).use { xz ->
                    val outFile = File(dstPath)
                    // 64KB 写缓冲，降低大量 SO 并发解压时的内存峰值
                    outFile.outputStream().buffered(64 * 1024).use { out ->
                        xz.copyTo(out, bufferSize = 16 * 1024)
                    }
                    val written = outFile.length()
                    Log.i(TAG, "decompress OK: $dstPath ($written bytes)")
                    written
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "decompress failed: $assetPath", e)
            -1L
        }
    }

    external fun copyAsset(
        assetMgr: AssetManager,
        assetPath: String,
        dstPath: String
    ): Boolean
}
