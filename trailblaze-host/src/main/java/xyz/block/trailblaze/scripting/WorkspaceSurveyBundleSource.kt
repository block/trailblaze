package xyz.block.trailblaze.scripting

import java.io.File
import kotlinx.coroutines.runBlocking
import xyz.block.trailblaze.llm.config.TrailblazeConfigPaths
import xyz.block.trailblaze.config.YamlBackedHostAppTarget
import xyz.block.trailblaze.config.project.TrailblazeTrailmapManifestLoader
import xyz.block.trailblaze.config.project.TrailblazeWorkspaceConfigResolver
import xyz.block.trailblaze.host.WorkspaceTypeScriptSetup
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.llm.config.WorkspaceConfigDirHolder
import xyz.block.trailblaze.ui.TrailblazeDesktopUtil
import xyz.block.trailblaze.surveys.SurveyBundle
import xyz.block.trailblaze.surveys.SurveyBundleSource
import xyz.block.trailblaze.surveys.SurveyBundles
import xyz.block.trailblaze.surveys.SurveyNotFoundException

/**
 * The daemon's survey bundles: built on first use from the workspace's survey files with the same
 * esbuild pipeline as live-bundled tools, so an edit takes effect on the next load.
 *
 * Survey files are the `*.survey.ts` (or `.mts`, `.js`, `.mjs`) under any `surveys/` directory of the workspace config dir —
 * a trailmap's own `surveys/` and the workspace-wide `<config>/surveys/`, the directories the
 * post-run runner reads.
 */
class WorkspaceSurveyBundleSource(
  private val configDir: () -> File? = { WorkspaceConfigDirHolder.resolver() },
  private val bundle: (File) -> File = { bundleWithResolvedToolchain(it) },
) : SurveyBundleSource {

  override fun load(name: String): SurveyBundle {
    val root = configDir()?.takeIf { it.isDirectory }
      ?: throw SurveyNotFoundException("No survey named $name: this daemon has no workspace config directory to find surveys in.")
    val files = surveyFiles(root)
    val exporting = files.filter { name in SurveyBundles.exportedSurveyNames(it.readText()) }
    if (exporting.size > 1) {
      throw IllegalStateException(
        "Survey $name is exported by ${exporting.joinToString(" and ") { it.relativeTo(root).invariantSeparatorsPath }}. " +
          "Surveys are named by their export, so rename one.",
      )
    }
    val file = exporting.firstOrNull()
      ?: throw SurveyNotFoundException(
        "No survey named $name. Surveys are named by their export, and the workspace exports: " +
          files.flatMap { SurveyBundles.exportedSurveyNames(it.readText()) }.sorted().joinToString().ifEmpty { "(none)" } + ".",
      )
    val relative = file.relativeTo(root).invariantSeparatorsPath
    val built = try {
      bundle(file)
    } catch (e: Exception) {
      throw IllegalStateException(SurveyBundles.unbuildableMessage(name, relative, e.message.orEmpty()), e)
    }
    return SurveyBundle(source = built.readText(), file = relative, targets = targetCatalog(root))
  }

  companion object {
    /**
     * Where a session finds its surveys: the workspace of the trail file it runs (the one
     * `trailblaze check` lints), else the one its target's trailmap came from, else this process's.
     */
    fun configDirFor(trailFilePath: String?, target: TrailblazeHostAppTarget?): File? =
      trailFilePath?.let { path ->
        File(path).absoluteFile.parentFile?.let { TrailblazeWorkspaceConfigResolver.resolve(it.toPath()).configDir }
      } ?: configDirOf(target) ?: WorkspaceConfigDirHolder.resolver()

    /**
     * The workspace config dir [target]'s trailmap was loaded from — `<config>/trailmaps/<id>` —
     * so a daemon session finds the surveys of the workspace its trail came from. Null for a
     * target bundled with the CLI or built from no trailmap.
     */
    fun configDirOf(target: TrailblazeHostAppTarget?): File? {
      val dirs = (target as? YamlBackedHostAppTarget)?.trailmapDirs ?: return null
      return (listOf(dirs[target.id]) + dirs.values).firstNotNullOfOrNull { dir ->
        dir?.absoluteFile?.parentFile?.takeIf { it.name == TrailblazeConfigPaths.TRAILMAPS_SUBDIR }?.parentFile
      }
    }

    /**
     * The targets the trailmaps under [root] define: id → platform → app ids, the catalog the
     * post-run runner builds from the same files. A trailmap that does not parse is left out.
     */
    fun targetCatalog(root: File): Map<String, Map<String, List<String>>> =
      root.walkTopDown()
        .onEnter { dir -> dir == root || !(dir.name.startsWith(".") || dir.name == "node_modules") }
        .filter { it.isFile && it.name == "trailmap.yaml" }
        .sortedBy { it.path }
        .mapNotNull { file -> runCatching { TrailblazeTrailmapManifestLoader.load(file).manifest }.getOrNull() }
        .mapNotNull { manifest ->
          val platforms = manifest.target?.platforms.orEmpty()
            .mapNotNull { (platform, config) -> config.appIds?.takeIf { it.isNotEmpty() }?.let { platform to it } }
            .toMap()
          platforms.takeIf { it.isNotEmpty() }?.let { manifest.id to it }
        }
        .distinctBy { it.first }
        .toMap()

    /** Every survey file (`*.survey.ts`, `.mts`, `.js`, `.mjs`) in a `surveys/` directory under [root], in path order. */
    fun surveyFiles(root: File): List<File> =
      root.walkTopDown()
        .onEnter { dir -> dir == root || !(dir.name.startsWith(".") || dir.name == "node_modules") }
        .filter { it.isFile && SurveyBundles.isSurveyFile(it.name) && "surveys" in it.relativeTo(root).invariantSeparatorsPath.split('/').dropLast(1) }
        .sortedBy { it.path }
        .toList()

    /** Bundles with the esbuild and slim SDK entry this daemon resolved for its tools. */
    internal fun bundleWithResolvedToolchain(file: File, cacheDir: File? = null): File {
      val bundler = resolvedToolchainBundler(cacheDir)
        ?: error("esbuild or the Trailblaze TypeScript SDK is not available to this daemon, so surveys cannot be built.")
      return runBlocking { bundler.bundleSurvey(file) }
    }

    /**
     * A bundler over the esbuild and SDK this process can reach — a source checkout's SDK, else
     * the runtime an installed CLI extracts beside its tools — or null when either is missing.
     */
    fun resolvedToolchainBundler(cacheDir: File? = null): DaemonScriptedToolBundler? {
      val esbuild = LazyYamlScriptedToolRegistration.resolveEsbuildBinary() ?: return null
      val sdkEntry = LazyYamlScriptedToolRegistration.resolveInProcessSdkEntry()
        ?: WorkspaceTypeScriptSetup.frameworkSdkRuntimeEntry(
          File(TrailblazeDesktopUtil.getDefaultAppDataDirectory(), TrailblazeDesktopUtil.SDK_CACHE_SUBDIR),
        )
        ?: return null
      return if (cacheDir == null) {
        DaemonScriptedToolBundler(esbuild, inProcessSdkEntryOverride = sdkEntry, allowLegacyEsbuildWalkup = false)
      } else {
        DaemonScriptedToolBundler(esbuild, inProcessSdkEntryOverride = sdkEntry, allowLegacyEsbuildWalkup = false, cacheDir = cacheDir)
      }
    }
  }
}
