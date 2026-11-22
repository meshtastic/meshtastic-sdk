- The first region set on firmware 2.8.0, which renumbers the node without resending `my_info`,
  now ends with the session on the new number. A region write answered only by its queue status
  completes as `Success` after five seconds and restarts the handshake, and a write refused for
  the old number with `PKI_SEND_FAIL_PUBLIC_KEY`, such as an `editSettings` commit, is resent to
  the number its NAK names.
- A paced admin write whose queue status carries a `Routing.Error` above 35, such as
  `PKI_SEND_FAIL_PUBLIC_KEY`, returns `AdminResult.Failed` with that error instead of
  `AdminResult.RateLimited`, and an RPC fails with it instead of `NodeUnreachable`.
