plugins {
    id("org.springframework.boot") version "3.5.16"
    id("io.spring.dependency-management") version "1.1.7"
    kotlin("jvm") version "2.0.20"
    kotlin("plugin.spring") version "2.0.20"
    kotlin("plugin.jpa") version "2.0.20"
    kotlin("plugin.allopen") version "2.0.20"
    kotlin("plugin.noarg") version "2.0.20"
}

group = "com.menuflow"
version = "0.0.1-SNAPSHOT"
java.sourceCompatibility = JavaVersion.VERSION_21

repositories {
    mavenCentral()
}

dependencies {
    // Spring Boot Starters
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-cache")
    implementation("com.github.ben-manes.caffeine:caffeine")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // AOP: necessario para os aspectos do Resilience4j (@CircuitBreaker/@Retry).
    implementation("org.springframework.boot:spring-boot-starter-aop")

    // Resilience4j (Fase 2.3): protege as chamadas ao Asaas com circuit breaker +
    // retry. spring-boot3 traz os aspectos auto-configurados; -kotlin nao e usado
    // aqui (anotamos os metodos, sem a DSL de coroutines).
    implementation("io.github.resilience4j:resilience4j-spring-boot3:2.2.0")

    // Database
    implementation("org.postgresql:postgresql")
    implementation("com.zaxxer:HikariCP")

    // Flyway — versioned migrations. Flyway 10.x (managed by Spring Boot 3.4)
    // split DB-specific support out of core: PostgreSQL 16 needs the postgresql
    // module too, otherwise "Unsupported Database: PostgreSQL 16". Versions are
    // managed by the Spring Boot BOM — do not pin them here.
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")

    // Redis
    implementation("org.springframework.boot:spring-boot-starter-data-redis")

    // Rate limiting (Sprint 2): Bucket4j core + Redis (Lettuce) proxy manager.
    // The Redis-backed limiter is the production path (counters shared across
    // instances). When no Lettuce connection is configured (dev/test) the limiter
    // falls back to a local in-memory bucket so the limit is still enforced.
    implementation("com.bucket4j:bucket4j-core:8.10.1")
    implementation("com.bucket4j:bucket4j-redis:8.10.1")

    // Kafka
    implementation("org.springframework.kafka:spring-kafka")

    // JSON
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

    // Kotlin
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")

    // JWT (HS256) — io.jsonwebtoken (jjwt)
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")

    // OpenAPI / Swagger (springdoc for Spring Boot 3.x)
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.6.0")

    // Testing
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.junit.jupiter:junit-jupiter")
    // Testcontainers
    testImplementation("org.testcontainers:junit-jupiter:1.20.4")
    testImplementation("org.testcontainers:postgresql:1.20.4")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
}

tasks {
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        compilerOptions {
            freeCompilerArgs.add("-Xjsr305=strict")
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }

    withType<Test> {
        useJUnitPlatform()
        testLogging {
            events("passed", "failed", "skipped")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            showStandardStreams = true
        }
    }
}

allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}

noArg {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}
