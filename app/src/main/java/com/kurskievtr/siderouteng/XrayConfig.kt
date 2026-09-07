package com.kurskievtr.siderouteng

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
    private const val PROBE_URL = "https://www.gstatic.com/generate_204"

    class Result(val json: String, val outbounds: Int)

    fun build(prefs: TunnelPrefs): Result? {
        val proxies = outbounds(prefs)
        if (proxies.isEmpty()) return null

        val config = JSONObject()
            .put("log", JSONObject().put("loglevel", prefs.logLevel))
            .put("inbounds", JSONArray().put(tunInbound(prefs)))
            .put("policy", policy())
            .put("dns", dns(prefs))

        val outbounds = JSONArray()
        proxies.forEach { outbounds.put(it) }
        outbounds.put(JSONObject().put("tag", "direct").put("protocol", "freedom"))
        outbounds.put(JSONObject().put("tag", "dns-out").put("protocol", "dns"))
        outbounds.put(JSONObject().put("tag", "block").put("protocol", "blackhole"))
        config.put("outbounds", outbounds)

        val rules = JSONArray()
        // Plain UDP/53 dies on SOCKS5 upstreams without a UDP relay, so DNS is answered by the
        // core's own resolver, which talks to the resolvers over TCP through the proxy.
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

    private fun outbounds(prefs: TunnelPrefs): List<JSONObject> {
        val links = prefs.outboundLinks
            .split('\n')
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }
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

    private fun dns(prefs: TunnelPrefs): JSONObject {
        val servers = JSONArray()
        prefs.dnsServers
            .split(',', '\n')
            .map(String::trim)
            .filter { it.isNotEmpty() }
            .forEach { servers.put(if (it.contains("://")) it else "tcp://$it") }
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
