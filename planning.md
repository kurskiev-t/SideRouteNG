# SideRouteNG — state and plans

## Where we are

The tunnel is a `VpnService` whose TUN descriptor is handed straight to Xray-core, so the whole
device traffic — TCP and UDP/QUIC alike — enters the core and leaves through the configured
outbound. Two kinds of upstream are supported: Xray-native links (`vless://`, `vmess://`,
`trojan://`, `ss://`) and a pool of public SOCKS5 endpoints. Native links take priority when both
are filled in.

### Done

- TUN inbound with sniffing, TUN fd passed to the core, own package excluded from the VPN.
- Outbound parsing for vless (incl. Reality/XTLS), vmess, trojan, shadowsocks, socks.
- Balancer with the observatory (`leastPing`) when more than one upstream is configured.
- Upstream server domains resolved by the platform and routed `direct`, so the core never has to
  reach its own upstream through the tunnel.
- Per-app routing (include/exclude list), configurable DNS servers and MTU, log view.
- "Test upstream": a one-off `measureOutboundDelay` probe with the server address pre-resolved.
- SOCKS5 finder: downloads public lists, checks every candidate against a real YouTube request,
  shows a table with latency and checkboxes, writes the selection into the pool field.
- Explicit stop reasons in the log and an idempotent service shutdown.

### Verified on the device

- A working Reality upstream carries YouTube, including QUIC (`udp:443 [tun -> proxy-1]`).
- Public VPN endpoints differ a lot: some are reachable, others are blocked before the handshake.
  Reachability has to be measured from the phone, not from a server.

## Planned

### 1. Finder for free Xray links (next)

Same UI as the SOCKS5 finder, separate entry point, result goes into the server links field.

- Sources: public aggregators that republish free vless/vmess/trojan configs, plain or base64
  (Epodonios/v2ray-configs, MhdiTaheri/V2rayCollector, mahdibland/V2RayAggregator).
- Parsing: decode base64 when needed, keep the protocols the app can build, deduplicate by
  address plus credentials, prefer Reality on 443.
- Checking: `measureOutboundDelay` per candidate with a small thread pool, because a link cannot
  be checked with a plain socket — the handshake is the thing that fails.
- Because the lists hold thousands of entries and roughly a third answer, the run is capped at a
  configurable number of candidates and stops early once enough live ones are found.

### 2. Self-maintaining pool

- Recheck the pool in the background while the tunnel runs.
- Mark a failing upstream instead of dropping it, and remove it after three failed rechecks.
- Refill automatically: when the pool holds fewer than the configured minimum, start a search.
- Persist the last known latency and failure count with the pool.

### 3. Subscription support

Accept a subscription URL, fetch and decode it, and refresh the links on demand. This is how
providers hand out keys, so it removes the manual copying of individual links.

### 4. Diagnostics in the UI

Show the active upstream and its traffic counters (`queryAllOutboundTrafficStats` is available in
libv2ray) instead of reading the log to find out which upstream is in use.

### Not planned for now

- Hysteria2 and TUIC: not supported by Xray-core, they need a second engine.
- `happ://crypt4/...` subscriptions: an encrypted format only the Happ client can read.
