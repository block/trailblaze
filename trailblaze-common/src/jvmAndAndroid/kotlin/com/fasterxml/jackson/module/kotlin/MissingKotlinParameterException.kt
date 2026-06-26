package com.fasterxml.jackson.module.kotlin

// Stub restoring the class removed in jackson-module-kotlin 2.15. Maestro's compiled bytecode
// references this class in catch blocks; without it the JVM throws NoClassDefFoundError on any
// YAML parse error path. Nothing in Trailblaze throws this exception — the stub just makes the
// class loadable so Maestro's findException<MissingKotlinParameterException> returns null and
// the real jackson exception (UnrecognizedPropertyException, MismatchedInputException, …) surfaces.
class MissingKotlinParameterException(message: String) : RuntimeException(message)
