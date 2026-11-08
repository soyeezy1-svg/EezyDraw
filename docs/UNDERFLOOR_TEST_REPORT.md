# Underfloor routing validation

Validated on 2026-11-08.

| Check | Result |
| --- | --- |
| Model JVM tests | 53 passed |
| Geometry JVM tests | 103 passed |
| Editing JVM tests | 232 passed |
| Storage / Robolectric tests | 27 passed |
| App / Compose / Robolectric tests | 246 passed |
| Debug APK | Built successfully |
| App and storage debug lint | Zero issues |

Total: **661 passed, zero failed, zero skipped**. APK: `app/build/outputs/apk/debug/app-debug.apk`.

The floor routing extension uses the existing native routing graph, library catalogue, fitting generation, sparse undo/redo, revision storage and portable archives.

Run the verification suite with the configured JDK 21 / Android SDK 35 / Gradle 8.13 environment:

```sh
./gradlew --max-workers=4 --continue :core:model:test :core:geometry:test :core:editing:test :core:data:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :core:data:lintDebug
```

Coverage includes backward-compatible settings, depth/default validation, all four service families at negative AFFL, 45° bends, tees/reducers, level-changing vertical sections, separate layer assignment, depth edits, channel updates, screed/slab depth warnings, crossing versus stacked services, locked-slab rejection, recessed floor boxes, slab sleeves, anchored rises to walls/floor boxes, device movement, schedule rows, deletion, dashed plan visibility, X-ray visibility, visibility undo/redo, and real native save/reopen/archive import.

Floor solid tests measure removed channel volume, outward triangle winding, mitred corners and overlaps, slab holes, channel/sleeve clearance, full-thickness openings, normal/X-ray/Section display and unchanged nominal slab thickness. Scaled and mirrored slab tests verify that the groove keeps its physical width and depth. Compose tests cover Installation, depth entry/readout, ghost/Done, direct rise actions, the slab launcher, the Visibility toggle and the floor schedule.

Quick manual path:

1. Create an Empty project. Draw → Floor slab; choose slab thickness, Place two opposite corners. Its top sits at the current level's finished floor.
2. Draw → Conduit, Trunking, Cable Tray or Pipe. Choose Installation → In screed / In slab / Under floor (raised floor void). Choose depth below finished floor, size, system, colour, bends and auto fittings. Place points and Done; Cancel discards an unfinished route. Precision pointer uses the existing Place action.
3. Check negative AFFL and live length/fitting count. The route appears dashed in plan and occupies the Underfloor services layer. In 3D it is concealed until the floor is X-ray/hidden or X-ray/Section is selected.
4. Select a route and choose Rise to wall or Rise to floor box. Select a placed target from the dialog. Check the inserted vertical section, bend and anchored endpoint. Move the target; undo/redo.
5. From Floor services in Library, place Recessed floor box, Floor socket and Sleeve through slab. Sleeves require a physical slab, match its thickness, and cut an opening with floor clearance. The new recessed devices have their top at finished floor; existing catalogue floor equipment retains its earlier insertion convention.
6. Change levels while routing to insert a riser, and place sleeves on the intersected slabs. Cross a separate route at the same depth and check the clash warning; place it at another depth and confirm the warning clears.
7. Visibility → Show underfloor services controls the routes independently of physical channel intent. Floor services schedule lists routes, floor boxes and sleeve openings by level, with profile, depth, XYZ position and length. It also exposes channel visibility, clearance, depth defaults, screed thickness and warning percentage.
8. Check groove geometry in X-ray and the existing horizontal Section view, dimensions, lock/hide, undo/redo, save/reopen and native export/import.

Depth is route centreline depth below the level's finished floor. Warning depth includes the section's lower half plus clearance. In-screed warnings use configurable screed thickness; in-slab warnings use the actual host slab thickness, falling back to level metadata without a physical slab. Raised-floor void services do not cut channels or trigger material-depth warnings. Screed is a configurable finish zone in the floor solid, rather than a separate structural element. Cross-level sleeves are placed explicitly. Crossing warnings concern intersecting route centrelines within 10 mm in height; this is not a general solid clash engine.

No emulator or physical tablet was attached. Automated tests substitute the native GLES surface for UI interaction; native rendering, touch/stylus use and engineering installation details remain device QA.
