package com.compiler.bytecode

sealed class Value {
    data class IntValue(val value: Long) : Value()
    data class FloatValue(val value: Double) : Value()
    data class BoolValue(val value: Boolean) : Value()
    data class ArrayRef(val heapId: Int) : Value()
    object VoidValue : Value()
}