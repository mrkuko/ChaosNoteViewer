# Release build (R8): code is shrunk and obfuscated. The libraries (Room, WorkManager, Glance, ML Kit, coroutines)
# ship their own keep rules; the app itself uses no reflection.

# readable stack traces: keep file names and line numbers, hide the original file name
# (the mapping of the release, build/outputs/mapping/release/mapping.txt, turns a trace back into the real names)
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ML Kit (the QR scanner) finds its parts through Firebase components: it reads the registrar class names from the
# manifest and creates them with their no-argument constructor. The rule shipped by firebase-components keeps only
# the classes, and R8 full mode then drops the constructors, so the scanner crashed on start in release builds.
-keep class * implements com.google.firebase.components.ComponentRegistrar { void <init>(); }
