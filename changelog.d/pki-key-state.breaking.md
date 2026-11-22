- `MeshEvent.SecurityWarning.PublicKeyMismatch` is a new case, so an exhaustive `when` over
  `SecurityWarning` needs a branch.
- `KeyVerificationPrompt` is a sealed interface with three concrete prompts
  (`EnterSecurityNumber`, `ShowSecurityNumber`, `ConfirmVerification`) instead of an empty
  marker interface. An external implementer no longer compiles; a collector that only
  `is`-checks it is unaffected.
- `sendDirectMessageEncrypted` is `suspend`, because it reads the recipient's key from the
  node DB. Callers outside a coroutine need a scope.
