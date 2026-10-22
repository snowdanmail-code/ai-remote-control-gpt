package com.example.airemote

/**
 * Merge independent centered public-board candidates with hero observations.
 * This never turns an unverified public board into a verified turn, table or
 * action permission. A disagreement invalidates an apparent preflop reading.
 */
object CenteredPlayerBoardFusion {
    fun merge(legacy: CardReading, centered: VisibleCommunityReading): CardReading {
        if (centered.cards.isEmpty()) return legacy
        if (!centered.complete) {
            // The geometry sees 3-5 card rectangles, but their glyphs are not
            // confirmed. Never silently report PRE-FLOP from a missed old ROI.
            if (legacy.complete && legacy.community.isEmpty()) return CardReading(
                legacy.hole, centered.cards, false, 0.0,
                "CENTERED_VISIBLE_CARDS_NOT_CLASSIFIED")
            return if (legacy.complete || legacy.community.size >= centered.cards.size)
                legacy else CardReading(legacy.hole, centered.cards, false, 0.0,
                    "CENTERED_BOARD_PARTIAL_ONLY")
        }
        if (legacy.community.isNotEmpty()) {
            if (legacy.community.size != centered.cards.size ||
                legacy.community.zip(centered.cards).any { (a,b) -> a != null && a != b })
                return CardReading(legacy.hole, centered.cards, false, 0.0,
                    "CENTERED_LEGACY_BOARD_DISAGREEMENT")
            // A complete matching legacy read retains its original confidence.
            if (legacy.complete) return legacy
        } else if (legacy.complete) {
            // Legacy visually claimed preflop although the independently
            // located public board actually has 3-5 cards.
            return CardReading(legacy.hole, centered.cards, false, 0.0,
                "CENTERED_BOARD_INVALIDATES_LEGACY_PREFLOP")
        }
        // Player hero must still be separately classified and accepted by
        // the existing CardConsensus on at least two fresh distinct frames.
        return CardReading(legacy.hole, centered.cards, false, 0.0,
            "CENTERED_PUBLIC_BOARD_DIAGNOSTIC_ONLY")
    }
}
