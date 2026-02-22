package com.lhstack.https

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.openapi.project.Project
import java.io.IOException
import java.net.Authenticator
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Duration
import java.util.Locale
import javax.script.ScriptEngineManager

object HttpApiSpecImportService {

    enum class SourceType {
        URL,
        FILE,
        JSON
    }

    data class ImportOptions(
        val sourceType: SourceType,
        val source: String,
        val rootGroupName: String? = null,
        val overwriteExisting: Boolean = true
    )

    data class ImportResult(
        val detectedSpecType: String,
        val sourceType: SourceType,
        val source: String,
        val totalEndpoints: Int,
        val createdGroups: Int,
        val createdRequests: Int,
        val updatedRequests: Int,
        val skippedRequests: Int
    )

    private data class ParsedEndpoint(
        val groupPath: List<String>,
        val name: String,
        val draft: HttpRequestDraft
    )

    private data class ParsedResponseDoc(
        val status: String = "",
        val contentType: String = "",
        val description: String = "",
        val body: String = "",
        val params: List<HttpKeyValue> = emptyList(),
        val statusDocs: List<HttpKeyValue> = emptyList()
    )

    private data class SecuritySchemeDef(
        val name: String,
        val type: String,
        val scheme: String,
        val location: String,
        val parameterName: String,
        val description: String
    )

    private data class SecurityContext(
        val schemes: Map<String, SecuritySchemeDef>,
        val globalRequirements: List<Map<String, Any?>>
    )

    fun importFromUrl(project: Project, url: String, rootGroupName: String?, overwriteExisting: Boolean): ImportResult {
        val normalized = url.trim()
        if (normalized.isBlank()) {
            throw IllegalArgumentException("url is required")
        }
        val request = HttpRequest.newBuilder(URI(normalized))
            .timeout(Duration.ofSeconds(20))
            .header("Accept", "application/json, */*")
            .GET()
            .build()
        val clientBuilder = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
        applyProxySettings(clientBuilder, HttpUiSettingsStore.load(project))
        val response = clientBuilder.build().send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("download failed, status=${response.statusCode()}")
        }
        val json = response.body().orEmpty()
        return importFromJson(
            project = project,
            json = json,
            options = ImportOptions(
                sourceType = SourceType.URL,
                source = normalized,
                rootGroupName = rootGroupName,
                overwriteExisting = overwriteExisting
            )
        )
    }

    private fun applyProxySettings(builder: HttpClient.Builder, settings: HttpUiSettings) {
        if (!settings.proxyEnabled) {
            return
        }
        val host = settings.proxyHost.trim()
        val port = settings.proxyPort
        if (host.isBlank() || port !in 1..65535) {
            return
        }
        val selector = if (isSocks(settings.proxyType)) {
            val proxyAddress = InetSocketAddress.createUnresolved(host, port)
            val proxy = Proxy(Proxy.Type.SOCKS, proxyAddress)
            object : ProxySelector() {
                override fun select(uri: URI?): MutableList<Proxy> {
                    return mutableListOf(proxy)
                }

                override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
                }
            }
        } else {
            ProxySelector.of(InetSocketAddress.createUnresolved(host, port))
        }
        builder.proxy(selector)

        val username = settings.proxyUsername.trim()
        val password = settings.proxyPassword
        if (username.isNotBlank() && password.isNotEmpty()) {
            builder.authenticator(object : Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication? {
                    if (requestorType != RequestorType.PROXY) {
                        return null
                    }
                    return PasswordAuthentication(username, password.toCharArray())
                }
            })
        }
    }

    private fun isSocks(type: String?): Boolean {
        return type?.trim()?.equals("SOCKS", ignoreCase = true) == true
    }

    fun importFromFile(project: Project, filePath: String, rootGroupName: String?, overwriteExisting: Boolean): ImportResult {
        val rawPath = filePath.trim()
        if (rawPath.isBlank()) {
            throw IllegalArgumentException("filePath is required")
        }
        val path = Paths.get(rawPath)
        if (!Files.exists(path) || !Files.isRegularFile(path)) {
            throw IllegalArgumentException("file not found: $rawPath")
        }
        val json = Files.readString(path, StandardCharsets.UTF_8)
        return importFromJson(
            project = project,
            json = json,
            options = ImportOptions(
                sourceType = SourceType.FILE,
                source = path.toAbsolutePath().toString(),
                rootGroupName = rootGroupName,
                overwriteExisting = overwriteExisting
            )
        )
    }

    fun importFromJson(project: Project, json: String, options: ImportOptions): ImportResult {
        val root = parseJsonObject(json)
        val parseResult = parseSpec(project, root)
        return persist(project, parseResult.first, parseResult.second, options)
    }

    private fun persist(
        project: Project,
        specType: String,
        endpoints: List<ParsedEndpoint>,
        options: ImportOptions
    ): ImportResult {
        if (endpoints.isEmpty()) {
            return ImportResult(
                detectedSpecType = specType,
                sourceType = options.sourceType,
                source = options.source,
                totalEndpoints = 0,
                createdGroups = 0,
                createdRequests = 0,
                updatedRequests = 0,
                skippedRequests = 0
            )
        }
        val groups = HttpApiStorage.loadGroups(project)
        val requests = HttpApiStorage.loadRequests(project)
        val initialGroupSize = groups.size
        val groupIndex = LinkedHashMap<String, HttpApiGroup>()
        groups.forEach { group ->
            groupIndex[groupIndexKey(group.parentId, group.name)] = group
        }

        var createdRequests = 0
        var updatedRequests = 0
        var skippedRequests = 0

        val rootSegments = splitGroupPath(options.rootGroupName)
        endpoints.forEach { endpoint ->
            val fullGroupPath = rootSegments + endpoint.groupPath
            val groupId = ensureGroupPath(project, fullGroupPath, groups, groupIndex)?.id
            val existing = requests.firstOrNull { req ->
                req.groupId == groupId &&
                    req.name == endpoint.name &&
                    req.draft.method.equals(endpoint.draft.method, ignoreCase = true) &&
                    compareDraftPath(req.draft, endpoint.draft)
            }
            if (existing != null) {
                if (options.overwriteExisting) {
                    existing.draft = cloneDraft(endpoint.draft)
                    HttpApiStorage.updateRequest(project, existing)
                    updatedRequests++
                } else {
                    skippedRequests++
                }
                return@forEach
            }
            val request = HttpSavedRequest(
                name = endpoint.name,
                groupId = groupId,
                draft = cloneDraft(endpoint.draft),
                sortIndex = nextRequestSortIndex(requests, groupId)
            )
            HttpApiStorage.insertRequest(project, request)
            requests.add(request)
            createdRequests++
        }
        val createdGroups = (groups.size - initialGroupSize).coerceAtLeast(0)

        return ImportResult(
            detectedSpecType = specType,
            sourceType = options.sourceType,
            source = options.source,
            totalEndpoints = endpoints.size,
            createdGroups = createdGroups,
            createdRequests = createdRequests,
            updatedRequests = updatedRequests,
            skippedRequests = skippedRequests
        )
    }

    private fun ensureGroupPath(
        project: Project,
        segments: List<String>,
        groups: MutableList<HttpApiGroup>,
        groupIndex: MutableMap<String, HttpApiGroup>
    ): HttpApiGroup? {
        if (segments.isEmpty()) {
            return null
        }
        var parentId: Long? = null
        var current: HttpApiGroup? = null
        segments.forEach { raw ->
            val name = raw.trim()
            if (name.isBlank()) {
                return@forEach
            }
            val key = groupIndexKey(parentId, name)
            val existed = groupIndex[key]
            if (existed != null) {
                current = existed
                parentId = existed.id
                return@forEach
            }
            val group = HttpApiGroup(
                parentId = parentId,
                name = name,
                sortIndex = nextGroupSortIndex(groups, parentId)
            )
            HttpApiStorage.insertGroup(project, group)
            groups.add(group)
            groupIndex[key] = group
            current = group
            parentId = group.id
        }
        return current
    }

    private fun parseSpec(project: Project, root: Map<String, Any?>): Pair<String, List<ParsedEndpoint>> {
        return when {
            root["openapi"] != null -> "openapi3" to parseOpenApi(project, root)
            root["swagger"] != null -> "swagger2" to parseSwagger(project, root)
            else -> throw IllegalArgumentException("unsupported spec: missing openapi/swagger field")
        }
    }

    private fun parseOpenApi(project: Project, root: Map<String, Any?>): List<ParsedEndpoint> {
        val baseUrl = resolveOpenApiBaseUrl(project, root)
        val securityContext = buildOpenApiSecurityContext(root)
        val paths = root["paths"].asMap()
        val endpoints = mutableListOf<ParsedEndpoint>()
        paths.forEach { (pathKey, rawPathItem) ->
            val pathItem = resolveRefMap(rawPathItem.asMap(), root)
            val pathLevelParams = pathItem["parameters"].asList()
            HTTP_METHODS.forEach { method ->
                val operation = resolveRefMap(pathItem[method].asMap(), root)
                if (operation.isEmpty()) {
                    return@forEach
                }
                val name = operation["summary"].asString()
                    .ifBlank { operation["operationId"].asString() }
                    .ifBlank { "${method.uppercase(Locale.ROOT)} $pathKey" }
                val tags = operation["tags"].asStringList()
                val params = pathLevelParams + operation["parameters"].asList()
                val requestBody = resolveRefMap(operation["requestBody"].asMap(), root)
                val draft = buildDraftFromOpenApi(
                    method = method,
                    path = pathKey,
                    baseUrl = baseUrl,
                    params = params,
                    requestBody = requestBody,
                    root = root
                )
                applyOperationSecurity(draft, operation, securityContext)
                applyOpenApiResponseDocumentation(draft, operation, root)
                endpoints.add(
                    ParsedEndpoint(
                        groupPath = resolveGroupPath(tags, pathKey),
                        name = name,
                        draft = draft
                    )
                )
            }
        }
        return endpoints
    }

    private fun parseSwagger(project: Project, root: Map<String, Any?>): List<ParsedEndpoint> {
        val baseUrl = resolveSwaggerBaseUrl(project, root)
        val securityContext = buildSwaggerSecurityContext(root)
        val paths = root["paths"].asMap()
        val endpoints = mutableListOf<ParsedEndpoint>()
        paths.forEach { (pathKey, rawPathItem) ->
            val pathItem = resolveRefMap(rawPathItem.asMap(), root)
            val pathLevelParams = pathItem["parameters"].asList()
            HTTP_METHODS.forEach { method ->
                val operation = resolveRefMap(pathItem[method].asMap(), root)
                if (operation.isEmpty()) {
                    return@forEach
                }
                val name = operation["summary"].asString()
                    .ifBlank { operation["operationId"].asString() }
                    .ifBlank { "${method.uppercase(Locale.ROOT)} $pathKey" }
                val tags = operation["tags"].asStringList()
                val params = pathLevelParams + operation["parameters"].asList()
                val consumes = operation["consumes"].asStringList()
                    .ifEmpty { pathItem["consumes"].asStringList() }
                    .ifEmpty { root["consumes"].asStringList() }
                val draft = buildDraftFromSwagger(
                    method = method,
                    path = pathKey,
                    baseUrl = baseUrl,
                    params = params,
                    consumes = consumes,
                    root = root
                )
                applyOperationSecurity(draft, operation, securityContext)
                applySwaggerResponseDocumentation(draft, operation, root)
                endpoints.add(
                    ParsedEndpoint(
                        groupPath = resolveGroupPath(tags, pathKey),
                        name = name,
                        draft = draft
                    )
                )
            }
        }
        return endpoints
    }

    private fun buildOpenApiSecurityContext(root: Map<String, Any?>): SecurityContext {
        val schemes = linkedMapOf<String, SecuritySchemeDef>()
        val securitySchemes = root["components"].asMap()["securitySchemes"].asMap()
        securitySchemes.forEach { (name, raw) ->
            val resolved = resolveRefMap(raw.asMap(), root)
            val parsed = parseSecurityScheme(name, resolved)
            if (parsed != null) {
                schemes[name] = parsed
            }
        }
        return SecurityContext(
            schemes = schemes,
            globalRequirements = parseSecurityRequirements(root["security"])
        )
    }

    private fun buildSwaggerSecurityContext(root: Map<String, Any?>): SecurityContext {
        val schemes = linkedMapOf<String, SecuritySchemeDef>()
        val securityDefinitions = root["securityDefinitions"].asMap()
        securityDefinitions.forEach { (name, raw) ->
            val resolved = resolveRefMap(raw.asMap(), root)
            val parsed = parseSecurityScheme(name, resolved)
            if (parsed != null) {
                schemes[name] = parsed
            }
        }
        return SecurityContext(
            schemes = schemes,
            globalRequirements = parseSecurityRequirements(root["security"])
        )
    }

    private fun parseSecurityScheme(name: String, raw: Map<String, Any?>): SecuritySchemeDef? {
        if (raw.isEmpty()) {
            return null
        }
        val type = raw["type"].asString().lowercase(Locale.ROOT)
        val description = raw["description"].asString()
        return when (type) {
            "http" -> {
                val scheme = raw["scheme"].asString().lowercase(Locale.ROOT)
                val normalized = if (scheme.isBlank()) "bearer" else scheme
                SecuritySchemeDef(
                    name = name,
                    type = "http",
                    scheme = normalized,
                    location = "header",
                    parameterName = "Authorization",
                    description = description
                )
            }
            "basic" -> {
                SecuritySchemeDef(
                    name = name,
                    type = "http",
                    scheme = "basic",
                    location = "header",
                    parameterName = "Authorization",
                    description = description
                )
            }
            "apikey" -> {
                val location = raw["in"].asString().lowercase(Locale.ROOT).ifBlank { "header" }
                val parameterName = raw["name"].asString().ifBlank {
                    when (location) {
                        "query" -> "api_key"
                        "cookie" -> "session"
                        else -> "X-API-Key"
                    }
                }
                SecuritySchemeDef(
                    name = name,
                    type = "apikey",
                    scheme = "",
                    location = location,
                    parameterName = parameterName,
                    description = description
                )
            }
            "oauth2", "openidconnect" -> {
                SecuritySchemeDef(
                    name = name,
                    type = type,
                    scheme = "bearer",
                    location = "header",
                    parameterName = "Authorization",
                    description = description
                )
            }
            else -> null
        }
    }

    private fun parseSecurityRequirements(raw: Any?): List<Map<String, Any?>> {
        return raw.asList()
            .map { it.asMap() }
            .filter { it.isNotEmpty() }
    }

    private fun applyOperationSecurity(
        draft: HttpRequestDraft,
        operation: Map<String, Any?>,
        context: SecurityContext
    ) {
        val hasOperationSecurity = operation.containsKey("security")
        val requirements = if (hasOperationSecurity) {
            parseSecurityRequirements(operation["security"])
        } else {
            context.globalRequirements
        }
        if (requirements.isEmpty()) {
            return
        }
        requirements.forEach { requirement ->
            requirement.forEach { (schemeName, rawScopes) ->
                val scheme = context.schemes[schemeName] ?: return@forEach
                applySecuritySchemeToDraft(draft, scheme, rawScopes.asStringList())
            }
        }
    }

    private fun applySecuritySchemeToDraft(
        draft: HttpRequestDraft,
        scheme: SecuritySchemeDef,
        scopes: List<String>
    ) {
        val scopeSuffix = if (scopes.isEmpty()) "" else "; scopes=${scopes.joinToString(",")}"
        val descBase = scheme.description.ifBlank { "认证方案 ${scheme.name}" }
        val desc = "$descBase$scopeSuffix"
        when (scheme.type) {
            "http" -> {
                if (scheme.scheme.equals("basic", ignoreCase = true)) {
                    upsertHeader(draft, "Authorization", "Basic {{base64(username:password)}}", desc)
                } else {
                    upsertHeader(draft, "Authorization", "Bearer {{token}}", desc)
                }
            }
            "oauth2", "openidconnect" -> {
                upsertHeader(draft, "Authorization", "Bearer {{token}}", desc)
            }
            "apikey" -> {
                when (scheme.location.lowercase(Locale.ROOT)) {
                    "query" -> upsertQueryParam(draft, scheme.parameterName, "{{apiKey}}", desc)
                    "cookie" -> upsertCookieHeader(draft, scheme.parameterName, "{{apiKey}}", desc)
                    else -> upsertHeader(draft, scheme.parameterName, "{{apiKey}}", desc)
                }
            }
        }
    }

    private fun upsertHeader(draft: HttpRequestDraft, key: String, value: String, description: String) {
        val existed = draft.headers.firstOrNull { it.key.equals(key, ignoreCase = true) }
        if (existed == null) {
            draft.headers.add(HttpKeyValue(key = key, value = value, description = description))
            return
        }
        if (existed.value.isBlank()) {
            existed.value = value
        }
        if (existed.description.isBlank()) {
            existed.description = description
        }
    }

    private fun upsertQueryParam(draft: HttpRequestDraft, key: String, value: String, description: String) {
        val existed = draft.params.firstOrNull { it.key == key }
        if (existed == null) {
            draft.params.add(HttpKeyValue(key = key, value = value, description = description))
            return
        }
        if (existed.value.isBlank()) {
            existed.value = value
        }
        if (existed.description.isBlank()) {
            existed.description = description
        }
    }

    private fun upsertCookieHeader(draft: HttpRequestDraft, cookieName: String, cookieValue: String, description: String) {
        val existed = draft.headers.firstOrNull { it.key.equals("Cookie", ignoreCase = true) }
        val cookiePair = "$cookieName=$cookieValue"
        if (existed == null) {
            draft.headers.add(HttpKeyValue(key = "Cookie", value = cookiePair, description = description))
            return
        }
        val names = existed.value.split(";")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { part -> part.substringBefore("=").trim() }
            .toSet()
        if (!names.contains(cookieName)) {
            existed.value = existed.value.trim().trimEnd(';')
                .let { if (it.isBlank()) cookiePair else "$it; $cookiePair" }
        }
        if (existed.description.isBlank()) {
            existed.description = description
        }
    }

    private fun buildDraftFromOpenApi(
        method: String,
        path: String,
        baseUrl: String,
        params: List<Any?>,
        requestBody: Map<String, Any?>,
        root: Map<String, Any?>
    ): HttpRequestDraft {
        val normalizedPath = normalizePath(path)
        val draft = HttpRequestDraft(
            method = method.uppercase(Locale.ROOT),
            url = baseUrl + normalizedPath,
            path = normalizedPath,
            timeoutSeconds = 10
        )
        params.forEach { raw ->
            val param = resolveRefMap(raw.asMap(), root)
            val name = param["name"].asString()
            if (name.isBlank()) {
                return@forEach
            }
            val value = resolveParamExample(param, root)
            val description = resolveParamDescription(param, root)
            when (param["in"].asString().lowercase(Locale.ROOT)) {
                "path" -> draft.pathParams.add(HttpKeyValue(name, value, description))
                "query" -> draft.params.add(HttpKeyValue(name, value, description))
                "header" -> draft.headers.add(HttpKeyValue(name, value, description))
            }
        }
        val content = requestBody["content"].asMap()
        if (content.isNotEmpty()) {
            val jsonMedia = pickMedia(content) { key ->
                key.equals("application/json", ignoreCase = true) ||
                    key.contains("+json", ignoreCase = true) ||
                    key.contains("/json", ignoreCase = true)
            }
            if (jsonMedia != null) {
                draft.bodyType = HttpBodyType.JSON.name
                draft.body = normalizeBodyText(resolveMediaExample(jsonMedia, root))
                val schema = resolveRefMap(jsonMedia["schema"].asMap(), root)
                draft.requestBodyParams = resolveRequestBodyParams(schema, root).toMutableList()
                return draft
            }

            val formUrlEncodedMedia = pickMedia(content) { key ->
                key.contains("application/x-www-form-urlencoded", ignoreCase = true)
            }
            if (formUrlEncodedMedia != null) {
                draft.bodyType = HttpBodyType.FORM_URLENCODED.name
                val schema = resolveRefMap(formUrlEncodedMedia["schema"].asMap(), root)
                val properties = resolveSchemaProperties(schema, root)
                properties.forEach { (key, property) ->
                    val example = resolveSchemaExampleAsString(property, root)
                    val description = resolveSchemaDescription(property, root)
                    draft.urlEncoded.add(HttpKeyValue(key, example, description))
                }
                return draft
            }

            val multipartMedia = pickMedia(content) { key ->
                key.contains("multipart/form-data", ignoreCase = true)
            }
            if (multipartMedia != null) {
                draft.bodyType = HttpBodyType.FORM_DATA.name
                val schema = resolveRefMap(multipartMedia["schema"].asMap(), root)
                val properties = resolveSchemaProperties(schema, root)
                properties.forEach { (key, property) ->
                    val fieldType = if (isBinarySchema(property)) {
                        HttpFormFieldType.FILE.name
                    } else {
                        HttpFormFieldType.TEXT.name
                    }
                    val value = resolveSchemaExampleAsString(property, root)
                    draft.formFields.add(HttpFormField(key = key, value = value, fieldType = fieldType))
                }
                return draft
            }
        }
        return draft
    }

    private fun buildDraftFromSwagger(
        method: String,
        path: String,
        baseUrl: String,
        params: List<Any?>,
        consumes: List<String>,
        root: Map<String, Any?>
    ): HttpRequestDraft {
        val normalizedPath = normalizePath(path)
        val draft = HttpRequestDraft(
            method = method.uppercase(Locale.ROOT),
            url = baseUrl + normalizedPath,
            path = normalizedPath,
            timeoutSeconds = 10
        )
        val formDataParams = mutableListOf<Map<String, Any?>>()
        params.forEach { raw ->
            val param = resolveRefMap(raw.asMap(), root)
            val name = param["name"].asString()
            if (name.isBlank()) {
                return@forEach
            }
            val location = param["in"].asString().lowercase(Locale.ROOT)
            val value = resolveParamExample(param, root)
            val description = resolveParamDescription(param, root)
            when (location) {
                "path" -> draft.pathParams.add(HttpKeyValue(name, value, description))
                "query" -> draft.params.add(HttpKeyValue(name, value, description))
                "header" -> draft.headers.add(HttpKeyValue(name, value, description))
                "body" -> {
                    draft.bodyType = HttpBodyType.JSON.name
                    val schema = resolveRefMap(param["schema"].asMap(), root)
                    val example = resolveParamExample(param, root).ifBlank {
                        stringifyJsonExample(resolveSchemaExample(schema, root))
                    }
                    draft.body = normalizeBodyText(example)
                    draft.requestBodyParams = resolveRequestBodyParams(schema, root).toMutableList()
                }
                "formdata" -> formDataParams.add(param)
            }
        }
        if (formDataParams.isNotEmpty()) {
            val isMultipart = consumes.any { it.contains("multipart/form-data") } ||
                formDataParams.any { it["type"].asString().equals("file", ignoreCase = true) }
            if (isMultipart) {
                draft.bodyType = HttpBodyType.FORM_DATA.name
                formDataParams.forEach { param ->
                    val fieldType = if (param["type"].asString().equals("file", ignoreCase = true)) {
                        HttpFormFieldType.FILE.name
                    } else {
                        HttpFormFieldType.TEXT.name
                    }
                    draft.formFields.add(
                        HttpFormField(
                            key = param["name"].asString(),
                            value = resolveParamExample(param, root),
                            fieldType = fieldType
                        )
                    )
                }
            } else {
                draft.bodyType = HttpBodyType.FORM_URLENCODED.name
                formDataParams.forEach { param ->
                    draft.urlEncoded.add(
                        HttpKeyValue(
                            param["name"].asString(),
                            resolveParamExample(param, root),
                            resolveParamDescription(param, root)
                        )
                    )
                }
            }
        }
        return draft
    }

    private fun applyOpenApiResponseDocumentation(
        draft: HttpRequestDraft,
        operation: Map<String, Any?>,
        root: Map<String, Any?>
    ) {
        val responses = operation["responses"].asMap()
        val statusDocs = collectResponseStatusDocs(responses, root)
        val selected = pickPreferredResponse(responses, root) ?: return
        val status = selected.first
        val response = selected.second
        val content = response["content"].asMap()
        val description = response["description"].asString()
        if (content.isEmpty()) {
            applyResponseDoc(
                draft = draft,
                doc = ParsedResponseDoc(
                    status = status,
                    description = description,
                    statusDocs = statusDocs
                )
            )
            return
        }
        val media = pickPreferredMedia(content)
        if (media == null) {
            applyResponseDoc(
                draft = draft,
                doc = ParsedResponseDoc(
                    status = status,
                    description = description,
                    statusDocs = statusDocs
                )
            )
            return
        }
        val mediaType = media.first
        val mediaValue = resolveRefMap(media.second.asMap(), root)
        val schema = mediaValue["schema"].asMap()
        val body = normalizeBodyText(resolveMediaExample(mediaValue, root))
        val params = resolveResponseParams(schema, root)
        applyResponseDoc(
            draft = draft,
            doc = ParsedResponseDoc(
                status = status,
                contentType = mediaType,
                description = description,
                body = body,
                params = params,
                statusDocs = statusDocs
            )
        )
    }

    private fun applySwaggerResponseDocumentation(
        draft: HttpRequestDraft,
        operation: Map<String, Any?>,
        root: Map<String, Any?>
    ) {
        val responses = operation["responses"].asMap()
        val statusDocs = collectResponseStatusDocs(responses, root)
        val selected = pickPreferredResponse(responses, root) ?: return
        val status = selected.first
        val response = selected.second
        val description = response["description"].asString()
        val schema = resolveRefMap(response["schema"].asMap(), root)
        val examples = response["examples"].asMap()
        val exampleEntry = pickPreferredMedia(examples)
        val mediaType = exampleEntry?.first.orEmpty()
        val body = when {
            exampleEntry != null -> normalizeBodyText(stringifyJsonExample(exampleEntry.second))
            schema.isNotEmpty() -> normalizeBodyText(stringifyJsonExample(resolveSchemaExample(schema, root)))
            else -> ""
        }
        val params = resolveResponseParams(schema, root)
        applyResponseDoc(
            draft = draft,
            doc = ParsedResponseDoc(
                status = status,
                contentType = mediaType,
                description = description,
                body = body,
                params = params,
                statusDocs = statusDocs
            )
        )
    }

    private fun applyResponseDoc(draft: HttpRequestDraft, doc: ParsedResponseDoc) {
        draft.responseStatus = doc.status
        draft.responseContentType = doc.contentType
        draft.responseDescription = doc.description.ifBlank { null }
        draft.responseBody = doc.body.ifBlank { null }
        draft.responseStatusDocs = doc.statusDocs.map { it.copy() }.toMutableList()
        draft.responseParams = doc.params.map { it.copy() }.toMutableList()
    }

    private fun pickPreferredResponse(
        responses: Map<String, Any?>,
        root: Map<String, Any?>
    ): Pair<String, Map<String, Any?>>? {
        if (responses.isEmpty()) {
            return null
        }
        return responses.entries
            .mapNotNull { entry ->
                val status = entry.key.trim()
                if (status.isBlank()) {
                    null
                } else {
                    status to resolveRefMap(entry.value.asMap(), root)
                }
            }
            .sortedWith(compareBy<Pair<String, Map<String, Any?>>> { responseOrder(it.first) }.thenBy { it.first })
            .firstOrNull()
    }

    private fun collectResponseStatusDocs(
        responses: Map<String, Any?>,
        root: Map<String, Any?>
    ): List<HttpKeyValue> {
        if (responses.isEmpty()) {
            return emptyList()
        }
        return responses.entries
            .mapNotNull { entry ->
                val status = entry.key.trim()
                if (status.isBlank()) {
                    null
                } else {
                    val resolved = resolveRefMap(entry.value.asMap(), root)
                    HttpKeyValue(
                        key = status,
                        value = "",
                        description = resolved["description"].asString()
                    )
                }
            }
            .sortedWith(compareBy<HttpKeyValue> { responseOrder(it.key) }.thenBy { it.key })
    }

    private fun responseOrder(status: String): Int {
        val normalized = status.trim().lowercase(Locale.ROOT)
        if (normalized == "200") {
            return 0
        }
        val code = normalized.toIntOrNull()
        if (code != null && code in 200..299) {
            return 100 + code
        }
        if (normalized == "default") {
            return 1000
        }
        if (code != null) {
            return 2000 + code
        }
        return 3000
    }

    private fun pickPreferredMedia(content: Map<String, Any?>): Pair<String, Any?>? {
        if (content.isEmpty()) {
            return null
        }
        val entries = content.entries.toList()
        return entries.firstOrNull { (key, _) ->
            key.equals("application/json", ignoreCase = true) ||
                key.contains("+json", ignoreCase = true) ||
                key.contains("/json", ignoreCase = true)
        }?.let { it.key to it.value } ?: entries.first().let { it.key to it.value }
    }

    private fun resolveResponseParams(schema: Map<String, Any?>, root: Map<String, Any?>): List<HttpKeyValue> {
        return resolveSchemaFieldDocs(schema, root, "items")
    }

    private fun resolveRequestBodyParams(schema: Map<String, Any?>, root: Map<String, Any?>): List<HttpKeyValue> {
        return resolveSchemaFieldDocs(schema, root, "items")
    }

    private fun resolveSchemaFieldDocs(
        schema: Map<String, Any?>,
        root: Map<String, Any?>,
        topArrayKey: String
    ): List<HttpKeyValue> {
        if (schema.isEmpty()) {
            return emptyList()
        }
        val docs = LinkedHashMap<String, HttpKeyValue>()
        collectSchemaFieldDocs(
            schema = schema,
            root = root,
            path = "",
            topArrayKey = topArrayKey.ifBlank { "items" },
            docs = docs,
            visitedRefs = linkedSetOf(),
            depth = 0
        )
        return docs.values.toList()
    }

    private fun collectSchemaFieldDocs(
        schema: Map<String, Any?>,
        root: Map<String, Any?>,
        path: String,
        topArrayKey: String,
        docs: LinkedHashMap<String, HttpKeyValue>,
        visitedRefs: MutableSet<String>,
        depth: Int
    ) {
        if (schema.isEmpty() || depth >= MAX_SCHEMA_EXAMPLE_DEPTH) {
            return
        }
        val ref = schema["\$ref"].asString()
        if (ref.isNotBlank() && !visitedRefs.add(ref)) {
            return
        }
        val resolved = resolveRefMap(schema, root, visitedRefs.toMutableSet())
        try {
            resolved["allOf"].asList().forEach { child ->
                collectSchemaFieldDocs(
                    schema = child.asMap(),
                    root = root,
                    path = path,
                    topArrayKey = topArrayKey,
                    docs = docs,
                    visitedRefs = visitedRefs.toMutableSet(),
                    depth = depth + 1
                )
            }
            resolved["oneOf"].asList().firstOrNull()?.asMap()?.let { child ->
                collectSchemaFieldDocs(
                    schema = child,
                    root = root,
                    path = path,
                    topArrayKey = topArrayKey,
                    docs = docs,
                    visitedRefs = visitedRefs.toMutableSet(),
                    depth = depth + 1
                )
            }
            resolved["anyOf"].asList().firstOrNull()?.asMap()?.let { child ->
                collectSchemaFieldDocs(
                    schema = child,
                    root = root,
                    path = path,
                    topArrayKey = topArrayKey,
                    docs = docs,
                    visitedRefs = visitedRefs.toMutableSet(),
                    depth = depth + 1
                )
            }

            val properties = resolved["properties"].asMap()
            if (properties.isNotEmpty()) {
                properties.forEach { (name, rawProperty) ->
                    val property = resolveRefMap(rawProperty.asMap(), root, visitedRefs.toMutableSet())
                    val key = if (path.isBlank()) name else "$path.$name"
                    val propertyPath = normalizeSchemaPropertyPath(key, property)
                    upsertSchemaDocRow(docs, propertyPath, property, root)
                    collectSchemaFieldDocs(
                        schema = property,
                        root = root,
                        path = propertyPath,
                        topArrayKey = topArrayKey,
                        docs = docs,
                        visitedRefs = visitedRefs.toMutableSet(),
                        depth = depth + 1
                    )
                }
                return
            }

            val additional = resolved["additionalProperties"]
            if (additional is Map<*, *>) {
                val additionalSchema = resolveRefMap(additional.asMap(), root, visitedRefs.toMutableSet())
                val entryPath = if (path.isBlank()) "$topArrayKey.{key}" else "$path.{key}"
                upsertSchemaDocRow(docs, entryPath, additionalSchema, root)
                collectSchemaFieldDocs(
                    schema = additionalSchema,
                    root = root,
                    path = entryPath,
                    topArrayKey = topArrayKey,
                    docs = docs,
                    visitedRefs = visitedRefs.toMutableSet(),
                    depth = depth + 1
                )
                return
            }

            val type = resolved["type"].asString().lowercase(Locale.ROOT)
            if (type == "array" || resolved["items"] != null) {
                val itemsSchema = resolveRefMap(resolved["items"].asMap(), root, visitedRefs.toMutableSet())
                val arrayPath = when {
                    path.isBlank() -> "$topArrayKey[]"
                    path.endsWith("[]") -> "$path.items[]"
                    else -> "$path[]"
                }
                val itemProperties = resolveSchemaProperties(itemsSchema, root)
                if (itemProperties.isNotEmpty()) {
                    collectSchemaFieldDocs(
                        schema = itemsSchema,
                        root = root,
                        path = arrayPath,
                        topArrayKey = topArrayKey,
                        docs = docs,
                        visitedRefs = visitedRefs.toMutableSet(),
                        depth = depth + 1
                    )
                } else if (arrayPath.isNotBlank()) {
                    upsertSchemaDocRow(docs, arrayPath, resolved, root)
                }
                return
            }

            val fallbackExample = resolveSchemaExample(resolved, root, visitedRefs.toMutableSet(), depth + 1)
            collectSchemaFieldDocsFromExample(
                example = fallbackExample,
                path = path,
                topArrayKey = topArrayKey,
                docs = docs,
                depth = depth + 1
            )
            if (type == "object" && path.isNotBlank()) {
                docs.putIfAbsent(path, HttpKeyValue(key = path))
            }
        } finally {
            if (ref.isNotBlank()) {
                visitedRefs.remove(ref)
            }
        }
    }

    private fun collectSchemaFieldDocsFromExample(
        example: Any?,
        path: String,
        topArrayKey: String,
        docs: LinkedHashMap<String, HttpKeyValue>,
        depth: Int
    ) {
        if (depth >= MAX_SCHEMA_EXAMPLE_DEPTH || example == null) {
            return
        }
        when (example) {
            is Map<*, *> -> {
                if (path.isNotBlank()) {
                    docs.putIfAbsent(path, HttpKeyValue(key = path))
                }
                example.forEach { (rawKey, rawValue) ->
                    val key = rawKey?.toString()?.trim().orEmpty()
                    if (key.isBlank() || key == "\$ref") {
                        return@forEach
                    }
                    val childPath = if (path.isBlank()) key else "$path.$key"
                    when (rawValue) {
                        is Map<*, *>, is List<*> -> {
                            collectSchemaFieldDocsFromExample(rawValue, childPath, topArrayKey, docs, depth + 1)
                        }
                        else -> {
                            mergeSchemaExampleRow(docs, childPath, rawValue)
                        }
                    }
                }
            }
            is List<*> -> {
                val arrayPath = when {
                    path.isBlank() -> "$topArrayKey[]"
                    path.endsWith("[]") -> "$path.items[]"
                    else -> "$path[]"
                }
                if (example.isEmpty()) {
                    docs.putIfAbsent(arrayPath, HttpKeyValue(key = arrayPath))
                    return
                }
                val first = example.firstOrNull()
                when (first) {
                    is Map<*, *>, is List<*> -> {
                        docs.putIfAbsent(arrayPath, HttpKeyValue(key = arrayPath))
                        collectSchemaFieldDocsFromExample(first, arrayPath, topArrayKey, docs, depth + 1)
                    }
                    else -> mergeSchemaExampleRow(docs, arrayPath, first)
                }
            }
            else -> {
                val key = if (path.isBlank()) topArrayKey else path
                mergeSchemaExampleRow(docs, key, example)
            }
        }
    }

    private fun mergeSchemaExampleRow(
        docs: LinkedHashMap<String, HttpKeyValue>,
        key: String,
        example: Any?
    ) {
        if (key.isBlank()) {
            return
        }
        val value = stringifyJsonExample(example)
        val existing = docs[key]
        if (existing == null) {
            docs[key] = HttpKeyValue(key = key, value = value)
        } else if (existing.value.isBlank() && value.isNotBlank()) {
            existing.value = value
        }
    }

    private fun normalizeSchemaPropertyPath(path: String, schema: Map<String, Any?>): String {
        if (path.isBlank()) {
            return path
        }
        val type = schema["type"].asString().lowercase(Locale.ROOT)
        val isArray = type == "array" || schema["items"] != null
        return if (isArray && !path.endsWith("[]")) "$path[]" else path
    }

    private fun upsertSchemaDocRow(
        docs: LinkedHashMap<String, HttpKeyValue>,
        key: String,
        schema: Map<String, Any?>,
        root: Map<String, Any?>
    ) {
        if (key.isBlank()) {
            return
        }
        val existing = docs[key]
        val example = resolveSchemaExampleAsString(schema, root)
        val description = resolveSchemaDescription(schema, root)
        if (existing == null) {
            docs[key] = HttpKeyValue(key = key, value = example, description = description)
            return
        }
        if (existing.value.isBlank() && example.isNotBlank()) {
            existing.value = example
        }
        if (existing.description.isBlank() && description.isNotBlank()) {
            existing.description = description
        }
    }

    private fun normalizeBodyText(value: String): String {
        val text = value.trim()
        if (text.isBlank()) {
            return ""
        }
        return runCatching {
            objectMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(objectMapper.readTree(text))
        }.getOrDefault(text)
    }

    private fun resolveOpenApiBaseUrl(project: Project, root: Map<String, Any?>): String {
        val fromServers = root["servers"].asList()
            .mapNotNull { it.asMap()["url"].asString().takeIf { value -> value.isNotBlank() } }
            .firstOrNull()
        if (!fromServers.isNullOrBlank()) {
            return normalizeBaseUrl(fromServers)
        }
        val port = HttpPluginContext.getPort(project) ?: 8080
        return "http://localhost:$port"
    }

    private fun resolveSwaggerBaseUrl(project: Project, root: Map<String, Any?>): String {
        val host = root["host"].asString()
        val basePath = normalizePath(root["basePath"].asString().ifBlank { "/" })
        if (host.isNotBlank()) {
            val schemes = root["schemes"].asStringList()
            val scheme = schemes.firstOrNull { it.isNotBlank() } ?: "http"
            return normalizeBaseUrl("$scheme://$host$basePath")
        }
        val port = HttpPluginContext.getPort(project) ?: 8080
        return "http://localhost:$port"
    }

    private fun normalizeBaseUrl(url: String): String {
        var value = url.trim().ifBlank { return "" }
        if (value.endsWith("/")) {
            value = value.dropLast(1)
        }
        return value
    }

    private fun normalizePath(path: String): String {
        val raw = path.trim().ifBlank { "/" }
        return if (raw.startsWith("/")) raw else "/$raw"
    }

    private fun resolveGroupPath(tags: List<String>, path: String): List<String> {
        if (tags.isNotEmpty()) {
            val primary = tags.first().trim()
            if (primary.isNotBlank()) {
                return splitGroupPath(primary)
            }
        }
        val firstSegment = normalizePath(path).trim('/').split('/').firstOrNull()?.trim().orEmpty()
        if (firstSegment.isBlank()) {
            return listOf("默认分组")
        }
        return listOf(firstSegment)
    }

    private fun splitGroupPath(value: String?): List<String> {
        if (value.isNullOrBlank()) {
            return emptyList()
        }
        return value.split("/", "\\", ">", "|")
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    private fun resolveParamExample(param: Map<String, Any?>, root: Map<String, Any?>): String {
        val direct = firstNonNullValue(
            param["x-example"],
            param["example"],
            param["default"],
            extractExamplesValue(param["examples"], root)
        )
        if (direct != null) {
            return direct.asJsonString()
        }
        val schema = resolveRefMap(param["schema"].asMap(), root)
        val generated = resolveSchemaExample(schema, root)
        return generated.asJsonString()
    }

    private fun resolveParamDescription(param: Map<String, Any?>, root: Map<String, Any?>): String {
        val direct = param["description"].asString()
        if (direct.isNotBlank()) {
            return direct
        }
        val schema = resolveRefMap(param["schema"].asMap(), root)
        return schema["description"].asString()
    }

    private fun resolveMediaExample(media: Map<String, Any?>, root: Map<String, Any?>): String {
        val direct = firstNonNullValue(
            media["example"],
            extractExamplesValue(media["examples"], root)
        )
        if (direct != null) {
            return stringifyJsonExample(direct)
        }
        val schema = resolveRefMap(media["schema"].asMap(), root)
        return stringifyJsonExample(resolveSchemaExample(schema, root))
    }

    private fun resolveSchemaExampleAsString(schema: Map<String, Any?>, root: Map<String, Any?>): String {
        return resolveSchemaExample(schema, root).asJsonString()
    }

    private fun resolveSchemaDescription(schema: Map<String, Any?>, root: Map<String, Any?>): String {
        val resolved = resolveRefMap(schema, root)
        return resolved["description"].asString()
    }

    private fun resolveSchemaProperties(schema: Map<String, Any?>, root: Map<String, Any?>): Map<String, Map<String, Any?>> {
        return resolveSchemaProperties(schema, root, linkedSetOf(), 0)
    }

    private fun resolveSchemaProperties(
        schema: Map<String, Any?>,
        root: Map<String, Any?>,
        visitedRefs: MutableSet<String>,
        depth: Int
    ): Map<String, Map<String, Any?>> {
        if (schema.isEmpty()) {
            return emptyMap()
        }
        if (depth >= MAX_SCHEMA_EXAMPLE_DEPTH) {
            return emptyMap()
        }
        val ref = schema["\$ref"].asString()
        if (ref.isNotBlank() && !visitedRefs.add(ref)) {
            return emptyMap()
        }
        val resolved = resolveRefMap(schema, root, visitedRefs.toMutableSet())
        val result = LinkedHashMap<String, Map<String, Any?>>()
        try {
            resolved["allOf"].asList().forEach { child ->
                result.putAll(resolveSchemaProperties(child.asMap(), root, visitedRefs.toMutableSet(), depth + 1))
            }
            val properties = resolved["properties"].asMap()
            properties.forEach { (key, value) ->
                result[key] = resolveRefMap(value.asMap(), root, visitedRefs.toMutableSet())
            }
            if (result.isNotEmpty()) {
                return result
            }
            val oneOf = resolved["oneOf"].asList().firstOrNull()?.asMap()
            if (oneOf != null) {
                return resolveSchemaProperties(oneOf, root, visitedRefs.toMutableSet(), depth + 1)
            }
            val anyOf = resolved["anyOf"].asList().firstOrNull()?.asMap()
            if (anyOf != null) {
                return resolveSchemaProperties(anyOf, root, visitedRefs.toMutableSet(), depth + 1)
            }
            return emptyMap()
        } finally {
            if (ref.isNotBlank()) {
                visitedRefs.remove(ref)
            }
        }
    }

    private fun resolveSchemaExample(
        schema: Map<String, Any?>,
        root: Map<String, Any?>,
        visited: MutableSet<String> = linkedSetOf(),
        depth: Int = 0
    ): Any? {
        if (schema.isEmpty()) {
            return null
        }
        if (depth >= MAX_SCHEMA_EXAMPLE_DEPTH) {
            return defaultValueByType(schema["type"].asString().lowercase(Locale.ROOT), schema["format"].asString())
        }
        val ref = schema["\$ref"].asString()
        if (ref.isNotBlank() && !visited.add(ref)) {
            return circularRefPlaceholder(schema, ref)
        }
        val resolved = resolveRefMap(schema, root, visited.toMutableSet())
        try {
            firstNonNullValue(
                resolved["x-example"],
                resolved["example"],
                resolved["default"],
                extractExamplesValue(resolved["examples"], root)
            )?.let { return it }

            val enumValues = resolved["enum"].asList()
            if (enumValues.isNotEmpty()) {
                return enumValues.first()
            }

            val allOf = resolved["allOf"].asList()
            if (allOf.isNotEmpty()) {
                val merged = LinkedHashMap<String, Any?>()
                allOf.forEach { child ->
                    val childExample = resolveSchemaExample(child.asMap(), root, visited.toMutableSet(), depth + 1)
                    if (childExample is Map<*, *>) {
                        childExample.forEach { (key, value) ->
                            if (key != null) {
                                merged[key.toString()] = value
                            }
                        }
                    }
                }
                if (merged.isNotEmpty()) {
                    return merged
                }
            }

            val oneOf = resolved["oneOf"].asList()
            if (oneOf.isNotEmpty()) {
                return resolveSchemaExample(oneOf.first().asMap(), root, visited.toMutableSet(), depth + 1)
            }
            val anyOf = resolved["anyOf"].asList()
            if (anyOf.isNotEmpty()) {
                return resolveSchemaExample(anyOf.first().asMap(), root, visited.toMutableSet(), depth + 1)
            }

            val type = resolved["type"].asString().lowercase(Locale.ROOT)
            val properties = resolved["properties"].asMap()
            if (type == "object" || properties.isNotEmpty() || resolved["additionalProperties"] != null) {
                val obj = LinkedHashMap<String, Any?>()
                resolveSchemaProperties(resolved, root).forEach { (key, propertySchema) ->
                    obj[key] = resolveSchemaExample(propertySchema, root, visited.toMutableSet(), depth + 1)
                }
                if (obj.isNotEmpty()) {
                    return obj
                }
                val additional = resolved["additionalProperties"]
                if (additional is Map<*, *>) {
                    val value = resolveSchemaExample(additional.asMap(), root, visited.toMutableSet(), depth + 1)
                    return linkedMapOf("key" to value)
                }
                return emptyMap<String, Any?>()
            }

            if (type == "array" || resolved["items"] != null) {
                val itemSchema = resolved["items"].asMap()
                val itemExample = resolveSchemaExample(itemSchema, root, visited.toMutableSet(), depth + 1)
                return if (itemExample == null) emptyList<Any?>() else listOf(itemExample)
            }

            return defaultValueByType(type, resolved["format"].asString())
        } finally {
            if (ref.isNotBlank()) {
                visited.remove(ref)
            }
        }
    }

    private fun circularRefPlaceholder(schema: Map<String, Any?>, ref: String): Any? {
        val type = schema["type"].asString().lowercase(Locale.ROOT)
        return when {
            type == "array" || schema["items"] != null -> emptyList<Any?>()
            type == "object" || schema["properties"] != null -> linkedMapOf("\$ref" to ref)
            else -> ""
        }
    }

    private fun defaultValueByType(type: String, format: String): Any? {
        return when (type) {
            "integer" -> 0
            "number" -> 0
            "boolean" -> false
            "string" -> when (format.lowercase(Locale.ROOT)) {
                "date-time" -> "2024-01-01T00:00:00Z"
                "date" -> "2024-01-01"
                "uuid" -> "00000000-0000-0000-0000-000000000000"
                else -> ""
            }
            "object" -> emptyMap<String, Any?>()
            "array" -> emptyList<Any?>()
            else -> ""
        }
    }

    private fun pickMedia(
        content: Map<String, Any?>,
        predicate: (String) -> Boolean
    ): Map<String, Any?>? {
        return content.entries.firstOrNull { predicate(it.key) }?.value?.asMap()
    }

    private fun isBinarySchema(schema: Map<String, Any?>): Boolean {
        val resolved = schema
        val type = resolved["type"].asString()
        val format = resolved["format"].asString()
        return type.equals("string", ignoreCase = true) &&
            (format.equals("binary", ignoreCase = true) || format.equals("byte", ignoreCase = true))
    }

    private fun resolveRefMap(
        source: Map<String, Any?>,
        root: Map<String, Any?>,
        visited: MutableSet<String> = mutableSetOf()
    ): Map<String, Any?> {
        if (source.isEmpty()) {
            return source
        }
        val ref = source["\$ref"].asString()
        if (ref.isBlank()) {
            return source
        }
        if (!visited.add(ref)) {
            return source.filterKeys { it != "\$ref" }
        }
        val resolvedTarget = resolveRefValue(root, ref).asMap()
        if (resolvedTarget.isEmpty()) {
            return source.filterKeys { it != "\$ref" }
        }
        val base = resolveRefMap(resolvedTarget, root, visited)
        val merged = LinkedHashMap(base)
        source.forEach { (key, value) ->
            if (key != "\$ref") {
                merged[key] = value
            }
        }
        return merged
    }

    private fun resolveRefValue(root: Map<String, Any?>, ref: String): Any? {
        if (!ref.startsWith("#/")) {
            return null
        }
        var current: Any? = root
        ref.removePrefix("#/").split("/")
            .map { token -> token.replace("~1", "/").replace("~0", "~") }
            .forEach { token ->
                current = (current as? Map<*, *>)?.get(token)
                if (current == null) {
                    return null
                }
            }
        return current
    }

    private fun extractExamplesValue(examplesRaw: Any?, root: Map<String, Any?>): Any? {
        val examples = examplesRaw.asMap()
        if (examples.isEmpty()) {
            return null
        }
        examples.values.forEach { raw ->
            val source = raw.asMap()
            if (source.isEmpty()) {
                if (raw != null) {
                    return raw
                }
                return@forEach
            }
            val item = resolveRefMap(source, root)
            if (item["value"] != null) {
                return item["value"]
            }
            if (item["example"] != null) {
                return item["example"]
            }
        }
        return null
    }

    private fun stringifyJsonExample(value: Any?): String {
        return when (value) {
            null -> ""
            is String -> {
                val text = value.trim()
                if (text.isBlank()) {
                    ""
                } else if (
                    text.startsWith("{") ||
                    text.startsWith("[") ||
                    text.startsWith("\"") ||
                    text == "true" ||
                    text == "false" ||
                    text == "null" ||
                    text.toDoubleOrNull() != null
                ) {
                    text
                } else {
                    "\"${escape(text)}\""
                }
            }
            else -> toJson(value)
        }
    }

    private fun firstNonNullValue(vararg values: Any?): Any? {
        values.forEach { value ->
            if (value == null) {
                return@forEach
            }
            if (value is String && value.isBlank()) {
                return@forEach
            }
            return value
        }
        return null
    }

    private fun parseJsonObject(text: String): Map<String, Any?> {
        val raw = text.trim()
        if (raw.isBlank()) {
            throw IllegalArgumentException("json is empty")
        }
        parseJsonObjectWithJackson(raw)?.let { return it }
        return parseJsonObjectWithScriptEngine(raw)
    }

    private fun parseJsonObjectWithJackson(raw: String): Map<String, Any?>? {
        return runCatching {
            val typeRef = object : TypeReference<LinkedHashMap<String, Any?>>() {}
            objectMapper.readValue(raw, typeRef) as Map<String, Any?>
        }.getOrNull()?.ifEmpty { throw IllegalArgumentException("json root must be object") }
    }

    private fun parseJsonObjectWithScriptEngine(raw: String): Map<String, Any?> {
        val manager = ScriptEngineManager(HttpApiSpecImportService::class.java.classLoader)
        val engine = manager.getEngineByName("JavaScript")
            ?: manager.getEngineByName("javascript")
            ?: manager.getEngineByName("js")
            ?: manager.getEngineByName("nashorn")
            ?: throw IllegalStateException("JavaScript engine is unavailable for JSON parsing")
        val bindings = engine.createBindings()
        bindings["__raw"] = raw
        val value = engine.eval("Java.asJSONCompatible(JSON.parse(__raw))", bindings)
        return value.asMap().ifEmpty { throw IllegalArgumentException("json root must be object") }
    }

    private fun groupIndexKey(parentId: Long?, name: String): String {
        val p = parentId?.toString() ?: "root"
        return "$p|${name.trim().lowercase(Locale.ROOT)}"
    }

    private fun nextGroupSortIndex(groups: List<HttpApiGroup>, parentId: Long?): Int {
        return groups.filter { it.parentId == parentId }.maxOfOrNull { it.sortIndex }?.plus(1) ?: 0
    }

    private fun nextRequestSortIndex(requests: List<HttpSavedRequest>, groupId: Long?): Int {
        return requests.filter { it.groupId == groupId }.maxOfOrNull { it.sortIndex }?.plus(1) ?: 0
    }

    private fun cloneDraft(draft: HttpRequestDraft): HttpRequestDraft {
        return draft.copy(
            requestVars = draft.requestVars.map { it.copy() }.toMutableList(),
            pathParams = draft.pathParams.map { it.copy() }.toMutableList(),
            params = draft.params.map { it.copy() }.toMutableList(),
            headers = draft.headers.map { it.copy() }.toMutableList(),
            urlEncoded = draft.urlEncoded.map { it.copy() }.toMutableList(),
            formFields = draft.formFields.map { HttpFormField(it.key, it.value, it.fieldType) }.toMutableList(),
            requestBodyParams = draft.requestBodyParams.map { it.copy() }.toMutableList(),
            body = draft.body,
            preScript = draft.preScript,
            postScript = draft.postScript,
            responseStatus = draft.responseStatus,
            responseContentType = draft.responseContentType,
            responseDescription = draft.responseDescription,
            responseBody = draft.responseBody,
            responseStatusDocs = draft.responseStatusDocs.map { it.copy() }.toMutableList(),
            responseParams = draft.responseParams.map { it.copy() }.toMutableList()
        )
    }

    private fun compareDraftPath(left: HttpRequestDraft, right: HttpRequestDraft): Boolean {
        val leftPath = left.path.trim().ifBlank { extractPath(left.url) }
        val rightPath = right.path.trim().ifBlank { extractPath(right.url) }
        return leftPath == rightPath
    }

    private fun extractPath(url: String): String {
        val raw = url.trim()
        if (raw.isBlank()) {
            return "/"
        }
        if (raw.startsWith("/")) {
            return raw.substringBefore('?')
        }
        val normalized = if (raw.contains("://")) raw else "http://$raw"
        return runCatching {
            URI(normalized).path?.ifBlank { "/" }?.substringBefore('?') ?: "/"
        }.getOrDefault("/")
    }

    private fun Any?.asMap(): Map<String, Any?> {
        val source = this as? Map<*, *> ?: return emptyMap()
        val map = LinkedHashMap<String, Any?>()
        source.forEach { (key, value) ->
            if (key != null) {
                map[key.toString()] = value
            }
        }
        return map
    }

    private fun Any?.asList(): List<Any?> {
        return this as? List<*> ?: emptyList()
    }

    private fun Any?.asString(): String {
        return this?.toString()?.trim().orEmpty()
    }

    private fun Any?.asStringList(): List<String> {
        return asList().mapNotNull { it?.toString()?.trim() }.filter { it.isNotBlank() }
    }

    private fun Any?.asJsonString(): String {
        return when (this) {
            null -> ""
            is String -> this
            is Number, is Boolean -> this.toString()
            is Map<*, *>, is List<*> -> toJson(this)
            else -> this.toString()
        }
    }

    private fun toJson(value: Any?): String {
        return when (value) {
            null -> "null"
            is String -> "\"${escape(value)}\""
            is Number, is Boolean -> value.toString()
            is Map<*, *> -> {
                val entries = value.entries.joinToString(",") { (k, v) ->
                    "\"${escape(k?.toString().orEmpty())}\":${toJson(v)}"
                }
                "{$entries}"
            }
            is List<*> -> value.joinToString(",", prefix = "[", postfix = "]") { toJson(it) }
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

    private val HTTP_METHODS = setOf("get", "post", "put", "patch", "delete", "head", "options")
    private const val MAX_SCHEMA_EXAMPLE_DEPTH = 8
    private val objectMapper: ObjectMapper = ObjectMapper()
}
