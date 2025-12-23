package com.compiler.vm.jit

import com.compiler.bytecode.BytecodeModule
import com.compiler.vm.CompiledFunctionExecutor
import com.compiler.vm.JITCompilerInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*

/**
 * Асинхронный JIT-компилятор для байткода модуля.
 *
 * Профилирует вызовы функций через recordCall и при достижении порога запускает фоновую компиляцию
 * "горячих" функций. Компиляции выполняются в фиксированном пуле потоков и управляются через
 * CoroutineScope; результат публикуется в thread-safe коллекцию.
 *
 * @param module байткодный модуль, содержащий функции для компиляции
 * @param threshold число вызовов функции, после которого её следует компилировать
 * @param maxParallelCompilations максимум параллельных компиляций (по умолчанию —
 * min(availableProcessors, 4))
 */
class JITCompiler(
        private val module: BytecodeModule,
        private val threshold: Int = 1000,
        maxParallelCompilations: Int = Runtime.getRuntime().availableProcessors().coerceAtMost(4)
) : JITCompilerInterface, AutoCloseable {

    private val callCounts = ConcurrentHashMap<String, AtomicInteger>()
    private val compiledFunctions = ConcurrentHashMap<String, CompiledFunctionExecutor>()
    private val inProgress =
            ConcurrentHashMap<String, CompletableDeferred<CompiledFunctionExecutor?>>()
    private val bytecodeGenerator = JVMBytecodeGenerator(module)

    private val functionMap: Map<String, com.compiler.bytecode.CompiledFunction> =
            module.functions.associateBy { it.name }

    private val executorService =
            Executors.newFixedThreadPool(maxParallelCompilations) // cpu-bound pool
    private val dispatcher = executorService.asCoroutineDispatcher()
    private val semaphore = Semaphore(maxParallelCompilations)

    private val scope = CoroutineScope(SupervisorJob() + dispatcher + CoroutineName("jit-scope"))

    @Volatile private var enabled: Boolean = true

    /**
     * Отмечает вызов функции для профилирования и при достижении порога планирует её асинхронную
     * компиляцию.
     *
     * Идемпотентен: повторные записи для одной функции, пока она в процессе компиляции,
     * игнорируются. Компиляция выполняется вне блокировок; публикация результата — атомарно.
     *
     * @param functionName имя функции в модуле
     */
    override fun recordCall(functionName: String) {
        if (!enabled) return

        val counter = callCounts.computeIfAbsent(functionName) { AtomicInteger(0) }
        val newCount = counter.incrementAndGet()

        if (newCount < threshold) return
        if (compiledFunctions.containsKey(functionName)) return

        val existing = inProgress[functionName]
        if (existing != null) return

        val deferred = CompletableDeferred<CompiledFunctionExecutor?>()
        val placed = inProgress.putIfAbsent(functionName, deferred)
        if (placed != null) return

        scope.launch {
            try {
                val fn = functionMap[functionName]
                if (fn == null) {
                    deferred.complete(null)
                    return@launch
                }

                if (!semaphore.tryAcquire(5, TimeUnit.SECONDS)) {
                    deferred.completeExceptionally(
                            CancellationException("Couldn't acquire compile slot")
                    )
                    return@launch
                }

                try {
                    val compiled =
                            withContext(dispatcher + CoroutineName("jit-compile-$functionName")) {
                                bytecodeGenerator.compileFunction(fn)
                            }

                    if (compiled != null) {
                        val prev = compiledFunctions.putIfAbsent(functionName, compiled)
                        if (prev == null) {
                            JITRuntime.registerCompiled(functionName, compiled)
                        }
                    }

                    deferred.complete(compiled)
                } catch (ce: CancellationException) {
                    deferred.cancel(ce)
                } catch (t: Throwable) {
                    deferred.completeExceptionally(t)
                } finally {
                    semaphore.release()
                }
            } finally {
                inProgress.remove(functionName)
            }
        }
    }

    /**
     * Возвращает скомпилированный executor для функции, если он доступен.
     *
     * @param functionName имя функции
     * @return CompiledFunctionExecutor или null, если функция ещё не скомпилирована
     */
    override fun getCompiled(functionName: String): CompiledFunctionExecutor? =
            compiledFunctions[functionName]

    /**
     * Возвращает, включён ли JIT.
     *
     * @return true если JIT включён
     */
    override fun isEnabled(): Boolean = enabled

    /**
     * Включает или выключает JIT. Если выключен — recordCall ничего не делает.
     *
     * @param value true для включения, false для отключения
     */
    fun setEnabled(value: Boolean) {
        enabled = value
    }

    /**
     * Корректно завершает работу JIT: отменяет все активные корутины и останавливает пул потоков.
     *
     * После вызова shutdown новые компиляции запланированы не будут.
     */
    fun shutdown() {
        scope.cancel()
        try {
            executorService.shutdown()
            executorService.awaitTermination(1, TimeUnit.SECONDS)
        } catch (ignored: InterruptedException) {}
    }

    /** То же, что shutdown(). Реализует AutoCloseable для удобного использования через `use`. */
    override fun close() = shutdown()
}
