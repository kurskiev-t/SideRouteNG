package com.kurskievtr.siderouteng

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.kurskievtr.siderouteng.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: TunnelPrefs
    private val logListener: (String) -> Unit = { text ->
        binding.logView.post {
            binding.logView.text = text
            binding.logScroll.post { binding.logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == TunnelPrefs.ENABLE) updateControlState()
    }

    private val vpnPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            TunnelService.enqueue(this, TunnelService.ACTION_CONNECT)
        } else {
            AppLog.w("VPN permission denied")
            Toast.makeText(this, R.string.vpn_permission_denied, Toast.LENGTH_LONG).show()
        }
    }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { startVpn() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        prefs = TunnelPrefs(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        binding.global.setOnCheckedChangeListener { _, _ ->
            if (prefs.enabled) return@setOnCheckedChangeListener
            savePrefs()
            updateControlState()
        }
        binding.apps.setOnClickListener {
            savePrefs()
            startActivity(Intent(this, AppListActivity::class.java))
        }
        binding.findProxies.setOnClickListener {
            savePrefs()
            startActivity(Intent(this, ProxyFinderActivity::class.java))
        }
        binding.save.setOnClickListener {
            savePrefs()
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
            AppLog.i("settings saved")
        }
        binding.control.setOnClickListener { toggleTunnel() }

        bindPrefsToUi()
        AppLog.i("ready; add a server link or SOCKS5 endpoints and tap Enable")
    }

    override fun onStart() {
        super.onStart()
        prefs.registerOnChange(prefsListener)
        AppLog.addListener(logListener)
        binding.links.setText(prefs.outboundLinks)
        binding.proxyList.setText(prefs.proxyList)
        updateControlState()
    }

    override fun onStop() {
        AppLog.removeListener(logListener)
        prefs.unregisterOnChange(prefsListener)
        super.onStop()
    }

    private fun toggleTunnel() {
        savePrefs()
        if (prefs.enabled) {
            TunnelService.enqueue(this, TunnelService.ACTION_DISCONNECT)
            return
        }
        if (!prefs.hasUpstream()) {
            Toast.makeText(this, R.string.upstream_required, Toast.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        startVpn()
    }

    private fun startVpn() {
        val prepare = VpnService.prepare(this)
        if (prepare != null) {
            AppLog.i("requesting VPN permission")
            vpnPermission.launch(prepare)
        } else {
            TunnelService.enqueue(this, TunnelService.ACTION_CONNECT)
        }
    }

    private fun bindPrefsToUi() {
        binding.links.setText(prefs.outboundLinks)
        binding.proxyList.setText(prefs.proxyList)
        binding.dns.setText(prefs.dnsServers)
        binding.global.isChecked = prefs.global
        updateControlState()
    }

    private fun savePrefs() {
        prefs.outboundLinks = binding.links.text?.toString().orEmpty().trim()
        prefs.proxyList = binding.proxyList.text?.toString().orEmpty().trim()
        prefs.dnsServers = binding.dns.text?.toString().orEmpty().trim()
        prefs.global = binding.global.isChecked
    }

    private fun updateControlState() {
        val editable = !prefs.enabled
        binding.tilLinks.isEnabled = editable
        binding.tilProxyList.isEnabled = editable
        binding.tilDns.isEnabled = editable
        binding.global.isEnabled = editable
        binding.apps.isEnabled = editable && !binding.global.isChecked
        binding.findProxies.isEnabled = editable
        binding.save.isEnabled = editable
        binding.control.setText(
            if (prefs.enabled) R.string.control_disable else R.string.control_enable
        )
    }
}
