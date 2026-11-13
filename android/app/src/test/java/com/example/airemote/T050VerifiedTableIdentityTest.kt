package com.example.airemote

import org.junit.Assert.*
import org.junit.Test

class T050VerifiedTableIdentityTest {
    private fun identity(table: String = "table-A",
                         epoch: Long = 21L,
                         window: Long = 3L) =
        T050VerifiedTableIdentity("com.example.game", epoch, window, table)

    @Test fun firstVerifiedTableMustPurgePreVerificationProfiles() {
        val guard = T050TableIdentityLedger()
        assertNull(guard.current())
        assertEquals(T050TableIdentityResult.UNVERIFIED,
            guard.accept(identity(), false))
        assertNull(guard.current())
        assertEquals(T050TableIdentityResult.FIRST_TRUSTED_ID,
            guard.accept(identity(), true))
        assertEquals(identity(), guard.current())
        assertEquals(T050TableIdentityResult.SAME_TRUSTED_TABLE,
            guard.accept(identity(), true))
        assertTrue(guard.belongsTo(identity()))
    }

    @Test fun secondTableWithinSameWindowMustBlockOldSeatMemory() {
        val guard = T050TableIdentityLedger()
        guard.accept(identity("table-A"), true)
        assertEquals(T050TableIdentityResult.CHANGED_TABLE_OR_WINDOW,
            guard.accept(identity("table-B"), true))
        assertFalse(guard.belongsTo(identity("table-B")))
        assertEquals(identity("table-A"), guard.current())
        guard.reset()
        assertEquals(T050TableIdentityResult.FIRST_TRUSTED_ID,
            guard.accept(identity("table-B"), true))
    }

    @Test fun staleEpochOrWindowCannotInheritAnotherSeating() {
        val guard = T050TableIdentityLedger()
        guard.accept(identity(), true)
        assertEquals(T050TableIdentityResult.CHANGED_TABLE_OR_WINDOW,
            guard.accept(identity(epoch=22L),true))
        assertEquals(T050TableIdentityResult.CHANGED_TABLE_OR_WINDOW,
            guard.accept(identity(window=4L),true))
        assertFalse(guard.belongsTo(identity(window=4L)))
    }

    @Test fun unverifiedAndMalformedIdentifiersNeverBind() {
        val guard = T050TableIdentityLedger()
        for (id in listOf(
            identity(""),
            identity("table\nspoof"),
            identity("A".repeat(110)),
            identity().copy(sourcePackage=""),
            identity(epoch=-1L)
        )) {
            assertEquals(T050TableIdentityResult.UNVERIFIED,guard.accept(id,true))
        }
        assertNull(guard.current())
    }

    @Test fun unverifiedNewTableCannotRewritePreviouslyConfirmedIdentity() {
        val guard = T050TableIdentityLedger()
        guard.accept(identity("A"),true)
        assertEquals(T050TableIdentityResult.UNVERIFIED,
            guard.accept(identity("B"),false))
        assertEquals(identity("A"),guard.current())
    }
}
