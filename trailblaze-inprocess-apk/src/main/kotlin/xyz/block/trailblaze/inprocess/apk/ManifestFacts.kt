package xyz.block.trailblaze.inprocess.apk

/** The manifest half of an app fingerprint. */
internal data class ManifestFacts(
  val packageName: String,
  val launcherActivity: String?,
  /**
   * The process [launcherActivity] runs in, or null when it runs in the app's default process.
   *
   * Not decoration: the in-process driver's launcher waits on `ActivityLifecycleMonitorRegistry`,
   * which only ever sees Activities in its **own** process. A launcher declared into another process
   * starts fine and is never observed, so the wait times out with nothing naming the cause.
   */
  val launcherProcess: String?,
  val debuggable: Boolean,
  val providers: List<DeclaredProvider>,
) {
  companion object {
    private const val LAUNCHER_CATEGORY = "android.intent.category.LAUNCHER"
    private const val INFO_CATEGORY = "android.intent.category.INFO"
    private const val MAIN_ACTION = "android.intent.action.MAIN"
    private const val STARTUP_INITIALIZATION_PROVIDER = "androidx.startup.InitializationProvider"

    /**
     * Reads `AndroidManifest.xml` out of [apk].
     *
     * @throws ApkReadException when the entry is missing, unreadable, or declares no `package`.
     */
    fun read(apk: ApkArchive): ManifestFacts {
      val bytes = apk.bytes("AndroidManifest.xml")
        ?: throw ApkReadException(
          "${apk.file} has no AndroidManifest.xml entry, so it is not an APK.",
        )
      // Same wrapping as DexScanner.readingDex, for the same reason: every offset after the header
      // is read from the file and trusted, so a truncated or hostile manifest fails deep inside the
      // parser as a BufferUnderflowException or a NegativeArraySizeException that names no file.
      // Unwrapped, the farm's pre-flight prints a stack trace and the process exits 1 ("this app is
      // unfit") instead of 2 ("an input it could not read").
      val root = try {
        AndroidBinaryXml.parse(bytes)
      } catch (e: ApkReadException) {
        throw e
      } catch (e: RuntimeException) {
        throw ApkReadException(
          "${apk.file}'s AndroidManifest.xml could not be read (${e::class.simpleName}: " +
            "${e.message}). The APK is truncated, or is not an APK.",
          e,
        )
      }
      return of(root, describeSource = apk.file.toString())
    }

    /**
     * The facts, from a parsed manifest tree.
     *
     * Split from [read] so the enablement and launcher rules are stated over a manifest a test can
     * write directly. Encoding those cases as binary AXML fixtures would test this repository's own
     * encoder, not the rules.
     */
    fun of(root: BinaryXmlElement, describeSource: String): ManifestFacts {
      // The `package` attribute sits in no namespace, unlike almost everything else in a manifest.
      val packageName = root.attr(namespace = null, name = "package")
        ?: throw ApkReadException(
          "$describeSource's AndroidManifest.xml declares no `package`, so there is no target " +
            "package to fingerprint.",
        )
      val application = root.childrenNamed("application").firstOrNull()
      val applicationEnabled = enabled(application)
      val launcher = findLauncher(root, packageName, applicationEnabled)

      return ManifestFacts(
        packageName = packageName,
        launcherActivity = launcher?.className,
        launcherProcess = launcher?.process(application),
        // Absent means false: AGP writes the attribute only on a debuggable build.
        debuggable = application?.androidAttr("debuggable")?.equals("true", ignoreCase = true) == true,
        providers = readProviders(root, packageName, applicationEnabled),
      )
    }

    /**
     * Whether a component (or `<application>`) is enabled as the manifest declares it.
     *
     * Only an explicit `android:enabled="false"` counts as disabled — absent means enabled, which is
     * the platform default. A component the app re-enables at runtime with
     * `PackageManager.setComponentEnabledSetting` reads as disabled here, because nothing in the APK
     * records that call. That is the direction to be wrong in: the launcher this predicts is the one
     * `getLaunchIntentForPackage` returns on a fresh install, which is what the harness gets.
     */
    private fun enabled(element: BinaryXmlElement?): Boolean =
      element?.androidAttr("enabled")?.equals("false", ignoreCase = true) != true

    /** The launcher activity's name, plus the element it came from so its process can be read. */
    private class Launcher(val className: String, val element: BinaryXmlElement) {
      /**
       * The process this launcher runs in, or null when it is the app's default process.
       *
       * An `android:process` on the `<application>` renames the default process for everything in
       * it, instrumentation included, so that alone does not put the launcher anywhere unusual —
       * only an activity that names a *different* process does.
       */
      fun process(application: BinaryXmlElement?): String? {
        val declared = element.androidAttr("process") ?: return null
        return declared.takeIf { it != application?.androidAttr("process") }
      }
    }

    private fun findLauncher(
      root: BinaryXmlElement,
      packageName: String,
      applicationEnabled: Boolean,
    ): Launcher? {
      // A disabled <application> disables every component in it, so there is no launcher to find.
      if (!applicationEnabled) return null
      // `activity-alias` counts: it is a launcher entry point as far as the launcher is concerned,
      // and an app whose only MAIN/LAUNCHER declaration is an alias still starts from the home
      // screen. `targetActivity` is what actually runs, so prefer it when present.
      val candidates = root.descendants("activity") + root.descendants("activity-alias")
      // `getLaunchIntentForPackage` — what starts the app under test — resolves MAIN/INFO across the
      // WHOLE package before it looks at MAIN/LAUNCHER at all, so this searches in the same order.
      // Both directions matter: an app whose only front door is MAIN/INFO has a launcher, and
      // reporting NO_LAUNCHER_ACTIVITY would fail it over one it has; an app declaring both starts
      // from its INFO activity, so naming the LAUNCHER one would fingerprint an Activity the harness
      // never starts.
      return launcherWithCategory(candidates, root, packageName, INFO_CATEGORY)
        ?: launcherWithCategory(candidates, root, packageName, LAUNCHER_CATEGORY)
    }

    private fun launcherWithCategory(
      candidates: List<BinaryXmlElement>,
      root: BinaryXmlElement,
      packageName: String,
      category: String,
    ): Launcher? {
      for (activity in candidates) {
        val launches = activity.childrenNamed("intent-filter").any { filter ->
          filter.childrenNamed("action").any { it.androidAttr("name") == MAIN_ACTION } &&
            filter.childrenNamed("category").any { it.androidAttr("name") == category }
        }
        if (!launches) continue
        // A disabled launcher activity is not a launcher: `getLaunchIntentForPackage`, which is what
        // actually starts the app under test, returns nothing for it.
        if (!enabled(activity)) continue
        val target = activity.androidAttr("targetActivity")
        // An alias declares no process of its own; the activity it targets is what actually starts,
        // so read the process from that activity. An alias whose target is absent from the manifest
        // or disabled starts nothing, so it is not a launcher either — reporting it would clear an
        // app the harness cannot start, with a class name in the fingerprint that does not exist.
        val declaring = if (target == null) activity else targetOf(root, target, packageName) ?: continue
        if (!enabled(declaring)) continue
        val name = target ?: activity.androidAttr("name") ?: continue
        return Launcher(qualify(name, packageName), declaring)
      }
      return null
    }

    private fun targetOf(
      root: BinaryXmlElement,
      targetActivity: String,
      packageName: String,
    ): BinaryXmlElement? = root.descendants("activity").firstOrNull { candidate ->
      candidate.androidAttr("name")?.let { qualify(it, packageName) } == qualify(targetActivity, packageName)
    }

    private fun readProviders(
      root: BinaryXmlElement,
      packageName: String,
      applicationEnabled: Boolean,
    ): List<DeclaredProvider> {
      if (!applicationEnabled) return emptyList()
      return root.descendants("provider").mapNotNull { provider ->
        val name = provider.androidAttr("name") ?: return@mapNotNull null
        // A disabled provider is never installed, so reporting it as raw initialization would name a
        // disqualifier the app does not actually have.
        if (!enabled(provider)) return@mapNotNull null
        val fqn = qualify(name, packageName)
        DeclaredProvider(
          className = fqn,
          authorities = provider.androidAttr("authorities"),
          androidxStartup = fqn == STARTUP_INITIALIZATION_PROVIDER,
        )
      }
    }

    /** Manifests may name a component relatively (`.MainActivity`) or absolutely. */
    private fun qualify(name: String, packageName: String): String = when {
      name.startsWith(".") -> packageName + name
      !name.contains('.') -> "$packageName.$name"
      else -> name
    }
  }
}
