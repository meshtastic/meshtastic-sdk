- Android AARs now ship each module's `consumer-rules.pro` as `proguard.txt`, so an app that
  shrinks with R8 keeps the Kable fields `BleResilience(refreshStaleGattCache = true)` reads by
  reflection instead of silently skipping the refresh.
