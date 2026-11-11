package com.example.airemote

import org.json.JSONArray
import org.json.JSONObject

/** Android's org.json is used only at the data boundary, not by pure Kotlin model objects. */
object CanonicalPokerJson {
    private fun JSONObject.requiredString(key: String): String {
        val value = get(key)
        require(value is String) { "Expected string at $key" }
        return value
    }
    private fun JSONObject.stringOrNull(key: String): String? =
        if (!has(key) || isNull(key)) null else getString(key)
    private fun JSONObject.intOrNull(key: String): Int? =
        if (!has(key) || isNull(key)) null else getInt(key)
    private fun JSONObject.longOrNull(key: String): Long? =
        if (!has(key) || isNull(key)) null else getLong(key)
    private fun JSONObject.boolOrNull(key: String): Boolean? =
        if (!has(key) || isNull(key)) null else getBoolean(key)
    private fun JSONObject.putNullable(key: String, value: Any?): JSONObject =
        put(key, value ?: JSONObject.NULL)
    private fun <T : Enum<T>> enumOrUnknown(value: String?, unknown: T, values: Array<T>): T =
        values.firstOrNull { it.name == value } ?: unknown
    private fun evidence(value: String?) = enumOrUnknown(value, PokerEvidence.UNKNOWN, PokerEvidence.values())
    private fun street(value: String?) = enumOrUnknown(value, PokerStreet.UNKNOWN, PokerStreet.values())
    private fun actionKind(value: String?) = enumOrUnknown(value, PokerActionKind.UNKNOWN, PokerActionKind.values())
    private fun JSONArray.items(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

    private fun cardJson(card: CanonicalPokerCard) = JSONObject().put("rank", card.rank).put("suit", card.suit)
    private fun parseCard(json: JSONObject) = CanonicalPokerCard(json.getString("rank"), json.getString("suit"))
    private fun cards(cards: List<CanonicalPokerCard>?): Any =
        if (cards == null) JSONObject.NULL else JSONArray().apply { cards.forEach { put(cardJson(it)) } }
    private fun parseCards(json: JSONObject, key: String): List<CanonicalPokerCard>? =
        if (!json.has(key) || json.isNull(key)) null else json.getJSONArray(key).items().map(::parseCard)

    fun encodeObservation(observation: CanonicalPokerObservation): String = observationObject(observation).toString()
    private fun observationObject(o: CanonicalPokerObservation): JSONObject {
        val source = JSONObject().put("namespace", o.source.namespace)
            .putNullable("tableId", o.source.tableId).putNullable("sessionId", o.source.sessionId)
            .putNullable("handId", o.source.handId)
        val seats = JSONArray().apply { o.seats.forEach { s ->
            put(JSONObject().put("seatIndex", s.seatIndex).putNullable("participantId", s.participantId)
                .putNullable("nickname", s.nickname).put("identityEvidence", s.identityEvidence.name)
                .putNullable("dealtIn", s.dealtIn).putNullable("activeInHand", s.activeInHand)
                .putNullable("stackChips", s.stackChips).put("holeCards", cards(s.holeCards)))
        } }
        val actions = JSONArray().apply { o.actions.forEach { a ->
            put(JSONObject().put("sequence", a.sequence).put("eventId", a.eventId)
                .put("street", a.street.name)
                .putNullable("actorSeat", a.actorSeat).put("kind", a.kind.name)
                .putNullable("amountChips", a.amountChips).put("evidence", a.evidence.name))
        } }
        return JSONObject().put("recordType", "observation").put("schemaMajor", o.schemaMajor)
            .put("schemaMinor", o.schemaMinor).put("source", source).put("observationId", o.observationId)
            .put("tableCapacity", o.tableCapacity).put("rulesetId", o.rulesetId).put("seats", seats)
            .put("seatingComplete", o.seatingComplete)
            .putNullable("capturedAtMs", o.capturedAtMs).putNullable("buttonSeat", o.buttonSeat)
            .putNullable("heroSeat", o.heroSeat).put("street", o.street.name)
            .put("boardCards", cards(o.boardCards)).putNullable("potChips", o.potChips)
            .put("actions", actions).put("observationEvidence", o.observationEvidence.name)
    }

    fun decodeObservation(text: String): CanonicalPokerObservation = parseObservation(JSONObject(text))
    private fun parseObservation(j: JSONObject): CanonicalPokerObservation {
        require(j.optString("recordType") == "observation") { "Not an observation" }
        val major = j.getInt("schemaMajor")
        val minor = j.getInt("schemaMinor")
        CanonicalPokerSchema.check(major, minor)
        val src = j.getJSONObject("source")
        val seats = j.getJSONArray("seats").items().map { s ->
            CanonicalPokerSeat(
                seatIndex = s.getInt("seatIndex"), participantId = s.stringOrNull("participantId"),
                nickname = s.stringOrNull("nickname"),
                identityEvidence = evidence(s.stringOrNull("identityEvidence")),
                dealtIn = s.boolOrNull("dealtIn"), activeInHand = s.boolOrNull("activeInHand"),
                stackChips = s.longOrNull("stackChips"), holeCards = parseCards(s, "holeCards")
            )
        }
        val actions = j.getJSONArray("actions").items().map { a ->
            CanonicalPokerAction(
                sequence = a.getLong("sequence"), street = street(a.stringOrNull("street")),
                eventId = a.requiredString("eventId"),
                actorSeat = a.intOrNull("actorSeat"), kind = actionKind(a.stringOrNull("kind")),
                amountChips = a.longOrNull("amountChips"), evidence = evidence(a.stringOrNull("evidence"))
            )
        }
        return CanonicalPokerObservation(
            source = PokerSourceIdentity(src.getString("namespace"), src.stringOrNull("tableId"),
                src.stringOrNull("sessionId"), src.stringOrNull("handId")),
            observationId = j.requiredString("observationId"), tableCapacity = j.getInt("tableCapacity"),
            rulesetId = j.requiredString("rulesetId"),
            seats = seats, seatingComplete = j.boolOrNull("seatingComplete") == true,
            schemaMajor = major, schemaMinor = minor,
            capturedAtMs = j.longOrNull("capturedAtMs"), buttonSeat = j.intOrNull("buttonSeat"),
            heroSeat = j.intOrNull("heroSeat"), street = street(j.stringOrNull("street")),
            boardCards = parseCards(j, "boardCards"), potChips = j.longOrNull("potChips"),
            actions = actions, observationEvidence = evidence(j.stringOrNull("observationEvidence"))
        )
    }

    fun encodeVerifiedHand(record: VerifiedPokerHandRecord): String =
        JSONObject().put("recordType", "verifiedHand").put("schemaMajor", record.schemaMajor)
            .put("schemaMinor", record.schemaMinor)
            .put("terminalObservation", observationObject(record.terminalObservation))
            .put("rulesetId", record.rulesetId).put("handRecordId", record.handRecordId)
            .put("verificationPolicyId", record.verificationPolicyId)
            .put("sourceProofRef", record.sourceProofRef).put("handIdentityProofRef", record.handIdentityProofRef)
            .put("terminalProofRef", record.terminalProofRef)
            .put("sourceEvidence", record.sourceEvidence.name)
            .put("handIdentityEvidence", record.handIdentityEvidence.name)
            .put("terminalEvidence", record.terminalEvidence.name)
            .putNullable("heroNetChips", record.heroNetChips)
            .put("resultEvidence", record.resultEvidence.name).toString()

    fun decodeVerifiedHand(text: String): VerifiedPokerHandRecord {
        val j = JSONObject(text)
        require(j.optString("recordType") == "verifiedHand") { "Not a verified hand" }
        val major = j.getInt("schemaMajor")
        val minor = j.getInt("schemaMinor")
        CanonicalPokerSchema.check(major, minor)
        return VerifiedPokerHandRecord(
            terminalObservation = parseObservation(j.getJSONObject("terminalObservation")),
            sourceProofRef = j.getString("sourceProofRef"),
            handIdentityProofRef = j.getString("handIdentityProofRef"),
            terminalProofRef = j.getString("terminalProofRef"),
            sourceEvidence = evidence(j.stringOrNull("sourceEvidence")),
            handIdentityEvidence = evidence(j.stringOrNull("handIdentityEvidence")),
            terminalEvidence = evidence(j.stringOrNull("terminalEvidence")),
            heroNetChips = j.longOrNull("heroNetChips"),
            resultEvidence = evidence(j.stringOrNull("resultEvidence")),
            schemaMajor = major, schemaMinor = minor,
            rulesetId = j.requiredString("rulesetId"),
            handRecordId = j.requiredString("handRecordId"),
            verificationPolicyId = j.requiredString("verificationPolicyId")
        )
    }
}
