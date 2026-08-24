package com.csr.c5w2a2

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.tensorflow.lite.Interpreter
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.InputStreamReader
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

data class Response(val contents: String)

object Emojifier {
    private const val TAG = "Emojifier"
    private const val MODEL_ASSET_NAME = "emojify_model.tflite"
    private const val VOCAB_ASSET_NAME = "glove_words.txt"
    private const val MAX_LEN = 10
    private const val NUM_CLASSES = 5

    // Emoji mapping matching Emoji_v3a.ipynb / emo_utils.py
    val EMOJI_DICTIONARY = mapOf(
        0 to "❤️", // 0: Heart
        1 to "⚾", // 1: Baseball
        2 to "😄", // 2: Smile
        3 to "😞", // 3: Disappointed
        4 to "🍴"  // 4: Food / Fork and knife
    )

    var isInitialized = false
        private set
    var activeBackend = "TFLite"
        private set

    private var interpreter: Interpreter? = null
    private val wordToIndex = HashMap<String, Int>(400000, 1.0f)

    fun initialize(context: Context) {
        Log.i(TAG, "Initializing Emojifier...")
        try {
            // Load TFLite Model
            val modelBuffer = loadModelFile(context, MODEL_ASSET_NAME)
            val options = Interpreter.Options()
            interpreter = Interpreter(modelBuffer, options)

            // Load Glove Vocabulary
            loadVocabulary(context, VOCAB_ASSET_NAME)

            isInitialized = true
            activeBackend = "TFLite"
            Log.i(TAG, "Emojifier successfully initialized with ${wordToIndex.size} vocabulary entries.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Emojifier", e)
            isInitialized = false
            throw e
        }
    }

    private fun loadModelFile(context: Context, assetName: String): MappedByteBuffer {
        val afd = context.assets.openFd(assetName)
        FileInputStream(afd.fileDescriptor).use { input ->
            val channel = input.channel
            return channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
        }
    }

    private fun loadVocabulary(context: Context, assetName: String) {
        wordToIndex.clear()
        context.assets.open(assetName).use { inputStream ->
            BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).useLines { lines ->
                var idx = 1
                for (line in lines) {
                    val word = line.trim()
                    if (word.isNotEmpty()) {
                        wordToIndex[word] = idx
                    }
                    idx++
                }
            }
        }
    }

    /**
     * Converts an input sentence into an array of indices corresponding to words in the sentence.
     * Corresponds to sentences_to_indices() from Emoji_v3a.ipynb.
     */
    fun sentencesToIndices(sentence: String, maxLen: Int = MAX_LEN): Array<IntArray> {
        val indices = Array(1) { IntArray(maxLen) }
        val words = sentence.lowercase().trim().split(Regex("\\s+"))

        var j = 0
        for (rawWord in words) {
            if (rawWord.isEmpty()) continue
            // Strip common punctuation if direct word is not in dictionary
            val word = if (wordToIndex.containsKey(rawWord)) {
                rawWord
            } else {
                rawWord.trim { !it.isLetterOrDigit() }
            }

            val index = wordToIndex[word]
            if (index != null) {
                indices[0][j] = index
                j++
                if (j >= maxLen) break
            }
        }
        return indices
    }

    /**
     * Runs model inference on the token index sequence and returns the predicted emoji index.
     * Corresponds to model.predict() from Emoji_v3a.ipynb.
     */
    fun predict(sentenceIndices: Array<IntArray>): Int {
        val interp = interpreter ?: throw IllegalStateException("Interpreter is not initialized")
        val outputProbs = Array(1) { FloatArray(NUM_CLASSES) }
        interp.run(sentenceIndices, outputProbs)

        val probs = outputProbs[0]
        var maxIdx = 0
        var maxProb = probs[0]
        for (i in 1 until probs.size) {
            if (probs[i] > maxProb) {
                maxProb = probs[i]
                maxIdx = i
            }
        }
        return maxIdx
    }

    fun labelToEmoji(label: Int): String {
        return EMOJI_DICTIONARY[label] ?: ""
    }

    /**
     * Preprocesses prompt with sentencesToIndices, predicts emoji using TFLite model,
     * and emits the sentence with the emoji appended at the end.
     */
    fun sendMessageAsync(prompt: String): Flow<Response>? {
        if (!isInitialized) return null

        return flow {
            val sentenceIndices = sentencesToIndices(prompt, MAX_LEN)
            val predictedLabel = predict(sentenceIndices)
            val emoji = labelToEmoji(predictedLabel)
            val resultText = if (emoji.isNotEmpty()) "$prompt $emoji" else prompt
            emit(Response(resultText))
        }
    }

    fun close() {
        interpreter?.close()
        interpreter = null
        wordToIndex.clear()
        isInitialized = false
        activeBackend = "None"
        Log.i(TAG, "Emojifier closed.")
    }
}

