## How to install

### Android (Pixel Tablet, Pixel 10 Pro XL, any 64-bit Android)
1. On the device, open this page and tap **TheClaww.apk** to download it.
2. Open the download. If Android asks, allow **Install unknown apps** for your browser or Files app (Settings → Apps → Special app access → Install unknown apps), then go back and tap **Install**.
3. Updating: install the new APK over the old one; your chats and projects stay.
4. Open The Claww. Ollama runs inside the app, so nothing else is needed. Go to Settings (gear) or the ⇩ Models button, pick a model and download it.

### Arch Linux
1. Download **TheClaww-arch-x86_64.pkg.tar.zst**.
2. Install it (pacman pulls in the dependencies):
   ```
   sudo pacman -U ~/Downloads/TheClaww-arch-x86_64.pkg.tar.zst
   ```
3. Install and start Ollama if you haven't:
   ```
   sudo pacman -S ollama
   sudo systemctl enable --now ollama
   ```
   (Use `ollama-cuda` or `ollama-rocm` instead for NVIDIA or AMD GPU acceleration.)
4. Launch **The Claww** from your app menu, or run `the-claww`. Download a model from ⇩ Models.
5. Updating: `sudo pacman -U` the newer file. Uninstall: `sudo pacman -R the-claww`.

### Windows 10 / 11
1. Install Ollama for Windows from https://ollama.com/download (it starts in the tray).
2. Download **TheClaww-windows-x64-setup.exe** and run it. If SmartScreen says "Windows protected your PC", click **More info → Run anyway** (the installer isn't code-signed).
3. Open **The Claww** from the Start menu and download a model from ⇩ Models.
4. Updating: run the newer installer. Uninstall: Settings → Apps → The Claww.

### Other Linux (Debian/Ubuntu or any distro)
- Debian/Ubuntu: `sudo apt install ./TheClaww-linux-amd64.deb`
- Any distro: `chmod +x TheClaww-linux-x86_64.AppImage && ./TheClaww-linux-x86_64.AppImage`
- Install Ollama with `curl -fsSL https://ollama.com/install.sh | sh`.

### Using a PC's Ollama from the tablet (optional)
On the PC, let Ollama listen on your network (Arch: `sudo systemctl edit ollama`, add `Environment="OLLAMA_HOST=0.0.0.0"`, then `sudo systemctl restart ollama`). In the app's Settings, set the Ollama address to `http://<PC-IP>:11434`.
