- Concurrent `AdminApi.editSettings` and `batch` calls to the same node run one at a time, in call
  order, across every `AdminApi` of the client, instead of interleaving their begin, writes and
  commit into the node's single edit transaction. A call to another node does not wait. Nesting
  either call for the same node inside its own block throws `IllegalStateException`.
