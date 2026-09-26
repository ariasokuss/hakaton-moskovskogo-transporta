// Зеркало Maven Central от Google — первым: из части сетей repo.maven.apache.org отвечает 403.
// Если зеркало недоступно, Gradle идёт в обычные репозитории.
pluginManagement {
    repositories {
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "tram-forecast"
