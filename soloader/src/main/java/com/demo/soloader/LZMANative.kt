package com.demo.soloader

import android.content.res.AssetManager
import android.util.Log
import org.tukaani.xz.XZInputStream
import java.io.File

object LZMANative {

    private const val TAG = "LZMANative"

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

    fun copyAsset(
        assetMgr: AssetManager,
        assetPath: String,
        dstPath: String
    ): Boolean {
        return try {
            assetMgr.open(assetPath).use { input ->
                File(dstPath).outputStream().buffered(64 * 1024).use { output ->
                    input.copyTo(output, bufferSize = 64 * 1024)
                }
            }
            Log.i(TAG, "copyAsset OK: $assetPath -> $dstPath")
            true
        } catch (e: Exception) {
            Log.e(TAG, "copyAsset failed: $assetPath", e)
            false
        }
    }
}
