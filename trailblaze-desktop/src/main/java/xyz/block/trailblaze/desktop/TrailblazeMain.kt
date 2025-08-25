@file:JvmName("Trailblaze")
@file:OptIn(ExperimentalCoroutinesApi::class)

package xyz.block.trailblaze.desktop

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import xyz.block.trailblaze.llm.TrailblazeLlmProvider
import xyz.block.trailblaze.llm.providers.OllamaTrailblazeLlmModelList
import xyz.block.trailblaze.llm.providers.OpenAITrailblazeLlmModelList
import xyz.block.trailblaze.logs.server.TrailblazeMcpServer
import xyz.block.trailblaze.mcp.utils.JvmLLMProvidersUtil.getAvailableTrailblazeLlmProviders
import xyz.block.trailblaze.mcp.utils.TargetTestApp
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet
import xyz.block.trailblaze.ui.MainTrailblazeApp
import xyz.block.trailblaze.ui.TrailblazeSettingsRepo
import xyz.block.trailblaze.ui.model.TrailblazeAppTab
import xyz.block.trailblaze.ui.model.TrailblazeRoute
import xyz.block.trailblaze.ui.models.TrailblazeServerState
import xyz.block.trailblaze.ui.tabs.sessions.SessionsTabComposableJvm
import xyz.block.trailblaze.ui.tabs.sessions.YamlTabComposable
import java.io.File

val logsDir = File("../logs")

val logsRepo = LogsRepo(logsDir)

@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
fun main() {
  val trailblazeSavedSettingsRepo = TrailblazeSettingsRepo(
    initialConfig = TrailblazeServerState.SavedTrailblazeAppConfig(
      availableFeatures = TrailblazeServerState.SavedTrailblazeAppConfig.AvailableFeatures(
        hostMode = false,
      ),
    ),
    allToolClasses = TrailblazeToolSet.AllBuiltInTrailblazeToolsByKoogToolDescriptor,
  )
  val server = TrailblazeMcpServer(
    logsRepo,
    isOnDeviceMode = {
      !trailblazeSavedSettingsRepo.serverStateFlow.value.appConfig.availableFeatures.hostMode
    },
  )

  val modelLists = setOf(
    OpenAITrailblazeLlmModelList,
    OllamaTrailblazeLlmModelList,
  )

  val availableTrailblazeLlmProviders: Set<TrailblazeLlmProvider> = getAvailableTrailblazeLlmProviders(modelLists)

  val availableModelLists = modelLists.filter { availableTrailblazeLlmProviders.contains(it.provider) }.toSet()

  MainTrailblazeApp(
    trailblazeSavedSettingsRepo = trailblazeSavedSettingsRepo,
    logsDir = logsDir,
    trailblazeMcpServerProvider = { server },
  ).runTrailblazeApp(
    listOf(
      TrailblazeAppTab(
        route = TrailblazeRoute.Sessions,
      ) {
        SessionsTabComposableJvm(logsRepo)
      },
      TrailblazeAppTab(
        route = TrailblazeRoute.YamlRoute,
      ) {
        val serverState by trailblazeSavedSettingsRepo.serverStateFlow.collectAsState()
        YamlTabComposable(TargetTestApp.DEFAULT, serverState, availableModelLists)
      },
    ),
    availableModelLists = availableModelLists,
  )
}
