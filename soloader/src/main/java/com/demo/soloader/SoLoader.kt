package com.demo.soloader

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * SO 分级加载器
 *
 * L0 → Application.onCreate 同步加载（不解压，直接 copy）
 * L1 → Application.onCreate 同步加载（解压后 load）
 * L2 → 首帧后后台加载
 * L3 → 业务按需触发
 */
object SoLoader {

    private const val TAG        = "SoLoader"
    private const val PREFS_NAME = "so_loader_cache"

    private lateinit var appContext: Context
    private lateinit var prefs: SharedPreferences

    // 宿主 app 的 versionCode（init 时从 PackageManager 读取）
    private var appVersionCode: Long = 0L

    private val soDir: File by lazy {
        File(appContext.filesDir, "so").also { it.mkdirs() }
    }

    val manifest: SOManifest by lazy {
        appContext.assets.open("so/manifest.json").use { stream ->
            Gson().fromJson(stream.bufferedReader().readText(), SOManifest::class.java)
        }
    }

    val loadStates = ConcurrentHashMap<String, LoadState>()
    private val loaded = ConcurrentHashMap<String, Boolean>()

    var onStateChanged: ((String, LoadState) -> Unit)? = null

    fun init(context: Context) {
        appContext     = context.applicationContext
        prefs          = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        appVersionCode = resolveVersionCode(appContext)

        manifest.entries.forEach { (name, _) ->
            loadStates[name] = LoadState.Pending
        }
    }

    fun initBlocking() {
        val t  = System.currentTimeMillis()
        val l0 = manifest.getByLevel(0)
        val l1 = manifest.getByLevel(1)

        Log.i(TAG, "initBlocking: L0=${l0.size}, L1=${l1.size}")
        (l0 + l1).forEach { entry -> ensureAndLoad(entry) }
        Log.i(TAG, "L0+L1 done in ${System.currentTimeMillis() - t}ms")
    }

    fun preloadL2(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            val l2 = manifest.getByLevel(2).sortedByDescending { it.compressedSize }
            Log.i(TAG, "preloadL2: ${l2.size} SOs")
            l2.forEach { entry -> ensureAndLoad(entry) }
        }
    }

    suspend fun loadOnDemand(soName: String): Boolean = withContext(Dispatchers.IO) {
        val entry = manifest.findEntry(soName) ?: run {
            Log.e(TAG, "entry not found: $soName")
            return@withContext false
        }
        entry.deps.forEach { dep ->
            manifest.findEntry(dep)?.let { ensureAndLoad(it) }
        }
        ensureAndLoad(entry)
    }

    @Synchronized
    fun ensureAndLoad(entry: SOEntry): Boolean {
        if (loaded[entry.name] == true) return true

        val outFile = File(soDir, entry.name)
        updateState(entry.name, LoadState.Loading)
        val t = System.currentTimeMillis()

        if (!isCacheValid(entry, outFile)) {
            Log.d(TAG, "cache miss: ${entry.name}, decompressing...")
            val ok = if (entry.compressed) {
                decompressToFile(entry, outFile)
            } else {
                copyAssetToFile(entry, outFile)
            }
            if (!ok) {
                updateState(entry.name, LoadState.Error("解压失败"))
                return false
            }
            prefs.edit()
                .putString(cacheKey(entry.name), cacheTag(entry))
                .putLong("size_${entry.name}", entry.origSize)
                .apply()
        } else {
            Log.d(TAG, "cache hit: ${entry.name}")
        }

        val loadOk = simulateLoad(entry, outFile)

        val ms = System.currentTimeMillis() - t
        if (loadOk) {
            loaded[entry.name] = true
            updateState(entry.name, LoadState.Done(ms))
            Log.i(TAG, "✓ ${entry.name} [L${entry.level}] ${ms}ms")
        } else {
            updateState(entry.name, LoadState.Error("load失败"))
        }
        return loadOk
    }

    private fun decompressToFile(entry: SOEntry, outFile: File): Boolean {
        return try {
            val written = LZMANative.decompress(
                assetMgr  = appContext.assets,
                assetPath = entry.assetPath,
                dstPath   = outFile.absolutePath,
                origSize  = entry.origSize
            )
            written > 0 && outFile.length() == entry.origSize
        } catch (e: Exception) {
            Log.e(TAG, "decompress error: ${entry.name}", e)
            false
        }
    }

    private fun copyAssetToFile(entry: SOEntry, outFile: File): Boolean {
        return try {
            LZMANative.copyAsset(
                assetMgr  = appContext.assets,
                assetPath = entry.assetPath,
                dstPath   = outFile.absolutePath
            )
        } catch (e: Exception) {
            Log.e(TAG, "copyAsset error: ${entry.name}", e)
            false
        }
    }

    private fun simulateLoad(entry: SOEntry, outFile: File): Boolean {
        if (!outFile.exists()) return false
        val magic = ByteArray(4)
        outFile.inputStream().use { it.read(magic) }
        val isELF = magic.contentEquals(byteArrayOf(0x7f, 0x45, 0x4c, 0x46))
        Log.d(TAG, "simulateLoad ${entry.name}: size=${outFile.length()}, ELF=${isELF}")
        return outFile.length() == entry.origSize
    }

    // ---- 缓存校验 ----
    private fun isCacheValid(entry: SOEntry, outFile: File): Boolean {
        if (!outFile.exists()) return false
        if (outFile.length() != entry.origSize) return false
        val saved = prefs.getString(cacheKey(entry.name), null)
        return saved == cacheTag(entry)
    }

    private fun cacheKey(name: String) = "v_$name"
    private fun cacheTag(entry: SOEntry) = "${appVersionCode}_${entry.origMd5}"

    // ---- 清理旧版本缓存 ----
    fun cleanOldCache() {
        val currentVer = appVersionCode.toString()
        soDir.listFiles()?.forEach { file ->
            val saved = prefs.getString(cacheKey(file.name), "") ?: ""
            if (!saved.startsWith(currentVer)) {
                Log.i(TAG, "clean old: ${file.name}")
                file.delete()
            }
        }
    }

    private fun updateState(name: String, state: LoadState) {
        loadStates[name] = state
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            onStateChanged?.invoke(name, state)
        }
    }

    fun getStats(): LoadStats {
        val total   = loadStates.size
        val done    = loadStates.values.count { it is LoadState.Done }
        val loading = loadStates.values.count { it is LoadState.Loading }
        val error   = loadStates.values.count { it is LoadState.Error }
        val pending = loadStates.values.count { it is LoadState.Pending }
        val totalMs = loadStates.values.filterIsInstance<LoadState.Done>().sumOf { it.ms }
        return LoadStats(total, done, loading, error, pending, totalMs)
    }

    // 从 PackageManager 读取宿主 app 的 versionCode（不依赖 BuildConfig）
    private fun resolveVersionCode(context: Context): Long {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0).versionCode.toLong()
            }
        } catch (e: Exception) {
            0L
        }
    }
}

data class LoadStats(
    val total: Int,
    val done: Int,
    val loading: Int,
    val error: Int,
    val pending: Int,
    val totalMs: Long
)
