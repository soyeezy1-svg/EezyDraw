# Clash detection

Validated on 2026-11-08: **708 tests pass** (53 model, 130 geometry, 235 editing, 28 storage, 262 app), with no failures or skipped tests. The debug APK builds and app/storage debug lint reports no issues.

Clash check is available in the Project toolbar. It compares any two system, discipline or object-category groups, with Electrical vs HVAC, Plumbing vs Structure and All vs All presets. It supports current/all levels, visible/all objects, per-system clearances, board clearance, tolerance and optional connected-joint exclusion. Results and check options save with the project.

## Automated coverage

Ten geometry tests cover:

- Hard overlap, face contact, containment and coincident duplicates.
- Euclidean clearance (a known 30/40mm diagonal gap measures 50mm), clearance boundary tolerance, 0.5mm ignored overlap and 2mm detected overlap at 1mm tolerance.
- Rotated slender boxes with overlapping AABBs but separate solids, and a true rotated intersection.
- Slab holes, enclosed items in real slab material, concealed wall chases versus unhosted conduit intersections, and in-slab channels.
- Crossing underfloor conduits, fitted/library geometry contained in structure, and route/fitting/underfloor category selection.
- System and discipline groups, current-level scope, hidden objects/layers, all-object checks and locked objects; per-system and 300mm board clearance.
- Billion-metre coordinate offsets, temporary red/cyan inspection styling and source immutability.
- A 2,000-object project with 24,000 triangles: the broad phase evaluates **zero unrelated pairs**, versus 1,999,000 possible unordered pairs. The regression completed in approximately 1.4 seconds in this cloud JVM run and includes a cooperative cancellation check. Its generous 20-second ceiling is a regression guard, not a tablet performance claim.

Three editing tests verify stable IDs/status/notes across reruns; resolution and reopening; preservation of unrelated scopes; status undo/redo on locked objects; saved check options; and resolved audit history after deletion.

The real Room/file/archive test verifies status, note, measurement and option save/load, portable archive import, backup restoration, and resolved history with a deleted object reference. Three Compose tests cover preset/current-level options, invalid numeric input, focus/cancel/status/note callbacks and independent type/system/level/sort filters.

Run the complete suite and APK/lint checks with:

```sh
./gradlew --max-workers=4 --continue :core:model:test :core:geometry:test :core:editing:test :core:data:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :core:data:lintDebug
```

## Manual Android path

1. Place a duct crossing a conduit, a pipe through a column, two coincident library items and a board 200mm from another object. Put another pair on an upper level; hide and lock some objects.
2. Open Clash check and run Electrical vs HVAC on the current level with visible-only enabled. Check progress and that drawing/navigation remain responsive. Repeat All vs All/all levels/all objects; locked and hidden solids must participate.
3. Set service clearance to 50mm, board clearance to 300mm and tolerance to 1mm. Test a 0.5mm overlap, a 2mm overlap, a 40mm gap and a 60mm gap. Coincident objects should be Duplicate, real intersections Hard and insufficient gaps Clearance.
4. Confirm valid concealed chases and slab channels leave empty material; an unrelated pipe/beam penetrating the remaining wall/slab must still clash. Compare fitted elbows and underfloor runs. Toggle Ignore connected joints to include/exclude intentional connections.
5. Open/collapse/drag the Clashes panel. Filter by type/system/level and change sorting. Tap a result: its pair must appear red/cyan in an isometric close-up with faded context; toggle X-ray and Clear focus. Hidden pairs are revealed temporarily, without changing saved Visibility settings.
6. Change statuses and notes, undo/redo, save, reopen and export/import the native archive. Run again: matching statuses/notes remain. Move a clash apart and rerun to resolve it; move it back and rerun to mark it New with its note retained. Results from another scope stay untouched.
7. Cancel a large check and verify previous results remain. Edit geometry during a check and verify stale results are rejected with a request to rerun. Scope a model beyond the explicit triangle/result limits down to a level/system; failed checks must never publish a partial list.

Native GPU/touch behavior remains device QA. The detector checks the existing tessellated physical geometry, not analytic CAD solids. Convex penetration uses separating axes; nonconvex penetration is estimated from boundary/interior witnesses and surface distances, with finite sampling. Pathological concave grazing contacts need engineering review. Room spaces/zero-volume annotations are excluded; use actual generated walls and slabs. Analysis limits are 200,000 triangles and 20,000 results.
