# Vendored Ghidra decompiler sources

Taken from `Ghidra/Features/Decompiler/src/decompile/cpp` of Ghidra **12.1.4**
(tag `Ghidra_12.1.4_build`), licensed under the Apache License 2.0 (see `LICENSE`).

Only the sources needed for the SLEIGH disassembler and the decompiler library are kept: the
console, the Ghidra (Java) protocol back end, the SLEIGH compiler and the GNU BFD loaders are left
out.

Local changes:

1. `prettyprint.hh` — `EmitPrettyPrint::setLowLevel(Emit *)` so the app can plug in its own
   low-level emitter and receive syntax-highlighting / address information.
2. `printc.cc` — `PrintC::genericFunctionName` names unknown callees `FUN_<address>` like Ghidra's
   UI instead of `func_0x<address>`.

The processor specifications in `app/src/main/assets/languages` come from `Ghidra/Processors/*/data/languages`
of the same release; the `.sla` files were compiled from the `.slaspec` sources with this release's
C++ SLEIGH compiler (see `tools/compile_sla.sh`).
