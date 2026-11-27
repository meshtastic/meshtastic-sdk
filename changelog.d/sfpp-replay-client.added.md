- SF++ replays: `ITEM_FRAME`s from an archive are reassembled, verified against their message
  hash, decrypted with the primary channel's key (AES-CTR, or AES-CCM on `use_aead` channels) and
  emitted as `StoreForwardEvent.SfppMessageRecovered`, without touching node bookkeeping.
