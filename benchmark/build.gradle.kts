plugins {
    `java`
    application
}

// 性能基准子项目（P1 基准设施）：
// 零外部依赖（纯 JDK + 引擎主源集），产物不入库（build/ 已被根 .gitignore 覆盖）。
// 结果文件写入 benchmark/results/<label>/<scenario>.txt（入库，作为对比证据）。

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    implementation(project(":"))
    // 主源集里 Bukkit 相关类的公开签名会引用 paper-api 类型；基准虽只用引擎纯逻辑，
    // 编译期仍需能解析这些签名（compileOnly，不进运行时）。
    compileOnly("io.papermc.paper:paper-api:26.2.build.121-stable")
}

application {
    mainClass.set("cn.quotidietium.balatro.bench.Main")
}

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

// 运行方式：./gradlew :benchmark:run --args="--label <标签>"
//           ./gradlew :benchmark:run --args="--compare <基线标签> <当前标签>"
// 工作目录统一到仓库根：结果固定写 <repo>/benchmark/results/（默认工作目录是本子项目目录）
tasks.run.get().workingDir = rootProject.projectDir
tasks.run.get().standardInput = System.`in`
// Windows 控制台默认 GBK：强制 UTF-8 输出，避免中文描述乱码
tasks.run.get().jvmArgs("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
