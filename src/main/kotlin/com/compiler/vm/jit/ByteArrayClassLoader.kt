package com.compiler.vm.jit

/**
 * ClassLoader for defining JVM classes directly from a byte array.
 *
 * Used by the JIT compiler to load classes generated at runtime (e.g. via ASM) without writing
 * .class files to disk.
 */
class ByteArrayClassLoader(parent: ClassLoader = ByteArrayClassLoader::class.java.classLoader) :
        ClassLoader(parent) {


    /**
     * Defines and loads a class from the given bytecode.
     *
     * @param className fully qualified class name
     * @param bytecode JVM class bytecode
     * @return loaded Class instance
     */
    fun defineClass(className: String, bytecode: ByteArray): Class<*> {
        return defineClass(className, bytecode, 0, bytecode.size)
    }
}
