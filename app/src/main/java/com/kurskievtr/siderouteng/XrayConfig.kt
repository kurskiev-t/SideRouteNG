package com.kurskievtr.siderouteng

import java.net.InetAddress
import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds the Xray-core configuration for the tunnel.
 *
 * The TUN file descriptor is handed to the core through an environment variable, so the whole
 * device traffic — TCP and UDP alike — enters the core through the `tun` inbound and leaves
 * through whichever outbound the routing picks. When several upstreams are configured they are
 * grouped into a balancer whose observatory keeps probing them and prefers the fastest live one.
 */
object XrayConfig {
    const val TUN_NAME = "srng0"
    const val PROXY_PREFIX = "proxy-"
    private const val BALANCER_TAG = "upstream"
    const val PROBE_URL = "https://www.gstatic.com/generate_204"

    class Result(val json: String, val outbounds: Int)

    fun build(prefs: TunnelPrefs): Result? {
        val links = links(prefs)
        val proxies = outbounds(prefs, links)
        if (proxies.isEmpty()) return null
        val serverDomains = if (links.isEmpty()) emptyList()
        else links.mapNotNull { Outbound.serverDomain(it) }.distinct()

        val config = JSONObject()
            .put("log", JSONObject().put("loglevel", prefs.logLevel))
            .put("inbounds", JSONArray().put(tunInbound(prefs)))
            .put("policy", policy())
            .put("dns", dns(prefs, serverDomains, overTcp = links.isEmpty()))

        val outbounds = JSONArray()
        proxies.forEach { outbounds.put(it) }
        outbounds.put(JSONObject().put("tag", "direct").put("protocol", "freedom"))
        outbounds.put(JSONObject().put("tag", "dns-out").put("protocol", "dns"))
        outbounds.put(JSONObject().put("tag", "block").put("protocol", "blackhole"))
        config.put("outbounds", outbounds)

        val rules = JSONArray()
        // The upstream servers themselves must never be routed into the tunnel, otherwise
        // resolving and dialing them would depend on a connection that does not exist yet.
        if (serverDomains.isNotEmpty()) {
            rules.put(
                JSONObject()
                    .put("type", "field")
                    .put("domain", JSONArray(serverDomains.map { "full:$it" }))
                    .put("outboundTag", "direct")
            )
        }
        rules.put(
            JSONObject()
                .put("type", "field")
                .put("inboundTag", JSONArray().put("tun"))
                .put("port", "53")
                .put("outboundTag", "dns-out")
        )
        val routing = JSONObject()
            .put("domainStrategy", "AsIs")
            .put("rules", rules)
        if (proxies.size > 1) {
            rules.put(
                JSONObject()
                    .put("type", "field")
                    .put("network", "tcp,udp")
                    .put("balancerTag", BALANCER_TAG)
            )
            routing.put(
                "balancers",
                JSONArray().put(
                    JSONObject()
                        .put("tag", BALANCER_TAG)
                        .put("selector", JSONArray().put(PROXY_PREFIX))
                        .put("strategy", JSONObject().put("type", "leastPing"))
                )
            )
            config.put(
                "observatory",
                JSONObject()
                    .put("subjectSelector", JSONArray().put(PROXY_PREFIX))
                    .put("probeUrl", PROBE_URL)
                    .put("probeInterval", "30s")
                    .put("enableConcurrency", true)
            )
        } else {
            rules.put(
                JSONObject()
                    .put("type", "field")
                    .put("network", "tcp,udp")
                    .put("outboundTag", proxies[0].getString("tag"))
            )
        }
        config.put("routing", routing)
        return Result(config.toString(2), proxies.size)
    }

    /**
     * Configuration for a one-off upstream latency probe: one outbound, no tunnel and no DNS.
     *
     * Server addresses are resolved here, by the platform resolver, because a bare core has no
     * resolver of its own on Android and would never reach a server given by domain.
     */
    fun buildProbe(prefs: TunnelPrefs): String? {
        val proxy = outbounds(prefs, links(prefs)).firstOrNull() ?: return null
        resolveServers(proxy)
        return JSONObject()
            .put("log", JSONObject().put("loglevel", prefs.logLevel))
            .put("outbounds", JSONArray().put(proxy))
            .toString(2)
    }

    private fun resolveServers(outbound: JSONObject) {
        val settings = outbound.optJSONObject("settings") ?: return
        val servers = settings.optJSONArray("vnext") ?: settings.optJSONArray("servers") ?: return
        for (i in 0 until servers.length()) {
            val server = servers.optJSONObject(i) ?: continue
            val address = server.optString("address")
            if (address.isEmpty()) continue
            val resolved = runCatching { InetAddress.getByName(address).hostAddress }.getOrNull()
            if (resolved == null) {
                AppLog.w("cannot resolve the upstream server address")
            } else {
                server.put("address", resolved)
            }
        }
    }

    private fun links(prefs: TunnelPrefs): List<String> = prefs.outboundLinks
        .split('\n')
        .map(String::trim)
        .filter { it.isNotEmpty() && !it.startsWith("#") }

    private fun outbounds(prefs: TunnelPrefs, links: List<String>): List<JSONObject> {
        val fromLinks = links.mapIndexedNotNull { index, link ->
            val outbound = Outbound.fromLink(link, "$PROXY_PREFIX${index + 1}")
            if (outbound == null) AppLog.w("cannot parse server link #${index + 1}")
            outbound
        }
        if (fromLinks.isNotEmpty()) return fromLinks
        return ProxyEndpoint.parseList(prefs.proxyList).mapIndexed { index, endpoint ->
            Outbound.fromSocks(endpoint, "$PROXY_PREFIX${index + 1}")
        }
    }

    private fun tunInbound(prefs: TunnelPrefs): JSONObject = JSONObject()
        .put("tag", "tun")
        .put("protocol", "tun")
        .put(
            "settings",
            JSONObject()
                .put("name", TUN_NAME)
                .put("MTU", prefs.mtu)
                .put("userLevel", Outbound.USER_LEVEL)
        )
        .put(
            "sniffing",
            JSONObject()
                .put("enabled", true)
                .put(
                    "destOverride",
                    JSONArray().put("http").put("tls").put("quic")
                )
        )

    /**
     * [overTcp] is for SOCKS5 upstreams: they have no UDP relay, so plain UDP/53 would be lost.
     * A real server relays UDP, and asking over UDP is one round trip instead of three.
     *
     * The upstream domains are resolved by the system resolver, which is outside the tunnel.
     */
    private fun dns(prefs: TunnelPrefs, serverDomains: List<String>, overTcp: Boolean): JSONObject {
        val servers = JSONArray()
        if (serverDomains.isNotEmpty()) {
            servers.put(
                JSONObject()
                    .put("address", "localhost")
                    .put("domains", JSONArray(serverDomains.map { "full:$it" }))
            )
        }
        prefs.dnsServers
            .split(',', '\n')
            .map(String::trim)
            .filter { it.isNotEmpty() }
            .forEach { servers.put(if (it.contains("://") || !overTcp) it else "tcp://$it") }
        return JSONObject()
            .put("servers", servers)
            .put("queryStrategy", "UseIPv4")
            .put("disableFallback", false)
    }

    private fun policy(): JSONObject = JSONObject()
        .put(
            "levels",
            JSONObject().put(
                Outbound.USER_LEVEL.toString(),
                JSONObject()
                    .put("handshake", 4)
                    .put("connIdle", 300)
                    .put("uplinkOnly", 1)
                    .put("downlinkOnly", 1)
            )
        )
}
