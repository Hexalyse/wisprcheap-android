# R8 rules for the release build. Libraries (OkHttp, kotlinx.serialization, Compose) ship their own rules.

# Keep line numbers so crash stack traces in the log stay readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
