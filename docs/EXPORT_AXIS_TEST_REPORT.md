# 3D export coordinate conventions

Validated on 2026-11-08: **674 tests pass** (53 model, 114 geometry, 232 editing, 27 storage, 248 app), with no failures or skipped tests. The debug APK builds and app/storage debug lint reports zero issues.

The export dialog exposes Up axis and Position, alongside mm/cm/m units. Defaults are Y-up/metres for OBJ and glTF/GLB, Z-up for STL and IFC. OBJ and STL permit either up axis; glTF/GLB enforces Y-up and IFC enforces Z-up. Invalid programmatic standard-axis overrides fail before writing any bytes.

The native Z-up → Y-up transform is `(x,y,z) → (x,z,-y)`, a rotation of −90° about X with determinant +1. Model forward becomes −Z; a separate forward setting is unnecessary. Positions, cuts and normals follow this convention. Keep project coordinates retains the datum; Move model to origin subtracts one native-space base centre from the filtered export before rotation. App metadata records this offset without changing the source project. OBJ/STL readers need matching import units because those formats have no standard unit declaration; glTF effective coordinates always remain metres, including when its local buffer uses mm or cm.

## Automated coverage

`ModelExportAxisTest` checks format defaults and constraints, common origin conversion, positive signed volumes, outward winding, OBJ/STL numeric bounds and explicit normals, and produces 42 marked-box fixtures. The model is 1m wide, 2m deep and 3m tall at a nonzero project datum. A separate marker above the top, displaced toward +X and +Y, makes rotation, reflection and origin errors distinguishable. Fixtures cover every format, all permitted axes, mm/cm/m, and both origin policies.

`scripts/validate_export_axes.py` independently reads those files with IfcOpenShell, Trimesh and pygltflib. It verifies exact expected bounds and marker location, watertightness, positive volume, outward normals, stored normal attributes, unit scaling and metadata. IFC files additionally pass IFC4 schema and EXPRESS rules. All **42 readbacks pass**. The existing model-export reader suite also passes, including IFC class/opening fixtures and all unit variants.

The Compose regression selects OBJ, verifies Y-up/metre defaults, chooses Z-up and Move model to origin, and confirms those values reach the export callback. Switching to GLB resets Y-up/metres and excludes Z-up from the choices. Existing scope/filter/unit/save regressions remain in place.

Reproduce with:

```sh
./gradlew --max-workers=4 --continue :core:model:test :core:geometry:test :core:editing:test :core:data:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :core:data:lintDebug
python -m pip install ifcopenshell trimesh pygltflib numpy
python scripts/validate_model_exports.py
python scripts/validate_export_axes.py
```

The fixture paths default to `/tmp/eezy-model-export` and `/tmp/eezy-axis-export`; each reader accepts an explicit directory when the JVM uses another temporary directory.

## Manual laptop and Android path

1. Create a 1000 × 2000 × 3000mm box and a small marker above its top, displaced toward the same positive X/Y corner. Place the model away from the project origin.
2. Project → Export → Export 3D model → OBJ. Confirm Y-up and m; select Move model to origin and save through the Android picker.
3. Open in a Y-up laptop viewer with metre import units. The box must stand 3m tall; top view must show its 1 × 2m footprint and top marker. Check face culling and lighting from outside.
4. Repeat OBJ with Z-up in a Z-up viewer, and test both axes for STL using matching viewer axis/unit settings. Check glTF/GLB in a glTF viewer and IFC in an IFC4 viewer, which use their standard fixed axes.
5. Export with Keep project coordinates, confirming the original datum after the documented axis rotation. Repeat using cm and mm and matching import settings. Check exported metadata and the installed app version.

Automated readback does not exercise a particular laptop viewer's import defaults or Android file-provider UI; those manual checks remain device QA.
