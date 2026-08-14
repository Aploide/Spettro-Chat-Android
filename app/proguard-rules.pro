# Project ProGuard/R8 rules. Library-specific keep-rules (Room, OkHttp,
# kotlinx.serialization, Compose) ship inside the libraries as consumer rules
# and do not need repeating here.

# Readable crash reports from the minified build.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# kotlinx.serialization: the runtime's embedded rules cover classes whose
# serializers are referenced statically, but keep the generated serializer
# lookup for our own models defensively — the chat store and export/import
# depend on them and the classes are tiny.
-keepclassmembers @kotlinx.serialization.Serializable class to.eyed.spettro.chat.** {
    static **$* *;
}
-keepclassmembers class to.eyed.spettro.chat.**$$serializer {
    *** INSTANCE;
}

# The Clerk SDK deserializes its API models reflectively via kotlinx.serialization.
-keep class com.clerk.api.** { *; }
-dontwarn com.clerk.api.**

# PdfBox-Android optionally decodes JPEG2000 images through a JP2 library we
# don't ship; PDFs embedding JPX images just skip those images at runtime.
-dontwarn com.gemalto.jp2.JP2Decoder

# Rhino (run-javascript sandbox) initializes its standard library and
# interpreter support classes reflectively; it also references desktop-only
# APIs (javax.script, dynamic bytecode generation) never touched in
# interpreted mode on Android.
-keep class org.mozilla.javascript.** { *; }
-dontwarn org.mozilla.javascript.**
-dontwarn javax.script.**
-dontwarn java.lang.invoke.**

# MediaPipe tasks-text (semantic recall embeddings): JNI entry points and
# protobuf-lite internals are reached reflectively/natively.
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.protobuf.**
