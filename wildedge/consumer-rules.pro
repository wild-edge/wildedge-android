# WildEdge Android SDK — consumer ProGuard / R8 rules.
# Gradle merges this file into the host app's minification config automatically.

# ---- Core API ----
-keep class dev.wildedge.sdk.WildEdge { *; }
-keep interface dev.wildedge.sdk.WildEdgeClient { *; }
-keep class dev.wildedge.sdk.ModelHandle { public *; }
-keep class dev.wildedge.sdk.ModelInfo { *; }
-keep enum dev.wildedge.sdk.InputModality { *; }
-keep enum dev.wildedge.sdk.OutputModality { *; }
-keep class dev.wildedge.sdk.Accelerator { *; }
-keep class dev.wildedge.sdk.FeedbackType { *; }
-keep class dev.wildedge.sdk.FeedbackType$* { *; }
-keep class dev.wildedge.sdk.MemoryWarningLevel { *; }
-keep class dev.wildedge.sdk.MemoryWarningLevel$* { *; }
-keep enum dev.wildedge.sdk.SpanKind { *; }
-keep enum dev.wildedge.sdk.SpanStatus { *; }
-keep class dev.wildedge.sdk.Span { *; }
-keep class dev.wildedge.sdk.SpanContext { *; }

# ContentProvider used for auto-init — Android instantiates it by class name from the manifest.
-keep class dev.wildedge.sdk.WildEdgeInitProvider

# ---- Event meta classes ----
# These data classes are constructed by host app code and passed to trackInference via toMap().
-keep class dev.wildedge.sdk.events.** { *; }

# ---- Integration decorators ----
-keep class dev.wildedge.sdk.integrations.TFLiteDecorator { *; }
-keep class dev.wildedge.sdk.integrations.OrtDecorator { *; }
-keep class dev.wildedge.sdk.integrations.LiteRtEngineDecorator { *; }
-keep class dev.wildedge.sdk.integrations.PlayServicesTfliteDecorator { *; }

# ---- Kotlin extension functions ----
# Kotlin compiles file-level functions to static methods on a class named after the file (*Kt).
# Without these rules, R8 renames or removes the classes and all extension calls break.
-keep class dev.wildedge.sdk.*Kt { *; }
-keep class dev.wildedge.sdk.integrations.*Kt { *; }
