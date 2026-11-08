# Schedules and quantity take-off

Validated on 2026-11-08: **735 tests pass** (54 model, 137 geometry, 240 editing, 30 storage, 274 app), with no failures or skipped tests. The debug APK builds; app/storage debug lint reports zero issues.

## Automated coverage

- Model: definition/settings serialization, defaults for old documents, all seven provided route schedules, allowance limits and currency/rate validation.
- Seven geometry tests: each route kind, true transformed XYZ lengths including risers, 10% allowance, equipment room assignment with holes, mirrored/scaled net room areas, managed/legacy fitting counts and row links, grouping/sorting/text/system/level/visibility filters, logical chase/mitre coalescing and opening positions/sizes, updates after removal, read-only row inspection of hidden/locked objects, and BOQ quantities/costs with independent currencies and mixed units.
- Two editing tests: real routing-generated bends/reducers/end fittings are counted exactly once, endpoint edits update length and undo restores it; schedule settings and rates update project/library definitions and undo/redo without editing locked geometry. Clearing rates is covered.
- Real Room/files/archive/backup: schedule definitions, filters, allowances, currencies, project rates and library defaults survive save/reopen, portable import and backup recovery. Portable device-library backup retains default rates.
- Two export tests: all formats, metadata/date/level filters, typed XLSX cells, CSV quoting and formula-like text, optional totals and multipage PDF. Three Compose tests cover invalid allowance input, currency/settings saving, direct total-cost entry, row selection callbacks and sharing the active definition.

Run all checks:

```sh
./gradlew --max-workers=4 --continue :core:model:test :core:geometry:test :core:editing:test :core:data:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :core:data:lintDebug
```

The export test writes an 80-row BOQ fixture. With openpyxl and PyMuPDF installed, validate it independently:

```sh
python scripts/validate_schedule_exports.py /tmp/eezy-schedule-export
```

openpyxl opens the workbook without warnings, verifies numeric quantities/rates/costs and the USD 200.00 total, and finds no formulas. Python's CSV reader retains quoted names and totals. PyMuPDF parses the PDF without repair/warnings, renders every page, verifies repeated project/date/level metadata and finds every source row and total across the wide table's column sets.

## Manual Android path

1. Place rooms (including a hole), several library devices on two levels and different systems, and conduit/tray/trunking/duct/pipe runs with a riser, tee, bend, reducer and supports. Add a concealed wall run, recessed device and wall penetration. Open Schedules; compare every provided table against the model. Cable ladder and basket have separate schedules too.
2. Tap equipment, route, fitting, room and wall-cut rows. Confirm all linked objects highlight and fit in plan and 3D. Include hidden objects, tap one of their rows, and Clear highlight; the temporary reveal must leave source Visibility and lock settings intact.
3. Create a custom schedule; select category/service, columns, grouping, sorting, text, multiple levels and systems, visibility scope and totals. Save/reopen the project and confirm the definition persists. Resize or delete a route and device, change a room dimension, and undo/redo: tables must refresh.
4. Set length allowance to 10%; compare measured/allowed lengths. Confirm item counts and room areas are unchanged. Open BOQ, enter unit rates and a total cost, check derived unit rate and rounded row costs, then change a quantity. Check that partial totals identify unpriced rows. Switch currency and back; rates must never be relabeled or exchanged.
5. Save a device rate to the reusable library, place a fresh instance in a new project and verify its default. Check project undo separately from library undo. Export/import both project and library backups and compare rates.
6. Share each table and BOQ as CSV, XLSX and PDF through Android's chooser. Open in a spreadsheet/PDF app, check numeric cells, headers, date/filter metadata, wide/long table continuation and totals; test cancelled sharing. Check panel collapse/move/hide, full-screen panel rules, small-screen scrolling and real-device touch selection.

Device share receivers, GPU highlights, large-model timings and phone/tablet ergonomics remain manual QA. Lengths are editable model centrelines, not fabrication/spool quantities; overlapping source chases are separate work items. Room assignment uses level/footprint, not volumetric room classification. Currency conversion, taxation and procurement certification are outside this take-off. The engine runs in the background; no device frame-rate claim is made.
