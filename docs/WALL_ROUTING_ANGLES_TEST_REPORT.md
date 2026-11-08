# Wall routing angles validation

Validated on 2026-11-08 with JDK 21, Android SDK 35 and Gradle 8.13.

| Check | Result |
| --- | --- |
| Model JVM tests | 51 passed |
| Geometry JVM tests | 94 passed |
| Editing JVM tests | 223 passed |
| Storage / Robolectric tests | 26 passed |
| App / Compose / Robolectric tests | 243 passed |
| Debug APK | Built successfully |
| App and storage debug lint | Zero issues |

Total: **637 passed, zero failed, zero skipped**. APK: `app/build/outputs/apk/debug/app-debug.apk`.

```sh
./gradlew --max-workers=4 --continue :core:model:test :core:geometry:test :core:editing:test :core:data:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :core:data:lintDebug
```

The five new editing tests cover angle choices on rotated wall faces and both sides, compatibility with existing Ortho settings, diagonal clipping at wall bounds, horizontal/vertical/diagonal conduit and trunking, sized 45°/90° fittings in surface and concealed modes, fitting port alignment with trimmed sections, diagonal chase outlines, depth warnings, connected endpoint editing, movement, and undo/redo.

The new geometry test checks the actual volume removed by a diagonal chase against its swept length × width × depth. The chase implementation is unchanged, including the existing rounded fitting envelopes.

The new native storage test builds both conduit and trunking with horizontal, vertical and diagonal sections, saves and reopens the real Room/file repository, and exports/imports a native archive. It compares angle settings, topology, fitting definitions/instances, trimmed paths, derived cuts and depth warnings. The existing Compose wall-route flow now exercises all four Snap angles choices and checks the remembered per-service settings before cancelling placement.

Manual device path:

1. Draw a wall, enter 3D or an elevation view, and choose Conduit or Trunking → Surface mounted or Concealed (chase).
2. Choose Snap angles → 45°. Place a horizontal run, a diagonal run, a vertical run and another horizontal run, then Done. Check two 45° bends and a 90° bend.
3. Try 0° (horizontal), 90° (horizontal/vertical) and Free; confirm the ghost and placed route agree. Repeat on a rotated wall and its opposite face.
4. Move a section or drag its end. Check connected sections, fitting angles and the concealed chase update. Undo/redo, save and reopen.
5. Set concealed depth above half the wall thickness. Check the warning remains available and nominal wall dimensions remain unchanged.

Compose tests substitute the native GLES surface. No physical tablet or emulator was attached; native rendering and touch/stylus ergonomics remain device QA.
