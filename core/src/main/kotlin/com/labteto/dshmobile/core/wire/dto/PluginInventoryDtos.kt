package com.labteto.dshmobile.core.wire.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Plugin-inventory DTOs, ported from `packages/host/plugin-inventory/src/types.ts`
 * (v0.1.0-rc.5).
 *
 * The inventory is a read-only, point-in-time view of the host's configured plugin entries,
 * served over the typert remote `pluginInventory/list`. It is not privileged: a plain LAN
 * connection may read it even when the settings plane is refused.
 */

/** Lifecycle state of an entry's root Fiber, or null when it has no live root Fiber. */
enum class PluginFiberPhase {
    Pending,
    Loading,
    Active,
    Failed,
    Unloading,
    /** The harness reported a phase this build does not know; the entry is still shown. */
    Unknown,
}

/**
 * One non-group Loader entry exposed to trusted clients. [fiberPhaseRaw] is the wire string kept
 * verbatim so a future harness phase never drops the row; [fiberPhase] maps it to a known state
 * (or [PluginFiberPhase.Unknown]). A null raw phase means the entry has no live root Fiber.
 */
@Serializable
data class PluginInventoryEntry(
    @SerialName("entryId") val entryId: String,
    @SerialName("moduleName") val moduleName: String,
    @SerialName("enabled") val enabled: Boolean,
    @SerialName("fiberPhase") val fiberPhaseRaw: String? = null,
) {
    val fiberPhase: PluginFiberPhase? get() = when (fiberPhaseRaw) {
        null -> null
        "pending" -> PluginFiberPhase.Pending
        "loading" -> PluginFiberPhase.Loading
        "active" -> PluginFiberPhase.Active
        "failed" -> PluginFiberPhase.Failed
        "unloading" -> PluginFiberPhase.Unloading
        else -> PluginFiberPhase.Unknown
    }
}

/** Point-in-time inventory returned by the plugin inventory Remote. */
@Serializable
data class PluginInventorySnapshot(
    @SerialName("entries") val entries: List<PluginInventoryEntry> = emptyList(),
)
