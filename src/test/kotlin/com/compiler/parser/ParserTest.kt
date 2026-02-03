package com.compiler.parser

import com.compiler.domain.SourcePos
import com.compiler.lexer.Token
import com.compiler.lexer.TokenType
import com.compiler.parser.ast.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ParserTest {

        private fun token(
                type: TokenType,
                lexeme: String = type.name,
                literal: Any? = null,
                position: SourcePos = SourcePos(1, 1)
        ): Token {
                return Token(type, lexeme, literal, position)
        }

        private fun ident(name: String) = token(TokenType.IDENTIFIER, name)
        private fun intLit(value: Long) = token(TokenType.INT_LITERAL, value.toString(), value)
        private fun floatLit(value: Double) =
                token(TokenType.FLOAT_LITERAL, value.toString(), value)
        private fun strLex(type: TokenType, lex: String) = token(type, lex)
        private fun eof() = token(TokenType.EOF, "")

        @Test
        fun `parse single variable declaration`() {
                val tokens =
                        listOf(
                                token(TokenType.LET),
                                ident("x"),
                                token(TokenType.COLON),
                                token(TokenType.TYPE_INT),
                                token(TokenType.ASSIGN),
                                intLit(42L),
                                token(TokenType.SEMICOLON),
                                eof()
                        )

                val p = Parser(tokens)
                val program = p.parse()

                assertEquals(1, program.statements.size)
                val stmt = program.statements[0]
                assertTrue(stmt is VarDecl)
                val varDecl = stmt as VarDecl
                assertEquals("x", varDecl.identifier)
                assertEquals(TypeNode.IntType, varDecl.type)
                assertTrue(varDecl.expression is LiteralExpr)
                assertEquals(42L, (varDecl.expression as LiteralExpr).value)
        }

        @Test
        fun `parse simple function declaration`() {
                val tokens =
                        listOf(
                                token(TokenType.FUNC),
                                ident("main"),
                                token(TokenType.LPAREN),
                                token(TokenType.RPAREN),
                                token(TokenType.COLON),
                                token(TokenType.TYPE_VOID),
                                token(TokenType.LBRACE),
                                token(TokenType.RBRACE),
                                eof()
                        )

                val p = Parser(tokens)
                val program = p.parse()

                assertEquals(1, program.statements.size)
                val stmt = program.statements[0]
                assertTrue(stmt is FunctionDecl)
                val fn = stmt as FunctionDecl
                assertEquals("main", fn.identifier)
                assertEquals(TypeNode.VoidType, fn.returnType)
                assertTrue(fn.parameters.isEmpty())
                assertTrue(fn.body.statements.isEmpty())
        }

        @Test
        fun `parse if else statement`() {
                val tokens =
                        listOf(
                                token(TokenType.IF),
                                token(TokenType.LPAREN),
                                ident("x"),
                                token(TokenType.GT),
                                intLit(0L),
                                token(TokenType.RPAREN),
                                token(TokenType.LBRACE),
                                ident("x"),
                                token(TokenType.ASSIGN),
                                intLit(1L),
                                token(TokenType.SEMICOLON),
                                token(TokenType.RBRACE),
                                token(TokenType.ELSE),
                                token(TokenType.LBRACE),
                                ident("x"),
                                token(TokenType.ASSIGN),
                                intLit(0L),
                                token(TokenType.SEMICOLON),
                                token(TokenType.RBRACE),
                                eof()
                        )

                val p = Parser(tokens)
                val program = p.parse()

                assertEquals(1, program.statements.size)
                val ifStmt = program.statements[0]
                assertTrue(ifStmt is IfStmt)
                val cond = (ifStmt as IfStmt).condition
                assertTrue(cond is BinaryExpr)
                assertEquals(TokenType.GT, (cond as BinaryExpr).operator)

                val thenBranch = ifStmt.thenBranch
                val thenStmts = (thenBranch).statements
                assertEquals(1, thenStmts.size)
                assertTrue(thenStmts[0] is ExprStmt)
                val thenExpr = (thenStmts[0] as ExprStmt).expr
                assertTrue(thenExpr is AssignExpr)

                val elseBranch = ifStmt.elseBranch
                assertTrue(elseBranch is BlockStmt)
                val elseStmts = (elseBranch as BlockStmt).statements
                assertEquals(1, elseStmts.size)
                assertTrue(elseStmts[0] is ExprStmt)
                val elseExpr = (elseStmts[0] as ExprStmt).expr
                assertTrue(elseExpr is AssignExpr)
        }

        @Test
        fun `parse classic for statement`() {
                val tokens =
                        listOf(
                                token(TokenType.FOR),
                                token(TokenType.LPAREN),
                                token(TokenType.LET),
                                ident("i"),
                                token(TokenType.COLON),
                                token(TokenType.TYPE_INT),
                                token(TokenType.ASSIGN),
                                intLit(0L),
                                token(TokenType.SEMICOLON),
                                ident("i"),
                                token(TokenType.LT),
                                intLit(10L),
                                token(TokenType.SEMICOLON),
                                ident("i"),
                                token(TokenType.ASSIGN),
                                ident("i"),
                                token(TokenType.PLUS),
                                intLit(1L),
                                token(TokenType.RPAREN),
                                token(TokenType.LBRACE),
                                ident("i"),
                                token(TokenType.ASSIGN),
                                ident("i"),
                                token(TokenType.SEMICOLON),
                                token(TokenType.RBRACE),
                                eof()
                        )

                val p = Parser(tokens)
                val program = p.parse()

                assertEquals(1, program.statements.size)
                val forStmt = program.statements[0]
                assertTrue(forStmt is ForStmt)
                val f = forStmt as ForStmt
                assertTrue(f.initializer is ForVarInit)
                val initDecl = (f.initializer as ForVarInit).decl
                assertEquals("i", initDecl.identifier)
                assertNotNull(f.condition)
                assertNotNull(f.increment)
        }

        @Test
        fun `parse array allocation and access`() {
                val tokens =
                        listOf(
                                token(TokenType.LET),
                                ident("arr"),
                                token(TokenType.COLON),
                                token(TokenType.TYPE_INT),
                                token(TokenType.LBRACKET),
                                token(TokenType.RBRACKET),
                                token(TokenType.ASSIGN),
                                token(TokenType.TYPE_INT),
                                token(TokenType.LBRACKET),
                                intLit(3L),
                                token(TokenType.RBRACKET),
                                token(TokenType.SEMICOLON),

                                token(TokenType.LET),
                                ident("first"),
                                token(TokenType.COLON),
                                token(TokenType.TYPE_INT),
                                token(TokenType.ASSIGN),
                                ident("arr"),
                                token(TokenType.LBRACKET),
                                intLit(0L),
                                token(TokenType.RBRACKET),
                                token(TokenType.SEMICOLON),
                                eof()
                        )

                val p = Parser(tokens)
                val program = p.parse()

                assertEquals(2, program.statements.size)

                val arrDecl = program.statements[0] as VarDecl
                assertTrue(arrDecl.expression is ArrayInitExpr)
                val arrInit = arrDecl.expression as ArrayInitExpr
                assertEquals(TypeNode.IntType, arrInit.elementType)
                val sizeExpr = arrInit.size
                assertTrue(sizeExpr is LiteralExpr)
                assertEquals(3L, (sizeExpr as LiteralExpr).value)

                val firstDecl = program.statements[1] as VarDecl
                assertTrue(firstDecl.expression is ArrayAccessExpr)
        }

        @Test
        fun `parse call and assignment`() {
                val tokens =
                        listOf(
                                token(TokenType.LET),
                                ident("r"),
                                token(TokenType.COLON),
                                token(TokenType.TYPE_INT),
                                token(TokenType.ASSIGN),
                                ident("foo"),
                                token(TokenType.LPAREN),
                                intLit(1L),
                                token(TokenType.COMMA),
                                intLit(2L),
                                token(TokenType.RPAREN),
                                token(TokenType.SEMICOLON),
                                eof()
                        )

                val p = Parser(tokens)
                val program = p.parse()
                assertEquals(1, program.statements.size)
                val decl = program.statements[0] as VarDecl
                assertTrue(decl.expression is CallExpr)
                val call = decl.expression as CallExpr
                assertEquals(2, call.args.size)
        }

        @Test
        fun `parse factorial program`() {
                val tokens = mutableListOf<Token>()

                tokens += token(TokenType.FUNC)
                tokens += ident("factorial")
                tokens += token(TokenType.LPAREN)
                tokens += ident("n")
                tokens += token(TokenType.COLON)
                tokens += token(TokenType.TYPE_INT)
                tokens += token(TokenType.RPAREN)
                tokens += token(TokenType.COLON)
                tokens += token(TokenType.TYPE_INT)
                tokens += token(TokenType.LBRACE)
                tokens += token(TokenType.IF)
                tokens += token(TokenType.LPAREN)
                tokens += ident("n")
                tokens += token(TokenType.LE)
                tokens += intLit(1L)
                tokens += token(TokenType.RPAREN)
                tokens += token(TokenType.LBRACE)
                tokens += token(TokenType.RETURN)
                tokens += intLit(1L)
                tokens += token(TokenType.SEMICOLON)
                tokens += token(TokenType.RBRACE)
                tokens += token(TokenType.ELSE)
                tokens += token(TokenType.LBRACE)
                tokens += token(TokenType.RETURN)
                tokens += ident("n")
                tokens += token(TokenType.STAR)
                tokens += ident("factorial")
                tokens += token(TokenType.LPAREN)
                tokens += ident("n")
                tokens += token(TokenType.MINUS)
                tokens += intLit(1L)
                tokens += token(TokenType.RPAREN)
                tokens += token(TokenType.SEMICOLON)
                tokens += token(TokenType.RBRACE)
                tokens += token(TokenType.RBRACE)

                tokens += token(TokenType.FUNC)
                tokens += ident("main")
                tokens += token(TokenType.LPAREN)
                tokens += token(TokenType.RPAREN)
                tokens += token(TokenType.COLON)
                tokens += token(TokenType.TYPE_VOID)
                tokens += token(TokenType.LBRACE)
                tokens += token(TokenType.LET)
                tokens += ident("result")
                tokens += token(TokenType.COLON)
                tokens += token(TokenType.TYPE_INT)
                tokens += token(TokenType.ASSIGN)
                tokens += ident("factorial")
                tokens += token(TokenType.LPAREN)
                tokens += intLit(5L)
                tokens += token(TokenType.RPAREN)
                tokens += token(TokenType.SEMICOLON)
                tokens += token(TokenType.RBRACE)

                tokens += eof()

                val p = Parser(tokens)
                val program = p.parse()

                assertEquals(2, program.statements.size)
                assertTrue(program.statements[0] is FunctionDecl)
                assertTrue(program.statements[1] is FunctionDecl)

                val factorialFn = program.statements[0] as FunctionDecl
                assertEquals("factorial", factorialFn.identifier)
                val ifStmt = (factorialFn.body.statements[0] as IfStmt)
                assertTrue(ifStmt.elseBranch is BlockStmt)
        }

        @Test
        fun `parse complex logical expression`() {
                val tokens =
                        listOf(
                                token(TokenType.LET),
                                ident("a"),
                                token(TokenType.COLON),
                                token(TokenType.TYPE_BOOL),
                                token(TokenType.ASSIGN),
                                ident("x"),
                                token(TokenType.GT),
                                intLit(0),
                                token(TokenType.AND),
                                ident("y"),
                                token(TokenType.LT),
                                intLit(10),
                                token(TokenType.OR),
                                ident("z"),
                                token(TokenType.EQ),
                                intLit(5),
                                token(TokenType.SEMICOLON),
                                eof()
                        )
                val program = Parser(tokens).parse()
                val decl = program.statements[0] as VarDecl
                val expr = decl.expression
                assertTrue(expr is BinaryExpr)
                val orExpr = expr as BinaryExpr
                assertEquals(TokenType.OR, orExpr.operator)
                assertTrue(orExpr.left is BinaryExpr)
                val andExpr = orExpr.left as BinaryExpr
                assertEquals(TokenType.AND, andExpr.operator)
        }

        @Test
        fun `parse unary operators`() {
                val tokens =
                        listOf(
                                token(TokenType.LET),
                                ident("b"),
                                token(TokenType.COLON),
                                token(TokenType.TYPE_INT),
                                token(TokenType.ASSIGN),
                                token(TokenType.MINUS),
                                intLit(42),
                                token(TokenType.SEMICOLON),
                                eof()
                        )
                val decl = (Parser(tokens).parse().statements[0] as VarDecl)
                val expr = decl.expression
                assertTrue(expr is UnaryExpr)
                assertEquals(TokenType.MINUS, (expr as UnaryExpr).operator)
        }

        @Test
        fun `parse nested if inside for`() {
                val tokens =
                        listOf(
                                token(TokenType.FOR),
                                token(TokenType.LPAREN),
                                token(TokenType.LET),
                                ident("i"),
                                token(TokenType.COLON),
                                token(TokenType.TYPE_INT),
                                token(TokenType.ASSIGN),
                                intLit(0),
                                token(TokenType.SEMICOLON),
                                ident("i"),
                                token(TokenType.LT),
                                intLit(5),
                                token(TokenType.SEMICOLON),
                                ident("i"),
                                token(TokenType.ASSIGN),
                                ident("i"),
                                token(TokenType.PLUS),
                                intLit(1),
                                token(TokenType.RPAREN),
                                token(TokenType.LBRACE),
                                token(TokenType.IF),
                                token(TokenType.LPAREN),
                                ident("i"),
                                token(TokenType.EQ),
                                intLit(2),
                                token(TokenType.RPAREN),
                                token(TokenType.LBRACE),
                                ident("x"),
                                token(TokenType.ASSIGN),
                                intLit(99),
                                token(TokenType.SEMICOLON),
                                token(TokenType.RBRACE),
                                token(TokenType.RBRACE),
                                eof()
                        )
                val program = Parser(tokens).parse()
                val forStmt = program.statements[0] as ForStmt
                assertTrue(forStmt.body.statements[0] is IfStmt)
        }

        @Test
        fun `parse grouped arithmetic expressions`() {
                val tokens =
                        listOf(
                                token(TokenType.LET),
                                ident("x"),
                                token(TokenType.COLON),
                                token(TokenType.TYPE_INT),
                                token(TokenType.ASSIGN),
                                token(TokenType.LPAREN),
                                intLit(1),
                                token(TokenType.PLUS),
                                intLit(2),
                                token(TokenType.RPAREN),
                                token(TokenType.STAR),
                                intLit(3),
                                token(TokenType.SEMICOLON),
                                eof()
                        )
                val decl = (Parser(tokens).parse().statements[0] as VarDecl)
                val expr = decl.expression
                assertTrue(expr is BinaryExpr)
                val mulExpr = expr as BinaryExpr
                assertEquals(TokenType.STAR, mulExpr.operator)
                assertTrue(mulExpr.left is GroupingExpr)
        }
}
