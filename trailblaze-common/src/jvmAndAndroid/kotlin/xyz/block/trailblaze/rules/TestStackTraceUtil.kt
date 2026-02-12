package xyz.block.trailblaze.rules

import kotlin.reflect.KClass
import kotlin.reflect.full.functions

object TestStackTraceUtil {

  data class TestMethodInfo(
    val kClass: KClass<*>,
    val methodName: String,
  ) {
    val fqClassName: String = kClass.qualifiedName!!
    val simpleClassName: String = kClass.simpleName!!
    val packageName: String = fqClassName.substringBeforeLast(simpleClassName).let {
      if (it.endsWith(".")) it.substringBeforeLast(".") else it
    }
  }

  /**
   * Given the current stack trace, calculate the asset path to the test YAML file.
   */
  fun getJUnit4TestMethodFromCurrentStacktrace(): TestMethodInfo = Thread.currentThread().stackTrace.drop(2)
    .firstNotNullOf { stackTraceElement ->
      val javaClass = Class.forName(stackTraceElement.className)
      val kotlinClass = javaClass.kotlin
      val kotlinMethod = kotlinClass.functions.firstOrNull { it.name == stackTraceElement.methodName }
      val isTest = kotlinMethod?.annotations?.any { it.annotationClass.qualifiedName == "org.junit.Test" } ?: false
      if (isTest) {
        TestMethodInfo(kotlinClass, stackTraceElement.methodName)
          .takeIf { it.methodName.isNotBlank() }
      } else {
        null
      }
    }
}
