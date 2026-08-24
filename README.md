# Emojifier Android Application

An Android application built with **Jetpack Compose** and **TensorFlow Lite (TFLite)** that accepts user text prompts, analyzes them using a GloVe-based NLP model, and appends an appropriate emoji prediction.

---

## 1. Class Diagram

```mermaid
classDiagram
    direction TB

    class ComponentActivity {
        <<Android Framework>>
    }

    class MainActivity {
        -MutableState~List~Message~~ messagesState
        +List~Message~ messages
        +onCreate(savedInstanceState: Bundle?) void
        +addMessage(message: Message) void
        +updateLastMessage(updated: Message) void
        +ChatScreen() void
        -sendMessage(prompt: String, scope: CoroutineScope) void
    }

    class Message {
        +Boolean isUser
        +String content
    }

    class Emojifier {
        <<Singleton / Object>>
        -String TAG
        -String MODEL_ASSET_NAME
        -String VOCAB_ASSET_NAME
        -Int MAX_LEN
        -Int NUM_CLASSES
        +Map~Int, String~ EMOJI_DICTIONARY
        +Boolean isInitialized
        +String activeBackend
        -Interpreter interpreter
        -HashMap~String, Int~ wordToIndex
        +initialize(context: Context) void
        -loadModelFile(context: Context, assetName: String) MappedByteBuffer
        -loadVocabulary(context: Context, assetName: String) void
        +sentencesToIndices(sentence: String, maxLen: Int) Array~IntArray~
        +predict(sentenceIndices: Array~IntArray~) Int
        +labelToEmoji(label: Int) String
        +sendMessageAsync(prompt: String) Flow~Response~?
        +close() void
    }

    class Response {
        +String contents
    }

    class Interpreter {
        <<TensorFlow Lite>>
        +run(input: Object, output: Object) void
        +close() void
    }

    ComponentActivity <|-- MainActivity
    MainActivity ..> Message : manages / displays
    MainActivity ..> Emojifier : initializes & invokes inference
    Emojifier ..> Response : emits via Flow
    Emojifier --> Interpreter : owns & executes
```

---

## 2. Sequence Diagrams

### 2.1 Initialization Lifecycle

```mermaid
sequenceDiagram
    autonumber
    actor User
    participant Activity as MainActivity
    participant Compose as ChatScreen (Compose)
    participant Dispatcher as Dispatchers.IO
    participant Emojifier as Emojifier (Singleton)
    participant Assets as AssetManager

    User->>Activity: Launch App
    Activity->>Activity: onCreate() & setContent()
    Activity->>Compose: Render ChatScreen()
    Compose->>Compose: LaunchedEffect(Unit)
    Compose->>Dispatcher: withContext(Dispatchers.IO)
    Dispatcher->>Emojifier: initialize(context)
    
    rect rgb(240, 248, 255)
        note over Emojifier, Assets: Load Model & GloVe Vocabulary
        Emojifier->>Assets: openFd("emojify_model.tflite")
        Assets-->>Emojifier: MappedByteBuffer
        Emojifier->>Emojifier: Create TFLite Interpreter instance
        Emojifier->>Assets: open("glove_words.txt")
        Assets-->>Emojifier: InputStream
        Emojifier->>Emojifier: Populate wordToIndex HashMap
    end

    Emojifier-->>Dispatcher: isInitialized = true
    Dispatcher-->>Compose: Initialization complete
```

---

### 2.2 Message Input & Inference Flow

```mermaid
sequenceDiagram
    autonumber
    actor User
    participant UI as ChatScreen (TextField / Send Button)
    participant Activity as MainActivity
    participant IO as Dispatchers.IO
    participant Emojifier as Emojifier
    participant TFLite as TFLite Interpreter
    participant Main as Dispatchers.Main

    User->>UI: Types text and taps Send (or presses Enter)
    UI->>Activity: sendMessage(prompt, scope)
    Activity->>Activity: addMessage(Message(isUser=true, prompt))
    Activity->>Activity: addMessage(Message(isUser=false, "..."))
    
    Activity->>Emojifier: sendMessageAsync(prompt)
    Emojifier-->>Activity: Flow<Response>

    Activity->>IO: withContext(Dispatchers.IO) { flow.collect }
    
    rect rgb(245, 255, 250)
        note over Emojifier, TFLite: NLP Tokenization & Model Inference
        Emojifier->>Emojifier: sentencesToIndices(prompt, MAX_LEN=10)
        Emojifier->>TFLite: run(sentenceIndices, outputProbs)
        TFLite-->>Emojifier: float[1][5] class probabilities
        Emojifier->>Emojifier: argmax(probs) -> label
        Emojifier->>Emojifier: labelToEmoji(label)
        Emojifier->>IO: emit Response("$prompt $emoji")
    end

    IO->>Main: withContext(Dispatchers.Main)
    Main->>Activity: updateLastMessage(Message(isUser=false, resultText))
    Activity->>UI: Recompose LazyColumn (Auto-scroll to bottom)
```

---

### 2.3 Cleanup / Teardown Lifecycle

```mermaid
sequenceDiagram
    autonumber
    participant Activity as MainActivity
    participant Compose as ChatScreen
    participant Emojifier as Emojifier
    participant TFLite as TFLite Interpreter

    Activity->>Compose: DisposableEffect onDispose / Activity Destroyed
    Compose->>Emojifier: close()
    Emojifier->>TFLite: close()
    Emojifier->>Emojifier: Clear wordToIndex cache & reset state
```

---

## 3. Architecture & Key Components

| Component | Responsibility |
| :--- | :--- |
| **`MainActivity`** | Hosts the Jetpack Compose environment, holds chat state (`messagesState`), handles key events, and launches inference coroutines. |
| **`ChatScreen` / `MessageItem`** | Composable UI components displaying message bubbles in a `LazyColumn` with auto-scroll and a text entry field. |
| **`Emojifier`** | Singleton managing the TensorFlow Lite `Interpreter`, mapping words to GloVe embedding indices, performing tokenization, running inference, and mapping class labels to emojis (❤️, ⚾, 😄, 😞, 🍴). |
| **`Response` / `Message`** | Domain models representing user prompts and model responses. |
