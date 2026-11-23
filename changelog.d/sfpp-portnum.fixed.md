- `StoreForwardPlusPlus` traffic on `STORE_FORWARD_PLUSPLUS_APP` (35), the port firmware sends it on,
  now reaches `StoreForwardApi.events`; only `STORE_FORWARD_APP` was read before. SF++ message
  types newer than the pinned protobufs no longer read as `CANON_ANNOUNCE`.
