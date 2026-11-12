# FastDrop command-line client

`fastdrop.py` lets a Windows, Linux or macOS PC send and receive files, folders and text with
FastDrop phones, TVs and Macs over the local network, with no cloud and no install beyond Python.
It speaks protocol 1 and 2 (see `../docs/PROTOCOL.md`). It is a single file using only the
Python 3.8+ standard library.

## Install

- **Windows:** install Python from python.org or `winget install Python.Python.3.12` (tick "Add to PATH"). Use `py` or `python` below.
- **Linux:** usually preinstalled (`python3`). Clipboard sending needs `xclip`, `xsel` or `wl-clipboard`.
- **macOS:** `python3` ships with the Xcode command line tools, or `brew install python`.

Then just run `python3 fastdrop.py ...` (Windows: `py fastdrop.py ...`). Nothing else to install.

## Use

```
python3 fastdrop.py scan                                  # list FastDrop devices (about 4 s)
python3 fastdrop.py send "Pixel 8" photo.jpg Movies/      # by device name or by IP
python3 fastdrop.py send 192.168.1.42 big.iso --streams 8 # more parallel streams for big files
python3 fastdrop.py send 192.168.1.42 notes.pdf --crc     # verify every chunk with CRC32
python3 fastdrop.py send "Pixel 8" --text "https://example.com"
python3 fastdrop.py send "Pixel 8" --clipboard            # send the clipboard text
python3 fastdrop.py recv --dir ~/Downloads/FastDrop       # appear on phones and receive
python3 fastdrop.py recv --accept ask                     # prompt for every offer
python3 fastdrop.py hello 192.168.1.42                    # show what a device reports
python3 fastdrop.py trust list                            # devices allowed to send without asking
python3 fastdrop.py trust remove <id>
```

`recv` accept modes: `trusted` (default: ask the first time, then trusted devices are accepted
automatically), `ask` (always ask) and `everyone`. The prompt shows a 4-digit confirmation code
that matches the one on the sender's screen; answer `y` (accept), `n`, or `a` (accept and always
trust this device). `--crc` lets the receiver agree when a sender asks for CRC32 checking.

Interrupted transfers resume: run the same `send` again and only the missing chunks go over the
wire. Unfinished files are kept as hidden `.<name>.fdpart` files in the receive folder and are
cleaned up after 7 days.

Folders are sent with their structure. On receive, names are sanitized (no `..`, drive letters or
absolute paths, no characters Windows forbids) and nothing is written outside `--dir`.

Settings (device id, name, trusted devices, resume data) live in `~/.fastdrop`
(Windows: `%APPDATA%\FastDrop`); override with `--state-dir DIR`. `--name NAME` sets the display name.
`--port P` changes the TCP port (default 53530).

## Firewall

Receiving needs inbound access:

- **Windows:** allow Python through Windows Defender Firewall when prompted (Private networks), or add rules:
  `netsh advfirewall firewall add rule name="FastDrop TCP" dir=in action=allow protocol=TCP localport=53530`
  and `netsh advfirewall firewall add rule name="FastDrop UDP" dir=in action=allow protocol=UDP localport=53531`.
- **Linux:** `sudo ufw allow 53530/tcp && sudo ufw allow 53531/udp` (or the firewalld equivalent).
- **macOS:** click Allow when asked about incoming connections.

Both devices must be on the same Wi-Fi/LAN (not a guest network with client isolation). If `scan`
finds nothing, send by IP address, which does not depend on discovery.

## Tests

```
python3 fastdrop.py selftest     # prints and checks the protocol-2 reference vectors
python3 interop_test.py          # end-to-end scenarios on 127.0.0.1 (cleans up after itself)
python3 fastdrop.py make test.bin 100 && python3 fastdrop.py sha test.bin
```

`fdtest.py` is the older protocol-1-only tool; it is kept as a regression client.
