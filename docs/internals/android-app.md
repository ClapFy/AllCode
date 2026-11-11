# Android app

`apps/mobile` is AllCode Connect, a native Kotlin + Jetpack Compose client. It does not
use `packages/client-runtime` or `packages/contracts`: it reads and writes the wire format
itself. A server or contract change that TypeScript accepts can therefore still break it.

## What it depends on

- **Pairing.** A one-time credential from a pairing link, QR code, or `t3 pair` is exchanged
  at `/oauth/token` (`environment-bootstrap` token type) for a bearer token, kept sealed with
  an Android Keystore key. Each socket gets a fresh ticket from
  `/api/auth/websocket-ticket/rpc`; preview and device streams use `/media` tickets.
- **Socket.** Effect RPC with JSON serialization on `/ws?wsTicket=…&frameParts=1&orchestrationProtocol=2`.
  The client acks every `Chunk`, pings every 5 seconds, drops the socket after 15 seconds of
  silence, and joins frames split with the record-separator prefix. On returning to the
  foreground it pings once and drops the socket if no frame (normally the `Pong`) arrives
  within 4 seconds, so the server must keep answering `Ping` promptly.
- **Opt-ins.** It sends `acceptBoundedSnapshot` and pages older history through
  `/api/orchestration/threads/:threadId/history`. It never opts into compact turn items,
  deduped bodies, or compact ids, so the server must keep the plain shapes for clients
  that omit those flags.
- **Reducers.** `ShellState.kt` and `ThreadProjection.kt` port the snapshot and event rules
  of `state/shellReducer.ts` and `state/orchestrationV2Projection.ts`. A new event type that
  changes projection state needs a matching case there; unknown fields are ignored.

Breaking any of these needs an `ORCHESTRATION_PROTOCOL_VERSION` bump, which the app reports as
"update one side" instead of failing silently, and a change in `apps/mobile`.
`LiveServerTest` exercises the whole path against a dev server with the Replay provider.

## Deliberate limits

Connections are direct only: LAN, Tailscale, or any address the phone can reach. There is no
Clerk sign-in, T3 Connect relay, or relay push. Notifications come from the app's own
connection, kept open by a foreground service while agents work.
