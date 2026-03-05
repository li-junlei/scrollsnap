package com.scrollsnap.core.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val tag: String,
    val releaseUrl: String
)

sealed interface UpdateCheckResult {
    data object UpToDate : UpdateCheckResult
    data class UpdateAvailable(val info: UpdateInfo) : UpdateCheckResult
    data class Error(val message: String) : UpdateCheckResult
}

class GitHubUpdateChecker(
    private val owner: String,
    private val repo: String,
    private val releasesBaseUrl: String
) {
    suspend fun checkForUpdate(currentVersion: String, skippedTag: String?): UpdateCheckResult =
        withContext(Dispatchers.IO) {
            if (owner.isBlank() || repo.isBlank()) {
                return@withContext UpdateCheckResult.Error("GitHub repository is not configured")
            }

            runCatching {
                val apiUrl = "https://api.github.com/repos/$owner/$repo/tags?per_page=20"
                val responseText = request(apiUrl)
                val tags = JSONArray(responseText)

                val newest = findNewestSemVerTag(tags)
                    ?: return@runCatching UpdateCheckResult.Error("No semantic version tags found")

                if (skippedTag != null && skippedTag.equals(newest, ignoreCase = true)) {
                    return@runCatching UpdateCheckResult.UpToDate
                }

                val current = normalizeVersion(currentVersion)
                val latest = normalizeVersion(newest)
                if (compareSemVer(latest, current) > 0) {
                    val releaseUrl = "$releasesBaseUrl/tag/$newest"
                    UpdateCheckResult.UpdateAvailable(UpdateInfo(tag = newest, releaseUrl = releaseUrl))
                } else {
                    UpdateCheckResult.UpToDate
                }
            }.getOrElse { UpdateCheckResult.Error(it.message ?: "unknown error") }
        }

    private fun request(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 7000
            readTimeout = 7000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "ScrollSnap-UpdateChecker")
        }
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                error("HTTP $code")
            }
            text
        } finally {
            conn.disconnect()
        }
    }

    private fun findNewestSemVerTag(tags: JSONArray): String? {
        var latestTag: String? = null
        for (i in 0 until tags.length()) {
            val obj = tags.optJSONObject(i) ?: continue
            val tagName = obj.optString("name")
            if (!isSemVer(tagName)) continue
            if (latestTag == null || compareSemVer(normalizeVersion(tagName), normalizeVersion(latestTag)) > 0) {
                latestTag = tagName
            }
        }
        return latestTag
    }

    private fun isSemVer(version: String): Boolean {
        val v = normalizeVersion(version)
        return Regex("^\\d+\\.\\d+\\.\\d+$").matches(v)
    }

    private fun normalizeVersion(version: String): String = version.trim().removePrefix("v").removePrefix("V")

    private fun compareSemVer(a: String, b: String): Int {
        val p1 = a.split('.').map { it.toIntOrNull() ?: 0 }
        val p2 = b.split('.').map { it.toIntOrNull() ?: 0 }
        val maxLen = maxOf(p1.size, p2.size)
        for (i in 0 until maxLen) {
            val n1 = p1.getOrElse(i) { 0 }
            val n2 = p2.getOrElse(i) { 0 }
            if (n1 != n2) return n1.compareTo(n2)
        }
        return 0
    }
}
