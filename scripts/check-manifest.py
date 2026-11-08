#!/usr/bin/env python3
"""Fail the build when an Activity is launched but never declared.

An explicit `Intent(context, SomeActivity::class.java)` to a class that is
missing from AndroidManifest.xml throws ActivityNotFoundException at runtime -
a force close the compiler and resource linker both accept silently. This check
closes that gap.

Exits non-zero and prints every offending class.
"""

from __future__ import annotations

import pathlib
import re
import sys
import xml.etree.ElementTree as ET

ANDROID = "{http://schemas.android.com/apk/res/android}"
ROOT = pathlib.Path(__file__).resolve().parent.parent
MANIFEST = ROOT / "app/src/main/AndroidManifest.xml"
SOURCES = ROOT / "app/src/main/java"


def declared_components() -> set[str]:
    """Collect simple class names declared in the manifest."""
    tree = ET.parse(MANIFEST)
    names: set[str] = set()
    for element in tree.iter():
        if element.tag not in ("activity", "service", "receiver", "provider"):
            continue
        raw = element.attrib.get(ANDROID + "name", "")
        if not raw:
            continue
        names.add(raw.rsplit(".", 1)[-1])
    return names


def launched_classes() -> dict[str, set[str]]:
    """Map every explicitly launched class to the files that launch it."""
    pattern = re.compile(r"Intent\(\s*[A-Za-z_][\w.]*\s*,\s*([A-Z]\w*)::class\.java")
    found: dict[str, set[str]] = {}
    for source in sorted(SOURCES.rglob("*.kt")):
        for match in pattern.finditer(source.read_text(encoding="utf-8")):
            found.setdefault(match.group(1), set()).add(source.name)
    return found


def main() -> int:
    """Run the check and report the result."""
    if not MANIFEST.is_file():
        print(f"check-manifest: manifest not found at {MANIFEST}", file=sys.stderr)
        return 2

    declared = declared_components()
    launched = launched_classes()
    missing = {name: files for name, files in launched.items() if name not in declared}

    print(f"check-manifest: declared={sorted(declared)}")
    print(f"check-manifest: launched={sorted(launched)}")

    if missing:
        print("", file=sys.stderr)
        for name, files in sorted(missing.items()):
            print(
                f"check-manifest: {name} is launched from {', '.join(sorted(files))} "
                f"but is not declared in AndroidManifest.xml",
                file=sys.stderr,
            )
        print(
            "check-manifest: an undeclared component force closes the app at launch time",
            file=sys.stderr,
        )
        return 1

    print("check-manifest: every launched component is declared")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
