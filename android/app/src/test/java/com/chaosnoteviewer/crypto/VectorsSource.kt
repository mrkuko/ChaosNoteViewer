package com.chaosnoteviewer.crypto

import org.json.JSONObject
import java.io.File

// On the PC the vectors are read from the repository (gradle runs the tests in android/app).
fun readVectors(): JSONObject = JSONObject(File("../../core/crypto/vectors.json").readText())
