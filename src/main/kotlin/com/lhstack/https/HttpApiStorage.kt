package com.lhstack.https

import com.intellij.openapi.project.Project
import java.sql.Statement

object HttpApiStorage {
    fun loadGroups(project: Project): MutableList<HttpApiGroup> {
        return HttpSqliteDb.withConnection(project) { connection ->
            connection.prepareStatement(
                """
                SELECT id, parent_id, name, sort_index, created_at, updated_at
                FROM api_group
                ORDER BY parent_id, sort_index, id
                """.trimIndent()
            ).use { stmt ->
                stmt.executeQuery().use { rs ->
                    val list = mutableListOf<HttpApiGroup>()
                    while (rs.next()) {
                        list.add(
                            HttpApiGroup(
                                id = rs.getLong("id"),
                                parentId = rs.getLong("parent_id").takeIf { !rs.wasNull() },
                                name = rs.getString("name"),
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

    fun loadRequests(project: Project): MutableList<HttpSavedRequest> {
        return HttpSqliteDb.withConnection(project) { connection ->
            connection.prepareStatement(
                """
                SELECT id, group_id, name, method, url, request_xml, sort_index, created_at, updated_at
                FROM api_request
                ORDER BY group_id, sort_index, id
                """.trimIndent()
            ).use { stmt ->
                stmt.executeQuery().use { rs ->
                    val list = mutableListOf<HttpSavedRequest>()
                    while (rs.next()) {
                        val requestXml = rs.getString("request_xml")
                        val draft = HttpSqliteDb.deserialize(requestXml, HttpRequestDraft::class.java) ?: HttpRequestDraft()
                        list.add(
                            HttpSavedRequest(
                                id = rs.getLong("id"),
                                groupId = rs.getLong("group_id").takeIf { !rs.wasNull() },
                                name = rs.getString("name"),
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

    fun insertGroup(project: Project, group: HttpApiGroup): HttpApiGroup {
        val now = HttpSqliteDb.nowString()
        HttpSqliteDb.withConnection(project) { connection ->
            connection.prepareStatement(
                """
                INSERT INTO api_group (parent_id, name, sort_index, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS
            ).use { stmt ->
                if (group.parentId == null) stmt.setNull(1, java.sql.Types.BIGINT) else stmt.setLong(1, group.parentId!!)
                stmt.setString(2, group.name)
                stmt.setInt(3, group.sortIndex)
                stmt.setString(4, now)
                stmt.setString(5, now)
                stmt.executeUpdate()
                stmt.generatedKeys.use { keys ->
                    if (keys.next()) {
                        group.id = keys.getLong(1)
                    }
                }
            }
        }
        group.createdAt = HttpSqliteDb.parseDateTime(now)
        group.updatedAt = group.createdAt
        return group
    }

    fun updateGroup(project: Project, group: HttpApiGroup) {
        val now = HttpSqliteDb.nowString()
        HttpSqliteDb.withConnection(project) { connection ->
            connection.prepareStatement(
                """
                UPDATE api_group
                SET parent_id = ?, name = ?, sort_index = ?, updated_at = ?
                WHERE id = ?
                """.trimIndent()
            ).use { stmt ->
                if (group.parentId == null) stmt.setNull(1, java.sql.Types.BIGINT) else stmt.setLong(1, group.parentId!!)
                stmt.setString(2, group.name)
                stmt.setInt(3, group.sortIndex)
                stmt.setString(4, now)
                stmt.setLong(5, group.id)
                stmt.executeUpdate()
            }
        }
        group.updatedAt = HttpSqliteDb.parseDateTime(now)
    }

    fun deleteGroup(project: Project, groupId: Long) {
        HttpSqliteDb.withConnection(project) { connection ->
            connection.prepareStatement("DELETE FROM api_group WHERE id = ?").use { stmt ->
                stmt.setLong(1, groupId)
                stmt.executeUpdate()
            }
        }
    }

    fun insertRequest(project: Project, request: HttpSavedRequest): HttpSavedRequest {
        val now = HttpSqliteDb.nowString()
        val requestXml = HttpSqliteDb.serialize(request.draft)
        HttpSqliteDb.withConnection(project) { connection ->
            connection.prepareStatement(
                """
                INSERT INTO api_request (group_id, name, method, url, request_xml, sort_index, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS
            ).use { stmt ->
                if (request.groupId == null) stmt.setNull(1, java.sql.Types.BIGINT) else stmt.setLong(1, request.groupId!!)
                stmt.setString(2, request.name)
                stmt.setString(3, request.draft.method)
                stmt.setString(4, request.draft.url)
                stmt.setString(5, requestXml)
                stmt.setInt(6, request.sortIndex)
                stmt.setString(7, now)
                stmt.setString(8, now)
                stmt.executeUpdate()
                stmt.generatedKeys.use { keys ->
                    if (keys.next()) {
                        request.id = keys.getLong(1)
                    }
                }
            }
        }
        request.createdAt = HttpSqliteDb.parseDateTime(now)
        request.updatedAt = request.createdAt
        return request
    }

    fun updateRequest(project: Project, request: HttpSavedRequest) {
        val now = HttpSqliteDb.nowString()
        val requestXml = HttpSqliteDb.serialize(request.draft)
        HttpSqliteDb.withConnection(project) { connection ->
            connection.prepareStatement(
                """
                UPDATE api_request
                SET group_id = ?, name = ?, method = ?, url = ?, request_xml = ?, sort_index = ?, updated_at = ?
                WHERE id = ?
                """.trimIndent()
            ).use { stmt ->
                if (request.groupId == null) stmt.setNull(1, java.sql.Types.BIGINT) else stmt.setLong(1, request.groupId!!)
                stmt.setString(2, request.name)
                stmt.setString(3, request.draft.method)
                stmt.setString(4, request.draft.url)
                stmt.setString(5, requestXml)
                stmt.setInt(6, request.sortIndex)
                stmt.setString(7, now)
                stmt.setLong(8, request.id)
                stmt.executeUpdate()
            }
        }
        request.updatedAt = HttpSqliteDb.parseDateTime(now)
    }

    fun deleteRequest(project: Project, requestId: Long) {
        HttpSqliteDb.withConnection(project) { connection ->
            connection.prepareStatement("DELETE FROM api_request WHERE id = ?").use { stmt ->
                stmt.setLong(1, requestId)
                stmt.executeUpdate()
            }
        }
    }

    fun updateGroups(project: Project, groups: List<HttpApiGroup>) {
        val now = HttpSqliteDb.nowString()
        HttpSqliteDb.withConnection(project) { connection ->
            connection.prepareStatement(
                """
                UPDATE api_group
                SET parent_id = ?, sort_index = ?, updated_at = ?
                WHERE id = ?
                """.trimIndent()
            ).use { stmt ->
                groups.forEach { group ->
                    if (group.parentId == null) stmt.setNull(1, java.sql.Types.BIGINT) else stmt.setLong(1, group.parentId!!)
                    stmt.setInt(2, group.sortIndex)
                    stmt.setString(3, now)
                    stmt.setLong(4, group.id)
                    stmt.addBatch()
                }
                stmt.executeBatch()
            }
        }
        val updated = HttpSqliteDb.parseDateTime(now)
        groups.forEach { it.updatedAt = updated }
    }

    fun updateRequests(project: Project, requests: List<HttpSavedRequest>) {
        val now = HttpSqliteDb.nowString()
        HttpSqliteDb.withConnection(project) { connection ->
            connection.prepareStatement(
                """
                UPDATE api_request
                SET group_id = ?, sort_index = ?, updated_at = ?
                WHERE id = ?
                """.trimIndent()
            ).use { stmt ->
                requests.forEach { request ->
                    if (request.groupId == null) stmt.setNull(1, java.sql.Types.BIGINT) else stmt.setLong(1, request.groupId!!)
                    stmt.setInt(2, request.sortIndex)
                    stmt.setString(3, now)
                    stmt.setLong(4, request.id)
                    stmt.addBatch()
                }
                stmt.executeBatch()
            }
        }
        val updated = HttpSqliteDb.parseDateTime(now)
        requests.forEach { it.updatedAt = updated }
    }
}
