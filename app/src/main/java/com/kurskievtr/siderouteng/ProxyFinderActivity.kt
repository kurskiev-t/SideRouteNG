package com.kurskievtr.siderouteng

import android.content.Context
import android.content.Intent
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
 * Searches public lists, shows what survived the check sorted by latency and writes the ticked
 * rows into the settings. Rows faster than the latency limit are ticked automatically.
 *
 * The same screen serves both kinds of upstream: SOCKS5 endpoints go into the pool, Xray links
 * into the server links, and the two lists are never mixed.
 */
class ProxyFinderActivity : AppCompatActivity(), Finder.Listener {
    private lateinit var binding: ActivityProxyFinderBinding
    private lateinit var prefs: TunnelPrefs
    private lateinit var adapter: ProxyAdapter
    private lateinit var finder: Finder
    private var links = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        prefs = TunnelPrefs(this)
        links = intent.getBooleanExtra(EXTRA_LINKS, false)
        finder = if (links) LinkFinder(this, this) else ProxyFinder(this)
        binding = ActivityProxyFinderBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        binding.toolbar.setTitle(if (links) R.string.find_links else R.string.find_proxies)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.add.setText(if (links) R.string.add_to_links else R.string.add_to_pool)

        adapter = ProxyAdapter()
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        binding.maxLatency.setText(prefs.maxLatencyMs.toString())
        binding.autoSelect.isChecked = prefs.autoSelectProxies

        binding.search.setOnClickListener { toggleSearch() }
        binding.selectAll.setOnClickListener { adapter.setAllChecked(true) }
        binding.clear.setOnClickListener { adapter.setAllChecked(false) }
        binding.add.setOnClickListener { addChosen() }
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

    private fun addChosen() {
        val chosen = adapter.checkedValues()
        if (chosen.isEmpty()) {
            Toast.makeText(this, R.string.nothing_selected, Toast.LENGTH_SHORT).show()
            return
        }
        val existing = (if (links) prefs.outboundLinks else prefs.proxyList)
            .split('\n')
            .map(String::trim)
            .filter { it.isNotEmpty() }
        val merged = (existing + chosen).distinct()
        if (links) prefs.outboundLinks = merged.joinToString("\n")
        else prefs.proxyList = merged.joinToString("\n")
        AppLog.i(
            if (links) "server links now hold ${merged.size} entries"
            else "proxy pool now has ${merged.size} endpoints"
        )
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

    override fun onFound(result: FinderResult) {
        val autoSelect = binding.autoSelect.isChecked && result.latencyMs <= prefs.maxLatencyMs
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

    companion object {
        private const val EXTRA_LINKS = "links"

        /** [links] searches for Xray server links instead of SOCKS5 endpoints. */
        fun intent(context: Context, links: Boolean): Intent =
            Intent(context, ProxyFinderActivity::class.java).putExtra(EXTRA_LINKS, links)
    }
}

private class ProxyRow(val result: FinderResult, var checked: Boolean)

private class ProxyAdapter : RecyclerView.Adapter<ProxyAdapter.Holder>() {
    private val rows = mutableListOf<ProxyRow>()

    class Holder(val binding: ItemProxyBinding) : RecyclerView.ViewHolder(binding.root)

    fun add(result: FinderResult, checked: Boolean) {
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

    fun checkedValues(): List<String> = rows.filter { it.checked }.map { it.result.candidate.value }

    override fun getItemCount(): Int = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val inflater = LayoutInflater.from(parent.context)
        return Holder(ItemProxyBinding.inflate(inflater, parent, false))
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = rows[position]
        holder.binding.address.text = row.result.candidate.label
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
