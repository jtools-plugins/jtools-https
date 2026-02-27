package com.lhstack.https

import com.intellij.execution.ExecutionListener
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.lhstack.tools.plugins.Logger

/**
 * 监听项目进程启动输出，并从日志中解析服务端口。
 *
 * 典型用途：当 Spring Boot / Tomcat / Netty 等服务启动后，
 * 自动识别端口并更新到 [HttpPluginContext]，供后续 HTTP 调试能力使用。
 */
class ProjectPortListener(
    /** 当前项目，用于按项目维度更新端口信息 */
    private val project: Project,
    /** 可选日志器（允许为空，避免强依赖） */
    private val logger: Logger?
) : ExecutionListener {

    /**
     * 支持的端口日志匹配规则（按顺序匹配，命中即返回）。
     *
     * 说明：
     * - 每个正则的第 1 个捕获组必须是端口号；
     * - 使用 IGNORE_CASE 兼容大小写差异；
     * - 新框架日志可按同样约定继续扩展。
     */
    private val portPatterns = listOf(
        Regex("Tomcat started on port(?:\\(s\\))?:?\\s*(\\d+)", RegexOption.IGNORE_CASE),
        Regex("Netty started on port(?:\\(s\\))?:?\\s*(\\d+)", RegexOption.IGNORE_CASE),
        Regex("Started .* on port(?:\\(s\\))?:?\\s*(\\d+)", RegexOption.IGNORE_CASE),
        // Tomcat startup log, e.g. `Starting ProtocolHandler ["http-nio-8998"]`
        Regex("""ProtocolHandler\s*\[\s*(?:["'])?[^"'\]]*?-(\d+)(?:["'])?\s*]""", RegexOption.IGNORE_CASE),
        Regex("Listening on port\\s*(\\d+)", RegexOption.IGNORE_CASE),
        Regex("listening on port\\s*(\\d+)", RegexOption.IGNORE_CASE)
    )

    /**
     * 进程启动后注册输出监听器。
     *
     * 每当进程有新文本输出时：
     * 1) 尝试从当前文本中提取端口；
     * 2) 提取成功则更新项目端口上下文；
     * 3) 记录检测日志。
     */
    override fun processStarted(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
        handler.addProcessListener(object : ProcessAdapter() {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                // 未匹配到端口则直接返回，不做任何更新
                val port = parsePort(event.text) ?: return

                // 将最新端口写入插件上下文（按项目隔离）
                HttpPluginContext.updatePort(project, port)

                // 记录检测信息（logger 为空时自动跳过）
                logger?.info("Detected server port: $port")
            }
        })
    }

    /**
     * 从一段进程输出文本中提取端口。
     *
     * @param text 单行或片段日志文本
     * @return 匹配到的端口号，未匹配或非法数字则返回 null
     */
    private fun parsePort(text: String): Int? {
        for (pattern in portPatterns) {
            val match = pattern.find(text) ?: continue
            val value = match.groupValues.getOrNull(1) ?: continue
            return value.toIntOrNull()
        }
        return null
    }
}
