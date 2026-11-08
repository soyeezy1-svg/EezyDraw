# 2D drawing export

Validated on 2026-11-08: **691 tests pass** (53 model, 120 geometry, 232 editing, 27 storage, 259 app), with no failures or skipped tests. The debug APK builds and app/storage debug lint reports zero issues.

Project → Export → Export 2D drawing provides PDF, DXF and PNG through Android CreateDocument. PDF supports current view, fitted current level and fixed-scale sheets. Paper sizes are A4/A3/A2/A1/A0 with portrait/landscape and preset/custom scales; title block, north arrow, scale bar and underlays are optional. DXF uses full-size model coordinates, system layers and mm/cm/m. PNG preserves the current camera and view mode. Hidden objects are excluded even when the editor displays faint hidden outlines.

## Automated coverage

- Six `DxfExportTest` tests cover all three unit declarations and coordinate factors, world/project origin, level/layer/system/object visibility, hidden levels, clipping, continuous/dashed line types, native text, dimension strokes, embedded underlays and collision-safe layer names.
- Nine `DrawingExportTest` tests cover PDF/DXF/PNG output, A3/A1 at 1:50/1:100, every paper orientation, billion-metre layout origins, invalid scale/level validation, current-view/current-level framing, visible dimensions/labels, hidden objects and layers, include/exclude underlay, opacity, rotation, hidden levels, missing assets, decoration options and output-write failure propagation.
- Two Compose tests exercise paper/scale/level selection, underlay exclusion, invalid custom scale, PNG's current-view scope, and DXF scope/units through the real export callback.
- Existing current-view renderer tests continue to cover six display modes, depth, level/system visibility, camera changes, dimensions, surface output, memory bounds and absence of transient selection controls.

`scripts/validate_drawing_exports.py` independently reads the generated files with PyMuPDF, ezdxf and Pillow. All **21 file readbacks pass**: ten PDFs, eight DXFs and three PNGs. PDF files parse without repair and render successfully; their title blocks, north arrows and scale labels are present. A known 10m line measures exactly 200mm at 1:50 and 100mm at 1:100 in both A3 and A1 output. The portrait fitted plan has the correct paper dimensions. Dimension and label geometry remains vector. Embedded opacity and a rotated two-colour underlay render in the expected orientation; exclusion removes the image resource.

DXF audits report **zero errors and zero repairs**. Reader assertions verify `$INSUNITS`, coordinate factors, Power layers, the DASHED pattern and portable SOLID underlays on the locked UNDERLAY layer. PNGs decode at the captured 400 × 240 pixel view size and honour the underlay toggle. A rendered PDF preview was also inspected for visible annotation linework and decoration placement.

Reproduce with:

```sh
./gradlew --max-workers=4 --continue :core:model:test :core:geometry:test :core:editing:test :core:data:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :core:data:lintDebug
python -m pip install pymupdf ezdxf pillow
python scripts/validate_drawing_exports.py
```

Unit tests write files into the JVM temporary directory's `eezy-2d-export` folder; pass that path to the reader if it differs from `/tmp/eezy-2d-export`.

## Manual Android and printing path

1. Create a room/walls on Ground, add a labelled socket and services in two systems, and use Measure to add a known 10m dimension. Put another object on an upper level. Hide an object and one system, then enable faint hidden outlines.
2. Choose Export 2D drawing → PDF → Paper size and scale → Ground → A3 → 1:50. Keep title block, north arrow and scale bar on. Save with the Android picker. Repeat A1/1:100 and portrait. Check visible dimensions/labels, hidden exclusions and the chosen level.
3. Open/print at **Actual size / 100%**, with printer fit-to-page disabled. The 10m line must measure 200mm at 1:50 or 100mm at 1:100. For an oversized drawing, the dialog's fixed-scale clipping rule applies; choose a larger paper or smaller scale to include more.
4. Export Current level (fit), then Current view after pan/zoom and in an orbit view. Check camera framing, actual scale labels and view mode; perspective output must state there is no uniform scale and omit a scale bar/north arrow.
5. With saved image/PDF underlays, repeat PDF/PNG with Include underlay on/off. Verify source page, position, calibration, rotation, opacity, stacking and hidden layers/levels. Lock should not affect export. Try a missing asset, then exclude it to complete the export.
6. Export DXF in mm/cm/m. Open in a CAD viewer and check model-space dimensions, system layers, colours, dashed underfloor services and the embedded UNDERLAY layer. Toggle that layer without changing the vector drawing.
7. Export PNG after pan/zoom. Check camera framing, dimensions/labels and hidden exclusions. Cancel each picker and try an unwritable destination: cancellation creates no export; failure appears in the existing error dialog. Export must leave document dirty/undo state unchanged.

Physical file-provider behavior, printing and platform PDF-underlay decoding require Android/device QA. PDF level/sheet linework is vector; current-view PDF/PNG and PDF/image underlays have a 2,048px-side bound. DXF's self-contained underlay is a 256 × 256 preview rather than an external raster reference; use PDF/PNG when background detail matters. DXF curves/dimensions are exploded drawing geometry, and PDF model labels retain the existing scene stroke font. Title-block text uses Helvetica/WinAnsi. Native project archives remain the lossless save format.
