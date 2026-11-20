// DHVON — Every Sound. Every Frame.  © rhshourav — https://github.com/rhshourav/DHVON
// AGP 9 ships built-in Kotlin, so the old `org.jetbrains.kotlin.android` plugin is intentionally NOT applied.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}
