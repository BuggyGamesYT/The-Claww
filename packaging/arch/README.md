# Claw Code on Arch Linux

`the-claww-git` builds `claw` from source and installs:

| Path | What |
| --- | --- |
| `/usr/bin/claw` | wrapper that applies Ollama defaults, then runs the binary |
| `/usr/lib/the-claww/claw` | the real binary |
| `/etc/the-claww/env` | defaults: `OLLAMA_HOST=http://127.0.0.1:11434`, `CLAW_MODEL=qwen3-coder:30b` |
| `/usr/share/doc/the-claww/` | README, USAGE, CHANGELOG, `settings.example.json` |
| `/usr/share/licenses/the-claww-git/LICENSE` | MIT |

## Install

Prebuilt package (from a release or the "Arch Linux package" workflow artifact):

```sh
sudo pacman -U the-claww-git-*-x86_64.pkg.tar.zst
```

From source:

```sh
sudo pacman -S --needed base-devel git rust
git clone https://github.com/BuggyGamesYT/The-Claww.git
cd The-Claww/packaging/arch
makepkg -si
```

## Use with Ollama

```sh
sudo systemctl enable --now ollama   # or ollama-cuda / ollama-rocm
ollama pull qwen3-coder:30b
claw                                 # REPL on qwen3-coder:30b via local Ollama
claw prompt "explain this repo"      # one-shot
claw --model llama3.2                # any other pulled tag
```

Defaults never override what you've already exported. Precedence for the model:
`--model` > exported `CLAW_MODEL` > `~/.config/the-claww/env` > `/etc/the-claww/env`.
Run `CLAW_NO_DEFAULTS=1 claw` to bypass the wrapper defaults entirely
(for example to use Anthropic/OpenAI credentials instead of Ollama).

Optional short alias in `~/.claw/settings.json`:

```json
{ "aliases": { "coder": "qwen3-coder:30b" } }
```

then `claw --model coder`.
