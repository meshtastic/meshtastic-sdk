- `DroppedFlow` gains `Nodes`. A node change dropped by a stalled `nodes` subscriber is reported as
  `MeshEvent.PacketsDropped(DroppedFlow.Nodes, …)` instead of `DroppedFlow.Events`. An exhaustive
  `when` over `DroppedFlow` needs the new case.
