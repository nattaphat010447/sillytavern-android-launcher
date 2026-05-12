# ── OkHttp + Okio ─────────────────────────────────────────────────────────────
-dontwarn okhttp3.**
-dontwarn okio.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# ── Kotlin ────────────────────────────────────────────────────────────────────
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
# Keep Kotlin metadata so reflection-based libraries work correctly
-keep class kotlin.Metadata { *; }
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

# ── JGit ──────────────────────────────────────────────────────────────────────
# JGit uses reflection heavily for config, transport, and merge strategies
-keep class org.eclipse.jgit.** { *; }
-dontwarn org.eclipse.jgit.**
-dontwarn org.slf4j.**
-dontwarn javax.servlet.**
-dontwarn org.apache.http.**

# ── ViewBinding ───────────────────────────────────────────────────────────────
-keep class com.standroid.launcher.databinding.** { *; }

# ── App classes used via reflection ───────────────────────────────────────────
-keep class com.standroid.launcher.** { *; }
