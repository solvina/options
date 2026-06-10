import org.openapitools.generator.gradle.plugin.tasks.GenerateTask

plugins {
    kotlin("jvm") version "2.1.0"
    kotlin("plugin.spring") version "2.1.0"
    kotlin("plugin.jpa") version "2.1.0"
    id("org.springframework.boot") version "3.5.0"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.openapi.generator") version "7.17.0"
    id("org.jlleitschuh.gradle.ktlint") version "13.1.0"
}

group = "cz.solvina"
version = "0.0.1-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    implementation("org.jetbrains.kotlin:kotlin-reflect")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // OpenAPI for WebFlux
    implementation("org.springdoc:springdoc-openapi-starter-webflux-ui:2.8.17")
    testImplementation("org.springdoc:springdoc-openapi-starter-webflux-api:2.8.17")

    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor:1.10.2")

    developmentOnly("org.springframework.boot:spring-boot-devtools")

    // Database
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.postgresql:postgresql")
    implementation("org.liquibase:liquibase-core")
    implementation("io.hypersistence:hypersistence-utils-hibernate-63:3.10.1")

    // Logging
    implementation("io.github.oshai:kotlin-logging-jvm:7.0.0")
    implementation("org.slf4j:slf4j-api:2.0.16")
    implementation("ch.qos.logback:logback-classic:1.5.18")

    // Testing
    testImplementation("io.mockk:mockk:1.14.9")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")

    // InfluxDB
    implementation("com.influxdb:influxdb-client-kotlin:7.2.0")

    // Interactive Brokers TWS API
    implementation(files("lib/TwsApi_debug.jar"))
}

apply { plugin("org.openapi.generator") }

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

val openApiDir = File("$rootDir/openapi")
val openApiSpecs =
    openApiDir
        .listFiles { file -> file.extension == "yaml" || file.extension == "yml" }
        ?.map { file ->
            file.nameWithoutExtension to
                file
                    .relativeTo(rootDir)
                    .path
        } ?: emptyList()

openApiSpecs.forEach { (name, path) ->
    tasks.register("openApiGenerate-$name", GenerateTask::class) {
        mustRunAfter("runKtlintCheckOverMainSourceSet")
        inputSpec.set("$rootDir/$path")
        generatorName.set("kotlin-spring")
        outputDir.set("$rootDir/build/generated/openapi/$name")
        apiPackage.set("$name.api")
        modelPackage.set("$name.dto")
        invokerPackage.set("cz.solvina.invoker")
        templateDir.set("$rootDir/openapi-templates/kotlin-spring")
        additionalProperties.set(
            mapOf(
                "reactive" to "true",
                "useSpringBoot3" to "true",
                "interfaceOnly" to "true",
                "skipDefaultApiInterface" to "true",
                "dateLibrary" to "java8",
                "serializationLibrary" to "jackson",
            ),
        )
    }

    sourceSets["main"].java.srcDir("$rootDir/build/generated/openapi/$name/src/main/kotlin")
}

tasks.register("openApiGenerateAll") {
    dependsOn(openApiSpecs.map { "openApiGenerate-${it.first}" })
}

tasks.named("compileKotlin") {
    dependsOn(
        tasks.named("openApiGenerateAll"),
    )
}

ktlint {
    verbose.set(true)
    android.set(false)
    outputToConsole.set(true)
    ignoreFailures.set(false)
    enableExperimentalRules.set(true)
    reporters {
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.PLAIN)
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.CHECKSTYLE)
    }
    filter {
        exclude { it.file.path.contains("/build/generated/") }
    }
}

tasks.withType<Test> {
    useJUnitPlatform {
        excludeTags("tws")
    }
}

tasks.register<Test>("twsTest") {
    useJUnitPlatform {
        includeTags("tws")
    }
    systemProperty("tests.tags", "tws")
}
