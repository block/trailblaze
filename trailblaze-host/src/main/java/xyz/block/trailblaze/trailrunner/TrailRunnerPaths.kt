package xyz.block.trailblaze.trailrunner

import xyz.block.trailblaze.logs.model.SessionInfo
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.recordings.TrailRecordings
import xyz.block.trailblaze.ui.TrailblazeDesktopUtil
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.util.isMacOs
import xyz.block.trailblaze.util.isWindows
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths

internal fun resolveRoots(trailsRootProvider: () -> File): Pair<File, List<File>> {
  val primary = resolvePrimaryRoot(trailsRootProvider)
  val extras = ExtraTrailRoots.list().map { File(it) }
  return primary to extras
}

/**
 * True when canonical path [child] is below canonical path [root]. Compares path components, so a
 * root that already ends in a separator (a drive root such as `D:\`, or `/`) still contains its
 * children, and `/a/bc` is not inside `/a/b`. Each component must match exactly, case included:
 * a Windows path compares case-insensitively, which would put a sibling `TRAILS` folder inside
 * `trails` when the parent folder is case-sensitive.
 */
internal fun isInside(child: String, root: String): Boolean {
  val childPath = Paths.get(child)
  val rootPath = Paths.get(root)
  return childPath.nameCount > rootPath.nameCount && hasExactPrefix(childPath, rootPath)
}

/** [isInside], or [child] is [root] itself. */
internal fun isSameOrInside(child: String, root: String): Boolean = hasExactPrefix(Paths.get(child), Paths.get(root))

private fun hasExactPrefix(child: Path, root: Path): Boolean =
  child.root?.toString() == root.root?.toString() &&
    child.nameCount >= root.nameCount &&
    (0 until root.nameCount).all { child.getName(it).toString() == root.getName(it).toString() }

internal fun resolveSafeSessionDir(logsDir: File, id: String): File? {
  // Reject ids that escape the logs dir (path traversal, separators, NUL).
  if (id.isEmpty() || id.contains("..") || id.contains("/") || id.contains("\\") || id.any { it.isISOControl() }) {
    return null
  }
  val dir = File(logsDir, id)
  val rootCanon = logsDir.canonicalPath
  val dirCanon = dir.canonicalPath
  if (!isSameOrInside(dirCanon, rootCanon)) return null
  return if (dir.isDirectory) dir else null
}

internal fun resolveTrailFile(idSegments: List<String>, primary: File, extras: List<File>): Pair<File, File>? {
  val rootIdx = idSegments.firstOrNull()?.toIntOrNull()
  val root: File = when {
    rootIdx == 0 -> primary
    rootIdx != null && rootIdx in 1..extras.size -> extras[rootIdx - 1]
    rootIdx == null -> primary
    else -> return null
  }
  val relative = (if (rootIdx == null) idSegments else idSegments.drop(1)).joinToString("/")
  if (relative.isEmpty()) return null
  // Trails resolve as <id>.trail.yaml; blazes index as ".../blaze" and resolve
  // as <id>.yaml. Both stay behind the same canonical-path containment check.
  val rootCanon = root.canonicalPath
  for (suffix in listOf(".trail.yaml", ".yaml")) {
    val candidate = File(root, "$relative$suffix")
    val fileCanon = candidate.canonicalPath
    if (!isSameOrInside(fileCanon, rootCanon)) return null
    if (candidate.exists() && candidate.isFile) return root to candidate
  }
  return null
}

// Short, human-readable name for an event-stream id: the last dotted/slashed segment.
// "com.example.network" -> "network", "feature_flags" -> "feature_flags". Falls back to the
// raw id when there's no trailing segment.
internal fun eventStreamLabel(streamId: String): String {
  val tail = streamId.substringAfterLast('.').substringAfterLast('/').trim()
  return tail.ifEmpty { streamId }
}

internal val EDITOR_CLIS = listOf("cursor", "code", "zed", "subl", "idea", "windsurf")

internal fun openInEditor(file: File): Boolean = runCatching {
  val pathDirs = System.getenv("PATH")?.split(File.pathSeparator).orEmpty()
  // On Windows only a real `.exe` is launched: Java runs a `.cmd`/`.bat` through cmd.exe, which
  // re-parses the arguments, so a trail path containing `&` or `%` would run as a command.
  val editor = EDITOR_CLIS.firstNotNullOfOrNull { name ->
    val fileName = if (isWindows()) "$name.exe" else name
    pathDirs.map { File(it, fileName) }.firstOrNull { it.isFile && it.canExecute() }
  }
  val cmd = when {
    editor != null -> listOf(editor.absolutePath, file.absolutePath)
    isMacOs() -> listOf("open", file.absolutePath)
    // Notepad, not the file's default action: Explorer or `start` would RUN an executable such as a
    // `.cmd` file, and hand the path to a parser (cmd.exe's, or Explorer's comma split). By its
    // absolute path, because Windows can resolve a bare name from the working directory (a repo).
    isWindows() -> {
      val systemRoot = System.getenv("SystemRoot")?.takeIf { it.isNotBlank() } ?: "C:\\Windows"
      listOf(File(systemRoot, "System32\\notepad.exe").path, file.absolutePath)
    }
    else -> listOf("xdg-open", file.absolutePath)
  }
  ProcessBuilder(cmd).start()
  true
}.onFailure { Console.log("[TrailRunnerEndpoint] open trail failed: ${it.message}") }.getOrDefault(false)

internal fun resolvePrimaryRoot(configuredProvider: () -> File): File {
  System.getenv("TRAILBLAZE_TRAILS_DIR")?.trim()?.takeIf { it.isNotEmpty() }?.let { envPath ->
    val envDir = File(envPath)
    if (envDir.isDirectory) return envDir
  }
  val configured = configuredProvider()
  // An existing configured directory is authoritative — honor the workspace the user picked in
  // Trail Runner even when it has no `.trail.yaml` files yet (a fresh workspace). Previously this
  // required `containsTrails(configured)`, so picking any empty/new folder silently snapped back to
  // the daemon launch-dir trails below and looked like the switch did nothing.
  //
  // The daemon's provider is `TrailblazeDesktopUtil.getEffectiveTrailsDirectory`, which already
  // applies a launch-workspace `trails:` declaration — so a workspace that declares one wins here
  // without a rung of its own.
  if (configured.isDirectory) return configured
  // Nothing usable is configured (e.g. the configured path doesn't exist): fall back to the
  // launch workspace's trails root, then to the configured path as a last resort. The workspace
  // is asked first because the `trails/` convention below is only ever a guess about layout,
  // and a repo that keeps its trails elsewhere (`trails: legacy-trails`) has stated the answer.
  cwdWorkspaceTrailsDir()?.let { return it }
  val cwdTrails = File(System.getProperty("user.dir"), "trails")
  if (cwdTrails.isDirectory && containsTrails(cwdTrails)) return cwdTrails
  return configured
}

/**
 * The `trails:` root declared by the workspace this process launched in, or null when it
 * declares none.
 *
 * Shares [TrailblazeDesktopUtil]'s memo rather than resolving independently. Two reasons: this
 * is NOT a cold path — `resolveRoots` runs on every Trail Runner route and the web UI polls a
 * few times a minute, so an unmemoized walk-up plus YAML parse would run per request for as long
 * as the configured directory stays missing — and a second independent resolver could disagree
 * with the one the desktop UI reports, which is exactly the split this PR set out to remove.
 */
private fun cwdWorkspaceTrailsDir(): File? = TrailblazeDesktopUtil.launchWorkspaceDeclaration()?.trailsDir

internal fun containsTrails(dir: File): Boolean {
  if (!dir.isDirectory) return false
  val stack = ArrayDeque<Pair<File, Int>>()
  stack.add(dir to 0)
  while (stack.isNotEmpty()) {
    val (current, depth) = stack.removeLast()
    val children = current.listFiles() ?: continue
    for (child in children) {
      // isTrailFile covers all trail shapes — notably the bare unified `trail.yaml`, which a
      // `.trail.yaml` suffix check can't see (the bare name has no leading classifier). It also
      // counts NL-only definitions (blaze.yaml / trailblaze.yaml) on purpose: a folder holding
      // only NL trails is still a trails workspace the runner can execute.
      if (child.isFile && TrailRecordings.isTrailFile(child.name)) return true
      if (child.isDirectory && depth < 4 && !child.name.startsWith(".")) {
        stack.add(child to depth + 1)
      }
    }
  }
  return false
}

internal fun toSessionSummary(info: SessionInfo): SessionSummary {
  val status = when (info.latestStatus) {
    is SessionStatus.Started -> "running"
    is SessionStatus.Ended.Succeeded -> "passed"
    is SessionStatus.Ended.SucceededWithSelfHeal -> "healed"
    is SessionStatus.Ended.Failed -> "failed"
    is SessionStatus.Ended.FailedWithSelfHeal -> "failed"
    is SessionStatus.Ended.Cancelled -> "cancelled"
    is SessionStatus.Ended.TimeoutReached -> "timeout"
    is SessionStatus.Ended.MaxCallsLimitReached -> "timeout"
    SessionStatus.Unknown -> "unknown"
    else -> "unknown"
  }
  val error = when (val s = info.latestStatus) {
    is SessionStatus.Ended.Failed -> s.exceptionMessage
    is SessionStatus.Ended.FailedWithSelfHeal -> s.exceptionMessage
    is SessionStatus.Ended.Cancelled -> s.cancellationMessage
    is SessionStatus.Ended.TimeoutReached -> s.message
    else -> null
  }
  val device = info.trailblazeDeviceInfo
  return SessionSummary(
    id = info.sessionId.value,
    title = info.displayName,
    status = status,
    durationMs = info.durationMs,
    timestampMs = info.timestamp.toEpochMilliseconds(),
    platform = device?.platform?.name?.lowercase(),
    device = device?.classifiers?.joinToString(" · ") { it.toString() }
      ?: device?.trailblazeDriverType?.name,
    // The advertised id first: the web runner paths mint a per-session instance id that device
    // discovery never lists, so a "run this on the same device" retry keyed on it would resolve
    // to nothing (and silently fall back to another device).
    deviceInstanceId = device?.advertisedInstanceId ?: device?.trailblazeDeviceId?.instanceId,
    target = info.trailConfig?.target,
    appId = info.targetAppInfo?.appId,
    appVersionName = info.targetAppInfo?.versionName,
    appVersionCode = info.targetAppInfo?.versionCode,
    appBuildNumber = info.targetAppInfo?.buildNumber,
    hasRecordedSteps = info.hasRecordedSteps,
    error = error,
    metadata = info.trailConfig?.metadata?.takeIf { it.isNotEmpty() },
    trailSourceUrl = info.trailSourceUrl,
    context = info.trailConfig?.context?.takeIf { it.isNotBlank() },
  )
}
