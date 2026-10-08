# TwoDo — plan

A to-do list app for Android whose lists are shared directly between phones.
No account, no server we run, no subscription.

## Goals (first pass)

- Create a list, share it with one or more other Android devices via QR code (or a pasted link).
- Every device can add, check/uncheck and delete entries.
- Sync over the internet between devices on different networks.
  - Primary case: both phones online at the same time → changes appear live.
  - When a device comes (back) online it automatically reconnects and catches up with any peer that is online.
- Conflict handling: latest timestamp wins; both devices are told when their edit was overridden.

## Non-goals (for now)

- Store-and-forward when the two devices are never online at the same time (needs a relay or an always-on peer).
- Nearby/Bluetooth sync, desktop/iOS clients, list renaming, reordering, rich text.
- Per-field merging (CRDTs) — revisit if whole-item LWW proves too coarse.

## Architecture

```
UI (Jetpack Compose)
   │
ListRepository ── JSON file per list, StateFlow, emits local edits + conflicts
   │
SyncManager ── one ListSwarm per list, encrypted sync protocol, forwards accepted changes
   │
ListSwarm ── finds peers via public WebTorrent trackers, connects with WebRTC data channels
```

### Transport: WebRTC + public WebTorrent trackers

- Phones on mobile data sit behind NAT, so they need a meeting point to find each other.
  We use **public WebTorrent trackers** (WebSocket) purely as a rendezvous/signalling channel —
  the same approach as Trystero. We don't run them; several are used for redundancy.
- The actual data flows **directly phone-to-phone** over a WebRTC data channel
  (public STUN servers for NAT hole punching).
- The tracker "room" is derived from the list secret, so only devices that have the QR code meet.
- Known limitation: when *both* phones are behind symmetric/carrier-grade NAT, hole punching can fail.
  A TURN relay is the standard fix — left as an optional setting for later.
- The transport sits behind `ListSwarm`, so it could later be replaced by Hyperswarm, iroh, etc.

### Security

- Each list has a random 256-bit secret, carried in the QR code / link (`twodo://join?id=…&name=…&k=…`).
- Tracker room id = hash of the secret (the secret itself never leaves the device except via the QR code).
- All sync messages are AES-256-GCM encrypted with a key derived from the secret, on top of WebRTC's DTLS.
  A peer that can't decrypt is dropped.
- Anyone holding the QR code can read and edit the list — same model as a shared link.

### Data model and conflict resolution

Each item is replaced as a whole on every edit:

```
Item(id, text, checked, deleted, createdAt, version=(ts, deviceId), editor, history=[previous versions…])
```

- An edit creates a new version `(max(now, prev.ts + 1), deviceId)` and pushes the old version onto `history` (capped at 32).
- Merging a remote item with the local one:
  - local version is in remote `history` → remote is newer, take it (no conflict);
  - remote version is in local `history` → we're already ahead, ignore;
  - otherwise the edits were made **concurrently** (e.g. both offline) → **latest timestamp wins**
    (device id breaks ties), the losing version is remembered so it is never re-applied, and a
    conflict notification is raised. The other device detects the same conflict when it receives
    our copy, so **both users are notified**.
- Deletes are tombstones (`deleted = true`) so they sync like any other edit.

### Sync protocol (over the data channel, encrypted JSON)

- `hello {deviceId, deviceName, listName}` on connect.
- `items {items, full}` — full state on connect, single items on each local edit.
- Changes accepted from one peer are forwarded to the other connected peers (3+ devices).

### Staying connected

- App in foreground → connected to every list's swarm.
- Network comes back → trackers reconnect immediately and re-announce.
- Optional "Sync in background" setting → foreground service keeps connections up (with a persistent notification).
- WorkManager job every 15 min (when online) runs a short sync window as a best effort.

## Milestones

1. **First pass (this)** — model + merge with unit tests, storage, transport, sync, Compose UI, QR share/join, conflict notifications.
2. Test on two real phones on different networks (Wi-Fi + mobile data); measure connection success rate.
3. Optional TURN relay setting; better connection diagnostics.
4. Nearby/LAN sync for when phones are together; offline delivery via an opt-in relay or always-on peer.
5. Play Store / F-Droid release.
