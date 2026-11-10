# Dimensions and wall positioning validation

Validation date: 2026-11-10.

## Implemented behavior

- Measure distance and Aligned dimension use two confirmations, show the pending canonical distance during dragging and retain associative dimensions after placement. Picked host vertices remain attached after resizing and transforms.
- Canvas and saved-measurement values open a numeric editor. Start fixed, End fixed and Center fixed drive wall, line and room-edge geometry; the successful choice is saved in the project. Room outer and hole edges are supported. Generated-wall length edits update their owning room boundary; individual thickness/height overrides survive regeneration.
- Wall and Line drawing accept exact lengths after the first point, including hardware digits, decimal, Backspace and Enter. Ghost, pointer and commit share the constrained endpoint. Wall chains retain the preceding end and clear the completed segment's numeric draft.
- Dimension this wall creates only that wall's centerline/inside/outside lengths, thickness and height. The chooser replaces the displayed property set without duplicating matching records. Selected-object snapping filters both plan and projected 3D indices before queries; cross-object reference measurements can opt out explicitly.
- Move from wall picks a finish face and accepts Near edge, Center or Far edge, with an optional second independent wall. Selected-item distance labels, nudge arrows, physical step entry and grid snapping use ordinary commands. Explicit clearance placement releases an older mounting attachment.
- Lock distance to wall follows wall transforms, thickness changes and generated geometry through the common command boundary. Source/follower changes are atomic; locked items/layers reject conflicting edits with a message. Constraint and annotation references remap on copy and detach on deletion. Undo/redo, hidden/level filters and native save/load retain the relationships.

## Automated coverage

After rebasing onto the repository's current `main` (including the portable-materials export fix), the full validation command passed **861 tests**, with zero failures, errors or skips: model 58, geometry 157, editing 279, storage 36 and app 331. The debug APK built and app/data debug lint reported **no issues**. The complete run finished in 4m 49s. This feature adds 32 tests to that upstream baseline. A final run after the 3D endpoint and incomplete-legacy-angle corrections passed all 279 editing tests and 25 focused UI tests, rebuilt the debug APK and passed app/data debug lint with no issues in 2m 14s. Rapid hardware digits plus Enter and differing wall/line elevation handling are included.


| Test | Evidence |
| --- | --- |
| `DimensionPositioningTest` | All fixed-end options on walls, lines and room edges; free/cross-object dimensions; picked-vertex attachment; exact live endpoint at survey coordinates; wall-only properties/face replacement; thickness/height; scoped snapping in plan/orbit; each clearance reference; oblique two-wall solves; locked followers; undo/redo; copy/delete cleanup; hidden/other-level candidates; parallel-wall rejection; generated room walls and mitred face lengths; nonuniform/tilted geometry; angular edits in their original 3D plane. |
| `EditableDimensionsFlowTest` | Actual canvas actions for exact Line and precision Wall lengths, keyboard entry/Enter, wall chaining, live distance while dragging, confirm-only measurement, tapping values, fixed-end edits and undo/redo. |
| `DimensionControlsTest` | Project-unit parsing, remembered fixed-end choice, every fixed-end/reference option, face choices, lock/second-wall controls, physical nudge steps and grid action. |
| `WallPositioningFlowTest` | More menu, two actual wall picks, near-edge/center entry, distance locks following wall movement, editable selected-item labels, undo/redo and ViewModel save/reopen. |
| `EditedGeometryPersistenceTest` | Real Room database close/reopen, revision files and portable archive import preserve bindings, picked-vertex metadata, wall-distance locks and fixed-end default. |
| `MeasurementsTest` | Older records decode without new metadata; existing measurement serialization and summaries remain covered. |
| Existing regressions | Selection/resize handles, Properties, measurements, all drawing tools, precision input, level/layer/system visibility, locks, routing, rendering, storage, exports and autosave. The legacy cross-object reference UI test explicitly disables the new object-only snap default. |

Commands use the workspace JDK 21, Android SDK 35 and existing cached dependencies:

```sh
./gradlew :core:model:test :core:geometry:test :core:editing:test \
  :core:data:testDebugUnitTest :app:testDebugUnitTest \
  :app:assembleDebug :app:lintDebug :core:data:lintDebug --offline
python3 scripts/gles_shader_smoke.py
git diff --check
```

The GLES smoke check passes production shaders, selected transforms, antialiasing and native GLES3 MSAA/resolve/readback with no GL errors. The APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

## Device QA

Compose flow tests substitute the EGL surface; storage tests use real files and Room. These checks do not certify physical-device input latency or GPU lifecycle behavior. On a phone/tablet, check finger/stylus precision and direct input, long hardware-keyboard drafts, tiny and large-coordinate dimensions, selected-object scope at wall junctions, all finish-face choices, two oblique walls, nudge/grid placement, and locked/hidden/multiple-level objects. Reopen a saved archive after moving reference walls; check undo/redo and generated shared-room walls. Repeat in plan/front/side/orbit, both themes, phone/tablet orientations and with the IME visible.
