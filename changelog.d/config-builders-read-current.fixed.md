- The `AdminApi.set*Config` builders no longer reset the rest of a section. Each one reads the
  section from the device it targets, applies the block to that value and writes it back, so
  `setLoraConfig { newBuilder().hop_limit(5).build() }` keeps the region and `tx_enabled`. A
  read that fails is returned and nothing is written; a read without the section returns
  `AdminResult.Failed(Routing.Error.NO_RESPONSE)`.
