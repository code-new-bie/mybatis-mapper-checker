// 根工程只做公共配置，不产出任何构件。

allprojects {
    group = "com.mapperchecker"
    version = providers.gradleProperty("pluginVersion").getOrElse("0.1.0-SNAPSHOT")
}

subprojects {
    plugins.withType<JavaPlugin> {
        extensions.configure<JavaPluginExtension> {
            // 兼容下限 IDEA 2023.3 运行在 JBR 17，语言级别固定为 17。
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
        tasks.withType<JavaCompile>().configureEach {
            options.encoding = "UTF-8"
            options.release.set(17)
            options.compilerArgs.add("-Xlint:all,-serial,-processing")
        }
        tasks.withType<Javadoc>().configureEach {
            options.encoding = "UTF-8"
        }
        tasks.withType<Test>().configureEach {
            systemProperty("file.encoding", "UTF-8")
            testLogging {
                events("failed", "skipped")
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            }
        }
    }
}
