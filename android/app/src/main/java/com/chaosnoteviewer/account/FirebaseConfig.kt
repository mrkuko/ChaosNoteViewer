package com.chaosnoteviewer.account

// The Firebase project this device syncs with. Every user brings their own project: nothing of it is built into the
// app, the user enters the Web API key and the project id (Firebase console, Project settings, General) on first start
// or in Settings, the same two values the desktop app takes. Kept in sync_state (api_key, project_id).
// [server]: one local server for everything instead of Google's (unit tests).
data class FirebaseConfig(
    val apiKey: String,
    val projectId: String,
    val emulator: Boolean = false,
    val server: String? = null,
) {
    val authUrl: String get() = when {
        server != null -> "$server/identitytoolkit.googleapis.com/v1"
        emulator -> "http://$EMULATOR_HOST:$EMULATOR_AUTH_PORT/identitytoolkit.googleapis.com/v1"
        else -> "https://identitytoolkit.googleapis.com/v1"
    }
    val tokenUrl: String get() = when {
        server != null -> "$server/securetoken.googleapis.com/v1"
        emulator -> "http://$EMULATOR_HOST:$EMULATOR_AUTH_PORT/securetoken.googleapis.com/v1"
        else -> "https://securetoken.googleapis.com/v1"
    }
    val firestoreUrl: String get() = when {
        server != null -> "$server/v1"
        emulator -> "http://$EMULATOR_HOST:$EMULATOR_FIRESTORE_PORT/v1"
        else -> "https://firestore.googleapis.com/v1"
    }

    companion object {
        // the PC's emulator through `adb reverse tcp:43099 tcp:43099` and `adb reverse tcp:43080 tcp:43080`
        // (ports from core/firebase/firebase.json; the project id matches `npm run emulators` there)
        const val EMULATOR_HOST = "localhost"
        const val EMULATOR_AUTH_PORT = 43099
        const val EMULATOR_FIRESTORE_PORT = 43080
        // offered in the Project ID field until the user types their own (same as core/firebase/.firebaserc)
        const val DEFAULT_PROJECT_ID = "chaosnoteviewer"
        val EMULATOR = FirebaseConfig("demo-key", "demo-chaosnoteviewer", emulator = true)

        // Google API keys: "AIza" and 35 more characters. Only the shape is checked; Firebase tells whether it works.
        private val KEY = Regex("AIza[0-9A-Za-z_-]{35}")
        // Google Cloud project ids: 6-30 lowercase letters, digits and hyphens, starting with a letter
        private val PROJECT = Regex("[a-z][a-z0-9-]{4,28}[a-z0-9]")

        // null when fine, otherwise what is wrong (shown under the field)
        fun keyProblem(key: String): String? = when {
            key.isBlank() -> "Enter the Web API key."
            !KEY.matches(key.trim()) -> "This does not look like a Web API key (it starts with AIza and has 39 characters)."
            else -> null
        }

        fun projectProblem(id: String): String? = when {
            id.isBlank() -> "Enter the project ID."
            !PROJECT.matches(id.trim()) -> "This does not look like a project ID (lowercase letters, digits and hyphens, e.g. my-notes-1a2b3)."
            else -> null
        }

        // The key and the project id found in a pasted text: the web app snippet from the Firebase console
        // (apiKey: "...", projectId: "...") or a google-services.json ("current_key", "project_id"). null = not found.
        fun extract(text: String): Pair<String?, String?> {
            fun find(vararg names: String): String? = names.firstNotNullOfOrNull { name ->
                Regex("\"?$name\"?\\s*[:=]\\s*[\"']([^\"']+)[\"']").find(text)?.groupValues?.get(1)?.trim()
            }
            val key = find("apiKey", "current_key") ?: KEY.find(text)?.value
            return key to find("projectId", "project_id")
        }
    }
}
