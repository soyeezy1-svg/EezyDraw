# Drawing editor validation

Validated on 2026-11-04 with JDK 21, Android SDK 35, Gradle 8.13 and the pinned project dependencies.

## Results

| Suite/check | Result |
| --- | --- |
| `:core:model:test` | 5 passed |
| `:core:geometry:test` | 50 passed |
| `:core:editing:test` | 87 passed |
| `:core:data:testDebugUnitTest` | 11 passed |
| `:app:testDebugUnitTest` | 81 passed |
| `assembleDebug` | Passed; `app/build/outputs/apk/debug/app-debug.apk` |
| `lintDebug` | Passed; app and storage report zero issues |
| Production GLES 2 shader smoke | Passed; no GL errors |

Total: **234 passed, zero failed, zero skipped**. Reproduce the Gradle checks from the repository root:

```sh
./gradlew testDebugUnitTest :core:model:test :core:geometry:test :core:editing:test assembleDebug lintDebug
python3 scripts/gles_shader_smoke.py
```

The Python check requires Linux, Python 3 and Mesa EGL/GLES 2 libraries. It reads the actual Kotlin shader sources and uses a surfaceless software GL context. Gradle XML/HTML reports are generated under each module's `build/test-results` and `build/reports`; lint reports are under `app/build/reports` and `core/data/build/reports`.

## Coverage and corrected issues

- New sized shapes previously combined centimetre-scale picked footprints from the empty view with a fixed 3m solid height. Ten closed/solid tools now place physical one-metre defaults with a single snapped anchor; a sphere has a 0.5m radius. Tests vary all five display units, insertion pixels, zoom and survey coordinates, and compare Properties and rendered bounds. The empty view now spans 4m and first entry into 3D fits the current geometry.
- Side controls change only width or depth, base corners change width/depth with an optional XY ratio lock, and the top control changes only height. Tests exercise every supported handle across all nineteen shape tools, preserve opposite edges/bases, and compare arbitrary XYZ rotations, mirrored scales, preview matrices and committed Properties. Full-app pointer tests cover all eight plan controls, top-height resizing, flat outlines, numeric Properties and save/reopen.
- Autosave success is silent by default, leaves drawing editable, and persists a default-off Show autosave notification option. Tests cover scheduled/manual saves, opt-in, failures, serialized overlapping writes, newer edits made during a save, settings restoration/backward compatibility, and absence of a success popup in the real app UI.
- Factories create every requested 2D tool, annotation/dimension and 3D primitive. Tests cover point counts, degenerate/self-crossing input, directed arcs, local coordinates at survey scale and dimensional unit/angle formatting.
- Selection tests exercise actual outlines/interiors, polygon holes, hidden/locked layers, levels, all five solids, local numeric dimensions and perspective axis dragging.
- Transform/history tests cover mixed XYZ rotations, signed scale and mirror composition, fresh clone IDs/internal references, arrays, deletion cleanup, stale commands, no-ops, redo branching and history limits. A 100,000-object regression checks sparse retained changes.
- Full-app Compose flows create lines, polylines and boxes; confirm body dragging only navigates; drag an actual move handle; apply all nine numeric Properties fields atomically; mirror/array/copy/paste/delete; undo/redo; and save/continue with a fresh history session.
- Real Room/files/ZIP tests retain all shape variants and mirrored XYZ transforms across save/reload, new-owner import and backup restoration.
- Handle tests verify front/side rotation and cancellation when a second pointer joins after preview recomposition. Panel blank areas consume touch input while child controls and outside canvas gestures continue to work.
- Integration review corrected stale selection previews/pivots, rejected/no-op preview cleanup, hidden selections after level/view changes, per-frame document scans, and keyboard focus handling. The consecutive-line regression catches omission of the selected object from snapping.
- Shader tests verify packed VBO attributes, light/dark colours, plan elevation flattening, 3D depth, selected translation/90-degree rotation/scaling, width-only/depth-only/height-only changes, rotated local corner/height resizing, and a combined transform about a nonzero pivot near billion-metre coordinates without re-uploading the selected VBO. CPU culling uses the same full oriented linear transform in Double.

## Device QA still required

Compose tests substitute only the native surface; they do not establish physical tablet frame rates, GPU memory use or Android EGL lifecycle behavior. Run the [README manual QA plan](../README.md#manual-qa-plan) on API 26/35 devices, including native rendering, background/resume/context loss, stylus/touch, dense drawings, accessibility and SAF providers. Primitives use the existing wireframe renderer; construction uses level XY planes, and dimensions have fixed nonassociative anchors.
