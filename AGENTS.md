# Repository guidance

## Product invariants

- The app is offline-first. Saving an inspection must never depend on cloud availability.
- Never use a destructive Room migration. Existing records, media, signatures and templates must survive upgrades.
- Every business entity uses a UUID and an `updatedAt` timestamp so Android, Windows and cloud providers can reconcile changes.
- Android and Windows must use the same sync protocol and preserve tombstones, conflict copies and the recycle-bin rules.
- A normal delete is recoverable. A permanent delete requires a password and explicit confirmation.
- PDF export is A4. Annual, quarterly and multi-select exports produce one PDF; each inspection date restarts its own `第1页/共N页` counter.
- Template edits affect future inspections only. Historical records keep their copied inspection items.
- Photo watermarks use EXIF capture time and available GPS/location. Do not invent a time, address or position that was not obtained.
- Secrets, signing keystores, OAuth client secrets, device tokens and real cloud identifiers must never be committed.

## Required checks

- Android: `gradle :app:testDebugUnitTest :app:assembleDebug`
- Sync server: `npm ci && npm run check`
- Desktop: `node tests/smoke.mjs`
