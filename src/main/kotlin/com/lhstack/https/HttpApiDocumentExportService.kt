package com.lhstack.https

import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.pdfbox.pdmodel.font.PDType1Font
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object HttpApiDocumentExportService {

    data class ExportContent(
        val format: String,
        val defaultFileName: String,
        val bytes: ByteArray
    )

    fun buildExportContent(
        format: String,
        requests: List<HttpSavedRequest>,
        title: String,
        version: String,
        serverUrl: String
    ): ExportContent {
        val normalized = format.trim().lowercase()
        return when (normalized) {
            "openapi", "openapi3" -> {
                val doc = buildOpenApiDocument(requests, title, version, serverUrl)
                ExportContent("openapi", "${sanitizeFileName(title)}-openapi.json", toJson(doc).toByteArray(StandardCharsets.UTF_8))
            }
            "swagger", "swagger2" -> {
                val doc = buildSwaggerDocument(requests, title, version, serverUrl)
                ExportContent("swagger", "${sanitizeFileName(title)}-swagger.json", toJson(doc).toByteArray(StandardCharsets.UTF_8))
            }
            "html" -> {
                val html = buildHtmlDocument(requests, title, version, serverUrl)
                ExportContent("html", "${sanitizeFileName(title)}.html", html.toByteArray(StandardCharsets.UTF_8))
            }
            "pdf" -> {
                val lines = buildPdfLines(requests, title, version, serverUrl)
                val bytes = buildPdfDocument(lines)
                ExportContent("pdf", "${sanitizeFileName(title)}.pdf", bytes)
            }
            else -> throw IllegalArgumentException("format must be one of openapi/swagger/html/pdf")
        }
    }

    private fun buildOpenApiDocument(
        requests: List<HttpSavedRequest>,
        title: String,
        version: String,
        serverUrl: String
    ): Map<String, Any?> {
        val paths = linkedMapOf<String, MutableMap<String, Any?>>()
        val securitySchemes = linkedMapOf<String, Any?>()
        requests.forEach { request ->
            val draft = request.draft
            val path = normalizeDocPath(draft.path.ifBlank { extractDocPath(draft.url) })
            val method = normalizeHttpMethod(draft.method)
            val operation = linkedMapOf<String, Any?>(
                "summary" to request.name,
                "operationId" to "req_${request.id}_$method",
                "responses" to buildOpenApiResponses(draft)
            )
            val parameters = mutableListOf<Map<String, Any?>>()
            draft.pathParams.filter { it.key.isNotBlank() }.forEach { param ->
                val item = linkedMapOf<String, Any?>(
                    "name" to param.key,
                    "in" to "path",
                    "required" to true,
                    "schema" to mapOf("type" to "string"),
                    "example" to param.value
                )
                if (param.description.isNotBlank()) {
                    item["description"] = param.description
                }
                parameters.add(item)
            }
            draft.params.filter { it.key.isNotBlank() }.forEach { param ->
                val item = linkedMapOf<String, Any?>(
                    "name" to param.key,
                    "in" to "query",
                    "required" to false,
                    "schema" to mapOf("type" to "string"),
                    "example" to param.value
                )
                if (param.description.isNotBlank()) {
                    item["description"] = param.description
                }
                parameters.add(item)
            }
            draft.headers.filter { it.key.isNotBlank() && !it.key.equals("Cookie", true) }.forEach { header ->
                val item = linkedMapOf<String, Any?>(
                    "name" to header.key,
                    "in" to "header",
                    "required" to false,
                    "schema" to mapOf("type" to "string"),
                    "example" to header.value
                )
                if (header.description.isNotBlank()) {
                    item["description"] = header.description
                }
                parameters.add(item)
            }
            if (parameters.isNotEmpty()) {
                operation["parameters"] = parameters
            }
            val requestBody = buildOpenApiRequestBody(draft)
            if (requestBody != null) {
                operation["requestBody"] = requestBody
            }
            val operationSecurity = collectOpenApiSecurity(draft, securitySchemes)
            if (operationSecurity.isNotEmpty()) {
                operation["security"] = operationSecurity
            }
            val pathItem = paths.getOrPut(path) { linkedMapOf() }
            pathItem[method] = operation
        }
        val root = linkedMapOf<String, Any?>(
            "openapi" to "3.0.3",
            "info" to mapOf("title" to title, "version" to version),
            "paths" to paths
        )
        if (securitySchemes.isNotEmpty()) {
            root["components"] = mapOf("securitySchemes" to securitySchemes)
        }
        if (serverUrl.isNotBlank()) {
            root["servers"] = listOf(mapOf("url" to serverUrl))
        }
        return root
    }

    private fun buildOpenApiRequestBody(draft: HttpRequestDraft): Map<String, Any?>? {
        return when (parseBodyType(draft.bodyType)) {
            HttpBodyType.NONE -> null
            HttpBodyType.JSON -> {
                val body = draft.body?.trim().orEmpty()
                val schema = buildObjectSchemaFromKv(draft.requestBodyParams)
                val example = parseJsonOrRaw(body)
                if (schema == null && body.isBlank()) {
                    null
                } else {
                    val media = linkedMapOf<String, Any?>()
                    if (schema != null) {
                        media["schema"] = schema
                    } else {
                        media["schema"] = mapOf("type" to "string")
                    }
                    if (example != null) {
                        media["example"] = example
                    }
                    mapOf(
                        "content" to mapOf(
                            "application/json" to media
                        )
                    )
                }
            }
            HttpBodyType.FORM_URLENCODED -> {
                val properties = draft.urlEncoded
                    .filter { it.key.isNotBlank() }
                    .associate { item ->
                        val property = linkedMapOf<String, Any?>(
                            "type" to "string",
                            "example" to item.value
                        )
                        if (item.description.isNotBlank()) {
                            property["description"] = item.description
                        }
                        item.key to property
                    }
                if (properties.isEmpty()) {
                    null
                } else {
                    mapOf(
                        "content" to mapOf(
                            "application/x-www-form-urlencoded" to mapOf(
                                "schema" to mapOf("type" to "object", "properties" to properties)
                            )
                        )
                    )
                }
            }
            HttpBodyType.FORM_DATA -> {
                val properties = draft.formFields
                    .filter { it.key.isNotBlank() }
                    .associate { field ->
                        field.key to if (parseFormFieldType(field.fieldType) == HttpFormFieldType.FILE) {
                            mapOf("type" to "string", "format" to "binary")
                        } else {
                            mapOf("type" to "string", "example" to field.value)
                        }
                    }
                if (properties.isEmpty()) {
                    null
                } else {
                    mapOf(
                        "content" to mapOf(
                            "multipart/form-data" to mapOf(
                                "schema" to mapOf("type" to "object", "properties" to properties)
                            )
                        )
                    )
                }
            }
        }
    }

    private fun buildSwaggerDocument(
        requests: List<HttpSavedRequest>,
        title: String,
        version: String,
        serverUrl: String
    ): Map<String, Any?> {
        val paths = linkedMapOf<String, MutableMap<String, Any?>>()
        val securityDefinitions = linkedMapOf<String, Any?>()
        requests.forEach { request ->
            val draft = request.draft
            val path = normalizeDocPath(draft.path.ifBlank { extractDocPath(draft.url) })
            val method = normalizeHttpMethod(draft.method)
            val produces = draft.responseContentType.trim().ifBlank { "application/json" }
            val operation = linkedMapOf<String, Any?>(
                "summary" to request.name,
                "operationId" to "req_${request.id}_$method",
                "produces" to listOf(produces),
                "responses" to buildSwaggerResponses(draft)
            )
            val parameters = mutableListOf<Map<String, Any?>>()
            draft.pathParams.filter { it.key.isNotBlank() }.forEach { param ->
                val item = linkedMapOf<String, Any?>(
                    "name" to param.key,
                    "in" to "path",
                    "required" to true,
                    "type" to "string",
                    "x-example" to param.value
                )
                if (param.description.isNotBlank()) {
                    item["description"] = param.description
                }
                parameters.add(item)
            }
            draft.params.filter { it.key.isNotBlank() }.forEach { param ->
                val item = linkedMapOf<String, Any?>(
                    "name" to param.key,
                    "in" to "query",
                    "required" to false,
                    "type" to "string",
                    "x-example" to param.value
                )
                if (param.description.isNotBlank()) {
                    item["description"] = param.description
                }
                parameters.add(item)
            }
            draft.headers.filter { it.key.isNotBlank() && !it.key.equals("Cookie", true) }.forEach { header ->
                val item = linkedMapOf<String, Any?>(
                    "name" to header.key,
                    "in" to "header",
                    "required" to false,
                    "type" to "string",
                    "x-example" to header.value
                )
                if (header.description.isNotBlank()) {
                    item["description"] = header.description
                }
                parameters.add(item)
            }
            when (parseBodyType(draft.bodyType)) {
                HttpBodyType.JSON -> {
                    val body = draft.body?.trim().orEmpty()
                    val schema = buildObjectSchemaFromKv(draft.requestBodyParams)
                    if (body.isNotBlank() || schema != null) {
                        operation["consumes"] = listOf("application/json")
                        val bodyParam = linkedMapOf<String, Any?>(
                            "name" to "body",
                            "in" to "body",
                            "required" to false,
                            "schema" to (schema ?: mapOf("type" to "string"))
                        )
                        if (body.isNotBlank()) {
                            bodyParam["x-example"] = body
                        }
                        parameters.add(
                            bodyParam
                        )
                    }
                }
                HttpBodyType.FORM_URLENCODED -> {
                    val fields = draft.urlEncoded.filter { it.key.isNotBlank() }
                    if (fields.isNotEmpty()) {
                        operation["consumes"] = listOf("application/x-www-form-urlencoded")
                        fields.forEach { field ->
                            val item = linkedMapOf<String, Any?>(
                                "name" to field.key,
                                "in" to "formData",
                                "required" to false,
                                "type" to "string",
                                "x-example" to field.value
                            )
                            if (field.description.isNotBlank()) {
                                item["description"] = field.description
                            }
                            parameters.add(item)
                        }
                    }
                }
                HttpBodyType.FORM_DATA -> {
                    val fields = draft.formFields.filter { it.key.isNotBlank() }
                    if (fields.isNotEmpty()) {
                        operation["consumes"] = listOf("multipart/form-data")
                        fields.forEach { field ->
                            parameters.add(
                                mapOf(
                                    "name" to field.key,
                                    "in" to "formData",
                                    "required" to false,
                                    "type" to if (parseFormFieldType(field.fieldType) == HttpFormFieldType.FILE) "file" else "string",
                                    "x-example" to field.value
                                )
                            )
                        }
                    }
                }
                HttpBodyType.NONE -> Unit
            }
            if (parameters.isNotEmpty()) {
                operation["parameters"] = parameters
            }
            val operationSecurity = collectSwaggerSecurity(draft, securityDefinitions)
            if (operationSecurity.isNotEmpty()) {
                operation["security"] = operationSecurity
            }
            val pathItem = paths.getOrPut(path) { linkedMapOf() }
            pathItem[method] = operation
        }
        val root = linkedMapOf<String, Any?>(
            "swagger" to "2.0",
            "info" to mapOf("title" to title, "version" to version),
            "paths" to paths
        )
        if (securityDefinitions.isNotEmpty()) {
            root["securityDefinitions"] = securityDefinitions
        }
        if (serverUrl.isNotBlank()) {
            root["x-server-url"] = serverUrl
        }
        return root
    }

    private fun buildHtmlDocument(
        requests: List<HttpSavedRequest>,
        title: String,
        version: String,
        serverUrl: String
    ): String {
        val generatedAt = DATE_TIME_FORMATTER.format(LocalDateTime.now())
        val sb = StringBuilder()
        sb.append("<!doctype html><html><head><meta charset=\"utf-8\"/>")
        sb.append("<title>").append(escapeHtml(title)).append("</title>")
        sb.append("<style>")
        sb.append("body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;padding:24px;line-height:1.5;}")
        sb.append("h1{margin:0 0 8px;} .meta{color:#666;margin-bottom:16px;} .card{border:1px solid #ddd;border-radius:8px;padding:12px;margin:12px 0;}")
        sb.append(".method{display:inline-block;padding:2px 8px;border-radius:999px;background:#f0f4ff;color:#2442a8;font-weight:600;font-size:12px;}")
        sb.append("table{width:100%;border-collapse:collapse;margin-top:8px;} th,td{border:1px solid #eee;padding:6px 8px;font-size:12px;text-align:left;}")
        sb.append("pre{background:#f8f8f8;border:1px solid #eee;padding:8px;border-radius:6px;overflow:auto;}")
        sb.append("</style></head><body>")
        sb.append("<h1>").append(escapeHtml(title)).append("</h1>")
        sb.append("<div class=\"meta\">version: ").append(escapeHtml(version))
        if (serverUrl.isNotBlank()) {
            sb.append(" | server: ").append(escapeHtml(serverUrl))
        }
        sb.append(" | generatedAt: ").append(escapeHtml(generatedAt)).append("</div>")
        requests.forEach { request ->
            val draft = request.draft
            sb.append("<div class=\"card\">")
            sb.append("<h2>").append(escapeHtml(request.name)).append("</h2>")
            sb.append("<div><span class=\"method\">").append(escapeHtml(draft.method)).append("</span> ")
                .append(escapeHtml(draft.url)).append("</div>")
            appendKvTable(sb, "路径变量", draft.pathParams)
            appendKvTable(sb, "查询参数", draft.params)
            appendKvTable(sb, "请求头", draft.headers)
            when (parseBodyType(draft.bodyType)) {
                HttpBodyType.JSON -> {
                    val body = draft.body?.trim().orEmpty()
                    if (body.isNotBlank()) {
                        sb.append("<h4>JSON Body</h4><pre>").append(escapeHtml(body)).append("</pre>")
                    }
                }
                HttpBodyType.FORM_URLENCODED -> appendKvTable(sb, "x-www-form-urlencoded", draft.urlEncoded)
                HttpBodyType.FORM_DATA -> {
                    if (draft.formFields.isNotEmpty()) {
                        sb.append("<h4>form-data</h4><table><thead><tr><th>key</th><th>value</th><th>type</th></tr></thead><tbody>")
                        draft.formFields.forEach { field ->
                            sb.append("<tr><td>").append(escapeHtml(field.key)).append("</td><td>")
                                .append(escapeHtml(field.value)).append("</td><td>")
                                .append(escapeHtml(field.fieldType)).append("</td></tr>")
                        }
                        sb.append("</tbody></table>")
                    }
                }
                HttpBodyType.NONE -> Unit
            }
            appendKvTable(sb, "请求字段说明", draft.requestBodyParams)
            appendResponseDocHtml(sb, draft)
            if (!draft.preScript.isNullOrBlank()) {
                sb.append("<h4>前置脚本</h4><pre>").append(escapeHtml(draft.preScript ?: "")).append("</pre>")
            }
            if (!draft.postScript.isNullOrBlank()) {
                sb.append("<h4>后置脚本</h4><pre>").append(escapeHtml(draft.postScript ?: "")).append("</pre>")
            }
            sb.append("</div>")
        }
        sb.append("</body></html>")
        return sb.toString()
    }

    private fun appendResponseDocHtml(sb: StringBuilder, draft: HttpRequestDraft) {
        val statusDocs = normalizeResponseStatusDocs(draft)
        val status = draft.responseStatus.trim().ifBlank { statusDocs.firstOrNull()?.key ?: "200" }
        val contentType = draft.responseContentType.trim().ifBlank { "application/json" }
        val description = draft.responseDescription.orEmpty().trim()
        val body = draft.responseBody.orEmpty().trim()
        val params = draft.responseParams.filter { it.key.isNotBlank() }
        if (statusDocs.isEmpty() && description.isBlank() && body.isBlank() && params.isEmpty()) {
            return
        }
        sb.append("<h4>响应文档</h4>")
        sb.append("<div>status: ").append(escapeHtml(status))
            .append(" | contentType: ").append(escapeHtml(contentType))
            .append("</div>")
        if (description.isNotBlank()) {
            sb.append("<div>description: ").append(escapeHtml(description)).append("</div>")
        }
        appendKvTable(sb, "响应状态码说明", statusDocs)
        appendKvTable(sb, "响应字段说明", params)
        if (body.isNotBlank()) {
            sb.append("<h4>响应示例</h4><pre>").append(escapeHtml(body)).append("</pre>")
        }
    }

    private fun buildOpenApiResponses(draft: HttpRequestDraft): Map<String, Any?> {
        val statusDocs = normalizeResponseStatusDocs(draft)
        val status = draft.responseStatus.trim().ifBlank { statusDocs.firstOrNull()?.key ?: "200" }
        val fallbackDescription = statusDocs.firstOrNull { it.key == status }?.description.orEmpty()
        val description = draft.responseDescription.orEmpty().ifBlank { fallbackDescription.ifBlank { "OK" } }
        val contentType = draft.responseContentType.trim().ifBlank { "application/json" }
        val body = draft.responseBody.orEmpty().trim()
        val schema = buildObjectSchemaFromKv(draft.responseParams)
        val responses = linkedMapOf<String, Any?>()
        statusDocs.forEach { row ->
            val code = row.key.trim()
            if (code.isBlank()) {
                return@forEach
            }
            val codeDescription = if (code == status) description else row.description.ifBlank { "OK" }
            responses[code] = mapOf("description" to codeDescription)
        }
        if (responses.isEmpty()) {
            responses[status] = mapOf("description" to description)
        }
        if (body.isBlank() && schema == null) {
            return responses
        }
        val media = linkedMapOf<String, Any?>()
        if (schema != null) {
            media["schema"] = schema
        } else {
            media["schema"] = mapOf("type" to "string")
        }
        parseJsonOrRaw(body)?.let { media["example"] = it }
        responses[status] = linkedMapOf<String, Any?>(
            "description" to description,
            "content" to mapOf(contentType to media)
        )
        return responses
    }

    private fun buildSwaggerResponses(draft: HttpRequestDraft): Map<String, Any?> {
        val statusDocs = normalizeResponseStatusDocs(draft)
        val status = draft.responseStatus.trim().ifBlank { statusDocs.firstOrNull()?.key ?: "200" }
        val fallbackDescription = statusDocs.firstOrNull { it.key == status }?.description.orEmpty()
        val description = draft.responseDescription.orEmpty().ifBlank { fallbackDescription.ifBlank { "OK" } }
        val body = draft.responseBody.orEmpty().trim()
        val contentType = draft.responseContentType.trim().ifBlank { "application/json" }
        val schema = buildObjectSchemaFromKv(draft.responseParams)
        val responses = linkedMapOf<String, Any?>()
        statusDocs.forEach { row ->
            val code = row.key.trim()
            if (code.isBlank()) {
                return@forEach
            }
            val codeDescription = if (code == status) description else row.description.ifBlank { "OK" }
            responses[code] = mapOf("description" to codeDescription)
        }
        if (responses.isEmpty()) {
            responses[status] = mapOf("description" to description)
        }
        if (body.isBlank() && schema == null) {
            return responses
        }
        val response = linkedMapOf<String, Any?>(
            "description" to description,
            "schema" to (schema ?: mapOf("type" to "string"))
        )
        if (body.isNotBlank()) {
            response["examples"] = mapOf(contentType to parseJsonOrRaw(body))
        }
        responses[status] = response
        return responses
    }

    private fun buildObjectSchemaFromKv(rows: List<HttpKeyValue>): Map<String, Any?>? {
        val items = rows.filter { it.key.isNotBlank() }
        if (items.isEmpty()) {
            return null
        }
        val root = linkedMapOf<String, Any?>(
            "type" to "object",
            "properties" to linkedMapOf<String, Any?>()
        )
        items.forEach { row ->
            insertRowIntoSchema(root, row)
        }
        val properties = root["properties"] as? Map<*, *>
        if (properties.isNullOrEmpty()) {
            return null
        }
        return root
    }

    private fun collectOpenApiSecurity(
        draft: HttpRequestDraft,
        securitySchemes: MutableMap<String, Any?>
    ): List<Map<String, Any?>> {
        val requirements = mutableListOf<Map<String, Any?>>()
        val seen = linkedSetOf<String>()
        val authHeader = draft.headers.firstOrNull { it.key.equals("Authorization", ignoreCase = true) }
        if (authHeader != null) {
            val authValue = authHeader.value.trim()
            if (authValue.startsWith("Basic ", ignoreCase = true)) {
                val schemeName = "basicAuth"
                securitySchemes.putIfAbsent(schemeName, mapOf("type" to "http", "scheme" to "basic"))
                addSecurityRequirement(requirements, seen, schemeName)
            } else {
                val schemeName = "bearerAuth"
                securitySchemes.putIfAbsent(
                    schemeName,
                    mapOf("type" to "http", "scheme" to "bearer", "bearerFormat" to "JWT")
                )
                addSecurityRequirement(requirements, seen, schemeName)
            }
        }
        draft.headers.forEach { header ->
            val key = header.key.trim()
            if (key.isBlank() || key.equals("Authorization", ignoreCase = true) || key.equals("Cookie", ignoreCase = true)) {
                return@forEach
            }
            if (!looksLikeAuthKey(key)) {
                return@forEach
            }
            val schemeName = "apiKeyHeader_${sanitizeSecurityName(key)}"
            securitySchemes.putIfAbsent(
                schemeName,
                mapOf("type" to "apiKey", "in" to "header", "name" to key)
            )
            addSecurityRequirement(requirements, seen, schemeName)
        }
        draft.params.forEach { param ->
            val key = param.key.trim()
            if (key.isBlank() || !looksLikeAuthKey(key)) {
                return@forEach
            }
            val schemeName = "apiKeyQuery_${sanitizeSecurityName(key)}"
            securitySchemes.putIfAbsent(
                schemeName,
                mapOf("type" to "apiKey", "in" to "query", "name" to key)
            )
            addSecurityRequirement(requirements, seen, schemeName)
        }
        val cookieHeader = draft.headers.firstOrNull { it.key.equals("Cookie", ignoreCase = true) }
        if (cookieHeader != null) {
            parseCookieNames(cookieHeader.value).forEach { cookieName ->
                if (!looksLikeAuthKey(cookieName)) {
                    return@forEach
                }
                val schemeName = "apiKeyCookie_${sanitizeSecurityName(cookieName)}"
                securitySchemes.putIfAbsent(
                    schemeName,
                    mapOf("type" to "apiKey", "in" to "cookie", "name" to cookieName)
                )
                addSecurityRequirement(requirements, seen, schemeName)
            }
        }
        return requirements
    }

    private fun collectSwaggerSecurity(
        draft: HttpRequestDraft,
        securityDefinitions: MutableMap<String, Any?>
    ): List<Map<String, Any?>> {
        val requirements = mutableListOf<Map<String, Any?>>()
        val seen = linkedSetOf<String>()
        val authHeader = draft.headers.firstOrNull { it.key.equals("Authorization", ignoreCase = true) }
        if (authHeader != null) {
            val authValue = authHeader.value.trim()
            if (authValue.startsWith("Basic ", ignoreCase = true)) {
                val schemeName = "basicAuth"
                securityDefinitions.putIfAbsent(schemeName, mapOf("type" to "basic"))
                addSecurityRequirement(requirements, seen, schemeName)
            } else {
                val schemeName = "bearerAuth"
                securityDefinitions.putIfAbsent(
                    schemeName,
                    mapOf("type" to "apiKey", "in" to "header", "name" to "Authorization")
                )
                addSecurityRequirement(requirements, seen, schemeName)
            }
        }
        draft.headers.forEach { header ->
            val key = header.key.trim()
            if (key.isBlank() || key.equals("Authorization", ignoreCase = true) || key.equals("Cookie", ignoreCase = true)) {
                return@forEach
            }
            if (!looksLikeAuthKey(key)) {
                return@forEach
            }
            val schemeName = "apiKeyHeader_${sanitizeSecurityName(key)}"
            securityDefinitions.putIfAbsent(
                schemeName,
                mapOf("type" to "apiKey", "in" to "header", "name" to key)
            )
            addSecurityRequirement(requirements, seen, schemeName)
        }
        draft.params.forEach { param ->
            val key = param.key.trim()
            if (key.isBlank() || !looksLikeAuthKey(key)) {
                return@forEach
            }
            val schemeName = "apiKeyQuery_${sanitizeSecurityName(key)}"
            securityDefinitions.putIfAbsent(
                schemeName,
                mapOf("type" to "apiKey", "in" to "query", "name" to key)
            )
            addSecurityRequirement(requirements, seen, schemeName)
        }
        val cookieHeader = draft.headers.firstOrNull { it.key.equals("Cookie", ignoreCase = true) }
        if (cookieHeader != null) {
            parseCookieNames(cookieHeader.value).forEach { cookieName ->
                if (!looksLikeAuthKey(cookieName)) {
                    return@forEach
                }
                val schemeName = "apiKeyCookie_${sanitizeSecurityName(cookieName)}"
                securityDefinitions.putIfAbsent(
                    schemeName,
                    mapOf("type" to "apiKey", "in" to "header", "name" to "Cookie")
                )
                addSecurityRequirement(requirements, seen, schemeName)
            }
        }
        return requirements
    }

    private fun addSecurityRequirement(
        target: MutableList<Map<String, Any?>>,
        seen: MutableSet<String>,
        schemeName: String
    ) {
        if (!seen.add(schemeName)) {
            return
        }
        target.add(mapOf(schemeName to emptyList<String>()))
    }

    private fun sanitizeSecurityName(raw: String): String {
        return raw.trim()
            .replace(Regex("[^A-Za-z0-9]+"), "_")
            .trim('_')
            .ifBlank { "key" }
    }

    private fun looksLikeAuthKey(raw: String): Boolean {
        val key = raw.trim().lowercase()
        if (key.isBlank()) {
            return false
        }
        return key.contains("token") ||
            key.contains("api-key") ||
            key.contains("apikey") ||
            key.contains("access-key") ||
            key.contains("accesskey") ||
            key.contains("appkey") ||
            key.contains("secret") ||
            key.contains("signature") ||
            key.startsWith("x-auth") ||
            key.startsWith("auth-")
    }

    private fun parseCookieNames(cookieHeader: String): List<String> {
        if (cookieHeader.isBlank()) {
            return emptyList()
        }
        return cookieHeader.split(";")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { it.substringBefore("=").trim() }
            .filter { it.isNotBlank() }
    }

    private fun normalizeResponseStatusDocs(draft: HttpRequestDraft): List<HttpKeyValue> {
        val rows = linkedMapOf<String, HttpKeyValue>()
        draft.responseStatusDocs.forEach { row ->
            val status = row.key.trim()
            if (status.isBlank()) {
                return@forEach
            }
            val existing = rows[status]
            if (existing == null) {
                rows[status] = HttpKeyValue(key = status, value = "", description = row.description.trim())
            } else if (existing.description.isBlank()) {
                existing.description = row.description.trim()
            }
        }
        val fallbackStatus = draft.responseStatus.trim()
        val fallbackDescription = draft.responseDescription.orEmpty().trim()
        if (fallbackStatus.isNotBlank() || fallbackDescription.isNotBlank()) {
            val key = fallbackStatus.ifBlank { "200" }
            val existing = rows[key]
            if (existing == null) {
                rows[key] = HttpKeyValue(key = key, value = "", description = fallbackDescription)
            } else if (existing.description.isBlank()) {
                existing.description = fallbackDescription
            }
        }
        return rows.values.toList()
    }

    private fun parseJsonOrRaw(value: String): Any? {
        val text = value.trim()
        if (text.isBlank()) {
            return null
        }
        return runCatching { objectMapper.readValue(text, Any::class.java) }.getOrDefault(text)
    }

    private fun insertRowIntoSchema(root: MutableMap<String, Any?>, row: HttpKeyValue) {
        val segments = parseKeySegments(row.key)
        if (segments.isEmpty()) {
            return
        }
        var container = ensureSchemaPropertiesContainer(root)
        for (index in segments.indices) {
            val segment = segments[index]
            val isLast = index == segments.lastIndex
            val propertySchema = ensureChildSchema(container, segment.name)
            if (segment.isArray) {
                propertySchema["type"] = "array"
                val itemsSchema = ensureArrayItemsSchema(propertySchema)
                if (isLast) {
                    applyLeafSchema(itemsSchema, row)
                    if (row.description.isNotBlank() && propertySchema["description"] == null) {
                        propertySchema["description"] = row.description
                    }
                } else {
                    val objectItems = ensureObjectSchema(itemsSchema)
                    container = ensureSchemaPropertiesContainer(objectItems)
                }
            } else {
                if (isLast) {
                    applyLeafSchema(propertySchema, row)
                } else {
                    val nested = ensureObjectSchema(propertySchema)
                    container = ensureSchemaPropertiesContainer(nested)
                }
            }
        }
    }

    private fun parseKeySegments(key: String): List<SchemaPathSegment> {
        val raw = key.trim()
        if (raw.isBlank()) {
            return emptyList()
        }
        val segments = mutableListOf<SchemaPathSegment>()
        raw.split(".")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .forEach { part ->
                if (part.endsWith("[]")) {
                    val name = part.removeSuffix("[]").trim()
                    if (name.isNotBlank()) {
                        segments.add(SchemaPathSegment(name = name, isArray = true))
                    }
                } else {
                    segments.add(SchemaPathSegment(name = part, isArray = false))
                }
            }
        return segments
    }

    private fun ensureSchemaPropertiesContainer(schema: MutableMap<String, Any?>): MutableMap<String, Any?> {
        val existing = schema["properties"] as? MutableMap<String, Any?>
        if (existing != null) {
            return existing
        }
        val created = linkedMapOf<String, Any?>()
        schema["type"] = "object"
        schema["properties"] = created
        return created
    }

    private fun ensureChildSchema(
        properties: MutableMap<String, Any?>,
        name: String
    ): MutableMap<String, Any?> {
        val existing = properties[name] as? MutableMap<String, Any?>
        if (existing != null) {
            return existing
        }
        val created = linkedMapOf<String, Any?>()
        properties[name] = created
        return created
    }

    private fun ensureArrayItemsSchema(property: MutableMap<String, Any?>): MutableMap<String, Any?> {
        val existing = property["items"] as? MutableMap<String, Any?>
        if (existing != null) {
            return existing
        }
        val created = linkedMapOf<String, Any?>()
        property["items"] = created
        return created
    }

    private fun ensureObjectSchema(schema: MutableMap<String, Any?>): MutableMap<String, Any?> {
        val type = schema["type"]?.toString().orEmpty()
        if (type.isBlank() || type == "object") {
            schema["type"] = "object"
            return schema
        }
        if (type == "array") {
            return ensureArrayItemsSchema(schema)
        }
        schema["type"] = "object"
        return schema
    }

    private fun applyLeafSchema(schema: MutableMap<String, Any?>, row: HttpKeyValue) {
        val exampleRaw = row.value.trim()
        val example = if (exampleRaw.isBlank()) null else parseJsonOrRaw(exampleRaw)
        val inferred = inferSchemaByExample(example)
        if (inferred != null) {
            schema.clear()
            schema.putAll(inferred)
        } else if (schema["type"] == null) {
            schema["type"] = "string"
        }
        if (example != null) {
            schema["example"] = example
        }
        if (row.description.isNotBlank()) {
            schema["description"] = row.description
        }
    }

    private fun inferSchemaByExample(value: Any?): MutableMap<String, Any?>? {
        return when (value) {
            null -> null
            is Int, is Long, is Short, is Byte -> linkedMapOf("type" to "integer")
            is Float, is Double -> linkedMapOf("type" to "number")
            is Boolean -> linkedMapOf("type" to "boolean")
            is String -> linkedMapOf("type" to "string")
            is Map<*, *> -> linkedMapOf("type" to "object")
            is List<*> -> {
                val itemSchema = inferSchemaByExample(value.firstOrNull()) ?: linkedMapOf("type" to "string")
                linkedMapOf(
                    "type" to "array",
                    "items" to itemSchema
                )
            }
            else -> linkedMapOf("type" to "string")
        }
    }

    private fun appendKvTable(sb: StringBuilder, title: String, rows: List<HttpKeyValue>) {
        val items = rows.filter { it.key.isNotBlank() }
        if (items.isEmpty()) {
            return
        }
        val hasDesc = items.any { it.description.isNotBlank() }
        sb.append("<h4>").append(escapeHtml(title)).append("</h4>")
        if (hasDesc) {
            sb.append("<table><thead><tr><th>key</th><th>value</th><th>description</th></tr></thead><tbody>")
            items.forEach { row ->
                sb.append("<tr><td>").append(escapeHtml(row.key)).append("</td><td>")
                    .append(escapeHtml(row.value)).append("</td><td>")
                    .append(escapeHtml(row.description)).append("</td></tr>")
            }
        } else {
            sb.append("<table><thead><tr><th>key</th><th>value</th></tr></thead><tbody>")
            items.forEach { row ->
                sb.append("<tr><td>").append(escapeHtml(row.key)).append("</td><td>")
                    .append(escapeHtml(row.value)).append("</td></tr>")
            }
        }
        sb.append("</tbody></table>")
    }

    private fun buildPdfLines(
        requests: List<HttpSavedRequest>,
        title: String,
        version: String,
        serverUrl: String
    ): List<String> {
        val lines = mutableListOf<String>()
        lines.add(title)
        lines.add("version: $version")
        if (serverUrl.isNotBlank()) {
            lines.add("server: $serverUrl")
        }
        lines.add("generatedAt: ${DATE_TIME_FORMATTER.format(LocalDateTime.now())}")
        lines.add("")
        requests.forEachIndexed { index, request ->
            val draft = request.draft
            lines.add("${index + 1}. ${request.name}")
            lines.add("${draft.method.uppercase()} ${draft.url}")
            if (draft.pathParams.isNotEmpty()) {
                lines.add("  pathParams: ${draft.pathParams.joinToString(", ") { formatKv(it) }}")
            }
            if (draft.params.isNotEmpty()) {
                lines.add("  params: ${draft.params.joinToString(", ") { formatKv(it) }}")
            }
            if (draft.headers.isNotEmpty()) {
                lines.add("  headers: ${draft.headers.joinToString(", ") { formatKv(it) }}")
            }
            when (parseBodyType(draft.bodyType)) {
                HttpBodyType.JSON -> {
                    val body = draft.body?.trim().orEmpty()
                    if (body.isNotBlank()) {
                        lines.add("  body(json): ${body.take(800)}")
                    }
                }
                HttpBodyType.FORM_URLENCODED -> {
                    if (draft.urlEncoded.isNotEmpty()) {
                        lines.add("  body(form-urlencoded): ${draft.urlEncoded.joinToString(", ") { formatKv(it) }}")
                    }
                }
                HttpBodyType.FORM_DATA -> {
                    if (draft.formFields.isNotEmpty()) {
                        lines.add("  body(form-data): ${draft.formFields.joinToString(", ") { "${it.key}=${it.value}[${it.fieldType}]" }}")
                    }
                }
                HttpBodyType.NONE -> Unit
            }
            if (draft.requestBodyParams.isNotEmpty()) {
                lines.add("  requestDoc: ${draft.requestBodyParams.joinToString(", ") { formatKv(it) }}")
            }
            val statusDocs = normalizeResponseStatusDocs(draft)
            val responseStatus = draft.responseStatus.trim().ifBlank { statusDocs.firstOrNull()?.key ?: "200" }
            val responseType = draft.responseContentType.trim().ifBlank { "application/json" }
            val responseDescription = draft.responseDescription.orEmpty().trim()
            if (statusDocs.isNotEmpty() || responseDescription.isNotBlank() || draft.responseBody.orEmpty().isNotBlank() || draft.responseParams.isNotEmpty()) {
                lines.add("  responseDoc: status=$responseStatus, type=$responseType")
                if (statusDocs.isNotEmpty()) {
                    lines.add("    statusCodes: ${statusDocs.joinToString(", ") { formatKv(it) }}")
                }
                if (responseDescription.isNotBlank()) {
                    lines.add("    description: $responseDescription")
                }
                if (draft.responseParams.isNotEmpty()) {
                    lines.add("    fields: ${draft.responseParams.joinToString(", ") { formatKv(it) }}")
                }
                val responseBody = draft.responseBody.orEmpty().trim()
                if (responseBody.isNotBlank()) {
                    lines.add("    example: ${responseBody.take(800)}")
                }
            }
            lines.add("")
        }
        return lines
    }

    private fun buildPdfDocument(lines: List<String>): ByteArray {
        PDDocument().use { doc ->
            val font = resolvePdfFont(doc)
            val fontSize = 10f
            val lineHeight = 14f
            val margin = 48f
            var page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            var content = PDPageContentStream(doc, page)
            var y = page.mediaBox.height - margin
            val maxWidth = page.mediaBox.width - margin * 2
            content.beginText()
            content.setFont(font, fontSize)
            content.newLineAtOffset(margin, y)
            lines.forEach { line ->
                val wrapped = wrapPdfLine(sanitizePdfText(line), font, fontSize, maxWidth)
                wrapped.forEach { row ->
                    if (y <= margin) {
                        content.endText()
                        content.close()
                        page = PDPage(PDRectangle.A4)
                        doc.addPage(page)
                        content = PDPageContentStream(doc, page)
                        y = page.mediaBox.height - margin
                        content.beginText()
                        content.setFont(font, fontSize)
                        content.newLineAtOffset(margin, y)
                    }
                    content.showText(row)
                    content.newLineAtOffset(0f, -lineHeight)
                    y -= lineHeight
                }
            }
            content.endText()
            content.close()
            val output = ByteArrayOutputStream()
            doc.save(output)
            return output.toByteArray()
        }
    }

    private fun resolvePdfFont(doc: PDDocument): PDFont {
        PDF_FONT_CANDIDATES.forEach { fontPath ->
            val path = Paths.get(fontPath)
            if (!Files.exists(path) || !Files.isRegularFile(path)) {
                return@forEach
            }
            val loaded = runCatching { PDType0Font.load(doc, path.toFile()) }.getOrNull()
            if (loaded != null) {
                return loaded
            }
        }
        return PDType1Font.HELVETICA
    }

    private fun wrapPdfLine(text: String, font: PDFont, fontSize: Float, maxWidth: Float): List<String> {
        if (text.isEmpty()) {
            return listOf("")
        }
        val lines = mutableListOf<String>()
        var index = 0
        while (index < text.length) {
            var end = index + 1
            while (end <= text.length) {
                val candidate = text.substring(index, end)
                if (textWidth(candidate, font, fontSize) > maxWidth) {
                    break
                }
                end++
            }
            val split = if (end - 1 <= index) index + 1 else end - 1
            lines.add(text.substring(index, split))
            index = split
        }
        return lines
    }

    private fun textWidth(text: String, font: PDFont, fontSize: Float): Float {
        return runCatching { font.getStringWidth(text) / 1000f * fontSize }
            .getOrElse { text.length * fontSize * 0.5f }
    }

    private fun sanitizePdfText(text: String): String {
        return text
            .replace("\t", "    ")
            .replace("\r", " ")
            .replace("\n", " ")
            .map { ch -> if (ch.code in 0..31) ' ' else ch }
            .joinToString("")
    }

    private fun formatKv(item: HttpKeyValue): String {
        return if (item.description.isNotBlank()) {
            "${item.key}=${item.value}(${item.description})"
        } else {
            "${item.key}=${item.value}"
        }
    }

    private fun parseBodyType(raw: String?): HttpBodyType {
        val normalized = raw?.trim()?.uppercase().orEmpty()
        return when (normalized) {
            "JSON" -> HttpBodyType.JSON
            "FORM_URLENCODED", "X-WWW-FORM-URLENCODED", "FORM-URLENCODED" -> HttpBodyType.FORM_URLENCODED
            "FORM_DATA", "FORM-DATA" -> HttpBodyType.FORM_DATA
            else -> HttpBodyType.NONE
        }
    }

    private fun parseFormFieldType(value: String): HttpFormFieldType {
        return if (value.equals("文件", ignoreCase = true) || value.equals(HttpFormFieldType.FILE.name, true)) {
            HttpFormFieldType.FILE
        } else {
            HttpFormFieldType.TEXT
        }
    }

    private fun normalizeDocPath(path: String): String {
        var value = path.trim().ifBlank { "/" }
        if (!value.startsWith("/")) {
            value = "/$value"
        }
        if (value.length > 1 && value.endsWith("/")) {
            value = value.dropLast(1)
        }
        return value
    }

    private fun extractDocPath(url: String): String {
        val trimmed = url.trim()
        if (trimmed.isBlank()) {
            return "/"
        }
        val withoutFragment = trimmed.substringBefore("#")
        if (withoutFragment.startsWith("/")) {
            return withoutFragment.substringBefore('?')
        }
        val normalized = if (withoutFragment.contains("://")) withoutFragment else "http://$withoutFragment"
        return runCatching {
            val uri = URI(normalized)
            uri.path?.ifBlank { "/" }?.substringBefore('?') ?: "/"
        }.getOrDefault("/")
    }

    private fun normalizeHttpMethod(method: String): String {
        val normalized = method.trim().lowercase()
        return when (normalized) {
            "get", "post", "put", "patch", "delete", "head", "options", "trace" -> normalized
            else -> "get"
        }
    }

    private fun escapeHtml(value: String): String {
        val sb = StringBuilder(value.length + 16)
        value.forEach { ch ->
            when (ch) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&#39;")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun sanitizeFileName(name: String): String {
        val value = name.trim().ifBlank { "http-api" }
        return value.replace(Regex("[^A-Za-z0-9._-]"), "_")
    }

    private fun toJson(value: Any?): String {
        return when (value) {
            null -> "null"
            is String -> "\"${escape(value)}\""
            is Number, is Boolean -> value.toString()
            is Map<*, *> -> {
                val items = value.entries.joinToString(",") { (k, v) ->
                    "\"${escape(k?.toString().orEmpty())}\":${toJson(v)}"
                }
                "{$items}"
            }
            is Iterable<*> -> value.joinToString(",", prefix = "[", postfix = "]") { toJson(it) }
            is Array<*> -> value.joinToString(",", prefix = "[", postfix = "]") { toJson(it) }
            else -> "\"${escape(value.toString())}\""
        }
    }

    private fun escape(value: String): String {
        val sb = StringBuilder(value.length + 16)
        value.forEach { ch ->
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (ch.code in 0..31) {
                        sb.append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                    } else {
                        sb.append(ch)
                    }
                }
            }
        }
        return sb.toString()
    }

    private val DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val objectMapper = ObjectMapper()
    private data class SchemaPathSegment(val name: String, val isArray: Boolean)
    private val PDF_FONT_CANDIDATES = listOf(
        "/System/Library/Fonts/Supplemental/Arial Unicode.ttf",
        "/System/Library/Fonts/Supplemental/Arial Unicode MS.ttf",
        "/System/Library/Fonts/PingFang.ttc",
        "/System/Library/Fonts/Supplemental/STHeiti Medium.ttc",
        "/Library/Fonts/Arial Unicode.ttf",
        "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
        "/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc",
        "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
        "C:\\Windows\\Fonts\\msyh.ttc",
        "C:\\Windows\\Fonts\\simhei.ttf"
    )
}
