#!/usr/bin/env python3
"""Resolve Bambu Studio / OrcaSlicer built-in preset inheritance for a selected printer.

This copies configuration only. It does not install Bambu Studio / OrcaSlicer, start a printer,
or confirm production calibration.
"""

import argparse
import json
from pathlib import Path


def catalog(root, vendor):
    result = {}
    for category in ("machine", "process", "filament"):
        entries = {}
        for path in sorted((root / vendor / category).rglob("*.json")):
            data = json.loads(path.read_text(encoding="utf-8"))
            name = data.get("name")
            if name:
                if name in entries:
                    raise ValueError(f"Ambiguous preset name: {category}/{name}")
                entries[name] = data
        result[category] = entries
    return result


def resolve(entries, name, visited=()):
    if name in visited:
        raise ValueError(f"Preset inheritance cycle: {' -> '.join((*visited, name))}")
    if name not in entries:
        raise ValueError(f"Preset not found: {name}")
    own = entries[name]
    result = {}
    parent = own.get("inherits")
    if parent:
        result.update(resolve(entries, parent, (*visited, name)))
    for included in own.get("include", []):
        result.update(resolve(entries, included, (*visited, name)))
    result.update(own)
    result.pop("include", None)
    result["inherits"] = ""
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--profiles-root", type=Path, required=True,
                        help="Bambu Studio / OrcaSlicer Resources/profiles directory")
    parser.add_argument("--vendor", required=True, help="Directory such as BBL, Creality, Prusa")
    parser.add_argument("--list", metavar="TEXT", help="List matching preset names and exit")
    parser.add_argument("--machine")
    parser.add_argument("--standard")
    parser.add_argument("--fine")
    parser.add_argument("--pla")
    parser.add_argument("--petg")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    presets = catalog(args.profiles_root, args.vendor)
    if args.list is not None:
        for category, entries in presets.items():
            for name in sorted(entries):
                if args.list.casefold() in name.casefold():
                    print(f"{category}: {name}")
        return
    if not all((args.machine, args.standard, args.fine, args.pla, args.output)):
        parser.error("Provide --machine, --standard, --fine, --pla and --output, or use --list")
    selected = {
        "machine.json": ("machine", args.machine),
        "standard.json": ("process", args.standard),
        "fine.json": ("process", args.fine),
        "pla.json": ("filament", args.pla),
    }
    if args.petg:
        selected["petg.json"] = ("filament", args.petg)
    output = {}
    for filename, (category, name) in selected.items():
        data = resolve(presets[category], name)
        compatible = data.get("compatible_printers")
        if category != "machine" and compatible and args.machine not in compatible:
            raise ValueError(f"{name} is not listed as compatible with {args.machine}")
        output[filename] = data
    args.output.mkdir(parents=True, exist_ok=True)
    for filename in output:
        if (args.output / filename).exists():
            raise ValueError(f"Refusing to overwrite existing profile: {args.output / filename}")
    for filename, data in output.items():
        (args.output / filename).write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Prepared {len(output)} resolved profiles in {args.output.resolve()}")
    print("Compare against your slicer settings before confirming production profiles.")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError) as error:
        raise SystemExit(str(error))
