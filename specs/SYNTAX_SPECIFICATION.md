# Спецификация синтаксиса языка

## Основные принципы

- **Строгая типизация** - все переменные и функции имеют явно указанные типы
- **Обязательные точки с запятой** - все выражения заканчиваются `;`
- **Обязательные скобки** - все блоки кода заключены в `{}`

## Лексические элементы

### Ключевые слова

- `let` - объявление переменной
- `func` - объявление функции
- `if`, `else` - условный оператор
- `for` - цикл
- `return` - возврат значения
- `true`, `false` - булевы литералы

### Типы данных

**Примитивные типы:**
- `int` - 64-битное целое число со знаком
- `float` - 64-битное число с плавающей точкой (IEEE 754 double)
- `bool` - булево значение
- `void` - отсутствие значения (только для функций)

**Массивы:**
- `int[]`, `float[]`, `bool[]` - массивы примитивных типов

### Операторы

- Арифметические: `+`, `-`, `*`, `/`, `%`
- Сравнения: `==`, `!=`, `<`, `>`, `<=`, `>=`
- Логические: `&&`, `||`, `!`
- Присваивания: `=`

### Литералы

- Целые: `0`, `42`, `-1`
- С плавающей точкой: `3.14`, `1.0e10`, `-2.71`
- Булевы: `true`, `false`

### Разделители

- `;` - точка с запятой
- `{}` - фигурные скобки для блоков
- `()` - круглые скобки для условий и вызовов
- `[]` - квадратные скобки для массивов
- `:` - двоеточие для указания типа
- `,` - запятая для разделения параметров

## Синтаксические конструкции

### Объявление переменных

```
let <identifier>: <type> = <expression>;
```

**Примеры:**
```kotlin
let x: int = 42;
let y: float = 3.14;
let arr: int[] = int[10];
```

### Функции

```
func <identifier>(<parameters>): <return_type> {
    <statements>
}
```

**Пример:**
```kotlin
func factorial(n: int): int {
    if (n <= 1) {
        return 1;
    } else {
        return n * factorial(n - 1);
    }
}
```

### Условные операторы

```
if (<condition>) {
    <statements>
} else {
    <statements>
}
```

### Циклы for

```
for (<initializer>?; <condition>?; <increment>?) {
    <statements>
}
```

**Примеры:**
```kotlin
for (let i: int = 0; i < 10; i = i + 1) { }
for (; i < 10;) { }  // только условие
for (;;) { }         // бесконечный цикл
```

### Массивы

**Объявление:**
```kotlin
let arr: int[] = int[10];  // массив из 10 элементов
```

**Доступ:**
```kotlin
let x: int = arr[0];
arr[0] = 42;
```

## Грамматика (EBNF)

```
program ::= { declaration }

declaration ::= functionDecl | varDecl | statement

functionDecl ::= "func" identifier "(" parameters? ")" ":" type block
varDecl ::= "let" identifier ":" type "=" expression ";"

statement ::= ifStmt | forStmt | returnStmt | block | expressionStmt

ifStmt ::= "if" "(" expression ")" statement ("else" statement)?
forStmt ::= "for" "(" [initializer] ";" [condition] ";" [increment] ")" statement
returnStmt ::= "return" [expression] ";"
block ::= "{" { declaration } "}"
expressionStmt ::= expression ";"

expression ::= assignment
assignment ::= [lvalue "="] logicOr
logicOr ::= logicAnd { "||" logicAnd }
logicAnd ::= equality { "&&" equality }
equality ::= comparison { ("==" | "!=") comparison }
comparison ::= term { ("<" | ">" | "<=" | ">=") term }
term ::= factor { ("+" | "-") factor }
factor ::= unary { ("*" | "/" | "%") unary }
unary ::= { "!" | "-" | "+" } call
call ::= primary { "(" arg_list? ")" | "[" expression "]" }
primary ::= literal | identifier | "(" expression ")" | arrayInit

arrayInit ::= base_type "[" expression "]"
lvalue ::= identifier | identifier "[" expression "]"

type ::= base_type { "[]" }
base_type ::= "int" | "float" | "bool" | "void"
parameters ::= [ parameter { "," parameter } ]
parameter ::= identifier ":" type
arg_list ::= expression { "," expression }
```

## Ограничения

- Нет строк - только числа и булевы значения
- Нет break/continue
- Нет while - используйте `for (condition)`
- Нет классов и методов
- Нет доступа к свойствам массивов через `.`
- Нет автовывода типов - все типы явные
