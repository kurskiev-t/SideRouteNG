package com.kurskievtr.siderouteng

/** A single upstream SOCKS5 server. */
data class ProxyEndpoint(
    val host: String,
    val port: Int,
    val username: String = "",
    val password: String = ""
) {
    override fun toString(): String = "$host:$port"

    companion object {
        /**
         * Accepts `host:port`, `host:port:user:pass`, `user:pass@host:port` and the same forms
         * with a `socks5://` scheme. Blank lines and `#` comments return null.
         */
        fun parse(raw: String): ProxyEndpoint? {
            var line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return null
            line = line.substringAfter("://")
            var credentials = ""
            if (line.contains('@')) {
                credentials = line.substringBeforeLast('@')
                line = line.substringAfterLast('@')
            }
            val fields = line.split(':')
            if (fields.size !in 2..4) return null
            val host = fields[0].trim()
            val port = fields[1].trim().toIntOrNull() ?: return null
            if (host.isEmpty() || port !in 1..65535) return null
            if (fields.size == 4) {
                return ProxyEndpoint(host, port, fields[2].trim(), fields[3].trim())
            }
            if (credentials.isNotEmpty()) {
                return ProxyEndpoint(
                    host,
                    port,
                    credentials.substringBefore(':'),
                    credentials.substringAfter(':', "")
                )
            }
            return ProxyEndpoint(host, port)
        }

        fun parseList(text: String): List<ProxyEndpoint> =
            text.split('\n', ',', ';').mapNotNull { parse(it) }.distinct()
    }
}
