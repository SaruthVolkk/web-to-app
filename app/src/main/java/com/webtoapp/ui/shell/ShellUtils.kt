package com.webtoapp.ui.shell

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.webtoapp.core.logging.AppLogger
import com.webtoapp.util.ensureWebUrlScheme
import com.webtoapp.util.normalizeExternalIntentUrl
import com.webtoapp.util.upgradeRemoteHttpToHttps
import java.io.File

/**
 * 格式化时间（毫秒）
 */
internal fun formatTimeMs(ms: Long): String {
    val seconds = (ms / 1000) % 60
    val minutes = (ms / 1000 / 60) % 60
    val hours = ms / 1000 / 60 / 60
    return if (hours > 0) {
        String.format(java.util.Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(java.util.Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }
}

internal fun normalizeShellTargetUrlForSecurity(rawUrl: String): String {
    val trimmed = rawUrl.trim()
    // 检查是否已经有协议头（如 :// 或常用的协议前缀）
    val hasScheme = trimmed.contains("://") || 
                   trimmed.startsWith("javascript:", ignoreCase = true) || 
                   trimmed.startsWith("data:", ignoreCase = true) ||
                   trimmed.startsWith("blob:", ignoreCase = true)
    
    return if (!hasScheme) {
        // 没有 scheme，添加 https 默认值
        "https://$trimmed"
    } else {
        // 已有 scheme，保持原样
        trimmed
    }
}

/**
 * 验证 Deep Link URL 是否在允许的域名列表中
 * 防止恶意 intent 携带非法 URL
 * 
 * @param url 待验证的 URL
 * @param allowedHosts 允许的域名列表
 * @param targetUrl 配置的目标 URL（其域名始终允许）
 * @param allowedSchemes 允许的自定义协议列表
 * @return 如果 URL 安全则返回 URL，否则返回 targetUrl
 */
internal fun validateDeepLinkUrl(
    url: String, 
    allowedHosts: List<String>, 
    targetUrl: String,
    allowedSchemes: List<String> = emptyList()
): String {
    val uri = try {
        android.net.Uri.parse(url)
    } catch (e: Exception) {
        AppLogger.w("ShellActivity", "Invalid deep link URL: $url")
        return targetUrl
    }
    
    val scheme = uri.scheme?.lowercase() ?: ""
    if (scheme.isEmpty()) return targetUrl

    // 1. 如果是网页协议 (http/https)，验证域名白名单
    if (scheme == "http" || scheme == "https") {
        if (allowedHosts.isEmpty()) return url  // 未配置白名单则放行
        
        val urlHost = uri.host?.lowercase() ?: ""
        if (urlHost.isBlank()) {
            AppLogger.w("ShellActivity", "Deep link URL has no host: $url")
            return targetUrl
        }
        
        // 提取配置 URL 的域名作为默认允许
        val configHost = try {
            android.net.Uri.parse(normalizeShellTargetUrlForSecurity(targetUrl)).host?.lowercase()
        } catch (e: Exception) { null }
        
        val allAllowed = buildSet {
            addAll(allowedHosts.map { it.lowercase() })
            configHost?.let { add(it) }
        }
        
        // 检查域名是否在白名单中（支持子域名匹配）
        val isAllowed = allAllowed.any { allowedHost ->
            urlHost == allowedHost || urlHost.endsWith(".$allowedHost")
        }
        
        if (!isAllowed) {
            AppLogger.w("ShellActivity", "Deep link URL host '$urlHost' not in allowed list, redirecting to target URL")
            return targetUrl
        }
        return url
    }
    
    // 2. 如果是自定义协议，验证协议白名单，然后提取真实 Web URL
    if (allowedSchemes.isNotEmpty()) {
        val normalizedSchemes = allowedSchemes.map {
            it.lowercase().removeSuffix("://").removeSuffix(":")
        }
        if (normalizedSchemes.contains(scheme)) {
            AppLogger.i("ShellActivity", "Allowed custom scheme deep link: $url")
            return extractWebUrlFromCustomScheme(url, scheme, targetUrl)
        }
    }

    AppLogger.w("ShellActivity", "Blocked URL with unauthorized scheme '$scheme': $url")
    return targetUrl
}

/**
 * Strips the custom scheme prefix and returns a loadable http/https URL.
 *
 * Handles two formats:
 *   intent://https://host/path  → https://host/path   (embedded full URL)
 *   intent://dashboard          → https://targetHost/dashboard  (path-only)
 */
/**
 * Strips any custom deep link scheme and returns a loadable http/https URL.
 *
 * Works regardless of which scheme the user configured (myapp, etc.).
 * Also fixes Chromium's URL mangling where "https://" becomes "https//" because
 * Chromium treats "https:" as the URI authority and drops the colon.
 *
 * Examples:
 *   myapp://https://host/path   → https://host/path
 *   myapp://https//host/path    → https://host/path  (Chromium-mangled form)
 *   myapp://dashboard           → https://targetHost/dashboard
 *   https://host/path           → https://host/path  (already web URL, unchanged)
 */
internal fun stripDeepLinkScheme(url: String, targetUrl: String): String {
    // Already a web URL — nothing to strip
    if (url.startsWith("http://", ignoreCase = true) ||
        url.startsWith("https://", ignoreCase = true)) {
        return url
    }

    // Find the "://" separator that ends the custom scheme
    val separatorIndex = url.indexOf("://")
    if (separatorIndex < 0) return url  // no scheme at all, return as-is

    // Everything after "scheme://"
    val afterScheme = url.substring(separatorIndex + 3)

    // Fix Chromium mangling: "https//" → "https://"  |  "http//" → "http://"
    val webUrl = when {
        afterScheme.startsWith("https://", ignoreCase = true) -> afterScheme
        afterScheme.startsWith("http://", ignoreCase = true)  -> afterScheme
        afterScheme.startsWith("https//", ignoreCase = true)  ->
            "https://" + afterScheme.substring("https//".length)
        afterScheme.startsWith("http//", ignoreCase = true)   ->
            "http://" + afterScheme.substring("http//".length)
        else -> null
    }

    if (webUrl != null) {
        AppLogger.i("ShellActivity", "Deep link scheme stripped → $webUrl")
        return webUrl
    }

    // Path-only form: myapp://dashboard → https://targetHost/dashboard
    if (afterScheme.isNotBlank()) {
        val targetOrigin = try {
            val u = android.net.Uri.parse(targetUrl)
            if (u.scheme != null && u.host != null) "${u.scheme}://${u.host}" else null
        } catch (e: Exception) { null }

        if (targetOrigin != null) {
            val path = if (afterScheme.startsWith("/")) afterScheme else "/$afterScheme"
            val resolved = "$targetOrigin$path"
            AppLogger.i("ShellActivity", "Deep link path-only → $resolved")
            return resolved
        }
    }

    AppLogger.w("ShellActivity", "Could not resolve deep link '$url', falling back to targetUrl")
    return targetUrl
}

// Keep old names as thin wrappers so existing call-sites still compile
internal fun extractWebUrlFromCustomScheme(url: String, scheme: String, targetUrl: String): String =
    stripDeepLinkScheme(url, targetUrl)

internal fun resolveDeepLinkToWebUrl(url: String, knownSchemes: List<String>, targetUrl: String): String =
    stripDeepLinkScheme(url, targetUrl)

internal fun normalizeExternalUrlForIntent(rawUrl: String): String {
    val safeUrl = normalizeExternalIntentUrl(rawUrl)
    if (safeUrl.isEmpty()) {
        AppLogger.w("ShellActivity", "Blocked invalid or dangerous external URL: $rawUrl")
        return ""
    }
    return normalizeShellTargetUrlForSecurity(safeUrl)
}

internal fun shouldReextractAssets(marker: File, expectedToken: String): Boolean {
    if (!marker.exists()) return true
    return try {
        marker.readText() != expectedToken
    } catch (_: Exception) {
        true
    }
}

internal fun writeExtractionMarker(marker: File, token: String) {
    marker.parentFile?.mkdirs()
    marker.writeText(token)
}

internal fun buildExtractionToken(
    context: Context,
    scope: String,
    configVersionCode: Int,
    extra: String = ""
): String {
    val packageInfo = try {
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, 0)
        }
    } catch (_: Exception) {
        null
    }

    val apkVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        packageInfo?.longVersionCode ?: 0L
    } else {
        @Suppress("DEPRECATION")
        (packageInfo?.versionCode ?: 0).toLong()
    }
    val apkLastUpdate = packageInfo?.lastUpdateTime ?: 0L

    return listOf(
        scope,
        "cfg=$configVersionCode",
        "apkVer=$apkVersionCode",
        "apkUpdated=$apkLastUpdate",
        "extra=$extra"
    ).joinToString("|")
}

internal fun extractAssetsRecursive(context: Context, assetPath: String, destDir: File) {
    AppLogger.d("extractAssets", "提取: assetPath='$assetPath' -> destDir='${destDir.absolutePath}'")
    destDir.mkdirs()
    val children = context.assets.list(assetPath)
    if (children == null) {
        AppLogger.w("extractAssets", "assets.list('$assetPath') 返回 null")
        return
    }
    AppLogger.d("extractAssets", "assets.list('$assetPath') -> ${children.size} 项: ${children.take(20).joinToString()}")

    if (children.isEmpty()) {
        // 叶子节点 = 文件
        context.assets.open(assetPath).use { input ->
            val destFile = File(destDir.parentFile, destDir.name)
            destFile.outputStream().use { output ->
                val bytes = input.copyTo(output)
                AppLogger.d("extractAssets", "  文件(叶子): $assetPath -> ${destFile.absolutePath} ($bytes bytes)")
            }
        }
        return
    }

    var extractedFiles = 0
    var extractedDirs = 0
    for (child in children) {
        val childAssetPath = "$assetPath/$child"
        val childDest = File(destDir, child)

        // 尝试列出子目录；若为空则说明是文件
        val subList = context.assets.list(childAssetPath)
        if (subList != null && subList.isNotEmpty()) {
            extractedDirs++
            extractAssetsRecursive(context, childAssetPath, childDest)
        } else {
            // 复制文件
            context.assets.open(childAssetPath).use { input ->
                childDest.outputStream().use { output ->
                    val bytes = input.copyTo(output)
                    extractedFiles++
                    AppLogger.d("extractAssets", "  文件: $child ($bytes bytes)")
                }
            }
        }
    }
    AppLogger.i("extractAssets", "'$assetPath' 提取完成: $extractedFiles 个文件, $extractedDirs 个子目录")
}
