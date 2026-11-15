package com.chaosnoteviewer.crypto

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject

// On the phone the vectors are an asset of the test app (see app/build.gradle.kts).
fun readVectors(): JSONObject =
    JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("vectors.json").bufferedReader().readText())
