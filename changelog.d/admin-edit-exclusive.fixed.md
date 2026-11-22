- Concurrent `AdminApi.editSettings` and `batch` calls to the same node run one at a time, in call
  order, across every `AdminApi` of the client, instead of interleaving their begin, writes and
  commit into the node's single edit transaction. A call to another node does not wait. Nesting
  either call inside its own block throws `IllegalStateException` for the same node, and for
  another node whose edit another caller has open.
