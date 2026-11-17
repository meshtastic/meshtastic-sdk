- Waypoint rules and senders: `Waypoint.isExpired(now)`, `Waypoint.canBeEditedBy(node)`,
  `newWaypointId()`, `RadioClient.sendWaypoint` and `RadioClient.deleteWaypoint` (a re-send with
  `expire = 1`, as firmware and the Android app delete). The SDK keeps no waypoint store.
