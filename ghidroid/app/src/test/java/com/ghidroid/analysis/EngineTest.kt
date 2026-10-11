package com.ghidroid.analysis

import com.ghidroid.engine.Engine
import com.ghidroid.loader.LoaderTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * Runs the real Ghidra engine (libghidroid built for the host, see app/build.gradle.kts) over the
 * fixtures through the same Kotlin code paths the app uses.
 */
class EngineTest {
    private fun open(name: String): Program = runBlocking {
        val r = ProgramLoader.open(LoaderTest.fixture(name), name, OpenOptions())
        (r as OpenResult.Opened).program
    }

    private fun Program.decompile(fn: String): String = runBlocking {
        val f = functions.value.first { it.name == fn }
        val raw = decompileRaw(f.address, DecompileOptions(100)).getOrThrow()
        CCodeParser.parse(f.address, raw).plainText
    }

    private fun Program.analyze() = runBlocking { Analyzer(this@analyze).run(discoverFunctions = true, minStringLength = 5) }

    @Test
    fun languagesLoaded() {
        assertTrue(Engine.languages.size > 150)
        assertNotNull(Engine.language("x86:LE:64:default"))
        assertNotNull(Engine.language("AARCH64:LE:64:v8A"))
    }

    @Test
    fun x86_64Executable() {
        open("sample-x86_64.elf").use { p ->
            p.analyze()
            assertEquals("x86:LE:64:default", p.language.id)
            // PLT stubs resolved to their imports.
            assertEquals("strlen", p.function(0x1090)?.name)
            assertTrue(p.strings.value.any { it.text == "usage: sample <word>" })
            val c = p.decompile("main")
            assertTrue(c, c.contains("strlen("))
            assertTrue(c, c.contains("\"usage: sample <word>\""))
            assertTrue(c, c.contains("sum_list(") || c.contains("while"))
            val insns = runBlocking { p.disassemble(0x10c0, 16) }
            assertEquals("ENDBR64", insns.first().mnemonic)
            assertTrue(insns.any { it.flow == Instruction.FLOW_CALL && it.target == 0x1090L })
            val refs = runBlocking { p.findCodeReferences(0x1090) }
            assertTrue(refs.contains(0x10dcL))
        }
    }

    @Test
    fun armThumbSharedObject() {
        open("libsample-armv7.so.bin").use { p ->
            p.analyze()
            assertEquals("ARM:LE:32:v8", p.language.id)
            assertEquals("puts", p.function(0x590)?.name)
            val insns = runBlocking { p.disassemble(0x4ac, 4) }
            assertEquals(2, insns.first().length)  // decoded as Thumb
            val c = p.decompile("main")
            assertTrue(c, c.contains("puts(") && c.contains("printf("))
            assertTrue(c, c.contains("\"usage: sample <word>\""))
        }
    }

    @Test
    fun arm64SharedObject() {
        open("libsample-arm64.so.bin").use { p ->
            p.analyze()
            val c = p.decompile("main")
            assertTrue(c, c.contains("malloc(") && c.contains("strlen("))
            assertTrue(c, c.contains("\"usage: sample <word>\""))
        }
    }

    @Test
    fun windowsDll() {
        open("sample-x64.dll").use { p ->
            p.analyze()
            assertEquals("windows", p.compiler.id)
            val c = p.decompile("checksum")
            assertTrue(c, c.contains("lstrlenA("))
            val pick = p.decompile("pick")
            assertTrue(pick, pick.contains("OutputDebugStringA("))
        }
    }

    @Test
    fun machODylib() {
        open("libsample-arm64.dylib").use { p ->
            p.analyze()
            val c = p.decompile("build")
            assertTrue(c, c.contains("strlen(") && c.contains("malloc("))
            assertTrue(c, c.contains("\"no word given\""))
        }
    }

    @Test
    fun rawImageNeedsLanguage() = runBlocking {
        val r = ProgramLoader.open(LoaderTest.fixture("sample-x86_64.elf"), "raw", OpenOptions(forceRaw = true))
        assertTrue(r is OpenResult.NeedsLanguage)
        val opened = ProgramLoader.open(
            LoaderTest.fixture("sample-x86_64.elf"), "raw",
            OpenOptions(languageId = "x86:LE:64:default", compilerId = "gcc", forceRaw = true, rawBase = 0),
        ) as OpenResult.Opened
        opened.program.use { p ->
            val insns = p.disassemble(0x10c0, 3)
            assertEquals("ENDBR64", insns.first().mnemonic)
        }
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun setUp() {
            val loaded = runCatching { com.ghidroid.engine.NativeBridge }.isSuccess
            assumeTrue("host libghidroid not available", loaded)
            runBlocking { Engine.initFrom(File("src/main/assets/languages").absoluteFile) }
        }
    }
}
