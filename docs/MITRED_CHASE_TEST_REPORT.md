# Mitred concealed chase validation

Validated on 2026-11-08 with JDK 21, Android SDK 35 and Gradle 8.13.

| Check | Result |
| --- | --- |
| Model JVM tests | 51 passed |
| Geometry JVM tests | 99 passed |
| Editing JVM tests | 225 passed |
| Storage / Robolectric tests | 26 passed |
| App / Compose / Robolectric tests | 243 passed |
| Debug APK | Built successfully |
| App and storage debug lint | Zero issues |

Total: **644 passed, zero failed, zero skipped**. APK: `app/build/outputs/apk/debug/app-debug.apk`.

The chase footprint now follows logical straight route sections and sharp mitre wedges. Fitting library geometry and physical service trims remain unchanged. Source widths, clearance, depth, schedule ownership and depth warnings retain their existing meaning. The existing depth-band solid union combines intersecting strips, T-junctions and overlapping routes without leaving internal faces.

```sh
./gradlew --max-workers=4 --continue :core:model:test :core:geometry:test :core:editing:test :core:data:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :core:data:lintDebug
```

Five new geometry regressions cover:

- Exact 45°/90° conduit and trunking footprints on both faces, compared with an independent JTS JOIN_MITRE/CAP_FLAT buffer; sharp corner coordinates, non-overlapping outer wedges, and removed volume confirm unchanged width/depth.
- Reversed legacy multi-point routes and a duplicate incoming section at a bend.
- T-junctions and overlapping straight chases: union area and removed volume count each intersection once.
- Multiple depths and opposite wall faces, including depth-band volume and the existing warning threshold.
- X-ray, wireframe, capped horizontal section and cut visibility; section volume and nominal wall thickness remain correct.

Two new editing regressions cover connected corner edits, disappearance of obsolete mitres, moving all connected sections, retained curved fitting definitions, undo/redo, and an engine-generated tee alongside bends. The existing wall-bend test now checks a sharp logical corner while confirming the fitting centreline remains curved. The existing native storage regression now includes a tee, moved routes and X-ray/section settings; it compares the exact source document, derived mitred cuts, warnings and fitting definitions after real Room/file save/reopen and portable archive import.

Manual device path:

1. Draw a wall and choose concealed Conduit or Trunking in 3D/elevation. Set Snap angles to 45°.
2. Place horizontal → diagonal → vertical → horizontal sections and finish. Check the groove's straight edges meet at sharp corners while the bend fittings retain their curved form.
3. Branch another concealed route into the first to create a tee. Cross or overlap a further chase on the same wall. Check there are no gaps or extra internal faces in the groove.
4. Move a section, drag an endpoint, undo/redo, then save/reopen. Check the corner cuts follow the current route.
5. Check Shaded, X-ray, wireframe and Section; hide/show wall cuts. Confirm nominal wall thickness and the depth warning remain unchanged.

No emulator or physical tablet was attached. The automated geometry checks validate footprints and solids; native GPU display and touch/stylus ergonomics remain device QA.
