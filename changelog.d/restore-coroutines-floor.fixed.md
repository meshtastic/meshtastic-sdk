- kotlinx-coroutines-core and -android are compiled against 1.8.1 again, after a dependency update
  raised them to 1.11.0, so hosts whose runtime pins 1.8.x (ATAK plugins) can load `:core`,
  `:storage-sqldelight` and `:transport-serial`. Renovate now holds them to 1.8 patches.
  `:transport-tcp` and `:transport-websocket` resolve 1.11.0 through Ktor 3.6.1, and
  `:transport-ble` through Kable 0.45.0, so the floor does not cover those three. Tests use
  kotlinx-coroutines-test 1.11.0, which `:testing` passes on to its consumers.
