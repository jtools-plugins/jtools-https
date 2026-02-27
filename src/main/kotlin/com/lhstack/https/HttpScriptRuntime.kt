package com.lhstack.https

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.CompilerModuleExtension
import com.intellij.openapi.roots.OrderEnumerator
import com.intellij.openapi.util.JDOMUtil
import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.xmlb.XmlSerializer
import com.intellij.util.xmlb.annotations.Tag
import com.intellij.util.xmlb.annotations.XCollection
import com.lhstack.tools.plugins.Helper
import java.net.URL
import java.net.URLClassLoader
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.script.ScriptEngine
import javax.script.ScriptEngineManager

data class HttpScriptFormField(
    var key: String = "",
    var value: String = "",
    var type: String = HttpFormFieldType.TEXT.name
)

data class HttpScriptCookie(
    var name: String = "",
    var value: String = "",
    var domain: String = "",
    var path: String = "/",
    var expiresAt: Long = 0,
    var secure: Boolean = false,
    var httpOnly: Boolean = false,
    var remove: Boolean = false
)

data class HttpScriptRequestContext(
    var method: String = "GET",
    var url: String = "",
    var timeoutSeconds: Int = 10,
    var pathParams: MutableMap<String, String> = linkedMapOf(),
    var params: MutableMap<String, String> = linkedMapOf(),
    var headers: MutableMap<String, String> = linkedMapOf(),
    var cookies: MutableMap<String, String> = linkedMapOf(),
    var bodyMode: String = HttpBodyType.NONE.name,
    var jsonBody: String? = null,
    var urlEncoded: MutableMap<String, String> = linkedMapOf(),
    var formData: MutableList<HttpScriptFormField> = mutableListOf()
)

data class HttpScriptResponseContext(
    var status: Int = 0,
    var statusText: String = "",
    var headers: MutableMap<String, String> = linkedMapOf(),
    var body: String? = null,
    var bodyBase64: String? = null,
    var cookies: MutableList<HttpScriptCookie> = mutableListOf()
)

data class HttpScriptEndpointParameterContext(
    var type: String = "",
    var annotations: MutableMap<String, MutableMap<String, String>> = linkedMapOf()
)

data class HttpScriptEndpointContext(
    var source: String = "",
    var methodAnnotations: MutableMap<String, MutableMap<String, String>> = linkedMapOf(),
    var parameters: MutableMap<String, HttpScriptEndpointParameterContext> = linkedMapOf(),
    var methodBody: String? = null,
    var methodDescriptor: MutableMap<String, Any?>? = null,
    var classDescriptor: MutableMap<String, Any?>? = null
)

@Tag("script-env-state")
data class HttpScriptEnvState(
    @XCollection(style = XCollection.Style.v2, elementName = "entry")
    var entries: MutableList<HttpKeyValue> = mutableListOf()
)

class HttpScriptEnv(project: Project) {
    private val globalValues = LinkedHashMap(HttpScriptEnvStore.loadGlobal())
    private val projectValues = LinkedHashMap(HttpScriptEnvStore.loadProject(project))

    fun get(key: String): String? {
        return projectValues[key] ?: globalValues[key]
    }

    fun getProject(key: String): String? {
        return projectValues[key]
    }

    fun getGlobal(key: String): String? {
        return globalValues[key]
    }

    fun all(): MutableMap<String, String> {
        val merged = LinkedHashMap<String, String>()
        merged.putAll(globalValues)
        merged.putAll(projectValues)
        return merged
    }
}

class HttpScriptStore(private val project: Project) {
    fun get(key: String): String? {
        return HttpScriptEnv(project).get(key)
    }

    fun getProject(key: String): String? {
        return HttpScriptEnvStore.loadProject(project)[key]
    }

    fun getGlobal(key: String): String? {
        return HttpScriptEnvStore.loadGlobal()[key]
    }

    fun setProject(key: String, value: Any?) {
        HttpScriptEnvStore.updateProject(project, key, value?.toString())
    }

    fun setGlobal(key: String, value: Any?) {
        HttpScriptEnvStore.updateGlobal(key, value?.toString())
    }

    fun removeProject(key: String) {
        HttpScriptEnvStore.removeProject(project, key)
    }

    fun removeGlobal(key: String) {
        HttpScriptEnvStore.removeGlobal(key)
    }
}

class HttpScriptLogger(private val project: Project) {
    private val fallback = Logger.getInstance(HttpScriptLogger::class.java)
    private val projectHash = project.locationHash

    fun info(value: Any?) {
        log(value?.toString().orEmpty(), LogLevel.INFO)
    }

    fun warn(value: Any?) {
        log(value?.toString().orEmpty(), LogLevel.WARN)
    }

    fun debug(value: Any?) {
        log(value?.toString().orEmpty(), LogLevel.DEBUG)
    }

    fun error(value: Any?) {
        log(value?.toString().orEmpty(), LogLevel.ERROR)
    }

    private fun log(message: String, level: LogLevel) {
        val sysLogged = runCatching {
            val logger = Helper.getSysLogger(projectHash)
            if (logger == null) {
                return@runCatching false
            }
            when (level) {
                LogLevel.INFO -> logger.info(message)
                LogLevel.WARN -> logger.warn(message)
                LogLevel.DEBUG -> logger.debug(message)
                LogLevel.ERROR -> logger.error(message)
            }
            true
        }.getOrDefault(false)
        if (sysLogged) {
            return
        }
        when (level) {
            LogLevel.INFO -> fallback.info(message)
            LogLevel.WARN -> fallback.warn(message)
            LogLevel.DEBUG -> fallback.debug(message)
            LogLevel.ERROR -> fallback.error(message)
        }
    }

    private enum class LogLevel {
        INFO,
        WARN,
        DEBUG,
        ERROR
    }
}

class HttpScriptJvmBridge(
    private val project: Project,
    private val logger: HttpScriptLogger
) {
    private val classLoader: ClassLoader by lazy {
        HttpScriptProjectClassPath.createClassLoader(project, logger)
    }

    fun type(className: String): Any {
        val clazz = loadClass(className)
        // Return Nashorn-friendly static class wrapper so scripts can call static methods directly.
        return runCatching {
            val staticClass = Class.forName("jdk.dynalink.beans.StaticClass")
            val forClass = staticClass.getMethod("forClass", Class::class.java)
            forClass.invoke(null, clazz) ?: clazz
        }.getOrDefault(clazz)
    }

    fun available(className: String): Boolean {
        return runCatching { loadClass(className) }.isSuccess
    }

    fun classpath(): List<String> {
        return HttpScriptProjectClassPath.collectClassPath(project).map { it.toString() }
    }

    private fun loadClass(className: String): Class<*> {
        val name = className.trim()
        if (name.isBlank()) {
            throw IllegalArgumentException("className 不能为空")
        }
        return runCatching { classLoader.loadClass(name) }
            .getOrElse { throwable ->
                throw IllegalStateException("无法加载类: $name", throwable)
            }
    }
}

object HttpScriptProjectClassPath {
    fun createClassLoader(project: Project, logger: HttpScriptLogger): ClassLoader {
        val urls = collectClassPath(project)
        if (urls.isEmpty()) {
            logger.warn("脚本 JVM Bridge 未检测到项目 classpath: ${project.name}")
        }
        return URLClassLoader(urls.toTypedArray(), HttpScriptProjectClassPath::class.java.classLoader)
    }

    fun collectClassPath(project: Project): List<URL> {
        val urls = LinkedHashSet<URL>()
        runCatching {
            val classRoots = OrderEnumerator.orderEntries(project)
                .recursively()
                .classes()
                .roots
            classRoots.forEach { root ->
                addRootUrl(root, urls)
            }
        }
        runCatching {
            ModuleManager.getInstance(project).modules.forEach { module ->
                val extension = CompilerModuleExtension.getInstance(module) ?: return@forEach
                extension.compilerOutputPath?.let { addRootUrl(it, urls) }
                extension.compilerOutputPathForTests?.let { addRootUrl(it, urls) }
            }
        }
        return urls.toList()
    }

    private fun addRootUrl(root: VirtualFile, urls: MutableSet<URL>) {
        if (!root.isValid) {
            return
        }
        val file = when (root.fileSystem.protocol) {
            "jar" -> JarFileSystem.getInstance().getVirtualFileForJar(root)
            else -> root
        } ?: return
        if (!file.isInLocalFileSystem) {
            return
        }
        runCatching {
            val ioFile = VfsUtilCore.virtualToIoFile(file)
            urls.add(ioFile.toURI().toURL())
        }
    }
}

object HttpScriptEnvStore {
    private const val GLOBAL_KEY = "jtools.https.env.global"
    private const val PROJECT_KEY = "jtools.https.env.project"

    fun loadGlobal(): Map<String, String> {
        val value = PropertiesComponent.getInstance().getValue(GLOBAL_KEY).orEmpty()
        return decode(value)
    }

    fun loadProject(project: Project): Map<String, String> {
        val component = projectProperties(project) ?: return emptyMap()
        val value = component.getValue(PROJECT_KEY).orEmpty()
        return decode(value)
    }

    fun updateGlobal(key: String, value: String?) {
        updateGlobal { map -> setValue(map, key, value) }
    }

    fun updateProject(project: Project, key: String, value: String?) {
        updateProject(project) { map -> setValue(map, key, value) }
    }

    fun saveGlobal(values: Map<String, String>) {
        val normalized = normalize(values)
        val encoded = encode(normalized)
        PropertiesComponent.getInstance().setValue(GLOBAL_KEY, encoded)
    }

    fun saveProject(project: Project, values: Map<String, String>) {
        val component = projectProperties(project) ?: return
        val normalized = normalize(values)
        val encoded = encode(normalized)
        component.setValue(PROJECT_KEY, encoded)
    }

    fun removeGlobal(key: String) {
        updateGlobal { map -> map.remove(key.trim()) }
    }

    fun removeProject(project: Project, key: String) {
        updateProject(project) { map -> map.remove(key.trim()) }
    }

    private fun updateGlobal(mutator: (MutableMap<String, String>) -> Unit) {
        val map = LinkedHashMap(loadGlobal())
        mutator(map)
        val encoded = encode(map)
        PropertiesComponent.getInstance().setValue(GLOBAL_KEY, encoded)
    }

    private fun updateProject(project: Project, mutator: (MutableMap<String, String>) -> Unit) {
        val component = projectProperties(project) ?: return
        val map = LinkedHashMap(loadProject(project))
        mutator(map)
        val encoded = encode(map)
        component.setValue(PROJECT_KEY, encoded)
    }

    private fun projectProperties(project: Project): PropertiesComponent? {
        if (project.isDisposed) {
            return null
        }
        return runCatching { PropertiesComponent.getInstance(project) }.getOrNull()
    }

    private fun setValue(map: MutableMap<String, String>, rawKey: String, rawValue: String?) {
        val key = rawKey.trim()
        if (key.isBlank()) {
            return
        }
        val value = rawValue?.trim()
        if (value.isNullOrEmpty()) {
            map.remove(key)
            return
        }
        map[key] = value
    }

    private fun normalize(values: Map<String, String>): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        values.forEach { (rawKey, rawValue) ->
            val key = rawKey.trim()
            val value = rawValue.trim()
            if (key.isNotBlank() && value.isNotBlank()) {
                result[key] = value
            }
        }
        return result
    }

    private fun encode(map: Map<String, String>): String {
        if (map.isEmpty()) {
            return ""
        }
        val state = HttpScriptEnvState(
            entries = map.entries
                .filter { it.key.isNotBlank() }
                .map { HttpKeyValue(it.key, it.value) }
                .toMutableList()
        )
        return runCatching {
            val element = XmlSerializer.serialize(state)
            JDOMUtil.writeElement(element)
        }.getOrDefault("")
    }

    private fun decode(value: String): Map<String, String> {
        if (value.isBlank()) {
            return emptyMap()
        }
        return runCatching {
            val element = JDOMUtil.load(value)
            val state = XmlSerializer.deserialize(element, HttpScriptEnvState::class.java) ?: HttpScriptEnvState()
            val result = LinkedHashMap<String, String>()
            state.entries.forEach { entry ->
                val key = entry.key.trim()
                if (key.isNotBlank()) {
                    result[key] = entry.value
                }
            }
            result
        }.getOrDefault(emptyMap())
    }
}

object HttpScriptEngine {
    private val executor = Executors.newCachedThreadPool(
        ThreadFactory { runnable ->
            Thread(runnable, "jtools-https-script").apply {
                isDaemon = true
            }
        }
    )

    fun execute(
        script: String,
        bindings: Map<String, Any?>,
        timeoutMs: Long
    ): Result<Unit> {
        if (script.isBlank()) {
            return Result.success(Unit)
        }
        val engine = createEngine()
            ?: return Result.failure(IllegalStateException("JavaScript 引擎不可用"))
        val task = executor.submit<Unit> {
            val contextBindings = engine.createBindings()
            bindings.forEach { (name, value) ->
                contextBindings[name] = value
            }
            engine.eval(script, contextBindings)
            Unit
        }
        return try {
            task.get(timeoutMs, TimeUnit.MILLISECONDS)
            Result.success(Unit)
        } catch (e: TimeoutException) {
            task.cancel(true)
            Result.failure(IllegalStateException("脚本执行超时(${timeoutMs}ms)"))
        } catch (e: InterruptedException) {
            task.cancel(true)
            Thread.currentThread().interrupt()
            Result.failure(e)
        } catch (e: ExecutionException) {
            task.cancel(true)
            Result.failure(e.cause ?: e)
        } catch (e: Exception) {
            task.cancel(true)
            Result.failure(e)
        }
    }

    private fun createEngine(): ScriptEngine? {
        // Prefer Nashorn with ES6 enabled so template literals like `a${b}` can be used in scripts.
        val nashorn = runCatching {
            val factoryClass = Class.forName("org.openjdk.nashorn.api.scripting.NashornScriptEngineFactory")
            val factory = factoryClass.getDeclaredConstructor().newInstance()
            val method = factoryClass.getMethod("getScriptEngine", Array<String>::class.java)
            method.invoke(factory, arrayOf("--language=es6")) as? ScriptEngine
        }.getOrNull()
        if (nashorn != null) {
            return nashorn
        }
        val manager = ScriptEngineManager(HttpScriptEngine::class.java.classLoader)
        return manager.getEngineByName("JavaScript")
            ?: manager.getEngineByName("javascript")
            ?: manager.getEngineByName("js")
            ?: manager.getEngineByName("nashorn")
    }
}
