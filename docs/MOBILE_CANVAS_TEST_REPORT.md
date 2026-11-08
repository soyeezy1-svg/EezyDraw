# Mobile canvas layout and navigation validation

## Changes

The canvas fills the screen. All existing commands are reachable through the manual bottom groups, scrollable sub-strips and requested detail trays. The architecture document includes the complete tool-to-group map. The close button prompts for unsaved changes. The mini toolbar and full-screen restore control sit over the drawing; the existing cube retains its face geometry and orbit interaction, with compass direction controls. A camera-following XYZ indicator can be disabled.

Navigation adds explicit Pan/Orbit, zoom commands/window/selection, fixed-eye Look around, a Walk drag control, plan rotation/reset north, named camera views and an adjustable axis-aligned section box. Preferences control sensitivity, inversion and zoom anchoring. Viewport projection, rendering, precision input, clipping and current-view exports share the same camera state.

## Automated verification

Run from the repository root with JDK 21 and Android SDK 35:

```sh
./gradlew --max-workers=4 --continue \
  :core:model:test :core:geometry:test :core:editing:test \
  :core:data:testDebugUnitTest :app:testDebugUnitTest \
  :app:assembleDebug :app:lintDebug :core:data:lintDebug
```

All tasks completed successfully. Test results: **749 passed**, zero failures/errors/skips.

| Suite | Passed |
| --- | ---: |
| Model | 54 |
| Geometry | 140 |
| Editing | 241 |
| Storage | 31 |
| App (including Compose workflows) | 283 |

`:app:assembleDebug` produced `app/build/outputs/apk/debug/app-debug.apk`. App and storage debug lint both reported zero issues. The tests include:

- Compose strip open/close, one-open-at-a-time, horizontal scrolling and touch-target sizes at tablet, phone, portrait and landscape constraints.
- Full-screen restore, independently hidden bottom bar, per-project layout recovery and empty-canvas taps retaining the strip.
- Full-surface layout, precision/drawing workflow with multitouch navigation, active navigation state, numeric plan rotation, named views and section fields.
- Dirty-project close: Cancel, discard and successful save before exit.
- Navigation preference persistence/restoration, inversion, axis visibility and Walk controls.
- Rotated projection/inverse projection, pan, anchored zoom/twist, window/extent fitting and precision nudges at large coordinates.
- Fixed-eye look, horizontal walking, anchored orbit zoom and section clipping of lines/triangles without source mutation.
- Saved-view save/delete undo/redo and document/archive/backup round trips for layout, rotation and section state.
- Current-view software rendering and GPU plan matrix agreement for rotated plans and section clipping.
- Existing geometry, routing, wall/floor services, library, issues, clashes, schedules, exports and save/load regressions. UI interaction helpers now use the public bottom strips instead of the removed canvas panel wrappers.

## Device smoke-test path

1. Open a project. Verify Close/cube at top left, the mini toolbar at top right, XYZ at bottom left and the scrollable bottom bar above the gesture area. Rotate the device and check light/dark themes.
2. Tap Draw twice to open/close it; switch to Route, then tap empty canvas. Route remains open. Browse all groups sideways and verify one sub-strip is open.
3. Place a room, wall, library socket and route with the existing precision pointer and Place/Cancel controls. Options remain accessible; explicitly close a tray to recover drawing space.
4. Select an object, open Edit and Properties, change a dimension and undo/redo. Open Layers, Issues and Schedule and verify existing editors and reports.
5. Pinch and two-finger pan while drawing. Twist a plan, reset north and rotate numerically. Use Pan/Orbit, then Select. Test empty-space orbit, look and Walk in 3D; verify inversion and zoom-anchor preferences.
6. Save a named plan and 3D view with visibility/display choices. Change the camera and restore each. Adjust all six section-box faces numerically and by dragging; inspect geometry and verify source dimensions remain unchanged.
7. Enter full screen and restore. Hide only the bottom bar and restore it. Save/reopen the project and check the last group/visibility state.
8. Make an unsaved change, Close, and exercise Cancel, Don't save and Save. Verify the saved model is retained only for Save.
9. Export a current-view PNG/PDF after plan rotation or section clipping and compare the framing with the canvas.

## Practical limits

Compose tests substitute the native EGL surface; software snapshot and matrix tests cover projection, but physical-device GPU rendering, system navigation insets, stylus input and multitouch feel still need the smoke test above. Walk has no collision/gravity simulation. The axis-aligned section box clips presentation geometry and leaves cut faces open; it does not perform solid booleans or alter exported model topology. Unsaved edits retain the existing process-death behavior.
