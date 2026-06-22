plugins {
	kotlin("jvm") version "1.9.25"
	kotlin("plugin.spring") version "1.9.25"
	id("org.springframework.boot") version "3.5.14"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "com.okestro"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

extra["springAiVersion"] = "1.1.7"

dependencies {
	// --- Spring Boot ---
	implementation("org.springframework.boot:spring-boot-starter-web")
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-jdbc")
	implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
	implementation("org.jetbrains.kotlin:kotlin-reflect")

	// --- Spring AI: OpenAI(Chat/Embedding/Moderation) + PgVector ---
	implementation("org.springframework.ai:spring-ai-starter-model-openai")
	implementation("org.springframework.ai:spring-ai-starter-vector-store-pgvector")

	// --- (M4) 회복탄력성 — Resilience4j 통합(Retry/CircuitBreaker + 사용자별 RateLimiter) ---
	implementation("io.github.resilience4j:resilience4j-spring-boot3:2.3.0")
	implementation("org.springframework.boot:spring-boot-starter-aop")   // resilience4j 애노테이션(@Aspect) 활성화에 필요
	// --- (M5) Slack — Socket Mode(공개 URL·서명검증 불필요) ---
	implementation("com.slack.api:bolt:1.45.3")
	implementation("com.slack.api:bolt-socket-mode:1.45.3")
	// Socket Mode WebSocket 백엔드(JavaWebSocket). Spring Boot 3=Jakarta라 기본 Tyrus(javax.websocket) 대신 사용
	implementation("org.java-websocket:Java-WebSocket:1.5.7")

	// --- Test ---
	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

dependencyManagement {
	imports {
		mavenBom("org.springframework.ai:spring-ai-bom:${property("springAiVersion")}")
	}
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict")
	}
}

tasks.withType<Test> {
	useJUnitPlatform()
}

tasks.register<JavaExec>("routingCli") {
	group = "application"
	description = "질문 라우터 수동 확인용 CLI (OPENAI_API_KEY 필요)"
	mainClass.set("com.okestro.ragbot.routing.interfaces.RoutingCliKt")
	classpath = sourceSets["main"].runtimeClasspath
	standardInput = System.`in`
}

// 실행 가능한 bootJar만 산출 (build/libs에 plain jar를 만들지 않음 → Docker COPY *.jar 안전)
tasks.named<Jar>("jar") {
	enabled = false
}
