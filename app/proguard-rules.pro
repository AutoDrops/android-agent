# Keep MediaPipe GenAI LLM Inference classes/methods (accessed via reflection in LocalLlm).
-keep class com.google.mediapipe.tasks.genai.llminference.** { *; }
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**

# v3.6: Vosk offline STT + JNA native bindings
-keep class org.vosk.** { *; }
-keep class com.sun.jna.** { *; }
-dontwarn com.sun.jna.**
-dontwarn org.vosk.**
