package com.ghidroid.engine

/**
 * Raw JNI surface of libghidroid (Ghidra's SLEIGH + decompiler, see app/src/main/cpp).
 * Everything except [demangle] must run on [Engine]'s thread.
 */
object NativeBridge {
    init {
        System.loadLibrary("ghidroid")
    }

    /** Registers every language directory under [specRoot]. Returns null on success, else an error. */
    @JvmStatic external fun init(specRoot: String): String?

    /** One row per language: fields separated by \u0001 (see jni_bridge.cc). */
    @JvmStatic external fun languages(): Array<String>

    /** Itanium C++ demangling; empty string for names that are not mangled. Thread safe. */
    @JvmStatic external fun demangle(names: Array<String>): Array<String>

    @JvmStatic external fun lastError(): String

    /**
     * Opens a program. [segments] holds 5 longs per segment (vaddr, file offset, file size, memory
     * size, flags); [ctxValues] 3 longs per context variable in [ctxNames] (start, end, value).
     * Returns 0 on failure ([lastError]).
     */
    @JvmStatic external fun open(
        path: String,
        target: String,
        segments: LongArray,
        ctxNames: Array<String>,
        ctxValues: LongArray,
    ): Long

    @JvmStatic external fun close(handle: Long)

    @JvmStatic external fun addSymbols(
        handle: Long,
        addresses: LongArray,
        sizes: LongArray,
        kinds: IntArray,
        names: Array<String>,
    )

    /** [addresses, lengths, flows, targets, refs, mnemonics, operands] */
    @JvmStatic external fun disassemble(handle: Long, address: Long, maxCount: Int, endAddress: Long): Array<Any>

    @JvmStatic external fun decompile(
        handle: Long,
        address: Long,
        maxLineWidth: Int,
        indent: Int,
        readonlyPropagate: Boolean,
    ): String?

    @JvmStatic external fun scanCalls(handle: Long, start: Long, end: Long): LongArray

    @JvmStatic external fun findRefs(handle: Long, start: Long, end: Long, target: Long): LongArray

    /** Pairs of (stub address, pointer slot). */
    @JvmStatic external fun resolveStubs(handle: Long, start: Long, end: Long, entryAlign: Int): LongArray

    /** [instruction alignment, pointer size] */
    @JvmStatic external fun info(handle: Long): IntArray

    @JvmStatic external fun description(handle: Long): String

    const val SEG_EXEC = 1
    const val SEG_WRITE = 2
    const val SEG_READ = 4

    const val SYM_FUNCTION = 0
    const val SYM_LABEL = 1
    const val SYM_DATA = 2
    const val SYM_IMPORT = 3
    const val SYM_STRING = 4

    const val NO_ADDR = -1L
}
