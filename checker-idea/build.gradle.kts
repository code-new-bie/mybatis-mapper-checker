import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

// checker-idea：所有 IDEA 相关代码。

plugins {
    java
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

val platformType = providers.gradleProperty("platformType")
val platformVersion = providers.gradleProperty("platformVersion")
val pluginSinceBuild = providers.gradleProperty("pluginSinceBuild")

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation(project(":checker-core"))

    intellijPlatform {
        create(platformType, platformVersion)
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
