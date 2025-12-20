package com.compiler.memory

import com.compiler.bytecode.Value

class MemoryManager(
) {
    // чтобы gc и heap были согласованы
    private val realHeap: Heap = Heap()
    private val realGc: RefCountGC = RefCountGC(realHeap)

    // Создаём с refCount=1: владение отдаётся VM
    fun newIntArray(size: Int): Value.ArrayRef {
        val id = realHeap.allocIntArray(size)
        val ref = Value.ArrayRef(id)
        realGc.retain(ref)
        return ref
    }

    fun newFloatArray(size: Int): Value.ArrayRef {
        val id = realHeap.allocFloatArray(size)
        val ref = Value.ArrayRef(id)
        realGc.retain(ref)
        return ref
    }

    fun intArrayLoad(ref: Value.ArrayRef, index: Int): Long {
        val obj = realHeap.get(ref.heapId)
        val arr = (obj as? IntArrayObject)
            ?: throw IllegalStateException("heapId=${ref.heapId} is not int[]")
        checkIndex(index, arr.elements.size)
        return arr.elements[index]
    }

    fun intArrayStore(ref: Value.ArrayRef, index: Int, value: Long) {
        val obj = realHeap.get(ref.heapId)
        val arr = (obj as? IntArrayObject)
            ?: throw IllegalStateException("heapId=${ref.heapId} is not int[]")
        checkIndex(index, arr.elements.size)
        arr.elements[index] = value
    }

    fun floatArrayLoad(ref: Value.ArrayRef, index: Int): Double {
        val obj = realHeap.get(ref.heapId)
        val arr = (obj as? FloatArrayObject)
            ?: throw IllegalStateException("heapId=${ref.heapId} is not float[]")
        checkIndex(index, arr.elements.size)
        return arr.elements[index]
    }

    fun floatArrayStore(ref: Value.ArrayRef, index: Int, value: Double) {
        val obj = realHeap.get(ref.heapId)
        val arr = (obj as? FloatArrayObject)
            ?: throw IllegalStateException("heapId=${ref.heapId} is not float[]")
        checkIndex(index, arr.elements.size)
        arr.elements[index] = value
    }

    fun retain(v: Value) = realGc.retain(v)
    fun release(v: Value) = realGc.release(v)

    fun debugRefCount(ref: Value.ArrayRef): Int = realHeap.get(ref.heapId).refCount
    fun debugHeapObjectCount(): Int = realHeap.objectCount()

    private fun checkIndex(index: Int, size: Int) {
        if (index < 0 || index >= size) {
            throw IndexOutOfBoundsException("Index $index out of bounds for size $size")
        }
    }
}
