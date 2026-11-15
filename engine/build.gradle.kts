import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    java
    id("org.springframework.boot") version "4.1.2"
}

group = "dev.subscriber"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))

    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("io.nats:jnats:2.26.4")
    implementation("tools.jackson.dataformat:jackson-dataformat-csv")
    implementation("org.springframework.shell:spring-shell-starter:4.0.3")

    runtimeOnly("com.h2database:h2")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-jdbc-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
    // Tests that open real windows or need a NATS server are opt-in: ./gradlew test -Pwindow-tests -Pnats-tests
    systemProperty("window.tests", providers.gradleProperty("window-tests").map { it.ifBlank { "true" } }.orElse("false").get())
    systemProperty("nats.tests", providers.gradleProperty("nats-tests").map { it.ifBlank { "true" } }.orElse("false").get())
    // GitPreflightParityTest runs the kit installer's copy of the git checks (a deliberate copy, see AGENTS.md), so a change there must re-run the tests.
    inputs.files(fileTree("../kit/install") { include("GitSetup.java") }).withPropertyName("kitGitSetup").withPathSensitivity(PathSensitivity.RELATIVE)
}

