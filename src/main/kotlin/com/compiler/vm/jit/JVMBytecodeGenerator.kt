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
                try {
                        val className = "com.compiler.vm.jit.Generated_${function.name.replace('.', '_')}_${System.currentTimeMillis()}"
                        val internalName = className.replace('.', '/')

                        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)
                        cw.visit(AsmOpcodes.V1_8, AsmOpcodes.ACC_PUBLIC or AsmOpcodes.ACC_FINAL,
                                internalName, null, "java/lang/Object", null)

                        // public static Lcom/compiler/bytecode/BytecodeModule; MODULE;
                        val moduleDesc = "Lcom/compiler/bytecode/BytecodeModule;"
                        cw.visitField(AsmOpcodes.ACC_PUBLIC or AsmOpcodes.ACC_STATIC, "MODULE", moduleDesc, null, null).visitEnd()

                        // Decide whether this function can be specialized for int-only execution.
                        val canSpecialize = canSpecializeFunction(function)

                        if (!canSpecialize) {
                                // Fallback: generate delegating stub that calls JITRuntime.executeCompiled
                                generateDelegatingMethod(cw, internalName, function)
                        } else {
                                // Generate specialized implementation that handles integer-only opcodes
                                generateBoxHelpers(cw, internalName)
                                generateIntSpecializedMethod(cw, internalName, function)
                        }

                        cw.visitEnd()

                        val bytes = cw.toByteArray()

                        val loader = ByteArrayClassLoader()
                        val clazz = loader.defineClass(className, bytes)

                        // set MODULE static field
                        val f = clazz.getField("MODULE")
                        f.set(null, module)

                        val method: Method = clazz.getMethod("execute", CallFrame::class.java, RcOperandStack::class.java, MemoryManager::class.java)
                        return CompiledJVMFunction(clazz, method)
                } catch (t: Throwable) {
                        System.err.println("JIT: class generation failed for function '${function.name}': ${t.message}")
                        t.printStackTrace(System.err)
                        return null
                }
        }

        private fun canSpecializeFunction(function: CompiledFunction): Boolean {
                // Only specialize functions that use only integer-related opcodes and no CALL/ARRAY/PRINT/FLOAT
                val instr = function.instructions
                var i = 0
                while (i + 3 < instr.size) {
                        val op = instr[i].toUByte().toInt().toByte()
                        when (op) {
                                        VMOpcodes.PUSH_INT, VMOpcodes.POP,
                                        VMOpcodes.LOAD_LOCAL, VMOpcodes.STORE_LOCAL,
                                        VMOpcodes.ADD_INT, VMOpcodes.SUB_INT, VMOpcodes.MUL_INT, VMOpcodes.DIV_INT, VMOpcodes.MOD_INT, VMOpcodes.NEG_INT,
                                        VMOpcodes.EQ_INT, VMOpcodes.NE_INT, VMOpcodes.LT_INT, VMOpcodes.LE_INT, VMOpcodes.GT_INT, VMOpcodes.GE_INT,
                                        VMOpcodes.JUMP, VMOpcodes.JUMP_IF_FALSE, VMOpcodes.JUMP_IF_TRUE,
                                        VMOpcodes.CALL,
                                        VMOpcodes.NEW_ARRAY_INT, VMOpcodes.ARRAY_LOAD, VMOpcodes.ARRAY_STORE,
                                        VMOpcodes.RETURN, VMOpcodes.RETURN_VOID -> {
                                                // allowed
                                        }
                                else -> return false
                        }
                        i += 4
                }

                // ensure parameters and return type are integer/void
                val paramsOk = function.parameters.all { it.type is com.compiler.parser.ast.TypeNode.IntType }
                val retOk = (function.returnType is com.compiler.parser.ast.TypeNode.IntType) || (function.returnType is com.compiler.parser.ast.TypeNode.VoidType)
                return paramsOk && retOk
        }

        private fun generateDelegatingMethod(cw: ClassWriter, internalName: String, function: CompiledFunction) {
                val methodDesc = "(Lcom/compiler/vm/CallFrame;Lcom/compiler/memory/RcOperandStack;Lcom/compiler/memory/MemoryManager;)Lcom/compiler/vm/VMResult;"
                val mv: MethodVisitor = cw.visitMethod(AsmOpcodes.ACC_PUBLIC or AsmOpcodes.ACC_STATIC,
                        "execute", methodDesc, null, null)
                mv.visitCode()

                // load MODULE
                mv.visitFieldInsn(AsmOpcodes.GETSTATIC, internalName, "MODULE", "Lcom/compiler/bytecode/BytecodeModule;")
                // load function name (const)
                mv.visitLdcInsn(function.name)
                // load method args: (CallFrame, RcOperandStack, MemoryManager)
                mv.visitVarInsn(AsmOpcodes.ALOAD, 0)
                mv.visitVarInsn(AsmOpcodes.ALOAD, 1)
                mv.visitVarInsn(AsmOpcodes.ALOAD, 2)

                // invoke JITRuntime.executeCompiled(module, functionName, frame, stack, mm)
                val owner = "com/compiler/vm/jit/JITRuntime"
                val runtimeDesc = "(Lcom/compiler/bytecode/BytecodeModule;Ljava/lang/String;Lcom/compiler/vm/CallFrame;Lcom/compiler/memory/RcOperandStack;Lcom/compiler/memory/MemoryManager;)Lcom/compiler/vm/VMResult;"
                mv.visitMethodInsn(AsmOpcodes.INVOKESTATIC, owner, "executeCompiled", runtimeDesc, false)

                // return value
                mv.visitInsn(AsmOpcodes.ARETURN)
                mv.visitMaxs(0, 0)
                mv.visitEnd()
        }

        private fun generateBoxHelpers(cw: ClassWriter, internalName: String) {
                // makeInt(long) : Value
                var mv: MethodVisitor = cw.visitMethod(AsmOpcodes.ACC_PRIVATE or AsmOpcodes.ACC_STATIC, "makeInt", "(J)Lcom/compiler/bytecode/Value;", null, null)
                mv.visitCode()
                mv.visitTypeInsn(AsmOpcodes.NEW, "com/compiler/bytecode/Value\$IntValue")
                mv.visitInsn(AsmOpcodes.DUP)
                mv.visitVarInsn(AsmOpcodes.LLOAD, 0)
                mv.visitMethodInsn(AsmOpcodes.INVOKESPECIAL, "com/compiler/bytecode/Value\$IntValue", "<init>", "(J)V", false)
                mv.visitInsn(AsmOpcodes.ARETURN)
                mv.visitMaxs(0, 0)
                mv.visitEnd()

                // makeBool(boolean) : Value
                mv = cw.visitMethod(AsmOpcodes.ACC_PRIVATE or AsmOpcodes.ACC_STATIC, "makeBool", "(Z)Lcom/compiler/bytecode/Value;", null, null)
                mv.visitCode()
                mv.visitTypeInsn(AsmOpcodes.NEW, "com/compiler/bytecode/Value\$BoolValue")
                mv.visitInsn(AsmOpcodes.DUP)
                mv.visitVarInsn(AsmOpcodes.ILOAD, 0)
                mv.visitMethodInsn(AsmOpcodes.INVOKESPECIAL, "com/compiler/bytecode/Value\$BoolValue", "<init>", "(Z)V", false)
                mv.visitInsn(AsmOpcodes.ARETURN)
                mv.visitMaxs(0, 0)
                mv.visitEnd()
        }

        private fun generateIntSpecializedMethod(cw: ClassWriter, internalName: String, function: CompiledFunction) {
                val methodDesc = "(Lcom/compiler/vm/CallFrame;Lcom/compiler/memory/RcOperandStack;Lcom/compiler/memory/MemoryManager;)Lcom/compiler/vm/VMResult;"
                val mv: MethodVisitor = cw.visitMethod(AsmOpcodes.ACC_PUBLIC or AsmOpcodes.ACC_STATIC,
                        "execute", methodDesc, null, null)
                mv.visitCode()

                val instr = function.instructions
                val instrCount = instr.size / 4
                val labels = Array(instrCount) { org.objectweb.asm.Label() }

                // reserve temp local slots: params occupy 0..2, temps start at 3
                val tempA = 3 // Value temp for a
                val tempB = 4 // Value temp for b

                // Predefine labels
                for (i in 0 until instrCount) mv.visitLabel(labels[i])

                // Create primitive stack and primitive locals arrays to hold unboxed ints
                // primStack (long[]) at local index 120, primTop (int) at 121, primLocals (long[]) at 122
                val primStackIndex = 120
                val primTopIndex = 121
                val primLocalsIndex = 122

                // primStack = new long[1024]
                mv.visitLdcInsn(1024)
                mv.visitIntInsn(AsmOpcodes.NEWARRAY, AsmOpcodes.T_LONG)
                mv.visitVarInsn(AsmOpcodes.ASTORE, primStackIndex)
                // primTop = 0
                mv.visitInsn(AsmOpcodes.ICONST_0)
                mv.visitVarInsn(AsmOpcodes.ISTORE, primTopIndex)

                // primLocals = new long[function.localsCount]
                val localsCount = function.localsCount
                if (localsCount <= 0) {
                        mv.visitLdcInsn(1)
                } else {
                        mv.visitLdcInsn(localsCount)
                }
                mv.visitIntInsn(AsmOpcodes.NEWARRAY, AsmOpcodes.T_LONG)
                mv.visitVarInsn(AsmOpcodes.ASTORE, primLocalsIndex)

                // primLocalFlags = new int[function.localsCount]
                if (localsCount <= 0) {
                        mv.visitLdcInsn(1)
                } else {
                        mv.visitLdcInsn(localsCount)
                }
                mv.visitIntInsn(AsmOpcodes.NEWARRAY, AsmOpcodes.T_INT)
                mv.visitVarInsn(AsmOpcodes.ASTORE, 123)

                // Unpack integer parameters into primLocals array
                val paramCount = function.parameters.size
                if (paramCount > 0) {
                        var pi = 0
                        while (pi < paramCount) {
                                // v = frame.locals.getRaw(pi)
                                mv.visitVarInsn(AsmOpcodes.ALOAD, 0)
                                mv.visitFieldInsn(AsmOpcodes.GETFIELD, "com/compiler/vm/CallFrame", "locals", "Lcom/compiler/memory/RcLocals;")
                                mv.visitLdcInsn(pi)
                                mv.visitMethodInsn(AsmOpcodes.INVOKEVIRTUAL, "com/compiler/memory/RcLocals", "getRaw", "(I)Lcom/compiler/bytecode/Value;", false)
                                mv.visitVarInsn(AsmOpcodes.ASTORE, 50)

                                val notNullLabel = org.objectweb.asm.Label()
                                val contLabel = org.objectweb.asm.Label()

                                mv.visitVarInsn(AsmOpcodes.ALOAD, 50)
                                mv.visitJumpInsn(AsmOpcodes.IFNULL, notNullLabel)

                                mv.visitVarInsn(AsmOpcodes.ALOAD, 50)
                                mv.visitTypeInsn(AsmOpcodes.INSTANCEOF, "com/compiler/bytecode/Value\$IntValue")
                                mv.visitJumpInsn(AsmOpcodes.IFNE, contLabel)

                                // Not an int -> return INVALID_VALUE_TYPE
                                mv.visitFieldInsn(AsmOpcodes.GETSTATIC, "com/compiler/vm/VMResult", "INVALID_VALUE_TYPE", "Lcom/compiler/vm/VMResult;")
                                mv.visitInsn(AsmOpcodes.ARETURN)

                                mv.visitLabel(contLabel)
                                // extract long
                                mv.visitVarInsn(AsmOpcodes.ALOAD, 50)
                                mv.visitTypeInsn(AsmOpcodes.CHECKCAST, "com/compiler/bytecode/Value\$IntValue")
                                mv.visitMethodInsn(AsmOpcodes.INVOKEVIRTUAL, "com/compiler/bytecode/Value\$IntValue", "getValue", "()J", false)
                                // store into primLocals[pi]
                                mv.visitVarInsn(AsmOpcodes.ALOAD, primLocalsIndex)
                                mv.visitLdcInsn(pi)
                                mv.visitInsn(AsmOpcodes.SWAP)
                                mv.visitInsn(AsmOpcodes.LASTORE)

                                mv.visitLabel(notNullLabel)
                                // if null, set 0
                                mv.visitVarInsn(AsmOpcodes.ALOAD, 50)
                                val skipZero = org.objectweb.asm.Label()
                                mv.visitJumpInsn(AsmOpcodes.IFNONNULL, skipZero)
                                mv.visitVarInsn(AsmOpcodes.ALOAD, primLocalsIndex)
                                mv.visitLdcInsn(pi)
                                mv.visitLdcInsn(0L)
                                mv.visitInsn(AsmOpcodes.LASTORE)
                                mv.visitLabel(skipZero)

                                pi++
                        }
                }

                var idx = 0
                while (idx < instrCount) {
                        val pc = idx * 4
                        val opcode = instr[pc].toUByte().toInt()
                        val operand = if (pc + 3 < instr.size) {
                                ((instr[pc + 1].toUByte().toInt() shl 16) or (instr[pc + 2].toUByte().toInt() shl 8) or instr[pc + 3].toUByte().toInt())
                        } else 0

                        // Visit label for this instruction
                        mv.visitLabel(labels[idx])

                        when (opcode.toByte()) {
                                VMOpcodes.PUSH_INT -> {
                                        val constVal = module.intConstants[operand]
                                        // primStack[primTop++] = constVal
                                        mv.visitVarInsn(AsmOpcodes.ALOAD, primStackIndex)
                                        mv.visitVarInsn(AsmOpcodes.ILOAD, primTopIndex)
                                        mv.visitLdcInsn(constVal)
                                        mv.visitInsn(AsmOpcodes.LASTORE)
                                        mv.visitIincInsn(primTopIndex, 1)
                                }

                                VMOpcodes.POP -> {
                                        // if primTop>0 then primTop-- else operandStack.popDrop()
                                        val hasPrim = org.objectweb.asm.Label()
                                        val afterPop = org.objectweb.asm.Label()
                                        mv.visitVarInsn(AsmOpcodes.ILOAD, primTopIndex)
                                        mv.visitJumpInsn(AsmOpcodes.IFNE, hasPrim)
                                        // call operandStack.popDrop()
                                        mv.visitVarInsn(AsmOpcodes.ALOAD, 1)
                                        mv.visitMethodInsn(AsmOpcodes.INVOKEVIRTUAL, "com/compiler/memory/RcOperandStack", "popDrop", "()V", false)
                                        mv.visitJumpInsn(AsmOpcodes.GOTO, afterPop)
                                        mv.visitLabel(hasPrim)
                                        mv.visitIincInsn(primTopIndex, -1)
                                        mv.visitLabel(afterPop)
                                }

                                VMOpcodes.NEW_ARRAY_INT -> {
                                               // flush prim stack to operandStack before object ops
                                               run {
                                                       val loopStart = org.objectweb.asm.Label()
                                                       val loopEnd = org.objectweb.asm.Label()
                                                       // i = 0
                                                       mv.visitInsn(AsmOpcodes.ICONST_0)
                                                       mv.visitVarInsn(AsmOpcodes.ISTORE, 300)
                                                       mv.visitLabel(loopStart)
                                                       mv.visitVarInsn(AsmOpcodes.ILOAD, 300)
                                                       mv.visitVarInsn(AsmOpcodes.ILOAD, primTopIndex)
                                                       mv.visitJumpInsn(AsmOpcodes.IF_ICMPGE, loopEnd)
                                                       // operandStack.pushMove(makeInt(primStack[i]))
                                                       mv.visitVarInsn(AsmOpcodes.ALOAD, 1)
                                                       mv.visitVarInsn(AsmOpcodes.ALOAD, primStackIndex)
                                                       mv.visitVarInsn(AsmOpcodes.ILOAD, 300)
                                                       mv.visitInsn(AsmOpcodes.LALOAD)
                                                       mv.visitMethodInsn(AsmOpcodes.INVOKESTATIC, internalName, "makeInt", "(J)Lcom/compiler/bytecode/Value;", false)
                                                       mv.visitMethodInsn(AsmOpcodes.INVOKEVIRTUAL, "com/compiler/memory/RcOperandStack", "pushMove", "(Lcom/compiler/bytecode/Value;)V", false)
                                                       // i++
                                                       mv.visitIincInsn(300, 1)
                                                       mv.visitJumpInsn(AsmOpcodes.GOTO, loopStart)
                                                       mv.visitLabel(loopEnd)
                                                       // primTop = 0
                                                       mv.visitInsn(AsmOpcodes.ICONST_0)
                                                       mv.visitVarInsn(AsmOpcodes.ISTORE, primTopIndex)
                                               }
                                        // sizeVal = operandStack.popMove(); if not IntValue -> push back and return INVALID_VALUE_TYPE
                                        mv.visitVarInsn(AsmOpcodes.ALOAD, 1)
                                        mv.visitMethodInsn(AsmOpcodes.INVOKEVIRTUAL, "com/compiler/memory/RcOperandStack", "popMove", "()Lcom/compiler/bytecode/Value;", false)
                                        mv.visitVarInsn(AsmOpcodes.ASTORE, tempA)
                                        // get size
                                        mv.visitVarInsn(AsmOpcodes.ALOAD, tempA)
                                        mv.visitTypeInsn(AsmOpcodes.CHECKCAST, "com/compiler/bytecode/Value\$IntValue")
                                        mv.visitMethodInsn(AsmOpcodes.INVOKEVIRTUAL, "com/compiler/bytecode/Value\$IntValue", "getValue", "()J", false)
                                        mv.visitVarInsn(AsmOpcodes.LSTORE, 5)
                                        // check negative
                                        mv.visitVarInsn(AsmOpcodes.LLOAD, 5)
                                        mv.visitLdcInsn(0L)
                                        mv.visitInsn(AsmOpcodes.LCMP)
                                        val okLabelArrNew = org.objectweb.asm.Label()
                                        mv.visitJumpInsn(AsmOpcodes.IFGE, okLabelArrNew)
                                        // negative -> push sizeVal back and return ARRAY_INDEX_OUT_OF_BOUNDS
                                        mv.visitVarInsn(AsmOpcodes.ALOAD, 1)
                                        mv.visitVarInsn(AsmOpcodes.ALOAD, tempA)
                                        mv.visitMethodInsn(AsmOpcodes.INVOKEVIRTUAL, "com/compiler/memory/RcOperandStack", "pushMove", "(Lcom/compiler/bytecode/Value;)V", false)
                                        mv.visitFieldInsn(AsmOpcodes.GETSTATIC, "com/compiler/vm/VMResult", "ARRAY_INDEX_OUT_OF_BOUNDS", "Lcom/compiler/vm/VMResult;")
                                        mv.visitInsn(AsmOpcodes.ARETURN)
                                        mv.visitLabel(okLabelArrNew)
                                        // call memoryManager.newIntArray((int)size)
                                        mv.visitVarInsn(AsmOpcodes.ALOAD, 1) // operandStack
                                        mv.visitVarInsn(AsmOpcodes.ALOAD, 2) // memoryManager
                                        mv.visitVarInsn(AsmOpcodes.LLOAD, 5)
                                        mv.visitInsn(AsmOpcodes.L2I)
                                        mv.visitMethodInsn(AsmOpcodes.INVOKEVIRTUAL, "com/compiler/memory/MemoryManager", "newIntArray", "(I)Lcom/compiler/bytecode/Value\$ArrayRef;", false)
                                        mv.visitMethodInsn(AsmOpcodes.INVOKEVIRTUAL, "com/compiler/memory/RcOperandStack", "pushMove", "(Lcom/compiler/bytecode/Value;)V", false)
                                }

                                VMOpcodes.LOAD_LOCAL -> {
                                        // if primLocalFlags[operand]==1 -> push primLocals[operand] onto primStack
                                        mv.visitVarInsn(AsmOpcodes.ALOAD, primLocalsIndex)
                                        mv.visitLdcInsn(operand)
                                        mv.visitInsn(AsmOpcodes.LALOAD)
                                        // We'll use primLocalFlags array stored at local 123: int[] primLocalFlags
                                        mv.visitVarInsn(AsmOpcodes.ALOAD, 123)
                                        mv.visitLdcInsn(operand)
                                        mv.visitInsn(AsmOpcodes.IALOAD)
                                        val isPrim = org.objectweb.asm.Label()
                                        val afterLoad = org.objectweb.asm.Label()
                                        mv.visitJumpInsn(AsmOpcodes.IFNE, isPrim)
                                        // object path
                                        mv.visitVarInsn(AsmOpcodes.ALOAD, 0)
                                        mv.visitFieldInsn(AsmOpcodes.GETFIELD, "com/compiler/vm/CallFrame", "locals", "Lcom/compiler/memory/RcLocals;")
                                        mv.visitLdcInsn(operand)
                                        mv.visitMethodInsn(AsmOpcodes.INVOKEVIRTUAL, "com/compiler/memory/RcLocals", "getCopy", "(I)Lcom/compiler/bytecode/Value;", false)
                                        mv.visitVarInsn(AsmOpcodes.ASTORE, tempA)
                                        mv.visitVarInsn(AsmOpcodes.ALOAD, 1)
                                        mv.visitVarInsn(AsmOpcodes.ALOAD, tempA)
                                        mv.visitMethodInsn(AsmOpcodes.INVOKEVIRTUAL, "com/compiler/memory/RcOperandStack", "pushCopy", "(Lcom/compiler/bytecode/Value;)V", false)
                                        mv.visitJumpInsn(AsmOpcodes.GOTO, afterLoad)
                                        mv.visitLabel(isPrim)
                                        // push primLocals[operand] onto primStack
                                        mv.visitVarInsn(AsmOpcodes.ALOAD, primStackIndex)
                                        mv.visitVarInsn(AsmOpcodes.ILOAD, primTopIndex)
                                        mv.visitVarInsn(AsmOpcodes.ALOAD, primLocalsIndex)
                                        mv.visitLdcInsn(operand)
                                        mv.visitInsn(AsmOpcodes.LALOAD)
                                        mv.visitInsn(AsmOpcodes.LASTORE)
                                        mv.visitIincInsn(primTopIndex, 1)
                                        mv.visitLabel(afterLoad)
                                }

                                VMOpcodes.STORE_LOCAL -> {
                                        // if primTop>0: pop primitive and store into primLocals[operand] and set flag=1
                                        val hasPrim = org.objectweb.asm.Label()
                                        val afterStore = org.objectweb.asm.Label()
                                        mv.visitVarInsn(AsmOpcodes.ILOAD, primTopIndex)
                                        mv.visitJumpInsn(AsmOpcodes.IFNE, hasPrim)
                                        // object path
                                        mv.visitVarInsn(AsmOpcodes.ALOAD, 1)
                                        mv.visitMethodInsn(AsmOpcodes.INVOKEVIRTUAL, "com/compiler/memory/RcOperandStack", "popMove", "()Lcom/compiler/bytecode/Value;", false)
                                        mv.visitVarInsn(AsmOpcodes.ASTORE, tempA)
                                        mv.visitVarInsn(AsmOpcodes.ALOAD, 0)
                                        mv.visitFieldInsn(AsmOpcodes.GETFIELD, "com/compiler/vm/CallFrame", "locals", "Lcom/compiler/memory/RcLocals;")
                                        mv.visitLdcInsn(operand)
                                        mv.visitVarInsn(AsmOpcodes.ALOAD, tempA)
                                        mv.visitMethodInsn(AsmOpcodes.INVOKEVIRTUAL, "com/compiler/memory/RcLocals", "setMove", "(ILcom/compiler/bytecode/Value;)V", false)
                                        // primLocalFlags[operand] = 0
                                        mv.visitVarInsn(AsmOpcodes.ALOAD, 123)
                                        mv.visitLdcInsn(operand)
                                        mv.visitInsn(AsmOpcodes.ICONST_0)
                                        mv.visitInsn(AsmOpcodes.IASTORE)
                                        mv.visitJumpInsn(AsmOpcodes.GOTO, afterStore)
                                        mv.visitLabel(hasPrim)
                                        // tmpIdx = primTop-1
                                        mv.visitVarInsn(AsmOpcodes.ILOAD, primTopIndex)
                                        mv.visitInsn(AsmOpcodes.ICONST_1)
                                        mv.visitInsn(AsmOpcodes.ISUB)
                                        mv.visitVarInsn(AsmOpcodes.ISTORE, 301)
                                        // value = primStack[tmpIdx]
                                        mv.visitVarInsn(AsmOpcodes.ALOAD, primLocalsIndex)
                                        mv.visitLdcInsn(operand)
                                        mv.visitVarInsn(AsmOpcodes.ALOAD, primStackIndex)
                                        mv.visitVarInsn(AsmOpcodes.ILOAD, 301)
                                        mv.visitInsn(AsmOpcodes.LALOAD)
                                        mv.visitInsn(AsmOpcodes.LASTORE)
                                        // primTop--
                                        mv.visitIincInsn(primTopIndex, -1)
                                        // primLocalFlags[operand] = 1
                                        mv.visitVarInsn(AsmOpcodes.ALOAD, 123)
                                        mv.visitLdcInsn(operand)
                                        mv.visitInsn(AsmOpcodes.ICONST_1)
                                        mv.visitInsn(AsmOpcodes.IASTORE)
                                        mv.visitLabel(afterStore)
                                }

                                VMOpcodes.ADD_INT, VMOpcodes.SUB_INT, VMOpcodes.MUL_INT, VMOpcodes.DIV_INT, VMOpcodes.MOD_INT -> {
                                        // operate on primStack directly: pop b, pop a, compute, push result
                                        // pop b
                                        mv.visitIincInsn(primTopIndex, -1)
                                        mv.visitVarInsn(AsmOpcodes.ALOAD, primStackIndex)
                                        mv.visitVarInsn(AsmOpcodes.ILOAD, primTopIndex)
                                        mv.visitInsn(AsmOpcodes.LALOAD)
                                        mv.visitVarInsn(AsmOpcodes.LSTORE, 7)

                                        // pop a
                                        mv.visitIincInsn(primTopIndex, -1)
                                        mv.visitVarInsn(AsmOpcodes.ALOAD, primStackIndex)
                                        mv.visitVarInsn(AsmOpcodes.ILOAD, primTopIndex)
                                        mv.visitInsn(AsmOpcodes.LALOAD)
                                        mv.visitVarInsn(AsmOpcodes.LSTORE, 5)

                                        when (opcode.toByte()) {
                                                VMOpcodes.ADD_INT -> {
                                                        mv.visitVarInsn(AsmOpcodes.LLOAD, 5)
                                                        mv.visitVarInsn(AsmOpcodes.LLOAD, 7)
                                                        mv.visitInsn(AsmOpcodes.LADD)
                                                }
                                                VMOpcodes.SUB_INT -> {
                                                        mv.visitVarInsn(AsmOpcodes.LLOAD, 5)
                                                        mv.visitVarInsn(AsmOpcodes.LLOAD, 7)
                                                        mv.visitInsn(AsmOpcodes.LSUB)
                                                }
                                                VMOpcodes.MUL_INT -> {
                                                        mv.visitVarInsn(AsmOpcodes.LLOAD, 5)
                                                        mv.visitVarInsn(AsmOpcodes.LLOAD, 7)
                                                        mv.visitInsn(AsmOpcodes.LMUL)
                                                }
                                                VMOpcodes.DIV_INT -> {
                                                        // if b==0 -> box a and b back to operandStack and return DIVISION_BY_ZERO
                                                        mv.visitVarInsn(AsmOpcodes.LLOAD, 7)
                                                        mv.visitLdcInsn(0L)
                                                        mv.visitInsn(AsmOpcodes.LCMP)
                                                        val contLabel = org.objectweb.asm.Label()
                                                        mv.visitJumpInsn(AsmOpcodes.IFNE, contLabel)
                                                        // box a
                                                        mv.visitVarInsn(AsmOpcodes.ALOAD, 1)
                                                        mv.visitVarInsn(AsmOpcodes.LLOAD, 5)
                                                        mv.visitMethodInsn(AsmOpcodes.INVOKESTATIC, internalName, "makeInt", "(J)Lcom/compiler/bytecode/Value;", false)
                                                        mv.visitMethodInsn(AsmOpcodes.INVOKEVIRTUAL, "com/compiler/memory/RcOperandStack", "pushMove", "(Lcom/compiler/bytecode/Value;)V", false)
                                                        // box b
                                                        mv.visitVarInsn(AsmOpcodes.ALOAD, 1)
                                                        mv.visitVarInsn(AsmOpcodes.LLOAD, 7)
                                                        mv.visitMethodInsn(AsmOpcodes.INVOKESTATIC, internalName, "makeInt", "(J)Lcom/compiler/bytecode/Value;", false)
                                                        mv.visitMethodInsn(AsmOpcodes.INVOKEVIRTUAL, "com/compiler/memory/RcOperandStack", "pushMove", "(Lcom/compiler/bytecode/Value;)V", false)
                                                        mv.visitFieldInsn(AsmOpcodes.GETSTATIC, "com/compiler/vm/VMResult", "DIVISION_BY_ZERO", "Lcom/compiler/vm/VMResult;")
                                                        mv.visitInsn(AsmOpcodes.ARETURN)
                                                        mv.visitLabel(contLabel)
                                                        mv.visitVarInsn(AsmOpcodes.LLOAD, 5)
                                                        mv.visitVarInsn(AsmOpcodes.LLOAD, 7)
                                                        mv.visitInsn(AsmOpcodes.LDIV)
                                                }
                                                VMOpcodes.MOD_INT -> {
                                                        mv.visitVarInsn(AsmOpcodes.LLOAD, 5)
                                                        mv.visitVarInsn(AsmOpcodes.LLOAD, 7)
                                                        mv.visitInsn(AsmOpcodes.LREM)
                                                }
                                        }

                                        // push result onto primStack
                                        mv.visitVarInsn(AsmOpcodes.ALOAD, primStackIndex)
                                        mv.visitVarInsn(AsmOpcodes.ILOAD, primTopIndex)
                                        mv.visitInsn(AsmOpcodes.LASTORE)
                                        mv.visitIincInsn(primTopIndex, 1)
                                }

                                VMOpcodes.ARRAY_LOAD -> {
                                        // indexVal = popMove(); arrayRef = popMove(); check types
                                        mv.visitVarInsn(AsmOpcodes.ALOAD, 1)
                                        mv.visitMethodInsn(AsmOpcodes.INVOKEVIRTUAL, "com/compiler/memory/RcOperandStack", "popMove", "()Lcom/compiler/bytecode/Value;", false)
                                        mv.visitVarInsn(AsmOpcodes.ASTORE, tempA) // indexVal

                                        mv.visitVarInsn(AsmOpcodes.ALOAD, 1)
                                        mv.visitMethodInsn(AsmOpcodes.INVOKEVIRTUAL, "com/compiler/memory/RcOperandStack", "popMove", "()Lcom/compiler/bytecode/Value;", false)
                                        mv.visitVarInsn(AsmOpcodes.ASTORE, tempB) // arrayRef

                                        // check types and get index
                                        mv.visitVarInsn(AsmOpcodes.ALOAD, tempA)
                                        mv.visitTypeInsn(AsmOpcodes.CHECKCAST, "com/compiler/bytecode/Value\$IntValue")
                                        mv.visitMethodInsn(AsmOpcodes.INVOKEVIRTUAL, "com/compiler/bytecode/Value\$IntValue", "getValue", "()J", false)
                                        mv.visitVarInsn(AsmOpcodes.LSTORE, 5)

                                        // check array type == "int"
                                        mv.visitLdcInsn("int")
                                        mv.visitVarInsn(AsmOpcodes.ALOAD, 2)
                                        mv.visitVarInsn(AsmOpcodes.ALOAD, tempB)
                                        mv.visitMethodInsn(AsmOpcodes.INVOKEVIRTUAL, "com/compiler/memory/MemoryManager", "getArrayType", "(Lcom/compiler/bytecode/Value\$ArrayRef;)Ljava/lang/String;", false)
                                        mv.visitMethodInsn(AsmOpcodes.INVOKEVIRTUAL, "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false)
                                        val okLoad = org.objectweb.asm.Label()
                                        mv.visitJumpInsn(AsmOpcodes.IFNE, okLoad)
                                        // invalid array type -> push back and return INVALID_ARRAY_TYPE
                                        mv.visitVarInsn(AsmOpcodes.ALOAD, 1)
                                        mv.visitVarInsn(AsmOpcodes.ALOAD, tempB)
                                        mv.visitMethodInsn(AsmOpcodes.INVOKEVIRTUAL, "com/compiler/memory/RcOperandStack", "pushMove", "(Lcom/compiler/bytecode/Value;)V", false)
                                        mv.visitVarInsn(AsmOpcodes.ALOAD, 1)
                                        mv.visitVarInsn(AsmOpcodes.ALOAD, tempA)
                                        mv.visitMethodInsn(AsmOpcodes.INVOKEVIRTUAL, "com/compiler/memory/RcOperandStack", "pushMove", "(Lcom/compiler/bytecode/Value;)V", false)
                                        mv.visitFieldInsn(AsmOpcodes.GETSTATIC, "com/compiler/vm/VMResult", "INVALID_ARRAY_TYPE", "Lcom/compiler/vm/VMResult;")
                                        mv.visitInsn(AsmOpcodes.ARETURN)
                                        mv.visitLabel(okLoad)

                                        // perform load: val = memoryManager.intArrayLoad(arrayRef, index)
                                        mv.visitVarInsn(AsmOpcodes.ALOAD, 1)
                                        mv.visitVarInsn(AsmOpcodes.ALOAD, 2)
                                        mv.visitVarInsn(AsmOpcodes.ALOAD, tempB)
                                        mv.visitVarInsn(AsmOpcodes.LLOAD, 5)
                                        mv.visitInsn(AsmOpcodes.L2I)
                                        mv.visitMethodInsn(AsmOpcodes.INVOKEVIRTUAL, "com/compiler/memory/MemoryManager", "intArrayLoad", "(Lcom/compiler/bytecode/Value\$ArrayRef;I)J", false)
                                        // box via makeInt and push
                                        mv.visitMethodInsn(AsmOpcodes.INVOKESTATIC, internalName, "makeInt", "(J)Lcom/compiler/bytecode/Value;", false)
                                        mv.visitMethodInsn(AsmOpcodes.INVOKEVIRTUAL, "com/compiler/memory/RcOperandStack", "pushMove", "(Lcom/compiler/bytecode/Value;)V", false)
                                }

                                VMOpcodes.ARRAY_STORE -> {
                                        // value = popMove(); indexVal = popMove(); arrayRef = popMove();
                                        mv.visitVarInsn(AsmOpcodes.ALOAD, 1)
                                        mv.visitMethodInsn(AsmOpcodes.INVOKEVIRTUAL, "com/compiler/memory/RcOperandStack", "popMove", "()Lcom/compiler/bytecode/Value;", false)
                                        mv.visitVarInsn(AsmOpcodes.ASTORE, tempA) // value

                                        mv.visitVarInsn(AsmOpcodes.ALOAD, 1)
                                        mv.visitMethodInsn(AsmOpcodes.INVOKEVIRTUAL, "com/compiler/memory/RcOperandStack", "popMove", "()Lcom/compiler/bytecode/Value;", false)
                                        mv.visitVarInsn(AsmOpcodes.ASTORE, tempB) // index

                                        mv.visitVarInsn(AsmOpcodes.ALOAD, 1)
                                        mv.visitMethodInsn(AsmOpcodes.INVOKEVIRTUAL, "com/compiler/memory/RcOperandStack", "popMove", "()Lcom/compiler/bytecode/Value;", false)
                                        mv.visitVarInsn(AsmOpcodes.ASTORE, 6) // arrayRef

                                        // check types
                                        mv.visitVarInsn(AsmOpcodes.ALOAD, tempB)
                                        mv.visitTypeInsn(AsmOpcodes.CHECKCAST, "com/compiler/bytecode/Value\$IntValue")
                                        mv.visitMethodInsn(AsmOpcodes.INVOKEVIRTUAL, "com/compiler/bytecode/Value\$IntValue", "getValue", "()J", false)
                                        mv.visitVarInsn(AsmOpcodes.LSTORE, 5)

                                        mv.visitVarInsn(AsmOpcodes.ALOAD, tempA)
                                        mv.visitTypeInsn(AsmOpcodes.CHECKCAST, "com/compiler/bytecode/Value\$IntValue")
                                        mv.visitMethodInsn(AsmOpcodes.INVOKEVIRTUAL, "com/compiler/bytecode/Value\$IntValue", "getValue", "()J", false)
                                        mv.visitVarInsn(AsmOpcodes.LSTORE, 7)

                                        // call memoryManager.intArrayStore(arrayRef, index, value)
                                        mv.visitVarInsn(AsmOpcodes.ALOAD, 2)
                                        mv.visitVarInsn(AsmOpcodes.ALOAD, 6)
                                        mv.visitVarInsn(AsmOpcodes.LLOAD, 5)
                                        mv.visitInsn(AsmOpcodes.L2I)
                                        mv.visitVarInsn(AsmOpcodes.LLOAD, 7)
                                        mv.visitMethodInsn(AsmOpcodes.INVOKEVIRTUAL, "com/compiler/memory/MemoryManager", "intArrayStore", "(Lcom/compiler/bytecode/Value\$ArrayRef;IJ)V", false)
                                }

                                VMOpcodes.NEG_INT -> {
                                        // pop primitive, negate, push primitive result
                                        mv.visitIincInsn(primTopIndex, -1)
                                        mv.visitVarInsn(AsmOpcodes.ALOAD, primStackIndex)
                                        mv.visitVarInsn(AsmOpcodes.ILOAD, primTopIndex)
                                        mv.visitInsn(AsmOpcodes.LALOAD)
                                        mv.visitVarInsn(AsmOpcodes.LSTORE, 5)
                                        mv.visitVarInsn(AsmOpcodes.LLOAD, 5)
                                        mv.visitInsn(AsmOpcodes.LNEG)
                                        mv.visitVarInsn(AsmOpcodes.ALOAD, primStackIndex)
                                        mv.visitVarInsn(AsmOpcodes.ILOAD, primTopIndex)
                                        mv.visitInsn(AsmOpcodes.LASTORE)
                                        mv.visitIincInsn(primTopIndex, 1)
                                }

                                VMOpcodes.EQ_INT, VMOpcodes.NE_INT, VMOpcodes.LT_INT, VMOpcodes.LE_INT, VMOpcodes.GT_INT, VMOpcodes.GE_INT -> {
                                        // pop primitives b and a, compare, box BoolValue and push
                                        // pop b
                                        mv.visitIincInsn(primTopIndex, -1)
                                        mv.visitVarInsn(AsmOpcodes.ALOAD, primStackIndex)
                                        mv.visitVarInsn(AsmOpcodes.ILOAD, primTopIndex)
                                        mv.visitInsn(AsmOpcodes.LALOAD)
                                        mv.visitVarInsn(AsmOpcodes.LSTORE, 7)
                                        // pop a
                                        mv.visitIincInsn(primTopIndex, -1)
                                        mv.visitVarInsn(AsmOpcodes.ALOAD, primStackIndex)
                                        mv.visitVarInsn(AsmOpcodes.ILOAD, primTopIndex)
                                        mv.visitInsn(AsmOpcodes.LALOAD)
                                        mv.visitVarInsn(AsmOpcodes.LSTORE, 5)

                                        mv.visitVarInsn(AsmOpcodes.LLOAD, 5)
                                        mv.visitVarInsn(AsmOpcodes.LLOAD, 7)
                                        mv.visitInsn(AsmOpcodes.LCMP)
                                        val trueLabelCmp = org.objectweb.asm.Label()
                                        val endLabelCmp = org.objectweb.asm.Label()
                                        when (opcode.toByte()) {
                                                VMOpcodes.EQ_INT -> mv.visitJumpInsn(AsmOpcodes.IFEQ, trueLabelCmp)
                                                VMOpcodes.NE_INT -> mv.visitJumpInsn(AsmOpcodes.IFNE, trueLabelCmp)
                                                VMOpcodes.LT_INT -> mv.visitJumpInsn(AsmOpcodes.IFLT, trueLabelCmp)
                                                VMOpcodes.LE_INT -> mv.visitJumpInsn(AsmOpcodes.IFLE, trueLabelCmp)
                                                VMOpcodes.GT_INT -> mv.visitJumpInsn(AsmOpcodes.IFGT, trueLabelCmp)
                                                VMOpcodes.GE_INT -> mv.visitJumpInsn(AsmOpcodes.IFGE, trueLabelCmp)
                                        }
                                        mv.visitInsn(AsmOpcodes.ICONST_0)
                                        mv.visitJumpInsn(AsmOpcodes.GOTO, endLabelCmp)
                                        mv.visitLabel(trueLabelCmp)
                                        mv.visitInsn(AsmOpcodes.ICONST_1)
                                        mv.visitLabel(endLabelCmp)
                                        mv.visitMethodInsn(AsmOpcodes.INVOKESTATIC, internalName, "makeBool", "(Z)Lcom/compiler/bytecode/Value;", false)
                                        mv.visitMethodInsn(AsmOpcodes.INVOKEVIRTUAL, "com/compiler/memory/RcOperandStack", "pushMove", "(Lcom/compiler/bytecode/Value;)V", false)
                                }

                                VMOpcodes.JUMP -> {
                                        // target = idx + signedOperand + 1
                                        val signed = if (operand and 0x800000 != 0) operand or 0xFF000000.toInt() else operand
                                        val target = idx + signed + 1
                                        if (target < 0 || target >= instrCount) {
                                                mv.visitFieldInsn(AsmOpcodes.GETSTATIC, "com/compiler/vm/VMResult", "INVALID_OPCODE", "Lcom/compiler/vm/VMResult;")
                                                mv.visitInsn(AsmOpcodes.ARETURN)
                                        } else {
                                                mv.visitJumpInsn(AsmOpcodes.GOTO, labels[target])
                                        }
                                }

                                VMOpcodes.JUMP_IF_FALSE, VMOpcodes.JUMP_IF_TRUE -> {
                                        // v = popMove(); if cond then goto target
                                        mv.visitVarInsn(AsmOpcodes.ALOAD, 1)
                                        mv.visitMethodInsn(AsmOpcodes.INVOKEVIRTUAL, "com/compiler/memory/RcOperandStack", "popMove", "()Lcom/compiler/bytecode/Value;", false)
                                        mv.visitVarInsn(AsmOpcodes.ASTORE, tempA)
                                        // check bool
                                        mv.visitVarInsn(AsmOpcodes.ALOAD, tempA)
                                        mv.visitTypeInsn(AsmOpcodes.CHECKCAST, "com/compiler/bytecode/Value\$BoolValue")
                                        mv.visitMethodInsn(AsmOpcodes.INVOKEVIRTUAL, "com/compiler/bytecode/Value\$BoolValue", "getValue", "()Z", false)
                                        // now top of stack has boolean
                                        val signed = if (operand and 0x800000 != 0) operand or 0xFF000000.toInt() else operand
                                        val target = idx + signed + 1
                                        if (target < 0 || target >= instrCount) {
                                                // consume boolean value from stack and return INVALID_OPCODE
                                                mv.visitFieldInsn(AsmOpcodes.GETSTATIC, "com/compiler/vm/VMResult", "INVALID_OPCODE", "Lcom/compiler/vm/VMResult;")
                                                mv.visitInsn(AsmOpcodes.ARETURN)
                                        } else {
                                                if (opcode.toByte() == VMOpcodes.JUMP_IF_TRUE) {
                                                        mv.visitJumpInsn(AsmOpcodes.IFNE, labels[target])
                                                } else {
                                                        mv.visitJumpInsn(AsmOpcodes.IFEQ, labels[target])
                                                }
                                        }
                                }

                                VMOpcodes.CALL -> {
                                        // CALL operand: call function at module.functions[operand]
                                        // Pop arguments (interpreter pops in reverse order)
                                        val targetFn = module.functions[operand]
                                        val paramCount = targetFn.parameters.size
                                        val argBase = 12
                                        for (i in paramCount - 1 downTo 0) {
                                                mv.visitVarInsn(AsmOpcodes.ALOAD, 1)
                                                mv.visitMethodInsn(AsmOpcodes.INVOKEVIRTUAL, "com/compiler/memory/RcOperandStack", "popMove", "()Lcom/compiler/bytecode/Value;", false)
                                                mv.visitVarInsn(AsmOpcodes.ASTORE, argBase + i)
                                        }

                                        // Retrieve CompiledFunction object at runtime: MODULE.getFunctions().get(operand)
                                        mv.visitFieldInsn(AsmOpcodes.GETSTATIC, internalName, "MODULE", "Lcom/compiler/bytecode/BytecodeModule;")
                                        mv.visitMethodInsn(AsmOpcodes.INVOKEVIRTUAL, "com/compiler/bytecode/BytecodeModule", "getFunctions", "()Ljava/util/List;", false)
                                        mv.visitLdcInsn(operand)
                                        mv.visitMethodInsn(AsmOpcodes.INVOKEINTERFACE, "java/util/List", "get", "(I)Ljava/lang/Object;", true)
                                        mv.visitTypeInsn(AsmOpcodes.CHECKCAST, "com/compiler/bytecode/CompiledFunction")
                                        mv.visitVarInsn(AsmOpcodes.ASTORE, 8)

                                        // Create RcLocals(mm, function.localsCount)
                                        mv.visitTypeInsn(AsmOpcodes.NEW, "com/compiler/memory/RcLocals")
                                        mv.visitInsn(AsmOpcodes.DUP)
                                        mv.visitVarInsn(AsmOpcodes.ALOAD, 2)
                                        mv.visitVarInsn(AsmOpcodes.ALOAD, 8)
                                        mv.visitMethodInsn(AsmOpcodes.INVOKEVIRTUAL, "com/compiler/bytecode/CompiledFunction", "getLocalsCount", "()I", false)
                                        mv.visitMethodInsn(AsmOpcodes.INVOKESPECIAL, "com/compiler/memory/RcLocals", "<init>", "(Lcom/compiler/memory/MemoryManager;I)V", false)
                                        mv.visitVarInsn(AsmOpcodes.ASTORE, 9)

                                        // Set parameters into locals in order
                                        for (i in 0 until paramCount) {
                                                mv.visitVarInsn(AsmOpcodes.ALOAD, 9)
                                                mv.visitLdcInsn(i)
                                                mv.visitVarInsn(AsmOpcodes.ALOAD, argBase + i)
                                                mv.visitMethodInsn(AsmOpcodes.INVOKEVIRTUAL, "com/compiler/memory/RcLocals", "setMove", "(ILcom/compiler/bytecode/Value;)V", false)
                                        }

                                        // Construct CallFrame(function, locals, 0, Integer.valueOf(frame.pc + 4))
                                        mv.visitTypeInsn(AsmOpcodes.NEW, "com/compiler/vm/CallFrame")
                                        mv.visitInsn(AsmOpcodes.DUP)
                                        mv.visitVarInsn(AsmOpcodes.ALOAD, 8)
                                        mv.visitVarInsn(AsmOpcodes.ALOAD, 9)
                                        mv.visitInsn(AsmOpcodes.ICONST_0)
                                        mv.visitVarInsn(AsmOpcodes.ALOAD, 0)
                                        mv.visitFieldInsn(AsmOpcodes.GETFIELD, "com/compiler/vm/CallFrame", "pc", "I")
                                        mv.visitLdcInsn(4)
                                        mv.visitInsn(AsmOpcodes.IADD)
                                        mv.visitMethodInsn(AsmOpcodes.INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false)
                                        mv.visitMethodInsn(AsmOpcodes.INVOKESPECIAL, "com/compiler/vm/CallFrame", "<init>", "(Lcom/compiler/bytecode/CompiledFunction;Lcom/compiler/memory/RcLocals;ILjava/lang/Integer;)V", false)
                                        mv.visitVarInsn(AsmOpcodes.ASTORE, 10)

                                        // Invoke JITRuntime.invokeCompiledIfPresent(MODULE, functionName, newFrame, stack, mm)
                                        mv.visitFieldInsn(AsmOpcodes.GETSTATIC, internalName, "MODULE", "Lcom/compiler/bytecode/BytecodeModule;")
                                        mv.visitLdcInsn(targetFn.name)
                                        mv.visitVarInsn(AsmOpcodes.ALOAD, 10)
                                        mv.visitVarInsn(AsmOpcodes.ALOAD, 1)
                                        mv.visitVarInsn(AsmOpcodes.ALOAD, 2)
                                        mv.visitMethodInsn(AsmOpcodes.INVOKESTATIC, "com/compiler/vm/jit/JITRuntime", "invokeCompiledIfPresent", "(Lcom/compiler/bytecode/BytecodeModule;Ljava/lang/String;Lcom/compiler/vm/CallFrame;Lcom/compiler/memory/RcOperandStack;Lcom/compiler/memory/MemoryManager;)Lcom/compiler/vm/VMResult;", false)
                                        mv.visitVarInsn(AsmOpcodes.ASTORE, 11)

                                        // if (res != VMResult.SUCCESS) return res
                                        mv.visitVarInsn(AsmOpcodes.ALOAD, 11)
                                        mv.visitFieldInsn(AsmOpcodes.GETSTATIC, "com/compiler/vm/VMResult", "SUCCESS", "Lcom/compiler/vm/VMResult;")
                                        val callOk = org.objectweb.asm.Label()
                                        mv.visitJumpInsn(AsmOpcodes.IF_ACMPEQ, callOk)
                                        mv.visitVarInsn(AsmOpcodes.ALOAD, 11)
                                        mv.visitInsn(AsmOpcodes.ARETURN)
                                        mv.visitLabel(callOk)

                                        // Clear callee locals to release resources (JITRuntime didn't manage caller-owned initialFrame)
                                        mv.visitVarInsn(AsmOpcodes.ALOAD, 10)
                                        mv.visitFieldInsn(AsmOpcodes.GETFIELD, "com/compiler/vm/CallFrame", "locals", "Lcom/compiler/memory/RcLocals;")
                                        mv.visitMethodInsn(AsmOpcodes.INVOKEVIRTUAL, "com/compiler/memory/RcLocals", "clearAndReleaseAll", "()V", false)
                                }

                                VMOpcodes.RETURN -> {
                                        // if primitive result available -> box and pushMove; else fallback to object pop/push
                                        val noPrimRet = org.objectweb.asm.Label()
                                        mv.visitVarInsn(AsmOpcodes.ILOAD, primTopIndex)
                                        mv.visitJumpInsn(AsmOpcodes.IFEQ, noPrimRet)
                                        // pop primitive result
                                        mv.visitIincInsn(primTopIndex, -1)
                                        mv.visitVarInsn(AsmOpcodes.ALOAD, primStackIndex)
                                        mv.visitVarInsn(AsmOpcodes.ILOAD, primTopIndex)
                                        mv.visitInsn(AsmOpcodes.LALOAD)
                                        mv.visitVarInsn(AsmOpcodes.LSTORE, 5)
                                        // box and push
                                        mv.visitVarInsn(AsmOpcodes.ALOAD, 1)
                                        mv.visitVarInsn(AsmOpcodes.LLOAD, 5)
                                        mv.visitMethodInsn(AsmOpcodes.INVOKESTATIC, internalName, "makeInt", "(J)Lcom/compiler/bytecode/Value;", false)
                                        mv.visitMethodInsn(AsmOpcodes.INVOKEVIRTUAL, "com/compiler/memory/RcOperandStack", "pushMove", "(Lcom/compiler/bytecode/Value;)V", false)
                                        mv.visitFieldInsn(AsmOpcodes.GETSTATIC, "com/compiler/vm/VMResult", "SUCCESS", "Lcom/compiler/vm/VMResult;")
                                        mv.visitInsn(AsmOpcodes.ARETURN)
                                        mv.visitLabel(noPrimRet)
                                        // fallback
                                        mv.visitVarInsn(AsmOpcodes.ALOAD, 1)
                                        mv.visitMethodInsn(AsmOpcodes.INVOKEVIRTUAL, "com/compiler/memory/RcOperandStack", "popMove", "()Lcom/compiler/bytecode/Value;", false)
                                        mv.visitVarInsn(AsmOpcodes.ASTORE, tempA)
                                        mv.visitVarInsn(AsmOpcodes.ALOAD, 1)
                                        mv.visitVarInsn(AsmOpcodes.ALOAD, tempA)
                                        mv.visitMethodInsn(AsmOpcodes.INVOKEVIRTUAL, "com/compiler/memory/RcOperandStack", "pushMove", "(Lcom/compiler/bytecode/Value;)V", false)
                                        mv.visitFieldInsn(AsmOpcodes.GETSTATIC, "com/compiler/vm/VMResult", "SUCCESS", "Lcom/compiler/vm/VMResult;")
                                        mv.visitInsn(AsmOpcodes.ARETURN)
                                }

                                VMOpcodes.RETURN_VOID -> {
                                        mv.visitFieldInsn(AsmOpcodes.GETSTATIC, "com/compiler/vm/VMResult", "SUCCESS", "Lcom/compiler/vm/VMResult;")
                                        mv.visitInsn(AsmOpcodes.ARETURN)
                                }

                                else -> {
                                        // unsupported opcode -- should not happen due to prior check
                                        mv.visitFieldInsn(AsmOpcodes.GETSTATIC, "com/compiler/vm/VMResult", "INVALID_OPCODE", "Lcom/compiler/vm/VMResult;")
                                        mv.visitInsn(AsmOpcodes.ARETURN)
                                }
                        }

                        idx++
                }

                // If execution falls through to end -> return SUCCESS
                mv.visitFieldInsn(AsmOpcodes.GETSTATIC, "com/compiler/vm/VMResult", "SUCCESS", "Lcom/compiler/vm/VMResult;")
                mv.visitInsn(AsmOpcodes.ARETURN)
                mv.visitMaxs(0, 0)
                mv.visitEnd()
        }
}
