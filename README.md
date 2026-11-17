# Flick

An Android home-screen widget that opens a gestural radial menu (marking menu, like Maya's).

- Tap the widget, then **flick**: each straight stroke picks the item in that direction. A sharp
  turn after pointing at a folder goes into it, so one zigzag can go several levels deep. Only the
  trail and the item you're pointing at are drawn.
- **Hold still** to see the ring (novice mode); hold on a folder to open it.
- **Tap** instead of flicking to get the ring to tap on.

Widgets can't track drags (the launcher owns the touch), so the widget opens a see-through
full-screen activity centred on the widget (`FlickActivity`), which does the gesture work.

- `Recognizer.kt`: pure-Kotlin gesture logic, unit-tested in `app/src/test`.
- `FlickView.kt`: drawing and touch. `WidgetRenderer.kt`: every widget size, 1×1 to 4×4.
- `tools/gen_layouts.py` generates the layouts; `tools/check_layouts.py` checks them (CI runs it).

Builds: push to `main` → GitHub release with `flick-widget.apk`.
