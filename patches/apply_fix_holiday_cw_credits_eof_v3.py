#!/usr/bin/env python3
from __future__ import annotations

import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
V2_PATCH = ROOT / "patches/apply_fix_holiday_cw_credits_eof_v2.py"
CHANGED: list[Path] = []


def replace_exact(path: Path, old: str, new: str, *, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count == 0 and new in text:
        print(f"[already applied] {label}")
        return
    if count != 1:
        raise SystemExit(
            f"{label}: expected exactly one original fragment in {path}, found {count}"
        )
    path.write_text(text.replace(old, new, 1), encoding="utf-8")
    if path not in CHANGED:
        CHANGED.append(path)
    print(f"[patched] {label}")


def insert_before_last(path: Path, marker: str, addition: str, *, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    if addition in text:
        print(f"[already applied] {label}")
        return
    index = text.rfind(marker)
    if index < 0:
        raise SystemExit(f"{label}: final marker not found in {path}")
    path.write_text(text[:index] + addition + text[index:], encoding="utf-8")
    if path not in CHANGED:
        CHANGED.append(path)
    print(f"[patched] {label}")


def require(path: Path, needle: str, *, label: str) -> None:
    if needle not in path.read_text(encoding="utf-8"):
        raise SystemExit(f"{label}: validation marker missing from {path}")


def main() -> None:
    # V2 applies the snowbank, CW, Skip Credits, EOF-rating, and post-play
    # handoff fixes. This V3 refinement then corrects how a legitimate analyzer
    # result is classified when a post-credit scene itself reaches media EOF and
    # therefore there is no separate final-credits segment/timestamp.
    subprocess.run([sys.executable, str(V2_PATCH)], cwd=ROOT, check=True)

    credit_timing = ROOT / "app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerCreditTiming.kt"
    post_play = ROOT / "app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerPostPlayModels.kt"
    timing_test = ROOT / "app/src/test/java/com/nuvio/tv/ui/screens/player/CreditTimingDecisionTest.kt"
    post_play_test = ROOT / "app/src/test/java/com/nuvio/tv/ui/screens/player/PlayerPostPlayModelsTest.kt"

    replace_exact(
        credit_timing,
        '''private const val CREDIT_JOB_POLL_INTERVAL_MS = 5_000L\nprivate const val MOVIE_FALLBACK_THRESHOLD = 0.95\n\ninternal fun PlayerRuntimeController.evaluateCreditTiming(''',
        '''private const val CREDIT_JOB_POLL_INTERVAL_MS = 5_000L\nprivate const val MOVIE_FALLBACK_THRESHOLD = 0.95\n\n/**\n * A completed analyzer result does not require a final-credits timestamp.\n *\n * Some titles end inside their final post-credit scene, so there is no later\n * block of final credits to timestamp. Such a result is still exact analyzer\n * data when it contains another usable timing signal.\n */\ninternal fun hasUsableCompletedCreditAnalysisTiming(\n    creditsStartMs: Long?,\n    finalCreditsStartMs: Long?,\n    postCreditSceneCount: Int\n): Boolean =\n    creditsStartMs?.takeIf { it >= 0L } != null ||\n        finalCreditsStartMs?.takeIf { it >= 0L } != null ||\n        postCreditSceneCount > 0\n\ninternal fun PlayerRuntimeController.evaluateCreditTiming(''',
        label="define valid complete analyzer result without final credits",
    )

    replace_exact(
        credit_timing,
        '''private fun PlayerRuntimeController.applyCreditAnalyzerResult(\n    job: CreditAnalyzerJobResponse,\n    expectedIdentity: String\n) {\n    if (creditAnalysisIdentity != expectedIdentity) return\n    val result = job.result\n    if (job.status != "complete" || result?.detected != true || result.finalCreditsStartMs == null) {\n        if (!job.error.isNullOrBlank()) {\n            Log.w(PlayerRuntimeController.TAG, "Credit analyzer failed: ${job.error}")\n        }\n        useCreditTimingFallback()\n        return\n    }\n    val postCreditScenes = result.postCreditScenes\n        .asSequence()\n        .filter { it.startMs >= 0L && it.endMs > it.startMs }\n        .sortedBy { it.startMs }\n        .map { scene ->\n            PostCreditSceneTiming(\n                startMs = scene.startMs,\n                endMs = scene.endMs\n            )\n        }\n        .toList()\n\n    _uiState.update {\n        it.copy(\n            creditTiming = CreditTimingUiState(\n                status = CreditTimingStatus.COMPLETE,\n                creditsStartMs = result.creditsStartMs,\n                finalCreditsStartMs = result.finalCreditsStartMs,\n                hasPostCreditScenes = postCreditScenes.isNotEmpty(),\n                postCreditScenes = postCreditScenes,\n                confidence = result.confidence\n            )\n        )\n    }''',
        '''private fun PlayerRuntimeController.applyCreditAnalyzerResult(\n    job: CreditAnalyzerJobResponse,\n    expectedIdentity: String\n) {\n    if (creditAnalysisIdentity != expectedIdentity) return\n    val result = job.result\n    if (job.status != "complete" || result?.detected != true) {\n        if (!job.error.isNullOrBlank()) {\n            Log.w(PlayerRuntimeController.TAG, "Credit analyzer failed: ${job.error}")\n        }\n        useCreditTimingFallback()\n        return\n    }\n\n    val postCreditScenes = result.postCreditScenes\n        .asSequence()\n        .filter { it.startMs >= 0L && it.endMs > it.startMs }\n        .sortedBy { it.startMs }\n        .map { scene ->\n            PostCreditSceneTiming(\n                startMs = scene.startMs,\n                endMs = scene.endMs\n            )\n        }\n        .toList()\n\n    val creditsStartMs = result.creditsStartMs?.takeIf { it >= 0L }\n    val finalCreditsStartMs = result.finalCreditsStartMs?.takeIf { it >= 0L }\n\n    if (!hasUsableCompletedCreditAnalysisTiming(\n            creditsStartMs = creditsStartMs,\n            finalCreditsStartMs = finalCreditsStartMs,\n            postCreditSceneCount = postCreditScenes.size\n        )\n    ) {\n        Log.w(\n            PlayerRuntimeController.TAG,\n            "Credit analyzer completed without usable timing; using fallback"\n        )\n        useCreditTimingFallback()\n        return\n    }\n\n    Log.i(\n        PlayerRuntimeController.TAG,\n        "Credit analyzer exact result ready: " +\n            "creditsStartMs=$creditsStartMs, " +\n            "finalCreditsStartMs=$finalCreditsStartMs, " +\n            "postCreditScenes=${postCreditScenes.size}"\n    )\n\n    _uiState.update {\n        it.copy(\n            creditTiming = CreditTimingUiState(\n                status = CreditTimingStatus.COMPLETE,\n                creditsStartMs = creditsStartMs,\n                finalCreditsStartMs = finalCreditsStartMs,\n                hasPostCreditScenes = postCreditScenes.isNotEmpty(),\n                postCreditScenes = postCreditScenes,\n                confidence = result.confidence\n            )\n        )\n    }''',
        label="accept exact post-credit-to-EOF result with no final-credit timestamp",
    )

    replace_exact(
        post_play,
        '''    val isAuthoritative: Boolean\n        get() = status == CreditTimingStatus.COMPLETE && finalCreditsStartMs != null''',
        '''    val isAuthoritative: Boolean\n        get() =\n            status == CreditTimingStatus.COMPLETE &&\n                (\n                    creditsStartMs != null ||\n                        finalCreditsStartMs != null ||\n                        postCreditScenes.isNotEmpty()\n                    )''',
        label="treat complete scene timing as authoritative without final credits",
    )

    insert_before_last(
        timing_test,
        "\n}\n",
        '''\n    @Test\n    fun `complete analyzer accepts post credit scene reaching eof without final credits`() {\n        assertTrue(\n            hasUsableCompletedCreditAnalysisTiming(\n                creditsStartMs = 100_000L,\n                finalCreditsStartMs = null,\n                postCreditSceneCount = 1\n            )\n        )\n\n        val timing = CreditTimingUiState(\n            status = CreditTimingStatus.COMPLETE,\n            creditsStartMs = 100_000L,\n            finalCreditsStartMs = null,\n            hasPostCreditScenes = true,\n            postCreditScenes = listOf(\n                PostCreditSceneTiming(\n                    startMs = 140_000L,\n                    endMs = 180_000L\n                )\n            )\n        )\n\n        assertTrue(timing.isAuthoritative)\n    }\n\n    @Test\n    fun `complete analyzer rejects detected payload with no usable timing`() {\n        assertFalse(\n            hasUsableCompletedCreditAnalysisTiming(\n                creditsStartMs = null,\n                finalCreditsStartMs = null,\n                postCreditSceneCount = 0\n            )\n        )\n    }\n''',
        label="cover completed post-credit-to-EOF analyzer result",
    )

    # V2's test name described only the missing-timestamp behavior. Keep its
    # semantics but make the regression explicitly cover a COMPLETE analyzer
    # result whose post-credit scene reaches physical EOF.
    replace_exact(
        post_play_test,
        '''    fun `terminal eof offers rating when analyzer never supplied final credits`() {\n        val state = PlayerUiState(\n            playbackEnded = true,\n            contentType = "movie",\n            isRatingProviderConnected = true,\n            creditTiming = CreditTimingUiState(\n                status = CreditTimingStatus.RUNNING,\n                finalCreditsStartMs = null\n            )\n        )''',
        '''    fun `terminal eof offers rating after complete post credit scene with no final credits`() {\n        val state = PlayerUiState(\n            playbackEnded = true,\n            contentType = "movie",\n            isRatingProviderConnected = true,\n            creditTiming = CreditTimingUiState(\n                status = CreditTimingStatus.COMPLETE,\n                creditsStartMs = 100_000L,\n                finalCreditsStartMs = null,\n                hasPostCreditScenes = true,\n                postCreditScenes = listOf(\n                    PostCreditSceneTiming(\n                        startMs = 140_000L,\n                        endMs = 180_000L\n                    )\n                )\n            )\n        )''',
        label="test EOF rating for successful analyzer with no final-credit segment",
    )

    require(
        credit_timing,
        "Credit analyzer exact result ready:",
        label="exact analyzer result logging",
    )
    require(
        credit_timing,
        "postCreditSceneCount = postCreditScenes.size",
        label="completed result usability check",
    )
    require(
        post_play,
        "postCreditScenes.isNotEmpty()",
        label="authoritative complete scene timing",
    )
    require(
        timing_test,
        "complete analyzer accepts post credit scene reaching eof without final credits",
        label="complete-result regression test",
    )
    require(
        post_play_test,
        "terminal eof offers rating after complete post credit scene with no final credits",
        label="EOF rating regression test",
    )

    print()
    if CHANGED:
        print("V3 analyzer/EOF refinement applied successfully to:")
        for path in CHANGED:
            print(f"  - {path.relative_to(ROOT)}")
    else:
        print("V3 analyzer/EOF refinement already applied; no additional files changed.")

    print()
    print("Recommended verification:")
    print("  git diff --check")
    print("  ./gradlew :app:testDebugUnitTest")
    print("  ./gradlew :app:compileDebugKotlin")
    print("  ./gradlew :app:assembleDebug")


if __name__ == "__main__":
    main()
