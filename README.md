# Agentic AI in Java (Google Gemini) - starter project

An LLM agent that loops, uses multiple tools (weather, calculator, save note),
remembers the conversation, and retries when Gemini is busy.

## Setup (macOS)

1. Install Java 17+ and Maven:
   ```
   brew install openjdk@21 maven
   ```
   Check with `java -version` and `mvn -version`.
   (If `java` isn't found after installing, run the `sudo ln -sfn ...` command that brew prints.)
2. Get a Gemini API key from https://aistudio.google.com
3. In this folder, copy the example env file and add your key:
   ```
   cp .env.example .env
   ```
   Then open `.env` and replace `your-gemini-api-key`.
4. Run:
   ```
   mvn -q compile exec:java
   ```
   The first run downloads dependencies, so it takes a minute.

## Run from an IDE

- **IntelliJ IDEA:** File > Open > select `pom.xml` > "Open as Project". Open `Main.java` and click the green Run arrow.
- **VS Code:** install the "Extension Pack for Java", open this folder, open `Main.java`, click "Run" above `main`.

Run from the project folder so `.env` is found. (You can also set `API_KEY` as a real environment variable instead.)

## Files

| File | Purpose |
|---|---|
| `Main.java` | Chat loop you run |
| `Agent.java` | The agent loop (LLM -> tools -> LLM), retries, fallback model |
| `Tools.java` | Tool methods + declarations. Add new tools here |
| `Env.java` | Reads `.env` |
| `pom.xml` | Dependencies (google-genai 1.72.0, jackson) |

## Add a new tool

In `Tools.java`:
1. Write a static method.
2. Add a `case` for it in `execute(...)`.
3. Add a `FunctionDeclaration` for it in `declarations()`.

## Troubleshooting

- `API key not found`: `.env` is missing or you ran from a different folder.
- `Model ... not found`: change `LLM_MODEL` in `.env` (try `gemini-flash-latest`).
- `Rate limit or quota reached`: wait a minute and retry.
- `503 / busy`: normal on free tiers; the agent retries and falls back to `LLM_FALLBACK_MODEL`.
- Keep the SDK below 2.0.0 (see `pom.xml`); 2.x changes automatic function calling.
