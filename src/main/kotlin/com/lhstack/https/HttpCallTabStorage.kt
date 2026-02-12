package com.lhstack.https

import com.intellij.openapi.project.Project
import java.sql.Statement

object HttpCallTabStorage {
    fun loadTabs(project: Project): MutableList<HttpCallTab> {
        return HttpSqliteDb.withConnection(project) { connection ->
            connection.prepareStatement(
                """
                SELECT id, title, saved_request_id, request_xml, sort_index, created_at, updated_at
                FROM call_tab
                ORDER BY sort_index, id
                """.trimIndent()
            ).use { stmt ->
                stmt.executeQuery().use { rs ->
                    val list = mutableListOf<HttpCallTab>()
                    while (rs.next()) {
                        val requestXml = rs.getString("request_xml")
                        val draft = HttpSqliteDb.deserialize(requestXml, HttpRequestDraft::class.java) ?: HttpRequestDraft()
                        list.add(
                            HttpCallTab(
                                id = rs.getLong("id"),
                                title = rs.getString("title"),
                                savedRequestId = rs.getLong("saved_request_id").takeIf { !rs.wasNull() },
                                draft = draft,
                                sortIndex = rs.getInt("sort_index"),
                                createdAt = HttpSqliteDb.parseDateTime(rs.getString("created_at")),
                                updatedAt = HttpSqliteDb.parseDateTime(rs.getString("updated_at"))
                            )
                        )
                    }
                    list
                }
            }
        }
    }

    fun insertTab(project: Project, tab: HttpCallTab): HttpCallTab {
        val now = HttpSqliteDb.nowString()
        val requestXml = HttpSqliteDb.serialize(tab.draft)
        HttpSqliteDb.withConnection(project) { connection ->
            connection.prepareStatement(
                """
                INSERT INTO call_tab (title, saved_request_id, request_xml, sort_index, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS
            ).use { stmt ->
                stmt.setString(1, tab.title)
                if (tab.savedRequestId == null) stmt.setNull(2, java.sql.Types.BIGINT) else stmt.setLong(2, tab.savedRequestId!!)
                stmt.setString(3, requestXml)
                stmt.setInt(4, tab.sortIndex)
                stmt.setString(5, now)
                stmt.setString(6, now)
                stmt.executeUpdate()
                stmt.generatedKeys.use { keys ->
                    if (keys.next()) {
                        tab.id = keys.getLong(1)
                    }
                }
            }
        }
        tab.createdAt = HttpSqliteDb.parseDateTime(now)
        tab.updatedAt = tab.createdAt
        return tab
    }

    fun updateTab(project: Project, tab: HttpCallTab) {
        val now = HttpSqliteDb.nowString()
        val requestXml = HttpSqliteDb.serialize(tab.draft)
        HttpSqliteDb.withConnection(project) { connection ->
            connection.prepareStatement(
                """
                UPDATE call_tab
                SET title = ?, saved_request_id = ?, request_xml = ?, sort_index = ?, updated_at = ?
                WHERE id = ?
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, tab.title)
                if (tab.savedRequestId == null) stmt.setNull(2, java.sql.Types.BIGINT) else stmt.setLong(2, tab.savedRequestId!!)
                stmt.setString(3, requestXml)
                stmt.setInt(4, tab.sortIndex)
                stmt.setString(5, now)
                stmt.setLong(6, tab.id)
                stmt.executeUpdate()
            }
        }
        tab.updatedAt = HttpSqliteDb.parseDateTime(now)
    }

    fun deleteTab(project: Project, tabId: Long) {
        HttpSqliteDb.withConnection(project) { connection ->
            connection.prepareStatement("DELETE FROM call_tab WHERE id = ?").use { stmt ->
                stmt.setLong(1, tabId)
                stmt.executeUpdate()
            }
        }
    }

    fun updateTabs(project: Project, tabs: List<HttpCallTab>) {
        val now = HttpSqliteDb.nowString()
        HttpSqliteDb.withConnection(project) { connection ->
            connection.prepareStatement(
                """
                UPDATE call_tab
                SET sort_index = ?, updated_at = ?
                WHERE id = ?
                """.trimIndent()
            ).use { stmt ->
                tabs.forEach { tab ->
                    stmt.setInt(1, tab.sortIndex)
                    stmt.setString(2, now)
                    stmt.setLong(3, tab.id)
                    stmt.addBatch()
                }
                stmt.executeBatch()
            }
        }
        val updated = HttpSqliteDb.parseDateTime(now)
        tabs.forEach { it.updatedAt = updated }
    }
}
