- `ConfigBundle.regionPresets`: the device's own legal modem presets and default preset per
  region (`FromRadio.region_presets`, sent in Stage 1, so config-only sessions get it too).
  `null` on firmware that never sends it. Session-only: re-sent on every connect, not stored.
- `DeviceCapabilities.hasModule(ExcludedModules)` and `ConfigBundle.capabilities`: whether the
  device has a module compiled in, from `DeviceMetadata.excluded_modules`. A device that
  reports no bitfield has every module.
