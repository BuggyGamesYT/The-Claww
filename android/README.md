# The Claww for Android

Android app that bundles the real Rust `claw` engine (rusty-claude-cli) **and Ollama itself**,
both cross-compiled for arm64 with the NDK, so everything runs on the device. Built by
`.github/workflows/android.yml`; download the APK from the workflow run's artifacts.

## Use
1. Open Settings, keep "Run Ollama on this tablet" on, pick a small model
   (`llama3.2:1b`, `qwen2.5-coder:1.5b`, `gemma3:1b`) and tap Download model.
2. Chat. Turn the switch off to use Ollama on a PC instead (`OLLAMA_HOST=0.0.0.0` on the PC,
   then enter `http://<pc-ip>:11434`).

## Notes
- Ollama runs CPU-only; expect a few tokens per second on small models.
- Each message runs `claw prompt`; the app prepends recent chat history for context.
- claw runs with `--dangerously-skip-permissions` inside the app's private workspace.
- arm64 devices only. Ollama version pinned by `OLLAMA_REF` in the workflow.
