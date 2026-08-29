# Android Core Translation UI Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebuild the Android core translation experience around the approved minimalist, left/right-ear conversation UI while preserving existing translation, Cloud session, and audio behavior.

**Architecture:** Keep ViewModels, coordinators and protocol classes as behavior authorities. Add pure UI mappers for testable presentation decisions, then compose focused screens from those models. `MainActivity` becomes a thin state/navigation/permission host; feature composables own no translation lifecycle state.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, Lifecycle Compose, existing Material icons, Android Keystore/Cloud clients, JVM unit tests, Gradle lint and Debug assembly.

**Spec:** `docs/superpowers/specs/2026-08-29-android-core-translation-ui-redesign-design.md`

## Global Constraints

- Modify only Android UI/navigation and unit tests; do not alter Cloud API, Agent, JWT, PCM, database or token storage behavior.
- Default user destination is Face-to-Face; primary bottom navigation is exactly `面对面 / 同声传译 / 我`.
- Reuse `FaceToFaceViewModel` methods: manual press/release and auto `startAuto`, `pressRightAuto`, `releaseRightAuto`, pause/resume/stop.
- Continuous mode semantics are fixed: left ear captures continuously; right press temporarily switches right; right release returns left.
- A microphone shows an indigo ripple only while its side is actively capturing; respect Android reduce-motion settings with a static ring fallback.
- No production emoji icons. All actionable icons have Chinese content descriptions and at least 48dp touch targets.
- Never render token, password, API key, DSN, session id, raw exception or endpoint details in a consumer screen.
- Use `JAVA_HOME=$HOME/.local/share/verba-android-tools/jdk17` and `ANDROID_SDK_ROOT=$HOME/.local/share/verba-android-tools/android-sdk` for all Android validation.
- APKs and Gradle/Kotlin/build caches must remain untracked.

---

## File Structure

| Path | Responsibility |
|---|---|
| `android/app/src/main/java/com/verba/interpretation/ui/design/VerbaDesignTokens.kt` | Static color, spacing, shape and accessible ripple primitives. |
| `android/app/src/main/java/com/verba/interpretation/ui/navigation/ProductBottomBar.kt` | Three-destination icon-plus-text bottom bar. |
| `android/app/src/main/java/com/verba/interpretation/ui/facetoface/FaceToFaceUiMapper.kt` | Pure mapping from `FaceToFaceState`/turns to display/interaction model. |
| `android/app/src/main/java/com/verba/interpretation/ui/facetoface/ConversationTimeline.kt` | Left/right timeline bubbles and safe bottom content padding. |
| `android/app/src/main/java/com/verba/interpretation/ui/facetoface/EarMicControls.kt` | Fixed ear microphones, press/release callbacks and capture ripple. |
| `android/app/src/main/java/com/verba/interpretation/ui/facetoface/FaceToFaceOverflowMenu.kt` | Manual/continuous selection and pause/resume/stop actions. |
| `android/app/src/main/java/com/verba/interpretation/ui/facetoface/FaceToFaceScreen.kt` | Feature screen composition, language chips and existing ViewModel calls. |
| `android/app/src/main/java/com/verba/interpretation/ui/interpretation/InterpretationUiMapper.kt` | Pure simultaneous-mode action/state mapping. |
| `android/app/src/main/java/com/verba/interpretation/ui/interpretation/InterpretationScreen.kt` | Single-task simultaneous translation screen. |
| `android/app/src/main/java/com/verba/interpretation/ui/account/AccountScreen.kt` | Safe account/entitlement presentation and secondary routes. |
| `android/app/src/main/java/com/verba/interpretation/MainActivity.kt` | Thin app host, permission launchers, ViewModel injection, navigation and secondary pages. |
| `android/app/src/main/java/com/verba/interpretation/ui/ProductNavigation.kt` | Three-primary-destination navigation policy and Face-to-Face default. |

## Task 1: Lock the Navigation and Presentation Policies

**Files:**
- Modify: `android/app/src/main/java/com/verba/interpretation/ui/ProductNavigation.kt`
- Create: `android/app/src/main/java/com/verba/interpretation/ui/facetoface/FaceToFaceUiMapper.kt`
- Test: `android/app/src/test/java/com/verba/interpretation/ui/ProductNavigationPolicyTest.kt`
- Create: `android/app/src/test/java/com/verba/interpretation/ui/facetoface/FaceToFaceUiMapperTest.kt`

**Consumes:** `ProductNavigationMode`, `ProductDestination`, `ProductScreen`, `FaceToFaceState`, `FaceToFaceMode`, `FaceToFacePhase`, `FaceToFaceSide`, `FaceToFaceTurn`.

**Produces:** `ProductNavigationPolicy` with Face-to-Face default and `FaceToFacePresentation` with `activeMic`, `listeningLabel`, `canChangeLanguages`, `isContinuous`, `turnAlignment`.

- [ ] **Step 1: Write failing policy tests**

```kotlin
@Test fun userNavigationDefaultsToFaceToFaceWithThreeDestinations() {
    assertEquals(ProductScreen.FACE_TO_FACE_WORKBENCH, ProductNavigationPolicy.initialScreen(ProductNavigationMode.USER))
    assertEquals(
        listOf(ProductDestination.FACE_TO_FACE, ProductDestination.INTERPRETATION, ProductDestination.PROFILE),
        ProductNavigationPolicy.destinationsFor(ProductNavigationMode.USER),
    )
}

@Test fun continuousRightPressAndReleaseMapToExpectedActiveMic() {
    val base = FaceToFaceState(mode = FaceToFaceMode.AUTO, phase = FaceToFacePhase.LISTENING, captureActive = true)
    assertEquals(FaceToFaceSide.LEFT, faceToFacePresentation(base.copy(activeSide = FaceToFaceSide.LEFT)).activeMic)
    assertEquals(FaceToFaceSide.RIGHT, faceToFacePresentation(base.copy(activeSide = FaceToFaceSide.RIGHT)).activeMic)
}
```

- [ ] **Step 2: Run the focused tests and verify failure**

Run:
```bash
./gradlew --no-daemon testDebugUnitTest --tests 'com.verba.interpretation.ui.ProductNavigationPolicyTest' --tests 'com.verba.interpretation.ui.facetoface.FaceToFaceUiMapperTest'
```
Expected: FAIL because default destinations and mapper do not exist.

- [ ] **Step 3: Implement pure navigation and presentation mapping**

- Limit USER destinations to `FACE_TO_FACE`, `INTERPRETATION`, `PROFILE`.
- Return `FACE_TO_FACE_WORKBENCH` for USER initial screen and exit target.
- Implement mapper without Compose dependencies. `activeMic` is non-null only when `phase == LISTENING && captureActive`; `listeningLabel` returns `听取中…` for `zh`, `Listening…` otherwise; `canChangeLanguages` is only true in idle with no active capture.
- Preserve existing admin and authentication policy behavior.

- [ ] **Step 4: Expand mapper tests for edge cases**

```kotlin
@Test fun manualProcessingHasNoRippleAndLocksLanguageChanges() { /* PROCESSING expectation */ }
@Test fun turnAlignmentAndPlaybackRouteFollowTurnSide() { /* LEFT=start, RIGHT=end */ }
@Test fun pausedContinuousModeHasNoRipple() { /* PAUSED expectation */ }
```

- [ ] **Step 5: Run focused tests and commit**

Run the command in Step 2. Expected: PASS.

```bash
git add android/app/src/main/java/com/verba/interpretation/ui/ProductNavigation.kt \
  android/app/src/main/java/com/verba/interpretation/ui/facetoface/FaceToFaceUiMapper.kt \
  android/app/src/test/java/com/verba/interpretation/ui/ProductNavigationPolicyTest.kt \
  android/app/src/test/java/com/verba/interpretation/ui/facetoface/FaceToFaceUiMapperTest.kt
git commit -m "feat(android): define core translation navigation"
```

## Task 2: Create the Shared Visual System and Bottom Navigation

**Files:**
- Create: `android/app/src/main/java/com/verba/interpretation/ui/design/VerbaDesignTokens.kt`
- Create: `android/app/src/main/java/com/verba/interpretation/ui/navigation/ProductBottomBar.kt`
- Test: `android/app/src/test/java/com/verba/interpretation/ui/ProductNavigationPolicyTest.kt`

**Consumes:** `ProductDestination`, `ProductNavigationPolicy`.

**Produces:** `VerbaColors`, `VerbaShapes`, `ProductBottomBar(selected, onSelect)`.

- [ ] **Step 1: Add a failing navigation test that excludes unsupported primary destinations**

```kotlin
@Test fun userPrimaryDestinationsDoNotExposeCameraOrHistory() {
    val destinations = ProductNavigationPolicy.destinationsFor(ProductNavigationMode.USER)
    assertFalse(ProductDestination.TRANSLATE in destinations)
    assertFalse(ProductDestination.HISTORY in destinations)
}
```

- [ ] **Step 2: Run the focused navigation test and verify failure**

Run:
```bash
./gradlew --no-daemon testDebugUnitTest --tests 'com.verba.interpretation.ui.ProductNavigationPolicyTest'
```
Expected: FAIL until Task 1 navigation policy is complete; if already green, record it as covered and continue.

- [ ] **Step 3: Implement visual tokens and bar**

- Define `Background = Color(0xFFF6F7FB)`, `Ink = Color(0xFF171923)`, `Muted = Color(0xFF747784)`, `Brand = Color(0xFF5B6CFF)`, `BrandSoft = Color(0xFFEEF0FF)`, `Danger = Color(0xFFC95B63)`.
- Define 4/8dp spacing units, 18/24/30dp rounded shapes, 48dp minimum touch target.
- `ProductBottomBar` must render icon plus Chinese text, select state with `BrandSoft`, and use Material outline icon combinations appropriate for face-to-face, headset interpretation and profile. Every icon uses a non-null Chinese content description.

- [ ] **Step 4: Run unit tests and compile Debug Kotlin**

Run:
```bash
./gradlew --no-daemon testDebugUnitTest
```
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/verba/interpretation/ui/design/VerbaDesignTokens.kt \
  android/app/src/main/java/com/verba/interpretation/ui/navigation/ProductBottomBar.kt \
  android/app/src/test/java/com/verba/interpretation/ui/ProductNavigationPolicyTest.kt
git commit -m "feat(android): add core translation design system"
```

## Task 3: Build the Face-to-Face Conversation Surface

**Files:**
- Create: `android/app/src/main/java/com/verba/interpretation/ui/facetoface/ConversationTimeline.kt`
- Create: `android/app/src/main/java/com/verba/interpretation/ui/facetoface/EarMicControls.kt`
- Create: `android/app/src/main/java/com/verba/interpretation/ui/facetoface/FaceToFaceOverflowMenu.kt`
- Create: `android/app/src/main/java/com/verba/interpretation/ui/facetoface/FaceToFaceScreen.kt`
- Modify: `android/app/src/main/java/com/verba/interpretation/MainActivity.kt`
- Test: `android/app/src/test/java/com/verba/interpretation/ui/facetoface/FaceToFaceUiMapperTest.kt`

**Consumes:** `FaceToFaceViewModel`, `FaceToFaceState`, `FaceToFacePresentation`, existing microphone permission launcher and `FaceToFaceTurn` data.

**Produces:** `FaceToFaceScreen(...)` that renders a safe timeline and invokes only existing ViewModel lifecycle methods.

- [ ] **Step 1: Write failing mapper tests for timeline and microphone behavior**

```kotlin
@Test fun leftTurnsMapToStartAndRightTurnsMapToEnd() { /* assert alignment */ }
@Test fun manualListeningShowsOnlyActiveSideRippleAndLocalizedPlaceholder() { /* zh/en */ }
@Test fun autoRightPressMapsRippleRightAndReleaseMapsItBackLeft() { /* active side snapshots */ }
```

- [ ] **Step 2: Run focused mapper tests and verify failure**

Run:
```bash
./gradlew --no-daemon testDebugUnitTest --tests 'com.verba.interpretation.ui.facetoface.FaceToFaceUiMapperTest'
```
Expected: FAIL for missing timeline/placeholder mapper data.

- [ ] **Step 3: Implement the Compose surface**

- Use `LazyColumn` for the timeline; left turns align start on white bubbles, right turns align end on `BrandSoft` bubbles.
- Each bubble renders source language, source text, a divider, and indigo translation. Per-turn replay is explicitly deferred: the current behavior authority exposes no controlled replay API, so render no play action and do not alter ViewModel, Coordinator or the audio pipeline.
- Set `contentPadding.bottom` to cover both the fixed microphone control lane and bottom navigation; use `LazyListState` plus existing `ChatFollowPolicy` to follow active/new turns without forcing scroll while the user reads history.
- Render compact left/right language chips. Open selection only if `presentation.canChangeLanguages`; use existing supported language lists and `viewModel.setLanguages`.
- `EarMicControls` uses `pointerInput`/press lifecycle so manual mode calls `manualPress(left/right)` on press and `manualRelease()` on release/cancel. In auto mode: left control displays the continuous active state; right calls `pressRightAuto()` on press and `releaseRightAuto()` on release/cancel.
- Draw animated indigo rings around only `presentation.activeMic`; use a static ring when system animation scale disables animation.
- Overflow menu selects `MANUAL` / `AUTO` only while idle and exposes existing start/pause/resume/stop calls. Do not add a second auto state machine.
- Preserve `FaceToFaceWorkbench` permission handling and lifecycle cancellation while replacing its visual layout with `FaceToFaceScreen`.

- [ ] **Step 4: Run mapper tests and Android unit tests**

Run:
```bash
./gradlew --no-daemon testDebugUnitTest
```
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/verba/interpretation/ui/facetoface \
  android/app/src/main/java/com/verba/interpretation/MainActivity.kt \
  android/app/src/test/java/com/verba/interpretation/ui/facetoface/FaceToFaceUiMapperTest.kt
git commit -m "feat(android): redesign face to face conversation"
```

## Task 4: Rebuild Simultaneous Interpretation and Account Screens

**Files:**
- Create: `android/app/src/main/java/com/verba/interpretation/ui/interpretation/InterpretationUiMapper.kt`
- Create: `android/app/src/main/java/com/verba/interpretation/ui/interpretation/InterpretationScreen.kt`
- Create: `android/app/src/main/java/com/verba/interpretation/ui/account/AccountScreen.kt`
- Modify: `android/app/src/main/java/com/verba/interpretation/MainActivity.kt`
- Test: `android/app/src/test/java/com/verba/interpretation/ui/interpretation/InterpretationUiMapperTest.kt`
- Test: `android/app/src/test/java/com/verba/interpretation/ui/AccountUiStateTest.kt`

**Consumes:** `InterpretationUiState`, `SessionPhase`, `InterpretationViewModel`, `AccountUiState`, `AccountViewModel`.

**Produces:** Minimal single-task simultaneous screen and safe account summary surface.

- [ ] **Step 1: Write failing pure mapper tests**

```kotlin
@Test fun simultaneousRunningShowsRippleAndPauseFinishActions() { /* RUNNING model */ }
@Test fun simultaneousErrorExposesSafeMessageOnly() { /* no raw exception */ }
@Test fun accountSummaryDoesNotContainSensitiveTransportTerms() { /* token, dsn, key */ }
```

- [ ] **Step 2: Run focused tests and verify failure**

Run:
```bash
./gradlew --no-daemon testDebugUnitTest --tests 'com.verba.interpretation.ui.interpretation.InterpretationUiMapperTest'
```
Expected: FAIL because mappers do not exist.

- [ ] **Step 3: Implement the two screens**

- `InterpretationScreen` renders language direction, latest source/translation pair, microphone ripple while running, and actions permitted by `SessionPhase`; call existing start/pause/resume/finish/reset methods.
- Map error states to safe fixed user copy; do not pass throwable text to Compose.
- `AccountScreen` receives only `AccountUiState` and callbacks. Show role-safe login/trial summary and secondary rows for history, service settings, help and logout. Do not display email if the design review requires an anonymous account summary; never display secrets.
- Move old visual code out of `MainActivity` only after feature screen behavior compiles.

- [ ] **Step 4: Run all JVM tests**

Run:
```bash
./gradlew --no-daemon testDebugUnitTest
```
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/verba/interpretation/ui/interpretation \
  android/app/src/main/java/com/verba/interpretation/ui/account \
  android/app/src/main/java/com/verba/interpretation/MainActivity.kt \
  android/app/src/test/java/com/verba/interpretation/ui/interpretation/InterpretationUiMapperTest.kt \
  android/app/src/test/java/com/verba/interpretation/ui/AccountUiStateTest.kt
git commit -m "feat(android): streamline interpretation and account screens"
```

## Task 5: Thin the App Host, Validate Accessibility and Produce Debug APK

**Files:**
- Modify: `android/app/src/main/java/com/verba/interpretation/MainActivity.kt`
- Modify: `android/app/src/main/java/com/verba/interpretation/brand/BrandTheme.kt` if token bridge is required
- Test: existing Android JVM test suite
- Create: `docs/reviews/android-core-translation-ui-redesign-acceptance.md`

**Consumes:** Tasks 1–4 screens, navigation policy, existing endpoint/account secondary screens.

**Produces:** Thin host composition, validated Debug APK and a token-safe visual/manual acceptance checklist.

- [ ] **Step 1: Write/adjust a failing navigation regression test**

```kotlin
@Test fun accountSecondaryScreensReturnToProfilePrimaryDestination() {
    assertEquals(ProductScreen.PROFILE, ProductNavigationPolicy.exitTarget(ProductScreen.ACCOUNT))
    assertEquals(ProductScreen.PROFILE, ProductNavigationPolicy.exitTarget(ProductScreen.ENDPOINT_SETTINGS))
}
```

- [ ] **Step 2: Run focused test and verify expected state**

Run:
```bash
./gradlew --no-daemon testDebugUnitTest --tests 'com.verba.interpretation.ui.ProductNavigationPolicyTest'
```
Expected: PASS only after policy and host routing are coherent.

- [ ] **Step 3: Complete host cleanup and acceptance document**

- Make `InterpretationApp` render the new three primary screens directly inside one Scaffold with `ProductBottomBar`.
- Keep account/auth/admin settings routes reachable; no fake history/camera primary route remains.
- Delete unused old private Compose functions/imports only after replacement has compiled.
- Create a token-safe manual checklist covering: default Face-to-Face, manual left/right hold, ripple transfer, continuous right press/release return to left, bottom safe area, language selection idle lock, simultaneous controls, account safety, landscape, large fonts and TalkBack labels.

- [ ] **Step 4: Run final verification and artifact exclusion checks**

Run:
```bash
export JAVA_HOME="$HOME/.local/share/verba-android-tools/jdk17"
export ANDROID_SDK_ROOT="$HOME/.local/share/verba-android-tools/android-sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_SDK_ROOT/platform-tools:$PATH"
cd android
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
cd ..
git diff --check
git ls-files | grep -E '(^|/)(\.gradle/|\.kotlin/|app/build/|.*\.apk$)' && exit 1 || true
```
Expected: `BUILD SUCCESSFUL`; Debug APK exists at `android/app/build/outputs/apk/debug/app-debug.apk`; no generated artifact is tracked.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/verba/interpretation/MainActivity.kt \
  android/app/src/main/java/com/verba/interpretation/brand/BrandTheme.kt \
  docs/reviews/android-core-translation-ui-redesign-acceptance.md
git commit -m "feat(android): complete core translation ui redesign"
```

## Plan Self-Review

- **Spec coverage:** Tasks 1–2 implement the three-tab IA and visual language. Task 3 covers the approved chat-flow, safe zone, manual and continuous semantics/ripples; per-turn replay is explicitly deferred because the behavior authority has no controlled replay API. Task 4 covers simultaneous and account surfaces. Task 5 covers host cleanup, accessibility-oriented manual checks, build and artifact hygiene.
- **No hidden behavior change:** Continuous mode uses existing `pressRightAuto()` / `releaseRightAuto()` and route behavior; no Cloud/Agent/audio layer task exists.
- **Testability:** Presentation decisions are pure JVM mapper tests; existing coordinators retain behavioral tests. Device-only visual, TalkBack, rotation and audio checks are explicit manual acceptance requirements.
- **Consistency:** All task references use the declared file structure and names; no placeholders remain.
