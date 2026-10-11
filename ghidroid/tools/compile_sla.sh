#!/usr/bin/env bash
# Regenerates app/src/main/assets/languages from a Ghidra source checkout.
#   tools/compile_sla.sh /path/to/ghidra
# Builds Ghidra's C++ SLEIGH compiler, compiles every .slaspec and copies the files the decompiler
# loads at run time (.sla, .pspec, .cspec, .ldefs) into the app's assets, one folder per processor.
set -euo pipefail
GHIDRA=${1:?usage: compile_sla.sh <ghidra checkout>}
ROOT=$(cd "$(dirname "$0")/.." && pwd)
OUT=$ROOT/app/src/main/assets/languages
CPP=$GHIDRA/Ghidra/Features/Decompiler/src/decompile/cpp

make -C "$CPP" sleigh_opt -j"$(nproc)"
find "$GHIDRA/Ghidra/Processors" -name '*.slaspec' -not -path '*/Toy/*' -print0 |
  xargs -0 -P"$(nproc)" -I{} sh -c 'f="{}"; "'"$CPP"'/sleigh_opt" "$f" "${f%.slaspec}.sla" > /dev/null'

rm -rf "$OUT"
for dir in "$GHIDRA"/Ghidra/Processors/*/; do
  proc=$(basename "$dir")
  [ "$proc" = Toy ] && continue
  lang=$dir/data/languages
  [ -d "$lang" ] || continue
  mkdir -p "$OUT/$proc"
  find "$lang" -maxdepth 1 -type f \( -name '*.sla' -o -name '*.pspec' -o -name '*.cspec' -o -name '*.ldefs' \) \
    -exec cp {} "$OUT/$proc/" \;
done
echo "Languages written to $OUT"
