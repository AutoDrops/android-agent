# Keep MediaPipe GenAI LLM Inference classes/methods (accessed via reflection in LocalLlm).
-keep class com.google.mediapipe.tasks.genai.llminference.** { *; }
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**
