plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// Builds can go elsewhere than the project, as onto a roomier disk:
// buildRoot=/some/dir in local.properties (which is not committed).
val buildRoot = java.util.Properties().apply {
    rootDir.resolve("local.properties").takeIf { it.exists() }?.reader()?.use(::load)
}.getProperty("buildRoot")
if (buildRoot != null) {
    allprojects {
        layout.buildDirectory.set(file("$buildRoot/${project.path.replace(':', '/').trim('/').ifEmpty { "root" }}"))
    }
}
