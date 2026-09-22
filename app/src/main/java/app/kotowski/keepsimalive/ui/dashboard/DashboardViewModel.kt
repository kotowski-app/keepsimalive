package app.kotowski.keepsimalive.ui.dashboard

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kotowski.keepsimalive.data.InFlightOccurrence
import app.kotowski.keepsimalive.data.KeepaliveRepository
import app.kotowski.keepsimalive.data.SimKeepaliveConfig
import app.kotowski.keepsimalive.util.CachedSimIdentity
import app.kotowski.keepsimalive.util.Logger
import app.kotowski.keepsimalive.util.PermissionManager
import app.kotowski.keepsimalive.util.PermissionsAutoResetStatus
import app.kotowski.keepsimalive.util.SimDetector
import app.kotowski.keepsimalive.util.SimIdentityCache
import app.kotowski.keepsimalive.util.SimInfoFetcher
import app.kotowski.keepsimalive.util.SimNameResolver
import app.kotowski.keepsimalive.util.appLocaleContext
import app.kotowski.keepsimalive.work.ScheduleReconciler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DashboardUiState(
    val smsGranted: Boolean = false,
    val phoneGranted: Boolean = false,
    val notificationsGranted: Boolean = false,
    val isAndroid13Plus: Boolean = false,
    val batteryOptimizationsGranted: Boolean = false,
    val permissionsAutoResetStatus: PermissionsAutoResetStatus = PermissionsAutoResetStatus.NOT_AVAILABLE,
    val realSimCards: List<SimCardStub> = emptyList(),
    // Configured SIMs that are no longer in the active subscription list, shown from the
    // last known identity (REMOVED status) until they are forgotten.
    val missingSimCards: List<SimCardStub> = emptyList(),
    // True once the live SIM list has been read at least once: the "no SIMs detected" card
    // must not flash while the first read is still in flight.
    val simCardsRead: Boolean = false,
    val multiSimDevice: Boolean = false,
    val inFlightStates: Map<Int, InFlightOccurrence> = emptyMap(),
)

internal fun applyStats(
    cards: List<SimCardStub>,
    configs: Map<Int, SimKeepaliveConfig>,
): List<SimCardStub> =
    cards.map { card ->
        configs[card.simId]?.let { c ->
            card.copy(
                lastSentAtMillis = c.lastSentAtMillis,
                nextSendAtMillis = c.nextSendAtMillis,
                sendCount = c.sendCount,
                keepaliveEnabled = c.enabled,
            )
        } ?: card
    }

@HiltViewModel
class DashboardViewModel
    @Inject
    constructor(
        @ApplicationContext private val appContext: Context,
        private val permissionManager: PermissionManager,
        private val keepaliveRepository: KeepaliveRepository,
        private val scheduleReconciler: ScheduleReconciler,
    ) : ViewModel() {
        private val _state = MutableStateFlow(DashboardUiState())
        val state: StateFlow<DashboardUiState> = _state.asStateFlow()

        // The card-merge inputs as one immutable snapshot behind a single lock:
        // updateSimCards runs on Dispatchers.IO (DashboardScreen) while the observeConfigs
        // collector runs on Main, and both do read-modify-write on the reference. The lock
        // makes each read-modify-write atomic, and every writer applies only its own fields
        // on top of the LATEST snapshot read inside the lock, so no writer can resurrect a
        // stale field. That is not just cosmetic: the stats feed re-emits only on
        // sim_config writes, so a lost stats write would not self-heal (a row can stay
        // unchanged for weeks), leaving a working schedule rendered as "Keep Alive OFF /
        // 0 sends" until the next config edit.
        private data class CardsIdentity(
            val realSimIdentity: List<SimCardStub>,
            val missingSimIdentity: List<SimCardStub>,
            val stats: Map<Int, SimKeepaliveConfig>,
            // Presence can only be judged with READ_PHONE_STATE: without it the active
            // list is unreadable and every configured SIM would wrongly look removed.
            val canVerifySimPresence: Boolean,
        )

        // The single lock behind every cardsIdentity read-modify-write (see above).
        private val lock = Any()

        @Volatile
        private var cardsIdentity = CardsIdentity(emptyList(), emptyList(), emptyMap(), false)

        val permissionManagerForUi: PermissionManager = permissionManager

        init {
            viewModelScope.launch {
                keepaliveRepository.observeConfigs().collect { configs ->
                    // The read-and-write is one lock section: a concurrent poll must not
                    // win the gap and resurrect a pre-stats snapshot over this write.
                    synchronized(lock) {
                        cardsIdentity = cardsIdentity.copy(stats = configs.associate { c -> c.simId to c })
                    }
                    refreshCards()
                }
            }
            viewModelScope.launch {
                keepaliveRepository.observeInFlightStates().collect { states ->
                    _state.update { it.copy(inFlightStates = states) }
                }
            }
        }

        // Cards are identity (device SIM list / remembered missing SIMs / static stubs) with
        // database stats overlaid. Every update funnels through here so a SIM list refresh
        // (launch, resume) never wipes stats that are already on screen. The merge writes one
        // snapshot: the real cards and the stats overlay always come from the same state.
        private fun refreshCards() {
            // Snapshot read and missingSimIdentity write are lock sections: the missing list
            // is computed outside the lock (it does SharedPreferences reads via
            // SimIdentityCache.get) and applied on top of the LATEST snapshot, so a poll
            // that lands in between is never overwritten with a pre-poll SIM list.
            val identity = synchronized(lock) { cardsIdentity }
            val missing = buildMissingSimIdentity(identity)
            val merged = synchronized(lock) { cardsIdentity.copy(missingSimIdentity = missing) }
            _state.update { st ->
                st.copy(
                    realSimCards = applyStats(merged.realSimIdentity, merged.stats),
                    // A missing card exists only while its config row exists: forgetting the
                    // SIM deletes the row and the card disappears with it, without waiting
                    // for the next SIM list poll.
                    missingSimCards = applyStats(missing, merged.stats).filter { it.simId in merged.stats },
                )
            }
        }

        fun updatePermissions(context: Context) {
            // The permission checks go through the single shared path (PermissionManager.isGranted):
            // permission state is app-wide, so the manager's injected app context is the
            // right source (the context argument serves the package name).
            val isAndroid13Plus = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU

            val batteryOptimizationsGranted = permissionManager.isIgnoringBatteryOptimizations(context.packageName)

            _state.update {
                it.copy(
                    smsGranted = permissionManager.isGranted(android.Manifest.permission.SEND_SMS),
                    phoneGranted = permissionManager.isGranted(android.Manifest.permission.READ_PHONE_STATE),
                    notificationsGranted = permissionManager.isGranted(android.Manifest.permission.POST_NOTIFICATIONS),
                    isAndroid13Plus = isAndroid13Plus,
                    batteryOptimizationsGranted = batteryOptimizationsGranted,
                )
            }
        }

        fun updateSimCards(context: Context) {
            // The resolver re-reads the live SIM list on every call: a SIM switched off/on in
            // system settings shows up on the next poll, not up to 60 s later.
            SimIdentityCache.prewarm(context)
            val simInfos = SimNameResolver.getActiveSims(context)
            // Remember the identity of every live SIM so a removed one can still be
            // recognized (and its card shown as "Missing") while it is out of the tray.
            val now = System.currentTimeMillis()
            simInfos.forEach { sim ->
                SimIdentityCache.save(
                    context,
                    CachedSimIdentity(
                        simId = sim.simId,
                        displayName = sim.displayName,
                        carrierName = sim.carrierName,
                        // 1-based, like SimPhoneStateData.slotIndex: the cached identity feeds
                        // the detail-screen header ("SIM N") and the "Last seen (Slot N)" row.
                        slotIndex = sim.slotIndex + 1,
                        networkOperatorName = sim.networkOperatorName,
                        simOperatorName = sim.simOperatorName,
                        isNetworkRoaming = sim.isNetworkRoaming,
                        lastSeenAtMillis = now,
                    ),
                )
            }
            // Log the granted -> lost transition once per process: without it the in-app log
            // is silent about why the SIM cards suddenly disappeared. Unlocked read on
            // purpose: a stale value is acceptable for this log check.
            val previous = cardsIdentity
            val phoneGranted = SimInfoFetcher.hasPhoneStatePermission(context)
            if (previous.canVerifySimPresence && !phoneGranted) {
                Logger.w("DashVM", "Phone permission lost, SIM list unreadable")
            }
            // The write must NOT reuse the early `previous` snapshot: a stats write that
            // landed after the read would be resurrected to its pre-stats value. Read the
            // LATEST snapshot inside the lock and apply only this poll's own fields.
            synchronized(lock) {
                cardsIdentity =
                    cardsIdentity.copy(
                        realSimIdentity = simInfos.map { it.toSimCardStub() },
                        canVerifySimPresence = phoneGranted,
                    )
            }
            val multiSimDevice = SimDetector.isMultiSimDevice(context)
            _state.update { it.copy(multiSimDevice = multiSimDevice, simCardsRead = true) }
            refreshCards()
        }

        // The configured SIMs that are no longer in the active subscription list, built from
        // the last known identity (the passed snapshot: the presence flag, the live SIM
        // list and the configured stats must come from the same state).
        private fun buildMissingSimIdentity(identity: CardsIdentity): List<SimCardStub> {
            if (!identity.canVerifySimPresence) return emptyList()
            val present = identity.realSimIdentity.map { it.simId }.toSet()
            return identity.stats.keys
                .filter { it !in present }
                .mapNotNull { simId -> SimIdentityCache.get(appContext, simId) }
                .map { it.toMissingSimCardStub(appContext.appLocaleContext()) }
                .sortedBy { it.slot }
        }

        fun updatePermissionsAutoResetStatus() {
            viewModelScope.launch {
                val status = permissionManager.getPermissionsAutoResetStatus()
                _state.update { it.copy(permissionsAutoResetStatus = status) }
            }
        }
    }
