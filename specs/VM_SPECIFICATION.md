# Спецификация виртуальной машины

## Обзор

Виртуальная машина (VM) выполняет байткод через интерпретацию. Может работать с JIT компилятором для ускорения выполнения.

## Архитектура

### Компоненты VM

```kotlin
class VirtualMachine(
    private val module: BytecodeModule,
    private val jitCompiler: JITCompilerInterface? = null
) {
    private val operandStack: RcOperandStack
    private val callStack: ArrayDeque<CallFrame>
    private val memoryManager: MemoryManager
}
```

### CallFrame

```kotlin
data class CallFrame(
    val function: CompiledFunction,
    val locals: RcLocals,
    var pc: Int,
    val returnAddress: Int?
)
```

### VMResult

```kotlin
enum class VMResult {
    SUCCESS,
    DIVISION_BY_ZERO,
    ARRAY_INDEX_OUT_OF_BOUNDS,
    STACK_UNDERFLOW,
    INVALID_OPCODE,
    INVALID_HEAP_ID,
    // ... другие коды ошибок
}
```

## Цикл интерпретации

### Алгоритм

1. **Инициализация:**
   - Найти функцию точки входа (`entryPoint`)
   - Создать первый `CallFrame` с `pc = 0`

2. **Основной цикл:**
   - Пока `callStack` не пуст:
     - Получить текущий фрейм
     - Если JIT включен и есть скомпилированная версия → выполнить её
   - Иначе интерпретировать одну инструкцию
   - Если результат не `SUCCESS` → вернуть ошибку

3. **Чтение инструкций:**
   - Читать opcode по адресу `pc` (1 байт)
   - Читать operand по адресу `pc + 1` (3 байта, big-endian)
   - Для JUMP преобразовать в signed

## Обработка инструкций

### Общий алгоритм

1. Проверить конец функции (`pc >= instructions.size`)
2. Прочитать opcode и operand
3. Выполнить инструкцию через switch по opcode
4. Увеличить `pc` на 4 (следующая инструкция)

### Основные категории инструкций

- **Константы:** PUSH_INT, PUSH_FLOAT, PUSH_BOOL, POP
- **Локальные:** LOAD_LOCAL, STORE_LOCAL
- **Арифметика:** ADD_INT, SUB_INT, MUL_INT, DIV_INT, MOD_INT, NEG_INT (аналогично для float)
- **Сравнения:** EQ_INT, NE_INT, LT_INT, LE_INT, GT_INT, GE_INT (аналогично для float)
- **Логические:** AND, OR, NOT
- **Управление потоком:** JUMP, JUMP_IF_FALSE, JUMP_IF_TRUE
- **Функции:** CALL, RETURN, RETURN_VOID
- **Массивы:** NEW_ARRAY_INT, NEW_ARRAY_FLOAT, NEW_ARRAY_BOOL, ARRAY_LOAD, ARRAY_STORE
- **Встроенные:** PRINT, PRINT_ARRAY

## Управление памятью (Reference Counting)

VM использует Reference Counting для управления памятью массивов.

### Правила retain/release

| Операция | Семантика | RC действие |
|----------|-----------|-------------|
| LOAD_LOCAL | COPY из locals в стек | `retain` перед push |
| STORE_LOCAL | MOVE из стека в locals | `release` старого значения |
| POP | DROP значение | `release` |
| CALL | MOVE аргументов | Без retain (move) |
| RETURN | MOVE возвращаемого значения | Без retain (move) |
| Завершение фрейма | Очистка locals | `release` всех ArrayRef |

### Завершение функции

При завершении функции (RETURN или достижение конца):
- Вызвать `frame.locals.clearAndReleaseAll()` для освобождения всех `ArrayRef`

## Интеграция с JIT

VM проверяет наличие скомпилированной версии функции:
1. В начале интерпретации функции
2. При вызове функции (CALL)

Интерфейс:
```kotlin
interface JITCompilerInterface {
    fun recordCall(functionName: String)
    fun getCompiled(functionName: String): CompiledFunctionExecutor?
    fun isEnabled(): Boolean
}
```

## Обработка ошибок

VM не выполняет проверки типов во время выполнения. Все ошибки возвращают соответствующий `VMResult`:
- Деление на ноль → `DIVISION_BY_ZERO`
- Выход за границы массива → `ARRAY_INDEX_OUT_OF_BOUNDS`
- Недостаточно значений на стеке → `STACK_UNDERFLOW`
- Неверный opcode → `INVALID_OPCODE`
