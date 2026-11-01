package app.projectsteps.core.deps

/**
 * Simple task dependencies. An edge (task, dependsOn) means "task depends on dependsOn".
 * Adding an edge that would create a cycle is refused.
 */
object DependencyGraph {

    data class Edge(val taskId: String, val dependsOnId: String)

    /** True if adding (taskId depends on dependsOnId) would create a cycle (including self-dependency). */
    fun wouldCreateCycle(edges: Collection<Edge>, taskId: String, dependsOnId: String): Boolean {
        if (taskId == dependsOnId) return true
        val adjacency = edges.groupBy({ it.taskId }, { it.dependsOnId })
        // A cycle appears if dependsOnId already (transitively) depends on taskId.
        val seen = HashSet<String>()
        val queue = ArrayDeque<String>()
        queue.add(dependsOnId)
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            if (cur == taskId) return true
            if (!seen.add(cur)) continue
            adjacency[cur]?.let { queue.addAll(it) }
        }
        return false
    }

    /** Tasks that have at least one unfinished prerequisite. */
    fun blockedTaskIds(edges: Collection<Edge>, doneIds: Set<String>, existingIds: Set<String>? = null): Set<String> =
        edges.filter { e ->
            e.dependsOnId !in doneIds && (existingIds == null || e.dependsOnId in existingIds)
        }.map { it.taskId }.toSet()
}
