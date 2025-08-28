package xyz.block.trailblaze.host.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

object XmlParser {
  fun parseJsonKeyValueFromScriptTag(xml: String): Map<String, String> {
    val factory = DocumentBuilderFactory.newInstance()
    val builder = factory.newDocumentBuilder()
    val document = builder.parse(InputSource(StringReader(xml)))
    val scriptElement = document.getElementsByTagName("script").item(0)
    val nodeContent = scriptElement.textContent
    println("nodeContent: $nodeContent")
    val json = Json.Default.parseToJsonElement(nodeContent).jsonObject // Parse as JsonObject
    val map: Map<String, String> = json.mapValues { it.value.toString().trim('"') } // Convert to Map

    println(map)
    return map
  }
}
