// Основной Maven Central — первым: это канонический репозиторий, доступный в большинстве сетей.
// Зеркало Google — запасное. Gradle переходит к следующему репозиторию, только если артефакт не найден (404);
// при сетевой ошибке первого репозитория сборка падает — поэтому первым стоит самый надёжный.
// 27.09.2026 зеркало Google было недоступно (соединение не устанавливалось), repo.maven.apache.org — доступен.
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
    }
}

rootProject.name = "tram-forecast"
