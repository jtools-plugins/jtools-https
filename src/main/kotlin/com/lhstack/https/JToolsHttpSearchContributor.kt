package com.lhstack.https

import com.intellij.codeInsight.AnnotationUtil
import com.intellij.ide.highlighter.JavaFileType
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereContributor
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.openapi.util.Disposer
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiAnnotationMemberValue
import com.intellij.psi.PsiArrayInitializerMemberValue
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.ScreenUtil
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.Processor
import com.lhstack.https.component.MultiLanguageTextField
import java.awt.Dimension
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Timer
import javax.swing.JList
import javax.swing.ListCellRenderer

class JToolsHttpSearchContributor(private val project: Project?) :
    SearchEverywhereContributor<JToolsHttpSearchContributor.SearchItem> {

    private val log = Logger.getInstance(JToolsHttpSearchContributor::class.java)

    @Volatile
    private var cachedItems: List<SearchItem>? = null
    @Volatile
    private var cacheInitialized = false
    @Volatile
    private var currentHighlightTokens: List<String> = emptyList()

    override fun getSearchProviderId(): String = "jtools.http.search"

    override fun getGroupName(): String = "JTools Http Search"

    override fun getSortWeight(): Int = 900

    override fun showInFindResults(): Boolean = true

    override fun isShownInSeparateTab(): Boolean = true

    override fun isDumbAware(): Boolean = true

    override fun isEmptyPatternSupported(): Boolean = false

    override fun getElementsRenderer(): ListCellRenderer<in SearchItem> = SearchItemRenderer()

    override fun fetchElements(
        pattern: String,
        progressIndicator: ProgressIndicator,
        consumer: Processor<in SearchItem>
    ) {
        val project = project ?: ProjectManager.getInstance().openProjects.firstOrNull() ?: return
        val query = pattern.trim()
        if (query.isEmpty()) {
            return
        }
        val tokens = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) {
            return
        }
        currentHighlightTokens = tokens

        val items = ensureItems(project)
        log.debug("JToolsHttpSearch project='${project.name}' query='$query' items=${items.size}")
        for (item in items) {
            if (progressIndicator.isCanceled) {
                return
            }
            if (item.matches(tokens)) {
                if (!consumer.process(item)) {
                    return
                }
            }
        }
    }

    override fun processSelectedItem(item: SearchItem, modifiers: Int, searchText: String): Boolean {
        val target = item.method.navigationElement
        if (target is Navigatable && target.isValid) {
            target.navigate(true)
            return true
        }
        return false
    }

    override fun getDataForItem(item: SearchItem, dataId: String): Any? {
        if (CommonDataKeys.NAVIGATABLE.`is`(dataId)) {
            val target = item.method.navigationElement
            if (target is Navigatable) {
                return target
            }
        }
        return null
    }

    private fun ensureItems(project: Project): List<SearchItem> {
        if (cacheInitialized) {
            return cachedItems ?: emptyList()
        }
        if (DumbService.isDumb(project)) {
            return emptyList()
        }
        val items = ReadAction.compute<List<SearchItem>, RuntimeException> {
            buildSearchItems(project)
        }
        cachedItems = items
        cacheInitialized = true
        return items
    }

    private fun buildSearchItems(project: Project): List<SearchItem> {
        val methods = collectSpringMethods(project)
        if (methods.isEmpty()) {
            return emptyList()
        }
        val port = HttpPluginContext.getPort(project) ?: 8080
        val items = mutableListOf<SearchItem>()
        for (method in methods) {
            try {
                val endpoint = HttpEndpointResolver.findEndpoint(method, true)
                val resolvedMethod = resolveHttpMethod(method)
                val resolvedPath = resolveFullPath(method)
                val endpointPath = endpoint?.path
                val path = chooseMoreCompletePath(endpointPath, resolvedPath)
                val httpMethod = endpoint?.httpMethod?.takeIf { it.isNotBlank() } ?: resolvedMethod
                val fullUrl = resolveFullUrl(method, port, path)
                val signature = buildSignature(method)
                val description = resolveMethodDescription(method)
                val searchText = buildSearchText(method, httpMethod, path, fullUrl, signature, description)
                items.add(
                    SearchItem(
                        method = method,
                        httpMethod = httpMethod,
                        path = path,
                        fullUrl = fullUrl,
                        signature = signature,
                        description = description,
                        searchText = searchText,
                        compactSearchText = compact(searchText)
                    )
                )
            } catch (t: Throwable) {
                log.warn("JToolsHttpSearch skip method: ${method.name}", t)
            }
        }
        return items
    }

    private fun chooseMoreCompletePath(endpointPath: String?, resolvedPath: String): String {
        val ep = normalizePath(endpointPath) ?: "/"
        val rp = normalizePath(resolvedPath) ?: "/"
        if (ep == "/" && rp != "/") {
            return rp
        }
        if (rp == "/" && ep != "/") {
            return ep
        }
        return if (rp.length > ep.length) rp else ep
    }

    private fun resolveFullUrl(method: PsiMethod, defaultPort: Int, path: String): String {
        val base = resolveFeignBaseUrl(method) ?: "http://localhost:$defaultPort"
        return joinBaseAndPath(base, path)
    }

    private fun joinBaseAndPath(base: String, path: String): String {
        val normalizedBase = base.trim().trimEnd('/')
        val normalizedPath = normalizePath(path) ?: "/"
        return if (normalizedPath == "/") normalizedBase else normalizedBase + normalizedPath
    }

    private fun collectSpringMethods(project: Project): List<PsiMethod> {
        val scope = GlobalSearchScope.projectScope(project)
        val psiFacade = com.intellij.psi.JavaPsiFacade.getInstance(project)
        val methods = LinkedHashSet<PsiMethod>()
        for (annotationName in mappingAnnotations) {
            val annotationClass = psiFacade.findClass(annotationName, scope) ?: continue
            AnnotatedElementsSearch.searchElements(annotationClass, scope, PsiMethod::class.java).forEach { method ->
                methods.add(method)
            }
        }
        if (methods.isNotEmpty()) {
            return methods.filter { it.isValid }
        }

        methods.addAll(scanJavaSourceMethods(project, scope))
        return methods.filter { it.isValid }
    }

    private fun scanJavaSourceMethods(project: Project, scope: GlobalSearchScope): List<PsiMethod> {
        val results = LinkedHashSet<PsiMethod>()
        val psiManager = PsiManager.getInstance(project)
        val files = FileTypeIndex.getFiles(JavaFileType.INSTANCE, scope)
        for (file in files) {
            try {
                val psiFile = psiManager.findFile(file) as? PsiJavaFile ?: continue
                PsiTreeUtil.findChildrenOfType(psiFile, PsiMethod::class.java).forEach { method ->
                    if (hasSpringMapping(method)) {
                        results.add(method)
                    }
                }
            } catch (t: Throwable) {
                log.warn("JToolsHttpSearch scan java file failed: ${file.path}", t)
            }
        }
        return results.toList()
    }

    private fun hasSpringMapping(method: PsiMethod): Boolean {
        for (annotation in method.modifierList.annotations) {
            val qualifiedName = annotation.qualifiedName
            if (qualifiedName != null && mappingAnnotations.contains(qualifiedName)) {
                return true
            }
            val shortName = annotation.nameReferenceElement?.referenceName
            if (shortName != null && shortNameSet.contains(shortName)) {
                return true
            }
            if (annotation.text.contains("RequestMapping") || annotation.text.contains("Mapping(")) {
                return true
            }
        }
        return false
    }

    private fun resolveHttpMethod(method: PsiMethod): String {
        for (candidate in methodAndSupers(method)) {
            for ((annotationName, httpMethod) in directMappingMethods) {
                if (AnnotationUtil.findAnnotation(candidate, annotationName) != null) {
                    return httpMethod
                }
            }
            val requestMapping = AnnotationUtil.findAnnotation(candidate, requestMappingAnnotation)
            if (requestMapping != null) {
                val methodAttribute = requestMapping.findDeclaredAttributeValue("method")
                    ?: requestMapping.findAttributeValue("method")
                val requestMethods = extractRequestMethods(methodAttribute)
                if (requestMethods.isNotEmpty()) {
                    return requestMethods.first()
                }
            }
        }
        return "GET"
    }

    private fun resolveFullPath(method: PsiMethod): String {
        val methodPath = findMethodMappingAnnotation(method)?.let(::extractPath)
        val classPath = resolveClassMappingPath(method.containingClass)
        return combinePaths(classPath, methodPath)
    }

    private fun findMethodMappingAnnotation(method: PsiMethod): PsiAnnotation? {
        for (candidate in methodAndSupers(method)) {
            val ann = findDirectMappingAnnotation(candidate)
            if (ann != null) {
                return ann
            }
        }
        return null
    }

    private fun findDirectMappingAnnotation(method: PsiMethod): PsiAnnotation? {
        for (annotationName in mappingAnnotations) {
            val annotation = AnnotationUtil.findAnnotation(method, annotationName)
            if (annotation != null) {
                return annotation
            }
        }
        for (annotation in method.modifierList.annotations) {
            val shortName = annotation.nameReferenceElement?.referenceName
            if (shortName != null && shortNameSet.contains(shortName)) {
                return annotation
            }
            if (annotation.text.contains("RequestMapping") || annotation.text.contains("Mapping(")) {
                return annotation
            }
        }
        return null
    }

    private fun resolveClassMappingPath(psiClass: PsiClass?): String? {
        if (psiClass == null) {
            return null
        }
        val hierarchy = collectClassHierarchy(psiClass)
        val requestPath = hierarchy.firstNotNullOfOrNull { cls ->
            findClassMappingAnnotation(cls)?.let(::extractPath)
        }
        val feignPath = hierarchy.firstNotNullOfOrNull { cls ->
            resolveFeignPathFromClass(cls)
        }
        return combinePaths(feignPath, requestPath)
    }

    private fun findClassMappingAnnotation(psiClass: PsiClass): PsiAnnotation? {
        for (annotationName in mappingAnnotations) {
            val annotation = AnnotationUtil.findAnnotation(psiClass, annotationName)
            if (annotation != null) {
                return annotation
            }
        }
        for (annotation in psiClass.modifierList?.annotations.orEmpty()) {
            val shortName = annotation.nameReferenceElement?.referenceName
            if (shortName != null && shortNameSet.contains(shortName)) {
                return annotation
            }
            if (annotation.text.contains("RequestMapping") || annotation.text.contains("Mapping(")) {
                return annotation
            }
        }
        return null
    }

    private fun resolveFeignBaseUrl(method: PsiMethod): String? {
        val owner = method.containingClass ?: return null
        val hierarchy = collectClassHierarchy(owner)
        for (cls in hierarchy) {
            val annotation = findFeignClientAnnotation(cls) ?: continue
            val url = extractFirstString(
                annotation.findDeclaredAttributeValue("url") ?: annotation.findAttributeValue("url")
            )?.trim()
            if (!url.isNullOrBlank()) {
                return if (url.startsWith("http://") || url.startsWith("https://")) url else "http://$url"
            }
            val name = extractFirstString(
                annotation.findDeclaredAttributeValue("name") ?: annotation.findAttributeValue("name")
            )?.trim()
            if (!name.isNullOrBlank()) {
                return "http://$name"
            }
            val value = extractFirstString(
                annotation.findDeclaredAttributeValue("value") ?: annotation.findAttributeValue("value")
            )?.trim()
            if (!value.isNullOrBlank() && !value.startsWith("/")) {
                return "http://$value"
            }
        }
        return null
    }

    private fun resolveFeignPathFromClass(psiClass: PsiClass): String? {
        val annotation = findFeignClientAnnotation(psiClass) ?: return null
        val path = extractFirstString(
            annotation.findDeclaredAttributeValue("path") ?: annotation.findAttributeValue("path")
        )?.trim()
        if (!path.isNullOrBlank()) {
            return normalizePath(path)
        }
        val value = extractFirstString(
            annotation.findDeclaredAttributeValue("value") ?: annotation.findAttributeValue("value")
        )?.trim()
        if (!value.isNullOrBlank() && value.startsWith("/")) {
            return normalizePath(value)
        }
        return null
    }

    private fun findFeignClientAnnotation(psiClass: PsiClass): PsiAnnotation? {
        for (annotationName in feignClientAnnotations) {
            val annotation = AnnotationUtil.findAnnotation(psiClass, annotationName)
            if (annotation != null) {
                return annotation
            }
        }
        for (annotation in psiClass.modifierList?.annotations.orEmpty()) {
            val shortName = annotation.nameReferenceElement?.referenceName
            if (shortName == "FeignClient") {
                return annotation
            }
            if (annotation.text.contains("@FeignClient")) {
                return annotation
            }
        }
        return null
    }

    private fun collectClassHierarchy(psiClass: PsiClass): List<PsiClass> {
        val result = mutableListOf<PsiClass>()
        val visited = mutableSetOf<PsiClass>()
        val queue = ArrayDeque<PsiClass>()
        queue.add(psiClass)
        while (queue.isNotEmpty()) {
            val cls = queue.removeFirst()
            if (!visited.add(cls)) {
                continue
            }
            result.add(cls)
            cls.superClass?.let { queue.add(it) }
            cls.interfaces.forEach { queue.add(it) }
        }
        return result
    }

    private fun methodAndSupers(method: PsiMethod): List<PsiMethod> {
        val result = LinkedHashSet<PsiMethod>()
        result.add(method)
        method.findSuperMethods().forEach { result.add(it) }
        return result.toList()
    }

    private fun extractPath(annotation: PsiAnnotation): String? {
        val declaredPath = extractFirstString(annotation.findDeclaredAttributeValue("path"))
        if (!declaredPath.isNullOrBlank()) {
            return normalizePath(declaredPath)
        }
        val declaredValue = extractFirstString(annotation.findDeclaredAttributeValue("value"))
        if (!declaredValue.isNullOrBlank()) {
            return normalizePath(declaredValue)
        }
        val path = extractFirstString(annotation.findAttributeValue("path"))
        if (!path.isNullOrBlank()) {
            return normalizePath(path)
        }
        val value = extractFirstString(annotation.findAttributeValue("value"))
        return normalizePath(value)
    }

    private fun extractFirstString(value: PsiAnnotationMemberValue?): String? {
        if (value == null) {
            return null
        }
        val constant = evaluateStringConstant(value)
        if (!constant.isNullOrBlank()) {
            return constant
        }
        if (value is PsiLiteralExpression) {
            val literal = value.value as? String
            if (!literal.isNullOrBlank()) {
                return literal
            }
        }
        if (value is PsiArrayInitializerMemberValue) {
            for (initializer in value.initializers) {
                val candidate = extractFirstString(initializer)
                if (!candidate.isNullOrBlank()) {
                    return candidate
                }
            }
        }
        val text = value.text ?: return null
        val match = quotedStringRegex.find(text) ?: return null
        return match.groupValues.getOrNull(1)
    }

    private fun evaluateStringConstant(value: PsiAnnotationMemberValue): String? {
        val expression = value as? PsiExpression ?: return null
        val result = runCatching {
            com.intellij.psi.JavaPsiFacade.getInstance(expression.project)
                .constantEvaluationHelper
                .computeConstantExpression(expression, true)
        }.getOrNull()
        return result as? String
    }

    private fun extractRequestMethods(value: PsiAnnotationMemberValue?): List<String> {
        if (value == null) {
            return emptyList()
        }
        val text = value.text ?: return emptyList()
        return requestMethodRegex.findAll(text).map { it.groupValues[1].uppercase() }.toList()
    }

    private fun combinePaths(classPath: String?, methodPath: String?): String {
        val classPart = classPath?.trim('/')?.takeIf { it.isNotBlank() }
        val methodPart = methodPath?.trim('/')?.takeIf { it.isNotBlank() }
        return when {
            classPart == null && methodPart == null -> "/"
            classPart == null -> "/$methodPart"
            methodPart == null -> "/$classPart"
            else -> "/$classPart/$methodPart"
        }
    }

    private fun normalizePath(path: String?): String? {
        val raw = path?.trim() ?: return null
        if (raw.isBlank()) {
            return null
        }
        var result = raw.substringBefore('?')
        if (!result.startsWith("/")) {
            result = "/$result"
        }
        if (result.length > 1 && result.endsWith("/")) {
            result = result.dropLast(1)
        }
        return result
    }

    private fun buildSignature(method: PsiMethod): String {
        val returnType = method.returnType?.presentableText ?: "void"
        val params = method.parameterList.parameters.joinToString(", ") { param ->
            "${param.type.presentableText} ${param.name}"
        }
        return "$returnType ${method.name}($params)"
    }

    private fun resolveMethodDescription(method: PsiMethod): String {
        for (candidate in methodAndSupers(method)) {
            val descriptionFromAnnotation = methodDescriptionDescriptors.firstNotNullOfOrNull { descriptor ->
                descriptor.extract(candidate) { value -> extractFirstString(value) }
            }
            if (!descriptionFromAnnotation.isNullOrBlank()) {
                return descriptionFromAnnotation
            }
        }
        for (candidate in methodAndSupers(method)) {
            val summary = candidate.docComment?.descriptionElements?.joinToString(" ") { it.text.trim() }?.trim()
            if (!summary.isNullOrBlank()) {
                return summary
            }
        }
        return ""
    }

    private fun buildSearchText(
        method: PsiMethod,
        httpMethod: String,
        path: String,
        fullUrl: String,
        signature: String,
        description: String
    ): String {
        val builder = StringBuilder()
        builder.append(httpMethod).append(' ')
        builder.append(path).append(' ')
        builder.append(fullUrl).append(' ')
        builder.append(signature).append(' ')
        builder.append(description).append(' ')
        builder.append(method.name).append(' ')
        method.parameterList.parameters.forEach { param ->
            builder.append(param.name).append(' ')
            builder.append(param.type.presentableText).append(' ')
        }
        builder.append(method.text).append(' ')
        method.docComment?.let { builder.append(it.text).append(' ') }
        method.body?.let { body ->
            builder.append(body.text).append(' ')
            PsiTreeUtil.findChildrenOfType(body, PsiComment::class.java).forEach { comment ->
                builder.append(comment.text).append(' ')
            }
        }
        method.modifierList.annotations.forEach { annotation ->
            builder.append(annotation.text).append(' ')
        }
        method.containingClass?.let { psiClass ->
            builder.append(psiClass.qualifiedName.orEmpty()).append(' ')
            builder.append(psiClass.name.orEmpty()).append(' ')
            psiClass.docComment?.let { builder.append(it.text).append(' ') }
        }
        return builder.toString().lowercase()
    }

    data class SearchItem(
        val method: PsiMethod,
        val httpMethod: String,
        val path: String,
        val fullUrl: String,
        val signature: String,
        val description: String,
        val searchText: String,
        val compactSearchText: String
    ) {
        fun matches(tokens: List<String>): Boolean {
            if (tokens.isEmpty()) {
                return true
            }
            return tokens.all { token ->
                if (searchText.contains(token)) {
                    return@all true
                }
                val compactToken = compact(token)
                compactToken.isNotBlank() && compactSearchText.contains(compactToken)
            }
        }
    }

    private data class AnnotationDescriptor(
        val qualifiedNames: List<String>,
        val shortNames: Set<String>,
        val fields: List<String>
    ) {
        fun extract(method: PsiMethod, valueExtractor: (PsiAnnotationMemberValue?) -> String?): String? {
            val annotations = mutableListOf<PsiAnnotation>()
            for (qualifiedName in qualifiedNames) {
                AnnotationUtil.findAnnotation(method, qualifiedName)?.let { annotations.add(it) }
            }
            for (annotation in method.modifierList.annotations) {
                val shortName = annotation.nameReferenceElement?.referenceName
                if (shortName != null && shortNames.contains(shortName)) {
                    annotations.add(annotation)
                    continue
                }
                if (shortName == null && shortNames.any { annotation.text.contains("@$it") }) {
                    annotations.add(annotation)
                }
            }
            if (annotations.isEmpty()) {
                return null
            }
            for (annotation in annotations) {
                val parts = fields.mapNotNull { field ->
                    valueExtractor(annotation.findDeclaredAttributeValue(field) ?: annotation.findAttributeValue(field))?.trim()
                }.filter { it.isNotBlank() }
                if (parts.isNotEmpty()) {
                    return parts.joinToString(" ")
                }
            }
            return null
        }
    }

    private inner class SearchItemRenderer : ColoredListCellRenderer<SearchItem>() {
        override fun customizeCellRenderer(
            list: JList<out SearchItem>,
            value: SearchItem?,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean
        ) {
            if (value == null) {
                return
            }
            HoverSourcePopupSupport.install(list) {
                project ?: ProjectManager.getInstance().openProjects.firstOrNull()
            }
            appendHighlighted(
                value.httpMethod,
                methodAttributes(value.httpMethod, selected),
                methodHitAttributes(value.httpMethod, selected),
                currentHighlightTokens
            )
            append("  ")
            appendHighlighted(
                value.fullUrl,
                if (selected) selectedUrlAttributes else normalUrlAttributes,
                if (selected) selectedHitAttributes else normalHitAttributes,
                currentHighlightTokens
            )
            append("  ")
            append(value.signature, SimpleTextAttributes.GRAYED_ATTRIBUTES)
            if (value.description.isNotBlank()) {
                append("  ")
                append(value.description, SimpleTextAttributes.GRAY_ATTRIBUTES)
            }
            toolTipText = null
        }

        private fun methodAttributes(method: String, selected: Boolean): SimpleTextAttributes {
            if (selected) {
                return SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES
            }
            return when (method.uppercase()) {
                "GET" -> SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, JBColor(0x2EA043, 0x3FB950))
                "POST" -> SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, JBColor(0xD97706, 0xF59E0B))
                "PUT" -> SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, JBColor(0x2563EB, 0x60A5FA))
                "DELETE" -> SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, JBColor(0xDC2626, 0xF87171))
                "PATCH" -> SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, JBColor(0x7C3AED, 0xA78BFA))
                else -> SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES
            }
        }

        private fun methodHitAttributes(method: String, selected: Boolean): SimpleTextAttributes {
            if (selected) {
                return selectedHitAttributes
            }
            return when (method.uppercase()) {
                "GET" -> SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, JBColor(0x1D7A35, 0x58D774))
                "POST" -> SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, JBColor(0xB65F00, 0xFFB020))
                "PUT" -> SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, JBColor(0x1D4ED8, 0x7DB3FF))
                "DELETE" -> SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, JBColor(0xB91C1C, 0xFF8E8E))
                "PATCH" -> SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, JBColor(0x6D28D9, 0xBE9CFF))
                else -> normalHitAttributes
            }
        }

        private fun appendHighlighted(
            text: String,
            normalAttrs: SimpleTextAttributes,
            hitAttrs: SimpleTextAttributes,
            tokens: List<String>
        ) {
            if (tokens.isEmpty()) {
                append(text, normalAttrs)
                return
            }
            val normalized = text.lowercase()
            var index = 0
            while (index < text.length) {
                val next = findNextHit(normalized, tokens, index)
                if (next == null) {
                    append(text.substring(index), normalAttrs)
                    break
                }
                val start = next.first
                val length = next.second
                if (start > index) {
                    append(text.substring(index, start), normalAttrs)
                }
                append(text.substring(start, start + length), hitAttrs)
                index = start + length
            }
        }

        private fun findNextHit(text: String, tokens: List<String>, from: Int): Pair<Int, Int>? {
            var bestStart = Int.MAX_VALUE
            var bestLength = 0
            for (token in tokens) {
                if (token.isBlank()) {
                    continue
                }
                val start = text.indexOf(token, from)
                if (start >= 0 && (start < bestStart || (start == bestStart && token.length > bestLength))) {
                    bestStart = start
                    bestLength = token.length
                }
            }
            return if (bestStart == Int.MAX_VALUE) null else Pair(bestStart, bestLength)
        }
    }

    private class HoverSourcePopupSupport private constructor(
        private val list: JList<out SearchItem>,
        private val projectProvider: () -> Project?
    ) : MouseAdapter() {
        private val timer = Timer(350) { showPopup() }.apply { isRepeats = false }
        private var hoverIndex: Int = -1
        private var popup: JBPopup? = null
        private var previewField: MultiLanguageTextField? = null

        override fun mouseMoved(e: MouseEvent) {
            val index = list.locationToIndex(e.point)
            if (index < 0 || !isInsideCell(index, e.point)) {
                if (popup == null) {
                    clearHover()
                } else {
                    timer.stop()
                    hoverIndex = -1
                }
                return
            }
            if (index == hoverIndex && popup != null) {
                return
            }
            hoverIndex = index
            timer.restart()
            popup?.cancel()
            popup = null
        }

        override fun mouseExited(e: MouseEvent) {
            timer.stop()
            hoverIndex = -1
        }

        override fun mousePressed(e: MouseEvent) {
            clearHover()
        }

        override fun mouseDragged(e: MouseEvent) {
            clearHover()
        }

        private fun isInsideCell(index: Int, point: Point): Boolean {
            val bounds = list.getCellBounds(index, index) ?: return false
            return bounds.contains(point)
        }

        private fun clearHover() {
            hoverIndex = -1
            timer.stop()
            popup?.cancel()
            popup = null
            previewField?.let {
                Disposer.dispose(it)
            }
            previewField = null
        }

        private fun showPopup() {
            val index = hoverIndex
            if (index < 0 || index >= list.model.getSize()) {
                return
            }
            val item = list.model.getElementAt(index) as? SearchItem ?: return
            val project = projectProvider() ?: return
            popup?.cancel()
            previewField?.let {
                Disposer.dispose(it)
            }
            val sourceText = buildSourceText(item.method)
            val editor = MultiLanguageTextField(JavaFileType.INSTANCE, project, isViewer = true).apply {
                setTextAndReformat(sourceText)
            }
            previewField = editor
            val popupSize = calculatePopupSize(sourceText, list)
            editor.preferredSize = popupSize
            popup = JBPopupFactory.getInstance()
                .createComponentPopupBuilder(editor, editor)
                .setTitle("方法源码")
                .setResizable(true)
                .setMovable(true)
                .setRequestFocus(false)
                .setCancelOnClickOutside(true)
                .setCancelOnWindowDeactivation(true)
                .createPopup()
            popup?.addListener(object : JBPopupListener {
                override fun onClosed(event: LightweightWindowEvent) {
                    previewField?.let {
                        Disposer.dispose(it)
                    }
                    previewField = null
                    popup = null
                }
            })

            popup?.show(RelativePoint.fromScreen(calculatePopupLocation(index, popupSize)))
        }

        private fun buildSourceText(method: PsiMethod): String {
            return method.text
        }

        private fun calculatePopupSize(sourceText: String, list: JList<out SearchItem>): Dimension {
            val screen = ScreenUtil.getScreenRectangle(list)
            val rootSize = list.rootPane?.size ?: Dimension(screen.width, screen.height)
            val lines = sourceText.split('\n')
            val lineCount = lines.size.coerceAtLeast(1)
            val maxLineLength = lines.maxOfOrNull { it.length } ?: 1
            val screenMaxWidth = (screen.width - 40).coerceAtLeast(700)
            val screenMaxHeight = (screen.height - 40).coerceAtLeast(420)
            val maxWidth = (rootSize.width * 0.6).toInt().coerceIn(700, screenMaxWidth)
            val maxHeight = (rootSize.height * 0.75).toInt().coerceIn(420, screenMaxHeight)
            val widthByContent = (maxLineLength * 8 + 80).coerceAtLeast(700)
            val heightByContent = (lineCount * 18 + 80).coerceAtLeast(380)
            return Dimension(
                widthByContent.coerceAtMost(maxWidth),
                heightByContent.coerceAtMost(maxHeight)
            )
        }

        private fun calculatePopupLocation(index: Int, popupSize: Dimension): Point {
            val screen = ScreenUtil.getScreenRectangle(list)
            val listOnScreen = list.locationOnScreen
            val cellBounds = list.getCellBounds(index, index) ?: Rectangle(0, 0, list.width, 0)

            var x = listOnScreen.x + list.width + 10
            if (x + popupSize.width > screen.x + screen.width) {
                x = listOnScreen.x - popupSize.width - 10
            }
            x = x.coerceIn(screen.x + 4, screen.x + screen.width - popupSize.width - 4)

            var y = listOnScreen.y + cellBounds.y
            if (y + popupSize.height > screen.y + screen.height) {
                y = screen.y + screen.height - popupSize.height - 4
            }
            y = y.coerceAtLeast(screen.y + 4)
            return Point(x, y)
        }

        companion object {
            private const val KEY = "jtools.http.search.hover.popup.support"

            fun install(list: JList<out SearchItem>, projectProvider: () -> Project?) {
                val installed = list.getClientProperty(KEY) as? HoverSourcePopupSupport
                if (installed != null) {
                    return
                }
                val support = HoverSourcePopupSupport(list, projectProvider)
                list.putClientProperty(KEY, support)
                list.addMouseMotionListener(support)
                list.addMouseListener(support)
            }
        }
    }

    companion object {
        private val mappingAnnotations = listOf(
            "org.springframework.web.bind.annotation.GetMapping",
            "org.springframework.web.bind.annotation.PostMapping",
            "org.springframework.web.bind.annotation.PutMapping",
            "org.springframework.web.bind.annotation.DeleteMapping",
            "org.springframework.web.bind.annotation.PatchMapping",
            "org.springframework.web.bind.annotation.RequestMapping"
        )

        private val shortNameSet = setOf(
            "GetMapping",
            "PostMapping",
            "PutMapping",
            "DeleteMapping",
            "PatchMapping",
            "RequestMapping"
        )

        private const val requestMappingAnnotation = "org.springframework.web.bind.annotation.RequestMapping"

        private val directMappingMethods = mapOf(
            "org.springframework.web.bind.annotation.GetMapping" to "GET",
            "org.springframework.web.bind.annotation.PostMapping" to "POST",
            "org.springframework.web.bind.annotation.PutMapping" to "PUT",
            "org.springframework.web.bind.annotation.DeleteMapping" to "DELETE",
            "org.springframework.web.bind.annotation.PatchMapping" to "PATCH"
        )

        private val quotedStringRegex = Regex("\"([^\"]+)\"")
        private val requestMethodRegex = Regex("RequestMethod\\.([A-Z]+)")
        private val feignClientAnnotations = listOf(
            "org.springframework.cloud.openfeign.FeignClient",
            "org.springframework.cloud.netflix.feign.FeignClient"
        )
        private val methodDescriptionDescriptors = listOf(
            AnnotationDescriptor(
                qualifiedNames = listOf("io.swagger.annotations.ApiOperation"),
                shortNames = setOf("ApiOperation"),
                fields = listOf("value", "notes")
            ),
            AnnotationDescriptor(
                qualifiedNames = listOf("io.swagger.v3.oas.annotations.Operation"),
                shortNames = setOf("Operation"),
                fields = listOf("summary", "description", "value")
            ),
            AnnotationDescriptor(
                qualifiedNames = listOf("com.github.xiaoymin.knife4j.annotations.ApiOperationSupport"),
                shortNames = setOf("ApiOperationSupport"),
                fields = listOf("value", "author", "notes")
            ),
            AnnotationDescriptor(
                qualifiedNames = listOf(
                    "com.lhstack.tools.plugins.ApiOperator",
                    "com.lhstack.tools.plugins.annotation.ApiOperator",
                    "com.lhstack.tools.plugin.annotation.ApiOperator"
                ),
                shortNames = setOf("ApiOperator"),
                fields = listOf("value", "name", "description", "desc")
            ),
            AnnotationDescriptor(
                qualifiedNames = listOf(
                    "com.lhstack.tools.plugins.Operate",
                    "com.lhstack.tools.plugins.annotation.Operate",
                    "com.lhstack.tools.plugin.annotation.Operate"
                ),
                shortNames = setOf("Operate"),
                fields = listOf("value", "name", "description", "desc")
            )
        )
        private val normalUrlAttributes = SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, JBColor(0x0F5FAF, 0x7CB8FF))
        private val selectedUrlAttributes = SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, null)
        private val normalHitAttributes = SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, JBColor(0xD4380D, 0xFF9D66))
        private val selectedHitAttributes = SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, JBColor(0xFFE08A, 0xFFD479))

        private fun compact(text: String): String {
            return text.lowercase().replace(Regex("[\\s\\p{Punct}]+"), "")
        }
    }
}
