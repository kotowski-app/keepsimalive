# Add project specific ProGuard rules here.

# Hilt
-keep class dagger.hilt.** { *; }
-keep class hilt_aggregated_deps.** { *; }
-dontwarn dagger.hilt.**
-keep class javax.inject.** { *; }
-dontwarn javax.inject.**
-keep class androidx.hilt.** { *; }
-dontwarn androidx.hilt.**

# WorkManager instantiates workers by class name from the work spec: only the two worker
# classes need name retention (their constructors are reached directly from the Hilt-
# generated factories). Manifest receivers need no rule: AAPT auto-keeps them
# (class name + constructor).
-keep class app.kotowski.keepsimalive.work.SendWorker { *; }
-keep class app.kotowski.keepsimalive.work.ScheduleSafetyWorker { *; }
