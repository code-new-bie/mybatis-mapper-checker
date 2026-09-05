// checker-core：纯 Java 业务模型与规则，禁止依赖任何 IDEA API。
// 这里没有 intellijPlatform 依赖，编译期就不可能出现 PsiElement / VirtualFile / Project。

plugins {
    `java-library`
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
