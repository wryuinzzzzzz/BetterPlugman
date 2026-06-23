package net.ilypluggy.betterplugman.dag

sealed class DagResult {
    data class Success(val order: List<PluginDescriptor>) : DagResult()
    /** A hard depend could not be satisfied by anything in the scanned set */
    data class MissingDependency(val plugin: String, val missing: String) : DagResult()

    /** A->B->...->A. We refuse to load anything in a cycle */
    data class CycleDetected(val cycle: List<String>) : DagResult()
}