package com.compiler

import com.compiler.parser.ParseException
import com.compiler.parser.Parser
import com.compiler.parser.ast.Program
import com.compiler.Printer
import java.io.File

object ParserService {

    fun run(filePath: String): Program? {
        val file = File(filePath)

        if (!file.exists()) {
            println("Error: File not found: $filePath")
            return null
        }

        return try {
            val tokens = LexerService.run(filePath) ?: return null

            val parser = Parser(tokens)
            val program = parser.parse()
            
            println()

            Printer.printParsing(program)

            program
        } catch (e: ParseException) {
            println("Parse Error:")
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
