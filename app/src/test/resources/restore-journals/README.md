# Golden restore journals

Fixed bytes of `synthx.restore-state.json` as older and current builds wrote them.
They are hand-written text, never produced by today's serializer, so a renamed
field or enum value in `RestoreTransaction.State` makes the tests that read them fail.

- `v2.4-complete.json`: shape shipped in v2.2.0 to v2.4.x (database 14, no
  `rollbackSource`, `requestId`, `completedRequestIds`). `RestoreJournalCompatibilityTest`
  proves these bytes equal the output of a test-only copy of the v2.4.1 class.
- `v2.5-complete-rolled-back.json`: shape shipped from v2.5.0 (database 16).
- `active-post-validated.json`: the "active" golden. Active journals are accepted only
  at the current database version, so this file must follow `SynthXDatabase.VERSION`.

The two folders `app/src/test/resources/restore-journals` and
`app/src/androidTest/assets/restore-journals` must stay byte-identical
(`RestoreJournalCompatibilityTest` checks it).

## When the database version bumps

`RestoreJournalCompatibilityTest.activeGoldenFollowsTheCurrentDatabaseVersion` fails on
purpose. Regenerate the active golden by hand, in both folders:

1. In `active-post-validated.json`, change `"candidateVersion":N` and `"oldVersion":N`
   to the new version. Change nothing else.
2. Copy the file to the other folder and check with `cmp`.
3. Never touch `v2.4-complete.json` or `v2.5-complete-rolled-back.json`: they record
   what shipped builds wrote.
4. If `RestoreTransaction.State` gained a field, add a new golden for the new shape
   instead of editing an old one.
