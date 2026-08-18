package xyz.block.trailblaze.report

/**
 * Turns a git clone URL into the web URL a report can link to.
 *
 * Parsed rather than prefix-matched: an unsupported form returns null, which surfaces as a link that
 * silently isn't there. The output is rebuilt from the parsed host and slug, never edited from the
 * input, because a clone URL's userinfo is where a CI token lives.
 *
 * CI shell and jq steps reimplement this parse — no JVM to call into — and the forms accepted here
 * are the reference they follow.
 */
internal object GitRepoUrls {

  internal data class Parsed(val host: String, val slug: String)

  private val SLUG = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*/[A-Za-z0-9][A-Za-z0-9._-]*$")

  /**
   * Host + `owner/repo` for `git@host:owner/repo.git`, `https://host/owner/repo.git`, and
   * `ssh://git@host[:port]/owner/repo.git`, with or without a trailing slash. Null for anything else.
   */
  internal fun parse(raw: String?): Parsed? {
    val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val withoutScheme = trimmed.substringAfter("://", trimmed)
    // Only strip an `@` before the first `/`, so userinfo containing a `/` fails to parse rather
    // than being half-stripped — the SLUG check below is what turns that into a null.
    val authorityAndPath = withoutScheme.substringBefore('/').let { authority ->
      authority.substringAfterLast('@') + withoutScheme.removePrefix(authority)
    }
    val normalized = authorityAndPath
      .replaceFirst(Regex(":[0-9]+/"), "/") // drop a :port; the web host is what a browser needs
      .replaceFirst(':', '/') // scp-style `host:owner/repo`
      .removeSuffix("/")
      .removeSuffix(".git")
      .removeSuffix("/")
    val segments = normalized.split('/').filter { it.isNotEmpty() }
    if (segments.size != 3) return null
    val slug = "${segments[1]}/${segments[2]}"
    if (!SLUG.matches(slug)) return null
    return Parsed(host = segments[0].lowercase(), slug = slug)
  }

  /**
   * `https://<host>/<owner>/<repo>`, or null for a host that doesn't serve GitHub's paths. Callers
   * append `/commit/<sha>` and the like; GitLab spells those differently, so returning a base for
   * any host would hand a reader an authoritative-looking 404. Covers GHE's `github.<org>.tld`.
   */
  internal fun webBaseUrl(cloneUrl: String?): String? {
    val parsed = parse(cloneUrl) ?: return null
    if (parsed.host != "github.com" && !parsed.host.startsWith("github.")) return null
    return "https://${parsed.host}/${parsed.slug}"
  }
}
