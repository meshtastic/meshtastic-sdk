- `ConfigBundle` gained a sixth constructor parameter, `regionPresets`, and `DeviceCapabilities`
  a second, `excludedModules`. Named and default-argument call sites are unaffected; binaries
  built against the old constructors, `copy` or `componentN` do not link.
