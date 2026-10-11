package com.scanner3d.core

/**
 * Open-addressing hash map from Long keys to Int values without boxing.
 * [EMPTY] (Long.MIN_VALUE) is reserved and cannot be used as a key.
 */
class LongIntHashMap(initialCapacity: Int = 1024) {
    private var keys: LongArray
    private var values: IntArray
    private var mask: Int
    var size: Int = 0
        private set

    init {
        var cap = 16
        while (cap < initialCapacity * 2) cap = cap shl 1
        keys = LongArray(cap) { EMPTY }
        values = IntArray(cap)
        mask = cap - 1
    }

    /** Returns the value for [key] or [missing] if absent. */
    fun get(key: Long, missing: Int = -1): Int {
        var i = mix(key) and mask
        while (true) {
            val k = keys[i]
            if (k == key) return values[i]
            if (k == EMPTY) return missing
            i = (i + 1) and mask
        }
    }

    fun containsKey(key: Long): Boolean = get(key, Int.MIN_VALUE) != Int.MIN_VALUE

    /** Inserts or replaces. */
    fun put(key: Long, value: Int) {
        require(key != EMPTY)
        if ((size + 1) * 2 > keys.size) grow()
        var i = mix(key) and mask
        while (true) {
            val k = keys[i]
            if (k == key) { values[i] = value; return }
            if (k == EMPTY) {
                keys[i] = key; values[i] = value; size++
                return
            }
            i = (i + 1) and mask
        }
    }

    /**
     * Returns the existing value for [key], or inserts [newValue] and returns -1. Values are
     * expected to be non-negative so callers can check `result < 0` to detect an insertion.
     */
    fun putIfAbsent(key: Long, newValue: Int): Int {
        require(key != EMPTY)
        if ((size + 1) * 2 > keys.size) grow()
        var i = mix(key) and mask
        while (true) {
            val k = keys[i]
            if (k == key) return values[i]
            if (k == EMPTY) {
                keys[i] = key; values[i] = newValue; size++
                return -1
            }
            i = (i + 1) and mask
        }
    }

    inline fun forEach(action: (key: Long, value: Int) -> Unit) {
        val ks = rawKeys()
        val vs = rawValues()
        for (i in ks.indices) {
            val k = ks[i]
            if (k != EMPTY) action(k, vs[i])
        }
    }

    @PublishedApi internal fun rawKeys(): LongArray = keys
    @PublishedApi internal fun rawValues(): IntArray = values

    fun clear() {
        keys.fill(EMPTY)
        size = 0
    }

    private fun grow() {
        val oldKeys = keys
        val oldValues = values
        val cap = oldKeys.size shl 1
        keys = LongArray(cap) { EMPTY }
        values = IntArray(cap)
        mask = cap - 1
        size = 0
        for (i in oldKeys.indices) {
            val k = oldKeys[i]
            if (k != EMPTY) {
                var j = mix(k) and mask
                while (keys[j] != EMPTY) j = (j + 1) and mask
                keys[j] = k
                values[j] = oldValues[i]
                size++
            }
        }
    }

    companion object {
        const val EMPTY = Long.MIN_VALUE

        fun mix(key: Long): Int {
            var h = key * -0x61c8864680b583ebL
            h = h xor (h ushr 32)
            h = h xor (h ushr 16)
            return h.toInt()
        }
    }
}

/** Packs three signed grid coordinates (each within ±2^20) into one Long key. */
object GridKey {
    private const val BITS = 21
    private const val OFFSET = 1 shl 20
    private const val MASK = (1L shl BITS) - 1

    fun pack(x: Int, y: Int, z: Int): Long =
        ((x + OFFSET).toLong() and MASK) or
            (((y + OFFSET).toLong() and MASK) shl BITS) or
            (((z + OFFSET).toLong() and MASK) shl (2 * BITS))

    fun x(key: Long): Int = (key and MASK).toInt() - OFFSET
    fun y(key: Long): Int = ((key ushr BITS) and MASK).toInt() - OFFSET
    fun z(key: Long): Int = ((key ushr (2 * BITS)) and MASK).toInt() - OFFSET
}
