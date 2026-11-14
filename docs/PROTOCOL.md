# FastDrop wire protocol

Plain TCP on the local network. **No encryption on the data path:** TVs have weak CPUs, and
decrypting the stream made them the bottleneck (and failed on some), so bytes are sent as-is.
Everything protocol 2 adds is a *per-connection* cost (one HMAC, one hash of the file list),
never a per-byte cost — except the optional CRC32 check, which is off by default.

Ports: `53530` TCP (transfers), `53531` UDP (discovery), multicast group `224.0.0.168`.
All integers are big-endian. `[len][json]` = 4-byte signed length + UTF-8 JSON.

## Protocol 1 (FastDrop 2.0 — unchanged, still supported)

Every TCP connection starts with a 4-byte magic.

| Magic | Name | Flow |
|---|---|---|
| `0x46444831` "FDH1" | HELLO | client → magic; server → `[len][device info json]`, close |
| `0x46444331` "FDC1" | CTRL | sender → magic + `[len][offer]`; receiver → `[len][reply]`; after all bytes land, receiver → `[len]{"ok":true}` |
| `0x46444431` "FDD1" | DATA | sender opens N in parallel: magic + `[int idLen][id bytes]`, then frames `[int fileIndex][long offset][int length][payload]`, ended by fileIndex `-1` (offset 0, length 0) |

Files are cut into **8 MB chunks** (`chunk = 8388608`). Every DATA frame carries exactly one
chunk: `offset` is a multiple of 8 MB and `length` is 8 MB except for a file's last chunk.
Chunk index = `offset / 8388608`.

Device info (HELLO): `{"app":"fastdrop","v":1,"id","name","type","port","receivable"}`.

Offer v1: `{"id": sessionId, "name", "type", "files":[{"name","size","app"?}]}`.
Reply v1: `{"accept": bool, "reason": "" | "busy" | "closed" | "timeout" | "invalid" | "storage" | "declined"}`.

## Protocol 2 additions (FastDrop 2.1)

Rule: **every new field is optional**. A v2 peer talking to a v1 peer behaves exactly like v1.
A side only uses a v2 feature when the other side announced it (offer `"v":2` / reply `"v":2`).

### Device info

Adds `"v": 2` and `"caps": ["text","resume","trust","crc","ctl"]` (`"ctl"` since FastDrop 2.3, see section 7).

### Offer (sender → receiver)

```json
{
  "id": "<session uuid>", "name": "Pixel 8", "type": "phone",
  "v": 2,
  "dev": "<sender's stable device id — the same id its HELLO reports>",
  "ts": 1731400000000,
  "chunk": 8388608,
  "files": [{"name": "Movies/a.mp4", "size": 123, "mtime": 1731300000000, "app": {...}}],
  "text": "optional — see Text",
  "crc": true,
  "auth": "<64 hex chars — see Trust>"
}
```

- `mtime` (ms since epoch, optional): source file's last-modified time; used only for resume keys.
- `ts` (ms since epoch): when the offer was made; used only by `auth`.

### Reply (receiver → sender)

```json
{"accept": true, "reason": "", "v": 2, "dev": "<receiver's stable id>",
 "have": {"0": [[0, 12], [15, 20]]}, "crc": true, "trust": "<64 hex chars>"}
```

- `dev`: the receiver's stable id. The sender keys stored trust secrets by this value.
- `have`, `crc`, `trust`: see below. Omitted when not used.

### 1. Text, links and clipboard

- Offer with `"text": "<string>"` and `"files": []`. Max 100 000 characters; the receiver
  rejects longer text with reason `"invalid"`.
- Receiver shows it like a file offer (or auto-accepts by the trust rules), then replies
  `{"accept":true,...}` and **immediately** `{"ok":true}` on the same connection. No DATA streams.
- The receiver keeps the text in its history/recent list with **Copy** and, when the whole text is
  an `http(s)://` URL, **Open**.
- A v1 receiver answers `"invalid"` (empty file list). Senders show
  "*<name>* needs FastDrop 2.1 or newer to receive text". If the target's HELLO said `v < 2`,
  senders may refuse up front with the same message.

### 2. Confirmation code (first-time senders)

Both sides derive a 4-digit code from the session id, no extra messages:

```
code = (first 4 bytes of SHA-256(UTF-8(id)) read as an unsigned big-endian int) mod 10000
display zero-padded to 4 digits, e.g. "0427"
```

The sender shows "Code 0427" while waiting for Accept; the receiver shows the same code in the
offer prompt so the user can confirm it's the device they expect. Not shown for offers that were
auto-accepted.

### 3. Trusted devices ("Always accept from this device")

Trust is **directional**: receiver R trusts sender S.

**Pairing.** The receiver's offer prompt has an "Always accept from *S*" option. If the user
accepts with it ticked, the receiver:
1. generates a random 32-byte key,
2. stores `trusted[S.dev] = {name, key, added}`,
3. returns `"trust": hex(key)` in the accept reply.

The sender stores `keys[reply.dev] = key` (keyed by the receiver's `dev` from the reply).

The key crosses the network once, in clear, at pairing time. That is the deliberate trade-off for
no encryption: anyone who can sniff Wi-Fi at that exact moment could copy it. Removing the device
from the trusted list on the receiver revokes it.

**Authenticated offers.** When the sender has a key for the target receiver id, it adds:

```
filesDigest = hex(SHA-256(UTF-8( for each file in order: name + "\n" + size + "\n"
                                 then, if text is present: "T\n" + text )))
canonical   = "fastdrop-auth-v1\n" + id + "\n" + dev + "\n" + ts + "\n" + filesDigest
auth        = hex(HMAC-SHA256(key, UTF-8(canonical)))          // lowercase hex
```

The receiver treats the offer as **trusted** only if all of these hold:
- `trusted[dev]` exists and `auth` matches (compared in constant time),
- `|now − ts| ≤ 24 h`,
- this `id` hasn't been seen in an authenticated offer in the last 24 h. The replay cache is
  **persisted** in the receiver's state folder (session id + time, capped at about 2000 entries,
  entries older than 24 h pruned), so restarting the receiver doesn't let a captured offer be replayed.

Otherwise the offer is treated as untrusted: a normal prompt, never an error. If `trusted[dev]`
already exists but the offer's auth is missing or invalid (someone may be spoofing `dev`), the prompt
**must not offer "always trust"**, and an offer never overwrites an existing trusted entry: the user
removes the device first to re-pair. A sender whose key
was revoked just sees the prompt again, and can be re-trusted, which issues a new key.

**Auto-accept setting** (replaces the old on/off switch):

| Setting | Untrusted offer | Trusted offer |
|---|---|---|
| Ask every time | prompt | prompt (pre-labelled as trusted) |
| **Trusted devices** (default) | prompt | accepted without prompting |
| Everyone | accepted | accepted |

Migration: an old `autoAccept = true` becomes **Everyone**; otherwise **Trusted devices**.
Settings lists trusted devices (name, date added) with a Remove action.

### 4. Resume interrupted transfers

Used only when the offer has `"v": 2`, `"dev"`, and `"chunk": 8388608`.

**Resume needs `mtime`.** The receiver offers `have` / resume only for files whose offer entry has
a positive `mtime`. A file without one (missing, 0 or negative) always starts fresh and keeps no
resume record. Senders therefore omit `mtime` when they don't know it. Reason: without an mtime
the resumeKey only covers name and size, so an edited file of the same size would match the old
partial and the receiver would splice two versions of the file into one corrupt result. (The
resumeKey formula and its test vectors are unchanged; the "no mtime" vector remains for the hash
itself.)

**Receiver bookkeeping.** For each incoming file:

```
resumeKey = hex(SHA-256(UTF-8( dev + "\n" + name + "\n" + size + "\n" + (mtime or "") )))
record    = {key, location of the partial file, size, chunks: done chunk ranges, updated}
```

A chunk counts as done only after its whole payload has been written (and passed CRC, if on).
Record ranges are half-open chunk-index intervals `[start, end)`, sorted and merged.

- If a transfer ends without finishing (fail, cancel, sender gone, app killed), unfinished files
  with at least one done chunk **keep their partial file**, and their record is saved. The
  receiver also checkpoints records about every 10 s while a transfer runs, so a killed app can
  still resume.
- Each file slot gets its **own partial name**, so two offered files with the same name (for
  example `a/x.bin` and `b/x.bin` flattened to `x.bin`) or two senders never share one partial.
  Recommended form: `.<name>.<resumeKey[:12] or sessionId[:8]>.<slotIndex>.fdpart`. The record stores
  the exact partial path, and before reuse the receiver checks that the partial belongs to that
  key (same folder, same name, key tag). Final names are de-duplicated (`x.bin`, `x (1).bin`).
- Partials stay invisible as finished files: Android keeps the MediaStore row `IS_PENDING = 1`
  (or a hidden temp file before Android 10), and the Mac writes to a hidden partial file that is
  renamed when complete.
- A record is deleted when its file completes. Records older than 7 days are deleted at
  startup along with their partial files.

**Handshake.** When a v2 offer arrives and records exist for some files (and their partial files
still exist), the accept reply carries:

```json
"have": {"<fileIndex>": [[startChunk, endChunk], ...]}
```

The receiver reopens those partial files instead of creating new ones and pre-counts the
bytes of done chunks (the last chunk may be shorter than 8 MB).

**Sender.** Leaves out every chunk listed in `have`. Progress starts at the bytes already there
("Resuming at 63 %"), and speed/ETA count only bytes sent in this session.

The **Retry** button after a failed send re-sends the same items to the same device, so a retry
resumes automatically.

### 5. Optional per-chunk CRC32 (off by default)

Setting: **Verify data (CRC32)**. It costs CPU: the sender loses zero-copy `sendfile`, and both
sides checksum every byte.

- Sender with the setting on adds `"crc": true` to the offer.
- A v2 receiver agrees by replying `"crc": true`. If not agreed, frames are v1.
- When agreed, every DATA frame's payload is followed by **4 bytes: CRC32 (IEEE / zlib) of that
  payload**, unsigned, big-endian. The end marker (fileIndex −1) has no CRC.
- A mismatch fails the transfer with "Data check failed". The bad chunk is not marked done, so
  Retry resumes and re-sends only what's missing.

### 6. Sending to several devices

Not a wire feature. The sender UI lets the user pick several devices. It runs one normal
transfer per device, one after another (only one transfer runs at a time), then shows a summary
such as "Sent to 2 of 3 devices".

### 7. Pause, resume and skipping a file (FastDrop 2.3, capability `"ctl"`)

Lets either side pause / resume a running transfer, or drop one file from it, without restarting.
It adds **no per-byte work**: a few small JSON messages on the CTRL connection, nothing on DATA.

**Negotiation.** A sender that supports it puts `"ctl": true` in a v2 file offer (never in a
text offer). A receiver that supports it answers `"ctl": true` in the accept reply. Only when both
did may either side send the messages below. Otherwise nothing changes (a 2.1/2.2 peer never sees
them), and UIs hide Pause and per-file Skip.

**Framing.** After the accept reply the CTRL connection is full duplex: each side reads
`[len][json]` messages from it until the transfer ends, and serialises its own writes (one
writer at a time). Messages carry `"t"`; unknown `"t"` values are ignored. The receiver's final
message stays `{"ok": true, ...}` (no `"t"`) and still ends the exchange.

| Message | Meaning |
|---|---|
| `{"t":"pause"}` | Pause the transfer. |
| `{"t":"resume"}` | Resume it. |
| `{"t":"skip","file":i}` | Drop file index `i` from this transfer. |

**Pause.** One shared state: the latest `pause` / `resume` from either side wins, both sides show
"Paused", and either can resume. While paused:
- the sender writes no new DATA frames (a frame already started is finished, so framing stays
  intact) and leaves its DATA connections open and idle;
- neither side counts the paused time against its no-progress watchdog (2.x: 30 s without data
  fails a transfer). Watchdogs restart from the moment the transfer resumes;
- neither side applies a read timeout to the CTRL connection (with `ctl`, CTRL reads never time out
  while the transfer runs; stalls are detected by the data watchdog as before).
There is no time limit on a pause; the user can still Cancel (closing the connections).

**Skip.** Either side may send `skip` for a file that is not finished yet. On sending *or* receiving
`skip` for file `i`:
- **Sender:** writes no further frames for `i` (frames already written stay valid).
- **Receiver:** if `i` is already complete it ignores the skip and keeps the file. Otherwise it
  marks `i` skipped: it still reads (and CRC-checks, if on) frames for `i` that arrive afterwards but
  discards their payload, counts `i` as done for completion, never shows it as received, and when
  the session ends deletes `i`'s partial file and its resume record.
- **Both:** subtract `i`'s not-yet-moved bytes from the expected total, so progress and ETA stay right.
  A skip for an index that is out of range, or already skipped, is ignored.

The receiver's final message lists what was dropped: `{"ok": true, "skipped": [i, ...]}` (omit
or `[]` when nothing was). If every file gets skipped, the receiver sends it as soon as all
in-flight frames have been read. The sender treats skipped files as not delivered (history, "Sent").

**Cancel** is unchanged: closing the connections. A receiver that stops reading CTRL still gets
cancels through DATA connection errors, as before.

## Test vectors

```
id   = "123e4567-e89b-12d3-a456-426614174000"
code = see tools/fastdrop.py `selftest` (all implementations must print the same value)
```

`tools/fastdrop.py selftest` prints the reference code, filesDigest, canonical string, auth,
resumeKey and CRC32 for fixed inputs. The Android and Mac unit tests assert the same values.
