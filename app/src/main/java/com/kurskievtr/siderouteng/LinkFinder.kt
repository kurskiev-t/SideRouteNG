package com.kurskievtr.siderouteng

import android.content.Context
import android.util.Base64

/**
 * Collects free Xray links from public aggregators and keeps the ones that carry a real request.
 *
 * A link cannot be judged by opening a socket: with Reality the TCP connection succeeds even when
 * the handshake is later blocked, so every candidate is dialled through a throwaway core and has
 * to answer the check URL. That is slow, hence the candidate cap and the early stop.
 */
class LinkFinder(context: Context, listener: Listener) : Finder(listener) {
    private val context = context.applicationContext

    override val concurrency: Int = 8
    override val enough: Int = 20

    override fun collect(): List<Candidate> {
        val links = LinkedHashMap<String, String>()
        for (source in SOURCES) {
            val text = download(source)?.let(::decodeIfBase64)
            if (text == null) {
                AppLog.w("link source failed: $source")
                continue
            }
            val found = LINK.findAll(text)
                .map { it.value.trimEnd(',', '"', '\'') }
                .filter { Outbound.fromLink(it, "probe") != null }
                .toList()
            found.forEach { links.putIfAbsent(it.substringBefore('#'), it) }
            AppLog.i("link source ${found.size} candidates: $source")
        }
        val (reality, rest) = links.values.partition { it.contains("security=reality") }
        return (reality.shuffled() + rest.shuffled())
            .take(MAX_CANDIDATES)
            .map { Candidate(label(it), it) }
    }

    override fun measure(candidate: Candidate): Long? =
        XrayCore.measureLink(context, candidate.value, CHECK_URL)

    /** Host and port of the server, plus the transport, which is all the table has room for. */
    private fun label(link: String): String {
        val body = link.substringBefore('#').substringAfter("://").substringAfter('@')
        val address = body.substringBefore('?').trimEnd('/')
        val security = SECURITY.find(link)?.groupValues?.get(1) ?: "tls"
        return "$address $security"
    }

    private fun decodeIfBase64(text: String): String {
        if (text.contains("://")) return text
        val decoded = runCatching {
            String(Base64.decode(text.trim(), Base64.DEFAULT or Base64.NO_WRAP))
        }.getOrNull()
        return decoded?.takeIf { it.contains("://") } ?: text
    }

    private companion object {
        /** Aggregators that republish free configs collected from public channels. */
        private val SOURCES = listOf(
            "https://raw.githubusercontent.com/Epodonios/v2ray-configs/main/" +
                "Splitted-By-Protocol/vless.txt",
            "https://raw.githubusercontent.com/MhdiTaheri/V2rayCollector/main/sub/vless",
            "https://raw.githubusercontent.com/ALIILAPRO/v2rayNG-Config/main/sub.txt"
        )
        private val LINK = Regex("""\b(?:vless|vmess|trojan|ss)://\S+""")
        private val SECURITY = Regex("""security=(\w+)""")
        private const val MAX_CANDIDATES = 400
    }
}
