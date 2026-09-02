plugins {
    `java`
}

group = "cn.quotidietium.balatro"
version = "0.4.62"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // 服务端已提供，仅编译期
    compileOnly("io.papermc.paper:paper-api:26.2.build.121-stable")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // Adventure 随 paper-api（compileOnly）不进测试类路径；
    // 聊天帮助增强（HoverText）为纯组件逻辑可单测，显式补同版本 Adventure（与 paper-api 26.2 对齐）。
    testImplementation(platform("net.kyori:adventure-bom:5.2.0"))
    testImplementation("net.kyori:adventure-api")
    testImplementation("net.kyori:adventure-text-serializer-legacy")
    testImplementation("net.kyori:adventure-text-serializer-plain")
}

// 统一 UTF-8；Paper 26.2 要求 Java 25，故用 --release 25 编译
// Paper 26.2 要求 Java 25：用 toolchain 固定编译 JDK（不再依赖机器 PATH / JAVA_HOME）。
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.test {
    useJUnitPlatform()
}

tasks.processResources {
    filteringCharset = "UTF-8"
    val projectVersion = project.version
    inputs.property("version", projectVersion)
    filesMatching("plugin.yml") {
        expand("version" to projectVersion)
    }
}

// 产物命名：balatro-<version>.jar
tasks.jar {
    archiveBaseName.set("balatro")
    archiveClassifier.set("")
}
