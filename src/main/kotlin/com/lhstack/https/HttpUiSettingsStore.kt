package com.lhstack.https

import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.annotations.Attribute
import com.intellij.util.xmlb.annotations.Tag

@Tag("ui-settings")
data class HttpUiSettings(
    @Attribute var defaultTimeoutSeconds: Int = 10,
    @Attribute var maxRawViewChars: Int = 0,
    @Attribute var maxRenderChars: Int = 0,
    @Attribute var lineMarkerEnabled: Boolean = true,
    @Attribute var contextMenuEnabled: Boolean = true,
    @Attribute var proxyEnabled: Boolean = false,
    @Attribute var proxyType: String = "HTTP",
    @Attribute var proxyHost: String = "",
    @Attribute var proxyPort: Int = 0,
    @Attribute var proxyUsername: String = "",
    @Attribute var proxyPassword: String = ""
)

object HttpUiSettingsStore {
    private const val MIN_PREVIEW_CHARS = 1000
    private const val MAX_PREVIEW_CHARS = 2_000_000
    private val cache = java.util.concurrent.ConcurrentHashMap<Project, HttpUiSettings>()

    fun load(project: Project): HttpUiSettings {
        val settings = HttpSqliteDb.withConnection(project) { connection ->
            val columns = """
                default_timeout_seconds,
                max_raw_view_chars,
                max_render_chars,
                line_marker_enabled,
                context_menu_enabled,
                proxy_enabled,
                proxy_type,
                proxy_host,
                proxy_port,
                proxy_username,
                proxy_password
            """.trimIndent()
            // Prefer canonical settings row(id=1). Fallback keeps compatibility with legacy rows.
            readSettings(connection, "SELECT $columns FROM ui_settings WHERE id = 1 LIMIT 1")
                ?: readSettings(connection, "SELECT $columns FROM ui_settings ORDER BY id LIMIT 1")
        }
        val sanitized = sanitize(settings ?: HttpUiSettings())
        cache[project] = sanitized
        return sanitized
    }

    fun save(project: Project, settings: HttpUiSettings) {
        val sanitized = sanitize(settings)
        val now = HttpSqliteDb.nowString()
        HttpSqliteDb.withConnection(project) { connection ->
            val updatedRows = connection.prepareStatement(
                """
                UPDATE ui_settings SET
                    default_timeout_seconds = ?,
                    max_raw_view_chars = ?,
                    max_render_chars = ?,
                    line_marker_enabled = ?,
                    context_menu_enabled = ?,
                    proxy_enabled = ?,
                    proxy_type = ?,
                    proxy_host = ?,
                    proxy_port = ?,
                    proxy_username = ?,
                    proxy_password = ?,
                    updated_at = ?
                WHERE id = 1
                """.trimIndent()
            ).use { stmt ->
                stmt.setInt(1, sanitized.defaultTimeoutSeconds)
                stmt.setInt(2, sanitized.maxRawViewChars)
                stmt.setInt(3, sanitized.maxRenderChars)
                stmt.setInt(4, if (sanitized.lineMarkerEnabled) 1 else 0)
                stmt.setInt(5, if (sanitized.contextMenuEnabled) 1 else 0)
                stmt.setInt(6, if (sanitized.proxyEnabled) 1 else 0)
                stmt.setString(7, sanitized.proxyType)
                stmt.setString(8, sanitized.proxyHost)
                stmt.setInt(9, sanitized.proxyPort)
                stmt.setString(10, sanitized.proxyUsername)
                stmt.setString(11, sanitized.proxyPassword)
                stmt.setString(12, now)
                stmt.executeUpdate()
            }
            if (updatedRows == 0) {
                connection.prepareStatement(
                    """
                    INSERT INTO ui_settings (
                        id,
                        default_timeout_seconds,
                        max_raw_view_chars,
                        max_render_chars,
                        line_marker_enabled,
                        context_menu_enabled,
                        proxy_enabled,
                        proxy_type,
                        proxy_host,
                        proxy_port,
                        proxy_username,
                        proxy_password,
                        created_at,
                        updated_at
                    ) VALUES (1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent()
                ).use { stmt ->
                    stmt.setInt(1, sanitized.defaultTimeoutSeconds)
                    stmt.setInt(2, sanitized.maxRawViewChars)
                    stmt.setInt(3, sanitized.maxRenderChars)
                    stmt.setInt(4, if (sanitized.lineMarkerEnabled) 1 else 0)
                    stmt.setInt(5, if (sanitized.contextMenuEnabled) 1 else 0)
                    stmt.setInt(6, if (sanitized.proxyEnabled) 1 else 0)
                    stmt.setString(7, sanitized.proxyType)
                    stmt.setString(8, sanitized.proxyHost)
                    stmt.setInt(9, sanitized.proxyPort)
                    stmt.setString(10, sanitized.proxyUsername)
                    stmt.setString(11, sanitized.proxyPassword)
                    stmt.setString(12, now)
                    stmt.setString(13, now)
                    stmt.executeUpdate()
                }
            }
        }
        cache[project] = sanitized
    }

    fun isLineMarkerEnabled(project: Project): Boolean {
        return load(project).lineMarkerEnabled
    }

    fun isContextMenuEnabled(project: Project): Boolean {
        return load(project).contextMenuEnabled
    }

    fun clearCache(project: Project) {
        cache.remove(project)
    }

    private fun sanitize(settings: HttpUiSettings): HttpUiSettings {
        val timeout = settings.defaultTimeoutSeconds.coerceIn(1, 120)
        val raw = if (settings.maxRawViewChars == 0) 0
        else settings.maxRawViewChars.coerceIn(MIN_PREVIEW_CHARS, MAX_PREVIEW_CHARS)
        val render = if (settings.maxRenderChars == 0) 0
        else settings.maxRenderChars.coerceIn(MIN_PREVIEW_CHARS, MAX_PREVIEW_CHARS)
        val proxyType = settings.proxyType.trim().uppercase().ifBlank { "HTTP" }
            .takeIf { it == "HTTP" || it == "SOCKS" } ?: "HTTP"
        val proxyHost = settings.proxyHost.trim()
        val proxyPort = if (settings.proxyPort in 1..65535) settings.proxyPort else 0
        val proxyUsername = settings.proxyUsername.trim()
        return settings.copy(
            defaultTimeoutSeconds = timeout,
            maxRawViewChars = raw,
            maxRenderChars = render,
            proxyType = proxyType,
            proxyHost = proxyHost,
            proxyPort = proxyPort,
            proxyUsername = proxyUsername
        )
    }

    private fun readSettings(connection: java.sql.Connection, sql: String): HttpUiSettings? {
        return connection.prepareStatement(sql).use { stmt ->
            stmt.executeQuery().use { rs ->
                if (!rs.next()) {
                    return@use null
                }
                HttpUiSettings(
                    defaultTimeoutSeconds = rs.getInt("default_timeout_seconds"),
                    maxRawViewChars = rs.getInt("max_raw_view_chars"),
                    maxRenderChars = rs.getInt("max_render_chars"),
                    lineMarkerEnabled = rs.getInt("line_marker_enabled") != 0,
                    contextMenuEnabled = rs.getInt("context_menu_enabled") != 0,
                    proxyEnabled = rs.getInt("proxy_enabled") != 0,
                    proxyType = rs.getString("proxy_type") ?: "HTTP",
                    proxyHost = rs.getString("proxy_host") ?: "",
                    proxyPort = rs.getInt("proxy_port"),
                    proxyUsername = rs.getString("proxy_username") ?: "",
                    proxyPassword = rs.getString("proxy_password") ?: ""
                )
            }
        }
    }
}
