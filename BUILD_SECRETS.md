# Nuvio durable build configuration

This custom Nuvio build intentionally keeps critical runtime configuration outside the Git repository and outside all Git worktrees.

## Canonical location

On Brandon's build Mac, the canonical production/sideload values live at:

`~/.nuvio/secrets.properties`

Do **not** move these values back into `local.properties`, `local.dev.properties`, a worktree, or any committed file as the primary source. Those repo-local files have been overwritten during development before, which silently produced APKs with disabled functionality.

The Gradle build reads `~/.nuvio/secrets.properties` first for production/sideload builds and only uses `local.properties` as a fallback for non-critical legacy values.

## Required sideload values

A sideload build must have all four of these values:

- `CREDIT_ANALYZER_BASE_URL`
- `CREDIT_ANALYZER_TOKEN`
- `SUPABASE_URL`
- `SUPABASE_ANON_KEY`

`assembleSideload` is intentionally configured to fail rather than create a broken APK when any of these four values is missing.

If the build fails with `Missing required Nuvio sideload config`, restore the missing value(s) in `~/.nuvio/secrets.properties`. Do not work around the guard and do not replace the missing values with empty strings.

## Why these matter

- The Credit Analyzer requires both `CREDIT_ANALYZER_BASE_URL` and `CREDIT_ANALYZER_TOKEN`. Without them the app reports the analyzer as unconfigured and credit-aware post-play will not run.
- The production/sideload Supabase client requires `SUPABASE_URL` and `SUPABASE_ANON_KEY`; these are part of the Nuvio authentication/login configuration.

## Distribution behavior

The external secrets file is only a build-time source on Brandon's Mac. The values are compiled into the sideload APK, so the small group of friends receiving the APK do **not** need this file on their Android TV devices.

## Recovery notes

If `~/.nuvio/secrets.properties` is ever lost, do not invent replacement values. Known-good values may be recoverable from an older successful sideload build's generated `BuildConfig.java` or from an already-installed known-good APK. Compare/recover without printing credentials into logs or chat whenever possible.

As of the migration that introduced this file, the known-good Credit Analyzer values were recovered from an older successful `NuvioTV_SimklFix` sideload build, and the Supabase values were recovered consistently from surviving Nuvio worktrees.

## Safety rule for future AI/developers

When changing build configuration, preserve this external canonical-source design. Branch switching, worktree creation, resets, clean builds, and automated repo edits must not be able to erase the only copy of these values.