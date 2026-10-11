package com.ghidroid.loader

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/** Read-only, memory-mapped view of a binary with bounds-checked little/big endian accessors. */
class Bin private constructor(private val buf: ByteBuffer, private val closer: Closeable?) : Closeable {
    val size: Long = buf.capacity().toLong()
    var littleEndian: Boolean = true
    private val le: ByteBuffer = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN)
    private val be: ByteBuffer = buf.duplicate().order(ByteOrder.BIG_ENDIAN)

    fun slice(offset: Long, length: Long): Bin {
        check(offset, length)
        val dup = buf.duplicate()
        dup.position(offset.toInt())
        dup.limit((offset + length).toInt())
        return Bin(dup.slice(), null).also { it.littleEndian = littleEndian }
    }

    private fun check(off: Long, len: Long) {
        if (off < 0 || len < 0 || off + len > size) throw FormatException("Read past end of file at 0x${Addr.hex(off)}")
    }

    fun has(off: Long, len: Long): Boolean = off >= 0 && len >= 0 && off + len <= size

    fun u8(off: Long): Int {
        check(off, 1)
        return buf.get(off.toInt()).toInt() and 0xFF
    }

    fun u16(off: Long): Int {
        check(off, 2)
        val b0 = buf.get(off.toInt()).toInt() and 0xFF
        val b1 = buf.get(off.toInt() + 1).toInt() and 0xFF
        return if (littleEndian) b0 or (b1 shl 8) else (b0 shl 8) or b1
    }

    fun u32(off: Long): Long {
        check(off, 4)
        val i = off.toInt()
        val v = if (littleEndian) le.getInt(i) else be.getInt(i)
        return v.toLong() and 0xFFFFFFFFL
    }

    fun i32(off: Long): Int = u32(off).toInt()

    fun u64(off: Long): Long {
        check(off, 8)
        val i = off.toInt()
        return if (littleEndian) le.getLong(i) else be.getLong(i)
    }

    /** Pointer-sized read. */
    fun ptr(off: Long, is64: Boolean): Long = if (is64) u64(off) else u32(off)

    fun bytes(off: Long, len: Int): ByteArray {
        check(off, len.toLong())
        val out = ByteArray(len)
        val dup = buf.duplicate()
        dup.position(off.toInt())
        dup.get(out)
        return out
    }

    /** Reads up to [len] bytes, clamped to the end of the file. */
    fun bytesClamped(off: Long, len: Int): ByteArray {
        if (off < 0 || off >= size) return ByteArray(0)
        return bytes(off, minOf(len.toLong(), size - off).toInt())
    }

    fun byteAt(off: Long): Byte = buf.get(off.toInt())

    fun cstring(off: Long, max: Int = 4096): String {
        if (off < 0 || off >= size) return ""
        val end = minOf(size, off + max)
        var p = off
        while (p < end && buf.get(p.toInt()).toInt() != 0) p++
        return String(bytes(off, (p - off).toInt()), Charsets.UTF_8)
    }

    /** Fixed-width, NUL padded name field (section / segment names). */
    fun fixedString(off: Long, len: Int): String {
        val b = bytes(off, len)
        val n = b.indexOf(0).let { if (it < 0) len else it }
        return String(b, 0, n, Charsets.UTF_8)
    }

    override fun close() {
        closer?.close()
    }

    companion object {
        fun open(file: File): Bin {
            val raf = RandomAccessFile(file, "r")
            try {
                val ch = raf.channel
                val len = ch.size()
                if (len > Int.MAX_VALUE) throw FormatException("Files larger than 2 GB are not supported")
                val map = ch.map(FileChannel.MapMode.READ_ONLY, 0, len)
                return Bin(map, raf)
            } catch (t: Throwable) {
                raf.close()
                throw t
            }
        }

        fun wrap(bytes: ByteArray): Bin = Bin(ByteBuffer.wrap(bytes), null)
    }
}
