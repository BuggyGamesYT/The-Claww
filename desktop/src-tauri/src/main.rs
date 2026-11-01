#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]
//! The Claww desktop: thin native layer for the web UI. Runs the bundled `claw` CLI,
//! streams Ollama HTTP responses, saves attachments, and reports system info.

use base64::Engine;
use serde_json::json;
use std::collections::HashMap;
use std::io::{BufRead, BufReader, Read, Write};
use std::path::PathBuf;
use std::process::{Child, Command, Stdio};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use tauri::{AppHandle, Emitter, Manager, State};

#[derive(Default)]
struct Jobs {
    procs: Mutex<HashMap<String, Child>>,
    flags: Mutex<HashMap<String, Arc<AtomicBool>>>,
}

fn no_window(c: &mut Command) -> &mut Command {
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        c.creation_flags(0x0800_0000);
    }
    c
}

fn data_dir(app: &AppHandle) -> PathBuf {
    let d = app.path().app_data_dir().unwrap_or_else(|_| std::env::temp_dir().join("the-claww"));
    let _ = std::fs::create_dir_all(&d);
    d
}

fn exe_dir() -> PathBuf {
    let exe = std::env::current_exe().unwrap_or_default();
    exe.parent().map(|p| p.to_path_buf()).unwrap_or_default()
}

fn claw_path() -> PathBuf {
    exe_dir().join(if cfg!(windows) { "claw.exe" } else { "claw" })
}

/// claw's user config folder (its settings.json, MCP servers): CLAW_CONFIG_HOME, else ~/.claw.
fn claw_home() -> PathBuf {
    if let Some(p) = std::env::var_os("CLAW_CONFIG_HOME") { return PathBuf::from(p); }
    std::env::var_os("HOME").or_else(|| std::env::var_os("USERPROFILE")).map(PathBuf::from)
        .unwrap_or_else(std::env::temp_dir).join(".claw")
}

/// Write a file only this user can read (0600 on Linux). Used for the GitHub token and claw's settings.
fn write_private(path: &std::path::Path, data: &[u8]) -> Result<(), String> {
    if let Some(d) = path.parent() { std::fs::create_dir_all(d).map_err(|e| e.to_string())?; }
    let mut o = std::fs::OpenOptions::new();
    o.write(true).create(true).truncate(true);
    #[cfg(unix)]
    { use std::os::unix::fs::OpenOptionsExt; o.mode(0o600); }
    let mut f = o.open(path).map_err(|e| e.to_string())?;
    #[cfg(unix)]
    { use std::os::unix::fs::PermissionsExt; let _ = f.set_permissions(std::fs::Permissions::from_mode(0o600)); }
    f.write_all(data).map_err(|e| e.to_string())
}

#[tauri::command]
fn paths(app: AppHandle) -> serde_json::Value {
    let d = data_dir(&app);
    let ws = d.join("workspace");
    let _ = std::fs::create_dir_all(&ws);
    json!({ "data": d, "workspace": ws, "claw": claw_path(), "clawFound": claw_path().exists(), "resetLook": std::env::args().any(|a| a == "--reset-look") })
}

#[tauri::command]
fn ensure_dir(path: String) -> Result<String, String> {
    std::fs::create_dir_all(&path).map_err(|e| e.to_string())?;
    Ok(path)
}

#[tauri::command]
fn claw_run(app: AppHandle, jobs: State<'_, Jobs>, id: String, prompt: String, model: String, host: String, cwd: String) -> Result<(), String> {
    let _ = std::fs::create_dir_all(&cwd);
    let mut cmd = Command::new(claw_path());
    cmd.args(["--model", &model, "--dangerously-skip-permissions", "--output-format", "json", "prompt", &prompt])
        .current_dir(&cwd)
        .env("OLLAMA_HOST", &host)
        .env("NO_COLOR", "1")
        .env("TERM", "dumb")
        .env("CLAW_TOOL_EVENTS", "1")
        .env("CLAW_CONFIG_HOME", claw_home())
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());
    let mut child = no_window(&mut cmd).spawn().map_err(|e| format!("couldn't start claw: {e}"))?;
    let out = child.stdout.take();
    let err = child.stderr.take();
    jobs.procs.lock().unwrap().insert(id.clone(), child);
    let pump = |app: AppHandle, id: String, mut r: Box<dyn Read + Send>| {
        std::thread::spawn(move || {
            let mut buf = [0u8; 1024];
            while let Ok(n) = r.read(&mut buf) {
                if n == 0 { break; }
                let s = String::from_utf8_lossy(&buf[..n]).to_string();
                let _ = app.emit("claw-out", json!({ "id": id, "chunk": s }));
            }
        })
    };
    let t1 = out.map(|o| pump(app.clone(), id.clone(), Box::new(o)));
    let t2 = err.map(|e| pump(app.clone(), id.clone(), Box::new(e)));
    std::thread::spawn(move || {
        if let Some(t) = t1 { let _ = t.join(); }
        if let Some(t) = t2 { let _ = t.join(); }
        let jobs = app.state::<Jobs>();
        let child = jobs.procs.lock().unwrap().remove(&id);
        let code = child.and_then(|mut c| c.wait().ok()).and_then(|s| s.code()).unwrap_or(-1);
        let _ = app.emit("claw-done", json!({ "id": id, "code": code }));
    });
    Ok(())
}

#[tauri::command]
fn stop(jobs: State<'_, Jobs>, id: String) {
    if let Some(c) = jobs.procs.lock().unwrap().get_mut(&id) { let _ = c.kill(); }
    if let Some(f) = jobs.flags.lock().unwrap().get(&id) { f.store(true, Ordering::SeqCst); }
}

/// POST `body` to `url` and emit each streamed JSON line as `{event}` with `{id, line}`, then `{event}-done`.
#[tauri::command]
fn http_stream(app: AppHandle, jobs: State<'_, Jobs>, id: String, url: String, body: String, event: String) {
    let flag = Arc::new(AtomicBool::new(false));
    jobs.flags.lock().unwrap().insert(id.clone(), flag.clone());
    std::thread::spawn(move || {
        let res = ureq::post(&url).set("Content-Type", "application/json").timeout(std::time::Duration::from_secs(3600)).send_string(&body);
        let mut error = None;
        match res {
            Ok(r) | Err(ureq::Error::Status(_, r)) => {
                let reader = BufReader::new(r.into_reader());
                for line in reader.lines() {
                    if flag.load(Ordering::SeqCst) { break; }
                    match line {
                        Ok(l) if !l.trim().is_empty() => { let _ = app.emit(&event, json!({ "id": id, "line": l })); }
                        Ok(_) => {}
                        Err(e) => { error = Some(e.to_string()); break; }
                    }
                }
            }
            Err(e) => error = Some(e.to_string()),
        }
        app.state::<Jobs>().flags.lock().unwrap().remove(&id);
        let _ = app.emit(&format!("{event}-done"), json!({ "id": id, "error": error, "stopped": flag.load(Ordering::SeqCst) }));
    });
}

#[tauri::command]
fn http_get(url: String, timeout_ms: Option<u64>) -> Result<String, String> {
    ureq::get(&url)
        .set("User-Agent", "TheClaww/0.3 (desktop)")
        .timeout(std::time::Duration::from_millis(timeout_ms.unwrap_or(3000)))
        .call().map_err(|e| e.to_string())?.into_string().map_err(|e| e.to_string())
}

#[derive(Default)]
struct Awake(Mutex<Option<Child>>);

/// Keep the PC from sleeping while models download (Windows: SetThreadExecutionState; Linux: systemd-inhibit).
#[tauri::command]
fn keep_awake(state: State<'_, Awake>, on: bool) {
    #[cfg(windows)]
    {
        #[link(name = "kernel32")]
        extern "system" { fn SetThreadExecutionState(flags: u32) -> u32; }
        const ES_CONTINUOUS: u32 = 0x8000_0000; const ES_SYSTEM_REQUIRED: u32 = 0x0000_0001;
        unsafe { SetThreadExecutionState(if on { ES_CONTINUOUS | ES_SYSTEM_REQUIRED } else { ES_CONTINUOUS }); }
        let _ = &state;
    }
    #[cfg(not(windows))]
    {
        let mut g = state.0.lock().unwrap();
        if on && g.is_none() {
            *g = Command::new("systemd-inhibit")
                .args(["--what=sleep:idle", "--who=The Claww", "--why=Downloading AI models", "--mode=block", "sleep", "infinity"])
                .stdin(Stdio::null()).stdout(Stdio::null()).stderr(Stdio::null()).spawn().ok();
        } else if !on {
            if let Some(mut c) = g.take() { let _ = c.kill(); let _ = c.wait(); }
        }
    }
}

#[tauri::command]
fn save_file(dir: String, name: String, b64: String) -> Result<String, String> {
    let bytes = base64::engine::general_purpose::STANDARD.decode(b64).map_err(|e| e.to_string())?;
    std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
    let clean: String = name.chars().map(|c| if "/\\:*?\"<>|".contains(c) { '_' } else { c }).collect();
    let mut p = PathBuf::from(&dir).join(&clean);
    let mut k = 1;
    while p.exists() {
        let stem = std::path::Path::new(&clean).file_stem().map(|s| s.to_string_lossy().to_string()).unwrap_or_default();
        let ext = std::path::Path::new(&clean).extension().map(|s| format!(".{}", s.to_string_lossy())).unwrap_or_default();
        p = PathBuf::from(&dir).join(format!("{stem}-{k}{ext}"));
        k += 1;
    }
    std::fs::write(&p, bytes).map_err(|e| e.to_string())?;
    Ok(p.to_string_lossy().to_string())
}

#[tauri::command]
fn read_text(path: String, max: usize) -> String {
    std::fs::read(&path).map(|b| String::from_utf8_lossy(&b[..b.len().min(max)]).to_string()).unwrap_or_default()
}

#[tauri::command]
fn read_b64(path: String) -> Result<String, String> {
    std::fs::read(&path).map(|b| base64::engine::general_purpose::STANDARD.encode(b)).map_err(|e| e.to_string())
}

#[tauri::command]
fn sys_info() -> serde_json::Value {
    let mut s = sysinfo::System::new();
    s.refresh_memory();
    s.refresh_cpu();
    let cpu = s.cpus().first().map(|c| c.brand().trim().to_string()).unwrap_or_default();
    json!({
        "os": sysinfo::System::long_os_version().unwrap_or_default(),
        "host": sysinfo::System::host_name().unwrap_or_default(),
        "cpu": cpu,
        "cores": s.cpus().len(),
        "memTotal": s.total_memory(),
        "memFree": s.available_memory(),
    })
}

fn spawn_ollama_serve(ctx_len: u32, clear_when_auto: bool) -> Result<(), String> {
    let mut c = Command::new("ollama");
    c.arg("serve").stdin(Stdio::null()).stdout(Stdio::null()).stderr(Stdio::null());
    if ctx_len > 0 { c.env("OLLAMA_CONTEXT_LENGTH", ctx_len.to_string()); } else if clear_when_auto { c.env_remove("OLLAMA_CONTEXT_LENGTH"); }
    no_window(&mut c).spawn().map(|_| ()).map_err(|e| format!("couldn't run `ollama serve`: {e}. Is Ollama installed?"))
}

#[tauri::command]
fn start_ollama(ctx_len: Option<u32>) -> Result<(), String> {
    spawn_ollama_serve(ctx_len.unwrap_or(0), false)
}

fn mem_label(n: u32) -> String { if n == 0 { "Auto".into() } else { format!("{}K", n / 1024) } }

/// Memory setting: sets OLLAMA_CONTEXT_LENGTH for this PC's Ollama (0 = Auto, removes it) and restarts Ollama.
#[tauri::command]
async fn set_ollama_memory(ctx_len: u32) -> Result<String, String> {
    tauri::async_runtime::spawn_blocking(move || apply_memory(ctx_len)).await.map_err(|e| e.to_string())?
}

#[allow(unreachable_code)]
fn apply_memory(n: u32) -> Result<String, String> {
    let done = || format!("Memory set to {}. Ollama restarted.", mem_label(n));
    #[cfg(target_os = "linux")]
    {
        let has_unit = Command::new("systemctl").args(["list-unit-files", "ollama.service", "--no-legend"]).output()
            .map(|o| String::from_utf8_lossy(&o.stdout).contains("ollama.service")).unwrap_or(false);
        if !has_unit {
            // No Ollama system service: restart a user-run `ollama serve` with the new setting.
            let _ = Command::new("pkill").args(["-x", "ollama"]).status();
            std::thread::sleep(std::time::Duration::from_millis(900));
            spawn_ollama_serve(n, true)?;
            return Ok(format!("Memory set to {}. No Ollama system service was found, so The Claww restarted `ollama serve` with it.", mem_label(n)));
        }
        let dir = "/etc/systemd/system/ollama.service.d";
        let script = if n > 0 {
            format!("mkdir -p {dir} && printf '[Service]\\nEnvironment=\"OLLAMA_CONTEXT_LENGTH={n}\"\\n' > {dir}/claww-memory.conf && systemctl daemon-reload && systemctl restart ollama")
        } else {
            format!("rm -f {dir}/claww-memory.conf && systemctl daemon-reload && systemctl restart ollama")
        };
        let out = Command::new("pkexec").args(["sh", "-c", &script]).output()
            .map_err(|e| format!("couldn't run pkexec ({e}). Install polkit, or add Environment=\"OLLAMA_CONTEXT_LENGTH={n}\" to the ollama service yourself."))?;
        return match out.status.code() {
            Some(0) => Ok(done()),
            Some(126) | Some(127) => Err("the password prompt was cancelled or not allowed, so nothing changed.".into()),
            c => {
                let err = String::from_utf8_lossy(&out.stderr).trim().to_string();
                Err(if err.is_empty() { format!("the change failed (exit code {}).", c.unwrap_or(-1)) } else { err })
            }
        };
    }
    #[cfg(windows)]
    {
        let v = n.to_string();
        if n > 0 {
            let ok = no_window(Command::new("setx").args(["OLLAMA_CONTEXT_LENGTH", v.as_str()])).stdout(Stdio::null()).stderr(Stdio::null())
                .status().map(|s| s.success()).unwrap_or(false);
            if !ok { return Err("couldn't save OLLAMA_CONTEXT_LENGTH (setx failed).".into()); }
            std::env::set_var("OLLAMA_CONTEXT_LENGTH", &v);
        } else {
            // Auto: remove the user variable (fine if it was never set).
            let _ = no_window(Command::new("reg").args(["delete", "HKCU\\Environment", "/v", "OLLAMA_CONTEXT_LENGTH", "/f"])).stdout(Stdio::null()).stderr(Stdio::null()).status();
            std::env::remove_var("OLLAMA_CONTEXT_LENGTH");
        }
        for im in ["ollama app.exe", "ollama.exe"] {
            let _ = no_window(Command::new("taskkill").args(["/F", "/IM", im])).stdout(Stdio::null()).stderr(Stdio::null()).status();
        }
        std::thread::sleep(std::time::Duration::from_millis(1200));
        let app = std::env::var_os("LOCALAPPDATA").map(|d| PathBuf::from(d).join("Programs").join("Ollama").join("ollama app.exe"));
        if let Some(app) = app.filter(|p| p.exists()) {
            // Started from here, Ollama inherits this process's environment, so pass the new value directly.
            let mut c = Command::new(app);
            if n > 0 { c.env("OLLAMA_CONTEXT_LENGTH", &v); } else { c.env_remove("OLLAMA_CONTEXT_LENGTH"); }
            c.stdin(Stdio::null()).stdout(Stdio::null()).stderr(Stdio::null()).spawn().map_err(|e| format!("saved, but couldn't restart Ollama: {e}"))?;
        } else {
            spawn_ollama_serve(n, true).map_err(|e| format!("saved, but {e}"))?;
        }
        return Ok(done());
    }
    let _ = &done;
    Err("The Memory setting works on Windows and Linux.".into())
}

// ---------- GitHub: token kept privately, GitHub's MCP server added to claw's settings ----------
const GITHUB_MCP_URL: &str = "https://api.githubcopilot.com/mcp/";

fn github_token_file(app: &AppHandle) -> PathBuf { data_dir(app).join("github_token") }

fn set_claw_github(token: Option<&str>) -> Result<(), String> {
    let path = claw_home().join("settings.json");
    let mut v: serde_json::Value = match std::fs::read_to_string(&path) {
        Ok(s) if !s.trim().is_empty() => serde_json::from_str(&s)
            .map_err(|e| format!("claw's settings file ({}) isn't valid JSON, so it was left alone: {e}", path.display()))?,
        _ => json!({}),
    };
    if !v.is_object() { return Err(format!("claw's settings file ({}) isn't a JSON object, so it was left alone.", path.display())); }
    let obj = v.as_object_mut().unwrap();
    match token {
        Some(t) => {
            let servers = obj.entry("mcpServers").or_insert_with(|| json!({}));
            if !servers.is_object() { *servers = json!({}); }
            servers.as_object_mut().unwrap().insert("github".into(), json!({
                "type": "http",
                "url": GITHUB_MCP_URL,
                "headers": { "Authorization": format!("Bearer {t}") }
            }));
        }
        None => {
            let empty = match obj.get_mut("mcpServers").and_then(|s| s.as_object_mut()) {
                Some(s) => { s.remove("github"); s.is_empty() }
                None => false,
            };
            if empty { obj.remove("mcpServers"); }
            if obj.is_empty() && !path.exists() { return Ok(()); }
        }
    }
    let text = serde_json::to_string_pretty(&v).map_err(|e| e.to_string())?;
    write_private(&path, text.as_bytes())
}

#[tauri::command]
fn github_status(app: AppHandle) -> bool {
    std::fs::read_to_string(github_token_file(&app)).map(|s| !s.trim().is_empty()).unwrap_or(false)
}

/// Save the token (empty = remove it) and add or remove GitHub's MCP server in claw's settings.
#[tauri::command]
fn github_set(app: AppHandle, token: String) -> Result<String, String> {
    let t = token.trim();
    let f = github_token_file(&app);
    if t.is_empty() {
        let _ = std::fs::remove_file(&f);
        set_claw_github(None)?;
        Ok("GitHub removed.".into())
    } else {
        if t.chars().any(char::is_whitespace) { return Err("that doesn't look like a token (it has spaces in it).".into()); }
        write_private(&f, t.as_bytes())?;
        set_claw_github(Some(t))?;
        Ok("GitHub connected. claw can now use your repos, issues and pull requests.".into())
    }
}

/// Open a web link in the default browser.
#[tauri::command]
fn open_url(url: String) -> Result<(), String> {
    if !url.starts_with("https://") { return Err("only https links".into()); }
    #[cfg(windows)]
    let r = no_window(Command::new("rundll32").args(["url.dll,FileProtocolHandler", &url])).spawn();
    #[cfg(not(windows))]
    let r = Command::new("xdg-open").arg(&url).stdin(Stdio::null()).stdout(Stdio::null()).stderr(Stdio::null()).spawn();
    r.map(|_| ()).map_err(|e| e.to_string())
}

// ---------- Videos the web view can't play: open in the system player, or make a playable copy with ffmpeg ----------
fn ffmpeg_cmd() -> Command {
    let mut c = Command::new(if cfg!(windows) { "ffmpeg.exe" } else { "ffmpeg" });
    c.stdin(Stdio::null());
    no_window(&mut c);
    c
}

#[tauri::command]
fn ffmpeg_available() -> bool {
    ffmpeg_cmd().arg("-version").stdout(Stdio::null()).stderr(Stdio::null()).status().map(|s| s.success()).unwrap_or(false)
}

/// Open a saved file (a video, a document) with the app the system uses for it.
#[tauri::command]
fn open_path(path: String) -> Result<(), String> {
    let p = PathBuf::from(&path);
    if !p.is_file() { return Err("that file isn't there anymore".into()); }
    #[cfg(windows)]
    let r = no_window(Command::new("explorer").arg(&p)).spawn();
    #[cfg(target_os = "macos")]
    let r = Command::new("open").arg(&p).spawn();
    #[cfg(all(unix, not(target_os = "macos")))]
    let r = Command::new("xdg-open").arg(&p).stdin(Stdio::null()).stdout(Stdio::null()).stderr(Stdio::null()).spawn();
    r.map(|_| ()).map_err(|e| format!("couldn't open it: {e}"))
}

/// Cache file for a video, keyed by its path, size and modified time.
fn video_cache(app: &AppHandle, path: &std::path::Path, suffix: &str) -> PathBuf {
    use std::hash::{Hash, Hasher};
    let mut h = std::collections::hash_map::DefaultHasher::new();
    path.hash(&mut h);
    if let Ok(m) = std::fs::metadata(path) {
        m.len().hash(&mut h);
        if let Ok(t) = m.modified() { t.hash(&mut h); }
    }
    let d = app.path().app_cache_dir().unwrap_or_else(|_| data_dir(app).join("cache")).join("video");
    let _ = std::fs::create_dir_all(&d);
    d.join(format!("{:016x}{suffix}", h.finish()))
}

fn ffmpeg_err(out: &std::process::Output) -> String {
    let err = String::from_utf8_lossy(&out.stderr);
    let last = err.lines().rev().find(|l| !l.trim().is_empty()).unwrap_or("").trim().to_string();
    format!("ffmpeg couldn't read this video ({})", if last.is_empty() { format!("exit code {}", out.status.code().unwrap_or(-1)) } else { last })
}

/// Make (once) an H.264/AAC MP4 copy any web view can play, in the app's cache. Returns its path.
#[tauri::command]
async fn video_preview(app: AppHandle, path: String) -> Result<String, String> {
    tauri::async_runtime::spawn_blocking(move || -> Result<String, String> {
        let src = PathBuf::from(&path);
        if !src.is_file() { return Err("that video isn't there anymore".into()); }
        let dest = video_cache(&app, &src, ".mp4");
        if dest.is_file() && std::fs::metadata(&dest).map(|m| m.len() > 0).unwrap_or(false) { return Ok(dest.to_string_lossy().to_string()); }
        let part = dest.with_extension("part.mp4");
        let out = ffmpeg_cmd().arg("-y").arg("-i").arg(&src)
            .args(["-map", "0:v:0", "-map", "0:a:0?", "-c:v", "libx264", "-preset", "veryfast", "-crf", "26", "-pix_fmt", "yuv420p",
                   "-vf", "scale='min(1920,iw)':-2", "-c:a", "aac", "-b:a", "128k", "-movflags", "+faststart"])
            .arg(&part).stdout(Stdio::null()).stderr(Stdio::piped()).output()
            .map_err(|e| format!("couldn't run ffmpeg: {e}"))?;
        if !out.status.success() { let _ = std::fs::remove_file(&part); return Err(ffmpeg_err(&out)); }
        std::fs::rename(&part, &dest).map_err(|e| e.to_string())?;
        Ok(dest.to_string_lossy().to_string())
    }).await.map_err(|e| e.to_string())?
}

fn video_seconds(src: &std::path::Path) -> f64 {
    // `ffmpeg -i file` prints "Duration: 00:01:02.34" on stderr.
    let out = match ffmpeg_cmd().arg("-hide_banner").arg("-i").arg(src).stdout(Stdio::null()).stderr(Stdio::piped()).output() { Ok(o) => o, Err(_) => return 0.0 };
    let err = String::from_utf8_lossy(&out.stderr);
    err.split("Duration:").nth(1).and_then(|r| r.split(',').next()).map(|t| {
        t.trim().split(':').fold(0.0, |acc, part| acc * 60.0 + part.trim().parse::<f64>().unwrap_or(0.0))
    }).unwrap_or(0.0)
}

fn ffmpeg_jpeg(src: &std::path::Path, at: f64, max: u32) -> Result<Vec<u8>, String> {
    let scale = format!("scale='if(gt(iw,ih),min({max},iw),-2)':'if(gt(iw,ih),-2,min({max},ih))'");
    let out = ffmpeg_cmd().arg("-hide_banner").args(["-ss", &format!("{at:.3}")]).arg("-i").arg(src)
        .args(["-frames:v", "1", "-vf", &scale, "-q:v", "4", "-f", "image2pipe", "-vcodec", "mjpeg", "-"])
        .stdout(Stdio::piped()).stderr(Stdio::piped()).output().map_err(|e| format!("couldn't run ffmpeg: {e}"))?;
    if !out.status.success() || out.stdout.is_empty() { return Err(ffmpeg_err(&out)); }
    Ok(out.stdout)
}

/// `n` evenly spaced frames as base64 JPEGs (longest side `max`), for showing a video to a vision model.
#[tauri::command]
async fn video_frames(path: String, n: u32, max: u32) -> Result<Vec<String>, String> {
    tauri::async_runtime::spawn_blocking(move || -> Result<Vec<String>, String> {
        let src = PathBuf::from(&path);
        if !src.is_file() { return Err("that video isn't there anymore".into()); }
        let d = video_seconds(&src);
        let n = n.clamp(1, 12);
        let mut frames = Vec::new();
        let mut last_err = String::new();
        for k in 1..=n {
            let at = if d > 0.0 { d * k as f64 / (n as f64 + 1.0) } else { 0.0 };
            match ffmpeg_jpeg(&src, at, max.clamp(64, 2048)) {
                Ok(b) => frames.push(base64::engine::general_purpose::STANDARD.encode(b)),
                Err(e) => last_err = e,
            }
            if d <= 0.0 { break; }
        }
        if frames.is_empty() { Err(if last_err.is_empty() { "no frames could be read".into() } else { last_err }) } else { Ok(frames) }
    }).await.map_err(|e| e.to_string())?
}

/// A still frame (JPEG in the cache) to show for a video the web view can't decode.
#[tauri::command]
async fn video_poster(app: AppHandle, path: String) -> Result<String, String> {
    tauri::async_runtime::spawn_blocking(move || -> Result<String, String> {
        let src = PathBuf::from(&path);
        let dest = video_cache(&app, &src, ".jpg");
        if dest.is_file() { return Ok(dest.to_string_lossy().to_string()); }
        let b = ffmpeg_jpeg(&src, 0.5, 640).or_else(|_| ffmpeg_jpeg(&src, 0.0, 640))?;
        std::fs::write(&dest, b).map_err(|e| e.to_string())?;
        Ok(dest.to_string_lossy().to_string())
    }).await.map_err(|e| e.to_string())?
}

// ---------- Speech to text: whisper.cpp on this PC (bundled whisper-cli + a small English model) ----------
const WHISPER_MODEL: &str = "ggml-base.en-q5_1.bin";
const WHISPER_URL: &str = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.en-q5_1.bin";

fn whisper_bin() -> PathBuf { exe_dir().join(if cfg!(windows) { "whisper-cli.exe" } else { "whisper-cli" }) }
fn whisper_model(app: &AppHandle) -> PathBuf { data_dir(app).join("whisper").join(WHISPER_MODEL) }

#[tauri::command]
fn whisper_status(app: AppHandle) -> serde_json::Value {
    json!({ "bin": whisper_bin().exists(), "model": whisper_model(&app).exists() })
}

/// Download the speech model once, emitting `whisper-dl` {done, total} and then `whisper-dl-done` {error}.
#[tauri::command]
fn whisper_download(app: AppHandle) {
    std::thread::spawn(move || {
        let dest = whisper_model(&app);
        let res = (|| -> Result<(), String> {
            if let Some(d) = dest.parent() { std::fs::create_dir_all(d).map_err(|e| e.to_string())?; }
            let part = dest.with_extension("part");
            let resp = ureq::get(WHISPER_URL).set("User-Agent", "TheClaww/0.3 (desktop)")
                .timeout(std::time::Duration::from_secs(1800)).call().map_err(|e| e.to_string())?;
            let total: u64 = resp.header("Content-Length").and_then(|s| s.parse().ok()).unwrap_or(0);
            let mut r = resp.into_reader();
            let mut f = std::fs::File::create(&part).map_err(|e| e.to_string())?;
            let mut buf = vec![0u8; 1 << 16];
            let (mut done, mut last) = (0u64, 0u64);
            loop {
                let n = r.read(&mut buf).map_err(|e| e.to_string())?;
                if n == 0 { break; }
                f.write_all(&buf[..n]).map_err(|e| e.to_string())?;
                done += n as u64;
                if done - last > 1 << 20 { last = done; let _ = app.emit("whisper-dl", json!({ "done": done, "total": total })); }
            }
            drop(f);
            if done < 1_000_000 { let _ = std::fs::remove_file(&part); return Err("the download was cut short".into()); }
            std::fs::rename(&part, &dest).map_err(|e| e.to_string())
        })();
        let _ = app.emit("whisper-dl-done", json!({ "error": res.err() }));
    });
}

/// Transcribe a 16 kHz mono WAV (base64) with whisper-cli and return the words.
#[tauri::command]
async fn transcribe(app: AppHandle, wav: String) -> Result<String, String> {
    tauri::async_runtime::spawn_blocking(move || -> Result<String, String> {
        let bin = whisper_bin();
        if !bin.exists() { return Err("speech to text isn't included in this build.".to_string()); }
        let model = whisper_model(&app);
        if !model.exists() { return Err("the speech model isn't downloaded yet.".to_string()); }
        let bytes = base64::engine::general_purpose::STANDARD.decode(wav).map_err(|e| e.to_string())?;
        let tmp = data_dir(&app).join("whisper").join("recording.wav");
        write_private(&tmp, &bytes)?;
        let threads = std::thread::available_parallelism().map(|n| n.get()).unwrap_or(4).clamp(1, 8).to_string();
        let mut c = Command::new(&bin);
        c.arg("-m").arg(&model).arg("-f").arg(&tmp).args(["-nt", "-np", "-l", "en", "-t", threads.as_str()])
            .stdin(Stdio::null()).stdout(Stdio::piped()).stderr(Stdio::piped());
        let out = no_window(&mut c).output().map_err(|e| format!("couldn't run whisper: {e}"))?;
        let _ = std::fs::remove_file(&tmp);
        if !out.status.success() {
            let err = String::from_utf8_lossy(&out.stderr);
            let last = err.lines().rev().find(|l| !l.trim().is_empty()).unwrap_or("").trim().to_string();
            return Err(format!("whisper failed ({})", if last.is_empty() { format!("exit code {}", out.status.code().unwrap_or(-1)) } else { last }));
        }
        let text = String::from_utf8_lossy(&out.stdout).lines().map(str::trim).filter(|l| !l.is_empty()).collect::<Vec<_>>().join(" ");
        Ok(clean_transcript(&text))
    }).await.map_err(|e| e.to_string())?
}

/// Drop whisper's non-speech tags like [BLANK_AUDIO] or (music).
fn clean_transcript(s: &str) -> String {
    let mut out = String::new();
    let mut depth = 0i32;
    for ch in s.chars() {
        match ch {
            '[' | '(' => depth += 1,
            ']' | ')' if depth > 0 => depth -= 1,
            _ if depth == 0 => out.push(ch),
            _ => {}
        }
    }
    out.split_whitespace().collect::<Vec<_>>().join(" ")
}

/// Linux: let the page use the microphone (WebKitGTK denies it unless the app allows it).
#[cfg(target_os = "linux")]
fn allow_microphone(app: &tauri::App) {
    use webkit2gtk::glib::prelude::Cast;
    use webkit2gtk::{PermissionRequestExt, SettingsExt, UserMediaPermissionRequest, WebViewExt};
    if let Some(w) = app.get_webview_window("main") {
        let _ = w.with_webview(|wv| {
            let v = wv.inner();
            if let Some(s) = WebViewExt::settings(&v) { s.set_enable_media_stream(true); s.set_enable_webaudio(true); }
            v.connect_permission_request(|_, req| {
                if req.downcast_ref::<UserMediaPermissionRequest>().is_some() { req.allow(); true } else { false }
            });
        });
    }
}

/// Start at a sane size that always fits the screen: 1100x760, or 90% of the monitor's work area (the part not
/// covered by the taskbar/panel) when that's smaller, centred. Never starts maximized.
fn fit_to_screen(app: &tauri::App) {
    let Some(w) = app.get_webview_window("main") else { return };
    let mon = w.current_monitor().ok().flatten().or_else(|| w.primary_monitor().ok().flatten())
        .or_else(|| w.available_monitors().ok().and_then(|v| v.into_iter().next()));
    if let Some(m) = mon {
        let area = m.work_area();
        let sf = m.scale_factor().max(0.5);
        let (aw, ah) = (area.size.width as f64 / sf, area.size.height as f64 / sf);
        if aw > 100.0 && ah > 100.0 {
            let (mut ww, mut wh) = (1100.0_f64, 760.0_f64);
            if ww > aw * 0.95 { ww = aw * 0.9; }
            if wh > ah * 0.95 { wh = ah * 0.9; }
            let _ = w.set_min_size(Some(tauri::LogicalSize::new(360.0_f64.min(ww), 300.0_f64.min(wh))));
            let _ = w.set_size(tauri::LogicalSize::new(ww, wh));
            let x = area.position.x + ((area.size.width as f64 - ww * sf) / 2.0).max(0.0) as i32;
            let y = area.position.y + ((area.size.height as f64 - wh * sf) / 2.0).max(0.0) as i32;
            let _ = w.set_position(tauri::PhysicalPosition::new(x, y));
        }
    }
    let _ = w.show();
    let _ = w.set_focus();
}

fn main() {
    // Keep GPU rendering on by default (forcing DMA-BUF off pushed blur/video onto the CPU and spun fans).
    // `the-claww --safe-render` opts into the slower fallback for GPUs where WebKitGTK shows black or crashes.
    #[cfg(target_os = "linux")]
    if std::env::args().any(|a| a == "--safe-render") && std::env::var_os("WEBKIT_DISABLE_DMABUF_RENDERER").is_none() {
        std::env::set_var("WEBKIT_DISABLE_DMABUF_RENDERER", "1");
    }
    tauri::Builder::default()
        .manage(Jobs::default())
        .manage(Awake::default())
        .setup(|_app| {
            fit_to_screen(_app);
            #[cfg(target_os = "linux")]
            allow_microphone(_app);
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![paths, ensure_dir, claw_run, stop, http_stream, http_get, save_file, read_text, read_b64, sys_info, start_ollama, set_ollama_memory, keep_awake, github_status, github_set, open_url, open_path, ffmpeg_available, video_preview, video_frames, video_poster, whisper_status, whisper_download, transcribe])
        .run(tauri::generate_context!())
        .expect("error while running The Claww");
}
