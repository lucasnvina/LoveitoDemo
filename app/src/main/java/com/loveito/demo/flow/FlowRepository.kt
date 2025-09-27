package com.loveito.demo.flow

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader

class FlowRepository(private val context: Context) {
    suspend fun loadDefinitionFromAsset(assetName: String): SeizureAssistantDefinition = withContext(Dispatchers.IO) {
        val json = context.assets.open(assetName).use { input ->
            input.bufferedReader().use(BufferedReader::readText)
        }
        FlowParser.parse(json)
    }
}

