package com.compiler.semantic

import com.compiler.parser.ast.Program
import com.compiler.parser.ast.TypeNode

sealed class Type {
    object Int : Type()
    object Float : Type()
    object Bool : Type()
    object Void : Type()
    data class Array(val elementType: Type) : Type()
    object Unknown : Type()

    override fun toString(): String = when (this) {
        Int -> "int"
        Float -> "float"
        Bool -> "bool"
        Void -> "void"
        is Array -> "${elementType}[]"
        Unknown -> "unknown"
    }
}

fun TypeNode.toSemanticType(): Type = when (this) {
    TypeNode.IntType -> Type.Int
    TypeNode.FloatType -> Type.Float
    TypeNode.BoolType -> Type.Bool
    TypeNode.VoidType -> Type.Void
    is TypeNode.ArrayType -> Type.Array(elementType.toSemanticType())
}

sealed class Symbol {
    abstract val name: String
}

data class VariableSymbol(
    override val name: String,
    val type: Type
) : Symbol()

data class FunctionSymbol(
    override val name: String,
    val parameters: List<VariableSymbol>,
    val returnType: Type
) : Symbol()

class Scope(val parent: Scope?) {
    private val variables = mutableMapOf<String, VariableSymbol>()
    private val functions = mutableMapOf<String, FunctionSymbol>()

    fun defineVariable(symbol: VariableSymbol): Boolean {
        if (variables.containsKey(symbol.name)) {
            return false
        }
        variables[symbol.name] = symbol
        return true
    }

    fun defineFunction(symbol: FunctionSymbol): Boolean {
        if (functions.containsKey(symbol.name)) {
            return false
        }
        functions[symbol.name] = symbol
        return true
    }

    fun resolveVariable(name: String): VariableSymbol? =
        variables[name] ?: parent?.resolveVariable(name)

    fun resolveFunction(name: String): FunctionSymbol? =
        functions[name] ?: parent?.resolveFunction(name)
}

data class SemanticError(
    val message: String,
    val position: Any?
)

data class AnalysisResult(
    val program: Program,
    val globalScope: Scope,
    val errors: List<SemanticError>
)
