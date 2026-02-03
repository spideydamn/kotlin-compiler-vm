package com.compiler.lexer

/**
 * Token types in the language
 */
enum class TokenType {
    // Keywords
    LET,
    FUNC,
    IF,
    ELSE,
    FOR,
    RETURN,
    TRUE,
    FALSE,
    
    // Type keywords
    TYPE_INT,
    TYPE_FLOAT,
    TYPE_BOOL,
    TYPE_VOID,
    
    // Identifiers and literals
    IDENTIFIER,
    INT_LITERAL,
    FLOAT_LITERAL,
    
    // Arithmetic operators
    PLUS,
    MINUS,
    STAR,
    SLASH,
    PERCENT,
    
    // Comparison operators
    EQ,
    NE,
    LT,
    LE,
    GT,
    GE,
    
    // Logical operators
    AND,
    OR,
    NOT,
    
    // Assignment
    ASSIGN,
    
    // Delimiters
    SEMICOLON,
    COLON,
    COMMA,
    DOT,
    
    // Brackets
    LPAREN,
    RPAREN,
    LBRACE,
    RBRACE,
    LBRACKET,
    RBRACKET,
    
    // Special
    EOF
}
