package com.compiler.vm

import com.compiler.bytecode.*
import com.compiler.parser.ast.TypeNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayOutputStream
import java.io.PrintStream

class VirtualMachineTest {

    private fun createModule(
        intConstants: List<Long> = emptyList(),
        floatConstants: List<Double> = emptyList(),
        functions: List<CompiledFunction>
    ): BytecodeModule {
        return BytecodeModule(
            intConstants = intConstants,
            floatConstants = floatConstants,
            functions = functions,
            entryPoint = "main"
        )
    }

    private fun createMainFunction(instructions: ByteArray, localsCount: Int = 0): CompiledFunction {
        return CompiledFunction(
            name = "main",
            parameters = emptyList(),
            returnType = TypeNode.VoidType,
            localsCount = localsCount,
            instructions = instructions
        )
    }

    private fun createFunction(
        name: String,
        instructions: ByteArray,
        parameters: List<ParameterInfo> = emptyList(),
        returnType: TypeNode = TypeNode.VoidType,
        localsCount: Int = 0
    ): CompiledFunction {
        return CompiledFunction(
            name = name,
            parameters = parameters,
            returnType = returnType,
            localsCount = localsCount,
            instructions = instructions
        )
    }

    @Test
    fun `push int constant`() {
        val builder = InstructionBuilder()
        builder.emit(Opcodes.PUSH_INT, 0)
        builder.emit(Opcodes.RETURN_VOID)

        val module = createModule(
            intConstants = listOf(42L),
            functions = listOf(createMainFunction(builder.build()))
        )

        val vm = VirtualMachine(module)
        val result = vm.execute()
        assertEquals(VMResult.SUCCESS, result)
    }

    @Test
    fun `push float constant`() {
        val builder = InstructionBuilder()
        builder.emit(Opcodes.PUSH_FLOAT, 0)
        builder.emit(Opcodes.RETURN_VOID)

        val module = createModule(
            floatConstants = listOf(3.14),
            functions = listOf(createMainFunction(builder.build()))
        )

        val vm = VirtualMachine(module)
        val result = vm.execute()
        assertEquals(VMResult.SUCCESS, result)
    }

    @Test
    fun `push bool true`() {
        val builder = InstructionBuilder()
        builder.emit(Opcodes.PUSH_BOOL, 1)
        builder.emit(Opcodes.RETURN_VOID)

        val module = createModule(functions = listOf(createMainFunction(builder.build())))
        val vm = VirtualMachine(module)
        val result = vm.execute()
        assertEquals(VMResult.SUCCESS, result)
    }

    @Test
    fun `push bool false`() {
        val builder = InstructionBuilder()
        builder.emit(Opcodes.PUSH_BOOL, 0)
        builder.emit(Opcodes.RETURN_VOID)

        val module = createModule(functions = listOf(createMainFunction(builder.build())))
        val vm = VirtualMachine(module)
        val result = vm.execute()
        assertEquals(VMResult.SUCCESS, result)
    }

    @Test
    fun `invalid constant index returns error`() {
        val builder = InstructionBuilder()
        builder.emit(Opcodes.PUSH_INT, 999)

        val module = createModule(
            intConstants = listOf(42L),
            functions = listOf(createMainFunction(builder.build()))
        )

        val vm = VirtualMachine(module)
        val result = vm.execute()
        assertEquals(VMResult.INVALID_CONSTANT_INDEX, result)
    }

    @Test
    fun `store and load local`() {
        val builder = InstructionBuilder()
        builder.emit(Opcodes.PUSH_INT, 0)
        builder.emit(Opcodes.STORE_LOCAL, 0)
        builder.emit(Opcodes.LOAD_LOCAL, 0)
        builder.emit(Opcodes.RETURN_VOID)

        val module = createModule(
            intConstants = listOf(100L),
            functions = listOf(createMainFunction(builder.build(), localsCount = 1))
        )

        val vm = VirtualMachine(module)
        val result = vm.execute()
        assertEquals(VMResult.SUCCESS, result)
    }

    @Test
    fun `invalid local index returns error`() {
        val builder = InstructionBuilder()
        builder.emit(Opcodes.PUSH_INT, 0)
        builder.emit(Opcodes.STORE_LOCAL, 999)

        val module = createModule(
            intConstants = listOf(100L),
            functions = listOf(createMainFunction(builder.build(), localsCount = 1))
        )

        val vm = VirtualMachine(module)
        val result = vm.execute()
        assertEquals(VMResult.INVALID_LOCAL_INDEX, result)
    }

    @Test
    fun `add int`() {
        val builder = InstructionBuilder()
        builder.emit(Opcodes.PUSH_INT, 0)
        builder.emit(Opcodes.PUSH_INT, 1)
        builder.emit(Opcodes.ADD_INT)
        builder.emit(Opcodes.RETURN_VOID)

        val module = createModule(
            intConstants = listOf(10L, 20L),
            functions = listOf(createMainFunction(builder.build()))
        )

        val vm = VirtualMachine(module)
        val result = vm.execute()
        assertEquals(VMResult.SUCCESS, result)
    }

    @Test
    fun `sub int`() {
        val builder = InstructionBuilder()
        builder.emit(Opcodes.PUSH_INT, 0)
        builder.emit(Opcodes.PUSH_INT, 1)
        builder.emit(Opcodes.SUB_INT)
        builder.emit(Opcodes.RETURN_VOID)

        val module = createModule(
            intConstants = listOf(20L, 10L),
            functions = listOf(createMainFunction(builder.build()))
        )

        val vm = VirtualMachine(module)
        val result = vm.execute()
        assertEquals(VMResult.SUCCESS, result)
    }

    @Test
    fun `mul int`() {
        val builder = InstructionBuilder()
        builder.emit(Opcodes.PUSH_INT, 0)
        builder.emit(Opcodes.PUSH_INT, 1)
        builder.emit(Opcodes.MUL_INT)
        builder.emit(Opcodes.RETURN_VOID)

        val module = createModule(
            intConstants = listOf(5L, 6L),
            functions = listOf(createMainFunction(builder.build()))
        )

        val vm = VirtualMachine(module)
        val result = vm.execute()
        assertEquals(VMResult.SUCCESS, result)
    }

    @Test
    fun `div int`() {
        val builder = InstructionBuilder()
        builder.emit(Opcodes.PUSH_INT, 0)
        builder.emit(Opcodes.PUSH_INT, 1)
        builder.emit(Opcodes.DIV_INT)
        builder.emit(Opcodes.RETURN_VOID)

        val module = createModule(
            intConstants = listOf(20L, 4L),
            functions = listOf(createMainFunction(builder.build()))
        )

        val vm = VirtualMachine(module)
        val result = vm.execute()
        assertEquals(VMResult.SUCCESS, result)
    }

    @Test
    fun `division by zero returns error`() {
        val builder = InstructionBuilder()
        builder.emit(Opcodes.PUSH_INT, 0)
        builder.emit(Opcodes.PUSH_INT, 1)
        builder.emit(Opcodes.DIV_INT)

        val module = createModule(
            intConstants = listOf(10L, 0L),
            functions = listOf(createMainFunction(builder.build()))
        )

        val vm = VirtualMachine(module)
        val result = vm.execute()
        assertEquals(VMResult.DIVISION_BY_ZERO, result)
    }

    @Test
    fun `mod int`() {
        val builder = InstructionBuilder()
        builder.emit(Opcodes.PUSH_INT, 0)
        builder.emit(Opcodes.PUSH_INT, 1)
        builder.emit(Opcodes.MOD_INT)
        builder.emit(Opcodes.RETURN_VOID)

        val module = createModule(
            intConstants = listOf(17L, 5L),
            functions = listOf(createMainFunction(builder.build()))
        )

        val vm = VirtualMachine(module)
        val result = vm.execute()
        assertEquals(VMResult.SUCCESS, result)
    }

    @Test
    fun `neg int`() {
        val builder = InstructionBuilder()
        builder.emit(Opcodes.PUSH_INT, 0)
        builder.emit(Opcodes.NEG_INT)
        builder.emit(Opcodes.RETURN_VOID)

        val module = createModule(
            intConstants = listOf(42L),
            functions = listOf(createMainFunction(builder.build()))
        )

        val vm = VirtualMachine(module)
        val result = vm.execute()
        assertEquals(VMResult.SUCCESS, result)
    }

    @Test
    fun `stack underflow on arithmetic`() {
        val builder = InstructionBuilder()
        builder.emit(Opcodes.PUSH_INT, 0)
        builder.emit(Opcodes.ADD_INT)

        val module = createModule(
            intConstants = listOf(10L),
            functions = listOf(createMainFunction(builder.build()))
        )

        val vm = VirtualMachine(module)
        val result = vm.execute()
        assertEquals(VMResult.STACK_UNDERFLOW, result)
    }

    @Test
    fun `add float`() {
        val builder = InstructionBuilder()
        builder.emit(Opcodes.PUSH_FLOAT, 0)
        builder.emit(Opcodes.PUSH_FLOAT, 1)
        builder.emit(Opcodes.ADD_FLOAT)
        builder.emit(Opcodes.RETURN_VOID)

        val module = createModule(
            floatConstants = listOf(1.5, 2.5),
            functions = listOf(createMainFunction(builder.build()))
        )

        val vm = VirtualMachine(module)
        val result = vm.execute()
        assertEquals(VMResult.SUCCESS, result)
    }

    @Test
    fun `div float`() {
        val builder = InstructionBuilder()
        builder.emit(Opcodes.PUSH_FLOAT, 0)
        builder.emit(Opcodes.PUSH_FLOAT, 1)
        builder.emit(Opcodes.DIV_FLOAT)
        builder.emit(Opcodes.RETURN_VOID)

        val module = createModule(
            floatConstants = listOf(10.0, 2.0),
            functions = listOf(createMainFunction(builder.build()))
        )

        val vm = VirtualMachine(module)
        val result = vm.execute()
        assertEquals(VMResult.SUCCESS, result)
    }

    @Test
    fun `neg float`() {
        val builder = InstructionBuilder()
        builder.emit(Opcodes.PUSH_FLOAT, 0)
        builder.emit(Opcodes.NEG_FLOAT)
        builder.emit(Opcodes.RETURN_VOID)

        val module = createModule(
            floatConstants = listOf(3.14),
            functions = listOf(createMainFunction(builder.build()))
        )

        val vm = VirtualMachine(module)
        val result = vm.execute()
        assertEquals(VMResult.SUCCESS, result)
    }

    @Test
    fun `eq int`() {
        val builder = InstructionBuilder()
        builder.emit(Opcodes.PUSH_INT, 0)
        builder.emit(Opcodes.PUSH_INT, 0)
        builder.emit(Opcodes.EQ_INT)
        builder.emit(Opcodes.RETURN_VOID)

        val module = createModule(
            intConstants = listOf(10L),
            functions = listOf(createMainFunction(builder.build()))
        )

        val vm = VirtualMachine(module)
        val result = vm.execute()
        assertEquals(VMResult.SUCCESS, result)
    }

    @Test
    fun `lt int`() {
        val builder = InstructionBuilder()
        builder.emit(Opcodes.PUSH_INT, 0)
        builder.emit(Opcodes.PUSH_INT, 1)
        builder.emit(Opcodes.LT_INT)
        builder.emit(Opcodes.RETURN_VOID)

        val module = createModule(
            intConstants = listOf(5L, 10L),
            functions = listOf(createMainFunction(builder.build()))
        )

        val vm = VirtualMachine(module)
        val result = vm.execute()
        assertEquals(VMResult.SUCCESS, result)
    }

    @Test
    fun `gt int`() {
        val builder = InstructionBuilder()
        builder.emit(Opcodes.PUSH_INT, 0)
        builder.emit(Opcodes.PUSH_INT, 1)
        builder.emit(Opcodes.GT_INT)
        builder.emit(Opcodes.RETURN_VOID)

        val module = createModule(
            intConstants = listOf(10L, 5L),
            functions = listOf(createMainFunction(builder.build()))
        )

        val vm = VirtualMachine(module)
        val result = vm.execute()
        assertEquals(VMResult.SUCCESS, result)
    }

    @Test
    fun `and operation`() {
        val builder = InstructionBuilder()
        builder.emit(Opcodes.PUSH_BOOL, 1)
        builder.emit(Opcodes.PUSH_BOOL, 1)
        builder.emit(Opcodes.AND)
        builder.emit(Opcodes.RETURN_VOID)

        val module = createModule(functions = listOf(createMainFunction(builder.build())))
        val vm = VirtualMachine(module)
        val result = vm.execute()
        assertEquals(VMResult.SUCCESS, result)
    }

    @Test
    fun `or operation`() {
        val builder = InstructionBuilder()
        builder.emit(Opcodes.PUSH_BOOL, 0)
        builder.emit(Opcodes.PUSH_BOOL, 1)
        builder.emit(Opcodes.OR)
        builder.emit(Opcodes.RETURN_VOID)

        val module = createModule(functions = listOf(createMainFunction(builder.build())))
        val vm = VirtualMachine(module)
        val result = vm.execute()
        assertEquals(VMResult.SUCCESS, result)
    }

    @Test
    fun `not operation`() {
        val builder = InstructionBuilder()
        builder.emit(Opcodes.PUSH_BOOL, 1)
        builder.emit(Opcodes.NOT)
        builder.emit(Opcodes.RETURN_VOID)

        val module = createModule(functions = listOf(createMainFunction(builder.build())))
        val vm = VirtualMachine(module)
        val result = vm.execute()
        assertEquals(VMResult.SUCCESS, result)
    }

    @Test
    fun `jump forward`() {
        val builder = InstructionBuilder()
        builder.emit(Opcodes.JUMP, 1)
        builder.emit(Opcodes.PUSH_INT, 0)
        builder.emit(Opcodes.RETURN_VOID)

        val module = createModule(
            intConstants = listOf(42L),
            functions = listOf(createMainFunction(builder.build()))
        )

        val vm = VirtualMachine(module)
        val result = vm.execute()
        assertEquals(VMResult.SUCCESS, result)
    }

    @Test
    fun `jump if false - condition false`() {
        val builder = InstructionBuilder()
        builder.emit(Opcodes.PUSH_BOOL, 0)
        builder.emit(Opcodes.JUMP_IF_FALSE, 1)
        builder.emit(Opcodes.PUSH_INT, 0)
        builder.emit(Opcodes.RETURN_VOID)

        val module = createModule(
            intConstants = listOf(42L),
            functions = listOf(createMainFunction(builder.build()))
        )

        val vm = VirtualMachine(module)
        val result = vm.execute()
        assertEquals(VMResult.SUCCESS, result)
    }

    @Test
    fun `jump if false - condition true`() {
        val builder = InstructionBuilder()
        builder.emit(Opcodes.PUSH_BOOL, 1)
        builder.emit(Opcodes.JUMP_IF_FALSE, 1)
        builder.emit(Opcodes.PUSH_INT, 0)
        builder.emit(Opcodes.RETURN_VOID)

        val module = createModule(
            intConstants = listOf(42L),
            functions = listOf(createMainFunction(builder.build()))
        )

        val vm = VirtualMachine(module)
        val result = vm.execute()
        assertEquals(VMResult.SUCCESS, result)
    }

    @Test
    fun `jump if true - condition true`() {
        val builder = InstructionBuilder()
        builder.emit(Opcodes.PUSH_BOOL, 1)
        builder.emit(Opcodes.JUMP_IF_TRUE, 1)
        builder.emit(Opcodes.PUSH_INT, 0)
        builder.emit(Opcodes.RETURN_VOID)

        val module = createModule(
            intConstants = listOf(42L),
            functions = listOf(createMainFunction(builder.build()))
        )

        val vm = VirtualMachine(module)
        val result = vm.execute()
        assertEquals(VMResult.SUCCESS, result)
    }

    @Test
    fun `call function and return`() {
        val addBuilder = InstructionBuilder()
        addBuilder.emit(Opcodes.LOAD_LOCAL, 0)
        addBuilder.emit(Opcodes.LOAD_LOCAL, 1)
        addBuilder.emit(Opcodes.ADD_INT)
        addBuilder.emit(Opcodes.RETURN)

        val addFunction = createFunction(
            name = "add",
            parameters = listOf(
                ParameterInfo("a", TypeNode.IntType),
                ParameterInfo("b", TypeNode.IntType)
            ),
            returnType = TypeNode.IntType,
            localsCount = 2,
            instructions = addBuilder.build()
        )

        val mainBuilder = InstructionBuilder()
        mainBuilder.emit(Opcodes.PUSH_INT, 0)
        mainBuilder.emit(Opcodes.PUSH_INT, 1)
        mainBuilder.emit(Opcodes.CALL, 1)
        mainBuilder.emit(Opcodes.RETURN_VOID)

        val mainFunction = createMainFunction(mainBuilder.build())

        val module = createModule(
            intConstants = listOf(10L, 20L),
            functions = listOf(mainFunction, addFunction)
        )

        val vm = VirtualMachine(module)
        val result = vm.execute()
        assertEquals(VMResult.SUCCESS, result)
    }

    @Test
    fun `return void`() {
        val builder = InstructionBuilder()
        builder.emit(Opcodes.RETURN_VOID)

        val module = createModule(functions = listOf(createMainFunction(builder.build())))
        val vm = VirtualMachine(module)
        val result = vm.execute()
        assertEquals(VMResult.SUCCESS, result)
    }

    @Test
    fun `invalid function index returns error`() {
        val builder = InstructionBuilder()
        builder.emit(Opcodes.CALL, 999)

        val module = createModule(functions = listOf(createMainFunction(builder.build())))
        val vm = VirtualMachine(module)
        val result = vm.execute()
        assertEquals(VMResult.INVALID_FUNCTION_INDEX, result)
    }

    @Test
    fun `new int array`() {
        val builder = InstructionBuilder()
        builder.emit(Opcodes.PUSH_INT, 0)
        builder.emit(Opcodes.NEW_ARRAY_INT)
        builder.emit(Opcodes.POP)
        builder.emit(Opcodes.RETURN_VOID)

        val module = createModule(
            intConstants = listOf(5L),
            functions = listOf(createMainFunction(builder.build()))
        )

        val vm = VirtualMachine(module)
        val result = vm.execute()
        assertEquals(VMResult.SUCCESS, result)
    }

    @Test
    fun `new float array`() {
        val builder = InstructionBuilder()
        builder.emit(Opcodes.PUSH_INT, 0)
        builder.emit(Opcodes.NEW_ARRAY_FLOAT)
        builder.emit(Opcodes.POP)
        builder.emit(Opcodes.RETURN_VOID)

        val module = createModule(
            intConstants = listOf(3L),
            functions = listOf(createMainFunction(builder.build()))
        )

        val vm = VirtualMachine(module)
        val result = vm.execute()
        assertEquals(VMResult.SUCCESS, result)
    }

    @Test
    fun `new bool array`() {
        val builder = InstructionBuilder()
        builder.emit(Opcodes.PUSH_INT, 0)
        builder.emit(Opcodes.NEW_ARRAY_BOOL)
        builder.emit(Opcodes.POP)
        builder.emit(Opcodes.RETURN_VOID)

        val module = createModule(
            intConstants = listOf(2L),
            functions = listOf(createMainFunction(builder.build()))
        )

        val vm = VirtualMachine(module)
        val result = vm.execute()
        assertEquals(VMResult.SUCCESS, result)
    }

    @Test
    fun `negative array size returns error`() {
        val builder = InstructionBuilder()
        builder.emit(Opcodes.PUSH_INT, 0)
        builder.emit(Opcodes.NEW_ARRAY_INT)

        val module = createModule(
            intConstants = listOf(-1L),
            functions = listOf(createMainFunction(builder.build()))
        )

        val vm = VirtualMachine(module)
        val result = vm.execute()
        assertEquals(VMResult.ARRAY_INDEX_OUT_OF_BOUNDS, result)
    }

    @Test
    fun `array load and store int`() {
        val builder = InstructionBuilder()
        builder.emit(Opcodes.PUSH_INT, 0)
        builder.emit(Opcodes.NEW_ARRAY_INT)
        builder.emit(Opcodes.STORE_LOCAL, 0)
        
        builder.emit(Opcodes.LOAD_LOCAL, 0)
        builder.emit(Opcodes.PUSH_INT, 1)
        builder.emit(Opcodes.PUSH_INT, 2)
        builder.emit(Opcodes.ARRAY_STORE)
        
        builder.emit(Opcodes.LOAD_LOCAL, 0)
        builder.emit(Opcodes.PUSH_INT, 1)
        builder.emit(Opcodes.ARRAY_LOAD)
        builder.emit(Opcodes.POP)
        
        builder.emit(Opcodes.RETURN_VOID)

        val module = createModule(
            intConstants = listOf(3L, 1L, 42L),
            functions = listOf(createMainFunction(builder.build(), localsCount = 1))
        )

        val vm = VirtualMachine(module)
        val result = vm.execute()
        assertEquals(VMResult.SUCCESS, result)
    }

    @Test
    fun `print int`() {
        val output = ByteArrayOutputStream()
        val originalOut = System.out
        System.setOut(PrintStream(output))

        try {
            val builder = InstructionBuilder()
            builder.emit(Opcodes.PUSH_INT, 0)
            builder.emit(Opcodes.PRINT)
            builder.emit(Opcodes.RETURN_VOID)

            val module = createModule(
                intConstants = listOf(42L),
                functions = listOf(createMainFunction(builder.build()))
            )

            val vm = VirtualMachine(module)
            val result = vm.execute()
            assertEquals(VMResult.SUCCESS, result)
            assertEquals("42", output.toString().trim())
        } finally {
            System.setOut(originalOut)
        }
    }

    @Test
    fun `print float`() {
        val output = ByteArrayOutputStream()
        val originalOut = System.out
        System.setOut(PrintStream(output))

        try {
            val builder = InstructionBuilder()
            builder.emit(Opcodes.PUSH_FLOAT, 0)
            builder.emit(Opcodes.PRINT)
            builder.emit(Opcodes.RETURN_VOID)

            val module = createModule(
                floatConstants = listOf(3.14),
                functions = listOf(createMainFunction(builder.build()))
            )

            val vm = VirtualMachine(module)
            val result = vm.execute()
            assertEquals(VMResult.SUCCESS, result)
            assertTrue(output.toString().contains("3.14"))
        } finally {
            System.setOut(originalOut)
        }
    }

    @Test
    fun `print bool`() {
        val output = ByteArrayOutputStream()
        val originalOut = System.out
        System.setOut(PrintStream(output))

        try {
            val builder = InstructionBuilder()
            builder.emit(Opcodes.PUSH_BOOL, 1)
            builder.emit(Opcodes.PRINT)
            builder.emit(Opcodes.RETURN_VOID)

            val module = createModule(functions = listOf(createMainFunction(builder.build())))
            val vm = VirtualMachine(module)
            val result = vm.execute()
            assertEquals(VMResult.SUCCESS, result)
            assertEquals("true", output.toString().trim())
        } finally {
            System.setOut(originalOut)
        }
    }

    @Test
    fun `print array`() {
        val output = ByteArrayOutputStream()
        val originalOut = System.out
        System.setOut(PrintStream(output))

        try {
            val builder = InstructionBuilder()
            builder.emit(Opcodes.PUSH_INT, 0)
            builder.emit(Opcodes.NEW_ARRAY_INT)
            builder.emit(Opcodes.STORE_LOCAL, 0)
            
            builder.emit(Opcodes.LOAD_LOCAL, 0)
            builder.emit(Opcodes.PUSH_INT, 1)
            builder.emit(Opcodes.PUSH_INT, 2)
            builder.emit(Opcodes.ARRAY_STORE)
            
            builder.emit(Opcodes.LOAD_LOCAL, 0)
            builder.emit(Opcodes.PUSH_INT, 3)
            builder.emit(Opcodes.PUSH_INT, 4)
            builder.emit(Opcodes.ARRAY_STORE)
            
            builder.emit(Opcodes.LOAD_LOCAL, 0)
            builder.emit(Opcodes.PUSH_INT, 5)
            builder.emit(Opcodes.PUSH_INT, 6)
            builder.emit(Opcodes.ARRAY_STORE)
            
            builder.emit(Opcodes.LOAD_LOCAL, 0)
            builder.emit(Opcodes.PRINT_ARRAY)
            builder.emit(Opcodes.RETURN_VOID)

            val module = createModule(
                intConstants = listOf(3L, 0L, 10L, 1L, 20L, 2L, 30L),
                functions = listOf(createMainFunction(builder.build(), localsCount = 1))
            )

            val vm = VirtualMachine(module)
            val result = vm.execute()
            assertEquals(VMResult.SUCCESS, result)
            val outputStr = output.toString().trim()
            assertTrue(outputStr.startsWith("[") && outputStr.endsWith("]"))
        } finally {
            System.setOut(originalOut)
        }
    }

    @Test
    fun `invalid opcode returns error`() {
        val builder = InstructionBuilder()
        builder.emit(0xFF.toByte(), 0)

        val module = createModule(functions = listOf(createMainFunction(builder.build())))
        val vm = VirtualMachine(module)
        val result = vm.execute()
        assertEquals(VMResult.INVALID_OPCODE, result)
    }

    @Test
    fun `stack underflow on return`() {
        val builder = InstructionBuilder()
        builder.emit(Opcodes.RETURN)

        val module = createModule(functions = listOf(createMainFunction(builder.build())))
        val vm = VirtualMachine(module)
        val result = vm.execute()
        assertEquals(VMResult.STACK_UNDERFLOW, result)
    }

    @Test
    fun `factorial calculation`() {
        val factBuilder = InstructionBuilder()
        
        val recursiveCaseLabel = factBuilder.createLabel("recursive_case")
        val afterIfLabel = factBuilder.createLabel("after_if")
        
        factBuilder.emit(Opcodes.LOAD_LOCAL, 0)
        factBuilder.emit(Opcodes.PUSH_INT, 0)
        factBuilder.emit(Opcodes.LE_INT)
        factBuilder.emitJump(Opcodes.JUMP_IF_FALSE, recursiveCaseLabel)
        
        factBuilder.emit(Opcodes.PUSH_INT, 0)
        factBuilder.emit(Opcodes.RETURN)
        
        factBuilder.defineLabel(recursiveCaseLabel.name)
        factBuilder.emit(Opcodes.LOAD_LOCAL, 0)
        factBuilder.emit(Opcodes.LOAD_LOCAL, 0)
        factBuilder.emit(Opcodes.PUSH_INT, 0)
        factBuilder.emit(Opcodes.SUB_INT)
        factBuilder.emit(Opcodes.CALL, 1)
        factBuilder.emit(Opcodes.MUL_INT)
        factBuilder.emit(Opcodes.RETURN)
        
        factBuilder.defineLabel(afterIfLabel.name)

        val factFunction = createFunction(
            name = "factorial",
            parameters = listOf(ParameterInfo("n", TypeNode.IntType)),
            returnType = TypeNode.IntType,
            localsCount = 1,
            instructions = factBuilder.build()
        )

        val mainBuilder = InstructionBuilder()
        mainBuilder.emit(Opcodes.PUSH_INT, 1)
        mainBuilder.emit(Opcodes.CALL, 1)
        mainBuilder.emit(Opcodes.POP)
        mainBuilder.emit(Opcodes.RETURN_VOID)

        val mainFunction = createMainFunction(mainBuilder.build())

        val module = createModule(
            intConstants = listOf(1L, 5L),
            functions = listOf(mainFunction, factFunction)
        )

        val vm = VirtualMachine(module)
        val result = vm.execute()
        assertEquals(VMResult.SUCCESS, result)
    }

    @Test
    fun `simple loop`() {
        val builder = InstructionBuilder()
        
        val loopStartLabel = builder.createLabel("loop_start")
        val exitLabel = builder.createLabel("exit")
        
        builder.emit(Opcodes.PUSH_INT, 0)
        builder.emit(Opcodes.STORE_LOCAL, 0)
        
        builder.defineLabel(loopStartLabel.name)
        builder.emit(Opcodes.LOAD_LOCAL, 0)
        builder.emit(Opcodes.PUSH_INT, 1)
        builder.emit(Opcodes.GT_INT)
        builder.emitJump(Opcodes.JUMP_IF_TRUE, exitLabel)
        
        builder.emit(Opcodes.LOAD_LOCAL, 0)
        builder.emit(Opcodes.PRINT)
        
        builder.emit(Opcodes.LOAD_LOCAL, 0)
        builder.emit(Opcodes.PUSH_INT, 2)
        builder.emit(Opcodes.ADD_INT)
        builder.emit(Opcodes.STORE_LOCAL, 0)
        
        builder.emitJump(Opcodes.JUMP, loopStartLabel)
        
        builder.defineLabel(exitLabel.name)
        builder.emit(Opcodes.RETURN_VOID)

        val module = createModule(
            intConstants = listOf(0L, 2L, 1L),
            functions = listOf(createMainFunction(builder.build(), localsCount = 1))
        )

        val vm = VirtualMachine(module)
        val result = vm.execute()
        assertEquals(VMResult.SUCCESS, result)
    }
}
