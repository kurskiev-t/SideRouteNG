package com.kurskievtr.siderouteng

import android.Manifest
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Filter
import android.widget.Filterable
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kurskievtr.siderouteng.databinding.ActivityAppListBinding
import com.kurskievtr.siderouteng.databinding.ItemAppBinding

class AppListActivity : AppCompatActivity() {
    private lateinit var binding: ActivityAppListBinding
    private lateinit var prefs: TunnelPrefs
    private lateinit var adapter: AppAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        prefs = TunnelPrefs(this)
        binding = ActivityAppListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        binding.toolbar.setNavigationOnClickListener { finish() }

        val selected = prefs.apps.toMutableSet()
        val pm = packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .asSequence()
            .filter { it.packageName != packageName }
            .filter { pm.checkPermission(Manifest.permission.INTERNET, it.packageName) == PackageManager.PERMISSION_GRANTED }
            .map { info ->
                AppRow(
                    packageName = info.packageName,
                    label = info.loadLabel(pm).toString(),
                    icon = info.loadIcon(pm),
                    selected = selected.contains(info.packageName),
                    system = info.flags and ApplicationInfo.FLAG_SYSTEM != 0
                )
            }
            .sortedWith(compareByDescending<AppRow> { it.selected }.thenBy { it.label.lowercase() })
            .toList()

        adapter = AppAdapter(apps) { row, checked ->
            row.selected = checked
        }
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        binding.search.doAfterTextChanged { adapter.filter.filter(it?.toString().orEmpty()) }
    }

    override fun onStop() {
        prefs.apps = adapter.allRows.filter { it.selected }.map { it.packageName }.toSet()
        AppLog.i("app list saved (${prefs.apps.size} apps)")
        super.onStop()
    }
}

private data class AppRow(
    val packageName: String,
    val label: String,
    val icon: android.graphics.drawable.Drawable,
    var selected: Boolean,
    val system: Boolean
)

private class AppAdapter(
    val allRows: List<AppRow>,
    private val onToggle: (AppRow, Boolean) -> Unit
) : RecyclerView.Adapter<AppAdapter.Holder>(), Filterable {
    private var visible = allRows.toList()

    inner class Holder(val binding: ItemAppBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val inflater = LayoutInflater.from(parent.context)
        return Holder(ItemAppBinding.inflate(inflater, parent, false))
    }

    override fun getItemCount(): Int = visible.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = visible[position]
        holder.binding.icon.setImageDrawable(row.icon)
        holder.binding.name.text = row.label
        holder.binding.packageName.text = row.packageName
        holder.binding.checked.setOnCheckedChangeListener(null)
        holder.binding.checked.isChecked = row.selected
        holder.binding.checked.setOnCheckedChangeListener { _, checked ->
            onToggle(row, checked)
        }
        holder.binding.root.setOnClickListener {
            holder.binding.checked.toggle()
        }
    }

    override fun getFilter(): Filter = object : Filter() {
        override fun performFiltering(constraint: CharSequence?): FilterResults {
            val q = constraint?.toString()?.trim()?.lowercase().orEmpty()
            val filtered = if (q.isEmpty()) {
                allRows
            } else {
                allRows.filter { it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q) }
            }
            return FilterResults().apply {
                values = filtered
                count = filtered.size
            }
        }

        @Suppress("UNCHECKED_CAST")
        override fun publishResults(constraint: CharSequence?, results: FilterResults) {
            visible = results.values as List<AppRow>
            notifyDataSetChanged()
        }
    }
}
