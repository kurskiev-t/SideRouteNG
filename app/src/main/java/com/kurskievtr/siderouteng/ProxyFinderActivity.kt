package com.kurskievtr.siderouteng

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kurskievtr.siderouteng.databinding.ActivityProxyFinderBinding
import com.kurskievtr.siderouteng.databinding.ItemProxyBinding

/**
 * Searches public SOCKS5 lists, shows what survived the check sorted by latency and writes the
 * ticked rows into the proxy pool. Rows faster than the latency limit are ticked automatically.
 */
class ProxyFinderActivity : AppCompatActivity(), ProxyFinder.Listener {
    private lateinit var binding: ActivityProxyFinderBinding
    private lateinit var prefs: TunnelPrefs
    private lateinit var adapter: ProxyAdapter
    private val finder = ProxyFinder(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        prefs = TunnelPrefs(this)
        binding = ActivityProxyFinderBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        binding.toolbar.setNavigationOnClickListener { finish() }

        adapter = ProxyAdapter()
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        binding.maxLatency.setText(prefs.maxLatencyMs.toString())
        binding.autoSelect.isChecked = prefs.autoSelectProxies

        binding.search.setOnClickListener { toggleSearch() }
        binding.selectAll.setOnClickListener { adapter.setAllChecked(true) }
        binding.clear.setOnClickListener { adapter.setAllChecked(false) }
        binding.add.setOnClickListener { addToPool() }
        showProgress(0, 0, 0)
    }

    override fun onDestroy() {
        finder.stop()
        super.onDestroy()
    }

    private fun toggleSearch() {
        if (finder.isRunning) {
            finder.stop()
            return
        }
        if (prefs.enabled) {
            Toast.makeText(this, R.string.finder_needs_tunnel_off, Toast.LENGTH_LONG).show()
            return
        }
        prefs.maxLatencyMs = binding.maxLatency.text?.toString()?.toIntOrNull()
            ?: TunnelPrefs.DEFAULT_MAX_LATENCY_MS
        prefs.autoSelectProxies = binding.autoSelect.isChecked
        binding.maxLatency.setText(prefs.maxLatencyMs.toString())
        adapter.clear()
        binding.search.setText(R.string.find_stop)
        finder.start()
    }

    private fun addToPool() {
        val chosen = adapter.checkedEndpoints()
        if (chosen.isEmpty()) {
            Toast.makeText(this, R.string.nothing_selected, Toast.LENGTH_SHORT).show()
            return
        }
        val existing = ProxyEndpoint.parseList(prefs.proxyList)
        val merged = (existing + chosen).distinct()
        prefs.proxyList = merged.joinToString("\n") { "${it.host}:${it.port}" }
        AppLog.i("proxy pool now has ${merged.size} endpoints")
        Toast.makeText(
            this,
            getString(R.string.added_to_pool, chosen.size, merged.size),
            Toast.LENGTH_SHORT
        ).show()
        finish()
    }

    override fun onProgress(checked: Int, total: Int, live: Int) {
        showProgress(checked, total, live)
    }

    override fun onFound(result: ProxyProbeResult) {
        val autoSelect = binding.autoSelect.isChecked &&
            result.latencyMs <= prefs.maxLatencyMs
        adapter.add(result, autoSelect)
    }

    override fun onFinished(checked: Int, live: Int, cancelled: Boolean) {
        binding.search.setText(R.string.find_start)
        val message = if (cancelled) R.string.find_cancelled else R.string.find_done
        Toast.makeText(this, getString(message, live, checked), Toast.LENGTH_LONG).show()
    }

    private fun showProgress(checked: Int, total: Int, live: Int) {
        binding.progress.text = getString(R.string.find_progress, checked, total, live)
    }
}

private class ProxyRow(val result: ProxyProbeResult, var checked: Boolean)

private class ProxyAdapter : RecyclerView.Adapter<ProxyAdapter.Holder>() {
    private val rows = mutableListOf<ProxyRow>()

    class Holder(val binding: ItemProxyBinding) : RecyclerView.ViewHolder(binding.root)

    fun add(result: ProxyProbeResult, checked: Boolean) {
        val row = ProxyRow(result, checked)
        val position = rows.indexOfFirst { it.result.latencyMs > result.latencyMs }
            .takeIf { it >= 0 } ?: rows.size
        rows.add(position, row)
        notifyItemInserted(position)
    }

    fun clear() {
        val count = rows.size
        rows.clear()
        notifyItemRangeRemoved(0, count)
    }

    fun setAllChecked(checked: Boolean) {
        rows.forEach { it.checked = checked }
        notifyItemRangeChanged(0, rows.size)
    }

    fun checkedEndpoints(): List<ProxyEndpoint> = rows.filter { it.checked }.map { it.result.endpoint }

    override fun getItemCount(): Int = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val inflater = LayoutInflater.from(parent.context)
        return Holder(ItemProxyBinding.inflate(inflater, parent, false))
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = rows[position]
        holder.binding.address.text = row.result.endpoint.toString()
        holder.binding.latency.text = holder.itemView.context
            .getString(R.string.latency_ms, row.result.latencyMs)
        holder.binding.checked.setOnCheckedChangeListener(null)
        holder.binding.checked.isChecked = row.checked
        holder.binding.checked.setOnCheckedChangeListener { _, checked ->
            rows.getOrNull(holder.bindingAdapterPosition)?.checked = checked
        }
        holder.binding.root.setOnClickListener { holder.binding.checked.toggle() }
    }
}
