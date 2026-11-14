# FastDrop 2.3

## Pause, resume and skip a file
- You can now **pause** a running transfer and **resume** it later. Either device can pause, and either can resume. Both show "Paused", and the paused time never counts as a stalled connection.
- You can **skip a single file** while the rest keep going. Tap ✕ next to a file that hasn't finished yet. The receiver drops that file and deletes its partial copy, and the transfer finishes with the other files ("1 skipped").
- This works on Android, on Mac (the transfer card) and in the command-line tool. The CLI has a `--ctl-test` option for scripted pause, resume and skip.
- Both devices need FastDrop 2.3 or newer. With an older device, transfers work exactly as before and Pause and ✕ are hidden.
- None of this adds work per byte, so transfer speed is unchanged. See docs/PROTOCOL.md, section 7.
