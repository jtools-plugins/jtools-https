package com.lhstack.https

import com.intellij.codeInsight.AnnotationUtil
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiAnnotationMemberValue
import com.intellij.psi.PsiArrayType
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiEnumConstant
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiPrimitiveType
import com.intellij.psi.PsiType
import com.intellij.psi.util.InheritanceUtil
import com.intellij.psi.util.PsiTypesUtil
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom

object HttpRequestSampleBuilder {
    private const val DEFAULT_PORT = 8080

    private const val REQUEST_PARAM = "org.springframework.web.bind.annotation.RequestParam"
    private const val PATH_VARIABLE = "org.springframework.web.bind.annotation.PathVariable"
    private const val REQUEST_BODY = "org.springframework.web.bind.annotation.RequestBody"
    private const val REQUEST_HEADER = "org.springframework.web.bind.annotation.RequestHeader"
    private const val REQUEST_PART = "org.springframework.web.bind.annotation.RequestPart"
    private const val MATRIX_VARIABLE = "org.springframework.web.bind.annotation.MatrixVariable"

    fun build(project: com.intellij.openapi.project.Project, endpoint: EndpointInfo): HttpRequestDraft {
        val port = HttpPluginContext.getPort(project) ?: DEFAULT_PORT
        val baseUrl = "http://localhost:$port"

        val pathVariables = linkedMapOf<String, String>()
        val queryParams = linkedMapOf<String, String>()
        val bodyParams = mutableListOf<BodyParam>()
        val headerParams = mutableListOf<HttpKeyValue>()
        var hasMultipartBody = false

        val psiMethod = endpoint.psiMethod
        if (psiMethod != null) {
            for (parameter in psiMethod.parameterList.parameters) {
                val descriptor = resolveParamDescriptor(parameter, endpoint.httpMethod)
                val annotation = findParamAnnotation(parameter)
                val qualifiedName = annotation?.qualifiedName
                val isRequestPart = qualifiedName == REQUEST_PART
                val mockValue = buildMockValue(parameter.type, 0)
                val isFile = isFileType(parameter.type)
                when (descriptor.kind) {
                    ParamKind.PATH -> {
                        pathVariables[descriptor.name] = mockValue.toQueryValue()
                    }
                    ParamKind.QUERY -> {
                        queryParams[descriptor.name] = mockValue.toQueryValue()
                    }
                    ParamKind.BODY -> {
                        bodyParams.add(BodyParam(descriptor.name, mockValue, isFile))
                        if (isFile || isRequestPart) {
                            hasMultipartBody = true
                        }
                    }
                    ParamKind.HEADER -> {
                        headerParams.add(HttpKeyValue(descriptor.name, mockValue.toQueryValue()))
                    }
                }
            }
        }

        val templatePath = endpoint.path
        val placeholderRegex = "\\{([^}]+)}".toRegex()
        val knownPathVars = placeholderRegex.findAll(templatePath).map { it.groupValues[1] }.toList()
        for (name in knownPathVars) {
            if (!pathVariables.containsKey(name)) {
                pathVariables[name] = MockRandom.string()
            }
        }

        val url = baseUrl + templatePath

        val bodyType = resolveBodyType(bodyParams, hasMultipartBody)
        val body = if (bodyType == HttpBodyType.JSON) buildBody(bodyParams) else null
        val headers = mutableListOf<HttpKeyValue>()
        headers.add(HttpKeyValue("Accept", "application/json"))
        headerParams.forEach { header ->
            if (header.key.isNotBlank()) {
                headers.add(header)
            }
        }
        if (bodyType == HttpBodyType.JSON && !body.isNullOrBlank()) {
            headers.add(HttpKeyValue("Content-Type", "application/json"))
        }

        val params = queryParams.entries.map { HttpKeyValue(it.key, it.value) }.toMutableList()
        val pathParams = pathVariables.entries.map { HttpKeyValue(it.key, it.value) }.toMutableList()
        val formFields = if (bodyType == HttpBodyType.FORM_DATA) {
            bodyParams.map { param ->
                val fieldType = if (param.isFile) HttpFormFieldType.FILE.name else HttpFormFieldType.TEXT.name
                HttpFormField(param.name, param.value.toQueryValue(), fieldType)
            }.toMutableList()
        } else {
            mutableListOf()
        }

        return HttpRequestDraft(
            method = endpoint.httpMethod,
            url = url,
            path = templatePath,
            pathParams = pathParams,
            params = params,
            headers = headers,
            bodyType = bodyType.name,
            formFields = formFields,
            body = body
        )
    }

    private fun resolveParamDescriptor(parameter: PsiParameter, httpMethod: String): ParamDescriptor {
        val annotation = findParamAnnotation(parameter)
        val qualifiedName = annotation?.qualifiedName
        val name = resolveParamName(parameter, annotation)
        val kind = when (qualifiedName) {
            PATH_VARIABLE -> ParamKind.PATH
            REQUEST_PARAM, MATRIX_VARIABLE -> {
                if (qualifiedName == REQUEST_PARAM && isFileType(parameter.type)) ParamKind.BODY else ParamKind.QUERY
            }
            REQUEST_HEADER -> ParamKind.HEADER
            REQUEST_BODY, REQUEST_PART -> ParamKind.BODY
            else -> null
        }
        val finalKind = kind ?: run {
            if (isFileType(parameter.type)) {
                ParamKind.BODY
            } else if (httpMethod == "GET" || httpMethod == "DELETE") {
                ParamKind.QUERY
            } else {
                if (isSimpleType(parameter.type)) ParamKind.QUERY else ParamKind.BODY
            }
        }
        return ParamDescriptor(finalKind, name)
    }

    private fun findParamAnnotation(parameter: PsiParameter): PsiAnnotation? {
        return AnnotationUtil.findAnnotation(
            parameter,
            PATH_VARIABLE,
            REQUEST_PARAM,
            MATRIX_VARIABLE,
            REQUEST_HEADER,
            REQUEST_BODY,
            REQUEST_PART
        )
    }

    private fun resolveParamName(parameter: PsiParameter, annotation: PsiAnnotation?): String {
        if (annotation != null) {
            val name = resolveAnnotationName(annotation)
            if (!name.isNullOrBlank()) {
                return name
            }
        }
        return parameter.name ?: "param"
    }

    private fun resolveAnnotationName(annotation: PsiAnnotation): String? {
        val declared = resolveDeclaredAttribute(annotation, "value")
            ?: resolveDeclaredAttribute(annotation, "name")
        if (!declared.isNullOrBlank()) {
            return declared
        }
        val fallback = AnnotationUtil.getStringAttributeValue(annotation, "value")
            ?: AnnotationUtil.getStringAttributeValue(annotation, "name")
        return fallback?.takeIf { it.isNotBlank() }
    }

    private fun resolveDeclaredAttribute(annotation: PsiAnnotation, name: String): String? {
        val declared = annotation.findDeclaredAttributeValue(name) ?: return null
        return resolveStringValue(declared)
    }

    private fun resolveStringValue(value: PsiAnnotationMemberValue): String? {
        val direct = AnnotationUtil.getStringAttributeValue(value)
        if (direct != null) {
            return direct
        }
        return try {
            JavaPsiFacade.getInstance(value.project)
                .constantEvaluationHelper
                .computeConstantExpression(value) as? String
        } catch (_: Exception) {
            null
        }
    }

    private fun buildBody(bodyParams: List<BodyParam>): String? {
        if (bodyParams.isEmpty()) {
            return null
        }
        if (bodyParams.size == 1) {
            return formatJson(bodyParams[0].value)
        }
        val composite = linkedMapOf<String, MockValue>()
        for (param in bodyParams) {
            composite[param.name] = param.value
        }
        return formatJson(composite)
    }

    private fun buildMockValue(type: PsiType, depth: Int): MockValue {
        if (depth > 2) {
            return MockValue.StringValue(MockRandom.string())
        }
        if (type is PsiPrimitiveType) {
            return MockValue.fromPrimitive(type.canonicalText)
        }
        if (type is PsiArrayType) {
            return MockValue.ArrayValue(listOf(buildMockValue(type.componentType, depth + 1)))
        }
        val psiClass = PsiTypesUtil.getPsiClass(type)
        if (psiClass != null) {
            val qualifiedName = psiClass.qualifiedName
            if (qualifiedName != null) {
                val simple = simpleValueForQualifiedName(qualifiedName)
                if (simple != null) {
                    return simple
                }
                if (InheritanceUtil.isInheritor(psiClass, "java.util.Collection") ||
                    InheritanceUtil.isInheritor(psiClass, "kotlin.collections.Collection")
                ) {
                    val itemType = (type as? PsiClassType)?.parameters?.firstOrNull()
                    val itemValue = buildMockValue(itemType ?: PsiType.getJavaLangObject(psiClass.manager, psiClass.resolveScope), depth + 1)
                    return MockValue.ArrayValue(listOf(itemValue))
                }
                if (InheritanceUtil.isInheritor(psiClass, "java.util.Map") ||
                    InheritanceUtil.isInheritor(psiClass, "kotlin.collections.Map")
                ) {
                    val valueType = (type as? PsiClassType)?.parameters?.getOrNull(1)
                    val value = buildMockValue(valueType ?: PsiType.getJavaLangObject(psiClass.manager, psiClass.resolveScope), depth + 1)
                    return MockValue.ObjectValue(linkedMapOf("key" to value))
                }
                if (psiClass.isEnum) {
                    val constants = psiClass.fields.filterIsInstance<PsiEnumConstant>()
                    val enumConstant = if (constants.isNotEmpty()) {
                        constants[MockRandom.index(constants.size)].name
                    } else {
                        null
                    }
                    return MockValue.StringValue(enumConstant ?: "ENUM")
                }
            }
            return MockValue.ObjectValue(buildObjectFields(psiClass, depth + 1))
        }
        return MockValue.StringValue(MockRandom.string())
    }

    private fun buildObjectFields(psiClass: PsiClass, depth: Int): Map<String, MockValue> {
        val result = linkedMapOf<String, MockValue>()
        for (field in psiClass.allFields) {
            if (field.hasModifierProperty(PsiModifier.STATIC)) {
                continue
            }
            val name = field.name ?: continue
            result[name] = buildMockValue(field.type, depth)
        }
        return result
    }

    private fun simpleValueForQualifiedName(qualifiedName: String): MockValue? {
        return when (qualifiedName) {
            "java.lang.String", "kotlin.String", "java.lang.CharSequence" -> MockValue.StringValue(MockRandom.string())
            "java.lang.Boolean", "kotlin.Boolean" -> MockValue.BooleanValue(MockRandom.boolean())
            "java.lang.Integer", "java.lang.Long", "java.lang.Short", "java.lang.Byte",
            "kotlin.Int", "kotlin.Long", "kotlin.Short", "kotlin.Byte" -> MockValue.NumberValue(MockRandom.int())
            "java.lang.Double", "java.lang.Float", "kotlin.Double", "kotlin.Float" -> MockValue.NumberValue(MockRandom.double())
            "java.util.UUID" -> MockValue.StringValue(UUID.randomUUID().toString())
            "java.time.LocalDate" -> MockValue.StringValue(MockRandom.date())
            "java.time.LocalDateTime" -> MockValue.StringValue(MockRandom.dateTime())
            "java.time.Instant" -> MockValue.StringValue(MockRandom.instant())
            "java.io.File", "org.springframework.web.multipart.MultipartFile",
            "org.springframework.core.io.Resource" -> MockValue.StringValue("<file>")
            else -> null
        }
    }

    private fun formatJson(value: MockValue): String {
        return renderJson(value, 0)
    }

    private fun formatJson(value: Map<String, MockValue>): String {
        return renderJson(MockValue.ObjectValue(value), 0)
    }

    private fun resolveBodyType(bodyParams: List<BodyParam>, hasMultipartBody: Boolean): HttpBodyType {
        if (bodyParams.isEmpty()) {
            return HttpBodyType.NONE
        }
        return if (hasMultipartBody) HttpBodyType.FORM_DATA else HttpBodyType.JSON
    }

    private fun renderJson(value: MockValue, indentLevel: Int): String {
        val indent = "  ".repeat(indentLevel)
        val nextIndent = "  ".repeat(indentLevel + 1)
        return when (value) {
            is MockValue.StringValue -> "\"${escapeJson(value.value)}\""
            is MockValue.NumberValue -> value.value.toString()
            is MockValue.BooleanValue -> value.value.toString()
            is MockValue.ArrayValue -> {
                if (value.items.isEmpty()) {
                    "[]"
                } else {
                    val inner = value.items.joinToString(",\n") { item ->
                        nextIndent + renderJson(item, indentLevel + 1)
                    }
                    "[\n$inner\n$indent]"
                }
            }
            is MockValue.ObjectValue -> {
                if (value.entries.isEmpty()) {
                    "{}"
                } else {
                    val inner = value.entries.entries.joinToString(",\n") { (key, item) ->
                        nextIndent + "\"${escapeJson(key)}\": " + renderJson(item, indentLevel + 1)
                    }
                    "{\n$inner\n$indent}"
                }
            }
        }
    }

    private fun escapeJson(value: String): String {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
    }

    private data class BodyParam(val name: String, val value: MockValue, val isFile: Boolean)

    private enum class ParamKind {
        PATH,
        QUERY,
        BODY,
        HEADER
    }

    private sealed class MockValue {
        data class StringValue(val value: String) : MockValue()
        data class NumberValue(val value: Number) : MockValue()
        data class BooleanValue(val value: Boolean) : MockValue()
        data class ArrayValue(val items: List<MockValue>) : MockValue()
        data class ObjectValue(val entries: Map<String, MockValue>) : MockValue()

        fun toQueryValue(): String {
            return when (this) {
                is StringValue -> value
                is NumberValue -> value.toString()
                is BooleanValue -> value.toString()
                is ArrayValue -> items.firstOrNull()?.toQueryValue() ?: ""
                is ObjectValue -> "{}"
            }
        }

        companion object {
            fun fromPrimitive(primitiveName: String): MockValue {
                return when (primitiveName) {
                    "boolean" -> BooleanValue(MockRandom.boolean())
                    "byte", "short", "int", "long" -> NumberValue(MockRandom.int())
                    "float", "double" -> NumberValue(MockRandom.double())
                    "char" -> StringValue(MockRandom.char())
                    else -> StringValue(MockRandom.string())
                }
            }
        }
    }

    private data class ParamDescriptor(
        val kind: ParamKind,
        val name: String
    )

    private object MockRandom {
        private const val ALPHANUM = "abcdefghijklmnopqrstuvwxyz0123456789"

        fun boolean(): Boolean = ThreadLocalRandom.current().nextBoolean()

        fun int(min: Int = 1, max: Int = 1000): Int {
            return ThreadLocalRandom.current().nextInt(min, max + 1)
        }

        fun double(min: Double = 1.0, max: Double = 100.0): Double {
            return ThreadLocalRandom.current().nextDouble(min, max)
        }

        fun string(length: Int = 6): String {
            val builder = StringBuilder(length)
            repeat(length) {
                val index = ThreadLocalRandom.current().nextInt(ALPHANUM.length)
                builder.append(ALPHANUM[index])
            }
            return builder.toString()
        }

        fun char(): String {
            val index = ThreadLocalRandom.current().nextInt(26)
            return ('a' + index).toString()
        }

        fun index(size: Int): Int = ThreadLocalRandom.current().nextInt(size)

        fun date(): String {
            val year = 2020 + int(0, 5)
            val month = int(1, 12)
            val day = int(1, 28)
            return "${year}-${pad(month)}-${pad(day)}"
        }

        fun dateTime(): String {
            val date = date()
            val hour = int(0, 23)
            val minute = int(0, 59)
            val second = int(0, 59)
            return "${date}T${pad(hour)}:${pad(minute)}:${pad(second)}"
        }

        fun instant(): String {
            return "${dateTime()}Z"
        }

        private fun pad(value: Int): String {
            return value.toString().padStart(2, '0')
        }
    }

    private fun isSimpleType(type: PsiType): Boolean {
        if (type is PsiPrimitiveType) {
            return true
        }
        val psiClass = PsiTypesUtil.getPsiClass(type) ?: return false
        val qualifiedName = psiClass.qualifiedName ?: return false
        return simpleValueForQualifiedName(qualifiedName) != null
    }

    private fun isFileType(type: PsiType): Boolean {
        if (type is PsiArrayType) {
            return isFileType(type.componentType)
        }
        val psiClass = PsiTypesUtil.getPsiClass(type) ?: return false
        val qualifiedName = psiClass.qualifiedName ?: return false
        if (isRawFileType(qualifiedName)) {
            return true
        }
        if (InheritanceUtil.isInheritor(psiClass, "java.util.Collection") ||
            InheritanceUtil.isInheritor(psiClass, "kotlin.collections.Collection") ||
            InheritanceUtil.isInheritor(psiClass, "java.lang.Iterable") ||
            InheritanceUtil.isInheritor(psiClass, "kotlin.collections.Iterable")
        ) {
            val itemType = (type as? PsiClassType)?.parameters?.firstOrNull() ?: return false
            return isFileType(itemType)
        }
        return false
    }

    private fun isRawFileType(qualifiedName: String): Boolean {
        return qualifiedName == "java.io.File" ||
            qualifiedName == "org.springframework.web.multipart.MultipartFile" ||
            qualifiedName == "org.springframework.core.io.Resource"
    }
}
