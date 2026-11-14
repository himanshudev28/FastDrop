# FastDrop 2.1.1

Fixes on Android. The Mac app is unchanged apart from its version number, which now matches.

## Choosing what to send
- **Send to** now lists everything you picked. Each file has a remove (✕) button. A folder, or an app with all its APK files, shows as one line. Long lists fold to 4 lines behind **Show all N**, and **Clear all** drops the whole selection.
- An old selection no longer comes back. Opening the picker from Home, or from **Send more**, starts empty. Leaving **Send to** with Back clears what you picked. **Edit** still opens the picker with your current files so you can add to them.

## Transfers
- **Retry** works again after you hide the transfer sheet, or after the receiver drops off the nearby list. It resends what was actually sent, and it continues from where the send stopped.
- The speed readout is an estimate now. It averages over about 5 seconds and changes at most every 2 seconds, instead of jumping several times a second.

## Fixes
- Home: the device name and IP address on the status line stay readable when the line is selected with a D-pad, a keyboard or a TV remote. Before, they disappeared (black on black, or white on white in dark mode).
