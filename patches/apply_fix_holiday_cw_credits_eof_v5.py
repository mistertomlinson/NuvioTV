#!/usr/bin/env python3
from __future__ import annotations

import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
V4_PATCH = ROOT / "patches/apply_fix_holiday_cw_credits_eof_v4.py"


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
    subprocess.run([sys.executable, str(V4_PATCH)], cwd=ROOT, check=True)

    post_play = ROOT / "app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerPostPlayModels.kt"

    replace_exact(
        post_play,
        '''        if (index == 0) {\n            val creditsStartMs = timing.creditsStartMs ?: return null\n            return scene.takeIf {\n                positionMs >= creditsStartMs &&\n                    remainingMs > minimumLeadMs\n            }\n        }''',
        '''        if (index == 0) {\n            val creditsStartMs = timing.creditsStartMs ?: return null\n            val structuralGapMs = scene.startMs - creditsStartMs\n            return scene.takeIf {\n                positionMs >= creditsStartMs &&\n                    structuralGapMs > minimumLeadMs &&\n                    remainingMs > minimumLeadMs\n            }\n        }''',
        label="require both structural and live lead time for first post-credit scene",
    )

    require(
        post_play,
        "structuralGapMs > minimumLeadMs &&",
        label="strict first-scene structural 10-second gate",
    )
    require(
        post_play,
        "remainingMs > minimumLeadMs",
        label="strict live 10-second gate",
    )

    print()
    print("V5 refinement applied successfully.")
    print()
    print("Recommended verification:")
    print("  git diff --check")
    print("  ./gradlew :app:testDebugUnitTest")
    print("  ./gradlew :app:compileDebugKotlin")
    print("  ./gradlew :app:assembleSideload")


if __name__ == "__main__":
    main()
