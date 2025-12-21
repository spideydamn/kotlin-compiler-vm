package com.compiler.parser.ast.optimizations

import com.compiler.domain.SourcePos
import com.compiler.lexer.TokenType
import com.compiler.parser.ast.*

/**
 * Выполняет проход оптимизации constant folding по AST.
 *
 * Реализует иммутабельный пост-обход дерева и возвращает новый [Program], в котором подставлены
 * вычисленные на этапе компиляции литералы.
 *
 * Поддерживается свёртка булевых и числовых (Long, Double) выражений, унарных операций, упрощение
 * ветвления [IfStmt] с константным условием, свёртка аргументов вызовов и индексов массивов.
 *
 * Деление и взятие остатка при делении на ноль не выполняются во время свёртки.
 */
object ConstantFolder {

    /**
     * Запускает оптимизацию constant folding для корневого узла программы.
     *
     * @param program исходный AST программы
     * @return новый [Program] с применённой оптимизацией
     */
    fun fold(program: Program): Program {
        val folded = program.statements.map { foldStatement(it) }
        return Program(folded)
    }

    /**
     * Обрабатывает узел-оператор и возвращает его свернутую версию.
     *
     * @param stmt входной оператор
     * @return свернутый оператор
     */
    private fun foldStatement(stmt: Statement): Statement =
            when (stmt) {
                is VarDecl -> stmt.copy(expression = foldExpression(stmt.expression))
                is FunctionDecl -> stmt.copy(body = foldBlock(stmt.body))
                is BlockStmt -> foldBlock(stmt)
                is IfStmt -> foldIf(stmt)
                is ForStmt -> foldFor(stmt)
                is ReturnStmt -> stmt.copy(value = stmt.value?.let { foldExpression(it) })
                is ExprStmt -> ExprStmt(foldExpression(stmt.expr))
            }

    /**
     * Обрабатывает блок операторов.
     *
     * @param block входной блок
     * @return новый [BlockStmt] с преобразованными операторами
     */
    private fun foldBlock(block: BlockStmt): BlockStmt {
        val foldedStmts = block.statements.map { foldStatement(it) }
        return BlockStmt(foldedStmts)
    }

    /**
     * Упрощает условный оператор, если условие является константой.
     *
     * @param ifStmt входной [IfStmt]
     * @return заменённый оператор: тогда-ветвь, иначе-ветвь или исходный if с свернутыми частями
     */
    private fun foldIf(ifStmt: IfStmt): Statement {
        val condF = foldExpression(ifStmt.condition)
        if (condF is LiteralExpr && condF.value is Boolean) {
            return if (condF.value) {
                foldBlock(ifStmt.thenBranch)
            } else {
                ifStmt.elseBranch?.let { foldBlock(it) } ?: BlockStmt(emptyList())
            }
        }
        val thenF = foldBlock(ifStmt.thenBranch)
        val elseF = ifStmt.elseBranch?.let { foldBlock(it) }
        return IfStmt(condF, thenF, elseF)
    }

    /**
     * Обрабатывает оператор цикла for и его составляющие.
     *
     * @param forStmt входной [ForStmt]
     * @return новый [ForStmt] с преобразованными частями
     */
    private fun foldFor(forStmt: ForStmt): ForStmt {
        val initF: ForInitializer =
                when (val init = forStmt.initializer) {
                    is ForVarInit ->
                            ForVarInit(
                                    init.decl.copy(
                                            expression = foldExpression(init.decl.expression)
                                    )
                            )
                    is ForExprInit -> ForExprInit(foldExpression(init.expr))
                    ForNoInit -> ForNoInit
                }
        val condF = forStmt.condition?.let { foldExpression(it) }
        val incF = forStmt.increment?.let { foldExpression(it) }
        val bodyF = foldBlock(forStmt.body)
        return ForStmt(initF, condF, incF, bodyF)
    }

    /**
     * Обрабатывает выражение и возвращает его свернутую версию, если возможно.
     *
     * @param expr входное выражение
     * @return свернутое выражение или исходное (с обработанными дочерними узлами)
     */
    private fun foldExpression(expr: Expression): Expression =
            when (expr) {
                is LiteralExpr -> expr
                is VariableExpr -> expr
                is GroupingExpr -> {
                    val inner = foldExpression(expr.expression)
                    if (inner is LiteralExpr) inner else GroupingExpr(inner, expr.pos)
                }
                is ArrayInitExpr -> expr.copy(size = foldExpression(expr.size))
                is ArrayAccessExpr -> {
                    val arr = foldExpression(expr.array)
                    val idx = foldExpression(expr.index)
                    ArrayAccessExpr(arr, idx, expr.pos)
                }
                is CallExpr -> CallExpr(expr.name, expr.args.map { foldExpression(it) }, expr.pos)
                is AssignExpr -> {
                    val tgt =
                            when (val t = expr.target) {
                                is VariableExpr -> t
                                is ArrayAccessExpr -> {
                                    val a = foldExpression(t.array)
                                    val i = foldExpression(t.index)
                                    ArrayAccessExpr(a, i, t.pos)
                                }
                            }
                    AssignExpr(tgt, foldExpression(expr.value), expr.pos)
                }
                is UnaryExpr -> foldUnary(expr)
                is BinaryExpr -> foldBinary(expr)
            }

    /**
     * Обрабатывает унарное выражение и выполняет вычисление при наличии литерала.
     *
     * @param un входной [UnaryExpr]
     * @return вычисленное [LiteralExpr] или новый [UnaryExpr] с свернутым операндом
     */
    private fun foldUnary(un: UnaryExpr): Expression {
        val rightF = foldExpression(un.right)
        if (rightF is LiteralExpr) {
            val v = rightF.value
            return when (un.operator) {
                TokenType.NOT ->
                        if (v is Boolean) LiteralExpr(!v, un.pos)
                        else UnaryExpr(un.operator, rightF, un.pos)
                TokenType.MINUS ->
                        when (v) {
                            is Long -> LiteralExpr(-v, un.pos)
                            is Double -> LiteralExpr(-v, un.pos)
                            else -> UnaryExpr(un.operator, rightF, un.pos)
                        }
                TokenType.PLUS ->
                        when (v) {
                            is Long -> LiteralExpr(+v, un.pos)
                            is Double -> LiteralExpr(+v, un.pos)
                            else -> UnaryExpr(un.operator, rightF, un.pos)
                        }
                else -> UnaryExpr(un.operator, rightF, un.pos)
            }
        }
        return UnaryExpr(un.operator, rightF, un.pos)
    }

    /**
     * Обрабатывает бинарное выражение, пытаясь выполнить арифметику/логические операции на
     * константах.
     *
     * @param bin входной [BinaryExpr]
     * @return вычисленное [LiteralExpr] либо новый [BinaryExpr] с свернутыми операндами
     */
    private fun foldBinary(bin: BinaryExpr): Expression {
        val leftF = foldExpression(bin.left)
        val rightF = foldExpression(bin.right)

        if (bin.operator == TokenType.OR || bin.operator == TokenType.AND) {
            val lLit = leftF as? LiteralExpr
            if (lLit?.value is Boolean) {
                return when (bin.operator) {
                    TokenType.OR -> if (lLit.value) LiteralExpr(true, bin.pos) else rightF
                    TokenType.AND -> if (!lLit.value) LiteralExpr(false, bin.pos) else rightF
                    else -> BinaryExpr(leftF, bin.operator, rightF, bin.pos)
                }
            }
        }

        if (leftF is LiteralExpr && rightF is LiteralExpr) {
            val l = leftF.value
            val r = rightF.value

            if (l is Boolean && r is Boolean) {
                return when (bin.operator) {
                    TokenType.AND -> LiteralExpr(l && r, bin.pos)
                    TokenType.OR -> LiteralExpr(l || r, bin.pos)
                    TokenType.EQ -> LiteralExpr(l == r, bin.pos)
                    TokenType.NE -> LiteralExpr(l != r, bin.pos)
                    else -> BinaryExpr(leftF, bin.operator, rightF, bin.pos)
                }
            }

            if ((l is Long || l is Double) && (r is Long || r is Double)) {
                try {
                    return when (bin.operator) {
                        TokenType.PLUS -> numericPlus(l, r, bin.pos)
                        TokenType.MINUS -> numericMinus(l, r, bin.pos)
                        TokenType.STAR -> numericMul(l, r, bin.pos)
                        TokenType.SLASH -> numericDiv(l, r, bin.pos)
                        TokenType.PERCENT -> numericRem(l, r, bin.pos)
                        TokenType.EQ -> LiteralExpr(numericEq(l, r), bin.pos)
                        TokenType.NE -> LiteralExpr(!numericEq(l, r), bin.pos)
                        TokenType.LT -> LiteralExpr(numericCompare(l, r) < 0, bin.pos)
                        TokenType.LE -> LiteralExpr(numericCompare(l, r) <= 0, bin.pos)
                        TokenType.GT -> LiteralExpr(numericCompare(l, r) > 0, bin.pos)
                        TokenType.GE -> LiteralExpr(numericCompare(l, r) >= 0, bin.pos)
                        else -> BinaryExpr(leftF, bin.operator, rightF, bin.pos)
                    }
                } catch (e: ArithmeticException) {
                    return BinaryExpr(leftF, bin.operator, rightF, bin.pos)
                }
            }
        }

        return BinaryExpr(leftF, bin.operator, rightF, bin.pos)
    }

    /**
     * Сравнение чисел с учётом приведения типов.
     *
     * @param a левое значение (Long или Double)
     * @param b правое значение (Long или Double)
     * @return true, если значения равны по числовому значению
     */
    private fun numericEq(a: Any, b: Any): Boolean {
        val (ad, bd) = toDoublePair(a, b)
        return ad == bd
    }

    /**
     * Числовое сравнение с приведением к Double.
     *
     * @param a левое значение (Long или Double)
     * @param b правое значение (Long или Double)
     * @return отрицательное, ноль или положительное значение как в [Double.compareTo]
     */
    private fun numericCompare(a: Any, b: Any): Int {
        val (ad, bd) = toDoublePair(a, b)
        return ad.compareTo(bd)
    }

    /**
     * Сложение с возвращением [LiteralExpr] соответствующего типа.
     *
     * @param a левый аргумент
     * @param b правый аргумент
     * @param pos позиция результата
     * @return [LiteralExpr] с Long если оба Long, иначе Double
     */
    private fun numericPlus(a: Any, b: Any, pos: SourcePos): LiteralExpr {
        return when {
            a is Long && b is Long -> LiteralExpr(a + b, pos)
            else -> LiteralExpr(toDouble(a) + toDouble(b), pos)
        }
    }

    /**
     * Вычитание с возвращением [LiteralExpr] соответствующего типа.
     *
     * @param a левый аргумент
     * @param b правый аргумент
     * @param pos позиция результата
     * @return [LiteralExpr] с Long если оба Long, иначе Double
     */
    private fun numericMinus(a: Any, b: Any, pos: SourcePos): LiteralExpr {
        return when {
            a is Long && b is Long -> LiteralExpr(a - b, pos)
            else -> LiteralExpr(toDouble(a) - toDouble(b), pos)
        }
    }

    /**
     * Умножение с возвращением [LiteralExpr] соответствующего типа.
     *
     * @param a левый аргумент
     * @param b правый аргумент
     * @param pos позиция результата
     * @return [LiteralExpr] с Long если оба Long, иначе Double
     */
    private fun numericMul(a: Any, b: Any, pos: SourcePos): LiteralExpr {
        return when {
            a is Long && b is Long -> LiteralExpr(a * b, pos)
            else -> LiteralExpr(toDouble(a) * toDouble(b), pos)
        }
    }

    /**
     * Деление с защитой от деления на ноль.
     *
     * @param a левый аргумент
     * @param b правый аргумент
     * @param pos позиция результата
     * @return [LiteralExpr] с Long когда оба Long и без остатка, иначе Double
     * @throws ArithmeticException если деление на ноль
     */
    private fun numericDiv(a: Any, b: Any, pos: SourcePos): LiteralExpr {
        if (isZero(b)) throw ArithmeticException("division by zero")
        return when {
            a is Long && b is Long -> {
                if (a % b == 0L) LiteralExpr(a / b, pos)
                else LiteralExpr(a.toDouble() / b.toDouble(), pos)
            }
            else -> LiteralExpr(toDouble(a) / toDouble(b), pos)
        }
    }

    /**
     * Остаток от деления с защитой от деления на ноль.
     *
     * @param a левый аргумент
     * @param b правый аргумент
     * @param pos позиция результата
     * @return [LiteralExpr] с Long если оба Long, иначе Double
     * @throws ArithmeticException если деление на ноль
     */
    private fun numericRem(a: Any, b: Any, pos: SourcePos): LiteralExpr {
        if (isZero(b)) throw ArithmeticException("modulo by zero")
        return when {
            a is Long && b is Long -> LiteralExpr(a % b, pos)
            else -> LiteralExpr(toDouble(a) % toDouble(b), pos)
        }
    }

    /**
     * Приведение пары значений к Double.
     *
     * @param a левое значение
     * @param b правое значение
     * @return пара значений в Double
     */
    private fun toDoublePair(a: Any, b: Any): Pair<Double, Double> = Pair(toDouble(a), toDouble(b))

    /**
     * Приведение значения Long/Double к Double.
     *
     * @param x входное значение
     * @return значение в Double
     * @throws IllegalArgumentException если тип не числовой
     */
    private fun toDouble(x: Any): Double =
            when (x) {
                is Long -> x.toDouble()
                is Double -> x
                else -> throw IllegalArgumentException("expected numeric literal")
            }

    /**
     * Проверяет, является ли значение нулём.
     *
     * @param x входное значение
     * @return true для 0L или 0.0, иначе false
     */
    private fun isZero(x: Any): Boolean =
            when (x) {
                is Long -> x == 0L
                is Double -> x == 0.0
                else -> false
            }
}
