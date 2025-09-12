package xyz.block.trailblaze.host

import kotlinx.coroutines.runBlocking
import maestro.Driver
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.host.rules.HostTrailblazeLoggingRule
import xyz.block.trailblaze.host.screenstate.HostMaestroDriverScreenState
import xyz.block.trailblaze.host.setofmark.HostCanvasSetOfMark
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.server.TrailblazeMcpServer
import xyz.block.trailblaze.report.utils.GitUtils
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.viewhierarchy.ViewHierarchyFilter
import xyz.block.trailblaze.viewhierarchy.ViewHierarchyTreeNodeUtils
import java.io.File
import javax.imageio.ImageIO
import kotlin.system.exitProcess

private const val INTERACTIVE_NAME = "interactive_mode"
private const val EXIT_CODE_SUCCESS = 0
private const val EXIT_CODE_FAILURE = 1

private val gitRoot = GitUtils.getGitRootViaCommand()
private val logsDir = File(gitRoot, "logs").also { println("Logs dir: ${it.canonicalPath}") }
private val logsRepo = LogsRepo(logsDir)

fun main(args: Array<String>) {
  runBlocking {
    TrailblazeLogger.startSession(INTERACTIVE_NAME)

    val hostRunner = MaestroHostRunnerImpl()

    val trailblazeDeviceInfoProvider = {
      TrailblazeDeviceInfo(
        trailblazeDriverType = hostRunner.connectedTrailblazeDriverType,
        widthPixels = hostRunner.connectedDevice.initialMaestroDeviceInfo.widthPixels,
        heightPixels = hostRunner.connectedDevice.initialMaestroDeviceInfo.heightPixels,
      )
    }

    val trailblazeLoggingRule = HostTrailblazeLoggingRule(
      trailblazeDeviceInfoProvider = trailblazeDeviceInfoProvider,
    )
    if (!trailblazeLoggingRule.trailblazeLogServerClient.isServerRunning()) {
      TrailblazeMcpServer(
        logsRepo,
        isOnDeviceMode = { false },
      ).startSseMcpServer(52525, false)
      Thread.sleep(1000)
    }
    trailblazeLoggingRule.subscribeToLoggingEventsAndSendToServer()
    if (args.isNotEmpty() && args[0] == "print-filtered-hierarchy") {
      runPrintFilteredHierarchy(hostRunner.loggingDriver)
      return@runBlocking
    }
    if (args.isNotEmpty() && args[0] == "print-unfiltered-hierarchy") {
      runPrintUnfilteredHierarchy(hostRunner.loggingDriver)
      return@runBlocking
    }

    // interactive
    // Parse flags
    TrailblazeLogger.sendStartLog(
      className = INTERACTIVE_NAME,
      methodName = INTERACTIVE_NAME,
      trailblazeDeviceInfo = trailblazeDeviceInfoProvider(),
    )
    InteractiveMainRunner(
      filterViewHierarchy = true,
      setOfMarkEnabled = true,
    ).run()
    // Interactive mode always exits with success
    TrailblazeLogger.sendEndLog(true)
    exitProcess(EXIT_CODE_SUCCESS)
  }
}

private fun runPrintFilteredHierarchy(driver: Driver) {
  // Get the screen state with the host driver
  val screenState = HostMaestroDriverScreenState(
    maestroDriver = driver,
    setOfMarkEnabled = false,
  )

  // Filter the view hierarchy using ViewHierarchyFilter
  val filter = ViewHierarchyFilter(
    screenHeight = screenState.deviceHeight,
    screenWidth = screenState.deviceWidth,
  )
  val filteredHierarchy = filter.filterInteractableViewHierarchyTreeNodes(screenState.viewHierarchy)

  // Pretty print the filtered hierarchy as JSON
  val json = TrailblazeJsonInstance.encodeToString(
    ViewHierarchyTreeNode.serializer(),
    filteredHierarchy,
  )
  println("Filtered View Hierarchy:\n$json")

  // Generate and save the Set of Mark screenshot
  val deviceInfo = driver.deviceInfo()
  val elementList = ViewHierarchyTreeNodeUtils.from(filteredHierarchy, deviceInfo)
  // Print bounding box and coordinates for each element
  println("Filtered Hierarchy Elements (bounding box and coordinates):")
  elementList.forEachIndexed { index, element ->
    println("Element $index $element")
  }
  val originalScreenshotBytes = screenState.screenshotBytes
  val bufferedImage = ImageIO.read(java.io.ByteArrayInputStream(originalScreenshotBytes))
  val canvas = HostCanvasSetOfMark(bufferedImage)
  canvas.draw(elementList)
  val outputBytes = canvas.toByteArray()
  val outputFile = File("filtered_hierarchy_setofmark.jpg")
  outputFile.writeBytes(outputBytes)
  println("Set of Mark screenshot saved to: ${outputFile.absolutePath}")
  exitProcess(EXIT_CODE_SUCCESS)
}

private fun runUnfilteredHierarchyCommon(
  driver: Driver,
  viewHierarchy: ViewHierarchyTreeNode,
  outputFileName: String,
) {
  // Pretty print the hierarchy as JSON
  val json = TrailblazeJsonInstance.encodeToString(
    ViewHierarchyTreeNode.serializer(),
    viewHierarchy,
  )
  println("Unfiltered View Hierarchy:\n$json")

  // Generate and save the Set of Mark screenshot
  val deviceInfo = driver.deviceInfo()
  val elementList = ViewHierarchyTreeNodeUtils.from(viewHierarchy, deviceInfo)
  // Print bounding box and coordinates for each element
  println("Unfiltered Hierarchy Elements (bounding box and coordinates):")
  elementList.forEachIndexed { index, element ->
    println("Element $index $element")
  }
  val screenState = HostMaestroDriverScreenState(
    maestroDriver = driver,
    setOfMarkEnabled = false,
  )
  val originalScreenshotBytes = screenState.screenshotBytes
  val bufferedImage = ImageIO.read(java.io.ByteArrayInputStream(originalScreenshotBytes))
  val canvas = HostCanvasSetOfMark(bufferedImage)
  canvas.draw(elementList)
  val outputBytes = canvas.toByteArray()
  val outputFile = File(outputFileName)
  outputFile.writeBytes(outputBytes)
  println("Set of Mark screenshot saved to: ${outputFile.absolutePath}")
  exitProcess(EXIT_CODE_SUCCESS)
}

private fun runPrintUnfilteredHierarchy(
  driver: Driver,
) {
  val screenState = HostMaestroDriverScreenState(driver, false)
  val unfilteredHierarchy = screenState.viewHierarchy
  runUnfilteredHierarchyCommon(
    driver = driver,
    viewHierarchy = unfilteredHierarchy,
    outputFileName = "unfiltered_hierarchy_setofmark.jpg",
  )
}
