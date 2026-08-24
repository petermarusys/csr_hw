package com.csr.c5w2a2

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.ui.unit.dp
import com.csr.c5w2a2.ui.theme.CsrTheme
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class Message(
    val isUser: Boolean,
    val content: String
)


@Composable
fun MessageItem(message: Message) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (message.isUser) Alignment.End else Alignment.Start
    ) {
        Text(text = message.content)
    }
}


class MainActivity : ComponentActivity() {
    private val messagesState = mutableStateOf<List<Message>>(emptyList())
    val messages: List<Message>
        get() = messagesState.value

    fun addMessage(message: Message) {
        messagesState.value = messagesState.value + message
    }

    fun updateLastMessage(updated: Message) {
        val list = messagesState.value.toMutableList()
        if (list.isNotEmpty()) {
            list[list.lastIndex] = updated
            messagesState.value = list
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CsrTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ChatScreen()
                }
            }
        }
    }

    @Composable
    fun ChatScreen() {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()

        // Use messages to keep UI in sync with backend
        val messages = messages
        var textInput by remember { mutableStateOf("") }
        val listState = rememberLazyListState()

        // Trigger initialization on launch
        LaunchedEffect(Unit) {
            if (!Emojifier.isInitialized) {
                try {
                    withContext(Dispatchers.IO) {
                        Emojifier.initialize(context)
                    }
                } catch (e: Exception) {
                    Log.e("Chat", "Initialization Error", e)
                }
            }
        }

        // Automatically scroll to the last item
        val lastMessageText = messages.lastOrNull()?.content
        LaunchedEffect(messages.size, lastMessageText) {
            if (messages.isNotEmpty()) {
                listState.animateScrollToItem(messages.size - 1, 10000)
            }
        }

        DisposableEffect(Unit) {
            onDispose { Emojifier.close() }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .imePadding()
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                items(messages) { message ->
                    MessageItem(message)
                }
            }

            // Bottom text input bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                @OptIn(ExperimentalComposeUiApi::class)
                InterceptPlatformTextInput(
                    interceptor = { _, _ ->
                        // Disable the on-screen soft keyboard by awaiting cancellation without showing IME
                        awaitCancellation()
                    }
                ) {
                    TextField(
                        value = textInput,
                        onValueChange = { textInput = it },
                        modifier = Modifier
                            .weight(1f)
                            .onKeyEvent { keyEvent ->
                                if (keyEvent.type == KeyEventType.KeyUp) {
                                    when (keyEvent.key) {
                                        Key.DirectionUp -> {
                                            scope.launch {
                                                listState.animateScrollBy(-500f)
                                            }
                                            true
                                        }
                                        Key.DirectionDown -> {
                                            scope.launch {
                                                listState.animateScrollBy(500f)
                                            }
                                            true
                                        }
                                        else -> false
                                    }
                                } else {
                                    false
                                }
                            },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = {
                            if (textInput.isNotBlank()) {
                                val prompt = textInput
                                textInput = ""
                                sendMessage(prompt, scope)
                            }
                        }),
                        singleLine = true
                    )
                }

                IconButton(
                    onClick = {
                        if (textInput.isNotBlank()) {
                            val prompt = textInput
                            textInput = ""
                            sendMessage(prompt, scope)
                        }
                    },
                    modifier = Modifier.padding(horizontal = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send Message",
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }
    }

    private fun sendMessage(
        prompt: String,
        scope: CoroutineScope,
    ) {
        if (prompt.isBlank()) return

        addMessage(Message(true, prompt.trim()))
        addMessage(Message(false, "..."))

        scope.launch {
            try {
                val flow = Emojifier.sendMessageAsync(prompt.trim())
                if (flow != null) {
                    withContext(Dispatchers.IO) {
                        var accumulatedText = ""
                        flow.collect { message ->
                            val chunk = message.contents.toString()
                            accumulatedText += chunk
                            withContext(Dispatchers.Main) {
                                updateLastMessage(Message(false, accumulatedText))
                            }
                        }
                    }
                } else {
                    updateLastMessage(Message(false, "Error: Conversation not initialized"))
                }
            } catch (e: Exception) {
                Log.e("Chat", "Model Error", e)
                updateLastMessage(Message(false, "Error: ${e.message}"))
            }
        }
    }
}
