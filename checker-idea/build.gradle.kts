import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

// checker-idea：所有 IDEA 相关代码。

plugins {
    java
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

val platformVersion = providers.gradleProperty("platformVersion").orElse("2024.2.6")
val pluginSinceBuild = providers.gradleProperty("pluginSinceBuild")

/**
 * 2025.3（build 253）起 JetBrains 把 IntelliJ IDEA Community 与 Ultimate 合并成统一发行版，
 * 下载坐标从 idea:ideaIC 变成 idea:idea，必须用 IntelliJPlatformType.IntellijIdea 而不是
 * IntellijIdeaCommunity，否则解析不到下载地址（"Couldn't resolve ... download URL"）。
 * 这里按版本号自动选型，命令行用 -PplatformVersion=2025.3.6 之类覆盖时无需再改这个文件。
 */
fun platformTypeFor(version: String): IntelliJPlatformType {
    val (year, minor) = version.split(".").let {
        (it.getOrNull(0)?.toIntOrNull() ?: 0) to (it.getOrNull(1)?.toIntOrNull() ?: 0)
    }
    val isUnified = year > 2025 || (year == 2025 && minor >= 3)
    return if (isUnified) IntelliJPlatformType.IntellijIdea else IntelliJPlatformType.IntellijIdeaCommunity
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation(project(":checker-core"))

    intellijPlatform {
        create(platformVersion.map { platformTypeFor(it) }, platformVersion)
        // Java PSI 来自 Java 插件；XML PSI 属于平台核心。
        bundledPlugin("com.intellij.java")

        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.Plugin.Java)

        pluginVerifier()
    }

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")
}

intellijPlatform {
    pluginConfiguration {
        id = "com.mapperchecker.mybatis"
        name = "MyBatis Mapper Checker"
        version = project.version.toString()
        description = providers.fileContents(layout.projectDirectory.file("src/main/resources/META-INF/description.html")).asText
        vendor {
            name = "mapperchecker"
        }
        ideaVersion {
            sinceBuild = pluginSinceBuild
            // 不设上限，由 Plugin Verifier 兜底。
            untilBuild = provider { null }
        }
    }

    pluginVerification {
        ides {
            // 覆盖兼容下限与几个主要版本。
            create(IntelliJPlatformType.IntellijIdeaCommunity, "2023.3.8")
            create(IntelliJPlatformType.IntellijIdeaCommunity, "2024.1.7")
            create(IntelliJPlatformType.IntellijIdeaCommunity, "2024.2.6")
            create(IntelliJPlatformType.IntellijIdeaCommunity, "2024.3.6")
            create(IntelliJPlatformType.IntellijIdeaCommunity, "2025.2.6")
            // 2025.3（build 253）起 JetBrains 不再单独发布 Community 版，Community/Ultimate 合并为统一发行版，
            // 要用 IntelliJPlatformType.IntellijIdea（无 Community/Ultimate 后缀）而不是 IntellijIdeaCommunity。
            create(IntelliJPlatformType.IntellijIdea, "2025.3.6")
            create(IntelliJPlatformType.IntellijIdea, "2026.1.5")
            create(IntelliJPlatformType.IntellijIdea, "2026.2.2")
        }
    }

    instrumentCode = false
    // 设置项很少，不生成 searchable options；同时避免构建时启动无头 IDEA（CI 无显示环境，本机也曾残留进程锁住沙箱 jar）。
    buildSearchableOptions = false
}

tasks {
    test {
        useJUnit()
        // 平台测试框架需要以下系统属性避免弹窗与遥测。
        systemProperty("idea.force.use.core.classloader", "true")
        systemProperty("java.awt.headless", "true")
    }

    runIde {
        jvmArgs("-Duser.language=zh", "-Duser.country=CN", "-Xmx2g")
    }
}
