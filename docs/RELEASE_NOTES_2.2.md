# FastDrop 2.2

None of these changes slow transfers down or put extra load on the devices.

## Android
- **Queue sends.** If you start a send while another transfer is running, it waits its turn instead of being refused. It starts by itself when the current transfer ends, including after a send to several devices. The transfer sheet shows "1 queued", with **Clear queue** to drop what's waiting.
- **Per-file progress.** The transfer sheet shows "File 3 of 12", and a tick appears next to each file once it's finished. This works both when sending and when receiving.
- **Undo** after removing a file on **Send to**, or after **Clear all**.
- **Retry** resends exactly what that transfer sent, to the same device, even if another send has started since.
- Fixed a crash on Android 5–7 when a file-type icon was drawn.
- Incoming file rows no longer show a size borrowed from an unrelated earlier send.
- The remaining hardcoded text now lives in the string resources, ready for translation. Nothing looks different.

## Mac
- The tray of selected files folds to 4 items behind **Show all N / Show less**.
- The remove (✕) button on each file is always visible, not only on hover.
- Sending to several devices shows "Device 2 of 3".
