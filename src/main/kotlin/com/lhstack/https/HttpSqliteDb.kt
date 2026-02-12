package com.lhstack.https

import com.intellij.openapi.util.JDOMUtil
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializer
import org.jdom.Element
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.sql.Connection
import java.sql.DriverManager
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

object HttpSqliteDb {
    private val formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME
    private val baseDir: Path = Paths.get(System.getProperty("user.home"), ".jtools", "jtools-https")
    private val initialized = ConcurrentHashMap<Path, Boolean>()

    private fun resolveDbPath(project: Project): Path {
        val name = project.name.ifBlank { "project" }
        val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "project" }
        val base = project.basePath ?: project.projectFilePath ?: name
        val hash = sha256(base).take(10)
        return baseDir.resolve("$safeName-$hash").resolve("data.db")
    }

    private fun ensureInitialized(dbPath: Path) {
        if (initialized[dbPath] == true) {
            return
        }
        synchronized(this) {
            if (initialized[dbPath] == true) {
                return
            }
            Class.forName("org.sqlite.JDBC")
            val dir = dbPath.parent
            if (dir != null && !Files.exists(dir)) {
                Files.createDirectories(dir)
            }
            DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { connection ->
                connection.createStatement().use { stmt ->
                    stmt.execute("PRAGMA foreign_keys = ON")
                    stmt.execute(
                        """
                        CREATE TABLE IF NOT EXISTS ui_settings (
                            id INTEGER PRIMARY KEY,
                            default_timeout_seconds INTEGER NOT NULL,
                            max_raw_view_chars INTEGER NOT NULL,
                            max_render_chars INTEGER NOT NULL,
                            line_marker_enabled INTEGER NOT NULL DEFAULT 1,
                            context_menu_enabled INTEGER NOT NULL DEFAULT 1,
                            created_at TEXT NOT NULL,
                            updated_at TEXT NOT NULL
                        )
                        """.trimIndent()
                    )
                    stmt.execute(
                        """
                        CREATE TABLE IF NOT EXISTS cookies (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            name TEXT NOT NULL,
                            value TEXT NOT NULL,
                            domain TEXT NOT NULL,
                            path TEXT NOT NULL,
                            expires_at INTEGER NOT NULL,
                            secure INTEGER NOT NULL,
                            http_only INTEGER NOT NULL,
                            created_at TEXT NOT NULL,
                            updated_at TEXT NOT NULL
                        )
                        """.trimIndent()
                    )
                    stmt.execute(
                        """
                        CREATE TABLE IF NOT EXISTS api_group (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            parent_id INTEGER NULL,
                            name TEXT NOT NULL,
                            sort_index INTEGER NOT NULL,
                            created_at TEXT NOT NULL,
                            updated_at TEXT NOT NULL,
                            FOREIGN KEY(parent_id) REFERENCES api_group(id) ON DELETE CASCADE
                        )
                        """.trimIndent()
                    )
                    stmt.execute(
                        """
                        CREATE TABLE IF NOT EXISTS api_request (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            group_id INTEGER NULL,
                            name TEXT NOT NULL,
                            method TEXT NOT NULL,
                            url TEXT NOT NULL,
                            request_xml TEXT NOT NULL,
                            sort_index INTEGER NOT NULL,
                            created_at TEXT NOT NULL,
                            updated_at TEXT NOT NULL,
                            FOREIGN KEY(group_id) REFERENCES api_group(id) ON DELETE SET NULL
                        )
                        """.trimIndent()
                    )
                    stmt.execute(
                        """
                        CREATE TABLE IF NOT EXISTS call_tab (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            title TEXT NOT NULL,
                            saved_request_id INTEGER NULL,
                            request_xml TEXT NOT NULL,
                            sort_index INTEGER NOT NULL,
                            created_at TEXT NOT NULL,
                            updated_at TEXT NOT NULL,
                            FOREIGN KEY(saved_request_id) REFERENCES api_request(id) ON DELETE SET NULL
                        )
                        """.trimIndent()
                    )
                    stmt.execute(
                        """
                        CREATE TABLE IF NOT EXISTS request_history (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            source_type TEXT NOT NULL,
                            source_id INTEGER NULL,
                            method TEXT NOT NULL,
                            url TEXT NOT NULL,
                            status INTEGER NOT NULL,
                            request_xml TEXT NOT NULL,
                            response_xml TEXT,
                            created_at TEXT NOT NULL,
                            updated_at TEXT NOT NULL
                        )
                        """.trimIndent()
                    )
                    runCatching {
                        stmt.execute("ALTER TABLE api_request ADD COLUMN sort_index INTEGER NOT NULL DEFAULT 0")
                    }
                    runCatching {
                        stmt.execute("ALTER TABLE call_tab ADD COLUMN sort_index INTEGER NOT NULL DEFAULT 0")
                    }
                    runCatching {
                        stmt.execute("ALTER TABLE ui_settings ADD COLUMN line_marker_enabled INTEGER NOT NULL DEFAULT 1")
                    }
                    runCatching {
                        stmt.execute("ALTER TABLE ui_settings ADD COLUMN context_menu_enabled INTEGER NOT NULL DEFAULT 1")
                    }
                }
            }
            initialized[dbPath] = true
        }
    }

    fun <T> withConnection(project: Project, block: (Connection) -> T): T {
        val dbPath = resolveDbPath(project)
        ensureInitialized(dbPath)
        DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { stmt ->
                stmt.execute("PRAGMA foreign_keys = ON")
            }
            return block(connection)
        }
    }

    fun nowString(): String {
        return LocalDateTime.now().format(formatter)
    }

    fun parseDateTime(value: String): LocalDateTime {
        return LocalDateTime.parse(value, formatter)
    }

    fun <T: Any> serialize(obj: T): String {
        val element: Element = XmlSerializer.serialize(obj)
        return JDOMUtil.writeElement(element)
    }

    fun <T> deserialize(xml: String, clazz: Class<T>): T? {
        return try {
            val element = JDOMUtil.load(xml)
            XmlSerializer.deserialize(element, clazz)
        } catch (_: Exception) {
            null
        }
    }

    private fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
