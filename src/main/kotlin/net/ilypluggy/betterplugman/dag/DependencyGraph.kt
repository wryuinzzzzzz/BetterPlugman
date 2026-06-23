package net.ilypluggy.betterplugman.dag

class DependencyGraph(descriptors: Collection<PluginDescriptor>) {

    private val byKey: Map<String, PluginDescriptor> = descriptors.associateBy { it.key }

    /** adjacency: key -> set of keys that depend on it (i.e. must come after) */
    private val edges: MutableMap<String, MutableSet<String>> = mutableMapOf()

    /** hard + resolvable soft + loadbefore */
    private val inDegree: MutableMap<String, Int> = mutableMapOf()

    init {
        byKey.keys.forEach { key ->
            edges.putIfAbsent(key, mutableSetOf())
            inDegree.putIfAbsent(key, 0)
        }

        for (node in byKey.values) {
            for (depName in node.depend) {
                val depKey = depName.lowercase()
                if (depKey !in byKey) continue // reported separately by validate()
                addEdge(from = depKey, to = node.key)
            }
            for (depName in node.softDepend) {
                val depKey = depName.lowercase()
                if (depKey !in byKey) continue // soft, just skip silently
                addEdge(from = depKey, to = node.key)
            }
            for (beforeName in node.loadBefore) {
                val beforeKey = beforeName.lowercase()
                if (beforeKey !in byKey) continue
                addEdge(from = node.key, to = beforeKey)
            }
        }
    }

    private fun addEdge(from: String, to: String) {
        if (from == to) return
        val added = edges.getOrPut(from) { mutableSetOf() }.add(to)
        if (added) {
            inDegree[to] = (inDegree[to] ?: 0) + 1
        }
    }

    fun findMissingHardDependency(): Pair<String, String>? {
        for (node in byKey.values) {
            for (depName in node.depend) {
                if (depName.lowercase() !in byKey) {
                    return node.name to depName
                }
            }
        }
        return null
    }

    fun resolve(): DagResult {
        findMissingHardDependency()?.let { (plugin, missing) ->
            return DagResult.MissingDependency(plugin, missing)
        }

        val remainingInDegree = inDegree.toMutableMap()
        val ready = sortedSetOf<String>(compareBy { it })
        ready.addAll(remainingInDegree.filterValues { it == 0 }.keys)

        val order = mutableListOf<PluginDescriptor>()
        val visited = mutableSetOf<String>()

        while (ready.isNotEmpty()) {
            val key = ready.first()
            ready.remove(key)
            visited += key
            byKey[key]?.let { order += it }

            for (next in edges[key].orEmpty()) {
                val newDegree = (remainingInDegree[next] ?: 1) - 1
                remainingInDegree[next] = newDegree
                if (newDegree == 0 && next !in visited) {
                    ready += next
                }
            }
        }

        if (order.size != byKey.size) {
            val cycleNodes = byKey.keys - visited
            return DagResult.CycleDetected(cycleNodes.map { byKey[it]?.name ?: it }.sorted())
        }

        return DagResult.Success(order)
    }

    fun resolveFor(target: PluginDescriptor): DagResult {
        val needed = mutableSetOf<String>()
        val stack = mutableListOf(target.key)
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            if (!needed.add(current)) continue
            val desc = byKey[current] ?: continue
            (desc.depend + desc.softDepend).forEach { dep ->
                val depKey = dep.lowercase()
                if (depKey in byKey) stack += depKey
            }
        }

        val subGraph = DependencyGraph(needed.mapNotNull { byKey[it] })
        return subGraph.resolve()
    }

    fun descriptorOf(name: String): PluginDescriptor? = byKey[name.lowercase()]

    fun all(): Collection<PluginDescriptor> = byKey.values
}