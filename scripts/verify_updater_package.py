#!/usr/bin/env python3
"""Verify (and optionally build) the tranShilator-plugin release package.

Why this exists: CheckUpdate.exe is git-ignored, so nothing in the repository
guarantees that the binary shipped in a release was built from the current
source. v1.4.4, v1.4.7 and v1.5.0 all shipped the v1.4.3 binary, which means
every updater fix in those releases was inert. A file-exists check would not
have caught that — the file was there, it was just years stale.

So the freshness check is behavioural: the binary must contain marker strings
that only exist in the current source, encoded as UTF-16 as .NET stores string
literals. A stale binary fails, and so does one built from an older checkout.

Usage:
    # validate a binary
    verify_updater_package.py --exe path/to/CheckUpdate.exe

    # build a release zip from a binary and validate everything
    verify_updater_package.py --exe CheckUpdate.exe --pack out.zip \
        --expect-version 1.5.1

    # validate an existing zip (e.g. one already uploaded)
    verify_updater_package.py --zip out.zip --expect-version 1.5.1
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
import zipfile
from pathlib import Path

# Windows runners default stdout to the legacy code page (cp1252), which cannot
# encode the Chinese markers this script prints. Force UTF-8 so the report is
# readable there instead of dying with UnicodeEncodeError.
for stream in (sys.stdout, sys.stderr):
    try:
        stream.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, ValueError):
        pass

REPO_ROOT = Path(__file__).resolve().parent.parent
EXTENSION_DIR = REPO_ROOT / "tranShilator-plugin"

# Entries placed at the root of the release package, under the
# "tranShilator-plugin/" prefix. Anything not listed here is not shipped:
# bin/, obj/, and any local scratch file stay out by construction.
EXTENSION_FILES = [
    "manifest.json",
    "background.js",
    "content.js",
    "github-content.js",
    "github-styles.css",
    "popup.html",
    "popup.js",
    "styles.css",
    "version.js",
    "使用教程.md",
]

UPDATE_CHECKING_SOURCES = [
    "CheckUpdate.csproj",
    "Program.cs",
    "UpdateForm.cs",
    "UpdateService.cs",
]

# Markers proving the binary was built from a checkout that contains the fixes
# they came from. Each is a string literal in the current source, so .NET stores
# it as UTF-16 and it survives single-file bundling.
#
# If you reword one of these messages, update the entry here in the same commit —
# otherwise this check fails for a correct binary. That is the intended tradeoff:
# a false alarm is cheap, shipping a stale updater is not.
FRESHNESS_MARKERS = [
    # #19: install mutex
    "另一个更新窗口正在安装",
    # #19: non-extension target guard
    "目标目录不是插件目录",
    # #15: network failure no longer blamed on the browser
    "下载过程中连接中断",
    # #15: anonymous API quota exhaustion
    "匿名额度",
    # #13: restoring the updater when the package omits it
    "发布包不含 CheckUpdate.exe",
]

MIN_EXE_BYTES = 100 * 1024 * 1024   # self-contained single-file is ~155 MB
MAX_EXE_BYTES = 400 * 1024 * 1024


class Failure(Exception):
    pass


def check(condition: bool, message: str) -> None:
    if not condition:
        raise Failure(message)
    print(f"  ok   {message}")


def verify_exe(exe: Path) -> str:
    print(f"binary: {exe}")
    check(exe.is_file(), "CheckUpdate.exe exists")

    size = exe.stat().st_size
    check(
        MIN_EXE_BYTES <= size <= MAX_EXE_BYTES,
        f"size is {size / 1024 / 1024:.1f} MB (expected 100-400 MB for a "
        "self-contained single-file build)",
    )

    blob = exe.read_bytes()
    check(blob[:2] == b"MZ", "starts with an MZ header (valid Windows PE)")

    print("  -- freshness (the binary must contain current source strings) --")
    for marker in FRESHNESS_MARKERS:
        encoded = marker.encode("utf-16-le")
        check(encoded in blob, f"contains {marker!r}")

    digest = hashlib.sha256(blob).hexdigest()
    print(f"  sha256: {digest}")
    return digest


def collect_extension_files() -> list[tuple[Path, str]]:
    entries: list[tuple[Path, str]] = []
    for name in EXTENSION_FILES:
        source = EXTENSION_DIR / name
        if not source.is_file():
            raise Failure(f"missing extension file: {source}")
        entries.append((source, f"tranShilator-plugin/{name}"))

    icons = EXTENSION_DIR / "icons"
    if not icons.is_dir():
        raise Failure(f"missing icons directory: {icons}")
    for icon in sorted(icons.iterdir()):
        if icon.is_file():
            entries.append((icon, f"tranShilator-plugin/icons/{icon.name}"))

    for name in UPDATE_CHECKING_SOURCES:
        source = EXTENSION_DIR / "updatechecking" / name
        if not source.is_file():
            raise Failure(f"missing updater source: {source}")
        entries.append((source, f"tranShilator-plugin/updatechecking/{name}"))

    return entries


def build_package(exe: Path, out: Path) -> None:
    """Write the release zip. Python's zipfile is used rather than a PowerShell
    helper because the package contains a Chinese filename, and PowerShell 5.1
    mangles those through its ANSI default."""
    out.parent.mkdir(parents=True, exist_ok=True)
    entries = collect_extension_files()

    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for source, arcname in entries:
            archive.write(source, arcname)
        archive.write(exe, "tranShilator-plugin/CheckUpdate.exe")

    print(f"packed: {out} ({out.stat().st_size / 1024 / 1024:.1f} MB, "
          f"{len(entries) + 1} entries)")


def verify_zip(zip_path: Path, expect_version: str | None) -> None:
    print(f"package: {zip_path}")
    check(zip_path.is_file(), "package exists")

    with zipfile.ZipFile(zip_path) as archive:
        names = archive.namelist()

        check(
            not any(n.startswith("tranShilator-plugin/bin/") for n in names),
            "no bin/ build output in the package",
        )
        check(
            not any(n.startswith("tranShilator-plugin/obj/") for n in names),
            "no obj/ build output in the package",
        )

        for name in EXTENSION_FILES:
            check(f"tranShilator-plugin/{name}" in names, f"contains {name}")

        for name in UPDATE_CHECKING_SOURCES:
            check(
                f"tranShilator-plugin/updatechecking/{name}" in names,
                f"contains updater source {name}",
            )

        check("tranShilator-plugin/CheckUpdate.exe" in names, "contains CheckUpdate.exe")

        manifest = json.loads(archive.read("tranShilator-plugin/manifest.json"))
        version = manifest.get("version")
        print(f"  manifest version: {version}")
        if expect_version is not None:
            check(version == expect_version, f"manifest version is {expect_version}")

        version_js = archive.read("tranShilator-plugin/version.js").decode("utf-8")
        check("EXTENSION_VERSION" in version_js, "version.js carries a build version")
        if expect_version is not None:
            check(
                f"'{expect_version}+" in version_js.replace('"', "'"),
                f"version.js agrees on {expect_version}",
            )

        # The exe inside the zip must be the fresh one, not merely present.
        blob = archive.read("tranShilator-plugin/CheckUpdate.exe")
        check(blob[:2] == b"MZ", "packaged CheckUpdate.exe is a valid PE")
        for marker in FRESHNESS_MARKERS:
            check(
                marker.encode("utf-16-le") in blob,
                f"packaged updater is current (contains {marker!r})",
            )
        print(f"  packaged exe sha256: {hashlib.sha256(blob).hexdigest()}")

    print(f"  package sha256: {hashlib.sha256(zip_path.read_bytes()).hexdigest()}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--exe", type=Path, help="CheckUpdate.exe to validate")
    parser.add_argument("--pack", type=Path, help="write a release zip to this path")
    parser.add_argument("--zip", type=Path, help="validate this existing zip")
    parser.add_argument("--expect-version", help="required manifest version, e.g. 1.5.1")
    args = parser.parse_args()

    try:
        if args.exe is None and args.zip is None:
            parser.error("give --exe, --zip, or both")

        if args.exe is not None:
            verify_exe(args.exe)

        if args.pack is not None:
            if args.exe is None:
                parser.error("--pack needs --exe")
            build_package(args.exe, args.pack)
            verify_zip(args.pack, args.expect_version)
        elif args.zip is not None:
            verify_zip(args.zip, args.expect_version)

    except Failure as error:
        print(f"\nFAIL: {error}", file=sys.stderr)
        return 1

    print("\nPASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
