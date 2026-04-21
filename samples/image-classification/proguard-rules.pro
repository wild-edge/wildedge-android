# compileOnly framework dependencies used by example files included via sourceSets.
# These classes are not in the APK at runtime, so suppress R8 missing-class warnings.
-dontwarn com.google.ai.client.generativeai.**
-dontwarn com.google.ai.edge.litertlm.**
-dontwarn ai.onnxruntime.**
-dontwarn com.google.android.gms.tasks.**
-dontwarn com.google.mlkit.**
