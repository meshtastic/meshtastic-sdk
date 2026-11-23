- A packet with `hop_start` 0 from a sender that stamps `Data.bitfield` (firmware 2.5 and later)
  sets the node's `hops_away` to 0, as firmware `getHopsAway` does. Before, the node kept its
  previous hop count.
