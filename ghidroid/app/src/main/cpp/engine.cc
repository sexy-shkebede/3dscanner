#include "engine.hh"

#include <cxxabi.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <cstdlib>
#include <cstring>
#include <iomanip>
#include <map>
#include <sstream>

#include "funcdata.hh"
#include "loadimage.hh"
#include "prettyprint.hh"
#include "printlanguage.hh"
#include "sleigh_arch.hh"

using namespace ghidra;

namespace gd {

namespace {

std::string hex(uint64_t v) {
  std::ostringstream s;
  s << std::hex << v;
  return s.str();
}

/// Serves bytes straight from a read-only mmap of the file, laid out by the given segments.
class MappedImage : public LoadImage {
  const uint8_t *base_ = nullptr;
  size_t size_ = 0;
  std::vector<Segment> segs_;

  /// Index of the segment containing `a`, or -1.
  int find(uint64_t a) const {
    auto it = std::upper_bound(segs_.begin(), segs_.end(), a,
                               [](uint64_t v, const Segment &s) { return v < s.vaddr; });
    if (it == segs_.begin()) return -1;
    --it;
    if (a - it->vaddr < it->memsize) return int(it - segs_.begin());
    return -1;
  }

 public:
  MappedImage(const std::string &path, const std::vector<Segment> &segs) : LoadImage(path), segs_(segs) {
    std::sort(segs_.begin(), segs_.end(), [](const Segment &a, const Segment &b) { return a.vaddr < b.vaddr; });
  }

  ~MappedImage() override {
    if (base_ != nullptr) munmap(const_cast<uint8_t *>(base_), size_);
  }

  void open() {
    int fd = ::open(filename.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) throw LowlevelError("Cannot open " + filename);
    struct stat st {};
    if (fstat(fd, &st) != 0) {
      ::close(fd);
      throw LowlevelError("Cannot stat " + filename);
    }
    size_ = size_t(st.st_size);
    if (size_ > 0) {
      void *p = mmap(nullptr, size_, PROT_READ, MAP_PRIVATE, fd, 0);
      if (p == MAP_FAILED) {
        ::close(fd);
        throw LowlevelError("Cannot map " + filename);
      }
      base_ = static_cast<const uint8_t *>(p);
    }
    ::close(fd);
  }

  bool isMapped(uint64_t a) const { return find(a) >= 0; }

  bool isReadonly(uint64_t a) const {
    int i = find(a);
    return i >= 0 && (segs_[i].flags & SEG_WRITE) == 0;
  }

  bool isExecutable(uint64_t a) const {
    int i = find(a);
    return i >= 0 && (segs_[i].flags & SEG_EXEC) != 0;
  }

  uint64_t highest() const {
    uint64_t h = 0;
    for (const Segment &s : segs_) h = std::max(h, s.vaddr + s.memsize);
    return h;
  }

  void loadFill(uint1 *ptr, int4 size, const Address &addr) override {
    uint64_t start = addr.getOffset();
    bool any = false;
    int4 i = 0;
    while (i < size) {
      uint64_t a = start + uint64_t(i);
      int si = find(a);
      if (si < 0) {
        // Zero fill up to the next segment (or the end of the request).
        auto next = std::upper_bound(segs_.begin(), segs_.end(), a,
                                     [](uint64_t v, const Segment &s) { return v < s.vaddr; });
        uint64_t gap = next == segs_.end() ? uint64_t(size - i) : next->vaddr - a;
        int4 n = int4(std::min<uint64_t>(gap, uint64_t(size - i)));
        if (n <= 0) n = 1;
        std::memset(ptr + i, 0, size_t(n));
        i += n;
        continue;
      }
      any = true;
      const Segment &s = segs_[si];
      uint64_t segoff = a - s.vaddr;
      int4 n = int4(std::min<uint64_t>(s.memsize - segoff, uint64_t(size - i)));
      int4 fromFile = 0;
      if (segoff < s.filesize) {
        uint64_t fo = s.fileoff + segoff;
        uint64_t avail = fo < size_ ? size_ - fo : 0;
        fromFile = int4(std::min<uint64_t>(std::min<uint64_t>(s.filesize - segoff, avail), uint64_t(n)));
        if (fromFile > 0) std::memcpy(ptr + i, base_ + fo, size_t(fromFile));
      }
      if (fromFile < n) std::memset(ptr + i + fromFile, 0, size_t(n - fromFile));
      i += n;
    }
    if (!any) throw DataUnavailError("Unable to load bytes at 0x" + hex(start));
  }

  void getReadonly(RangeList &list) const override {
    if (space_ == nullptr) return;
    for (const Segment &s : segs_) {
      if ((s.flags & SEG_WRITE) == 0 && s.memsize > 0) list.insertRange(space_, s.vaddr, s.vaddr + s.memsize - 1);
    }
  }

  std::string getArchType() const override { return "mapped"; }
  void adjustVma(long) override {}

  AddrSpace *space_ = nullptr;
};

class GdArchitecture : public SleighArchitecture {
  std::vector<Segment> segs_;

  void buildLoader(DocumentStorage &store) override {
    collectSpecFiles(*errorstream);
    std::unique_ptr<MappedImage> ldr(new MappedImage(getFilename(), segs_));
    ldr->open();
    image = ldr.get();
    loader = ldr.release();
  }

  void resolveArchitecture() override {
    archid = getTarget();
    SleighArchitecture::resolveArchitecture();
  }

  void postSpecFile() override {
    Architecture::postSpecFile();
    image->space_ = getDefaultCodeSpace();
  }

  /// Core types named the way Ghidra's own decompiler window shows them.
  void buildCoreTypes(DocumentStorage &store) override {
    bool wide = false;
    {
      const std::string &t = getTarget();
      size_t p1 = t.find(':');
      size_t p2 = p1 == std::string::npos ? p1 : t.find(':', p1 + 1);
      size_t p3 = p2 == std::string::npos ? p2 : t.find(':', p2 + 1);
      if (p3 != std::string::npos) wide = std::atoi(t.substr(p2 + 1, p3 - p2 - 1).c_str()) >= 64;
    }
    types->setCoreType("void", 1, TYPE_VOID, false);
    types->setCoreType("bool", 1, TYPE_BOOL, false);
    types->setCoreType("byte", 1, TYPE_UINT, false);
    types->setCoreType("ushort", 2, TYPE_UINT, false);
    types->setCoreType("uint", 4, TYPE_UINT, false);
    types->setCoreType(wide ? "ulong" : "ulonglong", 8, TYPE_UINT, false);
    types->setCoreType("sbyte", 1, TYPE_INT, false);
    types->setCoreType("short", 2, TYPE_INT, false);
    types->setCoreType("int", 4, TYPE_INT, false);
    types->setCoreType(wide ? "long" : "longlong", 8, TYPE_INT, false);
    types->setCoreType("float", 4, TYPE_FLOAT, false);
    types->setCoreType("double", 8, TYPE_FLOAT, false);
    types->setCoreType("float10", 10, TYPE_FLOAT, false);
    types->setCoreType("float16", 16, TYPE_FLOAT, false);
    types->setCoreType("undefined", 1, TYPE_UNKNOWN, false);
    types->setCoreType("undefined2", 2, TYPE_UNKNOWN, false);
    types->setCoreType("undefined4", 4, TYPE_UNKNOWN, false);
    types->setCoreType("undefined8", 8, TYPE_UNKNOWN, false);
    types->setCoreType("code", 1, TYPE_CODE, false);
    types->setCoreType("char", 1, TYPE_INT, true);
    types->setCoreType("wchar16", 2, TYPE_INT, true);
    types->setCoreType("wchar32", 4, TYPE_INT, true);
    types->cacheCoreTypes();
  }

 public:
  void nameFunction(const Address &addr, std::string &name) const override {
    std::ostringstream s;
    s << "FUN_" << std::hex << std::setfill('0') << std::setw(addr.getSpace()->getAddrSize() * 2) << addr.getOffset();
    name = s.str();
  }

 public:
  MappedImage *image = nullptr;

  GdArchitecture(const std::string &path, const std::string &target, const std::vector<Segment> &segs,
                 std::ostream *err)
      : SleighArchitecture(path, target, err), segs_(segs) {}

  static void collect(std::ostream &errs) { collectSpecFiles(errs); }

  /// Lets constants anywhere in the image be taken as pointers. The decompiler's default ignores
  /// the first page, where small images based at 0 (most .so files) keep .rodata.
  void inferPointersWithin(uint64_t lo, uint64_t hi) {
    AddrSpace *spaces[2] = {getDefaultDataSpace(), getDefaultCodeSpace()};
    for (AddrSpace *spc : spaces) {
      if (spc == nullptr || hi <= lo) continue;
      uint64_t top = std::min<uint64_t>(hi - 1, spc->getHighest());
      if (lo < spc->getPointerLowerBound()) setInferPtrBounds(Range(spc, lo, std::max<uint64_t>(top, spc->getPointerUpperBound())));
      if (spc == spaces[1] && spaces[0] == spaces[1]) break;
    }
  }
};

/// PrintLanguage keeps its emitter protected; a pointer-to-member formed through a derived class
/// reaches it without patching the header.
struct PrintAccess : public PrintLanguage {
  static Emit *emitter(PrintLanguage *p) { return p->*(&PrintAccess::emit); }
};

/// Low-level emitter that writes plain text plus in-band markup the app turns into styled spans:
///   \x01 <hl> text \x02                 highlighted token, hl = 'A' + Emit::syntax_highlight
///   \x05 <hl> hexaddr \x06 text \x02    highlighted token that refers to an address
///   \x03 hexaddr \x03                   address of the statement that starts here
class EmitColor : public Emit {
  std::ostream *s_ = nullptr;

  void tok(const std::string &text, syntax_highlight hl) {
    if (hl == no_color) {
      *s_ << text;
      return;
    }
    *s_ << '\x01' << char('A' + int(hl)) << text << '\x02';
  }

  void tokAddr(const std::string &text, syntax_highlight hl, uint64_t addr) {
    *s_ << '\x05' << char('A' + int(hl)) << std::hex << addr << std::dec << '\x06' << text << '\x02';
  }

 public:
  int4 beginDocument() override { return 0; }
  void endDocument(int4) override {}
  int4 beginFunction(const Funcdata *) override { return 0; }
  void endFunction(int4) override {}
  int4 beginBlock(const FlowBlock *) override { return 0; }
  void endBlock(int4) override {}
  void tagLine() override {
    *s_ << '\n';
    for (int4 i = indentlevel; i > 0; --i) *s_ << ' ';
  }
  void tagLine(int4 indent) override {
    *s_ << '\n';
    for (int4 i = indent; i > 0; --i) *s_ << ' ';
  }
  int4 beginReturnType(const Varnode *) override { return 0; }
  void endReturnType(int4) override {}
  int4 beginVarDecl(const Symbol *) override { return 0; }
  void endVarDecl(int4) override {}
  int4 beginStatement(const PcodeOp *op) override {
    if (op != nullptr) *s_ << '\x03' << std::hex << op->getAddr().getOffset() << std::dec << '\x03';
    return 0;
  }
  void endStatement(int4) override {}
  int4 beginFuncProto() override { return 0; }
  void endFuncProto(int4) override {}
  void tagVariable(const std::string &name, syntax_highlight hl, const Varnode *vn, const PcodeOp *) override {
    if (hl == global_color && vn != nullptr && vn->getSpace()->getType() == IPTR_PROCESSOR)
      tokAddr(name, hl, vn->getOffset());
    else
      tok(name, hl);
  }
  void tagOp(const std::string &name, syntax_highlight hl, const PcodeOp *) override { tok(name, hl); }
  void tagFuncName(const std::string &name, syntax_highlight hl, const Funcdata *fd, const PcodeOp *) override {
    if (fd != nullptr)
      tokAddr(name, hl, fd->getAddress().getOffset());
    else
      tok(name, hl);
  }
  void tagType(const std::string &name, syntax_highlight hl, const Datatype *) override { tok(name, hl); }
  void tagField(const std::string &name, syntax_highlight hl, const Datatype *, int4, const PcodeOp *) override {
    tok(name, hl);
  }
  void tagBitField(const std::string &name, syntax_highlight hl, const Datatype *, int4, const PcodeOp *) override {
    tok(name, hl);
  }
  void tagComment(const std::string &name, syntax_highlight hl, const AddrSpace *, uintb) override { tok(name, hl); }
  void tagLabel(const std::string &name, syntax_highlight hl, const AddrSpace *spc, uintb off) override {
    if (spc != nullptr && spc->getType() == IPTR_PROCESSOR)
      tokAddr(name, hl, off);
    else
      tok(name, hl);
  }
  void tagCaseLabel(const std::string &name, syntax_highlight hl, const PcodeOp *, uintb) override { tok(name, hl); }
  void print(const std::string &data, syntax_highlight hl) override { tok(data, hl); }
  int4 openParen(const std::string &paren, int4 id) override {
    *s_ << paren;
    parenlevel += 1;
    return id;
  }
  void closeParen(const std::string &paren, int4) override {
    *s_ << paren;
    parenlevel -= 1;
  }
  void setOutputStream(std::ostream *t) override { s_ = t; }
  std::ostream *getOutputStream() const override { return s_; }
  bool emitsMarkup() const override { return false; }
};

class AsmCollector : public AssemblyEmit {
 public:
  std::string mnem;
  std::string body;
  void dump(const Address &, const std::string &m, const std::string &b) override {
    mnem = m;
    body = b;
  }
};

/// Per-instruction p-code analysis: control-flow classification, data references and a small
/// constant propagator that carries register values across straight-line code (adrp+add,
/// movw+movt, literal pools, pc-relative arithmetic) and remembers which memory slot an indirect
/// branch target was loaded from (PLT / thunk resolution).
class InsnAnalyzer : public PcodeEmit {
  struct Key {
    AddrSpace *spc;
    uint64_t off;
    bool operator<(const Key &o) const { return spc != o.spc ? spc < o.spc : off < o.off; }
  };
  struct Val {
    uint64_t v;
    bool derived;  // computed from tracked state rather than an instruction immediate
  };
  std::map<Key, Val> known_;
  std::map<Key, uint64_t> loaded_;
  const MappedImage *img_;
  AddrSpace *code_;
  AddrSpace *data_;
  bool bigEndian_;
  bool hasCall_ = false, hasReturn_ = false, hasCBranch_ = false, hasBranch_ = false, hasIJump_ = false,
       hasICall_ = false;
  uint64_t callTarget_ = NO_ADDR, branchTarget_ = NO_ADDR, memRef_ = NO_ADDR, constRef_ = NO_ADDR,
           valueRef_ = NO_ADDR;

  static uint64_t mask(int4 size, uint64_t v) { return size >= 8 ? v : v & ((uint64_t(1) << (size * 8)) - 1); }

  bool val(const VarnodeData &v, uint64_t &out, bool *derived = nullptr) const {
    if (v.space->getType() == IPTR_CONSTANT) {
      out = v.offset;
      if (derived) *derived = false;
      return true;
    }
    auto it = known_.find(Key{v.space, v.offset});
    if (it == known_.end()) return false;
    out = it->second.v;
    if (derived) *derived = it->second.derived;
    return true;
  }

  bool isMemory(const VarnodeData &v) const { return v.space == data_ || v.space == code_; }

  bool loadedFrom(const VarnodeData &v, uint64_t &out) const {
    if (isMemory(v)) {
      out = v.offset;
      return true;
    }
    auto it = loaded_.find(Key{v.space, v.offset});
    if (it == loaded_.end()) return false;
    out = it->second;
    return true;
  }

  void clear(const VarnodeData *o) {
    if (o == nullptr) return;
    known_.erase(Key{o->space, o->offset});
    loaded_.erase(Key{o->space, o->offset});
  }

  void setKnown(const VarnodeData *o, uint64_t v, bool derived) {
    if (o == nullptr || isMemory(*o)) return;
    v = mask(o->size, v);
    known_[Key{o->space, o->offset}] = Val{v, derived};
    if (o->space->getType() != IPTR_INTERNAL && o->size >= 2) {
      if (v == watch) hit = true;
      if (img_->isMapped(v) && (derived || v >= 0x1000)) valueRef_ = v;
    }
  }

  void setLoaded(const VarnodeData *o, uint64_t slot) {
    if (o == nullptr || isMemory(*o)) return;
    loaded_[Key{o->space, o->offset}] = slot;
  }

  /// Reads a value from read-only memory (literal pools, constant tables).
  bool readConst(uint64_t addr, int4 size, uint64_t &out) const {
    if (size <= 0 || size > 8 || !img_->isReadonly(addr) || !img_->isReadonly(addr + uint64_t(size) - 1)) return false;
    uint1 buf[8];
    try {
      const_cast<MappedImage *>(img_)->loadFill(buf, size, Address(code_, addr));
    } catch (LowlevelError &) {
      return false;
    }
    uint64_t r = 0;
    for (int4 i = 0; i < size; ++i) {
      int4 idx = bigEndian_ ? i : size - 1 - i;
      r = (r << 8) | buf[idx];
    }
    out = r;
    return true;
  }

  void binary(OpCode opc, const VarnodeData *out, const VarnodeData *vars, int4 isize) {
    uint64_t a = 0, b = 0;
    bool da = false, db = false;
    if (isize != 2 || !val(vars[0], a, &da) || !val(vars[1], b, &db)) {
      // Masking the mode bit of a loaded code pointer (ARM interworking) keeps the slot.
      uint64_t slot = 0, c = 0;
      if (isize == 2 && (opc == CPUI_INT_AND || opc == CPUI_INT_OR) &&
          ((loadedFrom(vars[0], slot) && val(vars[1], c)) || (loadedFrom(vars[1], slot) && val(vars[0], c)))) {
        clear(out);
        setLoaded(out, slot);
        return;
      }
      clear(out);
      return;
    }
    uint64_t r;
    switch (opc) {
      case CPUI_INT_ADD:
      case CPUI_PTRSUB: r = a + b; break;
      case CPUI_INT_SUB: r = a - b; break;
      case CPUI_INT_AND: r = a & b; break;
      case CPUI_INT_OR: r = a | b; break;
      case CPUI_INT_XOR: r = a ^ b; break;
      case CPUI_INT_MULT: r = a * b; break;
      case CPUI_INT_LEFT: if (b >= 64) { clear(out); return; } r = a << b; break;
      case CPUI_INT_RIGHT: if (b >= 64) { clear(out); return; } r = a >> b; break;
      case CPUI_SUBPIECE: if (b >= 8) { clear(out); return; } r = a >> (b * 8); break;
      default: clear(out); return;
    }
    clear(out);
    setKnown(out, r, da || db || vars[0].space->getType() != IPTR_CONSTANT || vars[1].space->getType() != IPTR_CONSTANT);
  }

 public:
  // Results for the current instruction.
  int32_t flow = FLOW_NONE;
  uint64_t target = NO_ADDR;
  uint64_t ref = NO_ADDR;
  uint64_t slot = NO_ADDR;  // memory slot an indirect branch went through
  bool stop = false;        // a branch, call or return was seen
  // Reference search.
  uint64_t watch = NO_ADDR;
  bool hit = false;

  InsnAnalyzer(const MappedImage *img, AddrSpace *code, AddrSpace *data)
      : img_(img), code_(code), data_(data), bigEndian_(code->isBigEndian()) {}

  void resetState() {
    known_.clear();
    loaded_.clear();
  }

  void beginInsn() {
    flow = FLOW_NONE;
    target = ref = slot = NO_ADDR;
    stop = hit = false;
    hasCall_ = hasReturn_ = hasCBranch_ = hasBranch_ = hasIJump_ = hasICall_ = false;
    callTarget_ = branchTarget_ = memRef_ = constRef_ = valueRef_ = NO_ADDR;
  }

  void endInsn(uint64_t fallthrough) {
    if (hasCall_) { flow = FLOW_CALL; target = callTarget_; }
    else if (hasICall_) flow = FLOW_ICALL;
    else if (hasReturn_) flow = FLOW_RETURN;
    else if (hasCBranch_) { flow = FLOW_CJUMP; target = branchTarget_; }
    else if (hasBranch_) { flow = FLOW_JUMP; target = branchTarget_; }
    else if (hasIJump_) flow = FLOW_IJUMP;
    ref = memRef_ != NO_ADDR ? memRef_ : (valueRef_ != NO_ADDR ? valueRef_ : constRef_);
    if (ref == target || ref == fallthrough) ref = NO_ADDR;
    if (target != NO_ADDR && target == watch) hit = true;
    // Temporaries never outlive an instruction; registers do until control flow merges or a call clobbers them.
    if (flow != FLOW_NONE) {
      resetState();
    } else {
      for (auto it = known_.begin(); it != known_.end();)
        it = it->first.spc->getType() == IPTR_INTERNAL ? known_.erase(it) : std::next(it);
      for (auto it = loaded_.begin(); it != loaded_.end();)
        it = it->first.spc->getType() == IPTR_INTERNAL ? loaded_.erase(it) : std::next(it);
    }
  }

  void dump(const Address &, OpCode opc, VarnodeData *out, VarnodeData *vars, int4 isize) override {
    int4 first = 0;
    uint64_t a = 0;
    switch (opc) {
      case CPUI_CALL:
        if (isize > 0 && vars[0].space == code_) {
          hasCall_ = true;
          if (callTarget_ == NO_ADDR) callTarget_ = vars[0].offset;
        }
        stop = true;
        first = 1;
        break;
      case CPUI_BRANCH:
      case CPUI_CBRANCH:
        first = 1;
        if (isize > 0 && vars[0].space->getType() == IPTR_CONSTANT) break;  // p-code relative
        if (isize > 0 && vars[0].space == code_) {
          if (opc == CPUI_CBRANCH) hasCBranch_ = true; else hasBranch_ = true;
          if (branchTarget_ == NO_ADDR) branchTarget_ = vars[0].offset;
        }
        stop = true;
        break;
      case CPUI_BRANCHIND:
      case CPUI_CALLIND:
        if (opc == CPUI_BRANCHIND) hasIJump_ = true; else hasICall_ = true;
        if (isize > 0 && loadedFrom(vars[0], a)) slot = a;
        stop = true;
        break;
      case CPUI_RETURN:
        hasReturn_ = true;
        stop = true;
        break;
      default:
        break;
    }
    for (int4 i = first; i < isize; ++i) {
      const VarnodeData &v = vars[i];
      if (v.space == nullptr) continue;
      if (isMemory(v)) {
        if (memRef_ == NO_ADDR && img_->isMapped(v.offset)) memRef_ = v.offset;
        if (v.offset == watch) hit = true;
      } else if (v.space->getType() == IPTR_CONSTANT && v.size >= 2) {
        if (constRef_ == NO_ADDR && v.offset >= 0x1000 && img_->isMapped(v.offset)) constRef_ = v.offset;
        if (v.offset == watch && !(opc == CPUI_LOAD && i == 0) && !(opc == CPUI_STORE && i == 0)) hit = true;
      }
    }
    switch (opc) {
      case CPUI_COPY:
      case CPUI_INT_ZEXT:
      case CPUI_INT_SEXT:
      case CPUI_CAST: {
        uint64_t v = 0;
        bool d = false;
        clear(out);
        if (val(vars[0], v, &d)) {
          setKnown(out, v, d);
        } else if (loadedFrom(vars[0], a)) {
          setLoaded(out, a);
          if (out != nullptr && readConst(a, out->size, v)) setKnown(out, v, true);
        }
        break;
      }
      case CPUI_INT_ADD:
      case CPUI_PTRSUB:
      case CPUI_INT_SUB:
      case CPUI_INT_AND:
      case CPUI_INT_OR:
      case CPUI_INT_XOR:
      case CPUI_INT_MULT:
      case CPUI_INT_LEFT:
      case CPUI_INT_RIGHT:
      case CPUI_SUBPIECE:
        binary(opc, out, vars, isize);
        break;
      case CPUI_LOAD: {
        clear(out);
        uint64_t p = 0, v = 0;
        if (isize == 2 && val(vars[1], p)) {
          if (p == watch) hit = true;
          setLoaded(out, p);
          if (out != nullptr && readConst(p, out->size, v)) setKnown(out, v, true);
        }
        break;
      }
      default:
        if (out != nullptr) clear(out);
        break;
    }
  }
};

bool g_inited = false;

}  // namespace

class SessionImpl {
 public:
  std::ostringstream errs;
  std::unique_ptr<GdArchitecture> arch;
  AddrSpace *space = nullptr;
  uint64_t externBase = 0;
  uint64_t externNext = 0;
};

bool initLibrary(const std::string &specRoot, std::string &err) {
  try {
    if (!g_inited) {
      AttributeId::initialize();
      ElementId::initialize();
      CapabilityPoint::initializeAll();
      ArchitectureCapability::sortCapabilities();
      std::vector<std::string> dirs;
      FileManage::directoryList(dirs, specRoot);
      std::sort(dirs.begin(), dirs.end());
      for (const std::string &d : dirs) SleighArchitecture::specpaths.addDir2Path(d);
      std::ostringstream errs;
      GdArchitecture::collect(errs);
      g_inited = true;
    }
    return true;
  } catch (LowlevelError &e) {
    err = e.explain;
  } catch (DecoderError &e) {
    err = e.explain;
  } catch (std::exception &e) {
    err = e.what();
  }
  return false;
}

std::vector<LanguageInfo> languages() {
  std::vector<LanguageInfo> out;
  for (const LanguageDescription &d : SleighArchitecture::getDescriptions()) {
    LanguageInfo li;
    li.id = d.getId();
    li.processor = d.getProcessor();
    li.variant = d.getVariant();
    li.version = d.getVersion();
    li.description = d.getDescription();
    li.slafile = d.getSlaFile();
    li.bigEndian = d.isBigEndian();
    li.size = d.getSize();
    li.deprecated = d.isDeprecated();
    for (int4 i = 0; i < d.numCompilers(); ++i) {
      li.compilerIds.push_back(d.getCompiler(i).getId());
      li.compilerNames.push_back(d.getCompiler(i).getName());
    }
    out.push_back(std::move(li));
  }
  return out;
}

std::string demangle(const std::string &mangled) {
  int status = 0;
  char *res = abi::__cxa_demangle(mangled.c_str(), nullptr, nullptr, &status);
  if (status != 0 || res == nullptr) {
    std::free(res);
    return std::string();
  }
  std::string s(res);
  std::free(res);
  return s;
}

Session::Session(SessionImpl *impl) : impl_(impl) {}

Session::~Session() { delete impl_; }

std::unique_ptr<Session> Session::open(const std::string &path, const std::string &target,
                                       const std::vector<Segment> &segs, const std::vector<ContextDef> &ctx,
                                       std::string &err) {
  std::unique_ptr<SessionImpl> impl(new SessionImpl());
  try {
    impl->arch.reset(new GdArchitecture(path, target, segs, &impl->errs));
    DocumentStorage store;
    impl->arch->init(store);
    impl->space = impl->arch->getDefaultCodeSpace();

    // Swap the pretty printer's back end for the markup emitter.
    Emit *emit = PrintAccess::emitter(impl->arch->print);
    if (auto *pretty = dynamic_cast<EmitPrettyPrint *>(emit)) pretty->setLowLevel(new EmitColor());

    for (const ContextDef &c : ctx) {
      try {
        Address begin(impl->space, c.start);
        Address end = c.end == NO_ADDR ? Address() : Address(impl->space, c.end);
        impl->arch->context->setVariableRegion(c.var, begin, end, c.value);
      } catch (LowlevelError &) {
        // Variable not defined for this language: ignore.
      }
    }
    uint64_t lowest = NO_ADDR;
    for (const Segment &sg : segs) lowest = std::min(lowest, sg.vaddr);
    if (lowest != NO_ADDR) impl->arch->inferPointersWithin(std::max<uint64_t>(lowest, 0x10), impl->arch->image->highest());

    // Imports resolve to placeholder functions placed past the end of the image.
    uint64_t top = impl->arch->image->highest();
    uint64_t base = (top + 0xFFFF) & ~uint64_t(0xFFFF);
    uint64_t mask = impl->space->getHighest();
    if (base + 0x100000 > mask) base = mask - 0x100000;
    impl->externBase = impl->externNext = base + 0x1000;
    return std::unique_ptr<Session>(new Session(impl.release()));
  } catch (LowlevelError &e) {
    err = e.explain;
  } catch (DecoderError &e) {
    err = e.explain;
  } catch (std::exception &e) {
    err = e.what();
  }
  std::string log = impl->errs.str();
  if (!log.empty()) err += "\n" + log;
  return nullptr;
}

void Session::addSymbols(const std::vector<SymbolDef> &syms) {
  Architecture *arch = impl_->arch.get();
  Scope *global = arch->symboltab->getGlobalScope();
  AddrSpace *spc = impl_->space;
  uint64_t step = uint64_t(std::max(4, int(spc->getAddrSize())));
  for (const SymbolDef &s : syms) {
    if (s.name.empty()) continue;
    try {
      Address addr(spc, s.addr);
      switch (s.kind) {
        case SYM_FUNCTION:
          if (global->findFunction(addr) == nullptr) global->addFunction(addr, s.name);
          break;
        case SYM_LABEL:
          global->addCodeLabel(addr, s.name);
          break;
        case SYM_DATA: {
          Datatype *ct = nullptr;
          if (s.size == 1 || s.size == 2 || s.size == 4 || s.size == 8)
            ct = arch->types->getBase(int4(s.size), TYPE_UNKNOWN);
          else if (s.size > 8 && s.size <= (1u << 20))
            ct = arch->types->getTypeArray(int4(s.size), arch->types->getBase(1, TYPE_UNKNOWN));
          if (ct != nullptr) global->addSymbol(s.name, ct, addr, Address());
          break;
        }
        case SYM_STRING: {
          if (s.size < 2 || s.size > 4096) break;
          Datatype *ct = arch->types->getTypeArray(int4(s.size), arch->types->getTypeChar(1));
          global->addSymbol(s.name, ct, addr, Address());
          break;
        }
        case SYM_IMPORT: {
          Address ref(spc, impl_->externNext);
          impl_->externNext += step;
          global->addFunction(ref, s.name);
          global->addExternalRef(addr, ref, s.name);
          break;
        }
        default:
          break;
      }
    } catch (LowlevelError &) {
      // Duplicate or conflicting symbol: keep the first one.
    }
  }
}

std::vector<Insn> Session::disassemble(uint64_t addr, int32_t maxCount, uint64_t endAddr) {
  std::vector<Insn> out;
  Architecture *arch = impl_->arch.get();
  const Translate *trans = arch->translate;
  MappedImage *img = impl_->arch->image;
  int32_t align = std::max(1, trans->getAlignment());
  AsmCollector asmc;
  InsnAnalyzer an(img, impl_->space, arch->getDefaultDataSpace());
  uint64_t a = addr;
  while (int32_t(out.size()) < maxCount && a < endAddr) {
    Insn in;
    in.addr = a;
    in.target = NO_ADDR;
    in.ref = NO_ADDR;
    in.flow = FLOW_NONE;
    Address at(impl_->space, a);
    if (!img->isMapped(a)) break;
    try {
      in.length = trans->printAssembly(asmc, at);
      in.mnem = asmc.mnem;
      in.body = asmc.body;
    } catch (LowlevelError &) {
      in.length = align;
      in.flow = FLOW_INVALID;
      uint1 b[8] = {0};
      try {
        img->loadFill(b, align, at);
      } catch (LowlevelError &) {
      }
      std::ostringstream s;
      s << std::hex;
      for (int32_t i = 0; i < align; ++i) s << (i ? "," : "") << "0x" << int(b[i]);
      in.mnem = ".byte";
      in.body = s.str();
    }
    if (in.length <= 0) in.length = align;
    if (in.flow != FLOW_INVALID) {
      an.beginInsn();
      try {
        trans->oneInstruction(an, at);
      } catch (LowlevelError &) {
      }
      an.endInsn(a + uint64_t(in.length));
      in.flow = an.flow;
      in.target = an.target;
      in.ref = an.ref;
    } else {
      an.resetState();
    }
    out.push_back(std::move(in));
    a += uint64_t(out.back().length);
    if (a < addr) break;  // wrapped
  }
  return out;
}

bool Session::decompile(uint64_t addr, const DecompileOptions &opt, std::string &out, std::string &err) {
  Architecture *arch = impl_->arch.get();
  impl_->errs.str("");
  try {
    Scope *global = arch->symboltab->getGlobalScope();
    Address a(impl_->space, addr);
    Funcdata *fd = global->queryFunction(a);
    if (fd == nullptr) {
      std::string name;
      arch->nameFunction(a, name);
      fd = global->addFunction(a, name)->getFunction();
    }
    if (fd->isProcStarted()) arch->clearAnalysis(fd);
    arch->readonlypropagate = opt.readonlyPropagate;
    Action *act = arch->allacts.getCurrent();
    act->reset(*fd);
    int4 res = act->perform(*fd);
    std::ostringstream s;
    arch->print->setOutputStream(&s);
    arch->print->setMaxLineSize(std::max(40, std::min(400, int(opt.maxLineWidth))));
    arch->print->setIndentIncrement(std::max(1, std::min(8, int(opt.indent))));
    if (res < 0) s << "/* Decompilation interrupted */\n";
    arch->print->docFunction(fd);
    arch->print->setOutputStream(nullptr);
    out = s.str();
    arch->clearAnalysis(fd);
    return true;
  } catch (LowlevelError &e) {
    err = e.explain;
  } catch (DecoderError &e) {
    err = e.explain;
  } catch (std::exception &e) {
    err = e.what();
  }
  try {
    Funcdata *fd = arch->symboltab->getGlobalScope()->queryFunction(Address(impl_->space, addr));
    if (fd != nullptr && fd->isProcStarted()) arch->clearAnalysis(fd);
  } catch (...) {
  }
  std::string log = impl_->errs.str();
  if (!log.empty()) err += "\n" + log;
  return false;
}

/// Linear sweep helper: decodes [start, end) feeding every instruction to `an` and calling
/// `visit(address)` after each one.
template <typename F>
static void sweep(const Translate *trans, AddrSpace *space, InsnAnalyzer &an, uint64_t start, uint64_t end, F visit) {
  int32_t align = std::max(1, trans->getAlignment());
  uint64_t a = start;
  while (a < end) {
    int4 len;
    an.beginInsn();
    try {
      len = trans->oneInstruction(an, Address(space, a));
    } catch (UnimplError &e) {
      len = e.instruction_length;
    } catch (LowlevelError &) {
      len = 0;
    }
    if (len <= 0) {
      an.resetState();
      a += uint64_t(align);
      continue;
    }
    an.endInsn(a + uint64_t(len));
    visit(a);
    a += uint64_t(len);
  }
}

std::vector<uint64_t> Session::scanCalls(uint64_t start, uint64_t end) {
  std::vector<uint64_t> out;
  MappedImage *img = impl_->arch->image;
  InsnAnalyzer an(img, impl_->space, impl_->arch->getDefaultDataSpace());
  sweep(impl_->arch->translate, impl_->space, an, start, end, [&](uint64_t) {
    if (an.flow == FLOW_CALL && an.target != NO_ADDR && img->isExecutable(an.target)) out.push_back(an.target);
  });
  std::sort(out.begin(), out.end());
  out.erase(std::unique(out.begin(), out.end()), out.end());
  return out;
}

std::vector<uint64_t> Session::findRefs(uint64_t start, uint64_t end, uint64_t target) {
  std::vector<uint64_t> out;
  InsnAnalyzer an(impl_->arch->image, impl_->space, impl_->arch->getDefaultDataSpace());
  an.watch = target;
  sweep(impl_->arch->translate, impl_->space, an, start, end, [&](uint64_t a) {
    if (an.hit) out.push_back(a);
  });
  return out;
}

std::vector<std::pair<uint64_t, uint64_t>> Session::resolveStubs(uint64_t start, uint64_t end, uint32_t entryAlign) {
  std::vector<std::pair<uint64_t, uint64_t>> out;
  const Translate *trans = impl_->arch->translate;
  int32_t align = std::max(1, trans->getAlignment());
  InsnAnalyzer an(impl_->arch->image, impl_->space, impl_->arch->getDefaultDataSpace());
  uint64_t p = start;
  while (p < end) {
    an.resetState();
    uint64_t q = p, firstLen = 0, branchEnd = 0, slot = NO_ADDR;
    uint64_t starts[5];
    int count = 0;
    for (int n = 0; n < 5 && q < end; ++n) {
      starts[count++] = q;
      int4 len = 0;
      bool unimpl = false;
      an.beginInsn();
      try {
        len = trans->oneInstruction(an, Address(impl_->space, q));
      } catch (UnimplError &e) {
        len = e.instruction_length;
        unimpl = true;
      } catch (LowlevelError &) {
        len = 0;
      }
      if (len <= 0) break;
      if (n == 0) firstLen = uint64_t(len);
      q += uint64_t(len);
      bool stop = an.stop || unimpl;
      slot = an.slot;
      an.endInsn(q);
      if (stop) {
        branchEnd = q;
        break;
      }
    }
    if (slot != NO_ADDR && branchEnd > p) {
      // Padding before an entry ends up in the window: prefer the first aligned instruction start.
      uint64_t stub = p;
      if (entryAlign > 1) {
        for (int i = 0; i < count; ++i) {
          if (starts[i] % entryAlign == 0) {
            stub = starts[i];
            break;
          }
        }
      }
      out.emplace_back(stub, slot);
      p = branchEnd;
    } else {
      p += firstLen > 0 ? firstLen : uint64_t(align);
    }
  }
  return out;
}

int32_t Session::alignment() const { return std::max(1, impl_->arch->translate->getAlignment()); }

int32_t Session::pointerSize() const { return impl_->arch->getDefaultDataSpace()->getAddrSize(); }

std::string Session::languageDescription() const { return impl_->arch->getDescription(); }

}  // namespace gd
