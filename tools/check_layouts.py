#!/usr/bin/env python3
"""Checks every widget layout has the views the renderer touches, and prints which layout each
home-screen grid size gets. A missing view breaks the whole widget ("Problem loading widget").

Run from the repo root: python3 tools/check_layouts.py   (CI runs it before building)
"""
import os
import re
import sys
import xml.dom.minidom

ROOT = os.path.join(os.path.dirname(__file__), "..")
RENDERER = os.path.join(ROOT, "app/src/main/java/com/jono/flick/WidgetRenderer.kt")
LAYOUTS = os.path.join(ROOT, "app/src/main/res/layout")
INFO = os.path.join(ROOT, "app/src/main/res/xml/widget_info.xml")
A = "{http://schemas.android.com/apk/res/android}"

KIND = re.compile(
    r'^\s*(\w+)\(R\.layout\.(\w+), SizeF\(([\d.]+)f, ([\d.]+)f\), "[^"]*", cells = (\d+), '
    r'ring = (true|false), labels = (true|false), recents = (\d+)\)[,;]', re.M)

# What a size on a typical 4-column grid reports (portrait min width × max height), and what
# we expect each to get. Two kinds tying or a surprise here fails the check.
GRID = {
    (1, 1): {"TILE"}, (2, 1): {"ROW_S"}, (3, 1): {"ROW"}, (4, 1): {"ROW_L"},
    (1, 2): {"COLUMN"}, (1, 3): {"COLUMN_L"}, (1, 4): {"COLUMN_L"},
    (2, 2): {"RING"}, (3, 2): {"WIDE"}, (4, 2): {"WIDE_L"},
    (2, 3): {"TALL"}, (2, 4): {"TALL"},
    (3, 3): {"RING_L", "XL"}, (4, 3): {"XL_WIDE"}, (3, 4): {"XL"}, (4, 4): {"XL", "XL_WIDE"},
}
CELL_W = {1: 80, 2: 180, 3: 270, 4: 360}
ROW_HEIGHTS = (70, 90, 100)  # rows vary by phone and launcher


def ids_in(path):
    doc = xml.dom.minidom.parse(path)
    out = set()
    for el in doc.getElementsByTagName("*"):
        v = el.getAttribute("android:id")
        if v.startswith("@+id/"):
            out.add(v[5:])
    return out


def main():
    src = open(RENDERER).read()
    kinds = [(m[1], m[2], float(m[3]), float(m[4]), int(m[5]), m[6] == "true", m[7] == "true", int(m[8]))
             for m in KIND.finditer(src)]
    enum_names = re.findall(r"^\s*(\w+)\(R\.layout\.", src, re.M)
    errors = []
    if len(kinds) != len(enum_names):
        errors.append(f"parsed {len(kinds)} kinds but the enum has {len(enum_names)}: keep the argument order fixed")

    for name, layout, w, h, cells, ring, labels, recents in kinds:
        path = os.path.join(LAYOUTS, layout + ".xml")
        if not os.path.exists(path):
            errors.append(f"{name}: missing layout {layout}.xml")
            continue
        have = ids_in(path)
        need = {"hub", "update"}
        for c in range(cells):
            need |= {f"c{c}", f"c{c}i"} | ({f"c{c}t"} if labels else set())
        for r in range(recents):
            need |= {f"r{r}", f"r{r}i", f"r{r}t", f"r{r}s", f"r{r}p"}
        missing = need - have
        if missing:
            errors.append(f"{name} ({layout}): missing {sorted(missing)}")
        if ring and cells != 8:
            errors.append(f"{name}: ring kinds need all 8 cells")

    info = xml.dom.minidom.parse(INFO).documentElement
    for attr in ("android:initialLayout", "android:previewLayout"):
        ref = info.getAttribute(attr).replace("@layout/", "")
        if not os.path.exists(os.path.join(LAYOUTS, ref + ".xml")):
            errors.append(f"widget_info {attr} points at missing layout {ref}")

    # Same rule Android uses: closest layout that fits, by squared distance.
    for row_h in ROW_HEIGHTS:
        check_grid(kinds, row_h, errors)

    if errors:
        print("\nFAILED:\n  " + "\n  ".join(errors))
        sys.exit(1)
    print(f"\nOK: {len(kinds)} layouts have every view the renderer uses.")


def check_grid(kinds, row_h, errors):
    print(f"grid size → layout (rows {row_h} dp)")
    for (cw, ch), want in sorted(GRID.items(), key=lambda kv: (kv[0][1], kv[0][0])):
        W, H = CELL_W[cw], row_h * ch
        fits = [(((W - k[2]) ** 2 + (H - k[3]) ** 2), k[0]) for k in kinds if k[2] <= W and k[3] <= H]
        fits.sort()
        got = fits[0][1] if fits else "TILE"
        tie = len(fits) > 1 and fits[0][0] == fits[1][0]
        ok = got in want and not tie
        flag = "" if ok else "   <-- expected " + "/".join(sorted(want)) + (" (tie)" if tie else "")
        print(f"  {cw}×{ch} ({W}×{H} dp): {got}{flag}")
        if not ok:
            errors.append(f"rows {row_h}: {cw}×{ch} got {got}, expected {'/'.join(sorted(want))}{' (tie)' if tie else ''}")


if __name__ == "__main__":
    main()
