import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("fabric-loom") version "1.11.8"
    kotlin("jvm") version "2.2.20"
}

val testBuild = (property("test_build") as String).toBoolean()
version = (property("mod_version") as String) + if (testBuild) "-test" else ""
group = "dev.cobblesynergy"

base { archivesName.set("cobblesynergy") }

repositories {
    mavenCentral()
    maven("https://api.modrinth.com/maven")
    maven("https://maven.impactdev.net/repository/development/") { name = "Cobblemon" }
}

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    // CobbleLeague 도 Mojang 매핑으로 빌드되어 있어 맞춰 둔다.
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")
    modImplementation("net.fabricmc:fabric-language-kotlin:${property("fabric_kotlin_version")}")
    modImplementation("maven.modrinth:cobblemon:kF7CvxTo")

    // CobbleLeague 는 컴파일할 때만 참조한다. 실제 게임에서는 설치된 jar 를 사용한다.
    modCompileOnly(files((findProperty("cobbleleague_jar") as String?) ?: "libs/cobbleleague-0.7.4.jar"))

    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:2.2.20")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
}

tasks.processResources {
    inputs.property("version", project.version)
    inputs.property("test", testBuild)
    filesMatching("fabric.mod.json") { expand("version" to project.version) }
    filesMatching("cobblesynergy/build.properties") { expand("test" to testBuild.toString()) }
}

tasks.withType<Jar>().configureEach { isPreserveFileTimestamps = false; isReproducibleFileOrder = true }
