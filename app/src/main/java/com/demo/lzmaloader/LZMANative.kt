package com.demo.lzmaloader

import android.content.res.AssetManager
import android.util.Log
import org.tukaani.xz.XZInputStream
import java.io.File

/**
 * SO 解压/copy 层。
 *
 * decompress：Kotlin 实现，使用 org.tukaani:xz 解 XZ/LZMA2 格式。
 * copyAsset / verifyMd5：JNI 实现（native 层负责带 fsync 的文件写入）。
 */
object LZMANative {

    private const val TAG = "LZMANative"

    init {
        System.loadLibrary("lzma_loader")
    }

    /**
     * 解压 XZ (.lzma) asset 到目标路径。
     * @return 写入字节数，失败返回 -1
     */
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

    /**
     * L0 专用：直接 copy asset，不解压（native 带 fsync）。
     */
    external fun copyAsset(
        assetMgr: AssetManager,
        assetPath: String,
        dstPath: String
    ): Boolean

    /**
     * 校验 MD5（Demo 通过 file size 兜底，native 层直接返回 true）。
     */
    external fun verifyMd5(filePath: String, expectedMd5: String): Boolean
}
