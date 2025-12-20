package com.compiler.bytecode

sealed class Value {
    data class IntValue(val value: Long) : Value()      // 64-bit signed integer
    data class FloatValue(val value: Double) : Value()  // 64-bit IEEE 754 double
    data class BoolValue(val value: Boolean) : Value()   // boolean
    data class ArrayRef(val heapId: Int) : Value()       // ссылка на массив в куче
    object VoidValue : Value()                            // отсутствие значения
}