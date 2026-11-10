# Portable 3D export materials

## Behaviour and coverage

The coloured fixture includes a painted wall, room floor, slab, door, transparent blue window, equipment, pipe, fitting, X-ray equipment and a hidden item. It exercises OBJ/MTL ZIP, embedded-buffer glTF, GLB, IFC4 and colourless STL.

- OBJ uses standard `mtllib`/`usemtl` links and named MTL entries with diffuse RGB and opacity. The archive contains exactly `model.obj` and `model.mtl`; both must be extracted together. No vertex-colour extension is required.
- glTF/GLB primitives reference named matte PBR materials, with linear base-colour factors converted from the project's sRGB colours, alpha/BLEND, roughness 0.9 and metallic 0.
- IFC surface styles are linked to each geometry item, including spaces, and carry RGB and inverse-alpha transparency. Applicable elements also have named material associations.
- STL retains geometry and the export dialog explicitly explains that it is colourless.
- Material/System/Level grouping preserves intrinsic transparency; transparent system colours can reduce opacity further. Optional display export also preserves object/inherited and global X-ray transparency.
- Object, layer, system, level, room-side, ceiling and service hides remain excluded. A shared wall remains included if it is still visible through its other owning room. Whole-project can include automatically buried services while Visible-only follows the current reveal rules.
- Shared semantic defaults distinguish the requested categories. Assigned material colours now precede system colours in Material mode, after an explicit item colour. Plan, shaded geometry, Properties and portable export use the same policy. System export mode explicitly overrides RGB with the system colour. Existing explicit item colours remain authoritative.
- The Android save-path test checks that one document receives the complete OBJ/MTL ZIP and that edits after choosing Export do not change its frozen snapshot.
- Compose coverage checks the colour choices, X-ray option, STL note and OBJ package explanation. Existing axis/origin/unit regressions are retained; fixture folders now include the required MTL sidecar.

## Automated validation

Host test commands (JDK 21 and the configured Android SDK):

```sh
./gradlew --no-daemon --no-parallel -Pkotlin.compiler.execution.strategy=in-process --max-workers=2 \
  :core:model:test :core:geometry:test :core:editing:test :core:data:testDebugUnitTest :app:testDebugUnitTest
# Clean export rebuild after adding the shared-wall regression:
./gradlew --no-daemon --no-parallel -Pkotlin.compiler.execution.strategy=in-process --max-workers=2 \
  :core:geometry:clean :core:geometry:test
./gradlew --no-daemon --no-parallel -Pkotlin.compiler.execution.strategy=in-process --max-workers=2 \
  :app:assembleDebug lint
```

Independent readers:

```sh
python -m pip install ifcopenshell trimesh pygltflib numpy networkx
python scripts/validate_export_materials.py
python scripts/validate_model_exports.py
python scripts/validate_export_axes.py
```

The material reader extracts the actual ZIP into a fresh folder, resolves MTL diffuse colours with trimesh, checks opacity declarations, parses glTF/GLB PBR values with pygltflib and loads their geometry with trimesh. It uses IfcOpenShell to validate IFC4 schema/EXPRESS rules, build every product's geometry, and verify the reader's resulting surface colours/transparency. STL is loaded as geometry without a colour claim. The general reader covers 28 IFC and 16 OBJ/STL/glTF/GLB fixtures. The coordinate reader checks all 42 axis/unit/origin fixtures, including dimensions, top-marker placement, positive volume, winding, normals and metadata.

Results:

| Check | Result |
| --- | --- |
| Model tests | 57 passed |
| Geometry tests, including the clean export rebuild | 157 passed |
| Editing tests | 259 passed |
| Data debug tests | 35 passed |
| App debug/Compose tests | 321 passed |
| Total unit tests | 829 passed; zero failures, errors or skips |
| Coloured-format reader checks | All five formats passed, including OBJ/MTL ZIP and IFC surface alpha |
| General reader fixtures | 44 passed |
| Axis/unit/origin fixtures | All 42 passed |
| Debug APK | Built successfully |
| Lint | Passed (`lint` for app and data modules) |

## Scope of verification

These checks use independent desktop file libraries and Android host/Compose tests. No graphical laptop viewer or physical Android device was available in this environment. Viewer-specific lighting and alpha sorting can affect appearance; stored material values and geometry were independently checked. OBJ should be shared as the ZIP or with its MTL beside it; sharing an extracted OBJ alone discards its standard material dependency.
