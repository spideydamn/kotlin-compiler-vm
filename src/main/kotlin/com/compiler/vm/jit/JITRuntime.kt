package com.compiler.vm.jit

import com.compiler.bytecode.*
import com.compiler.memory.*
import com.compiler.vm.CompiledFunctionExecutor
import java.util.concurrent.ConcurrentHashMap
import com.compiler.vm.*
import java.util.ArrayDeque

/**
 * Interpreter used by generated JIT classes. The generated class simply delegates to
 * this runtime, passing the module and function name. This runtime executes the
 * function starting from the provided CallFrame and uses the provided operand
 * stack and memory manager. It must not clear or remove the initial frame (VM
 * will do that after compiled execution).
 */
object JITRuntime {

    // Registry of compiled functions (filled by JITCompiler when compilation completes)
    private val compiledRegistry: ConcurrentHashMap<String, CompiledFunctionExecutor> = ConcurrentHashMap()

    /** Register a compiled function under name */
    @JvmStatic
    fun registerCompiled(functionName: String, executor: CompiledFunctionExecutor) {
        compiledRegistry[functionName] = executor
    }

    @JvmStatic
    fun unregisterCompiled(functionName: String) {
        compiledRegistry.remove(functionName)
    }

    /**
     * If a compiled function is available, invoke it directly; otherwise fall back
     * to interpreter `executeCompiled`.
     */
    @JvmStatic
    fun invokeCompiledIfPresent(
        module: BytecodeModule,
        functionName: String,
        initialFrame: CallFrame,
        operandStack: RcOperandStack,
        memoryManager: MemoryManager
    ): VMResult {
        val ex = compiledRegistry[functionName]
        if (ex != null) {
            return ex.execute(initialFrame, operandStack, memoryManager)
        }
        return executeCompiled(module, functionName, initialFrame, operandStack, memoryManager)
    }

    @JvmStatic
    fun executeCompiled(
        module: BytecodeModule,
        functionName: String,
        initialFrame: CallFrame,
        operandStack: RcOperandStack,
        memoryManager: MemoryManager
    ): VMResult {
        // Validate target function exists
        if (module.functions.none { it.name == functionName }) return VMResult.INVALID_FUNCTION_INDEX

        // Helper for diagnostics
        fun failWith(res: VMResult, frame: CallFrame?): VMResult {
            try {
                val fn = frame?.function
                var extra = ""
                if (fn != null) {
                    val pc = frame.pc
                    val instr = fn.instructions
                    if (pc >= 0 && pc < instr.size) {
                        val opcode = instr[pc].toUByte().toInt()
                        val operand = if (pc + 3 < instr.size) {
                            ((instr[pc + 1].toUByte().toInt() shl 16) or (instr[pc + 2].toUByte().toInt() shl 8) or instr[pc + 3].toUByte().toInt())
                        } else 0
                        extra = " opcode=0x${opcode.toString(16)} operand=$operand"
                    }

                    // Dump first instructions for context (up to 20)
                    try {
                        fun opName(op: Int): String = when (op.toByte()) {
                            Opcodes.PUSH_INT -> "PUSH_INT"
                            Opcodes.PUSH_FLOAT -> "PUSH_FLOAT"
                            Opcodes.PUSH_BOOL -> "PUSH_BOOL"
                            Opcodes.POP -> "POP"
                            Opcodes.LOAD_LOCAL -> "LOAD_LOCAL"
                            Opcodes.STORE_LOCAL -> "STORE_LOCAL"
                            Opcodes.ADD_INT -> "ADD_INT"
                            Opcodes.SUB_INT -> "SUB_INT"
                            Opcodes.MUL_INT -> "MUL_INT"
                            Opcodes.DIV_INT -> "DIV_INT"
                            Opcodes.MOD_INT -> "MOD_INT"
                            Opcodes.NEG_INT -> "NEG_INT"
                            Opcodes.ADD_FLOAT -> "ADD_FLOAT"
                            Opcodes.SUB_FLOAT -> "SUB_FLOAT"
                            Opcodes.MUL_FLOAT -> "MUL_FLOAT"
                            Opcodes.DIV_FLOAT -> "DIV_FLOAT"
                            Opcodes.NEG_FLOAT -> "NEG_FLOAT"
                            Opcodes.EQ_INT -> "EQ_INT"
                            Opcodes.NE_INT -> "NE_INT"
                            Opcodes.LT_INT -> "LT_INT"
                            Opcodes.LE_INT -> "LE_INT"
                            Opcodes.GT_INT -> "GT_INT"
                            Opcodes.GE_INT -> "GE_INT"
                            Opcodes.EQ_FLOAT -> "EQ_FLOAT"
                            Opcodes.NE_FLOAT -> "NE_FLOAT"
                            Opcodes.LT_FLOAT -> "LT_FLOAT"
                            Opcodes.LE_FLOAT -> "LE_FLOAT"
                            Opcodes.GT_FLOAT -> "GT_FLOAT"
                            Opcodes.GE_FLOAT -> "GE_FLOAT"
                            Opcodes.AND -> "AND"
                            Opcodes.OR -> "OR"
                            Opcodes.NOT -> "NOT"
                            Opcodes.JUMP -> "JUMP"
                            Opcodes.JUMP_IF_FALSE -> "JUMP_IF_FALSE"
                            Opcodes.JUMP_IF_TRUE -> "JUMP_IF_TRUE"
                            Opcodes.CALL -> "CALL"
                            Opcodes.RETURN -> "RETURN"
                            Opcodes.RETURN_VOID -> "RETURN_VOID"
                            Opcodes.NEW_ARRAY_INT -> "NEW_ARRAY_INT"
                            Opcodes.NEW_ARRAY_FLOAT -> "NEW_ARRAY_FLOAT"
                            Opcodes.NEW_ARRAY_BOOL -> "NEW_ARRAY_BOOL"
                            Opcodes.ARRAY_LOAD -> "ARRAY_LOAD"
                            Opcodes.ARRAY_STORE -> "ARRAY_STORE"
                            Opcodes.PRINT -> "PRINT"
                            Opcodes.PRINT_ARRAY -> "PRINT_ARRAY"
                            else -> "OP_0x${op.toString(16)}"
                        }

                        val maxInstr = minOf(20, instr.size / 4)
                        val sb = StringBuilder("\nJITRuntime: function dump (first $maxInstr instructions):\n")
                        for (i in 0 until maxInstr) {
                            val off = i * 4
                            val op = instr[off].toUByte().toInt()
                            val oper = ((instr[off + 1].toUByte().toInt() shl 16) or (instr[off + 2].toUByte().toInt() shl 8) or instr[off + 3].toUByte().toInt())
                            sb.append(String.format("%02d: pc=%3d opcode=0x%02x %-16s operand=%d\n", i, off, op, opName(op), oper))
                        }
                        System.err.println(sb.toString())
                    } catch (_: Throwable) {}
                }
                System.err.println("JITRuntime: returning $res at function=${frame?.function?.name} pc=${frame?.pc}$extra")
            } catch (_: Throwable) {}
            return res
        }

        // Use a local call stack; initialFrame is managed by VM, so we use it but
        // must not clear or remove it. For nested calls we create new frames.
        val callStack: ArrayDeque<CallFrame> = ArrayDeque()
        callStack.addLast(initialFrame)

        while (callStack.isNotEmpty()) {
            val frame = callStack.last()

            // End of function (like implicit return)
            if (frame.pc >= frame.function.instructions.size) {
                // If this is the initial frame - finish execution
                if (frame === initialFrame) {
                    return VMResult.SUCCESS
                }

                // For nested frame: clear locals and pop
                frame.locals.clearAndReleaseAll()
                val returnAddress = frame.returnAddress
                callStack.removeLast()
                // restore caller pc if present
                if (callStack.isNotEmpty() && returnAddress != null) {
                    callStack.last().pc = returnAddress
                }
                continue
            }

            val instructions = frame.function.instructions
            val pc = frame.pc

            val opcode = instructions[pc].toUByte().toInt()
            val operand = if (pc + 3 < instructions.size) {
                ((instructions[pc + 1].toUByte().toInt() shl 16) or
                 (instructions[pc + 2].toUByte().toInt() shl 8) or
                 instructions[pc + 3].toUByte().toInt())
            } else 0

            when (opcode.toByte()) {
                Opcodes.PUSH_INT -> {
                    if (operand < 0 || operand >= module.intConstants.size) return VMResult.INVALID_CONSTANT_INDEX
                    val v = module.intConstants[operand]
                    operandStack.pushMove(Value.IntValue(v))
                }

                Opcodes.PUSH_FLOAT -> {
                    if (operand < 0 || operand >= module.floatConstants.size) return VMResult.INVALID_CONSTANT_INDEX
                    val v = module.floatConstants[operand]
                    operandStack.pushMove(Value.FloatValue(v))
                }

                Opcodes.PUSH_BOOL -> {
                    val v = operand != 0
                    operandStack.pushMove(Value.BoolValue(v))
                }

                Opcodes.POP -> {
                    if (operandStack.size() == 0) return failWith(VMResult.STACK_UNDERFLOW, frame)
                    operandStack.popDrop()
                }

                Opcodes.LOAD_LOCAL -> {
                    if (operand < 0 || operand >= frame.function.localsCount) return VMResult.INVALID_LOCAL_INDEX
                    val v = frame.locals.getCopy(operand)
                    operandStack.pushCopy(v)
                }

                Opcodes.STORE_LOCAL -> {
                    if (operand < 0 || operand >= frame.function.localsCount) return failWith(VMResult.INVALID_LOCAL_INDEX, frame)
                    if (operandStack.size() == 0) return failWith(VMResult.STACK_UNDERFLOW, frame)
                    val v = operandStack.popMove()
                    frame.locals.setMove(operand, v)
                }

                Opcodes.ADD_INT, Opcodes.SUB_INT, Opcodes.MUL_INT, Opcodes.DIV_INT, Opcodes.MOD_INT -> {
                    if (operandStack.size() < 2) return failWith(VMResult.STACK_UNDERFLOW, frame)
                    val b = operandStack.popMove()
                    val a = operandStack.popMove()
                        if (a is Value.IntValue && b is Value.IntValue) {
                        val res = when (opcode.toByte()) {
                            Opcodes.ADD_INT -> a.value + b.value
                            Opcodes.SUB_INT -> a.value - b.value
                            Opcodes.MUL_INT -> a.value * b.value
                            Opcodes.DIV_INT -> if (b.value == 0L) { operandStack.pushMove(a); operandStack.pushMove(b); return VMResult.DIVISION_BY_ZERO } else a.value / b.value
                            Opcodes.MOD_INT -> if (b.value == 0L) { operandStack.pushMove(a); operandStack.pushMove(b); return VMResult.DIVISION_BY_ZERO } else a.value % b.value
                            else -> 0L
                        }
                        operandStack.pushMove(Value.IntValue(res))
                    } else {
                        return VMResult.INVALID_VALUE_TYPE
                    }
                }

                Opcodes.NEG_INT -> {
                    if (operandStack.size() < 1) return failWith(VMResult.STACK_UNDERFLOW, frame)
                    val a = operandStack.popMove()
                    if (a is Value.IntValue) operandStack.pushMove(Value.IntValue(-a.value)) else { operandStack.pushMove(a); return failWith(VMResult.INVALID_VALUE_TYPE, frame) }
                }

                Opcodes.ADD_FLOAT, Opcodes.SUB_FLOAT, Opcodes.MUL_FLOAT, Opcodes.DIV_FLOAT -> {
                    if (operandStack.size() < 2) return failWith(VMResult.STACK_UNDERFLOW, frame)
                    val b = operandStack.popMove()
                    val a = operandStack.popMove()
                        if (a is Value.FloatValue && b is Value.FloatValue) {
                        val res = when (opcode.toByte()) {
                            Opcodes.ADD_FLOAT -> a.value + b.value
                            Opcodes.SUB_FLOAT -> a.value - b.value
                            Opcodes.MUL_FLOAT -> a.value * b.value
                            Opcodes.DIV_FLOAT -> a.value / b.value
                            else -> 0.0
                        }
                        operandStack.pushMove(Value.FloatValue(res))
                    } else {
                        return VMResult.INVALID_VALUE_TYPE
                    }
                }

                Opcodes.NEG_FLOAT -> {
                    if (operandStack.size() < 1) return failWith(VMResult.STACK_UNDERFLOW, frame)
                    val a = operandStack.popMove()
                    if (a is Value.FloatValue) operandStack.pushMove(Value.FloatValue(-a.value)) else { operandStack.pushMove(a); return failWith(VMResult.INVALID_VALUE_TYPE, frame) }
                }

                Opcodes.EQ_INT, Opcodes.NE_INT, Opcodes.LT_INT, Opcodes.LE_INT, Opcodes.GT_INT, Opcodes.GE_INT -> {
                    if (operandStack.size() < 2) return failWith(VMResult.STACK_UNDERFLOW, frame)
                    val b = operandStack.popMove()
                    val a = operandStack.popMove()
                    if (a is Value.IntValue && b is Value.IntValue) {
                        val res = when (opcode.toByte()) {
                            Opcodes.EQ_INT -> a.value == b.value
                            Opcodes.NE_INT -> a.value != b.value
                            Opcodes.LT_INT -> a.value < b.value
                            Opcodes.LE_INT -> a.value <= b.value
                            Opcodes.GT_INT -> a.value > b.value
                            Opcodes.GE_INT -> a.value >= b.value
                            else -> false
                        }
                        operandStack.pushMove(Value.BoolValue(res))
                    } else {
                        operandStack.pushMove(a)
                        operandStack.pushMove(b)
                        return failWith(VMResult.INVALID_VALUE_TYPE, frame)
                    }
                }

                Opcodes.EQ_FLOAT, Opcodes.NE_FLOAT, Opcodes.LT_FLOAT, Opcodes.LE_FLOAT, Opcodes.GT_FLOAT, Opcodes.GE_FLOAT -> {
                    if (operandStack.size() < 2) return VMResult.STACK_UNDERFLOW
                    val b = operandStack.popMove()
                    val a = operandStack.popMove()
                    if (a is Value.FloatValue && b is Value.FloatValue) {
                        val res = when (opcode.toByte()) {
                            Opcodes.EQ_FLOAT -> a.value == b.value
                            Opcodes.NE_FLOAT -> a.value != b.value
                            Opcodes.LT_FLOAT -> a.value < b.value
                            Opcodes.LE_FLOAT -> a.value <= b.value
                            Opcodes.GT_FLOAT -> a.value > b.value
                            Opcodes.GE_FLOAT -> a.value >= b.value
                            else -> false
                        }
                        operandStack.pushMove(Value.BoolValue(res))
                    } else {
                        operandStack.pushMove(a)
                        operandStack.pushMove(b)
                        return VMResult.INVALID_VALUE_TYPE
                    }
                }

                Opcodes.AND, Opcodes.OR -> {
                    if (operandStack.size() < 2) return failWith(VMResult.STACK_UNDERFLOW, frame)
                    val b = operandStack.popMove()
                    val a = operandStack.popMove()
                    if (a is Value.BoolValue && b is Value.BoolValue) {
                        val res = if (opcode.toByte() == Opcodes.AND) a.value && b.value else a.value || b.value
                        operandStack.pushMove(Value.BoolValue(res))
                    } else {
                        operandStack.pushMove(a)
                        operandStack.pushMove(b)
                        return failWith(VMResult.INVALID_VALUE_TYPE, frame)
                    }
                }

                Opcodes.NOT -> {
                    if (operandStack.size() < 1) return VMResult.STACK_UNDERFLOW
                    val a = operandStack.popMove()
                    if (a is Value.BoolValue) operandStack.pushMove(Value.BoolValue(!a.value)) else { operandStack.pushMove(a); return VMResult.INVALID_VALUE_TYPE }
                }

                Opcodes.JUMP -> {
                    val signedOperand = if (operand and 0x800000 != 0) operand or 0xFF000000.toInt() else operand
                    val newPC = frame.pc + (signedOperand * 4) + 4
                    if (newPC < 0 || newPC > frame.function.instructions.size) return VMResult.INVALID_OPCODE
                    frame.pc = newPC - 4
                }

                Opcodes.JUMP_IF_FALSE, Opcodes.JUMP_IF_TRUE -> {
                    if (operandStack.size() < 1) return failWith(VMResult.STACK_UNDERFLOW, frame)
                    val v = operandStack.popMove()
                    if (v !is Value.BoolValue) { operandStack.pushMove(v); return failWith(VMResult.INVALID_VALUE_TYPE, frame) }
                    val cond = v.value
                    val check = (opcode.toByte() == Opcodes.JUMP_IF_TRUE)
                    if (cond == check) {
                        val signedOperand = if (operand and 0x800000 != 0) operand or 0xFF000000.toInt() else operand
                        val newPC = frame.pc + (signedOperand * 4) + 4
                        if (newPC < 0 || newPC > frame.function.instructions.size) return VMResult.INVALID_OPCODE
                        frame.pc = newPC - 4
                    }
                }

                Opcodes.CALL -> {
                    if (operand < 0 || operand >= module.functions.size) return failWith(VMResult.INVALID_FUNCTION_INDEX, frame)
                    val function = module.functions[operand]

                    // Pop args in reverse order
                    if (operandStack.size() < function.parameters.size) return failWith(VMResult.STACK_UNDERFLOW, frame)
                    val args = mutableListOf<Value>()
                    for (i in function.parameters.indices.reversed()) {
                        args.add(0, operandStack.popMove())
                    }

                    val newFrame = CallFrame(function, RcLocals(memoryManager, function.localsCount), 0, frame.pc + 4)
                    for (i in args.indices) newFrame.locals.setMove(i, args[i])
                    callStack.addLast(newFrame)
                }

                Opcodes.RETURN -> {
                    if (operandStack.size() < 1) return failWith(VMResult.STACK_UNDERFLOW, frame)
                    val returnValue = operandStack.popMove()
                    // If this is initial frame: leave returnValue on stack and finish
                    if (frame === initialFrame) {
                        operandStack.pushMove(returnValue)
                        return VMResult.SUCCESS
                    }

                    // For nested frames: clear locals, pop, push return value to caller and restore PC
                    frame.locals.clearAndReleaseAll()
                    callStack.removeLast()
                    operandStack.pushMove(returnValue)
                    val caller = if (callStack.isNotEmpty()) callStack.last() else null
                    if (caller != null) caller.pc = frame.returnAddress ?: caller.pc
                }

                Opcodes.RETURN_VOID -> {
                    if (frame === initialFrame) {
                        return VMResult.SUCCESS
                    }
                    frame.locals.clearAndReleaseAll()
                    val returnAddress = frame.returnAddress
                    callStack.removeLast()
                    if (callStack.isNotEmpty() && returnAddress != null) {
                        callStack.last().pc = returnAddress
                    }
                }

                Opcodes.NEW_ARRAY_INT -> {
                    if (operandStack.size() < 1) return VMResult.STACK_UNDERFLOW
                    val sizeVal = operandStack.popMove()
                    if (sizeVal !is Value.IntValue) { operandStack.pushMove(sizeVal); return VMResult.INVALID_VALUE_TYPE }
                    val size = sizeVal.value.toInt()
                    if (size < 0) { operandStack.pushMove(sizeVal); return VMResult.ARRAY_INDEX_OUT_OF_BOUNDS }
                    operandStack.pushMove(memoryManager.newIntArray(size))
                }

                Opcodes.NEW_ARRAY_FLOAT -> {
                    if (operandStack.size() < 1) return VMResult.STACK_UNDERFLOW
                    val sizeVal = operandStack.popMove()
                    if (sizeVal !is Value.IntValue) { operandStack.pushMove(sizeVal); return VMResult.INVALID_VALUE_TYPE }
                    val size = sizeVal.value.toInt()
                    if (size < 0) { operandStack.pushMove(sizeVal); return VMResult.ARRAY_INDEX_OUT_OF_BOUNDS }
                    operandStack.pushMove(memoryManager.newFloatArray(size))
                }

                Opcodes.NEW_ARRAY_BOOL -> {
                    if (operandStack.size() < 1) return VMResult.STACK_UNDERFLOW
                    val sizeVal = operandStack.popMove()
                    if (sizeVal !is Value.IntValue) { operandStack.pushMove(sizeVal); return VMResult.INVALID_VALUE_TYPE }
                    val size = sizeVal.value.toInt()
                    if (size < 0) { operandStack.pushMove(sizeVal); return VMResult.ARRAY_INDEX_OUT_OF_BOUNDS }
                    operandStack.pushMove(memoryManager.newBoolArray(size))
                }

                Opcodes.ARRAY_LOAD -> {
                    if (operandStack.size() < 2) return VMResult.STACK_UNDERFLOW
                    val indexVal = operandStack.popMove()
                    val arrayRef = operandStack.popMove()
                        if (arrayRef !is Value.ArrayRef || indexVal !is Value.IntValue) { operandStack.pushMove(arrayRef); operandStack.pushMove(indexVal); return VMResult.INVALID_VALUE_TYPE }
                    try {
                        val atype = memoryManager.getArrayType(arrayRef) ?: return VMResult.INVALID_ARRAY_TYPE
                        when (atype) {
                            "int" -> operandStack.pushMove(Value.IntValue(memoryManager.intArrayLoad(arrayRef, indexVal.value.toInt())))
                            "float" -> operandStack.pushMove(Value.FloatValue(memoryManager.floatArrayLoad(arrayRef, indexVal.value.toInt())))
                            "bool" -> operandStack.pushMove(Value.BoolValue(memoryManager.boolArrayLoad(arrayRef, indexVal.value.toInt())))
                            else -> return VMResult.INVALID_ARRAY_TYPE
                        }
                    } catch (e: IndexOutOfBoundsException) {
                        operandStack.pushMove(arrayRef)
                        operandStack.pushMove(indexVal)
                        return VMResult.ARRAY_INDEX_OUT_OF_BOUNDS
                    } catch (e: IllegalStateException) {
                        operandStack.pushMove(arrayRef)
                        operandStack.pushMove(indexVal)
                        return VMResult.INVALID_HEAP_ID
                    }
                }

                Opcodes.ARRAY_STORE -> {
                    if (operandStack.size() < 3) return VMResult.STACK_UNDERFLOW
                    val value = operandStack.popMove()
                    val indexVal = operandStack.popMove()
                    val arrayRef = operandStack.popMove()
                        if (arrayRef !is Value.ArrayRef || indexVal !is Value.IntValue) { operandStack.pushMove(arrayRef); operandStack.pushMove(indexVal); operandStack.pushMove(value); return VMResult.INVALID_VALUE_TYPE }
                    try {
                        when (value) {
                            is Value.IntValue -> { memoryManager.intArrayStore(arrayRef, indexVal.value.toInt(), value.value); }
                            is Value.FloatValue -> { memoryManager.floatArrayStore(arrayRef, indexVal.value.toInt(), value.value); }
                            is Value.BoolValue -> { memoryManager.boolArrayStore(arrayRef, indexVal.value.toInt(), value.value); }
                            else -> { operandStack.pushMove(arrayRef); operandStack.pushMove(indexVal); operandStack.pushMove(value); return VMResult.INVALID_VALUE_TYPE }
                        }
                    } catch (e: IndexOutOfBoundsException) {
                        operandStack.pushMove(arrayRef)
                        operandStack.pushMove(indexVal)
                        operandStack.pushMove(value)
                        return VMResult.ARRAY_INDEX_OUT_OF_BOUNDS
                    } catch (e: IllegalStateException) {
                        operandStack.pushMove(arrayRef)
                        operandStack.pushMove(indexVal)
                        operandStack.pushMove(value)
                        return VMResult.INVALID_HEAP_ID
                    }
                }

                Opcodes.PRINT -> {
                    if (operandStack.size() < 1) return VMResult.STACK_UNDERFLOW
                    val v = operandStack.popMove()
                    when (v) {
                        is Value.IntValue -> print(v.value)
                        is Value.FloatValue -> print(v.value)
                        is Value.BoolValue -> print(v.value)
                        else -> { operandStack.pushMove(v); return VMResult.INVALID_VALUE_TYPE }
                    }
                }

                Opcodes.PRINT_ARRAY -> {
                    val arr = if (operandStack.size() >= 1) operandStack.popMove() else return VMResult.STACK_UNDERFLOW
                    if (arr !is Value.ArrayRef) { operandStack.pushMove(arr); return VMResult.INVALID_VALUE_TYPE }
                    try {
                        val atype = memoryManager.getArrayType(arr) ?: return VMResult.INVALID_ARRAY_TYPE
                        val size = memoryManager.getArraySize(arr)
                        print("[")
                        when (atype) {
                            "int" -> for (i in 0 until size) { if (i>0) print(", "); print(memoryManager.intArrayLoad(arr, i)) }
                            "float" -> for (i in 0 until size) { if (i>0) print(", "); print(memoryManager.floatArrayLoad(arr, i)) }
                            "bool" -> for (i in 0 until size) { if (i>0) print(", "); print(memoryManager.boolArrayLoad(arr, i)) }
                            else -> return VMResult.INVALID_ARRAY_TYPE
                        }
                        print("]")
                    } catch (e: IllegalStateException) {
                        operandStack.pushMove(arr)
                        return VMResult.INVALID_HEAP_ID
                    }
                }

                else -> return VMResult.INVALID_OPCODE
            }

            // If function frame still present and wasn't changed to another, increment pc
            if (callStack.isNotEmpty()) {
                val cur = callStack.last()
                // Only increment if current frame is still executing and pc was not modified
                if (cur === frame && frame.pc == pc) {
                    frame.pc += 4
                }
            }
        }

        return VMResult.SUCCESS
    }
}
