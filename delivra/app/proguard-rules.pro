# Delivra ProGuard rules

# Keep Room entity + DAO
-keep class com.kabshah.delivra.data.** { *; }

# Keep Hilt
-keep class * extends dagger.hilt.internal.GeneratedComponent { *; }

# Keep NodeJS mobile bridge (prevent renaming of JNI classes)
-keep class com.janeasystems.rn_nodejs_mobile.** { *; }

# Keep WorkManager worker classes
-keep class * extends androidx.work.Worker { *; }
-keep class * extends androidx.work.CoroutineWorker { *; }

# Keep broadcast receivers
-keep class com.kabshah.delivra.scheduling.AlarmReceiver { *; }
-keep class com.kabshah.delivra.scheduling.BootReceiver { *; }

# Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
