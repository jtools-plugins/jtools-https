package com.lhstack.https

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.util.xmlb.annotations.Attribute
import com.intellij.util.xmlb.annotations.Tag
import com.intellij.util.xmlb.annotations.XCollection
import java.net.URI
import java.util.UUID

enum class EndpointSource {
    METHOD,
    ROUTER
}

data class EndpointInfo(
    val httpMethod: String,
    val path: String,
    val anchor: PsiElement,
    val psiMethod: PsiMethod?,
    val source: EndpointSource
) {
    val displayName: String
        get() = "${httpMethod} ${path}"
}

@Tag("kv")
data class HttpKeyValue(
    @Attribute var key: String = "",
    @Attribute var value: String = "",
    @Attribute var description: String = ""
)

enum class HttpBodyType {
    NONE,
    JSON,
    FORM_URLENCODED,
    FORM_DATA
}

enum class HttpFormFieldType {
    TEXT,
    FILE
}

@Tag("form-field")
data class HttpFormField(
    @Attribute var key: String = "",
    @Attribute var value: String = "",
    @Attribute var fieldType: String = HttpFormFieldType.TEXT.name
)

@Tag("annotation-meta")
data class HttpScriptAnnotationMeta(
    @Attribute var qualifiedName: String = "",
    @XCollection(style = XCollection.Style.v2, elementName = "attr")
    var attributes: MutableList<HttpKeyValue> = mutableListOf()
)

@Tag("parameter-meta")
data class HttpScriptParameterMeta(
    @Attribute var name: String = "",
    @Attribute var type: String = "",
    @XCollection(style = XCollection.Style.v2, elementName = "annotation")
    var annotations: MutableList<HttpScriptAnnotationMeta> = mutableListOf()
)

@Tag("method-descriptor")
data class HttpScriptMethodDescriptor(
    @Attribute var name: String = "",
    @Attribute var returnType: String = "",
    @Attribute var declaringClass: String = "",
    @XCollection(style = XCollection.Style.v2, elementName = "param-type")
    var parameterTypes: MutableList<String> = mutableListOf(),
    @XCollection(style = XCollection.Style.v2, elementName = "throw-type")
    var throwsTypes: MutableList<String> = mutableListOf(),
    @XCollection(style = XCollection.Style.v2, elementName = "modifier")
    var modifiers: MutableList<String> = mutableListOf()
)

@Tag("class-descriptor")
data class HttpScriptClassDescriptor(
    @Attribute var name: String = "",
    @Attribute var qualifiedName: String = "",
    @Attribute var superClass: String = "",
    @XCollection(style = XCollection.Style.v2, elementName = "interface")
    var interfaces: MutableList<String> = mutableListOf(),
    @XCollection(style = XCollection.Style.v2, elementName = "modifier")
    var modifiers: MutableList<String> = mutableListOf(),
    @XCollection(style = XCollection.Style.v2, elementName = "annotation")
    var annotations: MutableList<HttpScriptAnnotationMeta> = mutableListOf()
)

@Tag("endpoint-code-meta")
data class HttpEndpointCodeMeta(
    @Attribute var source: String = "",
    @XCollection(style = XCollection.Style.v2, elementName = "method-annotation")
    var methodAnnotations: MutableList<HttpScriptAnnotationMeta> = mutableListOf(),
    @XCollection(style = XCollection.Style.v2, elementName = "parameter")
    var parameters: MutableList<HttpScriptParameterMeta> = mutableListOf(),
    var methodBody: String? = null,
    var methodDescriptor: HttpScriptMethodDescriptor? = null,
    var classDescriptor: HttpScriptClassDescriptor? = null
)

@Tag("request")
data class HttpRequestDraft(
    @Attribute var method: String = "GET",
    @Attribute var url: String = "",
    @Attribute var path: String = "",
    @Attribute var moduleName: String = "",
    @Attribute var timeoutSeconds: Int = 10,
    @XCollection(style = XCollection.Style.v2, elementName = "request-var")
    var requestVars: MutableList<HttpKeyValue> = mutableListOf(),
    @XCollection(style = XCollection.Style.v2, elementName = "path-param")
    var pathParams: MutableList<HttpKeyValue> = mutableListOf(),
    @XCollection(style = XCollection.Style.v2, elementName = "param")
    var params: MutableList<HttpKeyValue> = mutableListOf(),
    @XCollection(style = XCollection.Style.v2, elementName = "header")
    var headers: MutableList<HttpKeyValue> = mutableListOf(),
    @Attribute var bodyType: String = HttpBodyType.NONE.name,
    @XCollection(style = XCollection.Style.v2, elementName = "urlencoded")
    var urlEncoded: MutableList<HttpKeyValue> = mutableListOf(),
    @XCollection(style = XCollection.Style.v2, elementName = "form")
    var formFields: MutableList<HttpFormField> = mutableListOf(),
    @XCollection(style = XCollection.Style.v2, elementName = "request-body-param")
    var requestBodyParams: MutableList<HttpKeyValue> = mutableListOf(),
    var body: String? = null,
    @Attribute var preScriptEnabled: Boolean = true,
    @Attribute var postScriptEnabled: Boolean = true,
    var codeMeta: HttpEndpointCodeMeta? = null,
    var preScript: String? = null,
    var postScript: String? = null,
    @Attribute var responseStatus: String = "",
    @Attribute var responseContentType: String = "",
    var responseDescription: String? = null,
    var responseBody: String? = null,
    @XCollection(style = XCollection.Style.v2, elementName = "response-status-doc")
    var responseStatusDocs: MutableList<HttpKeyValue> = mutableListOf(),
    @XCollection(style = XCollection.Style.v2, elementName = "response-param")
    var responseParams: MutableList<HttpKeyValue> = mutableListOf()
)

@Tag("cookie")
data class HttpCookieEntry(
    @Attribute var name: String = "",
    @Attribute var value: String = "",
    @Attribute var domain: String = "",
    @Attribute var path: String = "",
    @Attribute var expiresAt: Long = 0,
    @Attribute var secure: Boolean = false,
    @Attribute var httpOnly: Boolean = false
)

@Tag("cookie-state")
data class HttpCookieState(
    @XCollection(style = XCollection.Style.v2, elementName = "cookie")
    var entries: MutableList<HttpCookieEntry> = mutableListOf()
)

@Tag("response")
data class HttpResponseSnapshot(
    @Attribute var status: Int = 0,
    @Attribute var statusText: String = "",
    @Attribute var durationMs: Long = 0,
    @Attribute var sizeBytes: Long = 0,
    @Attribute var contentType: String = "",
    @Attribute var contentEncoding: String = "",
    @Attribute var encodingUnsupported: Boolean = false,
    @Attribute var bodyTruncated: Boolean = false,
    @Attribute var requestMethod: String = "",
    @Attribute var requestUrl: String = "",
    @XCollection(style = XCollection.Style.v2, elementName = "header")
    var headers: MutableList<HttpKeyValue> = mutableListOf(),
    @XCollection(style = XCollection.Style.v2, elementName = "request-header")
    var requestHeaders: MutableList<HttpKeyValue> = mutableListOf(),
    @XCollection(style = XCollection.Style.v2, elementName = "request-param")
    var requestParams: MutableList<HttpKeyValue> = mutableListOf(),
    var body: String? = null,
    var bodyBase64: String? = null
)

@Tag("entry")
data class HttpHistoryEntry(
    @Attribute var id: String = "",
    @Attribute var createdAt: Long = 0,
    @Attribute var name: String = "",
    var request: HttpRequestDraft = HttpRequestDraft(),
    var response: HttpResponseSnapshot? = null
) {
    val displayName: String
        get() = buildDisplayName()

    companion object {
        fun create(draft: HttpRequestDraft): HttpHistoryEntry {
            return HttpHistoryEntry(
                id = UUID.randomUUID().toString(),
                createdAt = System.currentTimeMillis(),
                request = draft,
                response = null
            )
        }
    }

    private fun resolvePath(): String {
        if (request.path.isNotBlank()) {
            return request.path
        }
        val url = request.url
        if (url.isBlank()) {
            return "/"
        }
        return try {
            URI(url).path?.ifBlank { "/" } ?: "/"
        } catch (_: Exception) {
            "/"
        }
    }

    private fun buildDisplayName(): String {
        val methodUrl = "${request.method} ${request.url.ifBlank { resolvePath() }}"
        return if (name.isBlank()) methodUrl else "$name | $methodUrl"
    }
}

@Tag("history-state")
data class HttpHistoryState(
    @XCollection(style = XCollection.Style.v2, elementName = "entry")
    var entries: MutableList<HttpHistoryEntry> = mutableListOf()
)
