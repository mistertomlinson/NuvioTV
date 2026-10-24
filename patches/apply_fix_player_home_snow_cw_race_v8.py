#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CHANGED: list[Path] = []


def write_if_changed(path: Path, text: str, *, label: str) -> None:
    old = path.read_text(encoding="utf-8")
    if old == text:
        print(f"[already applied] {label}")
        return
    path.write_text(text, encoding="utf-8")
    if path not in CHANGED:
        CHANGED.append(path)
    print(f"[patched] {label}")


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
    write_if_changed(path, text.replace(old, new, 1), label=label)


def require(path: Path, needle: str, *, label: str) -> None:
    if needle not in path.read_text(encoding="utf-8"):
        raise SystemExit(f"{label}: validation marker missing from {path}")


def main() -> None:
    nav = ROOT / "app/src/main/java/com/nuvio/tv/ui/navigation/NuvioNavHost.kt"
    home = ROOT / "app/src/main/java/com/nuvio/tv/ui/screens/home/HomeScreen.kt"
    modern = ROOT / "app/src/main/java/com/nuvio/tv/ui/screens/home/ModernHomeContent.kt"

    # ------------------------------------------------------------------
    # 1) Player -> Home seasonal reveal.
    #
    # Snow is a separate Android surface above Compose, so Home's opaque
    # player-return curtain cannot hide it. Arm an external alpha=0 BEFORE
    # leaving Player, wait for the first returning Home frame, then either:
    #   - follow Home's existing player-return curtain reveal boundary, or
    #   - reveal immediately if no CW/player curtain is actually pending.
    #
    # Stream/Player still do not keep a snow SurfaceView alive.
    # ------------------------------------------------------------------
    replace_exact(
        home,
        '''    returnFrameSignalActive: Boolean = false,\n    onReturnFrameDrawn: () -> Unit = {},\n    onNavigateToDetail: (String, String, String) -> Unit,''',
        '''    returnFrameSignalActive: Boolean = false,\n    onReturnFrameDrawn: () -> Unit = {},\n    onPlayerReturnCurtainRevealStart: () -> Unit = {},\n    onNavigateToDetail: (String, String, String) -> Unit,''',
        label="expose player-return curtain reveal boundary",
    )

    replace_exact(
        home,
        '''            androidx.compose.runtime.withFrameNanos { }\n            playerReturnCurtainAlpha.animateTo(''',
        '''            androidx.compose.runtime.withFrameNanos { }\n\n            /*\n             * Snow lives in a separate Android surface above this Compose\n             * curtain. Release its external alpha on this exact boundary so\n             * both layers perform the same 350ms Home reveal.\n             */\n            onPlayerReturnCurtainRevealStart()\n\n            playerReturnCurtainAlpha.animateTo(''',
        label="release seasonal surface with player-return curtain",
    )

    replace_exact(
        nav,
        '''    val detailReturnSnowReveal =\n        androidx.compose.runtime.remember {\n            androidx.compose.runtime.mutableStateOf(false)\n        }\n\n    /*\n     * DETAIL_TO_DETAIL_RETURN_FROZEN_LAYER''',
        '''    val detailReturnSnowReveal =\n        androidx.compose.runtime.remember {\n            androidx.compose.runtime.mutableStateOf(false)\n        }\n\n    /*\n     * PLAYER_TO_HOME_SNOW_REVEAL\n     *\n     * Player intentionally tears the seasonal SurfaceView down. On a direct\n     * Player -> Home return, re-created snow must therefore start at alpha=0\n     * BEFORE Home becomes the active route. Home's own player-return curtain\n     * remains authoritative for readiness; this state only mirrors its reveal\n     * onto the independent Android snow surface.\n     */\n    val playerReturnSnowRevealActive =\n        androidx.compose.runtime.remember {\n            androidx.compose.runtime.mutableStateOf(false)\n        }\n\n    val playerReturnSnowRevealAlpha =\n        androidx.compose.runtime.remember {\n            androidx.compose.animation.core.Animatable(0f)\n        }\n\n    val playerReturnSnowRevealStarted =\n        androidx.compose.runtime.remember {\n            java.util.concurrent.atomic.AtomicBoolean(false)\n        }\n\n    val playerReturnSnowGateRunning =\n        androidx.compose.runtime.remember {\n            java.util.concurrent.atomic.AtomicBoolean(false)\n        }\n\n    val playerReturnSnowScope =\n        androidx.compose.runtime.rememberCoroutineScope()\n\n    val playerReturnSnowHomeDrawSignal =\n        androidx.compose.runtime.remember {\n            kotlinx.coroutines.channels.Channel<Unit>(\n                capacity = kotlinx.coroutines.channels.Channel.CONFLATED\n            )\n        }\n\n    val playerReturnSnowAwaitingHomeDraw =\n        androidx.compose.runtime.remember {\n            androidx.compose.runtime.mutableStateOf(false)\n        }\n\n    fun revealPlayerReturnSnow() {\n        if (!playerReturnSnowRevealActive.value) return\n        if (!playerReturnSnowRevealStarted.compareAndSet(false, true)) return\n\n        playerReturnSnowScope.launch {\n            try {\n                playerReturnSnowRevealAlpha.animateTo(\n                    targetValue = 1f,\n                    animationSpec =\n                        androidx.compose.animation.core.tween(\n                            durationMillis = 350\n                        )\n                )\n            } finally {\n                playerReturnSnowAwaitingHomeDraw.value = false\n                playerReturnSnowRevealActive.value = false\n                playerReturnSnowRevealStarted.set(false)\n                playerReturnSnowGateRunning.set(false)\n            }\n        }\n    }\n\n    fun runPlayerReturnHomeSnowGate(\n        shouldWaitForPlayerReturnCurtain: () -> Boolean,\n        navigateHome: () -> Unit\n    ) {\n        if (!playerReturnSnowGateRunning.compareAndSet(false, true)) return\n\n        playerReturnSnowRevealStarted.set(false)\n        playerReturnSnowRevealActive.value = true\n\n        playerReturnSnowScope.launch {\n            try {\n                /*\n                 * The previous reveal may have finished at alpha=1. Reset while\n                 * Player is still the active route, where no seasonal window\n                 * exists, so there is no visible snap.\n                 */\n                playerReturnSnowRevealAlpha.snapTo(0f)\n\n                while (\n                    playerReturnSnowHomeDrawSignal\n                        .tryReceive()\n                        .isSuccess\n                ) {\n                    // Drain a stale one-shot signal from an earlier return.\n                }\n\n                playerReturnSnowAwaitingHomeDraw.value = true\n                navigateHome()\n\n                /*\n                 * Home's outer draw is the earliest safe reveal boundary. The\n                 * timeout is only a failsafe and matches the existing return\n                 * handshakes.\n                 */\n                kotlinx.coroutines.withTimeoutOrNull(250L) {\n                    playerReturnSnowHomeDrawSignal.receive()\n                }\n                playerReturnSnowAwaitingHomeDraw.value = false\n\n                /*\n                 * Re-check AFTER Home has drawn. A CW transaction can settle\n                 * extremely quickly; if no player-return curtain remains, do\n                 * not wait for a callback that will never fire.\n                 */\n                if (!shouldWaitForPlayerReturnCurtain()) {\n                    revealPlayerReturnSnow()\n                }\n            } catch (error: Throwable) {\n                playerReturnSnowAwaitingHomeDraw.value = false\n                playerReturnSnowRevealActive.value = false\n                playerReturnSnowRevealStarted.set(false)\n                playerReturnSnowGateRunning.set(false)\n                throw error\n            }\n        }\n    }\n\n    /*\n     * DETAIL_TO_DETAIL_RETURN_FROZEN_LAYER''',
        label="add direct Player-to-Home seasonal reveal gate",
    )

    replace_exact(
        nav,
        '''    val seasonalOverlayRevealAlphaOverride =\n        if (\n            seasonalOverlayRoute == Screen.Home.route &&\n            showDetailReturnOverlay.value\n        ) {\n            if (detailReturnSnowReveal.value) {\n                (1f - detailReturnOverlayAlpha.value).coerceIn(0f, 1f)\n            } else {\n                0f\n            }\n        } else {\n            null\n        }''',
        '''    val seasonalOverlayRevealAlphaOverride =\n        when {\n            seasonalOverlayRoute == Screen.Home.route &&\n                playerReturnSnowRevealActive.value -> {\n                playerReturnSnowRevealAlpha.value\n                    .coerceIn(0f, 1f)\n            }\n\n            seasonalOverlayRoute == Screen.Home.route &&\n                showDetailReturnOverlay.value -> {\n                if (detailReturnSnowReveal.value) {\n                    (1f - detailReturnOverlayAlpha.value)\n                        .coerceIn(0f, 1f)\n                } else {\n                    0f\n                }\n            }\n\n            else -> null\n        }''',
        label="give Player-to-Home seasonal alpha priority on Home",
    )

    replace_exact(
        nav,
        '''                    returnFrameSignalActive =\n                        detailReturnAwaitingHomeDraw.value ||\n                            sidebarRootReturnAwaitingHomeDraw.value,\n                    onReturnFrameDrawn = {\n                        if (detailReturnAwaitingHomeDraw.value) {\n                            detailReturnHomeDrawSignal.trySend(Unit)\n                        }\n                        if (sidebarRootReturnAwaitingHomeDraw.value) {\n                            sidebarRootReturnHomeDrawSignal.trySend(Unit)\n                        }\n                    },\n                    onNavigateToDetail =''',
        '''                    returnFrameSignalActive =\n                        detailReturnAwaitingHomeDraw.value ||\n                            sidebarRootReturnAwaitingHomeDraw.value ||\n                            playerReturnSnowAwaitingHomeDraw.value,\n                    onReturnFrameDrawn = {\n                        if (detailReturnAwaitingHomeDraw.value) {\n                            detailReturnHomeDrawSignal.trySend(Unit)\n                        }\n                        if (sidebarRootReturnAwaitingHomeDraw.value) {\n                            sidebarRootReturnHomeDrawSignal.trySend(Unit)\n                        }\n                        if (playerReturnSnowAwaitingHomeDraw.value) {\n                            playerReturnSnowHomeDrawSignal.trySend(Unit)\n                        }\n                    },\n                    onPlayerReturnCurtainRevealStart = {\n                        revealPlayerReturnSnow()\n                    },\n                    onNavigateToDetail =''',
        label="wire Home draw and curtain boundaries to seasonal reveal",
    )

    replace_exact(
        nav,
        '''            val homeViewModelForPlayerReturn: HomeViewModel? =\n                homeEntryForPlayerReturn?.let { homeEntry ->\n                    hiltViewModel(homeEntry)\n                }\n\n            PlayerScreen(''',
        '''            val homeViewModelForPlayerReturn: HomeViewModel? =\n                homeEntryForPlayerReturn?.let { homeEntry ->\n                    hiltViewModel(homeEntry)\n                }\n\n            fun shouldWaitForPlayerReturnCurtain(): Boolean {\n                val homeViewModel =\n                    homeViewModelForPlayerReturn\n                        ?: return false\n\n                return\n                    homeViewModel\n                        .playerReturnCwRequestedGenerationState\n                        .value >\n                        homeViewModel\n                            .playerReturnCwSettledGeneration\n                            .value\n            }\n\n            PlayerScreen(''',
        label="derive current Home player-return curtain state",
    )

    replace_exact(
        nav,
        '''                onPostPlayBackPress = {\n                    backStackEntry.savedStateHandle["ratingExit"] = true\n                    val returnedHome = navController.popBackStack(\n                        Screen.Home.route,\n                        inclusive = false\n                    )\n                    if (!returnedHome) {\n                        navController.navigate(Screen.Home.route) {\n                            launchSingleTop = true\n                        }\n                    }\n                },''',
        '''                onPostPlayBackPress = {\n                    backStackEntry.savedStateHandle["ratingExit"] = true\n                    runPlayerReturnHomeSnowGate(\n                        shouldWaitForPlayerReturnCurtain =\n                            ::shouldWaitForPlayerReturnCurtain\n                    ) {\n                        val returnedHome = navController.popBackStack(\n                            Screen.Home.route,\n                            inclusive = false\n                        )\n                        if (!returnedHome) {\n                            navController.navigate(Screen.Home.route) {\n                                launchSingleTop = true\n                            }\n                        }\n                    }\n                },''',
        label="gate More-Like-This back snow until Home reveal",
    )

    replace_exact(
        nav,
        '''                    if (returnToHomeOnBack2) {\n                        navController.popBackStack(Screen.Home.route, inclusive = false)\n                    } else {\n                        navController.popBackStack()\n                    }''',
        '''                    if (returnToHomeOnBack2) {\n                        runPlayerReturnHomeSnowGate(\n                            shouldWaitForPlayerReturnCurtain =\n                                ::shouldWaitForPlayerReturnCurtain\n                        ) {\n                            val returnedHome =\n                                navController.popBackStack(\n                                    Screen.Home.route,\n                                    inclusive = false\n                                )\n                            if (!returnedHome) {\n                                navController.navigate(Screen.Home.route) {\n                                    launchSingleTop = true\n                                }\n                            }\n                        }\n                    } else {\n                        navController.popBackStack()\n                    }''',
        label="gate rating direct-Home snow until Home reveal",
    )

    replace_exact(
        nav,
        '''                                } else {\n                                    navController.popBackStack()\n                                }\n                            }\n                        }\n                    }\n                },\n                onPlaybackEnded =''',
        '''                                } else {\n                                    val previousRouteForHomeReturn =\n                                        navController\n                                            .previousBackStackEntry\n                                            ?.destination\n                                            ?.route\n\n                                    if (\n                                        previousRouteForHomeReturn ==\n                                            Screen.Home.route\n                                    ) {\n                                        runPlayerReturnHomeSnowGate(\n                                            shouldWaitForPlayerReturnCurtain =\n                                                ::shouldWaitForPlayerReturnCurtain\n                                        ) {\n                                            navController.popBackStack()\n                                        }\n                                    } else {\n                                        navController.popBackStack()\n                                    }\n                                }\n                            }\n                        }\n                    }\n                },\n                onPlaybackEnded =''',
        label="gate direct CW movie Player-to-Home snow",
    )

    # ------------------------------------------------------------------
    # 2) Continue Watching return race.
    #
    # During Player -> Home, pendingRowFocus already owns the saved CW restore
    # target, but real Compose focus may not have committed yet. A structural
    # CW update in that gap was incorrectly classified as an inactive-row
    # update and reset the target to index 0. Treat the still-pending restore as
    # ownership until ModernHomeRows confirms focus and clears pendingRowFocus.
    # ------------------------------------------------------------------
    replace_exact(
        modern,
        '''        val continueWatchingOwnsFocus =\n            focusHolder.activeRowKey ==\n                continueWatchingRow.key &&\n                !isCarouselFocused\n\n        val previousFocusedIndex =\n            (\n                if (continueWatchingOwnsFocus) {\n                    focusHolder.activeItemIndex\n                } else {\n                    uiCaches\n                        .lastActuallyFocusedIndexByRow[\n                            continueWatchingRow.key\n                        ]\n                        ?: focusedItemByRow[\n                            continueWatchingRow.key\n                        ]\n                        ?: 0\n                }\n            ).coerceAtLeast(0)\n\n        val targetIndex =\n            if (continueWatchingOwnsFocus) {\n                /*\n                 * CW owns focus:\n                 *\n                 * - a surviving title follows its identity through a reorder\n                 * - a removed title leaves focus on the same numeric slot\n                 */\n                resolveContinueWatchingStructuralFocusIndex(\n                    previousKeys = previousKeys,\n                    currentKeys =\n                        currentContinueWatchingOrderKeys,\n                    previousFocusedIndex =\n                        previousFocusedIndex\n                )\n            } else {\n                /*\n                 * CW is inactive.\n                 *\n                 * A fresh playback can insert a new title at the head while\n                 * the user actually belongs to another Home row. Do not carry\n                 * an old remembered CW title forward in that situation.\n                 *\n                 * Prepare CW at its newest item without requesting focus.\n                 */\n                0\n            }\n\n        uiCaches.lastActuallyFocusedIndexByRow.remove(\n            continueWatchingRow.key\n        )\n        focusedItemByRow[\n            continueWatchingRow.key\n        ] = targetIndex\n\n        rowListStates[\n            continueWatchingRow.key\n        ]?.scrollToItem(targetIndex, 0)\n\n        if (continueWatchingOwnsFocus) {''',
        '''        val continueWatchingOwnsFocus =\n            focusHolder.activeRowKey ==\n                continueWatchingRow.key &&\n                !isCarouselFocused\n\n        /*\n         * Saved CW focus can be in-flight while the platform/carousel still\n         * owns real Compose focus for a frame. pendingRowFocus is cleared only\n         * after ModernHomeRows confirms the requested poster actually focused,\n         * so it is the authoritative transient ownership signal here.\n         */\n        val continueWatchingRestorePending =\n            continueWatchingRestoreTargetIndex != null ||\n                (\n                    savedFocusTargetsContinueWatching &&\n                        pendingRowFocus.key ==\n                            continueWatchingRow.key &&\n                        pendingRowFocus.index != null\n                )\n\n        val continueWatchingOwnsReturnTarget =\n            continueWatchingOwnsFocus ||\n                continueWatchingRestorePending\n\n        val previousFocusedIndex =\n            (\n                when {\n                    continueWatchingRestoreTargetIndex != null ->\n                        focusState.focusedItemIndex\n\n                    continueWatchingRestorePending ->\n                        pendingRowFocus.index\n                            ?: focusState.focusedItemIndex\n\n                    continueWatchingOwnsFocus ->\n                        focusHolder.activeItemIndex\n\n                    else ->\n                        uiCaches\n                            .lastActuallyFocusedIndexByRow[\n                                continueWatchingRow.key\n                            ]\n                            ?: focusedItemByRow[\n                                continueWatchingRow.key\n                            ]\n                            ?: 0\n                }\n            ).coerceAtLeast(0)\n\n        val targetIndex =\n            if (continueWatchingOwnsReturnTarget) {\n                /*\n                 * CW either owns real focus or owns an in-flight saved return:\n                 *\n                 * - a surviving title follows its identity through a reorder\n                 * - a removed title leaves focus on the same numeric slot\n                 */\n                resolveContinueWatchingStructuralFocusIndex(\n                    previousKeys = previousKeys,\n                    currentKeys =\n                        currentContinueWatchingOrderKeys,\n                    previousFocusedIndex =\n                        previousFocusedIndex\n                )\n            } else {\n                /*\n                 * CW is truly inactive.\n                 *\n                 * A fresh playback can insert a new title at the head while\n                 * the user actually belongs to another Home row. Do not carry\n                 * an old remembered CW title forward in that situation.\n                 *\n                 * Prepare CW at its newest item without requesting focus.\n                 */\n                0\n            }\n\n        uiCaches.lastActuallyFocusedIndexByRow.remove(\n            continueWatchingRow.key\n        )\n        focusedItemByRow[\n            continueWatchingRow.key\n        ] = targetIndex\n\n        rowListStates[\n            continueWatchingRow.key\n        ]?.scrollToItem(targetIndex, 0)\n\n        if (continueWatchingOwnsReturnTarget) {''',
        label="preserve pending CW return target through structural updates",
    )

    # Fail-fast semantic markers.
    require(
        nav,
        "PLAYER_TO_HOME_SNOW_REVEAL",
        label="player-home snow gate",
    )
    require(
        nav,
        "playerReturnSnowRevealAlpha.value",
        label="player-home seasonal alpha override",
    )
    require(
        home,
        "onPlayerReturnCurtainRevealStart()",
        label="Home curtain seasonal release callback",
    )
    require(
        modern,
        "continueWatchingOwnsReturnTarget",
        label="pending CW return ownership",
    )

    print("\nV8 refinement applied successfully to:")
    for path in CHANGED:
        print(f"  - {path.relative_to(ROOT)}")

    print("\nRecommended verification:")
    print("  git diff --check")
    print("  ./gradlew :app:testDebugUnitTest")
    print("  ./gradlew :app:compileDebugKotlin")
    print("  ./gradlew :app:assembleSideload")


if __name__ == "__main__":
    main()
