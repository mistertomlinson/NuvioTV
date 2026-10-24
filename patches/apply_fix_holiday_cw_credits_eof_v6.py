#!/usr/bin/env python3
from __future__ import annotations

import importlib.util
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
V4_PATCH = ROOT / "patches/apply_fix_holiday_cw_credits_eof_v4.py"


def load_v4_module():
    spec = importlib.util.spec_from_file_location("holiday_cw_credits_eof_v4_repair", V4_PATCH)
    if spec is None or spec.loader is None:
        raise SystemExit(f"Unable to load {V4_PATCH}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def replace_exact(path: Path, old: str, new: str, *, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    if new in text:
        print(f"[already applied] {label}")
        return
    count = text.count(old)
    if count != 1:
        raise SystemExit(
            f"{label}: expected exactly one original fragment in {path}, found {count}"
        )
    path.write_text(text.replace(old, new, 1), encoding="utf-8")
    print(f"[patched] {label}")


def require(path: Path, needle: str, *, label: str) -> None:
    if needle not in path.read_text(encoding="utf-8"):
        raise SystemExit(f"{label}: validation marker missing from {path}")


def main() -> None:
    # Brandon's checkout already contains the V1-V3 work, but a V5 retry exposed
    # that the old chain is not truly idempotent once those source fragments have
    # evolved. Run V4's refinements directly while suppressing only its first
    # subprocess call back into V3. All V4 source/test transformations retain
    # their own already-applied guards and validation markers.
    v4 = load_v4_module()
    original_run = v4.subprocess.run

    def skip_v3_chain(*args, **kwargs):
        print("[repair] skipping obsolete V1-V3 replay; applying V4 directly to current tree")
        return subprocess.CompletedProcess(args=args[0] if args else None, returncode=0)

    v4.subprocess.run = skip_v3_chain
    try:
        v4.main()
    finally:
        v4.subprocess.run = original_run

    # V5's final correction: the first post-credit scene needs BOTH a structural
    # gap greater than the 10-second button lifetime and more than 10 seconds of
    # live playback remaining. Exactly 10,000 ms is deliberately suppressed.
    post_play = ROOT / "app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerPostPlayModels.kt"
    replace_exact(
        post_play,
        '''        if (index == 0) {\n            val creditsStartMs = timing.creditsStartMs ?: return null\n            return scene.takeIf {\n                positionMs >= creditsStartMs &&\n                    remainingMs > minimumLeadMs\n            }\n        }''',
        '''        if (index == 0) {\n            val creditsStartMs = timing.creditsStartMs ?: return null\n            val structuralGapMs = scene.startMs - creditsStartMs\n            return scene.takeIf {\n                positionMs >= creditsStartMs &&\n                    structuralGapMs > minimumLeadMs &&\n                    remainingMs > minimumLeadMs\n            }\n        }''',
        label="require both structural and live lead time for first post-credit scene",
    )

    # Validate the exact final behaviors that were missing from the APK built on
    # 2026-10-24: live Skip Credits gating and atomic terminal rating ownership.
    ui_state = ROOT / "app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerUiState.kt"
    initialization = ROOT / "app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt"
    credit_rating = ROOT / "app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerCreditRating.kt"

    require(post_play, "val remainingMs = scene.startMs - positionMs", label="live Skip Credits lead-time gate")
    require(post_play, "structuralGapMs > minimumLeadMs &&", label="strict first-scene structural lead-time gate")
    require(post_play, "remainingMs > minimumLeadMs", label="strict live lead-time gate")
    require(post_play, "shouldReserveNaturalEndRatingPrompt", label="provider-independent EOF rating reservation")
    require(ui_state, "naturalEndRatingCheckPending", label="terminal rating ownership state")
    require(initialization, "val reserveNaturalEndRating =", label="atomic STATE_ENDED reservation")
    require(credit_rating, "Terminal EOF rating check:", label="terminal provider recheck and diagnostics")

    print()
    print("V6 repair applied successfully.")
    print("The working tree now contains the intended V4 + V5 refinements without replaying V1-V3.")
    print()
    print("Recommended verification:")
    print("  git diff --check")
    print("  ./gradlew :app:testDebugUnitTest")
    print("  ./gradlew :app:compileDebugKotlin")
    print("  ./gradlew :app:assembleSideload")


if __name__ == "__main__":
    main()
