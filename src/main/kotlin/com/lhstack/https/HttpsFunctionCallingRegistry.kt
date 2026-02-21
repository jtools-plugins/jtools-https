package com.lhstack.https

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.lhstack.tools.plugins.FunctionCalling
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.pdfbox.pdmodel.font.PDType1Font
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.Authenticator
import java.net.HttpCookie
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import javax.script.ScriptEngine
import javax.script.ScriptEngineManager

class HttpsFunctionCallingRegistry(
    private val project: Project
) {
    fun functionCallings(): List<FunctionCalling> {
        return listOf(
            buildFunction(
                name = "https_open_panel",
                description = "打开 HTTP Client 工具窗口。",
                parameters = """
                    {"type":"object","properties":{}}
                """.trimIndent()
            ) {
                HttpPluginContext.openPanel(project)
                mapOf("opened" to true)
            },
            buildFunction(
                name = "https_add_request_tab",
                description = "创建请求标签，可指定 method/url/headers/body/脚本。",
                parameters = """
                    {
                      "type":"object",
                      "required":["url"],
                      "properties":{
                        "title":{"type":"string"},
                        "method":{"type":"string","description":"HTTP 方法：GET/POST/PUT/PATCH/DELETE/HEAD/OPTIONS"},
                        "url":{"type":"string"},
                        "timeoutSeconds":{"type":"integer"},
                        "pathParams":{"type":"object","additionalProperties":{"type":"string"}},
                        "params":{"type":"object","additionalProperties":{"type":"string"}},
                        "headers":{"type":"object","additionalProperties":{"type":"string"}},
                        "bodyType":{"type":"string","description":"请求体类型：NONE/JSON/FORM_URLENCODED/FORM_DATA"},
                        "body":{"type":"string"},
                        "urlEncoded":{"type":"object","additionalProperties":{"type":"string"}},
                        "formData":{"type":"array","items":{"type":"object","properties":{"key":{"type":"string"},"value":{"type":"string"},"type":{"type":"string"}}}},
                        "preScript":{"type":"string"},
                        "postScript":{"type":"string"}
                      }
                    }
                """.trimIndent()
            ) { args ->
                val url = args["url"]?.toString()?.trim().orEmpty()
                if (url.isBlank()) {
                    throw IllegalArgumentException("url is required")
                }
                val method = args["method"]?.toString()?.trim()?.uppercase().orEmpty().ifBlank { "GET" }
                val timeout = args["timeoutSeconds"].asIntOrNull()?.coerceIn(1, 120) ?: HttpUiSettingsStore.load(project).defaultTimeoutSeconds
                val bodyType = parseBodyType(args["bodyType"]?.toString())
                val title = args["title"]?.toString()?.trim().orEmpty().ifBlank { null }
                val draft = HttpRequestDraft(
                    method = method,
                    url = url,
                    timeoutSeconds = timeout,
                    pathParams = toKeyValueList(args["pathParams"]),
                    params = toKeyValueList(args["params"]),
                    headers = toKeyValueList(args["headers"]),
                    bodyType = bodyType.name,
                    urlEncoded = toKeyValueList(args["urlEncoded"]),
                    formFields = toFormFields(args["formData"]),
                    body = args["body"]?.toString(),
                    preScript = args["preScript"]?.toString(),
                    postScript = args["postScript"]?.toString()
                )
                HttpPluginContext.addSample(project, draft, title)
                mapOf(
                    "created" to true,
                    "title" to (title ?: "${draft.method} ${draft.url}"),
                    "method" to draft.method,
                    "url" to draft.url
                )
            },
            buildFunction(
                name = "https_list_saved_requests",
                description = "查询已保存接口列表（左侧 API 树）。",
                parameters = """
                    {
                      "type":"object",
                      "properties":{
                        "keyword":{"type":"string"},
                        "limit":{"type":"integer"}
                      }
                    }
                """.trimIndent()
            ) { args ->
                val keyword = args["keyword"]?.toString()?.trim().orEmpty()
                val limit = (args["limit"].asIntOrNull() ?: 100).coerceIn(1, 500)
                val items = HttpApiStorage.loadRequests(project)
                    .asSequence()
                    .filter { keyword.isBlank() || containsIgnoreCase(it.name, keyword) || containsIgnoreCase(it.draft.url, keyword) }
                    .take(limit)
                    .map {
                        mapOf(
                            "id" to it.id,
                            "name" to it.name,
                            "groupId" to it.groupId,
                            "method" to it.draft.method,
                            "url" to it.draft.url,
                            "updatedAt" to it.updatedAt.toString()
                        )
                    }
                    .toList()
                mapOf("total" to items.size, "items" to items)
            },
            buildFunction(
                name = "https_get_saved_request_detail",
                description = "按 id 获取已保存接口详情（包含完整请求草稿）。",
                parameters = """
                    {
                      "type":"object",
                      "required":["id"],
                      "properties":{
                        "id":{"type":"integer"}
                      }
                    }
                """.trimIndent()
            ) { args ->
                val id = args["id"].asLongOrNull()
                    ?: throw IllegalArgumentException("id is required")
                val request = HttpApiStorage.loadRequests(project).firstOrNull { it.id == id }
                    ?: throw IllegalArgumentException("saved request not found: $id")
                savedRequestToMap(request)
            },
            buildFunction(
                name = "https_save_request",
                description = "保存接口：可基于 sourceTabId 或 request 对象创建。",
                parameters = """
                    {
                      "type":"object",
                      "required":["name"],
                      "properties":{
                        "name":{"type":"string"},
                        "groupId":{"type":"integer"},
                        "sourceTabId":{"type":"integer"},
                        "sortIndex":{"type":"integer"},
                        "request":{
                          "type":"object",
                          "properties":{
                            "method":{"type":"string"},
                            "url":{"type":"string"},
                            "timeoutSeconds":{"type":"integer"},
                            "pathParams":{"type":"object","additionalProperties":{"type":"string"}},
                            "params":{"type":"object","additionalProperties":{"type":"string"}},
                            "headers":{"type":"object","additionalProperties":{"type":"string"}},
                            "bodyType":{"type":"string"},
                            "body":{"type":"string"},
                            "urlEncoded":{"type":"object","additionalProperties":{"type":"string"}},
                            "formData":{"type":"array","items":{"type":"object","properties":{"key":{"type":"string"},"value":{"type":"string"},"type":{"type":"string"}}}},
                            "preScript":{"type":"string"},
                            "postScript":{"type":"string"}
                          }
                        }
                      }
                    }
                """.trimIndent()
            ) { args ->
                val name = args["name"]?.toString()?.trim().orEmpty()
                if (name.isBlank()) {
                    throw IllegalArgumentException("name is required")
                }
                val groupId = args["groupId"].asLongOrNull()
                val sourceTabId = args["sourceTabId"].asLongOrNull()
                val requestDraft = when {
                    sourceTabId != null -> {
                        val tab = HttpCallTabStorage.loadTabs(project).firstOrNull { it.id == sourceTabId }
                            ?: throw IllegalArgumentException("tab not found: $sourceTabId")
                        cloneDraft(tab.draft)
                    }
                    else -> {
                        val parsed = parseDraftFromArgs(args)
                            ?: throw IllegalArgumentException("request or sourceTabId is required")
                        cloneDraft(parsed)
                    }
                }
                val url = requestDraft.url.trim()
                if (url.isBlank()) {
                    throw IllegalArgumentException("request.url is required")
                }
                requestDraft.url = url
                val sortIndex = args["sortIndex"].asIntOrNull()?.coerceAtLeast(0)
                    ?: nextRequestSortIndex(groupId)
                val savedRequest = HttpSavedRequest(
                    name = name,
                    groupId = groupId,
                    draft = requestDraft,
                    sortIndex = sortIndex
                )
                HttpApiStorage.insertRequest(project, savedRequest)
                mapOf(
                    "created" to true,
                    "request" to savedRequestToMap(savedRequest)
                )
            },
            buildFunction(
                name = "https_update_saved_request",
                description = "更新已保存接口，可修改名称/分组/请求内容。",
                parameters = """
                    {
                      "type":"object",
                      "required":["id"],
                      "properties":{
                        "id":{"type":"integer"},
                        "name":{"type":"string"},
                        "groupId":{"type":"integer"},
                        "sortIndex":{"type":"integer"},
                        "request":{
                          "type":"object",
                          "properties":{
                            "method":{"type":"string"},
                            "url":{"type":"string"},
                            "timeoutSeconds":{"type":"integer"},
                            "pathParams":{"type":"object","additionalProperties":{"type":"string"}},
                            "params":{"type":"object","additionalProperties":{"type":"string"}},
                            "headers":{"type":"object","additionalProperties":{"type":"string"}},
                            "bodyType":{"type":"string"},
                            "body":{"type":"string"},
                            "urlEncoded":{"type":"object","additionalProperties":{"type":"string"}},
                            "formData":{"type":"array","items":{"type":"object","properties":{"key":{"type":"string"},"value":{"type":"string"},"type":{"type":"string"}}}},
                            "preScript":{"type":"string"},
                            "postScript":{"type":"string"}
                          }
                        }
                      }
                    }
                """.trimIndent()
            ) { args ->
                val id = args["id"].asLongOrNull()
                    ?: throw IllegalArgumentException("id is required")
                val requests = HttpApiStorage.loadRequests(project)
                val existing = requests.firstOrNull { it.id == id }
                    ?: throw IllegalArgumentException("saved request not found: $id")
                if (args.containsKey("name")) {
                    val name = args["name"]?.toString()?.trim().orEmpty()
                    if (name.isBlank()) {
                        throw IllegalArgumentException("name cannot be blank")
                    }
                    existing.name = name
                }
                if (args.containsKey("groupId")) {
                    existing.groupId = args["groupId"].asLongOrNull()
                    if (!args.containsKey("sortIndex")) {
                        existing.sortIndex = nextRequestSortIndex(existing.groupId)
                    }
                }
                if (args.containsKey("sortIndex")) {
                    existing.sortIndex = args["sortIndex"].asIntOrNull()?.coerceAtLeast(0) ?: existing.sortIndex
                }
                val draftPatch = when {
                    args.containsKey("request") -> {
                        val rawRequest = args["request"]
                        if (rawRequest !is Map<*, *>) {
                            throw IllegalArgumentException("request must be an object")
                        }
                        rawRequest.toStringKeyMap()
                    }
                    hasDraftKeys(args) -> args
                    else -> emptyMap()
                }
                if (draftPatch.isNotEmpty()) {
                    existing.draft = applyDraftPatch(cloneDraft(existing.draft), draftPatch)
                }
                existing.draft.url = existing.draft.url.trim()
                if (existing.draft.url.isBlank()) {
                    throw IllegalArgumentException("request.url cannot be blank")
                }
                HttpApiStorage.updateRequest(project, existing)
                mapOf(
                    "updated" to true,
                    "request" to savedRequestToMap(existing)
                )
            },
            buildFunction(
                name = "https_delete_saved_request",
                description = "删除已保存接口，并解除 call tab 与该接口的绑定。",
                parameters = """
                    {
                      "type":"object",
                      "required":["id"],
                      "properties":{
                        "id":{"type":"integer"}
                      }
                    }
                """.trimIndent()
            ) { args ->
                val id = args["id"].asLongOrNull()
                    ?: throw IllegalArgumentException("id is required")
                val exists = HttpApiStorage.loadRequests(project).any { it.id == id }
                if (!exists) {
                    return@buildFunction mapOf("deleted" to false, "reason" to "saved request not found")
                }
                val tabs = HttpCallTabStorage.loadTabs(project)
                val unlinkedTabs = tabs.filter { it.savedRequestId == id }
                unlinkedTabs.forEach { tab ->
                    tab.savedRequestId = null
                    tab.title = defaultTabTitle(tab.draft)
                    HttpCallTabStorage.updateTab(project, tab)
                }
                HttpApiStorage.deleteRequest(project, id)
                mapOf(
                    "deleted" to true,
                    "requestId" to id,
                    "unlinkedTabCount" to unlinkedTabs.size
                )
            },
            buildFunction(
                name = "https_list_groups",
                description = "查询接口分组列表。",
                parameters = """
                    {
                      "type":"object",
                      "properties":{
                        "keyword":{"type":"string"},
                        "limit":{"type":"integer"}
                      }
                    }
                """.trimIndent()
            ) { args ->
                val keyword = args["keyword"]?.toString()?.trim().orEmpty()
                val limit = (args["limit"].asIntOrNull() ?: 200).coerceIn(1, 1000)
                val groups = HttpApiStorage.loadGroups(project)
                    .asSequence()
                    .filter { keyword.isBlank() || containsIgnoreCase(it.name, keyword) }
                    .take(limit)
                    .map {
                        mapOf(
                            "id" to it.id,
                            "parentId" to it.parentId,
                            "name" to it.name,
                            "sortIndex" to it.sortIndex,
                            "updatedAt" to it.updatedAt.toString()
                        )
                    }
                    .toList()
                mapOf("total" to groups.size, "items" to groups)
            },
            buildFunction(
                name = "https_upsert_group",
                description = "创建或更新分组（id 为空时创建）。",
                parameters = """
                    {
                      "type":"object",
                      "required":["name"],
                      "properties":{
                        "id":{"type":"integer"},
                        "name":{"type":"string"},
                        "parentId":{"type":"integer"},
                        "sortIndex":{"type":"integer"}
                      }
                    }
                """.trimIndent()
            ) { args ->
                val name = args["name"]?.toString()?.trim().orEmpty()
                if (name.isBlank()) {
                    throw IllegalArgumentException("name is required")
                }
                val id = args["id"].asLongOrNull()
                val parentId = args["parentId"].asLongOrNull()
                if (id != null && parentId != null && id == parentId) {
                    throw IllegalArgumentException("parentId cannot be self")
                }
                val groups = HttpApiStorage.loadGroups(project)
                if (parentId != null && groups.none { it.id == parentId }) {
                    throw IllegalArgumentException("parent group not found: $parentId")
                }
                if (id == null) {
                    val group = HttpApiGroup(
                        parentId = parentId,
                        name = name,
                        sortIndex = args["sortIndex"].asIntOrNull()?.coerceAtLeast(0) ?: nextGroupSortIndex(parentId)
                    )
                    HttpApiStorage.insertGroup(project, group)
                    mapOf("created" to true, "group" to groupToMap(group))
                } else {
                    val existing = groups.firstOrNull { it.id == id }
                        ?: throw IllegalArgumentException("group not found: $id")
                    existing.name = name
                    existing.parentId = parentId
                    if (args.containsKey("sortIndex")) {
                        existing.sortIndex = args["sortIndex"].asIntOrNull()?.coerceAtLeast(0) ?: existing.sortIndex
                    }
                    HttpApiStorage.updateGroup(project, existing)
                    mapOf("updated" to true, "group" to groupToMap(existing))
                }
            },
            buildFunction(
                name = "https_delete_group",
                description = "删除分组（包含其子分组）。分组下接口将自动变为未分组。",
                parameters = """
                    {
                      "type":"object",
                      "required":["id"],
                      "properties":{
                        "id":{"type":"integer"}
                      }
                    }
                """.trimIndent()
            ) { args ->
                val id = args["id"].asLongOrNull()
                    ?: throw IllegalArgumentException("id is required")
                val groups = HttpApiStorage.loadGroups(project)
                if (groups.none { it.id == id }) {
                    return@buildFunction mapOf("deleted" to false, "reason" to "group not found")
                }
                val deletedGroupIds = collectGroupAndDescendants(groups, id)
                val movedRequestCount = HttpApiStorage.loadRequests(project).count { request ->
                    request.groupId != null && deletedGroupIds.contains(request.groupId)
                }
                HttpApiStorage.deleteGroup(project, id)
                mapOf(
                    "deleted" to true,
                    "groupId" to id,
                    "deletedGroupCount" to deletedGroupIds.size,
                    "movedRequestCount" to movedRequestCount
                )
            },
            buildFunction(
                name = "https_list_call_tabs",
                description = "查询 call tab 列表。",
                parameters = """
                    {
                      "type":"object",
                      "properties":{
                        "keyword":{"type":"string"},
                        "limit":{"type":"integer"}
                      }
                    }
                """.trimIndent()
            ) { args ->
                val keyword = args["keyword"]?.toString()?.trim().orEmpty()
                val limit = (args["limit"].asIntOrNull() ?: 200).coerceIn(1, 1000)
                val tabs = HttpCallTabStorage.loadTabs(project)
                    .asSequence()
                    .filter {
                        keyword.isBlank() ||
                            containsIgnoreCase(it.title, keyword) ||
                            containsIgnoreCase(it.draft.url, keyword)
                    }
                    .take(limit)
                    .map { tab ->
                        mapOf(
                            "id" to tab.id,
                            "title" to tab.title,
                            "savedRequestId" to tab.savedRequestId,
                            "method" to tab.draft.method,
                            "url" to tab.draft.url,
                            "updatedAt" to tab.updatedAt.toString()
                        )
                    }
                    .toList()
                mapOf("total" to tabs.size, "items" to tabs)
            },
            buildFunction(
                name = "https_get_call_tab_detail",
                description = "按 id 获取 call tab 详情（包含完整请求草稿）。",
                parameters = """
                    {
                      "type":"object",
                      "required":["id"],
                      "properties":{
                        "id":{"type":"integer"}
                      }
                    }
                """.trimIndent()
            ) { args ->
                val id = args["id"].asLongOrNull()
                    ?: throw IllegalArgumentException("id is required")
                val tab = HttpCallTabStorage.loadTabs(project).firstOrNull { it.id == id }
                    ?: throw IllegalArgumentException("tab not found: $id")
                mapOf(
                    "id" to tab.id,
                    "title" to tab.title,
                    "savedRequestId" to tab.savedRequestId,
                    "sortIndex" to tab.sortIndex,
                    "draft" to draftToMap(tab.draft),
                    "createdAt" to tab.createdAt.toString(),
                    "updatedAt" to tab.updatedAt.toString()
                )
            },
            buildFunction(
                name = "https_update_tab_scripts",
                description = "更新指定 call tab 的前置/后置脚本。",
                parameters = """
                    {
                      "type":"object",
                      "required":["tabId"],
                      "properties":{
                        "tabId":{"type":"integer"},
                        "preScript":{"type":"string"},
                        "postScript":{"type":"string"}
                      }
                    }
                """.trimIndent()
            ) { args ->
                val tabId = args["tabId"].asLongOrNull()
                    ?: throw IllegalArgumentException("tabId is required")
                if (!args.containsKey("preScript") && !args.containsKey("postScript")) {
                    throw IllegalArgumentException("preScript or postScript is required")
                }
                val tabs = HttpCallTabStorage.loadTabs(project)
                val tab = tabs.firstOrNull { it.id == tabId }
                    ?: throw IllegalArgumentException("tab not found: $tabId")
                val draft = cloneDraft(tab.draft)
                if (args.containsKey("preScript")) {
                    draft.preScript = args["preScript"]?.toString()
                }
                if (args.containsKey("postScript")) {
                    draft.postScript = args["postScript"]?.toString()
                }
                tab.draft = draft
                HttpCallTabStorage.updateTab(project, tab)
                mapOf(
                    "updated" to true,
                    "tabId" to tab.id,
                    "hasPreScript" to !draft.preScript.isNullOrBlank(),
                    "hasPostScript" to !draft.postScript.isNullOrBlank()
                )
            },
            buildFunction(
                name = "https_build_curl",
                description = "基于请求草稿生成 cURL 命令。",
                parameters = """
                    {
                      "type":"object",
                      "properties":{
                        "sourceTabId":{"type":"integer"},
                        "savedRequestId":{"type":"integer"},
                        "request":{
                          "type":"object",
                          "properties":{
                            "method":{"type":"string"},
                            "url":{"type":"string"},
                            "timeoutSeconds":{"type":"integer"},
                            "pathParams":{"type":"object","additionalProperties":{"type":"string"}},
                            "params":{"type":"object","additionalProperties":{"type":"string"}},
                            "headers":{"type":"object","additionalProperties":{"type":"string"}},
                            "bodyType":{"type":"string"},
                            "body":{"type":"string"},
                            "urlEncoded":{"type":"object","additionalProperties":{"type":"string"}},
                            "formData":{"type":"array","items":{"type":"object","properties":{"key":{"type":"string"},"value":{"type":"string"},"type":{"type":"string"}}}}
                          }
                        }
                      }
                    }
                """.trimIndent()
            ) { args ->
                val resolved = resolveDraftForFunction(args)
                val curl = buildCurlCommand(resolved.draft)
                mapOf(
                    "sourceType" to (resolved.sourceType?.name ?: "ADHOC"),
                    "sourceId" to resolved.sourceId,
                    "curl" to curl
                )
            },
            buildFunction(
                name = "https_execute_request",
                description = "执行请求并返回响应摘要，可选写入历史与 Cookie。",
                parameters = """
                    {
                      "type":"object",
                      "properties":{
                        "sourceTabId":{"type":"integer"},
                        "savedRequestId":{"type":"integer"},
                        "request":{
                          "type":"object",
                          "properties":{
                            "method":{"type":"string"},
                            "url":{"type":"string"},
                            "timeoutSeconds":{"type":"integer"},
                            "pathParams":{"type":"object","additionalProperties":{"type":"string"}},
                            "params":{"type":"object","additionalProperties":{"type":"string"}},
                            "headers":{"type":"object","additionalProperties":{"type":"string"}},
                            "bodyType":{"type":"string"},
                            "body":{"type":"string"},
                            "urlEncoded":{"type":"object","additionalProperties":{"type":"string"}},
                            "formData":{"type":"array","items":{"type":"object","properties":{"key":{"type":"string"},"value":{"type":"string"},"type":{"type":"string"}}}},
                            "preScript":{"type":"string"},
                            "postScript":{"type":"string"}
                          }
                        },
                        "persistCookies":{"type":"boolean","description":"是否写入 Cookie 存储，默认 true"},
                        "saveHistory":{"type":"boolean","description":"是否写入请求历史，默认 false"},
                        "includeBody":{"type":"boolean","description":"是否返回 body/bodyBase64，默认 true"},
                        "maxBodyChars":{"type":"integer","description":"文本 body 最大返回字符数，默认 200000"}
                      }
                    }
                """.trimIndent()
            ) { args ->
                val resolved = resolveDraftForFunction(args)
                val persistCookies = args["persistCookies"].asBooleanOrNull() ?: true
                val saveHistory = args["saveHistory"].asBooleanOrNull() ?: false
                val includeBody = args["includeBody"].asBooleanOrNull() ?: true
                val maxBodyChars = (args["maxBodyChars"].asIntOrNull() ?: 200_000).coerceIn(1_000, 2_000_000)
                val request = buildHttpRequest(resolved.draft, persistCookies)
                val clientBuilder = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(resolved.draft.timeoutSeconds.toLong().coerceIn(1, 120)))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                applyProxySettings(clientBuilder, HttpUiSettingsStore.load(project))
                val client = clientBuilder.build()
                val start = System.nanoTime()
                val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
                val cookieMutations = extractCookieMutations(response)
                if (persistCookies && cookieMutations.isNotEmpty()) {
                    applyCookieMutations(cookieMutations)
                }
                val snapshot = buildResponseSnapshot(response, start, includeBody, maxBodyChars)
                if (saveHistory) {
                    val historyType = resolved.sourceType ?: HistorySourceType.TAB
                    HttpRequestHistoryStorage.append(
                        project,
                        HttpRequestHistoryEntry(
                            sourceType = historyType,
                            sourceId = resolved.sourceId,
                            request = cloneDraft(resolved.draft),
                            response = snapshot
                        )
                    )
                }
                mapOf(
                    "sourceType" to (resolved.sourceType?.name ?: "ADHOC"),
                    "sourceId" to resolved.sourceId,
                    "request" to draftToMap(resolved.draft),
                    "response" to responseToMap(snapshot),
                    "cookieMutationCount" to cookieMutations.size
                )
            },
            buildFunction(
                name = "https_jvm_class_available",
                description = "检测 JVM Bridge 是否可加载指定类。",
                parameters = """
                    {
                      "type":"object",
                      "required":["className"],
                      "properties":{
                        "className":{"type":"string"},
                        "includeClassPath":{"type":"boolean","description":"是否返回 classpath 列表，默认 false"}
                      }
                    }
                """.trimIndent()
            ) { args ->
                val className = args["className"]?.toString()?.trim().orEmpty()
                if (className.isBlank()) {
                    throw IllegalArgumentException("className is required")
                }
                val includeClassPath = args["includeClassPath"].asBooleanOrNull() ?: false
                val logger = HttpScriptLogger(project)
                val bridge = HttpScriptJvmBridge(project, logger)
                val available = bridge.available(className)
                val result = linkedMapOf<String, Any?>(
                    "className" to className,
                    "available" to available
                )
                if (includeClassPath) {
                    val classpath = bridge.classpath()
                    result["classpath"] = classpath
                    result["classpathSize"] = classpath.size
                }
                result
            },
            buildFunction(
                name = "https_export_requests_document",
                description = "导出选定接口文档，支持 openapi/swagger/html/pdf。",
                parameters = """
                    {
                      "type":"object",
                      "properties":{
                        "requestIds":{"type":"array","items":{"type":"integer"},"description":"要导出的接口 id 列表；为空则导出全部"},
                        "format":{"type":"string","description":"导出格式：openapi/swagger/html/pdf"},
                        "title":{"type":"string"},
                        "version":{"type":"string"},
                        "serverUrl":{"type":"string"},
                        "savePath":{"type":"string","description":"保存路径。调用前应先询问用户。"},
                        "overwrite":{"type":"boolean","description":"是否覆盖已存在文件，默认 true"}
                      }
                    }
                """.trimIndent()
            ) { args ->
                val savePath = args["savePath"]?.toString()?.trim().orEmpty()
                if (savePath.isBlank()) {
                    return@buildFunction mapOf(
                        "requiresUserInput" to true,
                        "question" to "请先询问用户希望保存到哪个路径，然后将该路径作为 savePath 重新调用。"
                    )
                }
                val format = args["format"]?.toString()?.trim()?.lowercase().orEmpty().ifBlank { "openapi" }
                val overwrite = args["overwrite"].asBooleanOrNull() ?: true
                val allRequests = HttpApiStorage.loadRequests(project)
                val requestIds = args["requestIds"].asLongList()
                val selected = if (requestIds.isEmpty()) {
                    allRequests
                } else {
                    val ids = requestIds.toSet()
                    allRequests.filter { ids.contains(it.id) }.toMutableList()
                }
                if (selected.isEmpty()) {
                    throw IllegalArgumentException("no saved requests selected for export")
                }
                val title = args["title"]?.toString()?.trim().orEmpty().ifBlank { "HTTP API" }
                val version = args["version"]?.toString()?.trim().orEmpty().ifBlank { "1.0.0" }
                val serverUrl = args["serverUrl"]?.toString()?.trim().orEmpty()
                val export = HttpApiDocumentExportService.buildExportContent(format, selected, title, version, serverUrl)
                val output = resolveOutputPath(savePath, format, export.defaultFileName)
                val parent = output.parent
                if (parent != null && !Files.exists(parent)) {
                    Files.createDirectories(parent)
                }
                if (!overwrite && Files.exists(output)) {
                    throw IllegalArgumentException("target file already exists: $output")
                }
                Files.write(output, export.bytes)
                mapOf(
                    "exported" to true,
                    "format" to export.format,
                    "path" to output.toAbsolutePath().toString(),
                    "sizeBytes" to export.bytes.size,
                    "requestCount" to selected.size
                )
            },
            buildFunction(
                name = "https_import_api_spec",
                description = "导入 OpenAPI/Swagger JSON 到接口列表，支持 url/file/json 三种来源。",
                parameters = """
                    {
                      "type":"object",
                      "required":["sourceType"],
                      "properties":{
                        "sourceType":{"type":"string","description":"来源类型：url/file/json"},
                        "url":{"type":"string"},
                        "filePath":{"type":"string"},
                        "json":{"type":"string"},
                        "rootGroupName":{"type":"string","description":"可选，导入时挂到该根分组下"},
                        "overwriteExisting":{"type":"boolean","description":"同名接口是否覆盖，默认 true"}
                      }
                    }
                """.trimIndent()
            ) { args ->
                val sourceType = args["sourceType"]?.toString()?.trim()?.lowercase().orEmpty()
                val rootGroupName = args["rootGroupName"]?.toString()
                val overwriteExisting = args["overwriteExisting"].asBooleanOrNull() ?: true
                val result = when (sourceType) {
                    "url" -> {
                        val url = args["url"]?.toString()?.trim().orEmpty()
                        if (url.isBlank()) {
                            throw IllegalArgumentException("url is required when sourceType=url")
                        }
                        HttpApiSpecImportService.importFromUrl(project, url, rootGroupName, overwriteExisting)
                    }
                    "file" -> {
                        val filePath = args["filePath"]?.toString()?.trim().orEmpty()
                        if (filePath.isBlank()) {
                            throw IllegalArgumentException("filePath is required when sourceType=file")
                        }
                        HttpApiSpecImportService.importFromFile(project, filePath, rootGroupName, overwriteExisting)
                    }
                    "json" -> {
                        val json = args["json"]?.toString().orEmpty()
                        if (json.isBlank()) {
                            throw IllegalArgumentException("json is required when sourceType=json")
                        }
                        HttpApiSpecImportService.importFromJson(
                            project = project,
                            json = json,
                            options = HttpApiSpecImportService.ImportOptions(
                                sourceType = HttpApiSpecImportService.SourceType.JSON,
                                source = "function-json",
                                rootGroupName = rootGroupName,
                                overwriteExisting = overwriteExisting
                            )
                        )
                    }
                    else -> throw IllegalArgumentException("sourceType must be one of url/file/json")
                }
                HttpPluginContext.refreshApiData(project)
                importResultToMap(result)
            },
            buildFunction(
                name = "https_import_api_from_url",
                description = "通过 URL 导入 OpenAPI/Swagger JSON。",
                parameters = """
                    {
                      "type":"object",
                      "required":["url"],
                      "properties":{
                        "url":{"type":"string"},
                        "rootGroupName":{"type":"string"},
                        "overwriteExisting":{"type":"boolean"}
                      }
                    }
                """.trimIndent()
            ) { args ->
                val url = args["url"]?.toString()?.trim().orEmpty()
                if (url.isBlank()) {
                    throw IllegalArgumentException("url is required")
                }
                val result = HttpApiSpecImportService.importFromUrl(
                    project = project,
                    url = url,
                    rootGroupName = args["rootGroupName"]?.toString(),
                    overwriteExisting = args["overwriteExisting"].asBooleanOrNull() ?: true
                )
                HttpPluginContext.refreshApiData(project)
                importResultToMap(result)
            },
            buildFunction(
                name = "https_import_api_from_file",
                description = "通过本地 JSON 文件导入 OpenAPI/Swagger。",
                parameters = """
                    {
                      "type":"object",
                      "required":["filePath"],
                      "properties":{
                        "filePath":{"type":"string"},
                        "rootGroupName":{"type":"string"},
                        "overwriteExisting":{"type":"boolean"}
                      }
                    }
                """.trimIndent()
            ) { args ->
                val filePath = args["filePath"]?.toString()?.trim().orEmpty()
                if (filePath.isBlank()) {
                    throw IllegalArgumentException("filePath is required")
                }
                val result = HttpApiSpecImportService.importFromFile(
                    project = project,
                    filePath = filePath,
                    rootGroupName = args["rootGroupName"]?.toString(),
                    overwriteExisting = args["overwriteExisting"].asBooleanOrNull() ?: true
                )
                HttpPluginContext.refreshApiData(project)
                importResultToMap(result)
            },
            buildFunction(
                name = "https_import_api_from_json",
                description = "通过 JSON 文本导入 OpenAPI/Swagger。",
                parameters = """
                    {
                      "type":"object",
                      "required":["json"],
                      "properties":{
                        "json":{"type":"string"},
                        "rootGroupName":{"type":"string"},
                        "overwriteExisting":{"type":"boolean"}
                      }
                    }
                """.trimIndent()
            ) { args ->
                val json = args["json"]?.toString().orEmpty()
                if (json.isBlank()) {
                    throw IllegalArgumentException("json is required")
                }
                val result = HttpApiSpecImportService.importFromJson(
                    project = project,
                    json = json,
                    options = HttpApiSpecImportService.ImportOptions(
                        sourceType = HttpApiSpecImportService.SourceType.JSON,
                        source = "function-json",
                        rootGroupName = args["rootGroupName"]?.toString(),
                        overwriteExisting = args["overwriteExisting"].asBooleanOrNull() ?: true
                    )
                )
                HttpPluginContext.refreshApiData(project)
                importResultToMap(result)
            },
            buildFunction(
                name = "https_list_request_history",
                description = "查询请求历史记录。",
                parameters = """
                    {
                      "type":"object",
                      "properties":{
                        "limit":{"type":"integer"},
                        "sourceType":{"type":"string","description":"来源类型：TAB/SAVED"},
                        "sourceId":{"type":"integer"}
                      }
                    }
                """.trimIndent()
            ) { args ->
                val limit = (args["limit"].asIntOrNull() ?: 50).coerceIn(1, 500)
                val sourceType = parseHistorySourceType(args["sourceType"]?.toString())
                val sourceId = args["sourceId"].asLongOrNull()
                val list = if (sourceType != null && sourceId != null) {
                    HttpRequestHistoryStorage.loadForSource(project, sourceType, sourceId)
                } else {
                    HttpRequestHistoryStorage.loadAll(project)
                }
                val items = list.take(limit).map {
                    mapOf(
                        "id" to it.id,
                        "sourceType" to it.sourceType.name,
                        "sourceId" to it.sourceId,
                        "method" to it.request.method,
                        "url" to it.request.url,
                        "status" to (it.response?.status ?: 0),
                        "createdAt" to it.createdAt.toString()
                    )
                }
                mapOf("total" to items.size, "items" to items)
            },
            buildFunction(
                name = "https_get_history_detail",
                description = "按历史 id 获取完整请求/响应详情。",
                parameters = """
                    {
                      "type":"object",
                      "required":["id"],
                      "properties":{
                        "id":{"type":"integer"}
                      }
                    }
                """.trimIndent()
            ) { args ->
                val id = args["id"].asLongOrNull()
                    ?: throw IllegalArgumentException("id is required")
                val entry = HttpRequestHistoryStorage.loadAll(project).firstOrNull { it.id == id }
                    ?: throw IllegalArgumentException("history not found: $id")
                mapOf(
                    "id" to entry.id,
                    "sourceType" to entry.sourceType.name,
                    "sourceId" to entry.sourceId,
                    "request" to draftToMap(entry.request),
                    "response" to responseToMap(entry.response),
                    "createdAt" to entry.createdAt.toString(),
                    "updatedAt" to entry.updatedAt.toString()
                )
            },
            buildFunction(
                name = "https_find_endpoints",
                description = "按 method + path/url 在项目中查找接口端点。",
                parameters = """
                    {
                      "type":"object",
                      "required":["pathOrUrl"],
                      "properties":{
                        "method":{"type":"string","description":"HTTP 方法：GET/POST/PUT/PATCH/DELETE/ANY"},
                        "pathOrUrl":{"type":"string"}
                      }
                    }
                """.trimIndent()
            ) { args ->
                val method = args["method"]?.toString().orEmpty()
                val pathOrUrl = args["pathOrUrl"]?.toString()?.trim().orEmpty()
                if (pathOrUrl.isBlank()) {
                    throw IllegalArgumentException("pathOrUrl is required")
                }
                val requestPath = parseRequestPath(pathOrUrl)
                if (requestPath == null) {
                    throw IllegalArgumentException("invalid pathOrUrl")
                }
                val endpoints = ReadAction.compute<List<EndpointInfo>, RuntimeException> {
                    HttpEndpointLocator.find(project, method, requestPath)
                }
                val items = endpoints.map { endpoint ->
                    mapOf(
                        "method" to endpoint.httpMethod,
                        "path" to endpoint.path,
                        "source" to endpoint.source.name,
                        "className" to endpoint.psiMethod?.containingClass?.qualifiedName,
                        "methodName" to endpoint.psiMethod?.name
                    )
                }
                mapOf("total" to items.size, "items" to items)
            },
            buildFunction(
                name = "https_get_ui_settings",
                description = "获取 HTTP Client UI 设置。",
                parameters = """
                    {"type":"object","properties":{}}
                """.trimIndent()
            ) {
                val settings = HttpUiSettingsStore.load(project)
                mapOf(
                    "defaultTimeoutSeconds" to settings.defaultTimeoutSeconds,
                    "maxRawViewChars" to settings.maxRawViewChars,
                    "maxRenderChars" to settings.maxRenderChars,
                    "lineMarkerEnabled" to settings.lineMarkerEnabled,
                    "contextMenuEnabled" to settings.contextMenuEnabled,
                    "proxyEnabled" to settings.proxyEnabled,
                    "proxyType" to settings.proxyType,
                    "proxyHost" to settings.proxyHost,
                    "proxyPort" to settings.proxyPort,
                    "proxyUsername" to settings.proxyUsername,
                    "proxyPasswordConfigured" to settings.proxyPassword.isNotEmpty()
                )
            },
            buildFunction(
                name = "https_update_ui_settings",
                description = "更新 HTTP Client UI 设置。",
                parameters = """
                    {
                      "type":"object",
                      "properties":{
                        "defaultTimeoutSeconds":{"type":"integer"},
                        "maxRawViewChars":{"type":"integer"},
                        "maxRenderChars":{"type":"integer"},
                        "lineMarkerEnabled":{"type":"boolean"},
                        "contextMenuEnabled":{"type":"boolean"},
                        "proxyEnabled":{"type":"boolean","description":"是否启用代理"},
                        "proxyType":{"type":"string","description":"代理类型：HTTP/SOCKS"},
                        "proxyHost":{"type":"string","description":"代理地址"},
                        "proxyPort":{"type":"integer","description":"代理端口，1-65535"},
                        "proxyUsername":{"type":"string","description":"代理用户名，可选"},
                        "proxyPassword":{"type":"string","description":"代理密码，可选；传空字符串可清空"}
                      }
                    }
                """.trimIndent()
            ) { args ->
                val current = HttpUiSettingsStore.load(project)
                val updated = current.copy(
                    defaultTimeoutSeconds = args["defaultTimeoutSeconds"].asIntOrNull() ?: current.defaultTimeoutSeconds,
                    maxRawViewChars = args["maxRawViewChars"].asIntOrNull() ?: current.maxRawViewChars,
                    maxRenderChars = args["maxRenderChars"].asIntOrNull() ?: current.maxRenderChars,
                    lineMarkerEnabled = args["lineMarkerEnabled"].asBooleanOrNull() ?: current.lineMarkerEnabled,
                    contextMenuEnabled = args["contextMenuEnabled"].asBooleanOrNull() ?: current.contextMenuEnabled,
                    proxyEnabled = args["proxyEnabled"].asBooleanOrNull() ?: current.proxyEnabled,
                    proxyType = args["proxyType"]?.toString() ?: current.proxyType,
                    proxyHost = args["proxyHost"]?.toString() ?: current.proxyHost,
                    proxyPort = args["proxyPort"].asIntOrNull() ?: current.proxyPort,
                    proxyUsername = args["proxyUsername"]?.toString() ?: current.proxyUsername,
                    proxyPassword = args["proxyPassword"]?.toString() ?: current.proxyPassword
                )
                HttpPluginContext.updateSettings(project, updated)
                val reloaded = HttpUiSettingsStore.load(project)
                mapOf(
                    "defaultTimeoutSeconds" to reloaded.defaultTimeoutSeconds,
                    "maxRawViewChars" to reloaded.maxRawViewChars,
                    "maxRenderChars" to reloaded.maxRenderChars,
                    "lineMarkerEnabled" to reloaded.lineMarkerEnabled,
                    "contextMenuEnabled" to reloaded.contextMenuEnabled,
                    "proxyEnabled" to reloaded.proxyEnabled,
                    "proxyType" to reloaded.proxyType,
                    "proxyHost" to reloaded.proxyHost,
                    "proxyPort" to reloaded.proxyPort,
                    "proxyUsername" to reloaded.proxyUsername,
                    "proxyPasswordConfigured" to reloaded.proxyPassword.isNotEmpty()
                )
            },
            buildFunction(
                name = "https_env_get",
                description = "读取环境变量。",
                parameters = """
                    {
                      "type":"object",
                      "required":["key"],
                      "properties":{
                        "scope":{"type":"string","description":"作用域：project/global/merged，默认 merged"},
                        "key":{"type":"string"}
                      }
                    }
                """.trimIndent()
            ) { args ->
                val key = args["key"]?.toString()?.trim().orEmpty()
                if (key.isBlank()) {
                    throw IllegalArgumentException("key is required")
                }
                val scope = args["scope"]?.toString()?.trim()?.lowercase().orEmpty()
                mapOf(
                    "key" to key,
                    "scope" to scope.ifBlank { "merged" },
                    "value" to getEnv(scope, key)
                )
            },
            buildFunction(
                name = "https_env_set",
                description = "设置环境变量（仅支持 project/global）。",
                parameters = """
                    {
                      "type":"object",
                      "required":["scope","key","value"],
                      "properties":{
                        "scope":{"type":"string","description":"作用域：project/global"},
                        "key":{"type":"string"},
                        "value":{"type":"string"}
                      }
                    }
                """.trimIndent()
            ) { args ->
                val scope = args["scope"]?.toString()?.trim()?.lowercase().orEmpty()
                val key = args["key"]?.toString()?.trim().orEmpty()
                if (key.isBlank()) {
                    throw IllegalArgumentException("key is required")
                }
                val value = args["value"]?.toString().orEmpty()
                when (scope) {
                    "project" -> HttpScriptEnvStore.updateProject(project, key, value)
                    "global" -> HttpScriptEnvStore.updateGlobal(key, value)
                    else -> throw IllegalArgumentException("scope must be project/global")
                }
                mapOf("updated" to true, "scope" to scope, "key" to key)
            },
            buildFunction(
                name = "https_env_list",
                description = "列出环境变量。",
                parameters = """
                    {
                      "type":"object",
                      "properties":{
                        "scope":{"type":"string","description":"作用域：project/global/merged，默认 merged"}
                      }
                    }
                """.trimIndent()
            ) { args ->
                val scope = args["scope"]?.toString()?.trim()?.lowercase().orEmpty()
                mapOf(
                    "scope" to scope.ifBlank { "merged" },
                    "values" to listEnv(scope)
                )
            },
            buildFunction(
                name = "https_env_remove",
                description = "删除环境变量（仅支持 project/global）。",
                parameters = """
                    {
                      "type":"object",
                      "required":["scope","key"],
                      "properties":{
                        "scope":{"type":"string","description":"作用域：project/global"},
                        "key":{"type":"string"}
                      }
                    }
                """.trimIndent()
            ) { args ->
                val scope = args["scope"]?.toString()?.trim()?.lowercase().orEmpty()
                val key = args["key"]?.toString()?.trim().orEmpty()
                if (key.isBlank()) {
                    throw IllegalArgumentException("key is required")
                }
                when (scope) {
                    "project" -> HttpScriptEnvStore.removeProject(project, key)
                    "global" -> HttpScriptEnvStore.removeGlobal(key)
                    else -> throw IllegalArgumentException("scope must be project/global")
                }
                mapOf("removed" to true, "scope" to scope, "key" to key)
            },
            buildFunction(
                name = "https_manage_env_variable",
                description = "管理脚本环境变量（查询/设置/删除/列表）。",
                parameters = """
                    {
                      "type":"object",
                      "required":["action","scope"],
                      "properties":{
                        "action":{"type":"string","description":"操作类型：get/set/remove/list"},
                        "scope":{"type":"string","description":"作用域：project/global/merged"},
                        "key":{"type":"string"},
                        "value":{"type":"string"}
                      }
                    }
                """.trimIndent()
            ) { args ->
                val action = args["action"]?.toString()?.trim()?.lowercase().orEmpty()
                val scope = args["scope"]?.toString()?.trim()?.lowercase().orEmpty()
                when (action) {
                    "list" -> mapOf("scope" to scope, "values" to listEnv(scope))
                    "get" -> {
                        val key = args["key"]?.toString()?.trim().orEmpty()
                        if (key.isBlank()) {
                            throw IllegalArgumentException("key is required for get")
                        }
                        mapOf("key" to key, "value" to getEnv(scope, key))
                    }
                    "set" -> {
                        val key = args["key"]?.toString()?.trim().orEmpty()
                        if (key.isBlank()) {
                            throw IllegalArgumentException("key is required for set")
                        }
                        val value = args["value"]?.toString().orEmpty()
                        when (scope) {
                            "project" -> HttpScriptEnvStore.updateProject(project, key, value)
                            "global" -> HttpScriptEnvStore.updateGlobal(key, value)
                            else -> throw IllegalArgumentException("scope must be project/global for set")
                        }
                        mapOf("updated" to true, "scope" to scope, "key" to key)
                    }
                    "remove" -> {
                        val key = args["key"]?.toString()?.trim().orEmpty()
                        if (key.isBlank()) {
                            throw IllegalArgumentException("key is required for remove")
                        }
                        when (scope) {
                            "project" -> HttpScriptEnvStore.removeProject(project, key)
                            "global" -> HttpScriptEnvStore.removeGlobal(key)
                            else -> throw IllegalArgumentException("scope must be project/global for remove")
                        }
                        mapOf("removed" to true, "scope" to scope, "key" to key)
                    }
                    else -> throw IllegalArgumentException("action must be one of get/set/remove/list")
                }
            }
        )
    }

    private fun buildFunction(
        name: String,
        description: String,
        parameters: String,
        handler: (Map<String, Any?>) -> Any?
    ): FunctionCalling {
        return object : FunctionCalling {
            override fun name(): String = name

            override fun description(): String = description

            override fun parameters(): String = parameters

            override fun call(arguments: String): String {
                return try {
                    val args = parseArgs(arguments)
                    val result = handler(args)
                    success(result)
                } catch (e: Exception) {
                    failure(e.message ?: "call failed")
                }
            }
        }
    }

    private fun parseDraftFromArgs(args: Map<String, Any?>): HttpRequestDraft? {
        if (args.containsKey("request")) {
            val raw = args["request"]
            if (raw !is Map<*, *>) {
                throw IllegalArgumentException("request must be an object")
            }
            val requestMap = raw.toStringKeyMap()
            return applyDraftPatch(HttpRequestDraft(), requestMap)
        }
        if (hasDraftKeys(args)) {
            return applyDraftPatch(HttpRequestDraft(), args)
        }
        return null
    }

    private fun hasDraftKeys(values: Map<String, Any?>): Boolean {
        return DRAFT_KEYS.any { values.containsKey(it) }
    }

    private fun applyDraftPatch(base: HttpRequestDraft, patch: Map<String, Any?>): HttpRequestDraft {
        val next = cloneDraft(base)
        if (patch.containsKey("method")) {
            val method = patch["method"]?.toString()?.trim()?.uppercase().orEmpty()
            if (method.isNotBlank()) {
                next.method = method
            }
        }
        if (patch.containsKey("url")) {
            next.url = patch["url"]?.toString().orEmpty()
        }
        if (patch.containsKey("timeoutSeconds")) {
            val timeout = patch["timeoutSeconds"].asIntOrNull()
            if (timeout != null) {
                next.timeoutSeconds = timeout.coerceIn(1, 120)
            }
        }
        if (patch.containsKey("pathParams")) {
            next.pathParams = toKeyValueList(patch["pathParams"])
        }
        if (patch.containsKey("params")) {
            next.params = toKeyValueList(patch["params"])
        }
        if (patch.containsKey("headers")) {
            next.headers = toKeyValueList(patch["headers"])
        }
        if (patch.containsKey("bodyType")) {
            next.bodyType = parseBodyType(patch["bodyType"]?.toString()).name
        }
        if (patch.containsKey("body")) {
            next.body = patch["body"]?.toString()
        }
        if (patch.containsKey("urlEncoded")) {
            next.urlEncoded = toKeyValueList(patch["urlEncoded"])
        }
        if (patch.containsKey("formData")) {
            next.formFields = toFormFields(patch["formData"])
        }
        if (patch.containsKey("requestBodyParams")) {
            next.requestBodyParams = toKeyValueList(patch["requestBodyParams"])
        }
        if (patch.containsKey("responseStatus")) {
            next.responseStatus = patch["responseStatus"]?.toString()?.trim().orEmpty()
        }
        if (patch.containsKey("responseContentType")) {
            next.responseContentType = patch["responseContentType"]?.toString()?.trim().orEmpty()
        }
        if (patch.containsKey("responseDescription")) {
            next.responseDescription = patch["responseDescription"]?.toString()
        }
        if (patch.containsKey("responseBody")) {
            next.responseBody = patch["responseBody"]?.toString()
        }
        if (patch.containsKey("responseStatusDocs")) {
            next.responseStatusDocs = toKeyValueList(patch["responseStatusDocs"])
                .map { item -> item.copy(value = "", description = item.description.ifBlank { item.value }) }
                .toMutableList()
        }
        if (patch.containsKey("responseParams")) {
            next.responseParams = toKeyValueList(patch["responseParams"])
        }
        if (patch.containsKey("preScript")) {
            next.preScript = patch["preScript"]?.toString()
        }
        if (patch.containsKey("postScript")) {
            next.postScript = patch["postScript"]?.toString()
        }
        when (parseBodyType(next.bodyType)) {
            HttpBodyType.NONE -> {
                next.body = null
                next.urlEncoded.clear()
                next.formFields.clear()
            }
            HttpBodyType.JSON -> {
                next.urlEncoded.clear()
                next.formFields.clear()
            }
            HttpBodyType.FORM_URLENCODED -> {
                next.body = null
                next.formFields.clear()
            }
            HttpBodyType.FORM_DATA -> {
                next.body = null
                next.urlEncoded.clear()
            }
        }
        return next
    }

    private fun cloneDraft(draft: HttpRequestDraft): HttpRequestDraft {
        return draft.copy(
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

    private fun nextRequestSortIndex(groupId: Long?): Int {
        return HttpApiStorage.loadRequests(project)
            .asSequence()
            .filter { it.groupId == groupId }
            .maxOfOrNull { it.sortIndex }
            ?.plus(1)
            ?: 0
    }

    private fun nextGroupSortIndex(parentId: Long?): Int {
        return HttpApiStorage.loadGroups(project)
            .asSequence()
            .filter { it.parentId == parentId }
            .maxOfOrNull { it.sortIndex }
            ?.plus(1)
            ?: 0
    }

    private fun collectGroupAndDescendants(groups: List<HttpApiGroup>, rootId: Long): Set<Long> {
        val children = groups.groupBy { it.parentId }
        val queue = java.util.ArrayDeque<Long>()
        val collected = LinkedHashSet<Long>()
        queue.addLast(rootId)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!collected.add(current)) {
                continue
            }
            children[current].orEmpty().forEach { queue.addLast(it.id) }
        }
        return collected
    }

    private fun defaultTabTitle(draft: HttpRequestDraft): String {
        val method = draft.method.trim().ifBlank { "GET" }
        val url = draft.url.trim().ifBlank { "未命名" }
        return "$method $url"
    }

    private fun groupToMap(group: HttpApiGroup): Map<String, Any?> {
        return mapOf(
            "id" to group.id,
            "parentId" to group.parentId,
            "name" to group.name,
            "sortIndex" to group.sortIndex,
            "createdAt" to group.createdAt.toString(),
            "updatedAt" to group.updatedAt.toString()
        )
    }

    private fun savedRequestToMap(request: HttpSavedRequest): Map<String, Any?> {
        return mapOf(
            "id" to request.id,
            "groupId" to request.groupId,
            "name" to request.name,
            "sortIndex" to request.sortIndex,
            "request" to draftToMap(request.draft),
            "createdAt" to request.createdAt.toString(),
            "updatedAt" to request.updatedAt.toString()
        )
    }

    private fun draftToMap(draft: HttpRequestDraft): Map<String, Any?> {
        return mapOf(
            "method" to draft.method,
            "url" to draft.url,
            "timeoutSeconds" to draft.timeoutSeconds,
            "pathParams" to keyValueListToMap(draft.pathParams),
            "params" to keyValueListToMap(draft.params),
            "headers" to keyValueListToMap(draft.headers),
            "bodyType" to parseBodyType(draft.bodyType).name,
            "body" to draft.body,
            "urlEncoded" to keyValueListToMap(draft.urlEncoded),
            "formData" to draft.formFields.map {
                mapOf(
                    "key" to it.key,
                    "value" to it.value,
                    "type" to it.fieldType
                )
            },
            "requestBodyParams" to keyValueListToMapWithDescription(draft.requestBodyParams),
            "responseStatus" to draft.responseStatus,
            "responseContentType" to draft.responseContentType,
            "responseDescription" to draft.responseDescription,
            "responseBody" to draft.responseBody,
            "responseStatusDocs" to keyValueListToMap(draft.responseStatusDocs.map { it.copy(value = it.description) }),
            "responseParams" to keyValueListToMapWithDescription(draft.responseParams),
            "preScript" to draft.preScript,
            "postScript" to draft.postScript
        )
    }

    private fun responseToMap(response: HttpResponseSnapshot?): Map<String, Any?>? {
        if (response == null) {
            return null
        }
        return mapOf(
            "status" to response.status,
            "statusText" to response.statusText,
            "durationMs" to response.durationMs,
            "sizeBytes" to response.sizeBytes,
            "contentType" to response.contentType,
            "contentEncoding" to response.contentEncoding,
            "encodingUnsupported" to response.encodingUnsupported,
            "bodyTruncated" to response.bodyTruncated,
            "requestMethod" to response.requestMethod,
            "requestUrl" to response.requestUrl,
            "headers" to keyValueListToMap(response.headers),
            "requestHeaders" to keyValueListToMap(response.requestHeaders),
            "requestParams" to keyValueListToMap(response.requestParams),
            "body" to response.body,
            "bodyBase64" to response.bodyBase64
        )
    }

    private fun importResultToMap(result: HttpApiSpecImportService.ImportResult): Map<String, Any?> {
        return mapOf(
            "detectedSpecType" to result.detectedSpecType,
            "sourceType" to result.sourceType.name,
            "source" to result.source,
            "totalEndpoints" to result.totalEndpoints,
            "createdGroups" to result.createdGroups,
            "createdRequests" to result.createdRequests,
            "updatedRequests" to result.updatedRequests,
            "skippedRequests" to result.skippedRequests
        )
    }

    private fun keyValueListToMap(entries: List<HttpKeyValue>): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        entries.forEach { entry ->
            val key = entry.key.trim()
            if (key.isNotBlank()) {
                map[key] = entry.value
            }
        }
        return map
    }

    private fun keyValueListToMapWithDescription(entries: List<HttpKeyValue>): List<Map<String, String>> {
        return entries.filter { it.key.isNotBlank() }
            .map { entry ->
                mapOf(
                    "key" to entry.key,
                    "value" to entry.value,
                    "description" to entry.description
                )
            }
    }

    private fun resolveDraftForFunction(args: Map<String, Any?>): ResolvedDraft {
        val sourceTabId = args["sourceTabId"].asLongOrNull()
        val savedRequestId = args["savedRequestId"].asLongOrNull()
        if (sourceTabId != null && savedRequestId != null) {
            throw IllegalArgumentException("sourceTabId and savedRequestId cannot be used together")
        }
        var sourceType: HistorySourceType? = null
        var sourceId: Long? = null
        val baseDraft = when {
            sourceTabId != null -> {
                val tab = HttpCallTabStorage.loadTabs(project).firstOrNull { it.id == sourceTabId }
                    ?: throw IllegalArgumentException("tab not found: $sourceTabId")
                sourceType = if (tab.savedRequestId != null) HistorySourceType.SAVED else HistorySourceType.TAB
                sourceId = tab.savedRequestId ?: tab.id
                cloneDraft(tab.draft)
            }
            savedRequestId != null -> {
                val request = HttpApiStorage.loadRequests(project).firstOrNull { it.id == savedRequestId }
                    ?: throw IllegalArgumentException("saved request not found: $savedRequestId")
                sourceType = HistorySourceType.SAVED
                sourceId = request.id
                cloneDraft(request.draft)
            }
            else -> {
                parseDraftFromArgs(args)
                    ?: throw IllegalArgumentException("request or sourceTabId or savedRequestId is required")
            }
        }
        val patchedDraft = when {
            args.containsKey("request") || hasDraftKeys(args) -> {
                val patch = when {
                    args.containsKey("request") -> {
                        val request = args["request"]
                        if (request !is Map<*, *>) {
                            throw IllegalArgumentException("request must be an object")
                        }
                        request.toStringKeyMap()
                    }
                    else -> args
                }
                applyDraftPatch(baseDraft, patch)
            }
            else -> baseDraft
        }
        val normalized = resolveDraftForRequest(patchedDraft)
        if (normalized.url.isBlank()) {
            throw IllegalArgumentException("request.url is required")
        }
        return ResolvedDraft(normalized, sourceType, sourceId)
    }

    private fun resolveDraftForRequest(draft: HttpRequestDraft): HttpRequestDraft {
        val normalizedUrl = normalizeUrl(draft.url)
        val parts = parseUrl(normalizedUrl)
        val mergedParams = mergeParams(parts.queryParams, draft.params)
        val resolvedPath = applyPathVariables(parts.path, draft.pathParams)
        val baseUrl = replacePathInBaseUrl(parts.baseUrl, parts.path, resolvedPath)
        val finalUrl = buildUrl(baseUrl, mergedParams)
        return draft.copy(
            url = finalUrl,
            path = resolvedPath,
            params = mergedParams,
            timeoutSeconds = draft.timeoutSeconds.coerceIn(1, MAX_TIMEOUT_SECONDS)
        )
    }

    private fun buildHttpRequest(draft: HttpRequestDraft, persistCookies: Boolean): HttpRequest {
        val uri = URI(normalizeUrl(draft.url))
        val timeoutSeconds = draft.timeoutSeconds.coerceIn(1, MAX_TIMEOUT_SECONDS)
        val builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(timeoutSeconds.toLong()))
        val headers = draft.headers
            .filter { it.key.isNotBlank() }
            .map { HttpKeyValue(it.key, it.value) }
            .toMutableList()
        val hasContentType = hasHeader(headers, "Content-Type")
        val bodyType = parseBodyType(draft.bodyType)
        val body = draft.body?.takeIf { it.isNotBlank() }
        val payload = buildPayload(bodyType, draft, body)
        if (payload.contentType != null && !hasContentType) {
            builder.header("Content-Type", payload.contentType)
        }
        applyDefaultHeaders(headers)
        headers.filterNot { isRestrictedHeader(it.key) }
            .forEach { builder.header(it.key, it.value) }
        if (persistCookies && !hasHeader(headers, "Cookie")) {
            val cookieHeader = buildCookieHeader(draft.url)
            if (!cookieHeader.isNullOrBlank()) {
                builder.header("Cookie", cookieHeader)
            }
        }
        builder.method(draft.method.uppercase().ifBlank { "GET" }, payload.publisher)
        return builder.build()
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
        val proxyType = normalizeProxyType(settings.proxyType)
        val selector = if (proxyType == "SOCKS") {
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

    private fun normalizeProxyType(value: String?): String {
        return if (value?.trim()?.equals("SOCKS", ignoreCase = true) == true) "SOCKS" else "HTTP"
    }

    private fun applyDefaultHeaders(headers: MutableList<HttpKeyValue>) {
        val defaults = listOf(
            HttpKeyValue("Accept", "*/*"),
            HttpKeyValue("Accept-Encoding", "gzip, deflate"),
            HttpKeyValue("Accept-Language", "zh-CN,zh;q=0.9"),
            HttpKeyValue(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            )
        )
        defaults.forEach { header ->
            if (!hasHeader(headers, header.key)) {
                headers.add(header)
            }
        }
    }

    private fun buildPayload(
        bodyType: HttpBodyType,
        draft: HttpRequestDraft,
        jsonBody: String?
    ): Payload {
        return when (bodyType) {
            HttpBodyType.NONE -> Payload(HttpRequest.BodyPublishers.noBody(), null)
            HttpBodyType.JSON -> {
                if (jsonBody.isNullOrBlank()) {
                    Payload(HttpRequest.BodyPublishers.noBody(), null)
                } else {
                    Payload(HttpRequest.BodyPublishers.ofString(jsonBody), "application/json")
                }
            }
            HttpBodyType.FORM_URLENCODED -> {
                val body = buildUrlEncodedBody(draft.urlEncoded)
                if (body.isBlank()) {
                    Payload(HttpRequest.BodyPublishers.noBody(), null)
                } else {
                    Payload(HttpRequest.BodyPublishers.ofString(body), "application/x-www-form-urlencoded")
                }
            }
            HttpBodyType.FORM_DATA -> {
                val multipart = buildMultipartBody(draft.formFields)
                if (multipart == null) {
                    Payload(HttpRequest.BodyPublishers.noBody(), null)
                } else {
                    Payload(multipart.publisher, multipart.contentType)
                }
            }
        }
    }

    private fun buildUrlEncodedBody(fields: List<HttpKeyValue>): String {
        val pairs = fields.filter { it.key.isNotBlank() }
        if (pairs.isEmpty()) {
            return ""
        }
        return pairs.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
    }

    private fun buildMultipartBody(fields: List<HttpFormField>): MultipartPayload? {
        val normalized = fields.filter { it.key.isNotBlank() && it.value.isNotBlank() }
        if (normalized.isEmpty()) {
            return null
        }
        val boundary = "jtools-${UUID.randomUUID()}"
        val output = ByteArrayOutputStream()
        val lineBreak = "\r\n"
        for (field in normalized) {
            val type = parseFormFieldType(field.fieldType)
            output.write("--$boundary$lineBreak".toByteArray(StandardCharsets.UTF_8))
            if (type == HttpFormFieldType.FILE) {
                val path = runCatching { Paths.get(field.value) }.getOrNull()
                val filename = path?.fileName?.toString() ?: "file"
                output.write(
                    "Content-Disposition: form-data; name=\"${escapeMultipart(field.key)}\"; filename=\"$filename\"$lineBreak".toByteArray(
                        StandardCharsets.UTF_8
                    )
                )
                output.write("Content-Type: application/octet-stream$lineBreak$lineBreak".toByteArray(StandardCharsets.UTF_8))
                if (path != null && Files.exists(path)) {
                    output.write(Files.readAllBytes(path))
                } else {
                    output.write(field.value.toByteArray(StandardCharsets.UTF_8))
                }
                output.write(lineBreak.toByteArray(StandardCharsets.UTF_8))
            } else {
                output.write(
                    "Content-Disposition: form-data; name=\"${escapeMultipart(field.key)}\"$lineBreak$lineBreak".toByteArray(
                        StandardCharsets.UTF_8
                    )
                )
                output.write(field.value.toByteArray(StandardCharsets.UTF_8))
                output.write(lineBreak.toByteArray(StandardCharsets.UTF_8))
            }
        }
        output.write("--$boundary--$lineBreak".toByteArray(StandardCharsets.UTF_8))
        return MultipartPayload(
            HttpRequest.BodyPublishers.ofByteArray(output.toByteArray()),
            "multipart/form-data; boundary=$boundary"
        )
    }

    private fun parseFormFieldType(value: String): HttpFormFieldType {
        return if (value.equals("文件", ignoreCase = true) || value.equals(HttpFormFieldType.FILE.name, true)) {
            HttpFormFieldType.FILE
        } else {
            HttpFormFieldType.TEXT
        }
    }

    private fun escapeMultipart(value: String): String {
        return value.replace("\"", "\\\"")
    }

    private fun buildCurlCommand(draft: HttpRequestDraft): String {
        val headers = draft.headers
            .filter { it.key.isNotBlank() }
            .map { HttpKeyValue(it.key, it.value) }
            .toMutableList()
        if (!hasHeader(headers, "Cookie")) {
            val cookieHeader = buildCookieHeader(draft.url)
            if (!cookieHeader.isNullOrBlank()) {
                headers.add(HttpKeyValue("Cookie", cookieHeader))
            }
        }
        val builder = StringBuilder()
        builder.append("curl -X ").append(draft.method.uppercase().ifBlank { "GET" })
        builder.append(" ").append(quoteCurl(draft.url))
        headers.forEach { header ->
            builder.append(" -H ").append(quoteCurl("${header.key}: ${header.value}"))
        }
        when (parseBodyType(draft.bodyType)) {
            HttpBodyType.JSON -> {
                val body = draft.body?.trim().orEmpty()
                if (body.isNotBlank()) {
                    if (!hasHeader(headers, "Content-Type")) {
                        builder.append(" -H ").append(quoteCurl("Content-Type: application/json"))
                    }
                    builder.append(" --data ").append(quoteCurl(body))
                }
            }
            HttpBodyType.FORM_URLENCODED -> {
                val body = buildUrlEncodedBody(draft.urlEncoded)
                if (body.isNotBlank()) {
                    if (!hasHeader(headers, "Content-Type")) {
                        builder.append(" -H ").append(quoteCurl("Content-Type: application/x-www-form-urlencoded"))
                    }
                    builder.append(" --data ").append(quoteCurl(body))
                }
            }
            HttpBodyType.FORM_DATA -> {
                val fields = draft.formFields.filter { it.key.isNotBlank() && it.value.isNotBlank() }
                fields.forEach { field ->
                    val value = if (parseFormFieldType(field.fieldType) == HttpFormFieldType.FILE) "@${field.value}" else field.value
                    builder.append(" -F ").append(quoteCurl("${field.key}=$value"))
                }
            }
            HttpBodyType.NONE -> Unit
        }
        return builder.toString()
    }

    private fun quoteCurl(value: String): String {
        val escaped = value.replace("'", "'\"'\"'")
        return "'$escaped'"
    }

    private fun buildResponseSnapshot(
        response: HttpResponse<ByteArray>,
        start: Long,
        includeBody: Boolean,
        maxBodyChars: Int
    ): HttpResponseSnapshot {
        val headers = response.headers().map().flatMap { (name, values) ->
            values.map { value -> HttpKeyValue(name, value) }
        }.toMutableList()
        val requestHeaders = response.request().headers().map().flatMap { (name, values) ->
            values.map { value -> HttpKeyValue(name, value) }
        }.toMutableList()
        val requestUrl = response.request().uri().toString()
        val requestMethod = response.request().method()
        val requestParams = parseQuery(response.request().uri().rawQuery)
        val rawBytes = response.body() ?: ByteArray(0)
        val contentType = response.headers().firstValue("Content-Type").orElse("")
        val contentEncoding = response.headers().firstValue("Content-Encoding").orElse("")
        val (bytes, encodingUnsupported) = decodeContentEncoding(rawBytes, contentEncoding)
        val size = rawBytes.size.toLong()
        val charset = parseCharset(contentType) ?: StandardCharsets.UTF_8
        val isText = (contentType.isBlank() || isTextContent(contentType)) && !encodingUnsupported
        var bodyText: String? = null
        var bodyBase64: String? = null
        var truncated = false
        if (includeBody) {
            if (encodingUnsupported) {
                if (bytes.isNotEmpty()) {
                    val previewBytes = if (bytes.size > MAX_BINARY_PREVIEW_BYTES) {
                        truncated = true
                        bytes.copyOf(MAX_BINARY_PREVIEW_BYTES)
                    } else {
                        bytes
                    }
                    bodyBase64 = Base64.getEncoder().encodeToString(previewBytes)
                }
            } else if (isText) {
                val text = String(bytes, charset)
                bodyText = if (text.length > maxBodyChars) {
                    truncated = true
                    text.take(maxBodyChars)
                } else {
                    text
                }
            } else if (bytes.isNotEmpty()) {
                val previewBytes = if (bytes.size > MAX_BINARY_PREVIEW_BYTES) {
                    truncated = true
                    bytes.copyOf(MAX_BINARY_PREVIEW_BYTES)
                } else {
                    bytes
                }
                bodyBase64 = Base64.getEncoder().encodeToString(previewBytes)
            }
        }
        return HttpResponseSnapshot(
            status = response.statusCode(),
            statusText = "",
            durationMs = elapsedMs(start),
            sizeBytes = size,
            contentType = contentType,
            contentEncoding = contentEncoding,
            encodingUnsupported = encodingUnsupported,
            bodyTruncated = truncated,
            requestMethod = requestMethod,
            requestUrl = requestUrl,
            headers = headers,
            requestHeaders = requestHeaders,
            requestParams = requestParams,
            body = bodyText,
            bodyBase64 = bodyBase64
        )
    }

    private fun extractCookieMutations(response: HttpResponse<ByteArray>): List<RegistryCookieMutation> {
        val headers = response.headers().allValues("Set-Cookie")
        if (headers.isEmpty()) {
            return emptyList()
        }
        val host = response.uri().host ?: ""
        val now = System.currentTimeMillis()
        val mutations = mutableListOf<RegistryCookieMutation>()
        headers.forEach { header ->
            val parsed = runCatching { HttpCookie.parse(header) }.getOrDefault(emptyList())
            parsed.forEach { cookie ->
                val name = cookie.name ?: return@forEach
                val value = cookie.value ?: ""
                val domain = cookie.domain?.ifBlank { host } ?: host
                val path = cookie.path?.ifBlank { "/" } ?: "/"
                val maxAge = cookie.maxAge
                if (maxAge == 0L) {
                    mutations.add(RegistryCookieMutation(HttpCookieEntry(name, value, domain, path), true))
                    return@forEach
                }
                val expiresAt = if (maxAge > 0) now + maxAge * 1000 else 0
                val entry = HttpCookieEntry(
                    name = name,
                    value = value,
                    domain = domain,
                    path = path,
                    expiresAt = expiresAt,
                    secure = cookie.secure,
                    httpOnly = cookie.isHttpOnly
                )
                mutations.add(RegistryCookieMutation(entry, false))
            }
        }
        return mutations
    }

    private fun applyCookieMutations(mutations: List<RegistryCookieMutation>) {
        if (mutations.isEmpty()) {
            return
        }
        val now = System.currentTimeMillis()
        val entries = HttpCookieStorage.load(project)
        val map = LinkedHashMap<String, HttpCookieEntry>()
        entries.forEach { map[cookieKey(it)] = it }
        mutations.forEach { mutation ->
            val key = cookieKey(mutation.entry)
            if (mutation.remove || (mutation.entry.expiresAt > 0 && mutation.entry.expiresAt <= now)) {
                map.remove(key)
            } else {
                map[key] = mutation.entry
            }
        }
        HttpCookieStorage.save(project, map.values.toList())
    }

    private fun buildCookieHeader(url: String): String? {
        val uri = runCatching { URI(normalizeUrl(url)) }.getOrNull() ?: return null
        val host = uri.host ?: return null
        val path = uri.path?.ifBlank { "/" } ?: "/"
        val scheme = uri.scheme ?: "http"
        val now = System.currentTimeMillis()
        val entries = HttpCookieStorage.load(project)
        val cookies = entries.filter { cookieMatches(it, host, path, scheme, now) }
        if (cookies.isEmpty()) {
            return null
        }
        return cookies.joinToString("; ") { "${it.name}=${it.value}" }
    }

    private fun cookieMatches(
        entry: HttpCookieEntry,
        host: String,
        path: String,
        scheme: String,
        now: Long
    ): Boolean {
        if (host.isBlank()) {
            return false
        }
        if (entry.expiresAt > 0 && entry.expiresAt <= now) {
            return false
        }
        val domain = entry.domain.trimStart('.')
        if (domain.isNotBlank() && !host.endsWith(domain)) {
            return false
        }
        val cookiePath = entry.path.ifBlank { "/" }
        if (!path.startsWith(cookiePath)) {
            return false
        }
        if (entry.secure && scheme.lowercase() != "https") {
            return false
        }
        return true
    }

    private fun cookieKey(entry: HttpCookieEntry): String {
        val domain = entry.domain.trimStart('.')
        val path = entry.path.ifBlank { "/" }
        return "${entry.name}|$domain|$path"
    }

    private fun normalizeUrl(rawUrl: String): String {
        val url = rawUrl.trim()
        if (url.isBlank()) {
            return url
        }
        return if (url.contains("://")) url else "http://$url"
    }

    private fun parseUrl(url: String): UrlParts {
        if (url.isBlank()) {
            return UrlParts(url, "/", mutableListOf())
        }
        if (url.contains("{") || url.contains("}")) {
            return parseUrlLenient(url)
        }
        return runCatching {
            val uri = URI(url)
            val baseUrl = URI(uri.scheme, uri.authority, uri.path, null, uri.fragment).toString()
            val path = uri.path?.ifBlank { "/" } ?: "/"
            val queryParams = parseQuery(uri.rawQuery)
            UrlParts(baseUrl, path, queryParams)
        }.getOrElse {
            parseUrlLenient(url)
        }
    }

    private fun parseUrlLenient(url: String): UrlParts {
        val trimmed = url.trim()
        if (trimmed.isBlank()) {
            return UrlParts(trimmed, "/", mutableListOf())
        }
        val withoutFragment = trimmed.substringBefore("#")
        val queryIndex = withoutFragment.indexOf('?')
        val baseUrl = if (queryIndex >= 0) {
            withoutFragment.substring(0, queryIndex)
        } else {
            withoutFragment
        }
        val query = if (queryIndex >= 0 && queryIndex + 1 < withoutFragment.length) {
            withoutFragment.substring(queryIndex + 1)
        } else {
            null
        }
        val path = extractPathFromBaseUrl(baseUrl)
        val queryParams = parseQuery(query)
        return UrlParts(baseUrl, path, queryParams)
    }

    private fun extractPathFromBaseUrl(baseUrl: String): String {
        val schemeIndex = baseUrl.indexOf("://")
        val start = if (schemeIndex >= 0) schemeIndex + 3 else 0
        val pathIndex = baseUrl.indexOf('/', start)
        return if (pathIndex >= 0) baseUrl.substring(pathIndex).ifBlank { "/" } else "/"
    }

    private fun parseQuery(query: String?): MutableList<HttpKeyValue> {
        if (query.isNullOrBlank()) {
            return mutableListOf()
        }
        val items = mutableListOf<HttpKeyValue>()
        query.split("&").forEach { pair ->
            if (pair.isBlank()) {
                return@forEach
            }
            val parts = pair.split("=", limit = 2)
            val name = decode(parts[0])
            val value = if (parts.size > 1) decode(parts[1]) else ""
            items.add(HttpKeyValue(name, value))
        }
        return items
    }

    private fun mergeParams(fromUrl: List<HttpKeyValue>, fromTable: List<HttpKeyValue>): MutableList<HttpKeyValue> {
        val merged = linkedMapOf<String, HttpKeyValue>()
        fromUrl.filter { it.key.isNotBlank() }.forEach { merged[it.key] = it.copy() }
        fromTable.filter { it.key.isNotBlank() }.forEach { merged[it.key] = it.copy() }
        return merged.values.toMutableList()
    }

    private fun buildUrl(baseUrl: String, params: List<HttpKeyValue>): String {
        if (params.isEmpty() || baseUrl.isBlank()) {
            return baseUrl
        }
        val query = params.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
        return "$baseUrl?$query"
    }

    private fun applyPathVariables(path: String, pathParams: List<HttpKeyValue>): String {
        if (path.isBlank()) {
            return "/"
        }
        val replacements = pathParams
            .filter { it.key.isNotBlank() }
            .associate { it.key to it.value }
        if (replacements.isEmpty()) {
            return path
        }
        return PATH_VARIABLE_REGEX.replace(path) { match ->
            val name = match.groupValues[1]
            val value = replacements[name]
            if (value.isNullOrBlank()) match.value else encodePathSegment(value)
        }
    }

    private fun replacePathInBaseUrl(baseUrl: String, oldPath: String, newPath: String): String {
        if (oldPath.isBlank()) {
            return baseUrl
        }
        if (baseUrl.endsWith(oldPath)) {
            return baseUrl.dropLast(oldPath.length) + newPath
        }
        if (oldPath == "/" && baseUrl.isNotBlank() && !baseUrl.endsWith("/")) {
            return baseUrl + newPath
        }
        return baseUrl
    }

    private fun encode(value: String): String {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.toString())
    }

    private fun decode(value: String): String {
        return URLDecoder.decode(value, StandardCharsets.UTF_8.toString())
    }

    private fun encodePathSegment(value: String): String {
        return encode(value).replace("+", "%20")
    }

    private fun hasHeader(headers: List<HttpKeyValue>, name: String): Boolean {
        return headers.any { it.key.equals(name, ignoreCase = true) }
    }

    private fun isRestrictedHeader(name: String): Boolean {
        return RESTRICTED_HEADERS.contains(name.trim().lowercase())
    }

    private fun elapsedMs(start: Long): Long {
        return (System.nanoTime() - start) / 1_000_000
    }

    private fun decodeContentEncoding(bytes: ByteArray, encoding: String): Pair<ByteArray, Boolean> {
        if (bytes.isEmpty() || encoding.isBlank()) {
            return bytes to false
        }
        val tokens = encoding.split(',')
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
        if (tokens.isEmpty()) {
            return bytes to false
        }
        val unsupported = tokens.firstOrNull { token ->
            token != "gzip" && token != "x-gzip" && token != "deflate" && token != "identity"
        }
        if (unsupported != null) {
            return bytes to true
        }
        var current = bytes
        for (token in tokens.asReversed()) {
            when (token) {
                "gzip", "x-gzip" -> {
                    val decoded = runCatching { decodeGzip(current) }.getOrNull() ?: return bytes to true
                    current = decoded
                }
                "deflate" -> {
                    val decoded = runCatching { decodeDeflate(current) }.getOrNull() ?: return bytes to true
                    current = decoded
                }
                "identity" -> Unit
            }
        }
        return current to false
    }

    private fun decodeGzip(bytes: ByteArray): ByteArray {
        GZIPInputStream(ByteArrayInputStream(bytes)).use { stream ->
            return readAllBytes(stream)
        }
    }

    private fun decodeDeflate(bytes: ByteArray): ByteArray {
        val standard = runCatching {
            InflaterInputStream(ByteArrayInputStream(bytes)).use { stream ->
                readAllBytes(stream)
            }
        }.getOrNull()
        if (standard != null) {
            return standard
        }
        InflaterInputStream(ByteArrayInputStream(bytes), Inflater(true)).use { stream ->
            return readAllBytes(stream)
        }
    }

    private fun readAllBytes(stream: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        stream.copyTo(output)
        return output.toByteArray()
    }

    private fun parseCharset(contentType: String): java.nio.charset.Charset? {
        val lower = contentType.lowercase()
        val index = lower.indexOf("charset=")
        if (index == -1) {
            return null
        }
        val charsetValue = lower.substring(index + 8).trim().trim('"', '\'').substringBefore(';')
        return runCatching { java.nio.charset.Charset.forName(charsetValue) }.getOrNull()
    }

    private fun isTextContent(contentType: String): Boolean {
        val lower = contentType.lowercase()
        return lower.startsWith("text/") ||
            lower.contains("json") ||
            lower.contains("xml") ||
            lower.contains("html") ||
            lower.contains("javascript")
    }

    private fun buildExportContent(
        format: String,
        requests: List<HttpSavedRequest>,
        title: String,
        version: String,
        serverUrl: String
    ): ExportContent {
        return when (format) {
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

    private fun resolveOutputPath(savePath: String, format: String, defaultFileName: String): Path {
        val raw = savePath.trim()
        if (raw.isBlank()) {
            throw IllegalArgumentException("savePath is required")
        }
        val path = Paths.get(raw)
        val ext = when (format) {
            "html" -> "html"
            "pdf" -> "pdf"
            else -> "json"
        }
        val target = when {
            Files.exists(path) && Files.isDirectory(path) -> path.resolve(defaultFileName)
            raw.endsWith("/") || raw.endsWith("\\") -> path.resolve(defaultFileName)
            else -> path
        }
        val fileName = target.fileName?.toString().orEmpty()
        if (!fileName.contains(".")) {
            return target.resolveSibling("$fileName.$ext")
        }
        return target
    }

    private fun buildOpenApiDocument(
        requests: List<HttpSavedRequest>,
        title: String,
        version: String,
        serverUrl: String
    ): Map<String, Any?> {
        val paths = linkedMapOf<String, MutableMap<String, Any?>>()
        requests.forEach { request ->
            val draft = request.draft
            val path = normalizeDocPath(draft.path.ifBlank { extractDocPath(draft.url) })
            val method = normalizeHttpMethod(draft.method)
            val operation = linkedMapOf<String, Any?>(
                "summary" to request.name,
                "operationId" to "req_${request.id}_$method",
                "responses" to mapOf("200" to mapOf("description" to "OK"))
            )
            val parameters = mutableListOf<Map<String, Any?>>()
            draft.pathParams.filter { it.key.isNotBlank() }.forEach { param ->
                parameters.add(
                    mapOf(
                        "name" to param.key,
                        "in" to "path",
                        "required" to true,
                        "schema" to mapOf("type" to "string"),
                        "example" to param.value
                    )
                )
            }
            draft.params.filter { it.key.isNotBlank() }.forEach { param ->
                parameters.add(
                    mapOf(
                        "name" to param.key,
                        "in" to "query",
                        "required" to false,
                        "schema" to mapOf("type" to "string"),
                        "example" to param.value
                    )
                )
            }
            draft.headers.filter { it.key.isNotBlank() && !it.key.equals("Cookie", true) }.forEach { header ->
                parameters.add(
                    mapOf(
                        "name" to header.key,
                        "in" to "header",
                        "required" to false,
                        "schema" to mapOf("type" to "string"),
                        "example" to header.value
                    )
                )
            }
            if (parameters.isNotEmpty()) {
                operation["parameters"] = parameters
            }
            val requestBody = buildOpenApiRequestBody(draft)
            if (requestBody != null) {
                operation["requestBody"] = requestBody
            }
            val pathItem = paths.getOrPut(path) { linkedMapOf() }
            pathItem[method] = operation
        }
        val root = linkedMapOf<String, Any?>(
            "openapi" to "3.0.3",
            "info" to mapOf("title" to title, "version" to version),
            "paths" to paths
        )
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
                if (body.isBlank()) {
                    null
                } else {
                    mapOf(
                        "content" to mapOf(
                            "application/json" to mapOf(
                                "schema" to mapOf("type" to "string"),
                                "example" to body
                            )
                        )
                    )
                }
            }
            HttpBodyType.FORM_URLENCODED -> {
                val properties = draft.urlEncoded
                    .filter { it.key.isNotBlank() }
                    .associate { it.key to mapOf("type" to "string", "example" to it.value) }
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
        requests.forEach { request ->
            val draft = request.draft
            val path = normalizeDocPath(draft.path.ifBlank { extractDocPath(draft.url) })
            val method = normalizeHttpMethod(draft.method)
            val operation = linkedMapOf<String, Any?>(
                "summary" to request.name,
                "operationId" to "req_${request.id}_$method",
                "produces" to listOf("application/json"),
                "responses" to mapOf("200" to mapOf("description" to "OK"))
            )
            val parameters = mutableListOf<Map<String, Any?>>()
            draft.pathParams.filter { it.key.isNotBlank() }.forEach { param ->
                parameters.add(
                    mapOf(
                        "name" to param.key,
                        "in" to "path",
                        "required" to true,
                        "type" to "string",
                        "x-example" to param.value
                    )
                )
            }
            draft.params.filter { it.key.isNotBlank() }.forEach { param ->
                parameters.add(
                    mapOf(
                        "name" to param.key,
                        "in" to "query",
                        "required" to false,
                        "type" to "string",
                        "x-example" to param.value
                    )
                )
            }
            draft.headers.filter { it.key.isNotBlank() && !it.key.equals("Cookie", true) }.forEach { header ->
                parameters.add(
                    mapOf(
                        "name" to header.key,
                        "in" to "header",
                        "required" to false,
                        "type" to "string",
                        "x-example" to header.value
                    )
                )
            }
            when (parseBodyType(draft.bodyType)) {
                HttpBodyType.JSON -> {
                    val body = draft.body?.trim().orEmpty()
                    if (body.isNotBlank()) {
                        operation["consumes"] = listOf("application/json")
                        parameters.add(
                            mapOf(
                                "name" to "body",
                                "in" to "body",
                                "required" to false,
                                "schema" to mapOf("type" to "string"),
                                "x-example" to body
                            )
                        )
                    }
                }
                HttpBodyType.FORM_URLENCODED -> {
                    val fields = draft.urlEncoded.filter { it.key.isNotBlank() }
                    if (fields.isNotEmpty()) {
                        operation["consumes"] = listOf("application/x-www-form-urlencoded")
                        fields.forEach { field ->
                            parameters.add(
                                mapOf(
                                    "name" to field.key,
                                    "in" to "formData",
                                    "required" to false,
                                    "type" to "string",
                                    "x-example" to field.value
                                )
                            )
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
            val pathItem = paths.getOrPut(path) { linkedMapOf() }
            pathItem[method] = operation
        }
        val root = linkedMapOf<String, Any?>(
            "swagger" to "2.0",
            "info" to mapOf("title" to title, "version" to version),
            "paths" to paths
        )
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

    private fun appendKvTable(sb: StringBuilder, title: String, rows: List<HttpKeyValue>) {
        val items = rows.filter { it.key.isNotBlank() }
        if (items.isEmpty()) {
            return
        }
        sb.append("<h4>").append(escapeHtml(title)).append("</h4>")
        sb.append("<table><thead><tr><th>key</th><th>value</th></tr></thead><tbody>")
        items.forEach { row ->
            sb.append("<tr><td>").append(escapeHtml(row.key)).append("</td><td>")
                .append(escapeHtml(row.value)).append("</td></tr>")
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
                lines.add("  pathParams: ${draft.pathParams.joinToString(", ") { "${it.key}=${it.value}" }}")
            }
            if (draft.params.isNotEmpty()) {
                lines.add("  params: ${draft.params.joinToString(", ") { "${it.key}=${it.value}" }}")
            }
            if (draft.headers.isNotEmpty()) {
                lines.add("  headers: ${draft.headers.joinToString(", ") { "${it.key}=${it.value}" }}")
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
                        lines.add("  body(form-urlencoded): ${draft.urlEncoded.joinToString(", ") { "${it.key}=${it.value}" }}")
                    }
                }
                HttpBodyType.FORM_DATA -> {
                    if (draft.formFields.isNotEmpty()) {
                        lines.add("  body(form-data): ${draft.formFields.joinToString(", ") { "${it.key}=${it.value}[${it.fieldType}]" }}")
                    }
                }
                HttpBodyType.NONE -> Unit
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
        val normalized = if (withoutFragment.contains("://")) {
            withoutFragment
        } else {
            "http://$withoutFragment"
        }
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

    private fun listEnv(scope: String): Map<String, String> {
        return when (scope) {
            "project" -> HttpScriptEnvStore.loadProject(project)
            "global" -> HttpScriptEnvStore.loadGlobal()
            "merged", "" -> {
                val merged = LinkedHashMap(HttpScriptEnvStore.loadGlobal())
                merged.putAll(HttpScriptEnvStore.loadProject(project))
                merged
            }
            else -> throw IllegalArgumentException("scope must be project/global/merged")
        }
    }

    private fun getEnv(scope: String, key: String): String? {
        return when (scope) {
            "project" -> HttpScriptEnvStore.loadProject(project)[key]
            "global" -> HttpScriptEnvStore.loadGlobal()[key]
            "merged", "" -> {
                val projectValue = HttpScriptEnvStore.loadProject(project)[key]
                projectValue ?: HttpScriptEnvStore.loadGlobal()[key]
            }
            else -> throw IllegalArgumentException("scope must be project/global/merged")
        }
    }

    private fun parseBodyType(raw: String?): HttpBodyType {
        val normalized = raw?.trim()?.uppercase().orEmpty()
        return when (normalized) {
            "JSON" -> HttpBodyType.JSON
            "FORM_URLENCODED", "X-WWW-FORM-URLENCODED", "FORM-URLENCODED" -> HttpBodyType.FORM_URLENCODED
            "FORM_DATA", "FORM-DATA" -> HttpBodyType.FORM_DATA
            "NONE", "" -> HttpBodyType.NONE
            else -> HttpBodyType.NONE
        }
    }

    private fun parseHistorySourceType(raw: String?): HistorySourceType? {
        val value = raw?.trim()?.uppercase().orEmpty()
        if (value.isBlank()) {
            return null
        }
        return runCatching { HistorySourceType.valueOf(value) }.getOrNull()
    }

    private fun toFormFields(value: Any?): MutableList<HttpFormField> {
        val list = (value as? List<*>) ?: return mutableListOf()
        return list.mapNotNull { item ->
            val map = item.toStringKeyMap()
            val key = map["key"]?.toString()?.trim().orEmpty()
            val itemValue = map["value"]?.toString().orEmpty()
            if (key.isBlank()) {
                null
            } else {
                val type = map["type"]?.toString()?.trim()?.uppercase().orEmpty()
                val fieldType = if (type == HttpFormFieldType.FILE.name) HttpFormFieldType.FILE.name else HttpFormFieldType.TEXT.name
                HttpFormField(key = key, value = itemValue, fieldType = fieldType)
            }
        }.toMutableList()
    }

    private fun toKeyValueList(value: Any?): MutableList<HttpKeyValue> {
        if (value is List<*>) {
            return value.mapNotNull { item ->
                val map = item.toStringKeyMap()
                val key = map["key"]?.toString()?.trim().orEmpty()
                if (key.isBlank()) {
                    null
                } else {
                    HttpKeyValue(
                        key = key,
                        value = map["value"]?.toString().orEmpty(),
                        description = map["description"]?.toString().orEmpty()
                    )
                }
            }.toMutableList()
        }
        val map = value.toStringKeyMap()
        if (map.isEmpty()) {
            return mutableListOf()
        }
        return map.entries
            .mapNotNull { (k, v) ->
                val key = k.trim()
                if (key.isBlank()) {
                    null
                } else {
                    HttpKeyValue(key, v?.toString().orEmpty())
                }
            }
            .toMutableList()
    }

    private fun parseRequestPath(pathOrUrl: String): String? {
        val raw = pathOrUrl.trim()
        if (raw.isBlank()) {
            return null
        }
        if (raw.startsWith("/")) {
            return raw.substringBefore('?')
        }
        val normalized = if (raw.contains("://")) raw else "http://$raw"
        return runCatching {
            val path = URI(normalized).path?.ifBlank { "/" } ?: "/"
            path.substringBefore('?')
        }.getOrNull()
    }

    private fun parseArgs(raw: String): Map<String, Any?> {
        val text = raw.trim()
        if (text.isBlank()) {
            return emptyMap()
        }
        val engine = createJsonEngine()
            ?: throw IllegalStateException("JavaScript engine is unavailable for argument parsing")
        return runCatching {
            val bindings = engine.createBindings()
            bindings["__raw"] = text
            val value = engine.eval("Java.asJSONCompatible(JSON.parse(__raw))", bindings)
            value.toStringKeyMap()
        }.getOrElse { throwable ->
            throw IllegalArgumentException("invalid function arguments JSON", throwable)
        }
    }

    private fun createJsonEngine(): ScriptEngine? {
        val manager = ScriptEngineManager(HttpsFunctionCallingRegistry::class.java.classLoader)
        return manager.getEngineByName("JavaScript")
            ?: manager.getEngineByName("javascript")
            ?: manager.getEngineByName("js")
            ?: manager.getEngineByName("nashorn")
    }

    private fun success(data: Any?): String {
        return toJson(
            linkedMapOf(
                "ok" to true,
                "data" to data
            )
        )
    }

    private fun failure(message: String): String {
        return toJson(
            linkedMapOf(
                "ok" to false,
                "error" to message
            )
        )
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

    private fun Any?.toStringKeyMap(): Map<String, Any?> {
        val source = this as? Map<*, *> ?: return emptyMap()
        val result = LinkedHashMap<String, Any?>()
        source.forEach { (key, value) ->
            if (key != null) {
                result[key.toString()] = value
            }
        }
        return result
    }

    private fun Any?.asIntOrNull(): Int? {
        return when (this) {
            is Number -> this.toInt()
            is String -> this.trim().toIntOrNull()
            else -> null
        }
    }

    private fun Any?.asLongOrNull(): Long? {
        return when (this) {
            is Number -> this.toLong()
            is String -> this.trim().toLongOrNull()
            else -> null
        }
    }

    private fun Any?.asBooleanOrNull(): Boolean? {
        return when (this) {
            is Boolean -> this
            is String -> when (this.trim().lowercase()) {
                "true", "1", "yes", "y" -> true
                "false", "0", "no", "n" -> false
                else -> null
            }
            else -> null
        }
    }

    private fun Any?.asLongList(): List<Long> {
        val list = this as? List<*> ?: return emptyList()
        return list.mapNotNull { item ->
            when (item) {
                is Number -> item.toLong()
                is String -> item.trim().toLongOrNull()
                else -> null
            }
        }
    }

    private fun containsIgnoreCase(source: String?, keyword: String): Boolean {
        if (source == null) {
            return false
        }
        return source.contains(keyword, ignoreCase = true)
    }

    private data class ResolvedDraft(
        val draft: HttpRequestDraft,
        val sourceType: HistorySourceType?,
        val sourceId: Long?
    )

    private data class UrlParts(
        val baseUrl: String,
        val path: String,
        val queryParams: MutableList<HttpKeyValue>
    )

    private data class Payload(
        val publisher: HttpRequest.BodyPublisher,
        val contentType: String?
    )

    private data class MultipartPayload(
        val publisher: HttpRequest.BodyPublisher,
        val contentType: String
    )

    private data class RegistryCookieMutation(
        val entry: HttpCookieEntry,
        val remove: Boolean
    )

    private data class ExportContent(
        val format: String,
        val defaultFileName: String,
        val bytes: ByteArray
    )

    companion object {
        private const val MAX_TIMEOUT_SECONDS = 120
        private const val MAX_BINARY_PREVIEW_BYTES = 200_000
        private val DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        private val PATH_VARIABLE_REGEX = "\\{([^}]+)}".toRegex()
        private val RESTRICTED_HEADERS = setOf(
            "connection",
            "content-length",
            "expect",
            "host",
            "upgrade"
        )
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
        private val DRAFT_KEYS = setOf(
            "method",
            "url",
            "timeoutSeconds",
            "pathParams",
            "params",
            "headers",
            "bodyType",
            "body",
            "urlEncoded",
            "formData",
            "requestBodyParams",
            "responseStatus",
            "responseContentType",
            "responseDescription",
            "responseBody",
            "responseStatusDocs",
            "responseParams",
            "preScript",
            "postScript"
        )
    }
}
