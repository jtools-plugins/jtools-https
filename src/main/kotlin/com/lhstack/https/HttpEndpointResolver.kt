package com.lhstack.https

import com.intellij.codeInsight.AnnotationUtil
import com.intellij.psi.*
import org.jetbrains.uast.*

object HttpEndpointResolver {
    private const val REQUEST_MAPPING = "org.springframework.web.bind.annotation.RequestMapping"

    private val directMappings = mapOf(
        "org.springframework.web.bind.annotation.GetMapping" to "GET",
        "org.springframework.web.bind.annotation.PostMapping" to "POST",
        "org.springframework.web.bind.annotation.PutMapping" to "PUT",
        "org.springframework.web.bind.annotation.DeleteMapping" to "DELETE",
        "org.springframework.web.bind.annotation.PatchMapping" to "PATCH"
    )

    private val allMappingAnnotations = directMappings.keys + REQUEST_MAPPING

    private val routerHttpMethods = setOf(
        "GET",
        "POST",
        "PUT",
        "DELETE",
        "PATCH",
        "HEAD",
        "OPTIONS"
    )

    fun findEndpoint(element: PsiElement): EndpointInfo? {
        if(element is PsiMethod) {
            val method = element.toUElementOfExpectedTypes(UMethod::class.java) ?: return null
            return fromMethod(method, element,false)
        }
        val uMethod = element.getUastParentOfType(UMethod::class.java, false)
        if (uMethod != null) {
            return fromMethod(uMethod, element, true)
        }
        val callExpression: UCallExpression? =
            element.getUastParentOfType(UCallExpression::class.java, false)
        if (callExpression != null) {
            return fromRouterCall(callExpression, element)
        }


        return null
    }

    fun findEndpointInMethod(element: PsiElement): EndpointInfo? {
        val uMethod = element.getUastParentOfType(UMethod::class.java, false) ?: return null
        return fromMethod(uMethod, element, false)
    }

    private fun fromMethod(
        uMethod: UMethod,
        element: PsiElement,
        requireAnchor: Boolean
    ): EndpointInfo? {
        val psiMethod = uMethod.javaPsi
        val mappingAnnotation = findMappingAnnotation(psiMethod) ?: return null

        val httpMethods = extractHttpMethods(mappingAnnotation)
        val httpMethod = httpMethods.firstOrNull() ?: "GET"

        val methodPath = extractPaths(mappingAnnotation).firstOrNull()
        val classPath = extractClassPath(psiMethod, uMethod)
        val fullPath = combinePaths(classPath, methodPath)

        val anchor = uMethod.uastAnchor?.sourcePsi ?: element
        if (requireAnchor && anchor != element) {
            return null
        }

        return EndpointInfo(
            httpMethod = httpMethod,
            path = fullPath,
            anchor = anchor,
            psiMethod = psiMethod,
            source = EndpointSource.METHOD
        )
    }


    private fun fromRouterCall(call: UCallExpression, element: PsiElement): EndpointInfo? {
        val methodName = call.methodName ?: return null
        if (!routerHttpMethods.contains(methodName)) {
            return null
        }
        if (!isRouterFunctionCall(call)) {
            return null
        }
        val methodIdentifier = call.methodIdentifier?.sourcePsi ?: call.sourcePsi
        if (methodIdentifier != element) {
            return null
        }

        val pathExpression = call.valueArguments.firstOrNull() ?: return null
        val path = evaluateString(pathExpression) ?: return null

        return EndpointInfo(
            httpMethod = methodName,
            path = normalizePath(path) ?: "/",
            anchor = element,
            psiMethod = null,
            source = EndpointSource.ROUTER
        )
    }

    private fun findMappingAnnotation(owner: PsiModifierListOwner): PsiAnnotation? {
        return AnnotationUtil.findAnnotation(owner, allMappingAnnotations)
    }

    private fun extractClassPath(method: PsiMethod, uMethod: UMethod): String? {
        return extractClassPathFromPsi(method) ?: extractClassPathFromUast(uMethod)
    }

    private fun extractClassPathFromPsi(method: PsiMethod): String? {
        val containingClass = method.containingClass ?: return null
        val classAnnotation = findMappingAnnotation(containingClass)
        val classPath = classAnnotation?.let { extractPaths(it).firstOrNull() }
        return normalizePath(classPath)
    }

    private fun extractClassPathFromUast(uMethod: UMethod): String? {
        val uClass = uMethod.getUastParentOfType(UClass::class.java, true) ?: return null
        val annotation = uClass.uAnnotations.firstOrNull { allMappingAnnotations.contains(it.qualifiedName) }
            ?: return null
        val classPath = extractPaths(annotation).firstOrNull()
        return normalizePath(classPath)
    }

    private fun extractPaths(annotation: PsiAnnotation): List<String> {
        val declaredPath = annotation.findDeclaredAttributeValue("path")
        val declaredValue = annotation.findDeclaredAttributeValue("value")
        val declaredPathResolved = resolvePsiPaths(declaredPath)
        if (declaredPathResolved.isNotEmpty()) {
            return declaredPathResolved
        }
        val declaredValueResolved = resolvePsiPaths(declaredValue)
        if (declaredValueResolved.isNotEmpty()) {
            return declaredValueResolved
        }
        val pathValue = annotation.findAttributeValue("path")
        val valueValue = annotation.findAttributeValue("value")
        val pathResolved = resolvePsiPaths(pathValue)
        if (pathResolved.isNotEmpty()) {
            return pathResolved
        }
        return resolvePsiPaths(valueValue)
    }

    private fun extractPaths(annotation: UAnnotation): List<String> {
        val declaredPath = annotation.findDeclaredAttributeValue("path")
        val declaredValue = annotation.findDeclaredAttributeValue("value")
        val declaredPathResolved = resolveUastPaths(declaredPath)
        if (declaredPathResolved.isNotEmpty()) {
            return declaredPathResolved
        }
        val declaredValueResolved = resolveUastPaths(declaredValue)
        if (declaredValueResolved.isNotEmpty()) {
            return declaredValueResolved
        }
        val pathValue = annotation.findAttributeValue("path")
        val valueValue = annotation.findAttributeValue("value")
        val pathResolved = resolveUastPaths(pathValue)
        if (pathResolved.isNotEmpty()) {
            return pathResolved
        }
        return resolveUastPaths(valueValue)
    }

    private fun extractHttpMethods(annotation: PsiAnnotation): List<String> {
        val qualifiedName = annotation.qualifiedName ?: return emptyList()
        val directMethod = directMappings[qualifiedName]
        if (directMethod != null) {
            return listOf(directMethod)
        }
        if (qualifiedName != REQUEST_MAPPING) {
            return emptyList()
        }
        val methodValue = annotation.findAttributeValue("method") ?: return emptyList()
        val values = AnnotationUtil.arrayAttributeValues(methodValue)
        val rawValues = if (values.isEmpty()) listOf(methodValue) else values
        return rawValues.mapNotNull { value ->
            val text = value.text
            val name = text.substringAfterLast('.')
            if (name.isNotBlank()) name else null
        }
    }

    private fun isRouterFunctionCall(call: UCallExpression): Boolean {
        val resolved = call.resolve() ?: return false
        val className = resolved.containingClass?.qualifiedName ?: return false
        return className.startsWith("org.springframework.web.reactive.function.server") ||
            className.startsWith("org.springframework.web.servlet.function")
    }

    private fun evaluateString(expression: org.jetbrains.uast.UExpression): String? {
        val literal = expression as? ULiteralExpression ?: return null
        return literal.value as? String
    }

    private fun combinePaths(classPath: String?, methodPath: String?): String {
        val classPart = classPath?.trim('/')?.takeIf { it.isNotBlank() }
        val methodPart = methodPath?.trim('/')?.takeIf { it.isNotBlank() }
        val combined = listOfNotNull(classPart, methodPart).joinToString("/")
        return if (combined.isBlank()) "/" else "/$combined"
    }

    private fun normalizePath(path: String?): String? {
        val raw = path?.trim() ?: return null
        if (raw.isBlank()) {
            return null
        }
        return if (raw.startsWith("/")) raw else "/$raw"
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

    private fun resolveUastStringValues(expression: UExpression): List<String> {
        val evaluated = safeEvaluate(expression)
        when (evaluated) {
            is String -> return listOf(evaluated)
            is Array<*> -> return evaluated.mapNotNull { it as? String }
            is List<*> -> return evaluated.mapNotNull { it as? String }
        }
        val literal = expression as? ULiteralExpression
        if (literal != null) {
            return listOfNotNull(literal.value as? String)
        }
        val call = expression as? UCallExpression
        if (call != null && call.methodName == "arrayOf") {
            return call.valueArguments.mapNotNull { resolveUastStringValue(it) }
        }
        return emptyList()
    }

    private fun resolveUastStringValue(expression: UExpression): String? {
        val evaluated = safeEvaluate(expression)
        if (evaluated is String) {
            return evaluated
        }
        val literal = expression as? ULiteralExpression
        return literal?.value as? String
    }

    private fun safeEvaluate(expression: UExpression): Any? {
        return try {
            expression.evaluate()
        } catch (_: Exception) {
            null
        }
    }

    private fun resolvePsiPaths(value: PsiAnnotationMemberValue?): List<String> {
        if (value == null) {
            return emptyList()
        }
        val values = AnnotationUtil.arrayAttributeValues(value)
        val raw = if (values.isEmpty()) {
            listOfNotNull(resolveStringValue(value))
        } else {
            values.mapNotNull { resolveStringValue(it) }
        }
        return raw.mapNotNull { normalizePath(it) }
    }

    private fun resolveUastPaths(value: UExpression?): List<String> {
        if (value == null) {
            return emptyList()
        }
        return resolveUastStringValues(value).mapNotNull { normalizePath(it) }
    }
}
