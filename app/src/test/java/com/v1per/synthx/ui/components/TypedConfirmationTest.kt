package com.v1per.synthx.ui.components

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The rule gating both irreversible actions in the app — deleting a song's file and replacing a
 * song — so the cases it must and must not accept are pinned rather than left to the two call
 * sites to agree on informally.
 */
class TypedConfirmationTest {

    @Test fun matchesExactWord() {
        assertTrue(typedConfirmationMatches("Delete", "Delete"))
    }

    @Test fun rejectsWrongCase() {
        // The whole point of the gate is that the word can't be produced by muscle memory.
        assertFalse(typedConfirmationMatches("delete", "Delete"))
        assertFalse(typedConfirmationMatches("DELETE", "Delete"))
    }

    @Test fun rejectsEmpty() {
        assertFalse(typedConfirmationMatches("", "Delete"))
        assertFalse(typedConfirmationMatches("   ", "Delete"))
    }

    @Test fun rejectsPartialAndExtraWords() {
        assertFalse(typedConfirmationMatches("Del", "Delete"))
        assertFalse(typedConfirmationMatches("Delete it", "Delete"))
        assertFalse(typedConfirmationMatches("DeleteDelete", "Delete"))
    }

    @Test fun toleratesSurroundingWhitespace() {
        // A soft keyboard's auto-space after a word is not the user failing to confirm.
        assertTrue(typedConfirmationMatches("Delete ", "Delete"))
        assertTrue(typedConfirmationMatches("  Delete", "Delete"))
    }

    @Test fun worksForTheReplaceWordToo() {
        assertTrue(typedConfirmationMatches("Replace", "Replace"))
        assertFalse(typedConfirmationMatches("Delete", "Replace"))
    }
}
