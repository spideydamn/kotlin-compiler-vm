package com.compiler.bytecode

/**
 * Helper class for building function bytecode.
 * Stores instructions as a list of bytes and provides methods for adding instructions.
 * Supports labels for forward references, enabling single-pass code generation.
 */
class InstructionBuilder {
    private val instructions = mutableListOf<Byte>()
    
    private val labels = mutableMapOf<String, Int>()
    private val forwardReferences = mutableListOf<ForwardReference>()
    
    private data class ForwardReference(
        val instructionAddress: Int,
        val labelName: String
    )
    
    /**
     * Creates a label at the current address.
     * @param name label name
     */
    fun defineLabel(name: String) {
        val address = currentAddress()
        labels[name] = address
        resolveForwardReferences(name, address)
    }
    
    /**
     * Creates a label that can be referenced before it's defined.
     * Returns a Label object that can be used in emitJump methods.
     */
    fun createLabel(name: String): Label {
        return Label(name)
    }
    
    /**
     * Resolves all forward references to a label.
     */
    private fun resolveForwardReferences(labelName: String, targetAddress: Int) {
        val toResolve = forwardReferences.filter { it.labelName == labelName }
        for (ref in toResolve) {
            val sourceAddress = ref.instructionAddress
            val offset = targetAddress - sourceAddress
            patchOperand(sourceAddress, offset)
        }
        forwardReferences.removeAll { it.labelName == labelName }
    }
    
    /**
     * Adds an instruction with opcode and operand.
     * Instruction format: [OPCODE: 1 byte] [OPERAND: 3 bytes]
     */
    fun emit(opcode: Byte, operand: Int = 0) {
        instructions.add(opcode)
        instructions.add(((operand shr 16) and 0xFF).toByte())
        instructions.add(((operand shr 8) and 0xFF).toByte())
        instructions.add((operand and 0xFF).toByte())
    }
    
    /**
     * Emits a jump instruction with a label reference.
     * If the label is not yet defined, creates a forward reference.
     */
    fun emitJump(opcode: Byte, label: Label) {
        val currentAddr = currentAddress()
        if (label.name in labels) {
            val targetAddr = labels[label.name]!!
            val offset = targetAddr - currentAddr
            emit(opcode, offset)
        } else {
            emit(opcode, 0)
            forwardReferences.add(ForwardReference(currentAddr, label.name))
        }
    }
    
    /**
     * Returns the current address (index of the next instruction).
     * Address is specified in number of instructions (not bytes).
     */
    fun currentAddress(): Int = instructions.size / 4
    
    /**
     * Patches the operand of an instruction at the given address.
     * @param address instruction address (in number of instructions)
     * @param operand new operand value
     */
    fun patchOperand(address: Int, operand: Int) {
        val byteIndex = address * 4 + 1 // +1 to skip opcode
        if (byteIndex + 2 >= instructions.size) {
            throw IllegalArgumentException("Invalid address for patching: $address")
        }
        instructions[byteIndex] = ((operand shr 16) and 0xFF).toByte()
        instructions[byteIndex + 1] = ((operand shr 8) and 0xFF).toByte()
        instructions[byteIndex + 2] = (operand and 0xFF).toByte()
    }
    
    /**
     * Returns the opcode of the last instruction, or null if there are no instructions.
     */
    fun getLastOpcode(): Byte? {
        return if (instructions.size >= 4) {
            instructions[instructions.size - 4]
        } else {
            null
        }
    }
    
    /**
     * Emits a raw byte (for copying incomplete instructions).
     */
    fun emitRawByte(byte: Byte) {
        instructions.add(byte)
    }
    
    /**
     * Returns the final byte array with instructions.
     * Resolves any remaining forward references (should be none if labels are properly defined).
     */
    fun build(): ByteArray {
        if (forwardReferences.isNotEmpty()) {
            val unresolved = forwardReferences.map { it.labelName }.distinct()
            throw IllegalStateException("Unresolved forward references to labels: ${unresolved.joinToString()}")
        }
        return instructions.toByteArray()
    }
}

/**
 * Represents a label that can be referenced before it's defined.
 */
data class Label(val name: String)

