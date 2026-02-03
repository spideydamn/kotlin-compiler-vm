package com.compiler.memory

import com.compiler.bytecode.Value

class RcLocals(
    private val mm: MemoryManager,
    size: Int
) {
    private val locals: Array<Value?> = arrayOfNulls(size)

    fun getRaw(i: Int): Value? = locals[i]

    /** 
     * COPY: отдаём копию (retain) 
     */
    fun getCopy(i: Int): Value {
        val v = locals[i] ?: error("Uninitialized local #$i")
        mm.retain(v)
        return v
    }

    /** 
     * MOVE: заменить локал на новое значение без retain, но release старого 
     */
    fun setMove(i: Int, v: Value?) {
        val old = locals[i]
        if (old != null) mm.release(old)
        locals[i] = v
    }

    /** 
     * COPY: retain нового, release старого 
     */
    fun setCopy(i: Int, v: Value?) {
        val old = locals[i]
        if (v != null) mm.retain(v)
        if (old != null) mm.release(old)
        locals[i] = v
    }

    fun clear(i: Int) = setMove(i, null)

    fun clearAndReleaseAll() {
        for (i in locals.indices) {
            clear(i)
        }
    }
}
