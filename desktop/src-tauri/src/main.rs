#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]
//! The Claww desktop: thin native layer for the web UI. Runs the bundled `claw` CLI,
//! streams Ollama HTTP responses, saves attachments, and reports system info.

use base64::Engine;
use serde_json::json;
use std::collections::HashMap;
use std::io::{BufRead, BufReader, Read};
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

fn claw_path() -> PathBuf {
    let exe = std::env::current_exe().unwrap_or_default();
    let dir = exe.parent().map(|p| p.to_path_buf()).unwrap_or_default();
    dir.join(if cfg!(windows) { "claw.exe" } else { "claw" })
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

#[tauri::command]
fn start_ollama() -> Result<(), String> {
    let mut c = Command::new("ollama");
    c.arg("serve").stdin(Stdio::null()).stdout(Stdio::null()).stderr(Stdio::null());
    no_window(&mut c).spawn().map(|_| ()).map_err(|e| format!("couldn't run `ollama serve`: {e}. Is Ollama installed?"))
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
        .invoke_handler(tauri::generate_handler![paths, ensure_dir, claw_run, stop, http_stream, http_get, save_file, read_text, read_b64, sys_info, start_ollama, keep_awake])
        .run(tauri::generate_context!())
        .expect("error while running The Claww");
}
