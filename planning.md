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
- VLESS finder: the same screen, fed by public aggregators; every candidate is dialled through a
  throwaway core and has to answer the check URL, so servers that only accept TCP are dropped.
  The selection goes into the server links field, never into the SOCKS5 pool.
- Explicit stop reasons in the log and an idempotent service shutdown.

### Verified on the device

- A working Reality upstream carries YouTube, including QUIC (`udp:443 [tun -> proxy-1]`).
- Public VPN endpoints differ a lot: some are reachable, others are blocked before the handshake.
  Reachability has to be measured from the phone, not from a server.

## Planned

### 1. Finder UI gaps (next)

- The latency limit is only applied while results arrive; changing it afterwards leaves the
  ticks as they were. It should re-tick, or filter, the rows already on screen.
- "Select all" and "Clear" are buttons; the usual Android pattern is one tri-state checkbox in
  the header that ticks and unticks everything and reflects the current selection.
- Show why a candidate failed (parse, handshake, timeout, bad answer) instead of hiding it, so a
  run that finds nothing can be diagnosed.
- The check proves TCP over the upstream. UDP/QUIC relaying is not verified separately yet.

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
