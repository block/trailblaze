package xyz.block.trailblaze.utils

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.Test

class NumberUtilTest {
  @Test
  fun checkGetNumberFromString() {
    val comparisons = mutableMapOf<String, Double>(
      "0.12" to 0.12,
      "-0.12" to -0.12,
      "123" to 123.0,
      "-123" to -123.0,
      "1.23" to 1.23,
      "-1.23" to -1.23,
      "1234" to 1234.0,
      "-1234" to -1234.0,
      "1,234" to 1234.0,
      "-1,234" to -1234.0,
      "1234567" to 1234567.0,
      "1,234,567" to 1234567.0,
      "-1234567" to -1234567.0,
      "-1,234,567" to -1234567.0,
    )
    for (test in comparisons) {
      assertThat(getNumberFromString(test.key)).isEqualTo(test.value)
    }
  }

  @Test
  fun checkParseNumberFromString() {
    val comparisons = mutableMapOf<String, String>(
      "0.12" to "0.12",
      "-0.12" to "-0.12",
      "123" to "123",
      "-123" to "-123",
      "1.23" to "1.23",
      "-1.23" to "-1.23",
      "1234" to "1234",
      "1,234" to "1234",
      "-1,234" to "-1234",
      "1234567" to "1234567",
      "1,234,567" to "1234567",
      "-1234567" to "-1234567",
      "-1,234,567" to "-1234567",
      // A number that starts the text keeps its separators too.
      "71,830 Points available" to "71830",
      "-1,234 Points" to "-1234",
      "1,234.50 due" to "1234.50",
      "1234 Points" to "1234",
      // One that starts the text still wins over one that ends it.
      "10 Points for $2.00" to "10",
      "Balance 71,830" to "71830",
    )
    for (test in comparisons) {
      assertThat(parseNumberString(test.key)).isEqualTo(test.value)
    }
  }
}
