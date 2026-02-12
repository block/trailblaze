package xyz.block.trailblaze.utils

// Regex to extract any positive or negative integer or float from a string
private val numberRegex = """-?\d{1,3}(?:,\d{3})*(?:\.\d+)?${'$'}|^-?\d+(?:\.\d+)?""".toRegex()

// This function will find the first instance of a number matching the above regex.
// It matches positive and negative numbers
// It will match numbers with and without comma separators
// It supports optional decimal places
// This returns the number as a string value without the comma values
fun parseNumberString(input: String): String? {
  val match = numberRegex.find(input)
  println("### Have match $match")
  return match?.value?.replace(",", "")
}

// This number will find the first number value in the provided string using parseNumberString()
// The value is then converted into a Double representation or null if it is invalid
fun getNumberFromString(input: String): Double? = parseNumberString(input)?.toDoubleOrNull()
