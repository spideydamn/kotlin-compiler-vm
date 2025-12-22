package com.compiler.vm.jit

import com.compiler.bytecode.*
import com.compiler.bytecode.Opcodes as VMOpcodes
import com.compiler.memory.MemoryManager
import com.compiler.memory.RcLocals
import com.compiler.memory.RcOperandStack
import com.compiler.vm.CallFrame
import com.compiler.vm.VMResult
import java.lang.reflect.Method
import java.util.ArrayDeque
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes as AsmOpcodes

class JVMBytecodeGenerator(private val module: BytecodeModule) {

        fun compileFunction(function: CompiledFunction): CompiledJVMFunction? {
                val safeName = function.name.replace("[^A-Za-z0-9_]".toRegex(), "_")
                val className = "com.compiler.vm.jit.Generated_${safeName}_${System.nanoTime()}"
                val internalName = className.replace('.', '/')

                val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)

                cw.visit(
                        AsmOpcodes.V1_8,
                        AsmOpcodes.ACC_PUBLIC or AsmOpcodes.ACC_FINAL or AsmOpcodes.ACC_SUPER,
                        internalName,
                        null,
                        "java/lang/Object",
                        null
                )

                cw.visitField(
                                AsmOpcodes.ACC_PUBLIC or AsmOpcodes.ACC_STATIC,
                                "INSTRUCTIONS",
                                "[B",
                                null,
                                null
                        )
                        .visitEnd()
                cw.visitField(
                                AsmOpcodes.ACC_PUBLIC or AsmOpcodes.ACC_STATIC,
                                "INT_CONSTANTS",
                                "[J",
                                null,
                                null
                        )
                        .visitEnd()
                cw.visitField(
                                AsmOpcodes.ACC_PUBLIC or AsmOpcodes.ACC_STATIC,
                                "FLOAT_CONSTANTS",
                                "[D",
                                null,
                                null
                        )
                        .visitEnd()
                cw.visitField(
                                AsmOpcodes.ACC_PUBLIC or AsmOpcodes.ACC_STATIC,
                                "MODULE",
                                "Lcom/compiler/bytecode/BytecodeModule;",
                                null,
                                null
                        )
                        .visitEnd()

                val callFrameDesc = "Lcom/compiler/vm/CallFrame;"
                val stackDesc = "Lcom/compiler/memory/RcOperandStack;"
                val mmDesc = "Lcom/compiler/memory/MemoryManager;"
                val vmResultDesc = "Lcom/compiler/vm/VMResult;"
                val methodDesc = "($callFrameDesc$stackDesc$mmDesc)$vmResultDesc"

                val mv: MethodVisitor =
                        cw.visitMethod(
                                AsmOpcodes.ACC_PUBLIC or AsmOpcodes.ACC_STATIC,
                                "execute",
                                methodDesc,
                                null,
                                null
                        )
                mv.visitCode()

                // load arguments
                mv.visitVarInsn(AsmOpcodes.ALOAD, 0)
                mv.visitVarInsn(AsmOpcodes.ALOAD, 1)
                mv.visitVarInsn(AsmOpcodes.ALOAD, 2)

                // load static fields
                mv.visitFieldInsn(AsmOpcodes.GETSTATIC, internalName, "INSTRUCTIONS", "[B")
                mv.visitFieldInsn(AsmOpcodes.GETSTATIC, internalName, "INT_CONSTANTS", "[J")
                mv.visitFieldInsn(AsmOpcodes.GETSTATIC, internalName, "FLOAT_CONSTANTS", "[D")
                mv.visitFieldInsn(
                        AsmOpcodes.GETSTATIC,
                        internalName,
                        "MODULE",
                        "Lcom/compiler/bytecode/BytecodeModule;"
                )

                val helperOwner = "com/compiler/vm/jit/JVMBytecodeGenerator"
                val helperDesc =
                        "(Lcom/compiler/vm/CallFrame;Lcom/compiler/memory/RcOperandStack;Lcom/compiler/memory/MemoryManager;[B[J[DLcom/compiler/bytecode/BytecodeModule;)Lcom/compiler/vm/VMResult;"

                mv.visitMethodInsn(
                        AsmOpcodes.INVOKESTATIC,
                        helperOwner,
                        "interpretCompiled",
                        helperDesc,
                        false
                )
                mv.visitInsn(AsmOpcodes.ARETURN)
                mv.visitMaxs(0, 0)
                mv.visitEnd()

                cw.visitEnd()

                val bytes = cw.toByteArray()
                val loader = ByteArrayClassLoader()
                val clazz: Class<*>
                try {
                        clazz = loader.defineClass(className, bytes)
                } catch (t: Throwable) {
                        t.printStackTrace()
                        return null
                }

                try {
                        val fInstr = clazz.getField("INSTRUCTIONS")
                        fInstr.isAccessible = true
                        fInstr.set(null, function.instructions)

                        val fInt = clazz.getField("INT_CONSTANTS")
                        fInt.isAccessible = true
                        fInt.set(null, module.intConstants.toLongArray())

                        val fFloat = clazz.getField("FLOAT_CONSTANTS")
                        fFloat.isAccessible = true
                        fFloat.set(null, module.floatConstants.toDoubleArray())

                        val fModule = clazz.getField("MODULE")
                        fModule.isAccessible = true
                        fModule.set(null, module)

                        val method: Method =
                                clazz.getMethod(
                                        "execute",
                                        CallFrame::class.java,
                                        RcOperandStack::class.java,
                                        MemoryManager::class.java
                                )
                        method.isAccessible = true

                        return CompiledJVMFunction(clazz, method)
                } catch (t: Throwable) {
                        t.printStackTrace()
                        return null
                }
        }

        companion object {
                @JvmStatic
                fun interpretCompiled(
                        startFrame: CallFrame,
                        stack: RcOperandStack,
                        mm: MemoryManager,
                        instructions: ByteArray,
                        intConstants: LongArray,
                        floatConstants: DoubleArray,
                        module: BytecodeModule
                ): VMResult {
                        val callStack = ArrayDeque<CallFrame>()
                        callStack.addLast(startFrame)

                        fun readOpcode(pc: Int): Int =
                                if (pc < instructions.size) (instructions[pc].toInt() and 0xFF)
                                else -1

                        fun readOperand(pc: Int): Int {
                                val b1 =
                                        if (pc + 1 < instructions.size)
                                                (instructions[pc + 1].toInt() and 0xFF)
                                        else 0
                                val b2 =
                                        if (pc + 2 < instructions.size)
                                                (instructions[pc + 2].toInt() and 0xFF)
                                        else 0
                                val b3 =
                                        if (pc + 3 < instructions.size)
                                                (instructions[pc + 3].toInt() and 0xFF)
                                        else 0
                                return (b1 shl 16) or (b2 shl 8) or b3
                        }

                        fun calculateJumpPC(frame: CallFrame, operand: Int): Int? {
                                val signedOperand =
                                        if (operand and 0x800000 != 0) {
                                                operand or 0xFF000000.toInt()
                                        } else {
                                                operand
                                        }
                                val newPC = frame.pc + (signedOperand * 4) + 4
                                return if (newPC < 0 || newPC > instructions.size) {
                                        null
                                } else {
                                        newPC - 4
                                }
                        }

                        // safe pop helpers (preserve order on mismatch)
                        fun safePopInt(): Value.IntValue? {
                                if (stack.size() < 1) return null
                                val v = stack.popMove()
                                return if (v is Value.IntValue) v
                                else {
                                        stack.pushMove(v)
                                        null
                                }
                        }
                        fun safePopFloat(): Value.FloatValue? {
                                if (stack.size() < 1) return null
                                val v = stack.popMove()
                                return if (v is Value.FloatValue) v
                                else {
                                        stack.pushMove(v)
                                        null
                                }
                        }
                        fun safePopBool(): Value.BoolValue? {
                                if (stack.size() < 1) return null
                                val v = stack.popMove()
                                return if (v is Value.BoolValue) v
                                else {
                                        stack.pushMove(v)
                                        null
                                }
                        }
                        fun safePopArrayRef(): Value.ArrayRef? {
                                if (stack.size() < 1) return null
                                val v = stack.popMove()
                                return if (v is Value.ArrayRef) v
                                else {
                                        stack.pushMove(v)
                                        null
                                }
                        }
                        fun safePopTwoIntInt(): Pair<Value.IntValue, Value.IntValue>? {
                                if (stack.size() < 2) return null
                                val b = stack.popMove()
                                val a = stack.popMove()
                                return if (a is Value.IntValue && b is Value.IntValue) Pair(a, b)
                                else {
                                        stack.pushMove(a)
                                        stack.pushMove(b)
                                        null
                                }
                        }
                        fun safePopTwoFloatFloat(): Pair<Value.FloatValue, Value.FloatValue>? {
                                if (stack.size() < 2) return null
                                val b = stack.popMove()
                                val a = stack.popMove()
                                return if (a is Value.FloatValue && b is Value.FloatValue)
                                        Pair(a, b)
                                else {
                                        stack.pushMove(a)
                                        stack.pushMove(b)
                                        null
                                }
                        }
                        fun safePopTwoBoolBool(): Pair<Value.BoolValue, Value.BoolValue>? {
                                if (stack.size() < 2) return null
                                val b = stack.popMove()
                                val a = stack.popMove()
                                return if (a is Value.BoolValue && b is Value.BoolValue) Pair(a, b)
                                else {
                                        stack.pushMove(a)
                                        stack.pushMove(b)
                                        null
                                }
                        }
                        fun safePopArrayRefAndIndex(): Pair<Value.ArrayRef, Value.IntValue>? {
                                if (stack.size() < 2) return null
                                val idx = stack.popMove()
                                val arr = stack.popMove()
                                return if (arr is Value.ArrayRef && idx is Value.IntValue)
                                        Pair(arr, idx)
                                else {
                                        stack.pushMove(arr)
                                        stack.pushMove(idx)
                                        null
                                }
                        }

                        fun binaryIntOp(
                                op: (Long, Long) -> Long,
                                checkZero: ((Long) -> Boolean)? = null
                        ): VMResult {
                                val pair =
                                        safePopTwoIntInt()
                                                ?: return if (stack.size() < 2)
                                                        VMResult.STACK_UNDERFLOW
                                                else VMResult.INVALID_VALUE_TYPE
                                val a = pair.first
                                val b = pair.second
                                if (checkZero != null && checkZero(b.value)) {
                                        stack.pushMove(a)
                                        stack.pushMove(b)
                                        return VMResult.DIVISION_BY_ZERO
                                }
                                stack.pushMove(Value.IntValue(op(a.value, b.value)))
                                return VMResult.SUCCESS
                        }

                        fun binaryFloatOp(op: (Double, Double) -> Double): VMResult {
                                val pair =
                                        safePopTwoFloatFloat()
                                                ?: return if (stack.size() < 2)
                                                        VMResult.STACK_UNDERFLOW
                                                else VMResult.INVALID_VALUE_TYPE
                                val a = pair.first
                                val b = pair.second
                                stack.pushMove(Value.FloatValue(op(a.value, b.value)))
                                return VMResult.SUCCESS
                        }

                        fun unaryIntOp(op: (Long) -> Long): VMResult {
                                val a =
                                        safePopInt()
                                                ?: return if (stack.size() < 1)
                                                        VMResult.STACK_UNDERFLOW
                                                else VMResult.INVALID_VALUE_TYPE
                                stack.pushMove(Value.IntValue(op(a.value)))
                                return VMResult.SUCCESS
                        }

                        fun unaryFloatOp(op: (Double) -> Double): VMResult {
                                val a =
                                        safePopFloat()
                                                ?: return if (stack.size() < 1)
                                                        VMResult.STACK_UNDERFLOW
                                                else VMResult.INVALID_VALUE_TYPE
                                stack.pushMove(Value.FloatValue(op(a.value)))
                                return VMResult.SUCCESS
                        }

                        fun compareIntOp(op: (Long, Long) -> Boolean): VMResult {
                                val pair =
                                        safePopTwoIntInt()
                                                ?: return if (stack.size() < 2)
                                                        VMResult.STACK_UNDERFLOW
                                                else VMResult.INVALID_VALUE_TYPE
                                val a = pair.first
                                val b = pair.second
                                stack.pushMove(Value.BoolValue(op(a.value, b.value)))
                                return VMResult.SUCCESS
                        }

                        fun compareFloatOp(op: (Double, Double) -> Boolean): VMResult {
                                val pair =
                                        safePopTwoFloatFloat()
                                                ?: return if (stack.size() < 2)
                                                        VMResult.STACK_UNDERFLOW
                                                else VMResult.INVALID_VALUE_TYPE
                                val a = pair.first
                                val b = pair.second
                                stack.pushMove(Value.BoolValue(op(a.value, b.value)))
                                return VMResult.SUCCESS
                        }

                        fun binaryBoolOp(op: (Boolean, Boolean) -> Boolean): VMResult {
                                val pair =
                                        safePopTwoBoolBool()
                                                ?: return if (stack.size() < 2)
                                                        VMResult.STACK_UNDERFLOW
                                                else VMResult.INVALID_VALUE_TYPE
                                val a = pair.first
                                val b = pair.second
                                stack.pushMove(Value.BoolValue(op(a.value, b.value)))
                                return VMResult.SUCCESS
                        }

                        fun newArray(create: (Int) -> Value.ArrayRef): VMResult {
                                val sizeVal =
                                        safePopInt()
                                                ?: return if (stack.size() < 1)
                                                        VMResult.STACK_UNDERFLOW
                                                else VMResult.INVALID_VALUE_TYPE
                                val size = sizeVal.value.toInt()
                                if (size < 0) {
                                        stack.pushMove(sizeVal)
                                        return VMResult.ARRAY_INDEX_OUT_OF_BOUNDS
                                }
                                stack.pushMove(create(size))
                                return VMResult.SUCCESS
                        }

                        while (callStack.isNotEmpty()) {
                                val frame = callStack.last()

                                // Use the *instructions* parameter length exclusively for bounds
                                // checks
                                if (frame.pc >= instructions.size) {
                                        // Function completed without explicit return -> treat as
                                        // RETURN_VOID
                                        val returnAddress = frame.returnAddress
                                        try {
                                                frame.locals.clearAndReleaseAll()
                                        } catch (_: Throwable) {}
                                        callStack.removeLast()
                                        if (callStack.isNotEmpty() && returnAddress != null) {
                                                val callerFrame = callStack.last()
                                                callerFrame.pc = returnAddress
                                        }
                                        if (callStack.isEmpty()) return VMResult.SUCCESS
                                        else continue
                                }

                                val stackSizeBefore = callStack.size
                                val pcBefore = frame.pc

                                val opcode = readOpcode(frame.pc)
                                val operand = readOperand(frame.pc)

                                when (opcode.toByte()) {
                                        VMOpcodes.PUSH_INT -> {
                                                val idx = operand
                                                if (idx < 0 || idx >= intConstants.size)
                                                        return VMResult.INVALID_CONSTANT_INDEX
                                                stack.pushMove(Value.IntValue(intConstants[idx]))
                                        }
                                        VMOpcodes.PUSH_FLOAT -> {
                                                val idx = operand
                                                if (idx < 0 || idx >= floatConstants.size)
                                                        return VMResult.INVALID_CONSTANT_INDEX
                                                stack.pushMove(
                                                        Value.FloatValue(floatConstants[idx])
                                                )
                                        }
                                        VMOpcodes.PUSH_BOOL -> {
                                                stack.pushMove(Value.BoolValue(operand != 0))
                                        }
                                        VMOpcodes.POP -> {
                                                if (stack.size() == 0)
                                                        return VMResult.STACK_UNDERFLOW
                                                stack.popDrop()
                                        }
                                        VMOpcodes.LOAD_LOCAL -> {
                                                if (operand < 0 ||
                                                                operand >=
                                                                        frame.function.localsCount
                                                )
                                                        return VMResult.INVALID_LOCAL_INDEX
                                                val v = frame.locals.getCopy(operand)
                                                stack.pushCopy(v)
                                        }
                                        VMOpcodes.STORE_LOCAL -> {
                                                if (operand < 0 ||
                                                                operand >=
                                                                        frame.function.localsCount
                                                )
                                                        return VMResult.INVALID_LOCAL_INDEX
                                                if (stack.size() == 0)
                                                        return VMResult.STACK_UNDERFLOW
                                                val v = stack.popMove()
                                                frame.locals.setMove(operand, v)
                                        }
                                        VMOpcodes.ADD_INT -> {
                                                val r = binaryIntOp(Long::plus)
                                                if (r != VMResult.SUCCESS) return r
                                        }
                                        VMOpcodes.SUB_INT -> {
                                                val r = binaryIntOp(Long::minus)
                                                if (r != VMResult.SUCCESS) return r
                                        }
                                        VMOpcodes.MUL_INT -> {
                                                val r = binaryIntOp(Long::times)
                                                if (r != VMResult.SUCCESS) return r
                                        }
                                        VMOpcodes.DIV_INT -> {
                                                val r = binaryIntOp(Long::div) { it == 0L }
                                                if (r != VMResult.SUCCESS) return r
                                        }
                                        VMOpcodes.MOD_INT -> {
                                                val r = binaryIntOp(Long::rem) { it == 0L }
                                                if (r != VMResult.SUCCESS) return r
                                        }
                                        VMOpcodes.NEG_INT -> {
                                                val r = unaryIntOp { -it }
                                                if (r != VMResult.SUCCESS) return r
                                        }
                                        VMOpcodes.ADD_FLOAT -> {
                                                val r = binaryFloatOp(Double::plus)
                                                if (r != VMResult.SUCCESS) return r
                                        }
                                        VMOpcodes.SUB_FLOAT -> {
                                                val r = binaryFloatOp(Double::minus)
                                                if (r != VMResult.SUCCESS) return r
                                        }
                                        VMOpcodes.MUL_FLOAT -> {
                                                val r = binaryFloatOp(Double::times)
                                                if (r != VMResult.SUCCESS) return r
                                        }
                                        VMOpcodes.DIV_FLOAT -> {
                                                val r = binaryFloatOp(Double::div)
                                                if (r != VMResult.SUCCESS) return r
                                        }
                                        VMOpcodes.NEG_FLOAT -> {
                                                val r = unaryFloatOp { -it }
                                                if (r != VMResult.SUCCESS) return r
                                        }
                                        VMOpcodes.EQ_INT -> {
                                                val r = compareIntOp { a, b -> a == b }
                                                if (r != VMResult.SUCCESS) return r
                                        }
                                        VMOpcodes.NE_INT -> {
                                                val r = compareIntOp { a, b -> a != b }
                                                if (r != VMResult.SUCCESS) return r
                                        }
                                        VMOpcodes.LT_INT -> {
                                                val r = compareIntOp { a, b -> a < b }
                                                if (r != VMResult.SUCCESS) return r
                                        }
                                        VMOpcodes.LE_INT -> {
                                                val r = compareIntOp { a, b -> a <= b }
                                                if (r != VMResult.SUCCESS) return r
                                        }
                                        VMOpcodes.GT_INT -> {
                                                val r = compareIntOp { a, b -> a > b }
                                                if (r != VMResult.SUCCESS) return r
                                        }
                                        VMOpcodes.GE_INT -> {
                                                val r = compareIntOp { a, b -> a >= b }
                                                if (r != VMResult.SUCCESS) return r
                                        }
                                        VMOpcodes.EQ_FLOAT -> {
                                                val r = compareFloatOp { a, b -> a == b }
                                                if (r != VMResult.SUCCESS) return r
                                        }
                                        VMOpcodes.NE_FLOAT -> {
                                                val r = compareFloatOp { a, b -> a != b }
                                                if (r != VMResult.SUCCESS) return r
                                        }
                                        VMOpcodes.LT_FLOAT -> {
                                                val r = compareFloatOp { a, b -> a < b }
                                                if (r != VMResult.SUCCESS) return r
                                        }
                                        VMOpcodes.LE_FLOAT -> {
                                                val r = compareFloatOp { a, b -> a <= b }
                                                if (r != VMResult.SUCCESS) return r
                                        }
                                        VMOpcodes.GT_FLOAT -> {
                                                val r = compareFloatOp { a, b -> a > b }
                                                if (r != VMResult.SUCCESS) return r
                                        }
                                        VMOpcodes.GE_FLOAT -> {
                                                val r = compareFloatOp { a, b -> a >= b }
                                                if (r != VMResult.SUCCESS) return r
                                        }
                                        VMOpcodes.AND -> {
                                                val r = binaryBoolOp { a, b -> a && b }
                                                if (r != VMResult.SUCCESS) return r
                                        }
                                        VMOpcodes.OR -> {
                                                val r = binaryBoolOp { a, b -> a || b }
                                                if (r != VMResult.SUCCESS) return r
                                        }
                                        VMOpcodes.NOT -> {
                                                val a =
                                                        safePopBool()
                                                                ?: return if (stack.size() < 1)
                                                                        VMResult.STACK_UNDERFLOW
                                                                else VMResult.INVALID_VALUE_TYPE
                                                stack.pushMove(Value.BoolValue(!a.value))
                                        }
                                        VMOpcodes.JUMP -> {
                                                val np =
                                                        calculateJumpPC(frame, operand)
                                                                ?: return VMResult.INVALID_OPCODE
                                                frame.pc = np
                                        }
                                        VMOpcodes.JUMP_IF_FALSE -> {
                                                val v =
                                                        safePopBool()
                                                                ?: return if (stack.size() < 1)
                                                                        VMResult.STACK_UNDERFLOW
                                                                else VMResult.INVALID_VALUE_TYPE
                                                if (!v.value) {
                                                        val np =
                                                                calculateJumpPC(frame, operand)
                                                                        ?: return VMResult.INVALID_OPCODE
                                                        frame.pc = np
                                                }
                                        }
                                        VMOpcodes.JUMP_IF_TRUE -> {
                                                val v =
                                                        safePopBool()
                                                                ?: return if (stack.size() < 1)
                                                                        VMResult.STACK_UNDERFLOW
                                                                else VMResult.INVALID_VALUE_TYPE
                                                if (v.value) {
                                                        val np =
                                                                calculateJumpPC(frame, operand)
                                                                        ?: return VMResult.INVALID_OPCODE
                                                        frame.pc = np
                                                }
                                        }
                                        VMOpcodes.CALL -> {
                                                if (operand < 0 || operand >= module.functions.size)
                                                        return VMResult.INVALID_FUNCTION_INDEX
                                                val func = module.functions[operand]
                                                if (stack.size() < func.parameters.size)
                                                        return VMResult.STACK_UNDERFLOW

                                                val args = mutableListOf<Value>()
                                                for (i in func.parameters.indices.reversed()) {
                                                        args.add(0, stack.popMove())
                                                }

                                                val newFrame =
                                                        CallFrame(
                                                                function = func,
                                                                locals =
                                                                        RcLocals(
                                                                                mm,
                                                                                func.localsCount
                                                                        ),
                                                                pc = 0,
                                                                returnAddress = frame.pc + 4
                                                        )
                                                for (i in args.indices) newFrame.locals.setMove(
                                                        i,
                                                        args[i]
                                                )
                                                callStack.addLast(newFrame)
                                                continue
                                        }
                                        VMOpcodes.RETURN -> {
                                                if (stack.size() < 1)
                                                        return VMResult.STACK_UNDERFLOW
                                                val returnValue = stack.popMove()
                                                try {
                                                        frame.locals.clearAndReleaseAll()
                                                } catch (_: Throwable) {}
                                                callStack.removeLast()
                                                if (callStack.isNotEmpty()) {
                                                        val caller = callStack.last()
                                                        stack.pushMove(returnValue)
                                                        caller.pc = frame.returnAddress ?: caller.pc
                                                } else {
                                                        return VMResult.SUCCESS
                                                }
                                        }
                                        VMOpcodes.RETURN_VOID -> {
                                                try {
                                                        frame.locals.clearAndReleaseAll()
                                                } catch (_: Throwable) {}
                                                val retAddr = frame.returnAddress
                                                callStack.removeLast()
                                                if (callStack.isNotEmpty() && retAddr != null) {
                                                        val caller = callStack.last()
                                                        caller.pc = retAddr
                                                } else {
                                                        return VMResult.SUCCESS
                                                }
                                        }
                                        VMOpcodes.NEW_ARRAY_INT -> {
                                                val r = newArray { mm.newIntArray(it) }
                                                if (r != VMResult.SUCCESS) return r
                                        }
                                        VMOpcodes.NEW_ARRAY_FLOAT -> {
                                                val r = newArray { mm.newFloatArray(it) }
                                                if (r != VMResult.SUCCESS) return r
                                        }
                                        VMOpcodes.NEW_ARRAY_BOOL -> {
                                                val r = newArray { mm.newBoolArray(it) }
                                                if (r != VMResult.SUCCESS) return r
                                        }
                                        VMOpcodes.ARRAY_LOAD -> {
                                                val pair =
                                                        safePopArrayRefAndIndex()
                                                                ?: return if (stack.size() < 2)
                                                                        VMResult.STACK_UNDERFLOW
                                                                else VMResult.INVALID_VALUE_TYPE
                                                val arrRef = pair.first
                                                val idxVal = pair.second
                                                val idx = idxVal.value.toInt()
                                                try {
                                                        val arrType =
                                                                mm.getArrayType(arrRef)
                                                                        ?: return VMResult.INVALID_ARRAY_TYPE
                                                        when (arrType) {
                                                                "int" ->
                                                                        stack.pushMove(
                                                                                Value.IntValue(
                                                                                        mm.intArrayLoad(
                                                                                                arrRef,
                                                                                                idx
                                                                                        )
                                                                                )
                                                                        )
                                                                "float" ->
                                                                        stack.pushMove(
                                                                                Value.FloatValue(
                                                                                        mm.floatArrayLoad(
                                                                                                arrRef,
                                                                                                idx
                                                                                        )
                                                                                )
                                                                        )
                                                                "bool" ->
                                                                        stack.pushMove(
                                                                                Value.BoolValue(
                                                                                        mm.boolArrayLoad(
                                                                                                arrRef,
                                                                                                idx
                                                                                        )
                                                                                )
                                                                        )
                                                                else ->
                                                                        return VMResult.INVALID_ARRAY_TYPE
                                                        }
                                                } catch (e: IndexOutOfBoundsException) {
                                                        stack.pushMove(arrRef)
                                                        stack.pushMove(idxVal)
                                                        return VMResult.ARRAY_INDEX_OUT_OF_BOUNDS
                                                } catch (e: IllegalStateException) {
                                                        stack.pushMove(arrRef)
                                                        stack.pushMove(idxVal)
                                                        return VMResult.INVALID_HEAP_ID
                                                }
                                        }
                                        VMOpcodes.ARRAY_STORE -> {
                                                if (stack.size() < 3)
                                                        return VMResult.STACK_UNDERFLOW
                                                val value = stack.popMove()
                                                val pair =
                                                        safePopArrayRefAndIndex()
                                                                ?: run {
                                                                        stack.pushMove(value)
                                                                        return VMResult.INVALID_VALUE_TYPE
                                                                }
                                                val arrRef = pair.first
                                                val idxVal = pair.second
                                                val idx = idxVal.value.toInt()
                                                try {
                                                        when (value) {
                                                                is Value.IntValue ->
                                                                        mm.intArrayStore(
                                                                                arrRef,
                                                                                idx,
                                                                                value.value
                                                                        )
                                                                is Value.FloatValue ->
                                                                        mm.floatArrayStore(
                                                                                arrRef,
                                                                                idx,
                                                                                value.value
                                                                        )
                                                                is Value.BoolValue ->
                                                                        mm.boolArrayStore(
                                                                                arrRef,
                                                                                idx,
                                                                                value.value
                                                                        )
                                                                else -> {
                                                                        stack.pushMove(arrRef)
                                                                        stack.pushMove(idxVal)
                                                                        stack.pushMove(value)
                                                                        return VMResult.INVALID_VALUE_TYPE
                                                                }
                                                        }
                                                } catch (e: IndexOutOfBoundsException) {
                                                        stack.pushMove(arrRef)
                                                        stack.pushMove(idxVal)
                                                        stack.pushMove(value)
                                                        return VMResult.ARRAY_INDEX_OUT_OF_BOUNDS
                                                } catch (e: IllegalStateException) {
                                                        stack.pushMove(arrRef)
                                                        stack.pushMove(idxVal)
                                                        stack.pushMove(value)
                                                        return VMResult.INVALID_HEAP_ID
                                                }
                                        }
                                        VMOpcodes.PRINT -> {
                                                if (stack.size() < 1)
                                                        return VMResult.STACK_UNDERFLOW
                                                val v = stack.popMove()
                                                when (v) {
                                                        is Value.IntValue -> print(v.value)
                                                        is Value.FloatValue -> print(v.value)
                                                        is Value.BoolValue -> print(v.value)
                                                        else -> {
                                                                stack.pushMove(v)
                                                                return VMResult.INVALID_VALUE_TYPE
                                                        }
                                                }
                                        }
                                        VMOpcodes.PRINT_ARRAY -> {
                                                val arrRef =
                                                        safePopArrayRef()
                                                                ?: return if (stack.size() < 1)
                                                                        VMResult.STACK_UNDERFLOW
                                                                else VMResult.INVALID_VALUE_TYPE
                                                try {
                                                        val arrType =
                                                                mm.getArrayType(arrRef)
                                                                        ?: return VMResult.INVALID_ARRAY_TYPE
                                                        val size = mm.getArraySize(arrRef)
                                                        print("[")
                                                        when (arrType) {
                                                                "int" ->
                                                                        for (i in 0 until size) {
                                                                                if (i > 0)
                                                                                        print(", ")
                                                                                print(
                                                                                        mm.intArrayLoad(
                                                                                                arrRef,
                                                                                                i
                                                                                        )
                                                                                )
                                                                        }
                                                                "float" ->
                                                                        for (i in 0 until size) {
                                                                                if (i > 0)
                                                                                        print(", ")
                                                                                print(
                                                                                        mm.floatArrayLoad(
                                                                                                arrRef,
                                                                                                i
                                                                                        )
                                                                                )
                                                                        }
                                                                "bool" ->
                                                                        for (i in 0 until size) {
                                                                                if (i > 0)
                                                                                        print(", ")
                                                                                print(
                                                                                        mm.boolArrayLoad(
                                                                                                arrRef,
                                                                                                i
                                                                                        )
                                                                                )
                                                                        }
                                                                else ->
                                                                        return VMResult.INVALID_ARRAY_TYPE
                                                        }
                                                        print("]")
                                                } catch (e: IllegalStateException) {
                                                        stack.pushMove(arrRef)
                                                        return VMResult.INVALID_HEAP_ID
                                                }
                                        }
                                        else -> return VMResult.INVALID_OPCODE
                                } // end when

                                // If frame was removed (RETURN), don't auto-increment
                                if (callStack.size < stackSizeBefore) {
                                        continue
                                }

                                // Increment pc only if unchanged
                                if (frame.pc == pcBefore) {
                                        frame.pc += 4
                                }
                        } // end while

                        return VMResult.SUCCESS
                }
        }
}
