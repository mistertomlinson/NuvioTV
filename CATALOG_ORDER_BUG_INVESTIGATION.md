# Catalog Order Reset — Diagnostic Investigation

## STATUS: AWAITING RUNTIME DATA — a logging probe is LIVE in the build.
**Do NOT ship with the probe.** Before shipping, grep `CATALOG_ORDER_PROBE` and
remove all hits (probe helper file, the two call sites, and the @ApplicationContext
param added to LayoutPreferenceDataStore for the probe). See "How to remove" below.

## Symptom
Home catalog row order resets "every few days." Affects ALL catalogs, not just
special ones — confirmed to move My List, Watchly catalogs, AND ordinary ones
like "Trending Movies." User must manually re-order each time.
Environment: self-hosted Watchly addon (id `com.bimal.watchly`) whose catalog IDs
rotate each session and whose manifest fetch is slow (timeout workarounds exist).
A My List feature (Trakt watchlist) is also present.

## How to use the probe WHEN THE BUG OCCURS
1. Pull the persistent log (survives reboots; package `com.nuviodebug.com`):
   adb -s <device> shell run-as com.nuviodebug.com cat files/catalog_order_probe.log > order_log.txt
   (File path is also printed in logcat under tag CATALOG_ORDER_PROBE.)
2. Hand order_log.txt + this document to Claude to continue without re-deriving.

## What the log records
- WRITE events: every persist via LayoutPreferenceDataStore.setHomeCatalogOrderKeys.
  Fields: caller=<stack>, emptyWipe=<bool>, beforeCount, afterCount, dropped=[...],
  after=[...]. `caller` identifies what triggered the save. emptyWipe=true means
  the order was DELETED (incoming empty).
- RECONCILE events: every HomeViewModel.rebuildCatalogOrder. Fields:
  savedInput=[...], output=[...], droppedFromSaved=[...].

## THE DECISION THE LOG RESOLVES (was the open question)
Does the reset PERSIST or VARY per launch? (User was unsure.)
- A WRITE shrinks/reorders WITHOUT the user touching settings/QR -> automatic save
  corrupting persisted data; the caller stack names it. Watch for emptyWipe=true
  or a large dropped list.
- WRITEs look correct but a RECONCILE emits wrong output from good savedInput ->
  load-time bug in rebuildCatalogOrder (HomeViewModelCatalogUtils.kt).
- RECONCILE savedInput already wrong -> trace back to the corrupting WRITE.

## PRIME SUSPECT to check first
LayoutPreferenceDataStore.setHomeCatalogOrderKeys (~line 298):
`if (normalizedKeys.isEmpty()) prefs.remove(homeCatalogOrderKeysKey)` — an empty
save DELETES the entire order. If any path calls it with empty keys during a
transient state, the order is wiped. Probe flags this as emptyWipe=true + caller.

## Theories ALREADY RULED OUT — do NOT re-investigate
1. My List special-case (rebuildCatalogOrder ~line 94-98): force-inserts My List
   at index 0 ONLY when MISSING from saved order; honors saved position otherwise.
   Not the general-reset cause. (Desired behavior: My List position always honored,
   no forced default. If wanted, remove the add(0,...) so it flows as ordinary key.)
2. Watchly grouping via watchlyGroup() (hardcoded com.bimal.watchly, ~line 121):
   robust. A looser watchlyGroupPrefix() (checks parts[1]) exists; mildly suspicious,
   not confirmed causal.
3. normalizeCatalogOrderKeys (LayoutPreferenceDataStore ~614): only trims/dedupes.
   NON-destructive. NOT the cause.
4. QR-config filter (AddonManagerViewModel ~305 and ~423:
   filter { it in availableCatalogKeys }): REAL LATENT BUG — prunes keys for
   transiently-absent catalogs (Watchly manifest slow/rotated) and persists the
   pruned order. BUT only runs via confirmPendingChange() (QR remote-config flow),
   user-initiated. NOT the automatic reset unless QR config used that often.
   READY FIX (only if log implicates QR flow): replace both
   filter { it in availableCatalogKeys } with:
     .filter { key -> key in availableCatalogKeys ||
         targetAddons.any { key.startsWith(it.id + "_") } }
   Preserves keys whose addon is still installed even if manifest absent.
   catalogKey format is "${addonId}_${type}_${catalogId}" (verified). The
   empty-fallback at ~303-304 needs the same non-destructive treatment.

## Save sites (all four; all appear user-triggered — hence the puzzle)
- AddonManagerViewModel:434 — QR flow (manual). Latent bug above.
- CatalogOrderViewModel:64, 192, 445 — settings reorder screen (manual).
- LayoutPreferenceDataStore:298 setHomeCatalogOrderKeys — PROBE INSTRUMENTS THIS.

## Reconcile
- HomeViewModel.rebuildCatalogOrder (HomeViewModelCatalogUtils ~line 46; writes at
  ~118-119). PROBE INSTRUMENTS THIS.
- Loaded async: loadHomeCatalogOrderPreferencePipeline collectLatest;
  homeCatalogOrderKeys starts as emptyList() (HomeViewModel ~178) — transient-empty
  window at startup. Watch for a WRITE during it.

## How to remove the probe before shipping
1. Delete app/src/main/java/com/nuvio/tv/data/local/CatalogOrderProbe.kt
2. LayoutPreferenceDataStore.kt: remove the CatalogOrderProbe.log(...) call in
   setHomeCatalogOrderKeys and revert the added @ApplicationContext probeContext param.
3. HomeViewModelCatalogUtils.kt: remove the CatalogOrderProbe.log(...) call in
   rebuildCatalogOrder.
4. Delete this file.
5. Verify: grep -rn CATALOG_ORDER_PROBE app/  returns nothing.
