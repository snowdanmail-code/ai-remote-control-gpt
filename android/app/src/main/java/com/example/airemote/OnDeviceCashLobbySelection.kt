package com.example.airemote

import android.content.Context
import android.os.SystemClock

/**
 * One guarded 1win lobby-row navigation per operator-armed capture session.
 *
 * It selects an already visible NL Hold'em cash-game table after BOTH:
 *   (a) two agreeing screen OCR observations; and
 *   (b) explicit persisted upper limits on blinds and buy-in.
 *
 * It only presses the labelled table-entry control. It NEVER confirms a
 * buy-in, transfers a balance, chooses the actual stake or places a bet.
 * Every application-level action goes through the Accessibility service's
 * independent root, geometry and STOP checks.
 */
data class OneWinCashLobbyEntryTicket(
    val key: String,
    val sourcePackage: String,
    val captureEpoch: Long,
    val windowGeneration: Long,
    val capturedAtMs: Long,
    val anchor: NormalizedTextBox,
    val tableName: String,
    val permittedBuyInCapCents: Long
)

enum class CashLobbySelectionPhase {
    OFF, WAITING_LOBBY, RECOMMENDED, ENTRY_SUBMITTED, REJECTED, EXPIRED, STOPPED,
    NAVIGATION_PENDING, WAITING_SORT_MENU, WAITING_SORTED_LOBBY,
    WAITING_SCROLLED_LOBBY, SCAN_EXHAUSTED
}

enum class OneWinCashLobbyNavAction { OPEN_SORT, CHOOSE_STAKES_ASC, SWIPE_UP, SWIPE_DOWN }

data class OneWinCashLobbyNavTicket(
    val action: OneWinCashLobbyNavAction,
    val sourcePackage: String,
    val captureEpoch: Long,
    val windowGeneration: Long,
    val observedAtMs: Long,
    val sequence: Long,
    val optionBox: NormalizedTextBox? = null
)
data class CashLobbySelectionStatus(
    val phase: CashLobbySelectionPhase,
    val message: String,
    val recommended: OneWinLobbyTableChoice? = null
)

object OneWinCashLobbyLimitsStore {
    private const val PREFS = "t043_onewin_cash_lobby_limits_v1"
    private const val MAX_BLIND = "max_blind_cents"
    private const val MAX_BUYIN = "max_buyin_cents"

    /** Never infer spending limits from balance or the current screenshot. */
    fun read(context: Context): OneWinCashTableLimits? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(MAX_BLIND) || !prefs.contains(MAX_BUYIN)) return null
        val result = OneWinCashTableLimits(prefs.getLong(MAX_BLIND, 0L),
            prefs.getLong(MAX_BUYIN, 0L))
        return result.takeIf { it.valid() }
    }

    fun save(context: Context, maxBigBlindCents: Long,
             maxBuyInCents: Long): Boolean {
        val limits = OneWinCashTableLimits(maxBigBlindCents, maxBuyInCents)
        if (!limits.valid()) return false
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(MAX_BLIND, maxBigBlindCents)
            .putLong(MAX_BUYIN, maxBuyInCents).commit()
    }

    /** Strict decimal dollar input: "4", "4.00", "0.04". */
    fun parseDollars(raw: String): Long? {
        val txt = raw.trim().replace(',', '.')
        if (!Regex("""[0-9]{1,6}(?:\.[0-9]{1,2})?""").matches(txt)) return null
        val items = txt.split('.')
        val major = items[0].toLongOrNull() ?: return null
        val cents = when (items.getOrNull(1)?.length) {
            null -> 0L
            1 -> (items[1].toLongOrNull() ?: return null) * 10L
            2 -> items[1].toLongOrNull() ?: return null
            else -> return null
        }
        return major * 100L + cents
    }
}

object OnDeviceCashLobbySelection {
    private var armed = false
    private var dispatched = false
    private var pending: OneWinCashLobbyEntryTicket? = null
    private var pendingNavigation: OneWinCashLobbyNavTicket? = null
    private var navSequence = 0L
    private var sorted = false
    private var sortAttempted = false
    private var lastNavigationAtMs = 0L
    private var pageBeforeSwipe = ""
    private val pageTracker = OneWinCashLobbyBrowseTracker(18)
    @Volatile private var current = CashLobbySelectionStatus(
        CashLobbySelectionPhase.OFF, "Автоматический выбор стола выключен")

    @Synchronized fun status() = current
    @Synchronized fun scanning(): Boolean = armed && !dispatched &&
        current.phase !in setOf(CashLobbySelectionPhase.OFF,
            CashLobbySelectionPhase.ENTRY_SUBMITTED, CashLobbySelectionPhase.STOPPED,
            CashLobbySelectionPhase.REJECTED, CashLobbySelectionPhase.EXPIRED,
            CashLobbySelectionPhase.SCAN_EXHAUSTED)

    @Synchronized fun onCaptureTick(nowMs: Long) {
        if (!scanning()) return
        val phase = current.phase
        if (phase in setOf(CashLobbySelectionPhase.WAITING_SORT_MENU,
                CashLobbySelectionPhase.WAITING_SORTED_LOBBY,
                CashLobbySelectionPhase.WAITING_SCROLLED_LOBBY) &&
            lastNavigationAtMs > 0L && nowMs - lastNavigationAtMs > 7_000L) {
            armed = false
            pendingNavigation = null
            current = CashLobbySelectionStatus(CashLobbySelectionPhase.EXPIRED,
                "После жеста не подтверждено ожидаемое меню/прокрутка за 7 секунд")
        }
    }

    @Synchronized fun reset(reason: String = "STOP") {
        armed = false
        dispatched = false
        pending = null
        pendingNavigation = null
        navSequence++
        sorted = false
        sortAttempted = false
        pageBeforeSwipe = ""
        lastNavigationAtMs = 0L
        pageTracker.reset()
        current = CashLobbySelectionStatus(CashLobbySelectionPhase.STOPPED, reason)
    }

    /** Explicitly start a single table-navigation session; no repeated joins. */
    @Synchronized fun start(context: Context): Boolean {
        if (armed || OperatorStopStore.isStopped(context) ||
            !FrameStore.isCapturing || CaptureObservationMode.isSpectator() ||
            !LiveGamePermissionGate.permitted() ||
            OneWinCashLobbyLimitsStore.read(context) == null ||
            LocalPlayerCheckPilot.isActive() ||
            OnDeviceOneWinTableAutoExit.active() ||
            OnDeviceLiveActionControl.guard.hasUnconfirmedAction()) return false
        pending = null
        pendingNavigation = null
        dispatched = false
        armed = true
        sortAttempted = false
        sorted = false
        pageBeforeSwipe = ""
        lastNavigationAtMs = 0L
        pageTracker.reset()
        current = CashLobbySelectionStatus(CashLobbySelectionPhase.WAITING_LOBBY,
            "Ожидается подтверждённый экран кэш-игр 1win")
        return true
    }

    /**
     * A navigation gesture is an observation transition, never a cash
     * transaction. The receiving Accessibility service rechecks exact
     * package/window/epoch, fresh OCR proof, STOP and screen geometry.
     */
    @Synchronized fun latestNavigationTicketMatches(
        ticket: OneWinCashLobbyNavTicket, nowMs: Long
    ): Boolean {
        if (!armed || dispatched || pendingNavigation != ticket ||
            current.phase != CashLobbySelectionPhase.NAVIGATION_PENDING ||
            nowMs < ticket.observedAtMs || nowMs - ticket.observedAtMs > 1_100L)
            return false
        return when (ticket.action) {
            OneWinCashLobbyNavAction.OPEN_SORT, OneWinCashLobbyNavAction.SWIPE_UP, OneWinCashLobbyNavAction.SWIPE_DOWN -> {
                val screen = OnDeviceOneWinCashLobby.latest()
                screen != null && screen.sourcePackage == ticket.sourcePackage &&
                    screen.captureEpoch == ticket.captureEpoch &&
                    screen.windowGeneration == ticket.windowGeneration &&
                    screen.confirmedAtMs >= ticket.observedAtMs
            }
            OneWinCashLobbyNavAction.CHOOSE_STAKES_ASC -> {
                val menu = OnDeviceOneWinCashLobby.latestSortMenu()
                menu != null && menu.sourcePackage == ticket.sourcePackage &&
                    menu.captureEpoch == ticket.captureEpoch &&
                    menu.windowGeneration == ticket.windowGeneration &&
                    menu.confirmedAtMs >= ticket.observedAtMs &&
                    menu.options.getValue(OneWinLobbySort.STAKES_ASC) == ticket.optionBox
            }
        }
    }

    private fun submitNavigation(
        context: Context, action: OneWinCashLobbyNavAction,
        sourcePackage: String, epoch: Long, window: Long, evidenceAt: Long,
        option: NormalizedTextBox? = null
    ) {
        if (pendingNavigation != null) return
        navSequence++
        val ticket = OneWinCashLobbyNavTicket(action, sourcePackage, epoch, window,
            evidenceAt, navSequence, option)
        pendingNavigation = ticket
        current = CashLobbySelectionStatus(CashLobbySelectionPhase.NAVIGATION_PENDING,
            "Навигация 1win: ${action.name}; ожидание результата жеста")
        val service = LiveForegroundProbe.activeService()
        if (service == null) {
            armed = false
            pendingNavigation = null
            current = CashLobbySelectionStatus(CashLobbySelectionPhase.REJECTED,
                "Accessibility недоступна для прокрутки/сортировки")
            return
        }
        service.offerOneWinCashLobbyNavigation(ticket) { completed ->
            synchronized(this) {
                if (pendingNavigation != ticket || !armed) return@synchronized
                pendingNavigation = null
                if (!completed) {
                    armed = false
                    current = CashLobbySelectionStatus(CashLobbySelectionPhase.REJECTED,
                        "Android не подтвердил жест ${ticket.action}; повтор запрещён")
                    return@synchronized
                }
                lastNavigationAtMs = SystemClock.elapsedRealtime()
                when (ticket.action) {
                    OneWinCashLobbyNavAction.OPEN_SORT ->
                        current = CashLobbySelectionStatus(
                            CashLobbySelectionPhase.WAITING_SORT_MENU,
                            "Меню сортировки нажато; ждём 2 совпадающих OCR-кадра")
                    OneWinCashLobbyNavAction.CHOOSE_STAKES_ASC -> {
                        pageTracker.reset()
                        current = CashLobbySelectionStatus(
                            CashLobbySelectionPhase.WAITING_SORTED_LOBBY,
                            "Выбраны ставки по возрастанию; ждём обновления списка")
                    }
                    OneWinCashLobbyNavAction.SWIPE_UP, OneWinCashLobbyNavAction.SWIPE_DOWN -> {
                        pageTracker.markSwipeSubmitted()
                        current = CashLobbySelectionStatus(
                            CashLobbySelectionPhase.WAITING_SCROLLED_LOBBY,
                            "Свайп вверх завершён; ждём следующую страницу")
                    }
                }
            }
        }
    }

    @Synchronized fun onSortMenuConfirmed(
        context: Context, menu: OneWinCashLobbySortMenuSnapshot
    ) {
        if (!armed || dispatched || pendingNavigation != null ||
            current.phase !in setOf(CashLobbySelectionPhase.WAITING_LOBBY,
                CashLobbySelectionPhase.WAITING_SORT_MENU)) return
        if (!menu.options.containsKey(OneWinLobbySort.STAKES_ASC) ||
            menu.sourcePackage != LiveGamePermissionGate.packageName() ||
            menu.captureEpoch != FrameStore.captureEpoch) return
        sortAttempted = true
        submitNavigation(context, OneWinCashLobbyNavAction.CHOOSE_STAKES_ASC,
            menu.sourcePackage, menu.captureEpoch, menu.windowGeneration,
            menu.confirmedAtMs, menu.options.getValue(OneWinLobbySort.STAKES_ASC))
    }

    @Synchronized fun latestTicketMatches(ticket: OneWinCashLobbyEntryTicket,
                                           nowMs: Long): Boolean {
        val lobby = OnDeviceOneWinCashLobby.latest() ?: return false
        return armed && !dispatched && pending == ticket &&
            current.phase == CashLobbySelectionPhase.RECOMMENDED &&
            lobby.sourcePackage == ticket.sourcePackage &&
            lobby.captureEpoch == ticket.captureEpoch &&
            lobby.windowGeneration == ticket.windowGeneration &&
            lobby.confirmedAtMs >= ticket.capturedAtMs &&
            nowMs >= lobby.confirmedAtMs && nowMs - lobby.confirmedAtMs <= 1_000L &&
            lobby.tables.any {
                it.stableKey() == ticket.key && it.buyInOcrBox == ticket.anchor
            }
    }

    @Synchronized fun onConfirmed(context: Context, lobby: OneWinCashLobbySnapshot) {
        val limits = OneWinCashLobbyLimitsStore.read(context)
        if (OperatorStopStore.isStopped(context) || limits == null) {
            reset("Нет установленных денежных лимитов или активен STOP")
            return
        }
        val choice = OneWinCashLobbySelector.choose(lobby.tables, limits)
        if (!armed || dispatched || pending != null) {
            if (!armed && choice != null)
                current = CashLobbySelectionStatus(CashLobbySelectionPhase.RECOMMENDED,
                    "Рекомендуемый из видимых столов: ${choice.table.label}; " +
                        "блайнды ${choice.table.smallBlindCents}/${choice.table.bigBlindCents} центов",
                    choice)
            return
        }
        if (pendingNavigation != null ||
            current.phase == CashLobbySelectionPhase.WAITING_SORT_MENU) return
        if (current.phase == CashLobbySelectionPhase.WAITING_SORTED_LOBBY) {
            if (lobby.capturedAtMs <= lastNavigationAtMs) return
            sorted = true
            pageTracker.reset()
            current = CashLobbySelectionStatus(CashLobbySelectionPhase.WAITING_LOBBY,
                "Меню закрыто, начинаем сканирование по возрастанию ставок")
        }
        if (current.phase == CashLobbySelectionPhase.WAITING_SCROLLED_LOBBY) {
            if (lobby.capturedAtMs <= lastNavigationAtMs) return
            if (pageTracker.pageSignature(lobby.tables) == pageBeforeSwipe) return
            current = CashLobbySelectionStatus(CashLobbySelectionPhase.WAITING_LOBBY,
                "Получен новый участок списка после свайпа")
        }
        if (current.phase != CashLobbySelectionPhase.WAITING_LOBBY) return
        if (!sortAttempted) {
            sortAttempted = true
            submitNavigation(context, OneWinCashLobbyNavAction.OPEN_SORT,
                lobby.sourcePackage, lobby.captureEpoch, lobby.windowGeneration,
                lobby.confirmedAtMs)
            return
        }
        pageTracker.recordConfirmedPage(lobby.tables)
        if (choice == null) {
            if (!pageTracker.canSwipe()) {
                armed = false
                current = CashLobbySelectionStatus(CashLobbySelectionPhase.SCAN_EXHAUSTED,
                    "Поиск завершён: ${pageTracker.distinctTablesSeen()} разных столов, " +
                        "${pageTracker.swipesSent} свайпов, подходящих по лимитам нет")
                return
            }
            pageBeforeSwipe = pageTracker.pageSignature(lobby.tables)
            submitNavigation(context, OneWinCashLobbyNavAction.SWIPE_UP,
                lobby.sourcePackage, lobby.captureEpoch, lobby.windowGeneration,
                lobby.confirmedAtMs)
            return
        }
        if (CaptureObservationMode.isSpectator() ||
            OnDeviceLiveActionControl.guard.hasUnconfirmedAction() ||
            LocalPlayerCheckPilot.isActive() ||
            LiveGamePermissionGate.packageName() != lobby.sourcePackage ||
            FrameStore.captureEpoch != lobby.captureEpoch) return

        val ticket = OneWinCashLobbyEntryTicket(
            choice.table.stableKey(), lobby.sourcePackage, lobby.captureEpoch,
            lobby.windowGeneration, lobby.confirmedAtMs,
            choice.table.buyInOcrBox, choice.table.label,
            choice.permittedBuyInCapCents)
        pending = ticket
        current = CashLobbySelectionStatus(CashLobbySelectionPhase.RECOMMENDED,
            "Выбран стол ${choice.table.label}; навигация без подтверждения бай-ина",
            choice)
        val service = LiveForegroundProbe.activeService()
        if (service == null) {
            pending = null
            current = CashLobbySelectionStatus(CashLobbySelectionPhase.REJECTED,
                "Служба Accessibility не подключена", choice)
            return
        }
        service.offerOneWinCashLobbyEntry(ticket) { accepted ->
            synchronized(this) {
                if (pending != ticket || !armed) return@synchronized
                pending = null
                dispatched = true
                armed = false
                current = CashLobbySelectionStatus(
                    if (accepted) CashLobbySelectionPhase.ENTRY_SUBMITTED
                    else CashLobbySelectionPhase.REJECTED,
                    if (accepted) "Нажат вход в ${choice.table.label}. " +
                        "Бай-ин не подтверждался. Требуется проверка нового экрана."
                    else "Android отклонил вход в стол; повторного касания нет",
                    choice)
            }
        }
    }
}
