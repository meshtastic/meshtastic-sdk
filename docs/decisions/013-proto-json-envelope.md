# ADR-013: Proto-JSON envelope for the CLI's `--format=json` mode

| Field | Value |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-04-19 |
| **Deciders** | Maintainers |
| **Supersedes** | — |
| **Related** | [ADR-001](001-public-api-uses-generated-protobufs.md), [ADR-009](009-cli-architecture.md) |

## Context

The CLI's `--format=json` mode emits one JSON object per record, each
representing either a `FromRadio` envelope, a `ToRadio` echo, an engine
event (`TransportState` change, handshake progress, error), or a CLI
control message (command start/end, exit code). Agents and eval harnesses
consume this stream to assert outcomes deterministically.

We needed an encoding scheme that:
- Preserves protobuf field semantics exactly (oneofs, unknown fields,
  bytes-vs-string).
- Is stable across schema evolution — adding a field upstream must not
  break a consumer pinned to last week's CLI output.
- Doesn't require us to hand-write JSON adapters for every protobuf
  message.

## Decision

The CLI uses **protobuf-to-JSON canonical mapping**
(<https://protobuf.dev/programming-guides/json/>) for every protobuf
message it emits. Specifically:

- `bytes` fields → base64 strings.
- `oneof` fields → the active arm's name as the JSON key.
- Unknown fields → preserved as a `"$unknown": [...]` array (we hand-roll
  this; canonical proto-JSON drops them).

Records are wrapped in a small CLI-owned envelope:

```json
{ "type": "node" | "packet" | "event" | "state" | "scan-hit" | "info" | "probe-run" | "probe-summary" | "error" | "done",
  "ts": 1713537662345,
  "data": { … proto-JSON or CLI object … } }
```

`ts` is wall-clock time in milliseconds since epoch, `type`
disambiguates the `data` schema, and `data` is whatever proto-JSON
or CLI-defined object fits. Records are newline-delimited
(`application/x-ndjson`) so consumers can stream them through `jq`.

## Rationale

- Canonical proto-JSON is a published, stable spec — agents can use
  off-the-shelf protobuf knowledge to interpret payloads.
- The thin envelope adds the timing and ordering metadata the wire
  format itself doesn't carry, without inventing yet another schema for
  payloads.
- NDJSON is trivially streamable; an agent or eval can `tail -f` the
  output and process records as they arrive.
- Preserving unknown fields lets us regression-test against firmware
  that's newer than the SDK without losing data.

## Alternatives considered

| Option | Why not |
|---|---|
| Hand-rolled JSON per message type | Doubles the maintenance surface; drifts the moment the proto evolves. |
| Plain text logs | Unparseable; no agent automation possible. |
| YAML output | Not streamable; harder to consume from `jq`/Python. |
| Wrap every record as a fully-typed Kotlin data class | Re-invents the protobuf schema in Kotlin for the sole benefit of CLI output. |

## Consequences

### Positive
- Agents and CI eval scripts get a deterministic, schema-validated
  output stream.
- New protobuf fields show up automatically in JSON output the next time
  the submodule updates.
- `jq` queries against CLI output become tests:
  `cli probe ... | jq -e '.payload.config_complete_id'`.

### Negative / costs
- The "$unknown" extension is a CLI-local convention — consumers that
  validate strictly against canonical proto-JSON need to either accept
  it or post-process it out.
- Base64 of large `bytes` fields is verbose. Acceptable: CLI output is
  for diagnostics, not bulk data transfer.

### Schema stability

The envelope schema should remain stable; **any future schema changes must be versioned** (e.g., adding a new top-level field or renaming `type` to `kind`) so that existing consumers can distinguish envelope versions by examining the presence/absence of fields or via an explicit `envelope_version` field.

### Follow-ups
- [ ] Document the envelope schema in `docs/manual-tests.md` alongside
      the CLI command reference.
- [ ] Add a golden-record test under `:testing` that asserts the
      envelope shape doesn't drift.

## References

- Canonical proto-JSON mapping:
  <https://protobuf.dev/programming-guides/json/>
- [ADR-001](001-public-api-uses-generated-protobufs.md) — why our public
  API speaks protobuf in the first place.
- [`samples/cli/src/main/kotlin/org/meshtastic/cli/internal/`](../../samples/cli/src/main/kotlin/org/meshtastic/cli/internal/)
