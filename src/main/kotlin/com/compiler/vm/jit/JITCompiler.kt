package com.compiler.vm.jit

import com.compiler.bytecode.BytecodeModule
import com.compiler.bytecode.CompiledFunction
import com.compiler.vm.CompiledFunctionExecutor
import com.compiler.vm.JITCompilerInterface
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

/**
 * JITCompiler — per-spec implementation.
 *
 * - profile calls via recordCall(functionName)
 * - when counter >= threshold -> schedule asynchronous compilation (if not already compiled /
 * compiling)
 * - getCompiled returns compiled function or null
 */
class JITCompiler(
        private val module: BytecodeModule,
        private val threshold: Int = 1000,
        private val executor: ExecutorService = Executors.newCachedThreadPool()
) : JITCompilerInterface {

    private val callCounts: ConcurrentHashMap<String, AtomicInteger> = ConcurrentHashMap()
    private val compiledFunctions: ConcurrentHashMap<String, CompiledFunctionExecutor> = ConcurrentHashMap()
    private val compileLocks: ConcurrentHashMap<String, Any> = ConcurrentHashMap()

    private val bytecodeGenerator = JVMBytecodeGenerator(module)

    @Volatile private var enabled: Boolean = true

    override fun recordCall(functionName: String) {
        if (!isEnabled()) return

        val counter = callCounts.computeIfAbsent(functionName) { AtomicInteger(0) }
        val newCount = counter.incrementAndGet()

        if (newCount >= threshold && !compiledFunctions.containsKey(functionName)) {
            val lock = compileLocks.computeIfAbsent(functionName) { Any() }

            executor.submit {
                synchronized(lock) {
                    if (compiledFunctions.containsKey(functionName)) {
                        return@synchronized
                    }
                    val fn: CompiledFunction? = module.functions.find { it.name == functionName }
                    if (fn == null) {
                        System.err.println(
                                "JIT: function '$functionName' not found in module, skipping compilation."
                        )
                        return@synchronized
                    }

                    try {
                        val compiled = bytecodeGenerator.compileFunction(fn)
                        if (compiled != null) {
                            compiledFunctions[functionName] = compiled
                        } else {
                            System.err.println(
                                    "JIT: compilation returned null for function '$functionName'."
                            )
                        }
                    } catch (t: Throwable) {
                        System.err.println(
                                "JIT: compilation failed for function '$functionName': ${t.message}"
                        )
                        t.printStackTrace(System.err)
                    }
                }
            }
        }
    }

    override fun getCompiled(functionName: String): CompiledFunctionExecutor? {
        return compiledFunctions[functionName]
    }

    override fun isEnabled(): Boolean = enabled

    /** 
     * Optional helper: force-disable JIT (for tests) 
     */
    fun setEnabled(value: Boolean) {
        enabled = value
    }

    /** 
     * Shutdown executor (call from VM shutdown) 
     */
    fun shutdown() {
        try {
            executor.shutdown()
            executor.awaitTermination(1, TimeUnit.SECONDS)
        } catch (ignored: InterruptedException) {}
    }
}
