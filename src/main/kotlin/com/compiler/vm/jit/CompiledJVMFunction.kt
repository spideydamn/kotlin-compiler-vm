package com.compiler.vm.jit

import com.compiler.vm.*
import java.lang.reflect.Method
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

    override fun execute(
            frame: CallFrame,
            stack: RcOperandStack,
            memoryManager: MemoryManager
    ): VMResult {
        return method.invoke(null, frame, stack, memoryManager) as VMResult
    }
}
