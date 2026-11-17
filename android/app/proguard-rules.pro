# Release build (R8): code is shrunk and obfuscated. The libraries (Room, WorkManager, Glance, Firebase, coroutines)
# ship their own keep rules; the app itself uses no reflection.

# readable stack traces: keep file names and line numbers, hide the original file name
# (the mapping of the release, build/outputs/mapping/release/mapping.txt, turns a trace back into the real names)
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
