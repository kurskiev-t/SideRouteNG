package com.kurskievtr.siderouteng

import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds Xray outbound objects.
 *
 * Two kinds of upstream are supported: a share link of a real proxy server (VLESS, VMess, Trojan,
 * Shadowsocks) and a plain SOCKS5 endpoint. Only the first kind relays UDP, which is what QUIC
 * needs; SOCKS5 endpoints are kept because free proxy lists give nothing else.
 */
object Outbound {
    const val USER_LEVEL = 8

    fun fromSocks(endpoint: ProxyEndpoint, tag: String): JSONObject {
        val server = JSONObject()
            .put("address", endpoint.host)
            .put("port", endpoint.port)
        if (endpoint.username.isNotEmpty()) {
            server.put(
                "users",
                JSONArray().put(
                    JSONObject()
                        .put("user", endpoint.username)
                        .put("pass", endpoint.password)
                        .put("level", USER_LEVEL)
                )
            )
        }
        return JSONObject()
            .put("tag", tag)
            .put("protocol", "socks")
            .put("settings", JSONObject().put("servers", JSONArray().put(server)))
    }

    /**
     * Domain of the server behind [link], or null when it is an address literal or an unknown
     * link. The tunnel has to reach these names without going through itself.
     */
    fun serverDomain(link: String): String? {
        val raw = link.trim()
        val host = when {
            raw.startsWith("vmess://") ->
                decodeBase64(raw.removePrefix("vmess://"))
                    ?.let { runCatching { JSONObject(it).optString("add") }.getOrNull() }
            raw.startsWith("ss://") -> {
                var body = raw.removePrefix("ss://").substringBefore('#')
                if (!body.contains('@')) body = decodeBase64(body).orEmpty()
                body.substringAfterLast('@').substringBefore('?').substringBeforeLast(':')
            }
            else -> runCatching { Uri.parse(raw).host }.getOrNull()
        }
        return host?.takeIf { it.isNotEmpty() && !isAddressLiteral(it) }
    }

    private fun isAddressLiteral(host: String): Boolean =
        host.contains(':') || host.all { it.isDigit() || it == '.' }

    /** Returns null when [link] is not a share link this build understands. */
    fun fromLink(link: String, tag: String): JSONObject? {
        val raw = link.trim()
        return when {
            raw.startsWith("vless://") -> vless(raw, tag)
            raw.startsWith("vmess://") -> vmess(raw, tag)
            raw.startsWith("trojan://") -> trojan(raw, tag)
            raw.startsWith("ss://") -> shadowsocks(raw, tag)
            raw.startsWith("socks://") || raw.startsWith("socks5://") ->
                ProxyEndpoint.parse(raw)?.let { fromSocks(it, tag) }
            else -> null
        }
    }

    private fun vless(link: String, tag: String): JSONObject? {
        val uri = Uri.parse(link)
        val host = uri.host ?: return null
        val id = uri.userInfo ?: return null
        val user = JSONObject()
            .put("id", id)
            .put("encryption", uri.getQueryParameter("encryption") ?: "none")
            .put("level", USER_LEVEL)
        uri.getQueryParameter("flow")?.let { if (it.isNotEmpty()) user.put("flow", it) }
        val settings = JSONObject().put(
            "vnext",
            JSONArray().put(
                JSONObject()
                    .put("address", host)
                    .put("port", uri.port.takeIf { it > 0 } ?: 443)
                    .put("users", JSONArray().put(user))
            )
        )
        return JSONObject()
            .put("tag", tag)
            .put("protocol", "vless")
            .put("settings", settings)
            .put("streamSettings", streamSettings(uri, host))
    }

    private fun trojan(link: String, tag: String): JSONObject? {
        val uri = Uri.parse(link)
        val host = uri.host ?: return null
        val password = uri.userInfo ?: return null
        val settings = JSONObject().put(
            "servers",
            JSONArray().put(
                JSONObject()
                    .put("address", host)
                    .put("port", uri.port.takeIf { it > 0 } ?: 443)
                    .put("password", password)
                    .put("level", USER_LEVEL)
            )
        )
        return JSONObject()
            .put("tag", tag)
            .put("protocol", "trojan")
            .put("settings", settings)
            .put("streamSettings", streamSettings(uri, host, defaultSecurity = "tls"))
    }

    /** `ss://base64(method:password)@host:port#name` and the fully base64-encoded legacy form. */
    private fun shadowsocks(link: String, tag: String): JSONObject? {
        var body = link.removePrefix("ss://").substringBefore('#')
        if (!body.contains('@')) body = decodeBase64(body) ?: return null
        val credentials = body.substringBeforeLast('@')
        val address = body.substringAfterLast('@').substringBefore('?')
        val decoded = if (credentials.contains(':')) credentials else decodeBase64(credentials)
        val method = decoded?.substringBefore(':') ?: return null
        val password = decoded.substringAfter(':', "")
        val host = address.substringBeforeLast(':')
        val port = address.substringAfterLast(':').toIntOrNull() ?: return null
        if (host.isEmpty() || password.isEmpty()) return null
        val settings = JSONObject().put(
            "servers",
            JSONArray().put(
                JSONObject()
                    .put("address", host)
                    .put("port", port)
                    .put("method", method)
                    .put("password", password)
                    .put("level", USER_LEVEL)
            )
        )
        return JSONObject()
            .put("tag", tag)
            .put("protocol", "shadowsocks")
            .put("settings", settings)
            .put("streamSettings", JSONObject().put("network", "tcp"))
    }

    /** `vmess://` carries a base64 JSON blob instead of a URI. */
    private fun vmess(link: String, tag: String): JSONObject? {
        val json = decodeBase64(link.removePrefix("vmess://")) ?: return null
        val blob = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val host = blob.optString("add").ifEmpty { return null }
        val port = blob.optString("port").toIntOrNull() ?: return null
        val user = JSONObject()
            .put("id", blob.optString("id"))
            .put("alterId", blob.optString("aid", "0").toIntOrNull() ?: 0)
            .put("security", blob.optString("scy", "auto").ifEmpty { "auto" })
            .put("level", USER_LEVEL)
        val settings = JSONObject().put(
            "vnext",
            JSONArray().put(
                JSONObject()
                    .put("address", host)
                    .put("port", port)
                    .put("users", JSONArray().put(user))
            )
        )
        val network = blob.optString("net", "tcp").ifEmpty { "tcp" }
        val stream = JSONObject().put("network", network)
        val security = blob.optString("tls")
        if (security == "tls") {
            stream.put("security", "tls")
            val sni = blob.optString("sni").ifEmpty { blob.optString("host").ifEmpty { host } }
            stream.put("tlsSettings", JSONObject().put("serverName", sni))
        }
        when (network) {
            "ws" -> stream.put(
                "wsSettings",
                JSONObject()
                    .put("path", blob.optString("path", "/"))
                    .put("host", blob.optString("host"))
            )
            "grpc" -> stream.put(
                "grpcSettings",
                JSONObject().put("serviceName", blob.optString("path"))
            )
        }
        return JSONObject()
            .put("tag", tag)
            .put("protocol", "vmess")
            .put("settings", settings)
            .put("streamSettings", stream)
    }

    private fun streamSettings(
        uri: Uri,
        host: String,
        defaultSecurity: String = "none"
    ): JSONObject {
        val network = uri.getQueryParameter("type") ?: "tcp"
        val security = uri.getQueryParameter("security") ?: defaultSecurity
        val stream = JSONObject().put("network", network).put("security", security)
        val sni = uri.getQueryParameter("sni") ?: uri.getQueryParameter("host") ?: host
        when (security) {
            "tls" -> {
                val tls = JSONObject()
                    .put("serverName", sni)
                    .put("allowInsecure", uri.getQueryParameter("allowInsecure") == "1")
                uri.getQueryParameter("fp")?.let { tls.put("fingerprint", it) }
                uri.getQueryParameter("alpn")?.let {
                    tls.put("alpn", JSONArray(it.split(',').map(String::trim)))
                }
                stream.put("tlsSettings", tls)
            }
            "reality" -> {
                val reality = JSONObject()
                    .put("serverName", sni)
                    .put("publicKey", uri.getQueryParameter("pbk").orEmpty())
                    .put("shortId", uri.getQueryParameter("sid").orEmpty())
                    .put("spiderX", uri.getQueryParameter("spx").orEmpty())
                    .put("fingerprint", uri.getQueryParameter("fp") ?: "chrome")
                stream.put("realitySettings", reality)
            }
        }
        when (network) {
            "ws" -> stream.put(
                "wsSettings",
                JSONObject()
                    .put("path", uri.getQueryParameter("path") ?: "/")
                    .put("host", uri.getQueryParameter("host") ?: sni)
            )
            "grpc" -> stream.put(
                "grpcSettings",
                JSONObject().put("serviceName", uri.getQueryParameter("serviceName").orEmpty())
            )
            "xhttp", "splithttp" -> stream.put(
                "xhttpSettings",
                JSONObject()
                    .put("path", uri.getQueryParameter("path") ?: "/")
                    .put("host", uri.getQueryParameter("host") ?: sni)
                    .put("mode", uri.getQueryParameter("mode") ?: "auto")
            )
        }
        return stream
    }

    private fun decodeBase64(value: String): String? {
        val flags = Base64.NO_WRAP or Base64.URL_SAFE
        return runCatching { String(Base64.decode(value, flags)) }.getOrNull()
    }
}
