# Wall openings, mounted items, display and ceilings — test report

Date: 2026-11-09. Branch: `fix/wall-openings-faces-display-ceilings`.

## Automated verification

Run with JDK 21, Android SDK 35 and the repository Gradle wrapper:

```sh
./gradlew --no-daemon --no-parallel -Pkotlin.compiler.execution.strategy=in-process --max-workers=2 \
  :core:model:test :core:geometry:test :core:editing:test \
  :core:data:testDebugUnitTest :app:testDebugUnitTest
./gradlew --no-daemon --no-parallel -Pkotlin.compiler.execution.strategy=in-process --max-workers=2 \
  :app:assembleDebug lintDebug
python3 scripts/gles_shader_smoke.py
git diff --check
```

All **794 tests pass**, with zero failures/errors/skips: model 54, geometry 140, editing 259, storage 33 and app 308. The complete final test run completed successfully in 7m 32s. There are 20 new tests, plus updated geometry/display/mounting regressions and timeout diagnostics for an existing Compose flow test.

The final debug APK build and `lintDebug` both succeed (3m 21s), with zero app/storage lint issues. The APK is generated at `app/build/outputs/apk/debug/app-debug.apk`. The production line/surface GLES shader smoke passes, and `git diff --check` is clean.

Tests were run separately from build/lint in the final verification. An initial combined run hit a 10-second render wait in the existing `ShapeDimensionsFlowTest`; the complete final app suite passes without changing that timeout or weakening its assertions. The new ViewModel test was corrected to restore a hidden wall through Visibility before exercising selected-object reset, since hiding intentionally clears selection.

## Coverage

| Area | Regressions |
| --- | --- |
| Door/window openings | All six stock door/window variants; exact width, height, sill and full wall depth; triangle coverage proves both finish faces are empty inside the opening and solid beside it; frames match wall thickness. |
| Views and updates | Filled/wireframe/X-ray/section compilation, projected plan gaps even with service cuts hidden, moving/resizing/deleting source items, unchanged nominal walls, undo/redo, explicit uncut model export. |
| Door swing | Both hinge sides × both swing directions; true leaf-radius quarter-circle plan arc; Flip round trip, leaf/symbol changes without changing the opening. |
| Wall mounting | Closest face on either side, Flip side, outward orientation, flush surface/recess contact, named room/outside faces, all four distance references, nearest-door jamb, numeric mounting/end-distance edits. |
| Wall display | One generated wall changes; adjacent/shared rooms retain one shared physical wall; all four modes, explicit Normal, legacy room-side overrides, reset selected/all, real ViewModel reconciliation, undo/redo. |
| Ceilings | Default None without a roof cap, inherited level/per-room Flat settings, separate selectable/X-ray/hidden slab, Show ceilings, deletion without regeneration, room deletion, height changes move mounted equipment, mounting plane without a drawn ceiling, None → Flat inheritance. |
| Controls | Compose tests exercise placement face/Flip, typed position and nudges, door hand/swing/Flip, Properties validation and atomic values, ceiling dialog validation, Visibility toggle/configuration/reset callbacks. |
| Storage | Real Room/file revision save and reopen plus portable archive import/export retain opening dimensions/swing, socket face/position, shared-wall display, ceiling configuration/ownership/display and Show ceilings. |

Existing suites also check geometry/picking, transform locks, reference cleanup, history limits, routing, clashes, model/drawing export, native bitmap output, library authoring and storage corruption handling.

## Corrections and compatibility

Generated-wall reconciliation previously started from the old record and discarded a just-edited wall display. It now retains current surviving records. Door/window solids previously sat on one finish face; they now supply full-depth opening intent and derived frame/leaf geometry. Room rendering no longer adds an implicit ceiling cap. Placement uses the raw crosshair in the same face resolver as commit.

The tests additionally protect physical-wall selection at coincident room boundaries, empty pickable opening space, and ceiling reconciliation being repeatable without turning inherited None into a permanent room override. Library extent regressions now account for the real open door leaf extending beyond the nominal opening depth. Linked-wall display regressions assert the physical wall record changes instead of expecting a room-side override.

New serialized fields have defaults. Stable legacy door/window catalogue identities are recognized and their existing attachments are normalized on opening a project. No Room database or archive version change is required.

## Device QA limits

The environment has no Android emulator or physical device. Compose/ViewModel tests run with Robolectric; native rendering validation uses software Mesa ES 2. These checks do not establish Android EGL/lifecycle behavior, GPU performance or real touch accuracy. On a phone and tablet, check door/window openings in plan/orbit/X-ray/section while moving/resizing, placement across both wall faces, shared-wall display/reset, ceiling selection/delete/toggle, and save/reopen in light/dark themes.
