# Спецификация JIT компилятора

## Обзор

JIT (Just-In-Time) компилятор динамически компилирует "горячие" функции в JVM bytecode для ускорения выполнения. Работает совместно с виртуальной машиной, автоматически определяя функции, которые вызываются часто.

## Архитектура

### Компоненты

```kotlin
class JITCompiler(
    private val module: BytecodeModule,
    private val threshold: Int = 1000
) {
    private val callCounts: ConcurrentHashMap<String, AtomicInteger>
    private val compiledFunctions: ConcurrentHashMap<String, CompiledFunctionExecutor>
    private val bytecodeGenerator: JVMBytecodeGenerator
}
```

### Интерфейс

```kotlin
interface JITCompilerInterface {
    fun recordCall(functionName: String)
    fun getCompiled(functionName: String): CompiledFunctionExecutor?
    fun isEnabled(): Boolean
}
```

## Профилирование

### Механизм

1. При каждом вызове функции VM вызывает `recordCall(functionName)`
2. Увеличивается счётчик вызовов (thread-safe через `AtomicInteger`)
3. Если счётчик достиг порога (`threshold`) и функция ещё не скомпилирована:
   - Запускается асинхронная компиляция функции

**Порог компиляции:** По умолчанию 1000 вызовов.

## Компиляция

### Асинхронная компиляция

Компиляция выполняется асинхронно в отдельном потоке, чтобы не блокировать выполнение программы.

**Алгоритм:**
1. Найти функцию по имени в модуле
2. Запустить компиляцию в отдельном потоке
3. При успешной компиляции сохранить результат в `compiledFunctions`
4. При ошибке логировать в stderr, но не прерывать выполнение

### Защита от двойной компиляции

Используется синхронизация с double-check locking для предотвращения одновременной компиляции одной функции.

## JVM Bytecode Generator

### Обзор

Генератор транслирует наш байткод в JVM bytecode через библиотеку ASM.

**Процесс компиляции:**
1. Генерация JVM bytecode через ASM API
2. Загрузка класса через `ByteArrayClassLoader`
3. Получение метода через reflection
4. Возврат результата в `CompiledJVMFunction`

### Генерация класса

- Имя класса: `Generated_<functionName>` (с timestamp для уникальности)
- Модификаторы: `public final`
- Метод: `public static execute(CallFrame, RcOperandStack, MemoryManager): VMResult`

## Трансляция инструкций

### Алгоритм

Трансляция выполняется в два прохода:

**Первый проход:** создание меток для JUMP
- Проход по всем инструкциям
- Для инструкций `JUMP`, `JUMP_IF_FALSE`, `JUMP_IF_TRUE` создаются метки

**Второй проход:** генерация кода
- Проход по всем инструкциям
- Для каждой инструкции добавляется метка (если есть) и генерируется соответствующий JVM bytecode

### Примеры трансляции

**PUSH_INT:**
1. Получить константу из `module.intConstants[operand]`
2. Загрузить константу на стек JVM через `visitLdcInsn()`
3. Вызвать `Value.IntValue.box(long)`
4. Вызвать `stack.pushMove(Value)`

**ADD_INT:**
1. Получить два значения со стека через `popMove()`
2. Выполнить сложение через `LADD` (JVM инструкция)
3. Создать `IntValue` через `box(long)`
4. Вызвать `stack.pushMove(IntValue)`

**JUMP_IF_FALSE:**
1. Получить bool значение со стека
2. Вычислить адрес цели перехода
3. Вызвать `visitJumpInsn(IFEQ, label)`

## Интеграция с VM

VM проверяет наличие скомпилированной версии функции:
1. В начале интерпретации функции
2. При вызове функции (CALL)

**Выполнение скомпилированной функции:**
1. Вызвать `compiled.execute(frame, operandStack, memoryManager)`
2. Проверить, завершилась ли функция
3. Если завершилась — удалить фрейм и освободить `ArrayRef`

## Производительность

### Overhead компиляции

- Время компиляции: ~1-10 мс на функцию (один раз)
- Память: ~1-5 KB на скомпилированную функцию

### Ускорение выполнения

После компиляции функции выполняются значительно быстрее:
- Простые операции в циклах: 50-100x быстрее
- Сложные функции с циклами: 10-20x быстрее
- Рекурсивные функции: 5-10x быстрее

### Источники ускорения

1. Устранение overhead интерпретации (нет switch по opcode)
2. JVM оптимизации (inlining, loop unrolling, dead code elimination)
3. Нативная компиляция JVM

## Обработка ошибок

- Ошибки компиляции логируются в stderr, функция продолжает выполняться через интерпретацию
- Ошибки выполнения обрабатываются так же, как в интерпретации
