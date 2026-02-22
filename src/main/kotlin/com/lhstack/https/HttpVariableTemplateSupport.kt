package com.lhstack.https

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.project.Project

data class HttpVariableTemplateSettings(
    var templateEnabled: Boolean = true,
    var unresolvedPolicy: String = UnresolvedPolicy.KEEP.name,
    var unscopedResolveOrder: String = ResolveOrder.REQUEST_PROJECT_GLOBAL.name
) {
    enum class UnresolvedPolicy {
        KEEP,
        ERROR
    }

    enum class ResolveOrder {
        REQUEST_PROJECT_GLOBAL,
        PROJECT_GLOBAL_REQUEST
    }

    fun unresolvedPolicyEnum(): UnresolvedPolicy {
        return runCatching { UnresolvedPolicy.valueOf(unresolvedPolicy.trim().uppercase()) }
            .getOrDefault(UnresolvedPolicy.KEEP)
    }

    fun resolveOrderEnum(): ResolveOrder {
        return runCatching { ResolveOrder.valueOf(unscopedResolveOrder.trim().uppercase()) }
            .getOrDefault(ResolveOrder.REQUEST_PROJECT_GLOBAL)
    }
}

object HttpVariableTemplateSettingsStore {
    private const val KEY_TEMPLATE_ENABLED = "jtools.https.template.enabled"
    private const val KEY_UNRESOLVED_POLICY = "jtools.https.template.unresolvedPolicy"
    private const val KEY_UNSCOPED_ORDER = "jtools.https.template.unscopedOrder"

    fun load(project: Project): HttpVariableTemplateSettings {
        val properties = PropertiesComponent.getInstance(project)
        return HttpVariableTemplateSettings(
            templateEnabled = properties.getBoolean(KEY_TEMPLATE_ENABLED, true),
            unresolvedPolicy = properties.getValue(
                KEY_UNRESOLVED_POLICY,
                HttpVariableTemplateSettings.UnresolvedPolicy.KEEP.name
            ) ?: HttpVariableTemplateSettings.UnresolvedPolicy.KEEP.name,
            unscopedResolveOrder = properties.getValue(
                KEY_UNSCOPED_ORDER,
                HttpVariableTemplateSettings.ResolveOrder.REQUEST_PROJECT_GLOBAL.name
            ) ?: HttpVariableTemplateSettings.ResolveOrder.REQUEST_PROJECT_GLOBAL.name
        )
    }

    fun save(project: Project, settings: HttpVariableTemplateSettings) {
        val properties = PropertiesComponent.getInstance(project)
        properties.setValue(KEY_TEMPLATE_ENABLED, settings.templateEnabled, true)
        properties.setValue(KEY_UNRESOLVED_POLICY, settings.unresolvedPolicyEnum().name)
        properties.setValue(KEY_UNSCOPED_ORDER, settings.resolveOrderEnum().name)
    }
}

object HttpVariableTemplateResolver {
    private val templateRegex = Regex("\\{\\{\\s*([^{}]+?)\\s*\\}\\}")

    fun resolveDraft(
        project: Project,
        draft: HttpRequestDraft,
        settings: HttpVariableTemplateSettings = HttpVariableTemplateSettingsStore.load(project)
    ): HttpRequestDraft {
        if (!settings.templateEnabled) {
            return cloneDraft(draft)
        }
        val projectEnv = HttpScriptEnvStore.loadProject(project)
        val globalEnv = HttpScriptEnvStore.loadGlobal()
        val requestVars = draft.requestVars
            .filter { it.key.isNotBlank() }
            .associate { it.key.trim() to it.value }
        val pathVars = draft.pathParams
            .filter { it.key.isNotBlank() }
            .associate { it.key.trim() to it.value }

        val unresolved = linkedSetOf<String>()
        val resolveOrder = settings.resolveOrderEnum()
        val unresolvedPolicy = settings.unresolvedPolicyEnum()

        fun resolveUnscoped(name: String): String? {
            if (name.isBlank()) {
                return null
            }
            return when (resolveOrder) {
                HttpVariableTemplateSettings.ResolveOrder.REQUEST_PROJECT_GLOBAL -> {
                    requestVars[name] ?: projectEnv[name] ?: globalEnv[name]
                }
                HttpVariableTemplateSettings.ResolveOrder.PROJECT_GLOBAL_REQUEST -> {
                    projectEnv[name] ?: globalEnv[name] ?: requestVars[name]
                }
            }
        }

        fun resolveExpression(expression: String): String? {
            val expr = expression.trim()
            if (expr.isBlank()) {
                return null
            }
            resolveUnscoped(expr)?.let { return it }
            val dot = expr.indexOf('.')
            if (dot <= 0 || dot >= expr.length - 1) {
                return null
            }
            val namespace = expr.substring(0, dot).trim().lowercase()
            val key = expr.substring(dot + 1).trim()
            if (key.isBlank()) {
                return null
            }
            return when (namespace) {
                "env" -> projectEnv[key] ?: globalEnv[key]
                "project" -> projectEnv[key]
                "global" -> globalEnv[key]
                "api", "request", "var", "vars" -> requestVars[key]
                "path" -> pathVars[key]
                else -> null
            }
        }

        fun replaceText(source: String?): String? {
            if (source == null || source.isBlank()) {
                return source
            }
            return templateRegex.replace(source) { match ->
                val expression = match.groupValues.getOrElse(1) { "" }.trim()
                val value = resolveExpression(expression)
                if (value != null) {
                    value
                } else {
                    unresolved.add(expression)
                    match.value
                }
            }
        }

        val resolved = cloneDraft(draft)
        resolved.url = replaceText(resolved.url).orEmpty()
        resolved.pathParams = resolved.pathParams.map { row ->
            row.copy(value = replaceText(row.value).orEmpty())
        }.toMutableList()
        resolved.params = resolved.params.map { row ->
            row.copy(value = replaceText(row.value).orEmpty())
        }.toMutableList()
        resolved.headers = resolved.headers.map { row ->
            row.copy(value = replaceText(row.value).orEmpty())
        }.toMutableList()
        resolved.urlEncoded = resolved.urlEncoded.map { row ->
            row.copy(value = replaceText(row.value).orEmpty())
        }.toMutableList()
        resolved.formFields = resolved.formFields.map { row ->
            HttpFormField(
                key = row.key,
                value = replaceText(row.value).orEmpty(),
                fieldType = row.fieldType
            )
        }.toMutableList()
        resolved.body = replaceText(resolved.body)

        if (unresolvedPolicy == HttpVariableTemplateSettings.UnresolvedPolicy.ERROR && unresolved.isNotEmpty()) {
            throw IllegalArgumentException("未解析变量: ${unresolved.joinToString(", ")}")
        }
        return resolved
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
}

