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
    @Attribute var contextMenuEnabled: Boolean = true
)

object HttpUiSettingsStore {
    private const val MIN_PREVIEW_CHARS = 1000
    private const val MAX_PREVIEW_CHARS = 2_000_000
    private val cache = java.util.concurrent.ConcurrentHashMap<Project, HttpUiSettings>()

    fun load(project: Project): HttpUiSettings {
        cache[project]?.let { return it }
        val settings = HttpSqliteDb.withConnection(project) { connection ->
            connection.prepareStatement(
                """
                SELECT default_timeout_seconds, max_raw_view_chars, max_render_chars, line_marker_enabled, context_menu_enabled
                FROM ui_settings
                ORDER BY id
                LIMIT 1
                """.trimIndent()
            ).use { stmt ->
                stmt.executeQuery().use { rs ->
                    if (!rs.next()) {
                        return@withConnection null
                    }
                    HttpUiSettings(
                        defaultTimeoutSeconds = rs.getInt("default_timeout_seconds"),
                        maxRawViewChars = rs.getInt("max_raw_view_chars"),
                        maxRenderChars = rs.getInt("max_render_chars"),
                        lineMarkerEnabled = rs.getInt("line_marker_enabled") != 0,
                        contextMenuEnabled = rs.getInt("context_menu_enabled") != 0
                    )
                }
            }
        }
        val sanitized = sanitize(settings ?: HttpUiSettings())
        cache[project] = sanitized
        return sanitized
    }

    fun save(project: Project, settings: HttpUiSettings) {
        val sanitized = sanitize(settings)
        val now = HttpSqliteDb.nowString()
        HttpSqliteDb.withConnection(project) { connection ->
            connection.prepareStatement(
                """
                INSERT INTO ui_settings (
                    id,
                    default_timeout_seconds,
                    max_raw_view_chars,
                    max_render_chars,
                    line_marker_enabled,
                    context_menu_enabled,
                    created_at,
                    updated_at
                ) VALUES (1, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    default_timeout_seconds = excluded.default_timeout_seconds,
                    max_raw_view_chars = excluded.max_raw_view_chars,
                    max_render_chars = excluded.max_render_chars,
                    line_marker_enabled = excluded.line_marker_enabled,
                    context_menu_enabled = excluded.context_menu_enabled,
                    updated_at = excluded.updated_at
                """.trimIndent()
            ).use { stmt ->
                stmt.setInt(1, sanitized.defaultTimeoutSeconds)
                stmt.setInt(2, sanitized.maxRawViewChars)
                stmt.setInt(3, sanitized.maxRenderChars)
                stmt.setInt(4, if (sanitized.lineMarkerEnabled) 1 else 0)
                stmt.setInt(5, if (sanitized.contextMenuEnabled) 1 else 0)
                stmt.setString(6, now)
                stmt.setString(7, now)
                stmt.executeUpdate()
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
        return settings.copy(
            defaultTimeoutSeconds = timeout,
            maxRawViewChars = raw,
            maxRenderChars = render
        )
    }
}
