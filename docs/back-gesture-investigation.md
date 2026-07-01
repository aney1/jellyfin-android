# Android Back Gesture Investigation — Player Screen

**Branch:** `feature/media-segments-on-2.6.4`  
**Status:** Unresolved — investigation parked for later

## Problem

When the native video player is open and the player controls overlay is **hidden**, swiping from
the **left edge in the middle vertical area** of the screen shows the player controls instead of
triggering the Android system back gesture. Swiping from the **top-left** and **bottom-left** of
the screen always worked correctly.

Swiping from the **right edge** also triggers back navigation correctly.

The user wants the standard Android built-in back gesture (edge swipe → navigate back) to work
from the left edge at any vertical position, with no player controls appearing as a side effect.

---

## What Was Tried

### Attempt 1 — Clear ExoPlayer DefaultTimeBar system gesture exclusion rects

**Hypothesis:** ExoPlayer's `DefaultTimeBar.onLayout()` calls
`setSystemGestureExclusionRects(Rect(0, 0, width, height))` on itself. If that rect covered the
left screen edge, the OS would skip back-gesture detection there and deliver the touch to the app
instead, which then shows the player controls via `onSingleTapConfirmed`.

**Implementation:** In `PlayerFragment.onViewCreated`, after the player is set up, attach a
`ViewTreeObserver.OnGlobalLayoutListener` to the `DefaultTimeBar` (`playerControlsBinding.exoProgress`)
that calls `ViewCompat.setSystemGestureExclusionRects(timeBar, emptyList())` after every layout
pass, overriding ExoPlayer's rects.

**Why it didn't work:** The `DefaultTimeBar` in our custom `exo_player_control_view.xml` is
constrained `start_toEndOf="exo_position"`, so its left edge starts roughly 60–100 dp from the
screen edge — well outside Android's typical 20 dp back-gesture zone. Its exclusion rect never
covers x = 0–20 dp, so it was never the cause.

Additionally, when the player controls overlay is **GONE** (the normal state while watching video),
the `DefaultTimeBar` is not laid out at all and registers no exclusion rects regardless.

**Commits:** introduced in `bc4477d0`, reverted in `958a3b0`.

---

### Attempt 2 — Suppress gesture detector for left-edge touches

**Hypothesis:** The OS cannot claim the touch as a back gesture (possibly a Samsung Edge Panel
intercept that falls through, or an OEM-specific gesture zone reservation), so the full touch
reaches the app. The gesture is short enough that `GestureDetector` classifies it as a single tap,
`onSingleTapConfirmed` fires, and the controls show.

**Implementation:** Added a `leftEdgeGestureActive` boolean field in `PlayerGestureHelper`. In the
`playerView.setOnTouchListener` block, on `ACTION_DOWN` we check whether `event.x` is within 24 dp
of the left edge. If so, we skip passing the event to `gestureDetector` / `zoomGestureDetector` for
the entire gesture. We still return `true` (consuming the event) so ExoPlayer's own `onTouchEvent`
is never called.

**Why it didn't work:** The menu still opened. Returning `true` from the touch listener or not
passing events to `GestureDetector` does not prevent the controls from showing — some other code
path is responsible. The root cause was not identified.

**Commits:** introduced in `90a5913e`, reverted in `958a3b0`.

---

## What Is Known

- `onSingleTapConfirmed` in `PlayerGestureHelper.gestureDetector` is the **only** place in the
  codebase that calls `playerView.showController()` (the `onScroll` handler explicitly returns
  `false` for horizontal swipes and never shows controls). Yet controls appear on a left-edge
  swipe, which should not trigger `onSingleTapConfirmed`.
- The `PlayerView.setOnTouchListener` always returns `true`, preventing ExoPlayer's own
  `onTouchEvent` (which toggles the controller on `performClick`) from being called. This is
  confirmed correct because no ExoPlayer built-in controller-show behavior is observed for any
  other gesture.
- The `playback_progress_bar` (a `ProgressBar`, not a `SeekBar`) does not set system gesture
  exclusion rects. No other always-visible view in `fragment_player.xml` sets exclusion rects.
- The Android system gesture back-navigation is intercepted at the **window/input level**, before
  touch events are dispatched to the view hierarchy. Returning `true` from a touch listener should
  **not** prevent the OS from claiming an edge swipe as a back gesture on stock Android. Behavior
  may differ on OEM skins (Samsung OneUI edge panels, etc.).
- The right edge back gesture works correctly with identical touch-listener code, which makes
  it unlikely that `true` return from the listener is itself the issue.

## Suspected Root Cause (Unconfirmed)

On some Samsung/OEM devices, the **left edge mid-screen** may be reserved for a system feature
(e.g. Samsung Edge Panel). The OS intercepts the gesture to check whether an edge panel should
open; if none is configured, it may fall through to the app rather than converting to a standard
back gesture. The app then receives what looks like a very short tap, `onSingleTapConfirmed` fires,
and the controls show.

This would explain:
- Right edge works (not reserved for edge panel).
- Top/bottom-left work (edge panel handle is typically at mid-height).
- Middle-left does not work.

## Ideas for Next Attempt

1. **Verify on a stock Android device.** If it works there but not on Samsung, the root cause is
   Samsung-specific and needs a Samsung-specific workaround.
2. **Inspect exclusion rects at runtime.** Use `adb shell dumpsys input` or add a debug overlay
   that iterates the view tree and prints all exclusion rects to logcat when the player is running.
   This would reveal whether any view actually has a conflicting rect.
3. **Intercept at Activity level.** Override `Activity.dispatchTouchEvent` to log the first
   `ACTION_DOWN` x/y and the gesture that eventually shows the controller. This would confirm
   whether `onSingleTapConfirmed` is truly the trigger.
4. **Use `WindowInsetsController` to disable the left back gesture zone.** This is destructive —
   it removes the system gesture for the whole screen — but would confirm if the system gesture is
   the conflict.
5. **Conditionally not showing the controller** for taps that came from near the left edge
   (`e.x < 30dp` guard in `onSingleTapConfirmed`). This only suppresses the symptom, not the cause,
   but it might be the pragmatic fix if the cause is OEM-specific and unfixable.
