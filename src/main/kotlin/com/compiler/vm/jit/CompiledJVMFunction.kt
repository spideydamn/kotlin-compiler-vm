package com.compiler.vm.jit

import com.compiler.vm.*
import java.lang.reflect.Method
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import com.compiler.memory.RcOperandStack
import com.compiler.memory.MemoryManager

/**
 * Wrapper for the compiled class which exposes an execute method via reflection and implements
 * CompiledFunctionExecutor so it can be stored in JIT maps.
 */
class CompiledJVMFunction(
    private val clazz: Class<*>,
    private val method: Method
) : CompiledFunctionExecutor {

    // Cache a MethodHandle for faster invocation compared to reflection
    private val mh: MethodHandle? = try {
        val lookup = MethodHandles.lookup()
        lookup.unreflect(method)
    } catch (t: Throwable) {
        null
    }

    override fun execute(
        frame: CallFrame,
        stack: RcOperandStack,
        memoryManager: MemoryManager
    ): VMResult {
        try {
            // Prefer MethodHandle invocation when available
            if (mh != null) {
                return mh.invoke(frame, stack, memoryManager) as VMResult
            }
        } catch (_: Throwable) {
            // fall back to reflection
        }

        return method.invoke(null, frame, stack, memoryManager) as VMResult
    }
}
