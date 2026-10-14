package com.xiao.feng.agent.terminal

import com.xiao.feng.agent.environment.EnvironmentSnapshot

interface TerminalBackend {
    suspend fun start(
        environment: EnvironmentSnapshot,
        argv: List<String>,
        workingDirectory: String?,
        environmentVariables: Map<String, String>,
        cols: Int,
        rows: Int,
    ): TerminalBackendStart
}
