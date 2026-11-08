# 3D model export

Validated on 2026-11-08: **669 tests pass** (53 model, 110 geometry, 232 editing, 27 storage, 247 app), the debug APK builds and app/storage debug lint reports zero issues. Independent file checks use IfcOpenShell 0.9.0.post1, Trimesh 5.2.0 and pygltflib 1.16.5.

The Project toolbar's Export menu opens Export 3D model. Choose IFC4, OBJ, glTF, GLB or STL, scope, units, system/category filters and cut treatment, then Save model. Android CreateDocument chooses the destination. The ViewModel captures an immutable document snapshot, executes conversion/writing on Dispatchers.IO, shows the existing busy progress indicator and reports exceptions through the error dialog.

The exporter uses the existing tessellated surface compiler, restores chunk origins and adds the project origin. OBJ/STL retain Z-up coordinates and the selected numeric units. STL carries a unit hint in its solid name; its format has no standard unit declaration. glTF/GLB use Y-up coordinates and metre-based node translations, with local vertex values in the requested units and a compensating node scale. glTF embeds its buffer so the file picker only needs to create one file. IFC4 declares the chosen SI length unit, uses actual tessellated world coordinates, and includes project/building/storeys, typed elements, system groups, presentation layers and a property set containing names, layer/system, size parameters, manufacturer, model and mounting height.

Whole project includes hidden objects; Visible objects uses object, layer, system, level and room-side visibility, rather than camera occlusion. Current level uses the level captured from the canvas (or chosen explicitly in the dialog). Selected objects also includes their generated fittings. Cut intent remains independent of service visibility: cuts can be baked into host geometry, omitted, or exported as separate volumes. Separate IFC openings use IfcRelVoidsElement when their exported host is an IfcElement; standalone/space-hosted cut volumes use proxies. Rooms map to IfcSpace and aggregate under their storey. Library class mapping uses fitting definitions, standard symbols and item names; unknown custom items map to IfcBuildingElementProxy. Exported size properties are source size parameters; transformed geometry supplies actual world orientation and scale.

The export rejects empty scopes and exports above 200,000 triangles with an actionable level-export message, in addition to existing surface compilation limits. Geometry conversion is bounded and writing uses a buffered output stream. IFC contains tessellated BIM elements, not editable parametric families. No CRS/geographic projection is inferred from the project origin.

Verification commands:

```sh
./gradlew --max-workers=4 --continue :core:model:test :core:geometry:test :core:editing:test :core:data:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :core:data:lintDebug
python -m pip install ifcopenshell trimesh pygltflib pytest
python scripts/validate_model_exports.py
```

ModelExportTest writes reader fixtures to the JVM temporary directory's eezy-model-export folder. Tests cover every format, units, origin/orientation, levels, selection, hidden objects, system and category filters, underfloor exclusion, cut modes, IFC types/properties and a 1,500-object export retaining the last object. The Compose dialog test checks format, scope, units and disabled empty-selection export. The independent script validates IFC4 schema/EXPRESS rules and constructs geometry with IfcOpenShell, reads OBJ/STL/glTF/GLB with Trimesh and parses glTF/GLB with pygltflib.

Manual Android path: open a model containing walls/slabs, equipment and services; choose Project → Export → Export 3D model; try each scope/format and save through the picker. Check the progress overlay, cancel the picker, test an inaccessible output location, and open the saved files in a BIM/mesh viewer. Physical-device file-provider and GPU behavior remain device QA.
