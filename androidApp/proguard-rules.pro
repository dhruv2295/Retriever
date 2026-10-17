# Only what R8 cannot see. R8 reported no missing classes (no missing_rules.txt); the other libraries ship their own rules.

# liblitertlm_jni.so: nativeComputeEmbedding finds these classes by name and calls their getters. litertlm-android has no consumer rules.
-keep class com.google.ai.edge.litertlm.InputData$Text { java.lang.String getText(); }
-keep class com.google.ai.edge.litertlm.InputData$Image { byte[] getBytes(); }
-keep class com.google.ai.edge.litertlm.InputData$Audio { byte[] getBytes(); }
# liblitertlm_jni.so: builds the result with NewObject; with no Java constructor call, R8 drops the constructor and getter.
-keep class com.google.ai.edge.litertlm.EmbeddingResponse { <init>(float[]); }
# liblitertlm_jni.so: thrown with ThrowNew on native errors; no Java code references it.
-keep class com.google.ai.edge.litertlm.LiteRtLmJniException { <init>(java.lang.String); }

# No rule for BenchmarkInfo: liblitertlm_jni.so has no JNI_OnLoad and no RegisterNatives, and its only reference to the class name sits inside nativeConversationGetBenchmarkInfo, part of the conversation API that Retriever never calls.

# ML Kit's ComponentDiscovery creates its registrars (TextRegistrar and others) by reflection from manifest names; the
# library rules keep only the class names, so R8 full mode drops the no-arg constructors and TextRecognition.getClient
# then throws in Application.onCreate.
-keep class * implements com.google.firebase.components.ComponentRegistrar { <init>(); }

# protobuf-javalite (via mediapipe tasks-core) ships no rules; its message schema looks fields up by their source name.
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { <fields>; }
