#!/bin/sh
# One-time setup for the Linux host: a private Python environment with the Claude SDK (uses the system GTK).
set -e
python3 -m venv --system-site-packages ~/.local/share/bluey/venv
~/.local/share/bluey/venv/bin/pip install -q anthropic faster-whisper  # Whisper turns the phone's recordings into text
# Ubuntu's own brotli is too old for the SDK's HTTP client; use a current one inside the venv.
~/.local/share/bluey/venv/bin/pip install -q --upgrade --ignore-installed brotli
echo "Done. Put your Anthropic key in ~/.config/bluey/anthropic_key (chmod 600), then run:"
echo "  ~/.local/share/bluey/venv/bin/python $(dirname "$(readlink -f "$0")")/bluey_host.py"
