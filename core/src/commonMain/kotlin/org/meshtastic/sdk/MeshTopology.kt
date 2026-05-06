/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Incremental mesh topology graph built from [NeighborInfo] reports.
 *
 * Usage:
 * ```kotlin
 * val topology = MeshTopology()
 * topology.addNeighborInfo(neighborInfo)
 * val path = topology.shortestPath(nodeA, nodeB)
 * val neighbors = topology.getNeighbors(nodeA)
 * ```
 *
 * **Thread-safe** — all mutations and reads are guarded by an internal [Mutex]. Safe to call
 * concurrently from the engine actor and UI collectors.
 * The graph is directed — if node A reports node B as a neighbor, that's a directed edge A→B.
 * Undirected queries consider both directions.
 */
public class MeshTopology {
    /**
     * Directed edge from a reporting node [from] to a neighbor [to], carrying the reported signal
     * quality ([snr]) and the [NeighborInfo.lastUpdated] value from the source report.
     */
    public data class Edge(
        val from: NodeId,
        val to: NodeId,
        val snr: Float,
        val lastUpdated: Int = 0,
    )

    private val mutex = Mutex()

    // Internal adjacency: Map<reporter, Map<neighbor, Edge>>
    private val adjacency = mutableMapOf<NodeId, MutableMap<NodeId, Edge>>()
    private var cachedNodes: Set<NodeId>? = null

    /**
     * Ingest a [NeighborInfo] report, replacing all edges from the reporting node.
     */
    public suspend fun addNeighborInfo(info: NeighborInfo): Unit = mutex.withLock {
        val edges = mutableMapOf<NodeId, Edge>()
        info.neighbors.forEach { neighbor ->
            edges[neighbor.nodeId] = Edge(
                from = info.nodeId,
                to = neighbor.nodeId,
                snr = neighbor.snr,
                lastUpdated = info.lastUpdated,
            )
        }
        adjacency[info.nodeId] = edges
        cachedNodes = null
    }

    /**
     * Remove a node and all edges referencing it.
     */
    public suspend fun removeNode(nodeId: NodeId): Unit = mutex.withLock {
        adjacency.remove(nodeId)
        adjacency.values.forEach { it.remove(nodeId) }
        cachedNodes = null
    }

    /** All nodes that have reported neighbors or been reported as a neighbor. */
    public suspend fun nodes(): Set<NodeId> = mutex.withLock {
        cachedNodes?.let { return@withLock it }
        val result = mutableSetOf<NodeId>()
        adjacency.forEach { (reporter, neighbors) ->
            result.add(reporter)
            result.addAll(neighbors.keys)
        }
        result.also { cachedNodes = it }
    }

    /** Get all outgoing edges from a node (nodes it reported as neighbors). */
    public suspend fun getNeighbors(nodeId: NodeId): List<Edge> = mutex.withLock {
        adjacency[nodeId]?.values?.toList() ?: emptyList()
    }

    /** Check if there's a direct edge in either direction between two nodes. */
    public suspend fun isDirectReach(a: NodeId, b: NodeId): Boolean = mutex.withLock {
        adjacency[a]?.containsKey(b) == true || adjacency[b]?.containsKey(a) == true
    }

    /** Get the edge from [from] to [to] (if [from] reported [to] as neighbor). */
    public suspend fun getEdge(from: NodeId, to: NodeId): Edge? = mutex.withLock {
        adjacency[from]?.get(to)
    }

    /**
     * Find shortest path between two nodes using BFS on the undirected graph.
     * Returns the path as a list of [NodeId]s including start and end.
     * Returns `listOf(from)` when [from] == [to].
     * Returns an empty list when no path exists.
     */
    public suspend fun shortestPath(from: NodeId, to: NodeId): List<NodeId> = mutex.withLock {
        if (from == to) return@withLock listOf(from)
        val visited = mutableSetOf(from)
        val queue = ArrayDeque<List<NodeId>>()
        queue.add(listOf(from))

        while (queue.isNotEmpty()) {
            val path = queue.removeFirst()
            val current = path.last()
            for (neighbor in undirectedNeighborsLocked(current)) {
                if (neighbor == to) return@withLock path + neighbor
                if (visited.add(neighbor)) {
                    queue.add(path + neighbor)
                }
            }
        }
        emptyList()
    }

    /**
     * Get all edges in the topology graph.
     */
    public suspend fun allEdges(): List<Edge> = mutex.withLock {
        adjacency.values.flatMap { it.values }
    }

    /**
     * Number of directed edges.
     */
    public suspend fun edgeCount(): Int = mutex.withLock {
        adjacency.values.sumOf { it.size }
    }

    /** Clear all topology data. */
    public suspend fun clear(): Unit = mutex.withLock {
        adjacency.clear()
        cachedNodes = null
    }

    /** Must be called while holding [mutex]. */
    private fun undirectedNeighborsLocked(nodeId: NodeId): Set<NodeId> {
        val result = mutableSetOf<NodeId>()
        adjacency[nodeId]?.keys?.let { result.addAll(it) }
        adjacency.forEach { (reporter, neighbors) ->
            if (neighbors.containsKey(nodeId)) result.add(reporter)
        }
        return result
    }
}
