package com.example.cliovision

import android.graphics.Bitmap
import android.util.Log
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.generationConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.cliovision.BuildConfig

class GeminiPipeline {

    private val config = generationConfig {
        temperature = 0.4f
        maxOutputTokens = 75
    }
    private val model = GenerativeModel(
        modelName = "gemini-2.5-flash",
        apiKey = BuildConfig.GEMINI_API_KEY,
        generationConfig = config
    )

    private val systemPrompt = """
    You are Clio, a campus tour guide for [Your University Name].
    Respond in 1-2 sentences only. Be warm and informative.
    If you recognize a campus building, name it and share one interesting fact.
    Never mention you are an AI.
    
    Campus buildings:
    - Emerson Electric Hall: Engineering building, houses ECE department
    - Parker Hall: Historic arts and sciences building, built 1923
    - Engineering Management: Business and engineering programs, renovated 2019
    (add your other buildings here in this short format)
""".trimIndent()

    // Send a camera frame + transcribed question to Gemini
    suspend fun sendImageAndQuestion(
        bitmap: Bitmap,
        question: String
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val prompt = content {
                image(bitmap)
                text("$systemPrompt\n\nVisitor question: $question")
            }

            // Override config at request level to guarantee token limit
            val requestConfig = generationConfig {
                temperature = 0.4f
                maxOutputTokens = 50
            }

            val response = model.generateContent(prompt)
            val responseText = response.text

            if (responseText != null) {
                Log.d("GeminiPipeline", "Response: $responseText")
                Result.success(responseText)
            } else {
                Result.failure(Exception("Empty response from Gemini"))
            }
        } catch (e: Exception) {
            Log.e("GeminiPipeline", "Request failed: ${e.message}")
            Result.failure(e)
        }
    }

    // Transcribe raw PCM audio bytes to text
    suspend fun transcribeAudio(audioBytes: ByteArray): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val prompt = content {
                    blob("audio/wav", audioBytes)
                    text("Transcribe exactly what is spoken. Return only the transcription.")
                }
                val response = model.generateContent(prompt)
                val transcription = response.text
                if (transcription != null) {
                    Log.d("GeminiPipeline", "Transcription: $transcription")
                    Result.success(transcription.trim())
                } else {
                    Result.failure(Exception("Empty transcription from Gemini"))
                }
            } catch (e: Exception) {
                Log.e("GeminiPipeline", "Transcription failed: ${e.message}")
                Result.failure(e)
            }
        }
}