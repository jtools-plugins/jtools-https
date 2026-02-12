package com.lhstack.https

import com.intellij.openapi.project.Project

object HttpCookieStorage {
    private const val MAX_COOKIES = 200

    fun load(project: Project): MutableList<HttpCookieEntry> {
        val entries = HttpSqliteDb.withConnection(project) { connection ->
            connection.prepareStatement(
                """
                SELECT name, value, domain, path, expires_at, secure, http_only
                FROM cookies
                ORDER BY id
                """.trimIndent()
            ).use { stmt ->
                stmt.executeQuery().use { rs ->
                    val list = mutableListOf<HttpCookieEntry>()
                    while (rs.next()) {
                        list.add(
                            HttpCookieEntry(
                                name = rs.getString("name"),
                                value = rs.getString("value"),
                                domain = rs.getString("domain"),
                                path = rs.getString("path"),
                                expiresAt = rs.getLong("expires_at"),
                                secure = rs.getInt("secure") != 0,
                                httpOnly = rs.getInt("http_only") != 0
                            )
                        )
                    }
                    list
                }
            }
        }
        return filterExpired(entries)
    }

    fun save(project: Project, entries: List<HttpCookieEntry>) {
        val trimmed = if (entries.size > MAX_COOKIES) entries.take(MAX_COOKIES) else entries
        val now = HttpSqliteDb.nowString()
        HttpSqliteDb.withConnection(project) { connection ->
            connection.createStatement().use { stmt ->
                stmt.execute("DELETE FROM cookies")
            }
            connection.prepareStatement(
                """
                INSERT INTO cookies (
                    name,
                    value,
                    domain,
                    path,
                    expires_at,
                    secure,
                    http_only,
                    created_at,
                    updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { stmt ->
                trimmed.forEach { entry ->
                    stmt.setString(1, entry.name)
                    stmt.setString(2, entry.value)
                    stmt.setString(3, entry.domain)
                    stmt.setString(4, entry.path)
                    stmt.setLong(5, entry.expiresAt)
                    stmt.setInt(6, if (entry.secure) 1 else 0)
                    stmt.setInt(7, if (entry.httpOnly) 1 else 0)
                    stmt.setString(8, now)
                    stmt.setString(9, now)
                    stmt.addBatch()
                }
                stmt.executeBatch()
            }
        }
    }

    fun clear(project: Project) {
        HttpSqliteDb.withConnection(project) { connection ->
            connection.createStatement().use { stmt ->
                stmt.execute("DELETE FROM cookies")
            }
        }
    }

    private fun filterExpired(entries: MutableList<HttpCookieEntry>): MutableList<HttpCookieEntry> {
        val now = System.currentTimeMillis()
        return entries.filter { it.expiresAt <= 0 || it.expiresAt > now }.toMutableList()
    }
}
