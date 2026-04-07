package com.demo.lzmaloader

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.demo.lzmaloader.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: SoAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupButtons()
        observeLoadStates()

        // 首帧渲染后触发 L2 预加载
        binding.root.post {
            SoLoader.preloadL2(lifecycleScope)
            updateStats()
        }

        updateStats()
    }

    private fun setupRecyclerView() {
        val entries = SoLoader.manifest.allSorted()
        adapter = SoAdapter(entries.map { it to SoLoader.loadStates[it.name] })
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
    }

    private fun setupButtons() {
        // 手动触发 L3 按需加载
        binding.btnLoadL3.setOnClickListener {
            binding.btnLoadL3.isEnabled = false
            lifecycleScope.launch {
                val l3 = SoLoader.manifest.getByLevel(3)
                l3.forEach { entry ->
                    SoLoader.loadOnDemand(entry.name)
                }
                withContext(Dispatchers.Main) {
                    binding.btnLoadL3.text = "L3 已加载"
                    updateStats()
                }
            }
        }

        // 清除缓存，重新解压
        binding.btnClearCache.setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                val soDir = java.io.File(filesDir, "so")
                soDir.deleteRecursively()
                soDir.mkdirs()
                getSharedPreferences("so_loader_cache", MODE_PRIVATE).edit().clear().apply()
                withContext(Dispatchers.Main) {
                    binding.tvStats.text = "缓存已清除，重启App重新解压"
                }
            }
        }
    }

    private fun observeLoadStates() {
        SoLoader.onStateChanged = { name, state ->
            // 已在主线程
            val entries = SoLoader.manifest.allSorted()
            adapter.update(entries.map { it to SoLoader.loadStates[it.name] })
            updateStats()
        }
    }

    private fun updateStats() {
        val stats = SoLoader.getStats()
        val manifest = SoLoader.manifest
        val totalOrigKB = manifest.entries.values.sumOf { it.origSize } / 1024
        val totalCompKB = manifest.entries.values.sumOf { it.compressedSize } / 1024
        val savedKB = totalOrigKB - totalCompKB

        binding.tvStats.text = """
            |📦 SO总计：${stats.total} 个  |  ✅ 已加载：${stats.done}  |  ⏳ 等待：${stats.pending}
            |📉 原始大小：${totalOrigKB}KB  →  压缩后：${totalCompKB}KB  (节省 ${savedKB}KB)
            |⏱ 累计解压耗时：${stats.totalMs}ms
        """.trimMargin()
    }
}

// ---- RecyclerView Adapter ----
class SoAdapter(
    private var items: List<Pair<SOEntry, LoadState?>>
) : RecyclerView.Adapter<SoAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvName:  TextView = view.findViewById(R.id.tvSoName)
        val tvInfo:  TextView = view.findViewById(R.id.tvSoInfo)
        val tvState: TextView = view.findViewById(R.id.tvSoState)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_so, parent, false)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val (entry, state) = items[position]
        val levelTag = when (entry.level) {
            0 -> "L0 启动"
            1 -> "L1 首屏"
            2 -> "L2 核心"
            3 -> "L3 按需"
            else -> "L?"
        }

        holder.tvName.text = "[${levelTag}]  ${entry.name}"
        holder.tvInfo.text = buildString {
            if (entry.compressed) {
                val ratio = (entry.compressionRatio * 100).toInt()
                append("${entry.origSize / 1024}KB → ${entry.compressedSize / 1024}KB")
                append("  压缩率${ratio}%  节省${entry.savedKB}KB")
            } else {
                append("${entry.origSize / 1024}KB  (不压缩)")
            }
            if (entry.deps.isNotEmpty()) {
                append("  依赖: ${entry.deps.joinToString()}")
            }
        }

        val (stateText, color) = when (state) {
            is LoadState.Done    -> "✅ 已加载 ${state.ms}ms" to 0xFF2E7D32.toInt()
            is LoadState.Loading -> "⏳ 解压中..."           to 0xFFF57F17.toInt()
            is LoadState.Error   -> "❌ ${state.msg}"        to 0xFFB71C1C.toInt()
            else                 -> "○ 等待"                 to 0xFF757575.toInt()
        }
        holder.tvState.text = stateText
        holder.tvState.setTextColor(color)
    }

    fun update(newItems: List<Pair<SOEntry, LoadState?>>) {
        items = newItems
        notifyDataSetChanged()
    }
}
