package xyz.block.trailblaze.report

import java.io.File

/**
 * Very basic command-line parsing helper.
 *
 * We had done this inline in the `main()` function, but now we've externalized it here.
 *
 * THis code is meant to mimic the clickt library which we can't include here because of transitive issues.
 * It would be nice in the future to have all CLIs use the same library, but this module would have to be refactored.
 */
abstract class SimpleCliCommand(
  private val name: String,
) {
  abstract fun run()

  fun main(args: Array<String>) {
    // Check for help flags first
    if (args.any { it == "--help" || it == "-h" }) {
      printUsage()
      kotlin.system.exitProcess(0)
    }

    parseArgs(args)
    run()
  }

  protected open fun parseArgs(args: Array<String>) {
    // Override in subclasses that need argument parsing
  }

  protected fun parseError(message: String): Nothing {
    System.err.println("Error: $message")
    System.err.println()
    printUsage()
    kotlin.system.exitProcess(1)
  }

  protected open fun printUsage() {
    System.err.println("Usage: $name")
  }
}

class FileArgument(
  private val name: String,
  private val help: String,
  private val mustExist: Boolean = false,
  private val canBeFile: Boolean = true,
  private val mustBeReadable: Boolean = false,
) {
  private var _value: File? = null

  val value: File
    get() = _value ?: error("Argument $name has not been set")

  fun parse(arg: String): File {
    val file = File(arg)

    if (mustExist && !file.exists()) {
      error("$name: File does not exist: ${file.absolutePath}")
    }

    if (!canBeFile && file.isFile) {
      error("$name: Path must be a directory: ${file.absolutePath}")
    }

    if (mustBeReadable && !file.canRead()) {
      error("$name: File is not readable: ${file.absolutePath}")
    }

    _value = file
    return file
  }

  fun getUsage(): String = "<$name>"
  fun getHelp(): String = help
}

class FlagOption(
  private val longName: String,
  private val help: String,
  default: Boolean = false,
) {
  var value: Boolean = default
    private set

  fun matches(arg: String): Boolean = arg == "--$longName"

  fun set() {
    value = true
  }

  fun getUsage(): String = "[--$longName]"
  fun getHelp(): String = help
}
