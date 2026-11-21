- Over BLE, an admin command to the connected node whose firmware handler takes the link down
  before answering (`commit_edit_settings`, `set_config`, `set_module_config`, the resets,
  `restore_preferences`, DFU) reports `AdminResult.Success` once the transport wrote it and the link
  dropped, instead of `NodeUnreachable`. A command still queued when the link drops does not.
- A remote admin read whose `MAX_RETRANSMIT` comes from the connected radio keeps waiting for the
  reply and reports `Failed(MAX_RETRANSMIT)` only at timeout, so a reply that arrives over another
  path is no longer lost.
