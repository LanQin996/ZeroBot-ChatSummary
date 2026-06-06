plugins {
    java
}

group = "your.group"
version = "1.0.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    compileOnly("cn.zerobot:zerobot-plugin-api:0.1.5") {
        isChanging = true
    }
}

configurations.configureEach {
    resolutionStrategy.cacheChangingModulesFor(0, "seconds")
}

tasks.jar {
    archiveBaseName.set("zerobot-chat-summary")
}

tasks.register<JavaExec>("previewReport") {
    group = "verification"
    description = "Generate a local chat summary preview image from sample data."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("cn.zerobot.chatsummary.PreviewReportGenerator")
}

tasks.register<JavaExec>("verifyJsonMessageStore") {
    group = "verification"
    description = "Verify JSONL chat message persistence with a small smoke test."
    classpath = sourceSets["test"].runtimeClasspath + sourceSets["main"].compileClasspath
    mainClass.set("cn.zerobot.chatsummary.JsonMessageStoreSmokeTest")
}
