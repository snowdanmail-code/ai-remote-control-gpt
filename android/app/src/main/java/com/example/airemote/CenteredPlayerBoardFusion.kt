package com.example.airemote

/**
 * Combines two independent real-card observations without inventing missing
 * ranks/suits. Partial glyphs are display only, never verified action input.
 */
object CenteredPlayerBoardFusion {
    fun merge(legacy: CardReading, centered: VisibleCommunityReading): CardReading {
        if (centered.cards.isEmpty()) return legacy
        val preview = if (centered.glyphs.isNotEmpty()) centered.glyphs
            else legacy.communityGlyphs
        fun partial(board: List<String?>, reason: String) = CardReading(
            legacy.hole, board, false, 0.0, reason, legacy.holeGlyphs, preview)
        if (!centered.complete) {
            if (legacy.complete && legacy.community.isEmpty())
                return partial(centered.cards, "CENTERED_VISIBLE_CARDS_NOT_CLASSIFIED")
            return if (legacy.complete || legacy.community.size >= centered.cards.size)
                legacy else partial(centered.cards, "CENTERED_BOARD_PARTIAL_ONLY")
        }
        if (legacy.community.isNotEmpty()) {
            if (legacy.community.size != centered.cards.size ||
                legacy.community.zip(centered.cards).any { (a,b) -> a != null && a != b })
                return partial(centered.cards,"CENTERED_LEGACY_BOARD_DISAGREEMENT")
            if (legacy.complete) return legacy
        } else if (legacy.complete) {
            return partial(centered.cards,"CENTERED_BOARD_INVALIDATES_LEGACY_PREFLOP")
        }
        // If a complete hero pair is already in this *same* observation, pair
        // it with the independently completed public board. Never manufacture
        // confidence from incomplete glyphs or assert source/game identity.
        val hero = legacy.hole.filterNotNull()
        val board = centered.cards.filterNotNull()
        val compatible = hero.size == 2 && legacy.hole.size == 2 &&
            board.size == centered.cards.size &&
            CardVisionCore.checkUnique(hero,board)
        val heroQuality = if (legacy.holeMinimumSimilarity > 0.0)
            legacy.holeMinimumSimilarity else legacy.minimumSimilarity
        val sufficientlyMeasured = heroQuality >= 0.78 &&
            centered.minimumSimilarity >= 0.78
        if (compatible && sufficientlyMeasured)
            return CardReading(legacy.hole, centered.cards, true,
                kotlin.math.min(heroQuality,centered.minimumSimilarity),
                "CENTERED_FUSED_INDEPENDENT_CARD_SYMBOLS",
                legacy.holeGlyphs, preview, heroQuality, centered.minimumSimilarity)
        return partial(centered.cards, "CENTERED_PUBLIC_BOARD_DIAGNOSTIC_ONLY")
    }
}
