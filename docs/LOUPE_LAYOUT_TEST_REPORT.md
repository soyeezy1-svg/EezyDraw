# Precision loupe and edge-to-edge layout validation

Date: 2026-11-09.

## Changes

- Replace the loupe's independent scene projection, face sorting and approximate shading with a small crop of the completed native GLES framebuffer. The source uses the snapped crosshair's canvas pixels, with top/bottom and RGBA/ARGB conversion, fractional sampling and configured 2×–4× scaling. All drawing passes, camera projection, view modes, selection, section and visibility rules therefore come from the canvas itself.
- Keep a bounded motion margin so a valid crop can follow nearby input immediately while the next render arrives. Reject a capture after the scene, camera, theme, mode or other render inputs change. Coalesce GL-to-UI delivery and clear retained captures on disposal.
- Measure Close/cube, top toolbar, bottom/options/hint/tray stack, left rail, Place/Cancel/nudges and other visible overlays. Search free rectangles inside safe areas, away from the finger and crosshair; retain a valid position until it needs to move. Constrained landscapes can use a smaller lens at the same zoom. A fully occluded viewport has no legal lens position and never gets an overlapping fallback.
- Paint the bottom stack before applying its single safe-area inset. Keep controls padded while painting to the bottom and both side edges, including landscape navigation. Remove navigation padding from the embedded status row. Disable the system three-button contrast scrim and match the canvas navigation colour to the chrome.
- Make the existing model-export reader fixture directory independent of test order. A clean full-suite run exposed a pre-existing missing-directory failure in `ModelExportTest`; no export behavior changed.

## Regression coverage

| Tests | Checks |
| --- | --- |
| `LoupeCaptureTest` | Every source pixel in asymmetric framebuffer fixtures; off-centre/fractional crosshairs; four screen corners; 2×, 3× and 4×; top-down orientation and RGBA channels; bounded readback; empty/outside surfaces; rejection of old scenes/cameras/themes/modes. |
| `PrecisionPointerOverlayTest` | Native Skia bitmap assertions for magnification about an off-centre point, live crosshair movement, re-centring an in-flight crop, light/dark source colours, centred snap marker/readouts, retained candidate and confirm-only controls. |
| `PrecisionPointerGeometryTest` | Finger/crosshair clearance, free-space search, stable positions, relocation, measured obstructions, safe areas and impossible-space handling; existing offset/stylus/nudge checks retained. |
| `CanvasEdgeToEdgeTest` | 24 viewport/theme/navigation combinations; screenshots sample the complete bottom edge and inset band against the bar colour; exactly one bottom inset; controls clear side navigation; lens avoids every measured obstacle and relocates as the crosshair approaches; resizable panel and left rail checks. |
| `MobileCanvasNavigationFlowTest` | Real tool-input drag forwards the current snapped crosshair and zoom to `CanvasFrame.loupe`; source updates during movement and clears on release; no overlap with the actual cube, toolbar, bottom stack or confirmation controls. The test substitutes the Android drawing surface to inspect frames. |
| `SystemBarsTest` | Android APIs 28, 29 and 35, each in both themes; matching fallback navigation colour where applicable, light icons on dark chrome and disabled three-button contrast enforcement. |

The screenshot matrix uses 360×800 and 800×360 phone viewports and 800×1280 and 1280×800 tablet viewports at mdpi. Each runs in light and dark themes. Portrait checks 24dp gesture and 48dp three-button bottom insets. Landscape checks gesture navigation and three-button navigation at the bottom, left and right, with a 24dp top safe area. These exercise the production inset-padding path, not a separate test layout.

## Commands

```sh
./gradlew --no-daemon -Pkotlin.compiler.execution.strategy=in-process --max-workers=2 \
  :core:model:test :core:geometry:test :core:editing:test \
  :core:data:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug lintDebug
python3 scripts/gles_shader_smoke.py
```

The Mesa check exercises the production line/surface shaders, buffer layouts, colours, dark contrast, depth, lighting, transparency and selected transforms without GL errors.

## Results

- **774 tests passed**, with zero failures, errors or skipped tests: model 54, geometry 140, editing 246, storage 32 and app 302.
- **Debug APK built**: `app/build/outputs/apk/debug/app-debug.apk` (21,046,953 bytes).
- **Lint passed**: app and storage both report “No issues found.”
- **Mesa GLES smoke passed**, with no GL errors.
- **All 24 screenshot/layout combinations passed**, including crosshair-triggered relocation. The left-rail/resizable-panel scenario and API 28/29/35 system-bar checks also passed.

## Device limits

Layout screenshots use Robolectric native graphics with simulated navigation/cutout-safe insets. System-bar tests verify platform styles separately. This environment has no installed Android emulator or `/dev/kvm`; physical gesture indicators, OEM three-button compositors, Android surface lifecycle and readback/frame-time behavior still require device QA. No physical-device performance result is claimed.
