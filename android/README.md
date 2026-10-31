# The Claww for Android

Android app that bundles the real Rust `claw` engine (rusty-claude-cli, cross-compiled
for arm64 with the NDK) and runs it on the device. Built by `.github/workflows/android.yml`;
download the APK from the workflow run's artifacts.

## Setup (Ollama)
Run Ollama on a PC on the same Wi-Fi with `OLLAMA_HOST=0.0.0.0` so it
  listens on the network, then enter `http://<pc-ip>:11434` and a model you pulled
  (for example `qwen2.5-coder:7b`).

## Notes
- Each message runs `claw prompt`; the app prepends recent chat history so it keeps context.
- claw runs with `--dangerously-skip-permissions` inside the app's private workspace
  (Android's sandbox still applies). Files it writes live in the app's `workspace` folder.
- arm64 devices only.
