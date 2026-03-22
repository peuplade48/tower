// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    // Android Application Plugin - Gradle 8.2.2 ile uyumlu kararlı sürüm
    id("com.android.application") version "8.2.2" apply false
    
    // Kotlin Android Plugin - Compose ve Kotlin 1.9.22 uyumluluğu için
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
    
    // Compose Compiler Plugin (Opsiyonel ama yeni Gradle yapılarında tavsiye edilir)
    // Not: Uygulama modülünde composeOptions kullanıldığı için burada sadece altyapı hazırlanır.
}

// Tüm projeler için ortak ayarlar (Gerekirse buraya ekleme yapılabilir)
tasks.register("clean", Delete::class) {
    delete(rootProject.buildDir)
}
