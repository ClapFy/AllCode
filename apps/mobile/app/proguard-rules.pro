# kotlinx.serialization keeps its generated serializers through its own consumer rules.
# OkHttp ships consumer rules as well; these silence optional-platform warnings.
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# WorkManager (which Glance uses) builds its Room database reflectively; full-mode R8
# otherwise strips the generated implementation and the app dies at startup.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep class androidx.work.impl.WorkDatabase_Impl { *; }

# ML Kit discovers its components by reflective no-arg constructors; without them the
# QR scanner never initializes.
-keep class * implements com.google.firebase.components.ComponentRegistrar { <init>(); }
-keep class com.google.mlkit.**.*Registrar { <init>(); }
