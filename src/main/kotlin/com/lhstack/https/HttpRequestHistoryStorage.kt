package com.lhstack.https

import com.intellij.openapi.project.Project

object HttpRequestHistoryStorage {
    private const val MAX_PERSISTED_BODY_CHARS = 200_000
    private const val MAX_PERSISTED_BASE64_CHARS = 200_000

    fun loadAll(project: Project): MutableList<HttpRequestHistoryEntry> {
        return loadInternal(project, null, null)
    }

    fun loadForSource(project: Project, sourceType: HistorySourceType, sourceId: Long): MutableList<HttpRequestHistoryEntry> {
        return loadInternal(project, sourceType, sourceId)
    }

    fun append(project: Project, entry: HttpRequestHistoryEntry) {
        val now = HttpSqliteDb.nowString()
        val safeResponse = entry.response?.let { trimResponse(it) }
        val requestXml = HttpSqliteDb.serialize(entry.request)
        val responseXml = safeResponse?.let { HttpSqliteDb.serialize(it) }
        val status = safeResponse?.status ?: 0
        HttpSqliteDb.withConnection(project) { connection ->
            connection.prepareStatement(
                """
                INSERT INTO request_history (
                    source_type,
                    source_id,
                    method,
                    url,
                    status,
                    request_xml,
                    response_xml,
                    created_at,
                    updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, entry.sourceType.name)
                if (entry.sourceId == null) stmt.setNull(2, java.sql.Types.BIGINT) else stmt.setLong(2, entry.sourceId!!)
                stmt.setString(3, entry.request.method)
                stmt.setString(4, entry.request.url)
                stmt.setInt(5, status)
                stmt.setString(6, requestXml)
                if (responseXml == null) stmt.setNull(7, java.sql.Types.VARCHAR) else stmt.setString(7, responseXml)
                stmt.setString(8, now)
                stmt.setString(9, now)
                stmt.executeUpdate()
            }
        }
    }

    fun deleteById(project: Project, id: Long) {
        if (id <= 0) {
            return
        }
        HttpSqliteDb.withConnection(project) { connection ->
            connection.prepareStatement(
                """
                DELETE FROM request_history
                WHERE id = ?
                """.trimIndent()
            ).use { stmt ->
                stmt.setLong(1, id)
                stmt.executeUpdate()
            }
        }
    }

    fun clearAll(project: Project) {
        HttpSqliteDb.withConnection(project) { connection ->
            connection.prepareStatement(
                """
                DELETE FROM request_history
                """.trimIndent()
            ).use { stmt ->
                stmt.executeUpdate()
            }
        }
    }

    fun clearForSource(project: Project, sourceType: HistorySourceType, sourceId: Long) {
        HttpSqliteDb.withConnection(project) { connection ->
            connection.prepareStatement(
                """
                DELETE FROM request_history
                WHERE source_type = ? AND source_id = ?
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, sourceType.name)
                stmt.setLong(2, sourceId)
                stmt.executeUpdate()
            }
        }
    }

    fun updateSourceId(project: Project, oldId: Long, newId: Long) {
        if (oldId == newId) {
            return
        }
        HttpSqliteDb.withConnection(project) { connection ->
            connection.prepareStatement(
                """
                UPDATE request_history
                SET source_id = ?
                WHERE source_type = ? AND source_id = ?
                """.trimIndent()
            ).use { stmt ->
                stmt.setLong(1, newId)
                stmt.setString(2, HistorySourceType.TAB.name)
                stmt.setLong(3, oldId)
                stmt.executeUpdate()
            }
        }
    }

    private fun loadInternal(
        project: Project,
        sourceType: HistorySourceType?,
        sourceId: Long?
    ): MutableList<HttpRequestHistoryEntry> {
        return HttpSqliteDb.withConnection(project) { connection ->
            val sql = if (sourceType == null || sourceId == null) {
                """
                SELECT id, source_type, source_id, request_xml, response_xml, created_at, updated_at
                FROM request_history
                ORDER BY id DESC
                """.trimIndent()
            } else {
                """
                SELECT id, source_type, source_id, request_xml, response_xml, created_at, updated_at
                FROM request_history
                WHERE source_type = ? AND source_id = ?
                ORDER BY id DESC
                """.trimIndent()
            }
            connection.prepareStatement(sql).use { stmt ->
                if (sourceType != null && sourceId != null) {
                    stmt.setString(1, sourceType.name)
                    stmt.setLong(2, sourceId)
                }
                stmt.executeQuery().use { rs ->
                    val list = mutableListOf<HttpRequestHistoryEntry>()
                    while (rs.next()) {
                        val requestXml = rs.getString("request_xml")
                        val responseXml = rs.getString("response_xml")
                        val request = HttpSqliteDb.deserialize(requestXml, HttpRequestDraft::class.java) ?: HttpRequestDraft()
                        val response = responseXml?.let { HttpSqliteDb.deserialize(it, HttpResponseSnapshot::class.java) }
                        list.add(
                            HttpRequestHistoryEntry(
                                id = rs.getLong("id"),
                                sourceType = HistorySourceType.valueOf(rs.getString("source_type")),
                                sourceId = rs.getLong("source_id").takeIf { !rs.wasNull() },
                                request = request,
                                response = response,
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

    private fun trimResponse(response: HttpResponseSnapshot): HttpResponseSnapshot {
        val (body, bodyTrimmed) = trimValue(response.body, MAX_PERSISTED_BODY_CHARS)
        val (base64, base64Trimmed) = trimValue(response.bodyBase64, MAX_PERSISTED_BASE64_CHARS)
        if (!bodyTrimmed && !base64Trimmed) {
            return response
        }
        return response.copy(
            body = body,
            bodyBase64 = base64,
            bodyTruncated = response.bodyTruncated || bodyTrimmed || base64Trimmed
        )
    }

    private fun trimValue(value: String?, limit: Int): Pair<String?, Boolean> {
        val raw = value ?: return null to false
        if (raw.length <= limit) {
            return raw to false
        }
        return raw.take(limit) + "\n\n[内容过大，已截断保存]" to true
    }
}
