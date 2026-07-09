# Go c-shared symbols are resolved via JNI at runtime (System.loadLibrary +
# external fun). They are not referenced from Java/Kotlin bytecode, so keep R8
# from stripping or renaming anything that the .so might touch.
-keep class com.deivid22srk.grokproxy.** { *; }
-keepclasseswithmembernames class * { native <methods>; }
