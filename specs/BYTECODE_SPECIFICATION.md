# Спецификация байткода виртуальной машины

## Обзор

Байткод представляет собой низкоуровневое представление программы для выполнения на стековой виртуальной машине.

## Формат инструкций

Каждая инструкция имеет фиксированный размер **4 байта (32 бита)**:

```
[OPCODE: 1 байт] [OPERAND: 3 байта]
```

- **OPCODE** (1 байт) - код операции (0x00-0xFF)
- **OPERAND** (3 байта) - операнд инструкции (максимум: 16,777,215)

## Представление значений

```kotlin
sealed class Value {
    data class IntValue(val value: Long) : Value()
    data class FloatValue(val value: Double) : Value()
    data class BoolValue(val value: Boolean) : Value()
    data class ArrayRef(val heapId: Int) : Value()
    object VoidValue : Value()
}
```

## Набор инструкций

### Константы (0x00-0x0F)

- **PUSH_INT** (0x01) - положить int константу на стек
- **PUSH_FLOAT** (0x02) - положить float константу на стек
- **PUSH_BOOL** (0x03) - положить bool значение на стек
- **POP** (0x04) - удалить верхний элемент стека

### Локальные переменные (0x10-0x1F)

- **LOAD_LOCAL** (0x10) - загрузить значение локальной переменной на стек
- **STORE_LOCAL** (0x11) - сохранить значение со стека в локальную переменную

### Арифметические операции - int (0x20-0x2F)

- **ADD_INT** (0x20), **SUB_INT** (0x21), **MUL_INT** (0x22), **DIV_INT** (0x23), **MOD_INT** (0x24), **NEG_INT** (0x25)

### Арифметические операции - float (0x30-0x3F)

- **ADD_FLOAT** (0x30), **SUB_FLOAT** (0x31), **MUL_FLOAT** (0x32), **DIV_FLOAT** (0x33), **NEG_FLOAT** (0x35)

### Операции сравнения - int (0x40-0x4F)

- **EQ_INT** (0x40), **NE_INT** (0x41), **LT_INT** (0x42), **LE_INT** (0x43), **GT_INT** (0x44), **GE_INT** (0x45)

### Операции сравнения - float (0x50-0x5F)

- **EQ_FLOAT** (0x50), **NE_FLOAT** (0x51), **LT_FLOAT** (0x52), **LE_FLOAT** (0x53), **GT_FLOAT** (0x54), **GE_FLOAT** (0x55)

### Логические операции (0x60-0x6F)

- **AND** (0x60), **OR** (0x61), **NOT** (0x62)

### Управление потоком (0x70-0x7F)

- **JUMP** (0x70) - безусловный переход (смещение в инструкциях)
- **JUMP_IF_FALSE** (0x71) - переход, если верхний элемент стека равен `false`
- **JUMP_IF_TRUE** (0x72) - переход, если верхний элемент стека равен `true`

### Функции (0x80-0x8F)

- **CALL** (0x80) - вызов функции (индекс функции в модуле)
- **RETURN** (0x81) - возврат из функции со значением
- **RETURN_VOID** (0x82) - возврат из функции без значения

### Массивы (0x90-0x9F)

- **NEW_ARRAY_INT** (0x90) - создать массив int на куче
- **NEW_ARRAY_FLOAT** (0x91) - создать массив float на куче
- **NEW_ARRAY_BOOL** (0x94) - создать массив bool на куче
- **ARRAY_LOAD** (0x92) - загрузить элемент массива
- **ARRAY_STORE** (0x93) - сохранить значение в элемент массива

### Встроенные функции (0xA0-0xAF)

- **PRINT** (0xA0) - печать примитивного значения
- **PRINT_ARRAY** (0xA1) - печать массива

## Структура данных

### BytecodeModule

```kotlin
data class BytecodeModule(
    val intConstants: List<Long>,
    val floatConstants: List<Double>,
    val functions: List<CompiledFunction>,
    val entryPoint: String
)
```

### CompiledFunction

```kotlin
data class CompiledFunction(
    val name: String,
    val parameters: List<ParameterInfo>,
    val returnType: TypeNode,
    val localsCount: Int,
    val instructions: ByteArray
)
```

## Генерация байткода

Генератор строит пулы констант во время компиляции. При встрече литерала:
1. Проверяет наличие константы в пуле
2. Если есть → использует её индекс
3. Если нет → добавляет в пул и запоминает индекс

**Пример:**
```kotlin
// Исходный код: let x: int = 5 + 3;
// Байткод:
PUSH_INT 0      // 5 (индекс в intConstants)
PUSH_INT 1      // 3
ADD_INT
STORE_LOCAL 0   // x
```
