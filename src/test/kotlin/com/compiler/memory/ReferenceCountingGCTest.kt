package com.compiler.memory

import com.compiler.bytecode.Value
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ReferenceCountingGCTest {

    @Test
    fun allocIntArrayHasRefCount1AndFreedAfterRelease() {
        val mm = MemoryManager()

        val ref = mm.newIntArray(3)
        assertEquals(1, mm.debugRefCount(ref))
        assertEquals(1, mm.debugHeapObjectCount())

        mm.release(ref)
        assertEquals(0, mm.debugHeapObjectCount())
    }

    @Test
    fun retainIncrementsAndMultipleReleasesFreeAtZero() {
        val mm = MemoryManager()
        val ref = mm.newIntArray(1)

        mm.retain(ref)
        mm.retain(ref)
        assertEquals(3, mm.debugRefCount(ref))
        assertEquals(1, mm.debugHeapObjectCount())

        mm.release(ref)
        assertEquals(2, mm.debugRefCount(ref))

        mm.release(ref)
        assertEquals(1, mm.debugRefCount(ref))

        mm.release(ref)
        assertEquals(0, mm.debugHeapObjectCount())
    }

    @Test
    fun stackMoveToLocalsKeepsRefCountStableAndFreesAfterClearingLocals() {
        val mm = MemoryManager()
        val stack = RcOperandStack(mm)
        val locals = RcLocals(mm, size = 1)

        val ref = mm.newIntArray(5)

        stack.pushMove(ref)
        assertEquals(1, mm.debugRefCount(ref))

        val v = stack.popMove()
        locals.setMove(0, v)
        assertEquals(1, mm.debugRefCount(ref))

        locals.clear(0)
        assertEquals(0, mm.debugHeapObjectCount())
    }

    @Test
    fun loadLocalCopiesReferenceAndMustRetain() {
        val mm = MemoryManager()
        val stack = RcOperandStack(mm)
        val locals = RcLocals(mm, size = 1)

        val ref = mm.newIntArray(2)
        locals.setMove(0, ref)
        assertEquals(1, mm.debugRefCount(ref))

        val copied = locals.getCopy(0)
        stack.pushMove(copied)
        assertEquals(2, mm.debugRefCount(ref))

        stack.popDrop()
        assertEquals(1, mm.debugRefCount(ref))

        locals.clearAndReleaseAll()
        assertEquals(0, mm.debugHeapObjectCount())
    }

    @Test
    fun intArrayLoadStoreWorksAndDoesNotAffectRefCount() {
        val mm = MemoryManager()
        val ref = mm.newIntArray(3)

        mm.intArrayStore(ref, 0, 42L)
        mm.intArrayStore(ref, 1, -7L)

        assertEquals(42L, mm.intArrayLoad(ref, 0))
        assertEquals(-7L, mm.intArrayLoad(ref, 1))
        assertEquals(0L, mm.intArrayLoad(ref, 2))

        assertEquals(1, mm.debugRefCount(ref))

        mm.release(ref)
        assertEquals(0, mm.debugHeapObjectCount())
    }

    @Test
    fun releaseUnknownHeapIdThrows() {
        val mm = MemoryManager()
        val bogus = Value.ArrayRef(999)

        assertThrows<IllegalStateException> {
            mm.release(bogus)
        }
    }

    @Test
    fun outOfBoundsThrows() {
        val mm = MemoryManager()
        val ref = mm.newIntArray(1)

        assertThrows<IndexOutOfBoundsException> {
            mm.intArrayLoad(ref, 1)
        }

        mm.release(ref)
    }
}
