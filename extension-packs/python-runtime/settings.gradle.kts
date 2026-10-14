pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven("https://chaquo.com/maven")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google { content { excludeGroup("com.xiao.feng") } }
        mavenCentral { content { excludeGroup("com.xiao.feng") } }
        val apiRepository = providers.gradleProperty("xfengPythonApiRepo")
            .orElse(System.getenv("XFENG_PYTHON_API_REPO") ?: "")
        if (apiRepository.isPresent && apiRepository.get().isNotBlank()) {
            maven {
                name = "XiaoFengPythonApi"
                url = uri(apiRepository.get())
                content { includeGroup("com.xiao.feng") }
            }
        }
    }
    versionCatalogs {
        create("libs") { from(files("../../gradle/libs.versions.toml")) }
    }
}

rootProject.name = "xfeng-python-runtime"
include(":runtime")
