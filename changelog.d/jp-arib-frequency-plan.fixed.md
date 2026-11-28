- Align `RegionCode.JP` slot widths, slot counts, and center frequencies with firmware's ARIB
  STD-T108 200 kHz unit-channel raster and 922.3 MHz sub-band split (`LoRaRegionInfo.unitChannelMHz`
  and `LoRaRegionInfo.bandSplitMHz`, `LoRaFrequency`), falling back to the legacy unbonded JP grid
  when `firmwareVersion` is at or below the pre-ARIB threshold (`2.8.2.d5f1619` placeholder;
  `DeviceCapabilities.supportsJpAribSlotPlan`).
