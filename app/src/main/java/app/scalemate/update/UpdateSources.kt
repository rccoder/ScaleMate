package app.scalemate.update

import java.net.URI

enum class UpdateSourceKind(val label: String) {
    GITHUB("GitHub"),
    THIRD_PARTY_PROXY("Proxy")
}

enum class DiscoverySupport {
    DIRECT,
    PROXY,
    NONE
}

data class UpdateSource(
    val id: String,
    val label: String,
    val kind: UpdateSourceKind,
    val discovery: DiscoverySupport,
    val enabled: Boolean = true,
    val priority: Int,
    private val templateUrl: (String) -> String
) {
    fun urlFor(tag: String, asset: String = UPDATE_APK_NAME): String =
        httpsUrl(templateUrl("$tag/$asset"), "source")

    val isThirdParty: Boolean get() = kind == UpdateSourceKind.THIRD_PARTY_PROXY
}

/**
 * IDs are part of the persisted preference and the release index validation.
 * Do not reuse an ID for a different domain.
 */
object UpdateSources {
    val AUTO = "auto"
    val GITHUB_OFFICIAL = UpdateSource(
        id = "github-official",
        label = "GitHub",
        kind = UpdateSourceKind.GITHUB,
        discovery = DiscoverySupport.DIRECT,
        priority = 10
    ) { path -> "https://github.com/$UPDATE_REPOSITORY_OWNER/$UPDATE_REPOSITORY_NAME/releases/download/$path" }
    val GH_PROXY_COM = proxy(
        "gh-proxy-com",
        "线路 A · gh-proxy.com",
        20,
        "https://gh-proxy.com",
        DiscoverySupport.PROXY
    )
    val GHFAST_TOP = proxy(
        "ghfast-top",
        "线路 B · ghfast.top",
        30,
        "https://ghfast.top",
        DiscoverySupport.NONE
    )
    val GH_PROXY_NET = proxy(
        "ghproxy-net",
        "线路 C · ghproxy.net",
        40,
        "https://ghproxy.net",
        DiscoverySupport.NONE
    )
    val GH_LLKK_CC = proxy(
        "gh-llkk-cc",
        "线路 D · gh.llkk.cc",
        50,
        "https://gh.llkk.cc",
        DiscoverySupport.PROXY
    )
    val GH_PROXY_ORG = proxy(
        "gh-proxy-org",
        "线路 E · gh-proxy.org",
        60,
        "https://gh-proxy.org",
        DiscoverySupport.PROXY
    )

    val all: List<UpdateSource> = listOf(
        GITHUB_OFFICIAL,
        GH_PROXY_COM,
        GHFAST_TOP,
        GH_PROXY_NET,
        GH_LLKK_CC,
        GH_PROXY_ORG
    )

    fun find(id: String): UpdateSource? = all.firstOrNull { it.id == id }

    /**
     * An explicit choice never falls back. Automatic mode tries the official
     * GitHub API first, then the proxies that can forward the GitHub API.
     */
    fun ordered(preferredId: String, health: Map<String, SourceHealth> = emptyMap()): List<UpdateSource> {
        val selected = find(preferredId)
        if (selected != null) return listOf(selected)
        return all.sortedWith(
            compareBy<UpdateSource> { health[it.id]?.consecutiveFailures ?: 0 }
                .thenBy { health[it.id]?.lastSucceededAt ?: 0L }
                .thenBy { it.priority }
        )
    }

    fun discoveryOrder(preferredId: String): List<UpdateSource> {
        if (preferredId != AUTO) {
            return find(preferredId)?.let(::listOf).orEmpty()
        }
        return listOf(GITHUB_OFFICIAL, GH_PROXY_COM, GH_LLKK_CC, GH_PROXY_ORG)
    }

    fun assetOrder(preferredId: String): List<UpdateSource> {
        val selected = find(preferredId)
        if (selected != null) return listOf(selected)
        return listOf(
            GITHUB_OFFICIAL,
            GH_PROXY_ORG,
            GH_LLKK_CC,
            GH_PROXY_COM,
            GHFAST_TOP,
            GH_PROXY_NET
        )
    }

    fun assetUrl(source: UpdateSource, tag: String, asset: String = UPDATE_APK_NAME): String =
        source.urlFor(tag, asset)

    fun releaseApiUrl(source: UpdateSource): String = when (source.kind) {
        UpdateSourceKind.GITHUB -> githubReleaseApiUrl()
        UpdateSourceKind.THIRD_PARTY_PROXY -> githubProxyReleaseApiUrl(
            URI(source.urlFor("", UPDATE_RELEASE_INDEX_NAME)).host
        )
    }

    private fun proxy(
        id: String,
        label: String,
        priority: Int,
        host: String,
        discovery: DiscoverySupport
    ) = UpdateSource(
        id = id,
        label = label,
        kind = UpdateSourceKind.THIRD_PARTY_PROXY,
        discovery = discovery,
        priority = priority
    ) { path ->
        "$host/https://github.com/$UPDATE_REPOSITORY_OWNER/$UPDATE_REPOSITORY_NAME/releases/download/$path"
    }
}

data class SourceHealth(
    val consecutiveFailures: Int = 0,
    val lastSucceededAt: Long = 0L,
    val lastFailedAt: Long = 0L,
    val lastError: String = ""
) {
    fun success(now: Long) = copy(consecutiveFailures = 0, lastSucceededAt = now, lastError = "")
    fun failure(now: Long, reason: String) = copy(
        consecutiveFailures = (consecutiveFailures + 1).coerceAtMost(99),
        lastFailedAt = now,
        lastError = reason.take(80)
    )
}

fun sourceLabel(id: String): String =
    if (id == UpdateSources.AUTO) "自动选择" else UpdateSources.find(id)?.label ?: "未知线路"

fun isAllowedReleaseAssetUrl(value: String): Boolean {
    val uri = try {
        URI(value)
    } catch (_: Exception) {
        return false
    }
    if (!uri.scheme.equals("https", ignoreCase = true) || uri.userInfo != null || uri.fragment != null) {
        return false
    }
    val host = uri.host?.lowercase() ?: return false
    if (host == "github.com" || host.endsWith(".githubusercontent.com")) return true
    return UpdateSources.all.any { source ->
        source.kind == UpdateSourceKind.THIRD_PARTY_PROXY &&
            host == URI(source.urlFor("", UPDATE_APK_NAME)).host
    }
}

fun proxyAssetUrl(baseHost: String, tag: String, asset: String): String =
    "https://" + baseHost.trim().trimEnd('/') +
        "/https://github.com/$UPDATE_REPOSITORY_OWNER/$UPDATE_REPOSITORY_NAME/releases/download/" +
        tag + "/" + asset

fun githubAssetUrl(tag: String, asset: String): String =
    "https://github.com/$UPDATE_REPOSITORY_OWNER/$UPDATE_REPOSITORY_NAME/releases/download/$tag/$asset"

fun githubReleaseUrl(tag: String): String =
    "https://github.com/$UPDATE_REPOSITORY_OWNER/$UPDATE_REPOSITORY_NAME/releases/tag/$tag"

fun githubReleaseApiUrl(page: Int = 1): String =
    "https://api.github.com/repos/$UPDATE_REPOSITORY_OWNER/$UPDATE_REPOSITORY_NAME/releases?per_page=100&page=$page"

fun githubProxyReleaseApiUrl(host: String, page: Int = 1): String =
    "https://" + host.trim().trimEnd('/') +
        "/https://api.github.com/repos/$UPDATE_REPOSITORY_OWNER/$UPDATE_REPOSITORY_NAME/releases?per_page=100&page=$page"
