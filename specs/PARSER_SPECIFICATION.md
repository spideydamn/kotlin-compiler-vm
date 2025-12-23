# Спецификация парсера

## Обзор

Парсер преобразует последовательность токенов в абстрактное синтаксическое дерево (AST). Реализован как рекурсивный нисходящий парсер (Recursive Descent Parser).

## Архитектура

### Метод парсинга

**Recursive Descent Parser** - каждая грамматическая конструкция соответствует отдельной функции.

**Входные данные:** `List<Token>` - последовательность токенов от лексера

**Выходные данные:** `Program` - корневой узел AST

## Структура AST

### Базовые классы

```kotlin
sealed class ASTNode
sealed class Statement : ASTNode()
sealed class Expression : ASTNode()
sealed class TypeNode : ASTNode()
```

### Узлы операторов

#### Program
```kotlin
data class Program(val statements: List<Statement>)
```

#### VarDecl
```kotlin
data class VarDecl(
    val identifier: String,
    val type: TypeNode,
    val expression: Expression,
    val pos: SourcePos
)
```

#### FunctionDecl
```kotlin
data class FunctionDecl(
    val identifier: String,
    val parameters: List<Parameter>,
    val returnType: TypeNode,
    val body: BlockStmt,
    val pos: SourcePos
)

data class Parameter(
    val identifier: String,
    val type: TypeNode,
    val pos: SourcePos
)
```

#### BlockStmt
```kotlin
data class BlockStmt(val statements: List<Statement>)
```

#### IfStmt
```kotlin
data class IfStmt(
    val condition: Expression,
    val thenBranch: BlockStmt,
    val elseBranch: BlockStmt? = null
)
```

#### ForStmt
```kotlin
data class ForStmt(
    val initializer: ForInitializer,
    val condition: Expression?,
    val increment: Expression?,
    val body: BlockStmt
)

sealed class ForInitializer {
    object ForNoInit : ForInitializer()
    data class ForVarInit(val decl: VarDecl) : ForInitializer()
    data class ForExprInit(val expr: Expression) : ForInitializer()
}
```

#### ReturnStmt
```kotlin
data class ReturnStmt(val value: Expression?, val pos: SourcePos)
```

#### ExprStmt
```kotlin
data class ExprStmt(val expr: Expression)
```

### Узлы выражений

#### LiteralExpr
```kotlin
data class LiteralExpr(val value: Any?, val pos: SourcePos)
// value: Long, Double, Boolean, или null
```

#### VariableExpr
```kotlin
data class VariableExpr(val name: String, val pos: SourcePos)
```

#### BinaryExpr
```kotlin
data class BinaryExpr(
    val left: Expression,
    val operator: TokenType,
    val right: Expression,
    val pos: SourcePos
)
```

#### UnaryExpr
```kotlin
data class UnaryExpr(
    val operator: TokenType,
    val right: Expression,
    val pos: SourcePos
)
```

#### AssignExpr
```kotlin
data class AssignExpr(
    val target: LValue,
    val value: Expression,
    val pos: SourcePos
)

sealed interface LValue  // VariableExpr или ArrayAccessExpr
```

#### CallExpr
```kotlin
data class CallExpr(
    val name: String,
    val args: List<Expression>,
    val pos: SourcePos
)
```

#### ArrayAccessExpr
```kotlin
data class ArrayAccessExpr(
    val array: Expression,
    val index: Expression,
    val pos: SourcePos
)
```

#### ArrayInitExpr
```kotlin
data class ArrayInitExpr(
    val elementType: TypeNode,
    val size: Expression,
    val pos: SourcePos
)
```

#### GroupingExpr
```kotlin
data class GroupingExpr(
    val expression: Expression,
    val pos: SourcePos
)
```

### Узлы типов

```kotlin
sealed class TypeNode : ASTNode() {
    object IntType : TypeNode()
    object FloatType : TypeNode()
    object BoolType : TypeNode()
    object VoidType : TypeNode()
    data class ArrayType(val elementType: TypeNode) : TypeNode()
}
```

## Приоритет операторов

От низшего к высшему:

1. Assignment (`=`)
2. Logical OR (`||`)
3. Logical AND (`&&`)
4. Equality (`==`, `!=`)
5. Comparison (`<`, `<=`, `>`, `>=`)
6. Term (`+`, `-`)
7. Factor (`*`, `/`, `%`)
8. Unary (`!`, `-`, `+`)
9. Postfix (вызовы функций, доступ к массивам)

**Ассоциативность:** все бинарные операторы левоассоциативны, унарные правоассоциативны.

## Алгоритм парсинга

### Грамматика

```
program ::= declaration* EOF
declaration ::= functionDecl | varDecl | statement
```

### Процесс парсинга

1. **Парсинг программы:** последовательно разбирает все декларации до EOF
2. **Парсинг деклараций:** определяет тип по первому токену (`func`, `let`, или statement)
3. **Парсинг выражений:** с учетом приоритета операторов, начиная с assignment

**Цепочка вызовов:**
```
expression() → assignment() → logicOr() → logicAnd() → 
equality() → comparison() → term() → factor() → 
unary() → call() → primary()
```

## Обработка ошибок

При синтаксической ошибке парсер выбрасывает `ParseException`:

```kotlin
class ParseException(
    val pos: SourcePos,
    message: String
) : RuntimeException(...)
```

**Типы ошибок:**
- Неожиданный токен
- Отсутствующий токен (`;`, `)`, `}`)
- Некорректный литерал
- Некорректная цель присваивания

## Особенности реализации

- Одиночные операторы после `if`/`for` автоматически оборачиваются в `BlockStmt`
- Типы массивов парсятся рекурсивно: `int[][]` → `ArrayType(ArrayType(IntType))`
- Пустые списки параметров/аргументов обрабатываются корректно
- Все части цикла `for` опциональны

## Интерфейс

```kotlin
class Parser(private val tokens: List<Token>) {
    fun parse(): Program
}
```
