// Ghidroid native engine: Ghidra's SLEIGH disassembler and C decompiler driven over a memory-mapped
// binary. The Kotlin side parses the container format (ELF / PE / Mach-O) and hands over the memory
// layout, symbols and processor context; everything here is format agnostic.
#pragma once

#include <cstdint>
#include <memory>
#include <string>
#include <utility>
#include <vector>

namespace gd {

/// A chunk of the file mapped into the program's address space.
struct Segment {
  uint64_t vaddr;
  uint64_t fileoff;
  uint64_t filesize;
  uint64_t memsize;
  uint32_t flags;  // SEG_* bits
};

enum : uint32_t { SEG_EXEC = 1, SEG_WRITE = 2, SEG_READ = 4 };

enum SymKind : int32_t {
  SYM_FUNCTION = 0,  // code entry point
  SYM_LABEL = 1,     // code label
  SYM_DATA = 2,      // global variable (size is honoured when non-zero)
  SYM_IMPORT = 3,    // pointer slot (GOT / IAT) that the loader fills with an imported function
  SYM_STRING = 4,    // NUL terminated string literal of `size` bytes (including the terminator)
};

struct SymbolDef {
  uint64_t addr;
  uint64_t size;
  int32_t kind;
  std::string name;
};

/// Value for a SLEIGH context variable (e.g. ARM "TMode") over [start, end).
struct ContextDef {
  std::string var;
  uint64_t start;
  uint64_t end;
  uint32_t value;
};

enum FlowType : int32_t {
  FLOW_NONE = 0,
  FLOW_JUMP = 1,
  FLOW_CJUMP = 2,
  FLOW_CALL = 3,
  FLOW_IJUMP = 4,
  FLOW_ICALL = 5,
  FLOW_RETURN = 6,
  FLOW_INVALID = 7,
};

struct Insn {
  uint64_t addr;
  int32_t length;
  int32_t flow;      // FlowType
  uint64_t target;   // branch / call destination, or NO_ADDR
  uint64_t ref;      // first data reference (memory operand or address-like constant), or NO_ADDR
  std::string mnem;
  std::string body;
};

constexpr uint64_t NO_ADDR = ~0ULL;

struct LanguageInfo {
  std::string id;
  std::string processor;
  std::string variant;
  std::string version;
  std::string description;
  std::string slafile;
  bool bigEndian;
  int32_t size;
  bool deprecated;
  std::vector<std::string> compilerIds;
  std::vector<std::string> compilerNames;
};

/// Register every language directory below `specRoot` (one sub-directory per processor).
bool initLibrary(const std::string &specRoot, std::string &err);
std::vector<LanguageInfo> languages();
std::string demangle(const std::string &mangled);

struct DecompileOptions {
  int32_t maxLineWidth = 100;
  int32_t indent = 2;
  bool readonlyPropagate = true;
  bool castsVerbose = false;
};

class SessionImpl;

/// One opened program. Not thread safe: callers serialise access (the app runs every call on a
/// single engine thread with a large stack, which the decompiler's recursion needs).
class Session {
 public:
  static std::unique_ptr<Session> open(const std::string &path, const std::string &target,
                                       const std::vector<Segment> &segs,
                                       const std::vector<ContextDef> &ctx, std::string &err);
  ~Session();

  void addSymbols(const std::vector<SymbolDef> &syms);
  std::vector<Insn> disassemble(uint64_t addr, int32_t maxCount, uint64_t endAddr);
  /// Pseudo-C with in-band markup (see engine.cc, `EmitColor`). Returns false and fills err on failure.
  bool decompile(uint64_t addr, const DecompileOptions &opt, std::string &out, std::string &err);
  /// Linear sweep over [start, end) collecting direct call targets.
  std::vector<uint64_t> scanCalls(uint64_t start, uint64_t end);
  /// Linear sweep over [start, end) returning instructions that reference `target` (branches,
  /// memory operands, address constants, values built up across instructions).
  std::vector<uint64_t> findRefs(uint64_t start, uint64_t end, uint64_t target);
  /// Finds import stubs (PLT entries, jump thunks) in [start, end): each result pairs the stub's
  /// address with the pointer slot (GOT / IAT entry) it jumps through. Stub starts are snapped to
  /// `entryAlign` when padding precedes an entry.
  std::vector<std::pair<uint64_t, uint64_t>> resolveStubs(uint64_t start, uint64_t end, uint32_t entryAlign);
  int32_t alignment() const;
  int32_t pointerSize() const;
  std::string languageDescription() const;

 private:
  explicit Session(SessionImpl *impl);
  SessionImpl *impl_;
};

}  // namespace gd
