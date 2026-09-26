# Nuvio Enhanced Repository Rules

## Canonical Enhanced build

The canonical custom Nuvio Enhanced repository is:

- Path: `/Users/mac/Developer/NuvioTV_Enhanced`
- Branch: `dev`
- Promotion baseline: `1912ac97b` (`Use cached post-credit scenes during analyzer fallback`)

Later commits on `dev` are expected. Do not reset the branch back to the promotion baseline.

Before making changes, always verify:

- `pwd`
- `git status --short --branch`
- `git --no-pager log -5 --oneline --decorate`
- `git worktree list`

## Repositories that must not be confused with Enhanced

### Archived old worktree

`/Users/mac/Developer/NuvioTV_420`

This is an archived/stale worktree on:

`archive/dev-stale-before-enhanced-promote-20260926`

Do not build from it.
Do not add new Enhanced work to it.
Do not switch it back to `dev`.

### Official beta 0.9.5 experiment

`/Users/mac/Developer/NuvioTV_OfficialBeta_Port`

This is a completely separate repository based on official Nuvio `0.9.5-beta`.

Its `platform-pages-port` work and build rules are unrelated to Enhanced.

Never merge, copy, reset, or otherwise mix this repository into Enhanced unless explicitly requested.

The no-AOT policy used for the official-beta experiment does NOT apply to the Enhanced repository.

## Android package safety

For Enhanced testing and installation, only touch:

`com.nuvio.tv.sideload`

Do not install over, clear data for, force-stop, compile, uninstall, or otherwise modify:

- `com.nuvio.tv`
- `com.nuviodebug.com`

unless explicitly requested.

## Enhanced build workflow

Use incremental builds first.

Normal build:

`./gradlew :app:assembleSideload`

Expected APK:

`app/build/outputs/apk/sideload/app-armeabi-v7a-sideload.apk`

For this Enhanced build, post-install AOT is allowed:

`adb shell cmd package compile -f -m speed com.nuvio.tv.sideload`

Do not apply the official-beta no-AOT rule here.

## Credit analyzer configuration

`local.properties` contains:

- `CREDIT_ANALYZER_BASE_URL`
- `CREDIT_ANALYZER_TOKEN`

Never print their values.
Never overwrite `local.properties` from a backup.
Never commit analyzer secrets.

`local.properties.before-credit-sync-20260926-141510` is a local backup and should remain untracked.

## Performance

UI scrolling must remain as smooth as the known-good Enhanced build.

Avoid adding work to active D-pad or scroll paths.

Do not use `clean`, `--rerun-tasks`, or destructive Gradle/cache operations unless there is a concrete reason.

## Git safety

Do not reset, rebase, cherry-pick, force-update branches, or discard changes merely because commits have similar names.

Inspect actual ancestry and diffs first.

Do not push unless explicitly requested.

Before committing:

- `git diff --check`
- `git status --short`
- `git diff --stat`

Stage only files belonging to the current task.
