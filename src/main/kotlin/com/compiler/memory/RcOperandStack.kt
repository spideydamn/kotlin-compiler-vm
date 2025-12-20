package com.compiler.memory

import com.compiler.bytecode.Value

class RcOperandStack(private val mm: MemoryManager) {
    private val data = ArrayList<Value>()

    fun size(): Int = data.size

    /** COPY: стек получает ещё одного владельца, значит retain */
    fun pushCopy(v: Value) {
        mm.retain(v)
        data.add(v)
    }

    /** MOVE: владение передано стеку, retain не нужен */
    fun pushMove(v: Value) {
        data.add(v)
    }

    /** MOVE: стек больше не владеет, но владение возвращается вызывающему (release не делаем) */
    fun popMove(): Value {
        if (data.isEmpty()) error("Operand stack underflow")
        return data.removeAt(data.lastIndex)
    }

    /** DROP: выбросить значение окончательно */
    fun popDrop() {
        val v = popMove()
        mm.release(v)
    }

    fun clearAndReleaseAll() {
        while (data.isNotEmpty()) {
            popDrop()
        }
    }
}
