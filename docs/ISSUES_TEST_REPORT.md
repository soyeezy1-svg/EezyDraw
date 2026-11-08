# Issues and markups

Validated on 2026-11-08: **719 tests pass** (53 model, 130 geometry, 238 editing, 29 storage, 269 app), with no failures or skipped tests. The debug APK builds; app and storage debug lint report no issues.

## Automated coverage

Three editing tests cover issue creation/editing, assignee search, priority/status filters, locks, hiding, missing attachments, source-clash links, and atomic issue/asset/layer undo and redo. These are manual issue records; rerunning clash checks continues to use the existing clash status tests.

Seven app tests cover field validation and Save, plan/3D pin projection and hidden-level handling, camera/display-mode capture and presentation restoration, historical-view annotations and hiding after camera movement, all seven markup painters, photo storage, portrait EXIF orientation, multipage PDF/CSV/PNG generation, embedded screenshots and read-only FileProvider sharing intents. Native graphics are used for markup/media tests.

A real Room/file/archive test retains issue fields, captures, markup, photo descriptors and bytes through save/load, portable import and backup recovery. Asset descriptor patches also participate in the history regression. Existing geometry, export, clash, editing and canvas tests run unchanged.

Run the suite, APK and lint checks:

```sh
./gradlew --max-workers=4 --continue :core:model:test :core:geometry:test :core:editing:test :core:data:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :core:data:lintDebug
```

After the tests generate their fixtures, run the independent reader check with PyMuPDF and Pillow:

```sh
python scripts/validate_issue_exports.py /tmp
```

The reader parses both PDF pages without structural repair or warnings, renders them, checks their embedded view images, reads the PNG, verifies quoted CSV fields and saved camera JSON, and decodes the embedded screenshot data URIs. Existing 2D export-reader tests also check the shared PDF writer's single-page output.

## Manual Android path

1. Open a project with two levels. Select an object, open Issues and choose New issue. Set title/description, priority, status, assignee, due date and XYZ. Save; inspect the pin in plan and 3D. Use Issue at point for an unlinked issue. Run a clash check and choose Create issue on a result; confirm its object links, position and source clash.
2. Create several issues, search by title/description/assignee, filter status/priority/level and change sorting. Collapse/move the Issues panel. Switch levels, hide Issue pins in Visibility, hide an individual issue and use its Hidden entry to restore it. Lock an issue, verify editing/deletion is blocked, then unlock it. Undo and redo edits/deletion.
3. Set a distinct plan or orbit camera, X-ray/display mode and object/layer/system visibility. Open an issue and Save view. Change the view and save another. Navigate away; reopen its pin and select either saved view. Confirm camera, mode and visibility restore without changing geometry or object locks; Return to project visibility restores ordinary presentation.
4. Choose Draw markup / screenshot. Drag to draw a cloud, arrow, rectangle, circle, pen stroke, text note and highlighter. Change colour and thickness, undo/redo in the editor, then Save markup screenshot. Confirm the saved-view overlay and thumbnail. Navigate to hide view-specific annotations and restore the issue to show them. Hide/lock Issue markups in Visibility and verify the controls take effect. Undo/redo the saved annotation command.
5. Add a gallery photo and a camera photo, including a portrait JPEG. Cancel each picker once. Rotate during the external picker and verify it still targets the same saved issue. Check thumbnails, logical removal, undo/redo, save/reopen, native export/import and backup recovery, including an older saved screenshot.
6. Export PDF all and CSV all through a real document provider. Confirm the issue metadata, photos and view; test a long description and more than four photos for PDF continuation. Read CSV as quoted UTF-8: View contains JSON and Attachments contains image data URIs. Share a single issue as an image and PDF through Android's chooser; preview attachments in a receiving app before sending.

The automated checks use Robolectric and native software graphics. Real-device camera/gallery lifecycle, SAF providers, share receivers, GPU overlays, touch ergonomics and aspect-ratio changes remain device QA. Markups are tied to captured screen views rather than world geometry. Media limits are 16MiB per imported file, 2048px decoding, a 24MiB project attachment budget and the existing native archive limits; retired immutable files are retained for history/backup safety.
