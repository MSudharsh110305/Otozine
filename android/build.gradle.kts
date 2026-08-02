plugins {
    alias(libs.plugins.android.application) apply false
    // Compose compiler only. Kotlin itself comes from AGP 9's built-in support.
    alias(libs.plugins.kotlin.compose) apply false
}
