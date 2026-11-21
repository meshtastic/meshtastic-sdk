- Device display mirroring: `AdminApi.setDisplayMirror` and `AdminApi.requestDisplayFrame`
  (local device only), `RadioClient.displayFrames` with reassembled `DisplayMirrorFrame`s, and
  `RadioClient.displayPalette`. Frames with a gap or over 1 MiB are dropped, and a slow collector
  loses the oldest frame.
