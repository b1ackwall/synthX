package com.v1per.synthx.ui.common

import android.content.Context
import com.v1per.synthx.R
import com.v1per.synthx.data.scanner.DestructiveFilePreflightResult
import com.v1per.synthx.data.scanner.KnownAudioRoot

fun Context.destructiveFileBlockMessage(
    reason: DestructiveFilePreflightResult.Blocking,
): String = getString(
    when (reason) {
        is DestructiveFilePreflightResult.GrantNotWritable -> when (reason.root) {
            KnownAudioRoot.Music -> R.string.destructive_file_music_grant_not_writable
            KnownAudioRoot.NewArrivals -> R.string.destructive_file_new_arrivals_grant_not_writable
        }
        DestructiveFilePreflightResult.DocumentNotWritable ->
            R.string.destructive_file_document_not_writable
        DestructiveFilePreflightResult.IdentityMismatch ->
            R.string.destructive_file_identity_mismatch
        DestructiveFilePreflightResult.RootUnknown -> R.string.destructive_file_root_unknown
    },
)
