package com.ghidroid.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class LibraryEntry(
    val id: String,
    val name: String,
    val size: Long,
    val addedAt: Long,
    val openedAt: Long,
    val format: String = "",
    val machine: String = "",
    val languageId: String? = null,
    val compilerId: String? = null,
    val forceRaw: Boolean = false,
    val rawBase: Long = 0,
    val functionCount: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("size", size); put("addedAt", addedAt); put("openedAt", openedAt)
        put("format", format); put("machine", machine)
        languageId?.let { put("languageId", it) }
        compilerId?.let { put("compilerId", it) }
        put("forceRaw", forceRaw); put("rawBase", rawBase); put("functions", functionCount)
    }

    companion object {
        fun fromJson(o: JSONObject) = LibraryEntry(
            id = o.getString("id"),
            name = o.optString("name", "binary"),
            size = o.optLong("size"),
            addedAt = o.optLong("addedAt"),
            openedAt = o.optLong("openedAt"),
            format = o.optString("format"),
            machine = o.optString("machine"),
            languageId = o.optString("languageId").takeIf { it.isNotEmpty() },
            compilerId = o.optString("compilerId").takeIf { it.isNotEmpty() },
            forceRaw = o.optBoolean("forceRaw"),
            rawBase = o.optLong("rawBase"),
            functionCount = o.optInt("functions"),
        )
    }
}

/** Binaries the user opened, copied into app storage so they can be memory-mapped and reopened. */
class Library(private val context: Context) {
    private val root = File(context.filesDir, "library").apply { mkdirs() }
    private val _entries = MutableStateFlow<List<LibraryEntry>>(emptyList())
    val entries: StateFlow<List<LibraryEntry>> = _entries.asStateFlow()

    suspend fun refresh() = withContext(Dispatchers.IO) {
        _entries.value = root.listFiles().orEmpty().mapNotNull { dir ->
            val meta = File(dir, "meta.json")
            if (!meta.isFile || !File(dir, "blob").isFile) return@mapNotNull null
            runCatching { LibraryEntry.fromJson(JSONObject(meta.readText())) }.getOrNull()
        }.sortedByDescending { it.openedAt }
    }

    fun fileOf(entry: LibraryEntry): File = File(File(root, entry.id), "blob")

    fun get(id: String): LibraryEntry? = _entries.value.firstOrNull { it.id == id }

    /** Copies the document behind [uri] into the library, reporting progress in [0, 1]. */
    suspend fun import(uri: Uri, onProgress: (Float) -> Unit): LibraryEntry = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "binary"
        var size = -1L
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it }
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        }
        val id = UUID.randomUUID().toString()
        val dir = File(root, id).apply { mkdirs() }
        val out = File(dir, "blob")
        try {
            val input = resolver.openInputStream(uri) ?: error("Cannot open $uri")
            input.use { ins ->
                out.outputStream().use { os ->
                    val buf = ByteArray(256 * 1024)
                    var total = 0L
                    while (true) {
                        val r = ins.read(buf)
                        if (r < 0) break
                        os.write(buf, 0, r)
                        total += r
                        if (size > 0) onProgress((total.toFloat() / size).coerceIn(0f, 1f))
                    }
                    size = total
                }
            }
        } catch (t: Throwable) {
            dir.deleteRecursively()
            throw t
        }
        val now = System.currentTimeMillis()
        val entry = LibraryEntry(id, name, size, now, now, format = sniffFormat(out))
        save(entry)
        entry
    }

    private fun sniffFormat(file: File): String {
        val head = ByteArray(4)
        val n = runCatching { file.inputStream().use { it.read(head) } }.getOrDefault(0)
        if (n < 4) return ""
        val be = ((head[0].toInt() and 0xFF) shl 24) or ((head[1].toInt() and 0xFF) shl 16) or
            ((head[2].toInt() and 0xFF) shl 8) or (head[3].toInt() and 0xFF)
        return when {
            head[0] == 0x7F.toByte() && head[1] == 'E'.code.toByte() && head[2] == 'L'.code.toByte() && head[3] == 'F'.code.toByte() -> "ELF"
            head[0] == 'M'.code.toByte() && head[1] == 'Z'.code.toByte() -> "PE"
            be == 0xFEEDFACE.toInt() || be == 0xFEEDFACF.toInt() || be == 0xCEFAEDFE.toInt() || be == 0xCFFAEDFE.toInt() || be == 0xCAFEBABE.toInt() -> "Mach-O"
            else -> ""
        }
    }

    suspend fun save(entry: LibraryEntry) = withContext(Dispatchers.IO) {
        val dir = File(root, entry.id).apply { mkdirs() }
        File(dir, "meta.json").writeText(entry.toJson().toString())
        _entries.value = (_entries.value.filter { it.id != entry.id } + entry).sortedByDescending { it.openedAt }
    }

    suspend fun delete(entry: LibraryEntry) = withContext(Dispatchers.IO) {
        File(root, entry.id).deleteRecursively()
        _entries.value = _entries.value.filter { it.id != entry.id }
    }
}
