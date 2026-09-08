# Add project specific ProGuard rules here.

# ---- WorkManager (reminders/DigestWorker.kt, reminders/WaitingNudgeWorker.kt) ----
# WorkManager stores each enqueued job's Worker class as a plain string and reconstructs it
# later via Class.forName + reflection on the (Context, WorkerParameters) constructor — R8
# renaming either class (or stripping that constructor as "unused" since it's only ever called
# reflectively) would make a previously-scheduled digest/nudge job crash with
# ClassNotFoundException/NoSuchMethodException at run time, often not until the next day when
# the job actually fires.
-keep class com.futurepath.actionbox.reminders.DigestWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class com.futurepath.actionbox.reminders.WaitingNudgeWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# ---- Room (data/AppDatabase.kt, NotificationEntity.kt, LearningPatternEntity.kt, *Dao.kt) ----
# Room's own generated code (via KSP, compiled alongside these classes) accesses entity fields
# directly rather than through runtime reflection, so it's normally safe under R8 without extra
# rules — kept explicitly anyway since these rows are the app's entire persisted state and the
# cost of being wrong here (silently corrupted/unreadable data) is much higher than the cost of
# an unnecessary keep rule.
-keep class com.futurepath.actionbox.data.NotificationEntity { *; }
-keep class com.futurepath.actionbox.data.LearningPatternEntity { *; }
-keep class com.futurepath.actionbox.data.AppDatabase { *; }
-keep interface com.futurepath.actionbox.data.NotificationDao { *; }
-keep interface com.futurepath.actionbox.data.LearningPatternDao { *; }
-keep class com.futurepath.actionbox.data.Converters { *; }

# ClassifiedState.name() is stored as the literal column value in Room (see Converters) and
# read back via valueOf() — default enum keep rules already cover this (bundled in
# proguard-android-optimize.txt), kept explicitly here too since a mismatch would corrupt
# every already-captured notification's category.
-keepclassmembers enum com.futurepath.actionbox.classification.ClassifiedState {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-keep enum com.futurepath.actionbox.data.LearningPatternType { *; }

# ---- On-device ML classifier (ml/TfliteNotificationClassifier.kt) ----
# The tensorflow-lite AAR bundles its own consumer ProGuard rules for the native/JNI-bound
# Interpreter classes, so no manual rules are needed for the library itself. Our own wrapper's
# public surface is kept since HybridClassifier calls it from elsewhere in the app.
-keep class com.futurepath.actionbox.ml.TfliteNotificationClassifier { public *; }

# ---- Google Play Billing (billing/BillingRepository.kt) ----
# Billing's AAR bundles its own consumer rules; kept defensively since a stripped/renamed
# callback interface implementation would silently break purchase handling with no crash to
# surface the problem.
-keep class com.android.billingclient.api.** { *; }
-dontwarn com.android.billingclient.api.**

# ---- NotificationListenerService / BroadcastReceiver ----
# Both are declared in AndroidManifest.xml, which AGP already auto-keeps via its generated
# manifest-component rules — kept explicitly here too since CapturedNotificationListenerService
# is instantiated by the OS (not by any of our own code), so there's no ordinary code reference
# that would otherwise hint to R8 that it's reachable.
-keep class com.futurepath.actionbox.service.CapturedNotificationListenerService { *; }
-keep class com.futurepath.actionbox.service.BootCompletedReceiver { *; }
