package com.labteto.dshmobile.ui.screens.connect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.labteto.dshmobile.connection.ConnectStage
import com.labteto.dshmobile.connection.ConnectionManager
import com.labteto.dshmobile.connection.ConnectionPhase
import com.labteto.dshmobile.connection.DiscoveredHost
import com.labteto.dshmobile.connection.DiscoveryEngine
import com.labteto.dshmobile.connection.HostConfig
import com.labteto.dshmobile.connection.HostsStore
import com.labteto.dshmobile.connection.ProbeOutcome
import com.labteto.dshmobile.connection.ProbeTimeouts
import com.labteto.dshmobile.core.wire.dto.HostDescription
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import java.net.InetAddress
import javax.inject.Inject

/** Whether a remembered harness is answering right now. */
sealed interface HostProbe {
    /** The probe is in flight. */
    data object Probing : HostProbe

    /** It answered `host.describe`; [description] is what it said. */
    data class Reachable(val description: HostDescription) : HostProbe

    /** No answer — switched off, asleep, or on another network. */
    data object Unreachable : HostProbe
}

/** How far the subnet sweep has got, so the UI can show more than a spinner. */
data class ScanProgress(val probed: Int, val total: Int)

data class ConnectUiState(
    val remembered: List<HostConfig> = emptyList(),
    /** Liveness per remembered host, keyed by `host:port`. Absent = not probed yet. */
    val recentStatus: Map<String, HostProbe> = emptyMap(),
    val discovered: List<DiscoveredHost> = emptyList(),
    val scanning: Boolean = false,
    val scanProgress: ScanProgress? = null,
    /** What the connect attempt is doing right now. */
    val stage: ConnectStage = ConnectStage.Idle,
    /** Why the last attempt failed, or null. */
    val failure: ConnectFailure? = null,
    /** The authority actually attempted, e.g. `192.168.1.20:3080` — never the live field text. */
    val attempted: String? = null,
    /** The loop is still retrying in the background, so a cancel is worth offering. */
    val retrying: Boolean = false,
    val autoConnectLast: Boolean = true,
    val autoConnectLan: Boolean = false,
    val autoConnectLoopback: Boolean = true,
    val showAdvanced: Boolean = false,
) {
    /**
     * Derived, never stored.
     *
     * The old stored boolean was only ever cleared by a callback that could not fire, so a failed
     * connect left the button disabled for the rest of the session. Reading it off the stage means
     * there is no latch to get stuck: `disconnect()` resets the whole state object, and every other
     * path ends in either [ConnectStage.Connected] or a failure the screen displays.
     */
    val connecting: Boolean
        get() = stage != ConnectStage.Idle && stage != ConnectStage.Connected

    /**
     * Discovered harnesses that are not already remembered.
     *
     * A harness in both lists used to render twice, with two different Connect buttons doing the
     * same thing; the Recent card is the one with the history on it, so the sweep yields.
     */
    val unknownDiscovered: List<DiscoveredHost>
        get() {
            val known = remembered.map { it.authority }.toSet()
            return discovered.filterNot { it.authority in known }
        }
}

@HiltViewModel
class ConnectViewModel @Inject constructor(
    private val connectionManager: ConnectionManager,
    private val discoveryEngine: DiscoveryEngine,
    private val hostsStore: HostsStore,
) : ViewModel() {

    private val _state = MutableStateFlow(ConnectUiState())
    val state: StateFlow<ConnectUiState> = _state.asStateFlow()

    /**
     * Non-null while this ViewModel owns the outcome rather than the manager.
     *
     * Validation and the pre-flight probe happen here and can end the attempt before the manager is
     * ever engaged; everything from the handshake on belongs to the manager. Set to null only when
     * handing over, so a locally-decided result is not overwritten by a manager still reporting the
     * previous attempt — and so a stale manager stage cannot pin the screen on "Reaching…".
     */
    private var localStage: ConnectStage? = null

    init {
        viewModelScope.launch {
            connectionManager.state.collect { conn ->
                _state.update { current ->
                    val connected = conn.phase == ConnectionPhase.CONNECTED
                    val owned = localStage != null
                    current.copy(
                        stage = localStage ?: conn.stage,
                        failure = when {
                            connected -> null
                            owned -> current.failure
                            else -> conn.failure
                        },
                        retrying = !owned && !connected && conn.attempts > 0,
                        // The Recent card's liveness dot used to be greyed by the failure callback
                        // that no longer exists; without this a dead entry keeps looking healthy.
                        recentStatus = current.attempted
                            ?.takeIf { !owned && conn.failure != null }
                            ?.let { current.recentStatus + (it to HostProbe.Unreachable) }
                            ?: current.recentStatus,
                    )
                }
            }
        }
    }

    init {
        viewModelScope.launch {
            val settings = hostsStore.settingsOnce()
            _state.update {
                it.copy(
                    autoConnectLast = settings.autoConnectLast,
                    autoConnectLan = settings.autoConnectLan,
                    autoConnectLoopback = settings.autoConnectLoopback,
                )
            }
            hostsStore.hosts.collect { hosts ->
                _state.update { it.copy(remembered = hosts) }
            }
        }
        viewModelScope.launch { autoConnect() }
        viewModelScope.launch { probeRemembered() }
    }

    /**
     * Probe every remembered host once, concurrently.
     *
     * Without this a Recent row can only offer a Connect button that may or may not do anything;
     * one `host.describe` per entry is what turns the list into something you can read before
     * tapping. Results are folded back into storage so the metadata survives the harness going away.
     */
    private suspend fun probeRemembered() {
        val hosts = hostsStore.hosts.first()
        if (hosts.isEmpty()) return
        _state.update { current ->
            current.copy(recentStatus = hosts.associate { it.authority to HostProbe.Probing })
        }
        supervisorScope {
            hosts.map { host ->
                async {
                    val description = runCatching {
                        // A remembered host is named, not swept — worth waiting for.
                        discoveryEngine.probe(host.host, host.port, ProbeTimeouts.Manual)
                    }.getOrNull()
                    _state.update { current ->
                        current.copy(
                            recentStatus = current.recentStatus + (
                                host.authority to (
                                    description?.let { HostProbe.Reachable(it) } ?: HostProbe.Unreachable
                                    )
                                ),
                        )
                    }
                    if (description != null) {
                        hostsStore.cacheDescription(host.host, host.port, description)
                    }
                }
            }.awaitAll()
        }
    }

    /** Re-run the liveness pass, e.g. after the user comes back to the screen. */
    fun refreshRecent() {
        viewModelScope.launch { probeRemembered() }
    }

    private suspend fun autoConnect() {
        val settings = hostsStore.settingsOnce()
        // 1. Last used host.
        if (settings.autoConnectLast) {
            val last = hostsStore.hosts.first().firstOrNull()
            if (last != null) {
                val desc = discoveryEngine.probe(last.host, last.port, ProbeTimeouts.Manual)
                if (desc != null) {
                    connectTo(last)
                    return
                }
            }
        }
        // 2. LAN discovery.
        if (settings.autoConnectLan) {
            val found = discoveryEngine.scan(settings.knownPorts)
            val first = found.firstOrNull()
            if (first != null) {
                val config = hostsStore.rememberHost(
                    name = hostLabel(first.host),
                    host = first.host,
                    port = first.port,
                    isLoopback = false,
                    description = first.description,
                )
                connectTo(config)
                return
            }
        }
        // 3. Same-device loopback.
        if (settings.autoConnectLoopback) {
            val desc = discoveryEngine.probe(LOOPBACK, DEFAULT_PORT)
            if (desc != null) {
                val config = hostsStore.rememberHost(
                    name = hostLabel(LOOPBACK),
                    host = LOOPBACK,
                    port = DEFAULT_PORT,
                    isLoopback = true,
                    description = desc,
                )
                connectTo(config)
            }
        }
    }

    fun scan() {
        if (_state.value.scanning) return
        _state.update { it.copy(scanning = true, scanProgress = null, failure = null) }
        viewModelScope.launch {
            val settings = hostsStore.settingsOnce()
            val found = discoveryEngine.scan(settings.knownPorts) { probed, total ->
                _state.update { it.copy(scanProgress = ScanProgress(probed, total)) }
            }
            _state.update { it.copy(scanning = false, scanProgress = null, discovered = found) }
        }
    }

    fun connectManual(host: String, port: String) {
        val trimmed = host.trim()
        val portInt = port.trim().toIntOrNull()
        if (trimmed.isBlank() || portInt == null || portInt !in 1..65535) {
            fail(ConnectFailure.InvalidInput, attempted = null)
            return
        }
        val authority = "$trimmed:$portInt"
        // The emulator's host alias (10.0.2.2 == host 127.0.0.1) is reachable from the guest
        // even though it is not on the guest's /24, so it must skip the subnet pre-check.
        val isLoopback = trimmed == LOOPBACK || trimmed == "localhost" || trimmed == "10.0.2.2"

        localStage = ConnectStage.Validating
        _state.update { it.copy(stage = ConnectStage.Validating, failure = null, attempted = authority) }

        viewModelScope.launch {
            // Cheap and decisive: the sweep only ever looks at this phone's own /24, so an address
            // outside it can never be reached from here and can never be found by scanning either.
            // Saying so now beats a four-second timeout that blames the firewall.
            if (!isLoopback && !discoveryEngine.isOnLocalSubnet(trimmed)) {
                fail(ConnectFailure.DifferentSubnet(discoveryEngine.localSubnetLabel()), authority)
                return@launch
            }
            localStage = ConnectStage.Reaching
            _state.update { it.copy(stage = ConnectStage.Reaching) }
            val outcome = discoveryEngine.probeOutcome(
                host = trimmed,
                port = portInt,
                timeouts = ProbeTimeouts.Manual,
                preflight = true,
            )
            if (outcome !is ProbeOutcome.Reachable) {
                fail(ConnectFailure.from(outcome), authority)
                return@launch
            }
            hostsStore.addKnownPort(portInt)
            val config = hostsStore.rememberHost(
                name = hostLabel(trimmed),
                host = trimmed,
                port = portInt,
                isLoopback = isLoopback,
                description = outcome.description,
            )
            connectTo(config)
        }
    }

    /** Stop a connect attempt that the loop would otherwise keep retrying every few seconds. */
    fun cancelConnect() {
        // Keep ownership: the manager resets its own state on disconnect, but claiming Idle here
        // means the screen is never briefly re-driven by a trailing emission.
        localStage = ConnectStage.Idle
        connectionManager.disconnect()
        _state.update { it.copy(stage = ConnectStage.Idle, failure = null, retrying = false) }
    }

    /**
     * End the attempt with a reason, and mark the attempted host unreachable if there was one.
     *
     * Holds [localStage] at Idle rather than releasing it: this outcome was decided here, and a
     * manager emission from an earlier attempt must not replace it or revive `connecting`.
     */
    private fun fail(failure: ConnectFailure, attempted: String?) {
        localStage = ConnectStage.Idle
        _state.update {
            it.copy(
                stage = ConnectStage.Idle,
                failure = failure,
                attempted = attempted ?: it.attempted,
                retrying = false,
                recentStatus = attempted?.let { key -> it.recentStatus + (key to HostProbe.Unreachable) }
                    ?: it.recentStatus,
            )
        }
    }

    /**
     * Connect to a remembered host, and say so when it does not work.
     *
     * Progress and failure now arrive on the manager's own state flow, which the collector in
     * `init` folds in — so a tap on a dead Recent entry reports the same diagnosis as a manual
     * attempt instead of looking like an inert button.
     */
    fun connectTo(host: HostConfig) {
        localStage = null
        _state.update {
            it.copy(
                stage = ConnectStage.OpeningStreams,
                failure = null,
                attempted = host.authority,
                retrying = false,
            )
        }
        viewModelScope.launch { connectionManager.connect(host) }
    }

    fun connectDiscovered(discovered: DiscoveredHost) {
        _state.update { it.copy(failure = null, attempted = discovered.authority) }
        viewModelScope.launch {
            hostsStore.addKnownPort(discovered.port)
            val config = hostsStore.rememberHost(
                name = hostLabel(discovered.host),
                host = discovered.host,
                port = discovered.port,
                isLoopback = false,
                description = discovered.description,
            )
            connectTo(config)
        }
    }

    fun forget(host: HostConfig) {
        viewModelScope.launch { hostsStore.removeHost(host.id) }
    }

    fun setAuto(key: String, value: Boolean) {
        viewModelScope.launch {
            hostsStore.setSetting { current ->
                when (key) {
                    "last" -> current.copy(autoConnectLast = value)
                    "lan" -> current.copy(autoConnectLan = value)
                    else -> current.copy(autoConnectLoopback = value)
                }
            }
            val settings = hostsStore.settingsOnce()
            _state.update {
                it.copy(
                    autoConnectLast = settings.autoConnectLast,
                    autoConnectLan = settings.autoConnectLan,
                    autoConnectLoopback = settings.autoConnectLoopback,
                )
            }
        }
    }

    fun clearError() = _state.update { it.copy(failure = null) }

    /**
     * A readable name for an address: reverse DNS when the network offers one, the address itself
     * otherwise. Storing the IP as the name made a card's two lines say the same thing twice.
     */
    private suspend fun hostLabel(address: String): String = withContext(Dispatchers.IO) {
        runCatching {
            val canonical = InetAddress.getByName(address).canonicalHostName
            canonical.takeIf { it.isNotBlank() && it != address }?.substringBefore('.') ?: address
        }.getOrDefault(address)
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val DEFAULT_PORT = 3080
    }
}
