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
    OFF, WAITING_LOBBY, RECOMMENDED, ENTRY_SUBMITTED, REJECTED, EXPIRED, STOPPED
}
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
    @Volatile private var current = CashLobbySelectionStatus(
        CashLobbySelectionPhase.OFF, "Автоматический выбор стола выключен")

    @Synchronized fun status() = current
    @Synchronized fun scanning(): Boolean = armed && !dispatched &&
        current.phase in setOf(CashLobbySelectionPhase.WAITING_LOBBY,
            CashLobbySelectionPhase.RECOMMENDED)

    @Synchronized fun reset(reason: String = "STOP") {
        armed = false
        dispatched = false
        pending = null
        current = CashLobbySelectionStatus(CashLobbySelectionPhase.STOPPED, reason)
    }

    /** Explicitly start a single table-navigation session; no repeated joins. */
    @Synchronized fun start(context: Context): Boolean {
        if (armed || OperatorStopStore.isStopped(context) ||
            !FrameStore.isCapturing || CaptureObservationMode.isSpectator() ||
            !LiveGamePermissionGate.permitted() ||
            OneWinCashLobbyLimitsStore.read(context) == null ||
            LocalPlayerCheckPilot.isActive() ||
            OnDeviceLiveActionControl.guard.hasUnconfirmedAction()) return false
        pending = null
        dispatched = false
        armed = true
        current = CashLobbySelectionStatus(CashLobbySelectionPhase.WAITING_LOBBY,
            "Ожидается подтверждённый экран кэш-игр 1win")
        return true
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
        if (choice == null) {
            current = CashLobbySelectionStatus(CashLobbySelectionPhase.WAITING_LOBBY,
                "Пока нет видимых столов, подходящих под установленные лимиты")
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
