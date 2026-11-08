# Canvas UI completion test report

## Implementation

All canvas group panels now share a resizable, non-modal bottom sheet. The header supports drag up/down, drag down to close, an accessible Resize button and Close. Library, Layers, Issues and Schedule open their primary sheet only when their group is tapped. Clashes, Properties, route editing, measurements and camera editors use the same sheet. Drawing, selection and camera updates never launch sheets.

The selection toolbar follows the projected selected bounds alongside the existing gizmo. It has short labels and the same 24dp outlined icons and Material long-press tooltips as the strips. Edit exposes direct Move, Rotate, Scale, proportions, layer, colour, locks, display, rotation-snap, room-wall and issue commands. Library has direct custom-item and from-drawing entry points. Legacy canvas panel/toolbar wrappers and panel-layout menus are absent from the running canvas.

Settings adds **Bottom bar labels: Show / Hide** and **Bar position: Bottom / Left**. The scrollable left rail retains the view cube, full-screen restore and accessible labels/tooltips, and leaves tool strips beside the rail. Precision controls and contextual overlays account for its width. These preferences survive settings draft restoration and storage, and older JSON retains Bottom/Show defaults.

Start Menu, Settings and Help share slate headers, cyan theme accents, 20dp shapes, 24dp icons, 48dp navigation targets and 24dp content spacing. Existing screen actions and forms remain available.

## Automated validation

```sh
./gradlew --max-workers=4 --continue \
  :core:model:test :core:geometry:test :core:editing:test \
  :core:data:testDebugUnitTest :app:testDebugUnitTest \
  :app:assembleDebug :app:lintDebug :core:data:lintDebug \
  -Pkotlin.incremental=false
```

All requested tasks completed successfully. **754 tests passed**, with zero failures, errors or skipped tests.

| Suite | Passed |
| --- | ---: |
| Model | 54 |
| Geometry | 140 |
| Editing | 241 |
| Storage | 31 |
| App, including Compose workflows | 288 |

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. App and storage debug lint report **zero issues**. The validation command disables incremental Kotlin compilation after the screen action-builder extraction; no compiler/build configuration change is required by the feature.

New coverage includes:

- Enumerating the actual rendered tools in every group and clicking every entry, including more than 100 enabled actions. The fixture is reset for each entry so earlier navigation/dialog state cannot hide an untested tool. Clear reference is exercised after establishing a reference with the public probe.
- No sheet opening from selection/empty taps, explicit group launch, content filling the sheet, resizing by drag, drag-close, Close and reopen. A standalone sheet regression verifies both header and content touches.
- Left rail scrolling, hidden labels retaining content descriptions and long-press tooltips, full-screen restoration and the remembered group.
- Bar preference changes surviving draft recreation, serialization and real settings storage; backward-compatible defaults.
- Existing feature workflows continue to cover construction, snapping, dimensions, transforms, library authoring, wall/floor services, routes/fittings, visibility, clashes, issues, schedules, exports and project persistence.

The architecture document contains the full tool map and explains the two-tap audit. Entry means opening a tool/editor, separately from object selection, data browsing, parameter input and committing an operation.

## Manual device checks

1. In light/dark themes, inspect Start Menu, Settings and all Help tabs. Navigate back and save preferences; check existing actions and keyboard/gesture insets.
2. Open each canvas group. Tap Library, Layers, Issues and Schedule and drag their header to resize, close and reopen. Open Properties and Clashes and repeat. Tap empty canvas and select another object; no closed sheet should reopen.
3. Select a room, route and library item. Check the selection toolbar follows the object through pan/zoom/orbit and rotation, its Move/Rotate mode still controls the gizmo, and Properties/Generate walls/More remain usable. Navigate with Pan/Orbit and verify edit handles yield to navigation.
4. Switch bar labels to Hide, long-press several icons, then switch the bar to Left. Scroll to all groups, draw with precision Place/Cancel in either pointer handedness, resize a sheet, rotate the device and enter/leave full screen.
5. Check the existing issue/clash review, Library authoring, BOQ and PDF/DXF/PNG/model exports through their documented entries.

Physical-device GPU output, actual stylus/gesture feel and system navigation behavior require this smoke test. Compose tests substitute the native drawing surface; they exercise real canvas controls and callbacks, while existing geometry/render tests cover model projections.
