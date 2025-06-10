package xyz.block.trailblaze

import org.junit.runner.Description

fun Description.toTestName(): String = "${testClass.canonicalName}_$methodName"
