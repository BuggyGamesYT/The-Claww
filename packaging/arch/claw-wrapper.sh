#!/bin/sh
# /usr/bin/claw — wrapper installed by the-claww-git.
# Loads defaults (OLLAMA_HOST, CLAW_MODEL) from /etc/the-claww/env, then
# ~/.config/the-claww/env, without overriding anything already exported.
# Precedence for the model: --model flag > exported CLAW_MODEL > env files.
# Set CLAW_NO_DEFAULTS=1 to run the bare binary untouched.

if [ -z "${CLAW_NO_DEFAULTS:-}" ]; then
  _claww_load() {
    [ -r "$1" ] || return 0
    while IFS='=' read -r key value; do
      case "$key" in ''|\#*) continue ;; esac
      key=$(printf '%s' "$key" | tr -d '[:space:]')
      value=$(printf '%s' "$value" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//' -e 's/^"\(.*\)"$/\1/' -e "s/^'\(.*\)'$/\1/")
      case "$key" in
        OLLAMA_HOST|CLAW_MODEL)
          eval "_cur=\${$key:-}"
          if [ -z "$_cur" ] && [ -n "$value" ]; then
            export "$key=$value"
          fi ;;
      esac
    done < "$1"
  }
  _claww_load "${XDG_CONFIG_HOME:-$HOME/.config}/the-claww/env"
  _claww_load /etc/the-claww/env
fi

exec /usr/lib/the-claww/claw "$@"
