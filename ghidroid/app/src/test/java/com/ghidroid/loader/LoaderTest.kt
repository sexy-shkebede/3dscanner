package com.ghidroid.loader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LoaderTest {
    private fun load(name: String): LoadedBinary = Bin.open(fixture(name)).use { Loaders.load(it) }

    private fun LoadedBinary.function(name: String): BinSymbol? = symbols.firstOrNull { it.name == name && it.type == SymbolType.FUNCTION }

    @Test
    fun elfX86_64() {
        val b = load("sample-x86_64.elf")
        assertEquals(BinaryFormat.ELF, b.format)
        assertEquals("ELF64", b.formatName)
        assertEquals("x86-64", b.machine)
        assertEquals("x86:LE:64:default", b.languageIds.first())
        assertEquals("gcc", b.compiler)
        assertEquals(0x10c0L, b.function("main")?.address)
        assertEquals(0x12a0L, b.function("sum_list")?.address)
        val slots = b.imports.associate { it.name to it.slot }
        assertEquals(0x3fb8L, slots["puts"])
        assertEquals(0x3fc0L, slots["strlen"])
        assertTrue(b.stubRanges.any { it.start == 0x1080L })  // .plt.sec
        assertTrue(b.info.any { it.first == "Build ID" })
        assertEquals(listOf(0x11b0L), b.entryPoints)
    }

    @Test
    fun elfArmThumb() {
        val b = load("libsample-armv7.so.bin")
        assertEquals("ARM", b.machine)
        assertEquals(0x4acL, b.function("main")?.address)  // Thumb bit stripped
        assertEquals(0x16acL, b.imports.first { it.name == "puts" }.slot)
        assertTrue(b.libraries.contains("libc.so"))
        // Thumb by default, ARM for the PLT.
        val plt = b.stubRanges.single()
        assertTrue(b.contexts.any { it.variable == "TMode" && it.value == 1 && it.start == 0x4acL })
        assertTrue(b.contexts.last { it.start == plt.start }.value == 0)
    }

    @Test
    fun elfArm64() {
        val b = load("libsample-arm64.so.bin")
        assertEquals("AArch64", b.machine)
        assertEquals("AARCH64:LE:64:v8A", b.languageIds.first())
        assertEquals(0x738L, b.function("main")?.address)
        assertEquals(0x4a68L, b.imports.first { it.name == "puts" }.slot)
    }

    @Test
    fun peDll() {
        val b = load("sample-x64.dll")
        assertEquals(BinaryFormat.PE, b.format)
        assertEquals("PE32+", b.formatName)
        assertEquals("DLL", b.fileType)
        val exports = b.symbols.filter { it.exported }.map { it.name }.toSet()
        assertEquals(setOf("checksum", "pick"), exports)
        val imp = b.imports.associateBy { it.name }
        assertEquals("kernel32.dll", imp["lstrlenA"]?.library)
        assertNotNull(imp["OutputDebugStringA"])
        assertTrue(b.functionStarts.isNotEmpty())  // .pdata
        assertTrue(b.libraries.contains("kernel32.dll"))
    }

    @Test
    fun machODylib() {
        val b = load("libsample-arm64.dylib")
        assertEquals(BinaryFormat.MACHO, b.format)
        assertEquals("ARM64", b.machine)
        assertEquals("Dynamic library", b.fileType)
        assertEquals(0x510L, b.function("sum_list")?.address)
        assertEquals(0x530L, b.function("build")?.address)
        val stubs = b.stubs.associate { it.name to it.address }
        assertEquals(0x5d4L, stubs["strlen"])
        assertEquals(0x5c8L, stubs["malloc"])
        assertTrue(b.info.any { it.first == "Install name" && it.second == "@rpath/libsample.dylib" })
    }

    @Test
    fun rawFallback() {
        val b = Bin.wrap(ByteArray(64) { it.toByte() }).use { Loaders.load(it, forceRaw = true, rawBase = 0x8000) }
        assertEquals(BinaryFormat.RAW, b.format)
        assertEquals(0x8000L, b.blocks.single().address)
        assertTrue(b.languageIds.isEmpty())
    }

    @Test
    fun addressHelpers() {
        assertEquals(0x1234L, Addr.parse("0x1234"))
        assertEquals(0xffffffff80001000uL.toLong(), Addr.parse("ffffffff80001000"))
        assertTrue(Addr.inRange(0xffffffff80001000uL.toLong(), 0xffffffff80000000uL.toLong(), 0x2000))
        assertEquals("00001234", Addr.hex(0x1234, 8))
    }

    companion object {
        fun fixture(name: String): File {
            val url = LoaderTest::class.java.classLoader!!.getResource("fixtures/$name") ?: error("missing fixture $name")
            return File(url.toURI())
        }
    }
}
