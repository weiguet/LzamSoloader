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
 * L1 → Application.onCreate 异步加载（解压后 load，避免阻塞主线程）
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

    // manifest 在 init() 中 fail-fast 加载，避免 lazy 延迟导致的未初始化异常
    private lateinit var _manifest: SOManifest

    val manifest: SOManifest
        get() {
            check(::_manifest.isInitialized) {
                "SoLoader not initialized. Call SoLoader.init(context) first."
            }
            return _manifest
        }

    val loadStates = ConcurrentHashMap<String, LoadState>()
    private val loaded = ConcurrentHashMap<String, Boolean>()

    // 每个 SO 独立锁，避免全局 @Synchronized 串行化所有 I/O
    private val entryLocks = ConcurrentHashMap<String, Any>()

    var onStateChanged: ((String, LoadState) -> Unit)? = null

    fun init(context: Context) {
        appContext     = context.applicationContext
        prefs          = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        appVersionCode = resolveVersionCode(appContext)

        // fail-fast：manifest 解析失败则立刻抛出，而不是在首次访问时崩溃
        _manifest = try {
            appContext.assets.open("so/manifest.json").use { stream ->
                Gson().fromJson(stream.bufferedReader().readText(), SOManifest::class.java)
            }
        } catch (e: Exception) {
            throw IllegalStateException(
                "Failed to load SO manifest. Ensure so/manifest.json is in assets.", e
            )
        }

        _manifest.entries.forEach { (name, _) ->
            loadStates[name] = LoadState.Pending
        }
    }

    /**
     * 同步加载 L0（仅 copy，无解压），在 Application.onCreate 主线程调用。
     * L0 文件无压缩，通常极小，copy 耗时可忽略。
     */
    fun initL0Blocking() {
        val t  = System.currentTimeMillis()
        val l0 = manifest.getByLevel(0)
        Log.i(TAG, "initL0Blocking: ${l0.size} SOs")
        l0.forEach { entry -> ensureAndLoad(entry) }
        Log.i(TAG, "L0 done in ${System.currentTimeMillis() - t}ms")
    }

    /**
     * 异步加载 L1（IO 线程解压），不阻塞主线程。
     * 在 Application.onCreate 调用，与 UI 并发执行。
     */
    fun initL1Async(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO + CoroutineExceptionHandler { _, e ->
            Log.e(TAG, "initL1Async failed", e)
        }) {
            val t  = System.currentTimeMillis()
            val l1 = manifest.getByLevel(1)
            Log.i(TAG, "initL1Async: ${l1.size} SOs")
            try {
                l1.forEach { entry -> ensureAndLoad(entry) }
                Log.i(TAG, "L1 done in ${System.currentTimeMillis() - t}ms")
            } catch (e: CancellationException) {
                Log.i(TAG, "initL1Async cancelled")
                throw e
            }
        }
    }

    fun preloadL2(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO + CoroutineExceptionHandler { _, e ->
            Log.e(TAG, "preloadL2 failed", e)
        }) {
            val l2 = manifest.getByLevel(2).sortedByDescending { it.compressedSize }
            Log.i(TAG, "preloadL2: ${l2.size} SOs")
            try {
                l2.forEach { entry -> ensureAndLoad(entry) }
            } catch (e: CancellationException) {
                Log.i(TAG, "preloadL2 cancelled")
                throw e
            }
        }
    }

    /**
     * 按需加载指定 SO 及其所有依赖（递归，防循环）。
     */
    suspend fun loadOnDemand(soName: String): Boolean = withContext(Dispatchers.IO) {
        val visited = mutableSetOf<String>()
        loadRecursive(soName, visited)
    }

    private fun loadRecursive(soName: String, visited: MutableSet<String>): Boolean {
        if (!visited.add(soName)) return true  // 已处理（防循环）
        val entry = manifest.findEntry(soName) ?: run {
            Log.e(TAG, "entry not found: $soName")
            return false
        }
        // 先递归加载所有依赖
        entry.deps.forEach { dep -> loadRecursive(dep, visited) }
        return ensureAndLoad(entry)
    }

    fun ensureAndLoad(entry: SOEntry): Boolean {
        // 快速路径：已加载
        if (loaded[entry.name] == true) return true

        // 每个 SO 独立锁，不同 SO 可并发加载
        val lock = entryLocks.getOrPut(entry.name) { Any() }
        synchronized(lock) {
            // 双重检查
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
    }

    /**
     * 解压 LZMA 压缩的 SO：先写临时文件，成功后原子 rename，失败则清理。
     */
    private fun decompressToFile(entry: SOEntry, outFile: File): Boolean {
        val tempFile = File(outFile.parent, "${outFile.name}.tmp")
        tempFile.delete() // 清理上次可能残留的临时文件
        return try {
            val written = LZMANative.decompress(
                assetMgr  = appContext.assets,
                assetPath = entry.assetPath,
                dstPath   = tempFile.absolutePath,
                origSize  = entry.origSize
            )
            if (written > 0 && tempFile.length() == entry.origSize) {
                outFile.delete()
                tempFile.renameTo(outFile)
            } else {
                tempFile.delete()
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "decompress error: ${entry.name}", e)
            tempFile.delete()
            false
        }
    }

    /**
     * Copy 未压缩 SO：先写临时文件，成功后原子 rename，失败则清理。
     */
    private fun copyAssetToFile(entry: SOEntry, outFile: File): Boolean {
        val tempFile = File(outFile.parent, "${outFile.name}.tmp")
        tempFile.delete()
        return try {
            val ok = LZMANative.copyAsset(
                assetMgr  = appContext.assets,
                assetPath = entry.assetPath,
                dstPath   = tempFile.absolutePath
            )
            if (ok && tempFile.length() == entry.origSize) {
                outFile.delete()
                tempFile.renameTo(outFile)
            } else {
                tempFile.delete()
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "copyAsset error: ${entry.name}", e)
            tempFile.delete()
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
        val saved = prefs.getString(cacheKey(entry.name), null) ?: return false
        return saved == cacheTag(entry)
    }

    private fun cacheKey(name: String) = "v_$name"
    private fun cacheTag(entry: SOEntry) = "${appVersionCode}_${entry.origMd5}"

    // ---- 清理旧版本缓存 ----
    fun cleanOldCache() {
        val files = soDir.listFiles() ?: run {
            Log.w(TAG, "cleanOldCache: cannot list soDir")
            return
        }
        val currentVer = appVersionCode.toString()
        files.forEach { file ->
            if (!file.isFile) return@forEach
            val saved = prefs.getString(cacheKey(file.name), "")
            if (saved?.startsWith(currentVer) != true) {
                Log.i(TAG, "clean old: ${file.name}")
                if (!file.delete()) {
                    Log.w(TAG, "cleanOldCache: failed to delete ${file.name}")
                }
            }
        }
    }

    private fun updateState(name: String, state: LoadState) {
        loadStates[name] = state
        // 捕获快照，避免 onStateChanged 在 post 前被置 null 的竞态
        val callback = onStateChanged
        if (callback != null) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                callback.invoke(name, state)
            }
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
