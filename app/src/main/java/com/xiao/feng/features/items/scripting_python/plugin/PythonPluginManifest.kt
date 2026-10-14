package com.xiao.feng.features.items.scripting_python.plugin

import kotlinx.serialization.Serializable

@Serializable
data class PythonPluginManifest(
    val schema: Int,
    val id: String,
    val name: String,
    val version: String,
    val author: String = "",
    val description: String = "",
    val entry: String = "main",
    val minXiaoFengVersionCode: Int = 0,
    val processes: List<String> = listOf("main"),
)
