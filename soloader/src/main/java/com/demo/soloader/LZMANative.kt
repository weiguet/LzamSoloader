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
                    outFile.outputStream().buffered(256 * 1024).use { out ->
                        xz.copyTo(out)
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

    external fun verifyMd5(filePath: String, expectedMd5: String): Boolean
}
