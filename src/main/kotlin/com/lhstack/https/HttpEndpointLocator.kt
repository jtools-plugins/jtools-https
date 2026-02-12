package com.lhstack.https

import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AllClassesSearch
import com.intellij.psi.search.searches.AnnotatedElementsSearch

object HttpEndpointLocator {
    private val cache = java.util.concurrent.ConcurrentHashMap<Project, MutableList<EndpointInfo>>()
    private val mappingAnnotations = listOf(
        "org.springframework.web.bind.annotation.GetMapping",
        "org.springframework.web.bind.annotation.PostMapping",
        "org.springframework.web.bind.annotation.PutMapping",
        "org.springframework.web.bind.annotation.DeleteMapping",
        "org.springframework.web.bind.annotation.PatchMapping",
        "org.springframework.web.bind.annotation.RequestMapping"
    )

    fun find(project: Project, httpMethod: String, requestPath: String): List<EndpointInfo> {
        val normalizedMethod = httpMethod.trim().uppercase()
        val normalizedPath = normalizePath(requestPath)
        if (normalizedPath == null) {
            return emptyList()
        }
        val allowFallback = normalizedMethod.isBlank() || normalizedMethod == "ANY"
        val cached = findInCache(project, normalizedMethod, normalizedPath)
        if (cached.isNotEmpty()) {
            return cached
        }

        val scope = GlobalSearchScope.projectScope(project)
        val psiFacade = JavaPsiFacade.getInstance(project)
        val methods = LinkedHashSet<PsiMethod>()
        for (annotationName in mappingAnnotations) {
            val annotationClass = psiFacade.findClass(annotationName, scope) ?: continue
            AnnotatedElementsSearch.searchElements(annotationClass, scope, PsiMethod::class.java).forEach { method ->
                methods.add(method)
            }
        }

        var endpoints = methods.mapNotNull { HttpEndpointResolver.findEndpoint(it,true) }
        if (endpoints.isEmpty()) {
            endpoints = scanAllEndpoints(project, scope)
        }
        if (endpoints.isNotEmpty()) {
            cache[project] = endpoints.toMutableList()
        }
        val pathMatches = endpoints.filter { matchesEndpointPath(it.path, normalizedPath) }
        if (pathMatches.isEmpty()) {
            return emptyList()
        }
        val methodMatches = pathMatches.filter { it.httpMethod.equals(normalizedMethod, true) }
        if (methodMatches.isNotEmpty()) {
            return methodMatches
        }
        return if (allowFallback) pathMatches else emptyList()
    }

    fun clearCache(project: Project) {
        cache.remove(project)
    }

    private fun findInCache(project: Project, httpMethod: String, requestPath: String): List<EndpointInfo> {
        val cached = cache[project] ?: return emptyList()
        val validEndpoints = cached.filter { it.anchor.isValid }
        if (validEndpoints.size != cached.size) {
            cache[project] = validEndpoints.toMutableList()
        }
        val pathMatches = validEndpoints.filter { matchesEndpointPath(it.path, requestPath) }
        if (pathMatches.isEmpty()) {
            return emptyList()
        }
        val methodMatches = pathMatches.filter { it.httpMethod.equals(httpMethod, true) }
        if (methodMatches.isNotEmpty()) {
            return methodMatches
        }
        val allowFallback = httpMethod.isBlank() || httpMethod == "ANY"
        return if (allowFallback) pathMatches else emptyList()
    }

    private fun scanAllEndpoints(project: Project, scope: GlobalSearchScope): List<EndpointInfo> {
        val results = mutableListOf<EndpointInfo>()
        AllClassesSearch.search(scope, project).forEach { psiClass ->
            psiClass.methods.forEach { method ->
                val endpoint = HttpEndpointResolver.findEndpoint(method,true) ?: return@forEach
                results.add(endpoint)
            }
        }
        return results
    }

    private fun matchesEndpointPath(endpointPath: String, requestPath: String): Boolean {
        val normalizedEndpoint = normalizePath(endpointPath) ?: return false
        val normalizedRequest = normalizePath(requestPath) ?: return false
        val regex = buildPathRegex(normalizedEndpoint)
        if (regex.matches(normalizedRequest)) {
            return true
        }
        val endpointSegments = normalizedEndpoint.trim('/').split('/').filter { it.isNotBlank() }
        val requestSegments = normalizedRequest.trim('/').split('/').filter { it.isNotBlank() }
        if (endpointSegments.isEmpty() || requestSegments.size <= endpointSegments.size) {
            return false
        }
        val tail = "/" + requestSegments.takeLast(endpointSegments.size).joinToString("/")
        return regex.matches(tail)
    }

    private fun normalizePath(path: String?): String? {
        val raw = path?.trim() ?: return null
        if (raw.isBlank()) {
            return null
        }
        val noQuery = raw.substringBefore('?')
        var result = noQuery
        if (!result.startsWith("/")) {
            result = "/$result"
        }
        if (result.length > 1 && result.endsWith("/")) {
            result = result.dropLast(1)
        }
        return result
    }

    private fun buildPathRegex(path: String): Regex {
        if (path == "/") {
            return Regex("^/$")
        }
        val sb = StringBuilder("^")
        var index = 0
        while (index < path.length) {
            val ch = path[index]
            if (ch == '{') {
                val end = path.indexOf('}', index + 1)
                if (end > index) {
                    sb.append("[^/]+")
                    index = end + 1
                    continue
                }
            }
            if (".[]{}()+-^$|?\\".indexOf(ch) >= 0) {
                sb.append('\\')
            }
            sb.append(ch)
            index++
        }
        sb.append("/?$")
        return Regex(sb.toString())
    }
}
