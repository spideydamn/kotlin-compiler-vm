package com.compiler

import com.compiler.lexer.Lexer
import com.compiler.lexer.LexerException
import com.compiler.lexer.Token
import com.compiler.Printer
import java.io.File

object LexerService {

    fun run(filePath: String): List<Token>? {
        val file = File(filePath)

        if (!file.exists()) {
            println("Error: File not found: $filePath")
            return null
        }

        val source = file.readText()

        return try {
            val lexer = Lexer(source)
            val tokens = lexer.tokenize()
            Printer.printTokens(tokens)
            tokens
        } catch (e: LexerException) {
            println("Lexer Error:")
            println("  ${e.message}")
            null
        } catch (e: Exception) {
            println("Unexpected error:")
            println("  ${e.message}")
            e.printStackTrace()
            null
        }
    }
}
