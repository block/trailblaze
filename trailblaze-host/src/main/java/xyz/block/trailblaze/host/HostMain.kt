package xyz.block.trailblaze.host

import kotlinx.coroutines.runBlocking
import maestro.Driver
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.host.rules.HostTrailblazeLoggingRule
import xyz.block.trailblaze.host.screenstate.HostMaestroDriverScreenState
import xyz.block.trailblaze.host.screenstate.toTrailblazeDevicePlatform
import xyz.block.trailblaze.host.setofmark.HostCanvasSetOfMark
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.server.TrailblazeMcpServer
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.util.GitUtils
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
    val trailblazeLoggingRule = HostTrailblazeLoggingRule(
      trailblazeDeviceInfoProvider = {
        // This will be set properly after hostRunner is initialized
        TrailblazeDeviceInfo(
          trailblazeDriverType = xyz.block.trailblaze.devices.TrailblazeDriverType.ANDROID_HOST,
          widthPixels = 0,
          heightPixels = 0,
        )
      },
    )

    // Create the logger instance for this interactive session
    trailblazeLoggingRule.trailblazeLogger.startSession(INTERACTIVE_NAME)

    // Use the logger from logging rule
    val hostRunner = MaestroHostRunnerImpl(trailblazeLogger = trailblazeLoggingRule.trailblazeLogger)

    if (!trailblazeLoggingRule.trailblazeLogServerClient.isServerRunning()) {
      TrailblazeMcpServer(
        logsRepo,
        isOnDeviceMode = { false },
        targetTestAppProvider = { TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget },
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
    trailblazeLoggingRule.trailblazeLogger.sendStartLog(
      trailConfig = null,
      className = INTERACTIVE_NAME,
      methodName = INTERACTIVE_NAME,
      trailblazeDeviceInfo = TrailblazeDeviceInfo(
        trailblazeDriverType = hostRunner.connectedTrailblazeDriverType,
        widthPixels = hostRunner.connectedDevice.initialMaestroDeviceInfo.widthPixels,
        heightPixels = hostRunner.connectedDevice.initialMaestroDeviceInfo.heightPixels,
      ),
    )
    InteractiveMainRunner(
      filterViewHierarchy = true,
      setOfMarkEnabled = true,
      trailblazeLogger = trailblazeLoggingRule.trailblazeLogger,
    ).run()
    // Interactive mode always exits with success
    trailblazeLoggingRule.trailblazeLogger.sendEndLog(true)
    exitProcess(EXIT_CODE_SUCCESS)
  }
}

private fun runPrintFilteredHierarchy(driver: Driver) {
  // Get the screen state with the host driver
  val screenState = HostMaestroDriverScreenState(
    maestroDriver = driver,
    setOfMarkEnabled = true,
  )

  // Filter the view hierarchy using ViewHierarchyFilter
  val filter = ViewHierarchyFilter.create(
    screenHeight = screenState.deviceHeight,
    screenWidth = screenState.deviceWidth,
    platform = driver.deviceInfo().platform.toTrailblazeDevicePlatform(),
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
  val canvas = HostCanvasSetOfMark(bufferedImage, deviceInfo)
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
    setOfMarkEnabled = true,
  )
  val originalScreenshotBytes = screenState.screenshotBytes
  val bufferedImage = ImageIO.read(java.io.ByteArrayInputStream(originalScreenshotBytes))
  val canvas = HostCanvasSetOfMark(bufferedImage, deviceInfo)
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
  val screenState = HostMaestroDriverScreenState(driver, true)
  val unfilteredHierarchy = screenState.viewHierarchy
  runUnfilteredHierarchyCommon(
    driver = driver,
    viewHierarchy = unfilteredHierarchy,
    outputFileName = "unfiltered_hierarchy_setofmark.jpg",
  )
}
