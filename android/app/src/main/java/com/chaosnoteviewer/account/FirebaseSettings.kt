package com.chaosnoteviewer.account

import com.chaosnoteviewer.data.StateKeys
import com.chaosnoteviewer.data.StateStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// Which Firebase project this device uses, and whether the first-start setup was already shown.
// [fixed]: a debug build for the local emulator, the project can't be changed then.
// [onProjectChanged]: called when another project replaces a previous one: the cursor, the uploaded state and the
// encryption key belong to the old project's cloud copy and start again.
class FirebaseSettings(
    private val store: StateStore,
    private val fixed: FirebaseConfig? = null,
    private val onProjectChanged: suspend () -> Unit = {},
) {
    private val _config = MutableStateFlow(fixed)
    val config: StateFlow<FirebaseConfig?> = _config.asStateFlow()

    // null until loaded; false = show the first-start setup
    private val _setupSeen = MutableStateFlow<Boolean?>(if (fixed != null) true else null)
    val setupSeen: StateFlow<Boolean?> = _setupSeen.asStateFlow()

    val editable: Boolean get() = fixed == null
    val current: FirebaseConfig? get() = _config.value

    suspend fun load() {
        if (fixed != null) return
        val key = store.getState(StateKeys.API_KEY)
        val project = store.getState(StateKeys.PROJECT_ID)
        _config.value = if (key.isNotEmpty() && project.isNotEmpty()) FirebaseConfig(key, project) else null
        _setupSeen.value = _config.value != null || store.getState(StateKeys.SETUP_SEEN) == "1"
    }

    // Saves a checked key and project id. Returns what is wrong, or null when saved.
    suspend fun save(apiKey: String, projectId: String): String? {
        if (fixed != null) return null
        val key = apiKey.trim()
        val project = projectId.trim()
        (FirebaseConfig.keyProblem(key) ?: FirebaseConfig.projectProblem(project))?.let { return it }
        val previous = store.getState(StateKeys.PROJECT_ID)
        if (previous.isNotEmpty() && previous != project)
            onProjectChanged()
        store.setState(StateKeys.API_KEY, key)
        store.setState(StateKeys.PROJECT_ID, project)
        store.setState(StateKeys.SETUP_SEEN, "1")
        _config.value = FirebaseConfig(key, project)
        _setupSeen.value = true
        return null
    }

    // "Use on this device only": the setup is not shown again, sync can be set up later in Settings
    suspend fun skipSetup() {
        store.setState(StateKeys.SETUP_SEEN, "1")
        _setupSeen.value = true
    }

    // Sync off: the key goes, the project id stays so that coming back to the same project continues where it was.
    suspend fun remove() {
        if (fixed != null) return
        store.setState(StateKeys.API_KEY, "")
        _config.value = null
    }
}
