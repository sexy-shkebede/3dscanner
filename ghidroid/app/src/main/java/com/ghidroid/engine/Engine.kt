package com.ghidroid.engine

import android.content.Context
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

data class CompilerSpec(val id: String, val name: String)

data class Language(
    val id: String,
    val processor: String,
    val variant: String,
    val version: String,
    val description: String,
    val slaFile: String,
    val bigEndian: Boolean,
    val size: Int,
    val deprecated: Boolean,
    val compilers: List<CompilerSpec>,
) {
    val endian: String get() = if (bigEndian) "BE" else "LE"

    fun compilerOrDefault(preferred: String?): CompilerSpec =
        compilers.firstOrNull { it.id == preferred }
            ?: compilers.firstOrNull { it.id == "default" }
            ?: compilers.firstOrNull { it.id == "gcc" }
            ?: compilers.first()
}

/**
 * Owns the native engine. Ghidra's decompiler is single threaded and recursion heavy, so every
 * native call runs on one dedicated thread with a large stack.
 */
object Engine {
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(null, r, "ghidra-engine", 256L shl 20).apply { isDaemon = true }
    }
    val dispatcher = executor.asCoroutineDispatcher()

    private val initLock = Mutex()

    @Volatile
    var languages: List<Language> = emptyList()
        private set

    suspend fun <T> call(block: () -> T): T = withContext(dispatcher) { block() }

    /** Fire-and-forget work on the engine thread (runs after everything already queued). */
    fun post(block: () -> Unit) = executor.execute(block)

    /** Copies the processor specifications out of the APK (once per app version) and loads them. */
    suspend fun ensureReady(context: Context, onProgress: (Float) -> Unit = {}) = initLock.withLock {
        if (languages.isNotEmpty()) return@withLock
        val root = withContext(Dispatchers.IO) { SpecInstaller.install(context, onProgress) }
        load(root)
    }

    /** Loads the language definitions found under [root] (one directory per processor). */
    suspend fun initFrom(root: File) = initLock.withLock {
        if (languages.isEmpty()) load(root)
    }

    private suspend fun load(root: File) {
        val langs = call {
            NativeBridge.init(root.absolutePath)?.let { throw IllegalStateException(it) }
            NativeBridge.languages().map(::parseLanguage)
        }
        languages = langs.sortedWith(compareBy<Language> { it.processor.lowercase() }.thenBy { it.id })
    }

    fun language(id: String): Language? = languages.firstOrNull { it.id == id }

    private fun parseLanguage(row: String): Language {
        val f = row.split('\u0001')
        val ids = f.getOrElse(9) { "" }.split('\u0002').filter { it.isNotEmpty() }
        val names = f.getOrElse(10) { "" }.split('\u0002')
        return Language(
            id = f[0],
            processor = f[1],
            variant = f[2],
            version = f[3],
            description = f[4],
            slaFile = f[5],
            bigEndian = f[6] == "BE",
            size = f[7].toIntOrNull() ?: 32,
            deprecated = f[8] == "1",
            compilers = ids.mapIndexed { i, id -> CompilerSpec(id, names.getOrElse(i) { id }) },
        )
    }
}

internal object SpecInstaller {
    private const val ASSET_DIR = "languages"

    fun install(context: Context, onProgress: (Float) -> Unit): File {
        val root = File(context.noBackupFilesDir, ASSET_DIR)
        val stamp = File(root, ".installed")
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val version = "${PackageInfoCompat.getLongVersionCode(info)}:${info.lastUpdateTime}"
        if (stamp.isFile && stamp.readText() == version) return root

        root.deleteRecursively()
        root.mkdirs()
        val assets = context.assets
        // One directory per processor. (Some asset managers, e.g. Robolectric's, list nested files directly.)
        val files = assets.list(ASSET_DIR).orEmpty().flatMap { e ->
            if ('/' in e) listOf(e) else assets.list("$ASSET_DIR/$e").orEmpty().map { "$e/$it" }
        }
        files.forEachIndexed { i, rel ->
            val out = File(root, rel)
            out.parentFile?.mkdirs()
            assets.open("$ASSET_DIR/$rel").use { input -> out.outputStream().use { input.copyTo(it, 64 * 1024) } }
            onProgress((i + 1).toFloat() / files.size)
        }
        stamp.writeText(version)
        return root
    }
}
