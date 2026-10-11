// JNI surface for com.ghidroid.engine.NativeBridge. Every call is expected on the single engine
// thread the app creates (large stack, serialised access), so the error slot is thread local only
// for safety.
#include <jni.h>

#include <memory>
#include <string>
#include <vector>

#include "engine.hh"

namespace {

thread_local std::string g_error;

/// Lenient UTF-8 -> UTF-16 conversion: binaries are full of names and strings that are not valid
/// (modified) UTF-8, which NewStringUTF would reject.
jstring toJ(JNIEnv *env, const std::string &s) {
  std::vector<jchar> out;
  out.reserve(s.size());
  const auto *p = reinterpret_cast<const unsigned char *>(s.data());
  size_t n = s.size(), i = 0;
  while (i < n) {
    unsigned c = p[i];
    uint32_t cp;
    size_t len;
    if (c < 0x80) {
      cp = c;
      len = 1;
    } else if ((c & 0xE0) == 0xC0) {
      cp = c & 0x1F;
      len = 2;
    } else if ((c & 0xF0) == 0xE0) {
      cp = c & 0x0F;
      len = 3;
    } else if ((c & 0xF8) == 0xF0) {
      cp = c & 0x07;
      len = 4;
    } else {
      out.push_back(0xFFFD);
      ++i;
      continue;
    }
    bool ok = i + len <= n;
    for (size_t k = 1; ok && k < len; ++k) {
      if ((p[i + k] & 0xC0) != 0x80) ok = false;
      else cp = (cp << 6) | (p[i + k] & 0x3F);
    }
    if (!ok || (len == 2 && cp < 0x80) || (len == 3 && cp < 0x800) || (len == 4 && (cp < 0x10000 || cp > 0x10FFFF))) {
      out.push_back(0xFFFD);
      ++i;
      continue;
    }
    if (cp >= 0x10000) {
      cp -= 0x10000;
      out.push_back(jchar(0xD800 + (cp >> 10)));
      out.push_back(jchar(0xDC00 + (cp & 0x3FF)));
    } else {
      out.push_back(jchar(cp));
    }
    i += len;
  }
  return env->NewString(out.data(), jsize(out.size()));
}

std::string fromJ(JNIEnv *env, jstring s) {
  if (s == nullptr) return std::string();
  const char *c = env->GetStringUTFChars(s, nullptr);
  std::string r(c != nullptr ? c : "");
  if (c != nullptr) env->ReleaseStringUTFChars(s, c);
  return r;
}

jobjectArray stringArray(JNIEnv *env, const std::vector<std::string> &v) {
  jclass cls = env->FindClass("java/lang/String");
  jobjectArray arr = env->NewObjectArray(jsize(v.size()), cls, nullptr);
  for (size_t i = 0; i < v.size(); ++i) {
    jstring js = toJ(env, v[i]);
    env->SetObjectArrayElement(arr, jsize(i), js);
    env->DeleteLocalRef(js);
  }
  env->DeleteLocalRef(cls);
  return arr;
}

std::string join(const std::vector<std::string> &v, char sep) {
  std::string r;
  for (size_t i = 0; i < v.size(); ++i) {
    if (i) r += sep;
    r += v[i];
  }
  return r;
}

gd::Session *session(jlong h) { return reinterpret_cast<gd::Session *>(h); }

}  // namespace

#define JFN(ret, name) extern "C" JNIEXPORT ret JNICALL Java_com_ghidroid_engine_NativeBridge_##name

JFN(jstring, init)(JNIEnv *env, jclass, jstring specRoot) {
  std::string err;
  if (gd::initLibrary(fromJ(env, specRoot), err)) return nullptr;
  return toJ(env, err);
}

JFN(jobjectArray, languages)(JNIEnv *env, jclass) {
  std::vector<std::string> rows;
  for (const gd::LanguageInfo &l : gd::languages()) {
    std::string r;
    const char sep = '\x01';
    r += l.id; r += sep;
    r += l.processor; r += sep;
    r += l.variant; r += sep;
    r += l.version; r += sep;
    r += l.description; r += sep;
    r += l.slafile; r += sep;
    r += l.bigEndian ? "BE" : "LE"; r += sep;
    r += std::to_string(l.size); r += sep;
    r += l.deprecated ? "1" : "0"; r += sep;
    r += join(l.compilerIds, '\x02'); r += sep;
    r += join(l.compilerNames, '\x02');
    rows.push_back(std::move(r));
  }
  return stringArray(env, rows);
}

JFN(jobjectArray, demangle)(JNIEnv *env, jclass, jobjectArray names) {
  jsize n = env->GetArrayLength(names);
  std::vector<std::string> out(size_t(n > 0 ? n : 0));
  for (jsize i = 0; i < n; ++i) {
    auto js = static_cast<jstring>(env->GetObjectArrayElement(names, i));
    out[size_t(i)] = gd::demangle(fromJ(env, js));
    env->DeleteLocalRef(js);
  }
  return stringArray(env, out);
}

JFN(jstring, lastError)(JNIEnv *env, jclass) { return toJ(env, g_error); }

JFN(jlong, open)(JNIEnv *env, jclass, jstring path, jstring target, jlongArray segs, jobjectArray ctxNames,
                 jlongArray ctxVals) {
  std::vector<gd::Segment> segments;
  {
    jsize n = env->GetArrayLength(segs);
    std::vector<jlong> v(static_cast<size_t>(n));
    env->GetLongArrayRegion(segs, 0, n, v.data());
    for (jsize i = 0; i + 4 < n; i += 5) {
      segments.push_back(gd::Segment{uint64_t(v[i]), uint64_t(v[i + 1]), uint64_t(v[i + 2]), uint64_t(v[i + 3]),
                                     uint32_t(v[i + 4])});
    }
  }
  std::vector<gd::ContextDef> ctx;
  {
    jsize n = env->GetArrayLength(ctxNames);
    std::vector<jlong> v(size_t(n) * 3);
    if (n > 0) env->GetLongArrayRegion(ctxVals, 0, n * 3, v.data());
    for (jsize i = 0; i < n; ++i) {
      auto js = static_cast<jstring>(env->GetObjectArrayElement(ctxNames, i));
      ctx.push_back(gd::ContextDef{fromJ(env, js), uint64_t(v[size_t(i) * 3]), uint64_t(v[size_t(i) * 3 + 1]),
                                   uint32_t(v[size_t(i) * 3 + 2])});
      env->DeleteLocalRef(js);
    }
  }
  std::string err;
  std::unique_ptr<gd::Session> s = gd::Session::open(fromJ(env, path), fromJ(env, target), segments, ctx, err);
  if (!s) {
    g_error = err;
    return 0;
  }
  return reinterpret_cast<jlong>(s.release());
}

JFN(void, close)(JNIEnv *, jclass, jlong h) { delete session(h); }

JFN(void, addSymbols)(JNIEnv *env, jclass, jlong h, jlongArray addrs, jlongArray sizes, jintArray kinds,
                      jobjectArray names) {
  jsize n = env->GetArrayLength(addrs);
  std::vector<jlong> a(static_cast<size_t>(n)), sz(static_cast<size_t>(n));
  std::vector<jint> k(static_cast<size_t>(n));
  env->GetLongArrayRegion(addrs, 0, n, a.data());
  env->GetLongArrayRegion(sizes, 0, n, sz.data());
  env->GetIntArrayRegion(kinds, 0, n, k.data());
  std::vector<gd::SymbolDef> syms;
  syms.reserve(static_cast<size_t>(n));
  for (jsize i = 0; i < n; ++i) {
    auto js = static_cast<jstring>(env->GetObjectArrayElement(names, i));
    syms.push_back(gd::SymbolDef{uint64_t(a[size_t(i)]), uint64_t(sz[size_t(i)]), k[size_t(i)], fromJ(env, js)});
    env->DeleteLocalRef(js);
  }
  session(h)->addSymbols(syms);
}

JFN(jobjectArray, disassemble)(JNIEnv *env, jclass, jlong h, jlong addr, jint maxCount, jlong endAddr) {
  std::vector<gd::Insn> v = session(h)->disassemble(uint64_t(addr), maxCount, uint64_t(endAddr));
  jsize n = jsize(v.size());
  std::vector<jlong> addrs(v.size()), targets(v.size()), refs(v.size());
  std::vector<jint> lens(v.size()), flows(v.size());
  std::vector<std::string> mnems(v.size()), bodies(v.size());
  for (size_t i = 0; i < v.size(); ++i) {
    addrs[i] = jlong(v[i].addr);
    targets[i] = jlong(v[i].target);
    refs[i] = jlong(v[i].ref);
    lens[i] = v[i].length;
    flows[i] = v[i].flow;
    mnems[i] = std::move(v[i].mnem);
    bodies[i] = std::move(v[i].body);
  }
  jclass objCls = env->FindClass("java/lang/Object");
  jobjectArray res = env->NewObjectArray(7, objCls, nullptr);
  auto putLongs = [&](int idx, const std::vector<jlong> &d) {
    jlongArray arr = env->NewLongArray(n);
    env->SetLongArrayRegion(arr, 0, n, d.data());
    env->SetObjectArrayElement(res, idx, arr);
    env->DeleteLocalRef(arr);
  };
  auto putInts = [&](int idx, const std::vector<jint> &d) {
    jintArray arr = env->NewIntArray(n);
    env->SetIntArrayRegion(arr, 0, n, d.data());
    env->SetObjectArrayElement(res, idx, arr);
    env->DeleteLocalRef(arr);
  };
  putLongs(0, addrs);
  putInts(1, lens);
  putInts(2, flows);
  putLongs(3, targets);
  putLongs(4, refs);
  jobjectArray m = stringArray(env, mnems);
  env->SetObjectArrayElement(res, 5, m);
  env->DeleteLocalRef(m);
  jobjectArray b = stringArray(env, bodies);
  env->SetObjectArrayElement(res, 6, b);
  env->DeleteLocalRef(b);
  env->DeleteLocalRef(objCls);
  return res;
}

JFN(jstring, decompile)(JNIEnv *env, jclass, jlong h, jlong addr, jint maxLineWidth, jint indent,
                        jboolean readonlyPropagate) {
  gd::DecompileOptions opt;
  opt.maxLineWidth = maxLineWidth;
  opt.indent = indent;
  opt.readonlyPropagate = readonlyPropagate == JNI_TRUE;
  std::string out, err;
  if (!session(h)->decompile(uint64_t(addr), opt, out, err)) {
    g_error = err;
    return nullptr;
  }
  return toJ(env, out);
}

JFN(jlongArray, scanCalls)(JNIEnv *env, jclass, jlong h, jlong start, jlong end) {
  std::vector<uint64_t> v = session(h)->scanCalls(uint64_t(start), uint64_t(end));
  jlongArray arr = env->NewLongArray(jsize(v.size()));
  if (!v.empty()) env->SetLongArrayRegion(arr, 0, jsize(v.size()), reinterpret_cast<const jlong *>(v.data()));
  return arr;
}

JFN(jlongArray, findRefs)(JNIEnv *env, jclass, jlong h, jlong start, jlong end, jlong target) {
  std::vector<uint64_t> v = session(h)->findRefs(uint64_t(start), uint64_t(end), uint64_t(target));
  jlongArray arr = env->NewLongArray(jsize(v.size()));
  if (!v.empty()) env->SetLongArrayRegion(arr, 0, jsize(v.size()), reinterpret_cast<const jlong *>(v.data()));
  return arr;
}

JFN(jlongArray, resolveStubs)(JNIEnv *env, jclass, jlong h, jlong start, jlong end, jint align) {
  auto v = session(h)->resolveStubs(uint64_t(start), uint64_t(end), uint32_t(align));
  std::vector<jlong> flat;
  flat.reserve(v.size() * 2);
  for (auto &p : v) {
    flat.push_back(jlong(p.first));
    flat.push_back(jlong(p.second));
  }
  jlongArray arr = env->NewLongArray(jsize(flat.size()));
  if (!flat.empty()) env->SetLongArrayRegion(arr, 0, jsize(flat.size()), flat.data());
  return arr;
}

JFN(jintArray, info)(JNIEnv *env, jclass, jlong h) {
  jint v[2] = {session(h)->alignment(), session(h)->pointerSize()};
  jintArray arr = env->NewIntArray(2);
  env->SetIntArrayRegion(arr, 0, 2, v);
  return arr;
}

JFN(jstring, description)(JNIEnv *env, jclass, jlong h) { return toJ(env, session(h)->languageDescription()); }
