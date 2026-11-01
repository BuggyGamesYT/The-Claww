//! The Claww for Arch Linux: the same chat app as the Android version, on the desktop.
//! Each message runs `claw prompt` against Ollama, with recent chat history prepended.

use eframe::egui::{
    self, Align, Color32, ColorImage, Layout, Margin, RichText, Rounding, Sense, Stroke,
    TextureHandle, TextureOptions, Vec2,
};
use serde::{Deserialize, Serialize};
use std::io::{BufRead, BufReader, Read};
use std::path::PathBuf;
use std::process::{Child, Command, Stdio};
use std::sync::{Arc, Mutex};
use std::time::Duration;

#[derive(Clone, Copy)]
struct Theme {
    name: &'static str,
    bg: Color32,
    surface: Color32,
    surface2: Color32,
    fg: Color32,
    muted: Color32,
    accent: Color32,
    on_accent: Color32,
}

const fn rgb(r: u8, g: u8, b: u8) -> Color32 {
    Color32::from_rgb(r, g, b)
}

const THEMES: [Theme; 6] = [
    Theme { name: "Ember", bg: rgb(14, 14, 16), surface: rgb(28, 28, 32), surface2: rgb(40, 40, 46), fg: rgb(236, 236, 240), muted: rgb(150, 150, 160), accent: rgb(255, 122, 69), on_accent: Color32::BLACK },
    Theme { name: "Midnight", bg: rgb(10, 14, 26), surface: rgb(22, 30, 50), surface2: rgb(34, 44, 70), fg: rgb(228, 234, 248), muted: rgb(140, 152, 180), accent: rgb(110, 160, 255), on_accent: Color32::BLACK },
    Theme { name: "Forest", bg: rgb(12, 20, 16), surface: rgb(24, 36, 30), surface2: rgb(36, 52, 44), fg: rgb(228, 240, 232), muted: rgb(140, 165, 150), accent: rgb(110, 210, 140), on_accent: Color32::BLACK },
    Theme { name: "Grape", bg: rgb(18, 12, 26), surface: rgb(34, 24, 48), surface2: rgb(50, 36, 68), fg: rgb(240, 232, 250), muted: rgb(165, 150, 185), accent: rgb(190, 130, 255), on_accent: Color32::BLACK },
    Theme { name: "AMOLED", bg: Color32::BLACK, surface: rgb(20, 20, 20), surface2: rgb(34, 34, 34), fg: Color32::WHITE, muted: rgb(140, 140, 140), accent: Color32::WHITE, on_accent: Color32::BLACK },
    Theme { name: "Paper", bg: rgb(246, 244, 240), surface: Color32::WHITE, surface2: rgb(232, 229, 224), fg: rgb(28, 28, 30), muted: rgb(110, 110, 116), accent: rgb(230, 92, 40), on_accent: Color32::WHITE },
];

const WALLPAPERS: [(&str, Option<(Color32, Color32)>); 6] = [
    ("None", None),
    ("Sunset", Some((rgb(255, 120, 80), rgb(90, 30, 110)))),
    ("Aurora", Some((rgb(20, 180, 150), rgb(20, 30, 90)))),
    ("Ocean", Some((rgb(40, 120, 220), rgb(10, 20, 50)))),
    ("Dusk", Some((rgb(120, 80, 200), rgb(240, 120, 150)))),
    ("Your photo", None),
];

const GOOD: Color32 = rgb(80, 200, 120);
const AMBER: Color32 = rgb(240, 180, 60);

#[derive(Serialize, Deserialize, Clone)]
#[serde(default)]
struct Config {
    theme: String,
    wall: String,
    photo: String,
    dim: u8,
    builtin: bool,
    host: String,
    model: String,
    workspace: String,
}

impl Default for Config {
    fn default() -> Self {
        let model = std::env::var("CLAW_MODEL").unwrap_or_else(|_| "qwen3-coder:30b".into());
        Self {
            theme: "Ember".into(),
            wall: "None".into(),
            photo: String::new(),
            dim: 40,
            builtin: true,
            host: String::new(),
            model,
            workspace: home().join("TheClaww").to_string_lossy().into_owned(),
        }
    }
}

fn home() -> PathBuf {
    std::env::var_os("HOME").map(PathBuf::from).unwrap_or_else(|| PathBuf::from("/tmp"))
}

fn config_path() -> PathBuf {
    let base = std::env::var_os("XDG_CONFIG_HOME").map(PathBuf::from).unwrap_or_else(|| home().join(".config"));
    base.join("the-claww").join("gui.json")
}

impl Config {
    fn load() -> Self {
        std::fs::read_to_string(config_path()).ok().and_then(|s| serde_json::from_str(&s).ok()).unwrap_or_default()
    }
    fn save(&self) {
        let p = config_path();
        if let Some(d) = p.parent() {
            let _ = std::fs::create_dir_all(d);
        }
        if let Ok(s) = serde_json::to_string_pretty(self) {
            let _ = std::fs::write(p, s);
        }
    }
    fn host(&self) -> String {
        if self.builtin || self.host.trim().is_empty() {
            "http://127.0.0.1:11434".into()
        } else {
            self.host.trim().trim_end_matches('/').to_string()
        }
    }
    fn theme(&self) -> Theme {
        *THEMES.iter().find(|t| t.name == self.theme).unwrap_or(&THEMES[0])
    }
}

#[derive(Default, Clone)]
struct Status {
    reachable: bool,
    has_model: bool,
    models: Vec<String>,
}

#[derive(Default)]
struct Reply {
    text: String,
    done: bool,
    code: Option<i32>,
    error: Option<String>,
}

#[derive(Default)]
struct Pull {
    running: bool,
    status: String,
    progress: Option<f32>,
}

struct Msg {
    text: String,
    user: bool,
    stopped: bool,
}

struct App {
    cfg: Config,
    shared: Arc<Mutex<(String, String)>>, // (host, model) read by the status thread
    status: Arc<Mutex<Status>>,
    chat: Vec<Msg>,
    history: String,
    input: String,
    reply: Option<(Arc<Mutex<Reply>>, Arc<Mutex<Option<Child>>>, String)>,
    stopped: bool,
    pull: Arc<Mutex<Pull>>,
    show_settings: bool,
    show_look: bool,
    ollama: Option<Child>,
    tried_start: bool,
    wall_tex: Option<(String, TextureHandle)>,
    photo_error: Option<String>,
    scroll_to_bottom: bool,
}

fn ansi() -> &'static regex::Regex {
    static R: std::sync::OnceLock<regex::Regex> = std::sync::OnceLock::new();
    R.get_or_init(|| regex::Regex::new("\u{1b}\\[[0-9;?]*[ -/]*[@-~]").unwrap())
}

fn claw_bin() -> String {
    if let Ok(p) = std::env::var("CLAWW_CLAW_BIN") {
        return p;
    }
    if std::path::Path::new("/usr/lib/the-claww/claw").exists() {
        return "/usr/lib/the-claww/claw".into();
    }
    "claw".into()
}

fn normalize(model: &str) -> String {
    if model.contains(':') { model.to_string() } else { format!("{model}:latest") }
}

fn fetch_models(host: &str) -> Option<Vec<String>> {
    let v: serde_json::Value = ureq::get(&format!("{host}/api/tags"))
        .timeout(Duration::from_millis(1500))
        .call()
        .ok()?
        .into_json()
        .ok()?;
    Some(
        v["models"]
            .as_array()?
            .iter()
            .filter_map(|m| m["name"].as_str().map(String::from))
            .collect(),
    )
}

impl App {
    fn new(cc: &eframe::CreationContext<'_>) -> Self {
        let cfg = Config::load();
        let shared = Arc::new(Mutex::new((cfg.host(), cfg.model.clone())));
        let status = Arc::new(Mutex::new(Status::default()));
        {
            let shared = shared.clone();
            let status = status.clone();
            let ctx = cc.egui_ctx.clone();
            std::thread::spawn(move || loop {
                let (host, model) = shared.lock().unwrap().clone();
                let models = fetch_models(&host);
                let st = Status {
                    reachable: models.is_some(),
                    has_model: models.as_ref().map(|m| m.contains(&normalize(&model))).unwrap_or(false),
                    models: models.unwrap_or_default(),
                };
                *status.lock().unwrap() = st;
                ctx.request_repaint();
                std::thread::sleep(Duration::from_secs(3));
            });
        }
        let mut app = Self {
            cfg,
            shared,
            status,
            chat: vec![Msg { text: "Hi! I'm The Claww. What are we working on?".into(), user: false, stopped: false }],
            history: String::new(),
            input: String::new(),
            reply: None,
            stopped: false,
            pull: Arc::new(Mutex::new(Pull::default())),
            show_settings: false,
            show_look: false,
            ollama: None,
            tried_start: false,
            wall_tex: None,
            photo_error: None,
            scroll_to_bottom: false,
        };
        app.apply_style(&cc.egui_ctx);
        app
    }

    fn apply_style(&self, ctx: &egui::Context) {
        let t = self.cfg.theme();
        let light = t.bg.r() as u32 + t.bg.g() as u32 + t.bg.b() as u32 > 380;
        let mut v = if light { egui::Visuals::light() } else { egui::Visuals::dark() };
        v.panel_fill = t.bg;
        v.window_fill = t.surface;
        v.extreme_bg_color = t.surface2;
        v.faint_bg_color = t.surface2;
        v.override_text_color = Some(t.fg);
        v.selection.bg_fill = t.accent.gamma_multiply(0.5);
        v.hyperlink_color = t.accent;
        v.window_rounding = Rounding::same(16.0);
        v.window_stroke = Stroke::NONE;
        for w in [&mut v.widgets.inactive, &mut v.widgets.hovered, &mut v.widgets.active] {
            w.rounding = Rounding::same(12.0);
        }
        v.widgets.inactive.weak_bg_fill = t.surface2;
        v.widgets.inactive.bg_fill = t.surface2;
        v.widgets.hovered.weak_bg_fill = t.surface2.gamma_multiply(1.2);
        ctx.set_visuals(v);
        let mut style = (*ctx.style()).clone();
        style.spacing.item_spacing = Vec2::new(8.0, 8.0);
        style.spacing.button_padding = Vec2::new(12.0, 7.0);
        ctx.set_style(style);
    }

    fn sync_shared(&self) {
        *self.shared.lock().unwrap() = (self.cfg.host(), self.cfg.model.clone());
    }

    /// Start `ollama serve` once when "Run Ollama on this PC" is on and nothing answers.
    fn ensure_ollama(&mut self, reachable: bool) {
        if !self.cfg.builtin || reachable || self.tried_start {
            return;
        }
        self.tried_start = true;
        if let Ok(c) = Command::new("ollama").arg("serve").stdout(Stdio::null()).stderr(Stdio::null()).spawn() {
            self.ollama = Some(c);
        }
    }

    fn send(&mut self, ctx: &egui::Context) {
        if let Some((_, child, _)) = &self.reply {
            // Stop works even when the input box is empty.
            self.stopped = true;
            if let Some(c) = child.lock().unwrap().as_mut() {
                let _ = c.kill();
            }
            return;
        }
        let msg = self.input.trim().to_string();
        if msg.is_empty() {
            return;
        }
        self.input.clear();
        self.chat.push(Msg { text: msg.clone(), user: true, stopped: false });
        self.chat.push(Msg { text: "…".into(), user: false, stopped: false });
        self.scroll_to_bottom = true;
        self.stopped = false;

        let ctx_hist = if self.history.len() > 8000 {
            let mut start = self.history.len() - 8000;
            while !self.history.is_char_boundary(start) {
                start += 1;
            }
            self.history[start..].to_string()
        } else {
            self.history.clone()
        };
        let prompt = if ctx_hist.trim().is_empty() {
            msg.clone()
        } else {
            format!("Earlier in this conversation:\n{ctx_hist}\n\nNew message from the user:\n{msg}")
        };

        let reply = Arc::new(Mutex::new(Reply::default()));
        let child_slot: Arc<Mutex<Option<Child>>> = Arc::new(Mutex::new(None));
        let work = PathBuf::from(&self.cfg.workspace);
        let _ = std::fs::create_dir_all(&work);
        let spawn = Command::new(claw_bin())
            .args(["--model", &self.cfg.model, "--dangerously-skip-permissions", "prompt", &prompt])
            .current_dir(&work)
            .env("NO_COLOR", "1")
            .env("TERM", "dumb")
            .env("OLLAMA_HOST", self.cfg.host())
            .stdin(Stdio::null())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .spawn();
        match spawn {
            Err(e) => {
                let mut r = reply.lock().unwrap();
                r.error = Some(format!("Couldn't start claw: {e}"));
                r.done = true;
            }
            Ok(mut child) => {
                let streams: Vec<Box<dyn Read + Send>> = vec![
                    Box::new(child.stdout.take().unwrap()),
                    Box::new(child.stderr.take().unwrap()),
                ];
                *child_slot.lock().unwrap() = Some(child);
                let readers: Vec<_> = streams
                    .into_iter()
                    .map(|mut s| {
                        let reply = reply.clone();
                        let ctx = ctx.clone();
                        std::thread::spawn(move || {
                            let mut buf = [0u8; 512];
                            while let Ok(n) = s.read(&mut buf) {
                                if n == 0 {
                                    break;
                                }
                                let chunk = String::from_utf8_lossy(&buf[..n]).to_string();
                                reply.lock().unwrap().text.push_str(&chunk);
                                ctx.request_repaint();
                            }
                        })
                    })
                    .collect();
                let reply2 = reply.clone();
                let slot = child_slot.clone();
                let ctx = ctx.clone();
                std::thread::spawn(move || {
                    for r in readers {
                        let _ = r.join();
                    }
                    let code = loop {
                        let mut g = slot.lock().unwrap();
                        match g.as_mut().map(|c| c.try_wait()) {
                            Some(Ok(Some(s))) => break s.code(),
                            Some(Ok(None)) => {}
                            _ => break None,
                        }
                        drop(g);
                        std::thread::sleep(Duration::from_millis(50));
                    };
                    let mut r = reply2.lock().unwrap();
                    r.code = code;
                    r.done = true;
                    ctx.request_repaint();
                });
            }
        }
        self.reply = Some((reply, child_slot, msg));
    }

    /// Copy streamed output into the last bubble; finish the turn when claw exits.
    fn poll_reply(&mut self) {
        let Some((reply, _, msg)) = &self.reply else { return };
        let r = reply.lock().unwrap();
        let shown = ansi().replace_all(&r.text, "").trim().to_string();
        let finished = r.done;
        let err = r.error.clone();
        let code = r.code;
        drop(r);
        let msg = msg.clone();
        if let Some(last) = self.chat.last_mut() {
            if !shown.is_empty() {
                if last.text != shown {
                    self.scroll_to_bottom = true;
                }
                last.text = shown.clone();
            }
            if finished {
                if self.stopped {
                    last.stopped = true;
                    if shown.is_empty() {
                        last.text.clear();
                    }
                } else if let Some(e) = err {
                    last.text = e;
                } else if shown.is_empty() {
                    last.text = format!(
                        "claw stopped (code {}). Is a model downloaded?",
                        code.map(|c| c.to_string()).unwrap_or_else(|| "?".into())
                    );
                }
            }
        }
        if finished {
            self.history.push_str(&format!(
                "User: {msg}\nAssistant: {shown}{}\n\n",
                if self.stopped { " [reply cut off by user]" } else { "" }
            ));
            self.reply = None;
        }
    }

    fn start_pull(&self, ctx: &egui::Context) {
        let pull = self.pull.clone();
        let host = self.cfg.host();
        let name = self.cfg.model.clone();
        let ctx = ctx.clone();
        {
            let mut p = pull.lock().unwrap();
            p.running = true;
            p.status = "Starting download…".into();
            p.progress = None;
        }
        std::thread::spawn(move || {
            let res = ureq::post(&format!("{host}/api/pull")).send_json(serde_json::json!({ "name": name }));
            match res {
                Err(e) => pull.lock().unwrap().status = format!("Download failed: {e}"),
                Ok(resp) => {
                    for line in BufReader::new(resp.into_reader()).lines().map_while(Result::ok) {
                        let Ok(j) = serde_json::from_str::<serde_json::Value>(&line) else { continue };
                        let mut p = pull.lock().unwrap();
                        if let Some(e) = j["error"].as_str() {
                            p.status = e.to_string();
                            continue;
                        }
                        let st = j["status"].as_str().unwrap_or("").to_string();
                        let tot = j["total"].as_u64().unwrap_or(0);
                        let done = j["completed"].as_u64().unwrap_or(0);
                        if tot > 0 {
                            p.status = format!("{st}  {} / {} MB", done / 1_048_576, tot / 1_048_576);
                            p.progress = Some(done as f32 / tot as f32);
                        } else {
                            p.status = st;
                        }
                        drop(p);
                        ctx.request_repaint();
                    }
                }
            }
            pull.lock().unwrap().running = false;
            ctx.request_repaint();
        });
    }

    fn paint_wallpaper(&mut self, ctx: &egui::Context, ui: &egui::Ui) {
        let rect = ui.max_rect();
        let painter = ui.painter();
        let t = self.cfg.theme();
        let grad = WALLPAPERS.iter().find(|(n, _)| *n == self.cfg.wall).and_then(|(_, g)| *g);
        let mut painted = false;
        if let Some((top, bot)) = grad {
            let mut mesh = egui::Mesh::default();
            mesh.colored_vertex(rect.left_top(), top);
            mesh.colored_vertex(rect.right_top(), top);
            mesh.colored_vertex(rect.right_bottom(), bot);
            mesh.colored_vertex(rect.left_bottom(), bot);
            mesh.add_triangle(0, 1, 2);
            mesh.add_triangle(0, 2, 3);
            painter.add(egui::Shape::mesh(mesh));
            painted = true;
        } else if self.cfg.wall == "Your photo" && !self.cfg.photo.is_empty() {
            if self.wall_tex.as_ref().map(|(p, _)| p != &self.cfg.photo).unwrap_or(true) {
                match image::open(&self.cfg.photo) {
                    Ok(img) => {
                        let rgba = img.to_rgba8();
                        let size = [rgba.width() as usize, rgba.height() as usize];
                        let ci = ColorImage::from_rgba_unmultiplied(size, rgba.as_raw());
                        self.wall_tex = Some((self.cfg.photo.clone(), ctx.load_texture("wallpaper", ci, TextureOptions::LINEAR)));
                        self.photo_error = None;
                    }
                    Err(e) => {
                        self.photo_error = Some(format!("Couldn't open photo: {e}"));
                        self.cfg.wall = "None".into();
                        self.cfg.save();
                    }
                }
            }
            if let Some((_, tex)) = &self.wall_tex {
                // Cover the window, cropping the photo like a phone wallpaper.
                let [w, h] = tex.size();
                let (iw, ih) = (w as f32, h as f32);
                let (rw, rh) = (rect.width(), rect.height());
                let scale = (rw / iw).max(rh / ih);
                let (uw, uh) = (rw / (iw * scale), rh / (ih * scale));
                let uv = egui::Rect::from_min_size(egui::pos2((1.0 - uw) / 2.0, (1.0 - uh) / 2.0), Vec2::new(uw, uh));
                painter.image(tex.id(), rect, uv, Color32::WHITE);
                painted = true;
            }
        }
        if painted {
            let a = (self.cfg.dim as f32 / 100.0 * 255.0) as u8;
            painter.rect_filled(rect, 0.0, Color32::from_rgba_unmultiplied(t.bg.r(), t.bg.g(), t.bg.b(), a));
        } else {
            painter.rect_filled(rect, 0.0, t.bg);
        }
    }

    fn has_wall(&self) -> bool {
        self.cfg.wall != "None"
    }

    fn chip(ui: &mut egui::Ui, t: &Theme, label: &str) -> egui::Response {
        let b = egui::Button::new(RichText::new(label).size(13.0).color(t.fg)).fill(t.surface2).rounding(Rounding::same(16.0));
        ui.add(b)
    }

    fn settings_window(&mut self, ctx: &egui::Context) {
        let t = self.cfg.theme();
        let mut open = self.show_settings;
        let mut changed = false;
        let mut new_chat = false;
        let mut close = false;
        let mut do_pull = false;
        let status = self.status.lock().unwrap().clone();
        egui::Window::new("Settings")
            .open(&mut open)
            .collapsible(false)
            .resizable(false)
            .default_width(380.0)
            .anchor(egui::Align2::CENTER_CENTER, Vec2::ZERO)
            .show(ctx, |ui| {
                ui.set_width(380.0);
                changed |= ui.checkbox(&mut self.cfg.builtin, "Run Ollama on this PC").changed();
                ui.label(RichText::new("Remote Ollama host (when not on this PC)").size(12.0).color(t.muted));
                changed |= ui
                    .add_enabled(!self.cfg.builtin, egui::TextEdit::singleline(&mut self.cfg.host).hint_text("http://192.168.1.50:11434").desired_width(f32::INFINITY))
                    .changed();
                ui.label(RichText::new("Model").size(12.0).color(t.muted));
                changed |= ui
                    .add(egui::TextEdit::singleline(&mut self.cfg.model).hint_text("qwen3-coder:30b").desired_width(f32::INFINITY))
                    .changed();
                let running = self.pull.lock().unwrap().running;
                if ui.add_enabled(!running, egui::Button::new("Download model")).clicked() {
                    do_pull = true;
                }
                {
                    let p = self.pull.lock().unwrap();
                    if p.running {
                        ui.add(egui::ProgressBar::new(p.progress.unwrap_or(0.0)).fill(t.accent).animate(p.progress.is_none()));
                    }
                    if !p.status.is_empty() {
                        ui.label(RichText::new(&p.status).size(12.0).color(t.muted));
                    }
                }
                ui.label(RichText::new("Installed models (click to use)").size(12.0).color(t.muted));
                if !status.reachable {
                    ui.label("Ollama not reachable yet");
                } else if status.models.is_empty() {
                    ui.label("None yet");
                } else {
                    for m in &status.models {
                        let sel = normalize(&self.cfg.model) == *m;
                        if ui.selectable_label(sel, m).clicked() {
                            self.cfg.model = m.clone();
                            changed = true;
                        }
                    }
                }
                ui.label(RichText::new("Workspace folder (where claw reads and writes files)").size(12.0).color(t.muted));
                changed |= ui.add(egui::TextEdit::singleline(&mut self.cfg.workspace).desired_width(f32::INFINITY)).changed();
                ui.add_space(8.0);
                ui.horizontal(|ui| {
                    if ui.button("New chat").clicked() {
                        new_chat = true;
                        close = true;
                    }
                    ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
                        if ui.add(egui::Button::new(RichText::new("Done").color(t.on_accent)).fill(t.accent)).clicked() {
                            close = true;
                        }
                    });
                });
            });
        if do_pull {
            self.cfg.save();
            self.sync_shared();
            self.start_pull(ctx);
        }
        if changed {
            self.cfg.save();
            self.sync_shared();
            self.tried_start = false;
        }
        if new_chat && self.reply.is_none() {
            self.history.clear();
            self.chat.clear();
            self.chat.push(Msg { text: "New chat. What's next?".into(), user: false, stopped: false });
        }
        self.show_settings = open && !close;
        if !self.show_settings {
            self.cfg.save();
        }
    }

    fn swatch(ui: &mut egui::Ui, t: &Theme, name: &str, selected: bool, paint: impl FnOnce(&egui::Painter, egui::Rect)) -> bool {
        let mut clicked = false;
        ui.vertical(|ui| {
            ui.set_width(62.0);
            let (rect, resp) = ui.allocate_exact_size(Vec2::splat(54.0), Sense::click());
            let painter = ui.painter_at(rect);
            paint(&painter, rect);
            let stroke = if selected { Stroke::new(3.0, t.accent) } else { Stroke::new(1.0, t.muted) };
            ui.painter().rect_stroke(rect, 14.0, stroke);
            ui.label(RichText::new(name).size(11.0));
            clicked = resp.clicked();
        });
        clicked
    }

    fn look_window(&mut self, ctx: &egui::Context) {
        let t = self.cfg.theme();
        let mut open = self.show_look;
        let mut restyle = false;
        let mut close = false;
        egui::Window::new("Look")
            .open(&mut open)
            .collapsible(false)
            .resizable(false)
            .anchor(egui::Align2::CENTER_CENTER, Vec2::ZERO)
            .show(ctx, |ui| {
                ui.label(RichText::new("Theme").size(12.0).color(t.muted));
                ui.horizontal(|ui| {
                    for th in THEMES.iter() {
                        let sel = th.name == t.name;
                        if Self::swatch(ui, &t, th.name, sel, |p, r| gradient(p, r, th.surface, th.accent, true)) {
                            self.cfg.theme = th.name.into();
                            restyle = true;
                        }
                    }
                });
                ui.label(RichText::new("Wallpaper").size(12.0).color(t.muted));
                ui.horizontal(|ui| {
                    for (name, g) in WALLPAPERS.iter() {
                        let sel = *name == self.cfg.wall;
                        let clicked = Self::swatch(ui, &t, name, sel, |p, r| match g {
                            Some((a, b)) => gradient(p, r, *a, *b, false),
                            None => {
                                p.rect_filled(r, 14.0, if *name == "None" { t.bg } else { t.surface2 });
                            }
                        });
                        if clicked {
                            self.cfg.wall = (*name).into();
                            restyle = true;
                        }
                    }
                });
                if self.cfg.wall == "Your photo" {
                    ui.label(RichText::new("Photo file (PNG, JPEG or WebP)").size(12.0).color(t.muted));
                    ui.horizontal(|ui| {
                        let r = ui.add(egui::TextEdit::singleline(&mut self.cfg.photo).hint_text("/home/you/Pictures/wall.jpg").desired_width(260.0));
                        if r.changed() {
                            restyle = true;
                        }
                    });
                    ui.label(RichText::new("Tip: drag a picture onto the window to use it.").size(11.0).color(t.muted));
                }
                if let Some(e) = &self.photo_error {
                    ui.label(RichText::new(e).size(12.0).color(AMBER));
                }
                ui.label(RichText::new("Wallpaper dimming").size(12.0).color(t.muted));
                if ui.add(egui::Slider::new(&mut self.cfg.dim, 0..=90).suffix("%")).changed() {
                    restyle = true;
                }
                ui.add_space(6.0);
                ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
                    if ui.add(egui::Button::new(RichText::new("Done").color(t.on_accent)).fill(t.accent)).clicked() {
                        close = true;
                    }
                });
            });
        if restyle {
            self.cfg.save();
            self.apply_style(ctx);
        }
        self.show_look = open && !close;
    }
}

fn gradient(p: &egui::Painter, r: egui::Rect, a: Color32, b: Color32, diagonal: bool) {
    // Rounded clip is approximated by painting a rounded base then a gradient mesh inset.
    p.rect_filled(r, 14.0, b);
    let inner = r.shrink(3.0);
    let mut mesh = egui::Mesh::default();
    if diagonal {
        mesh.colored_vertex(inner.left_top(), a);
        mesh.colored_vertex(inner.right_top(), lerp(a, b));
        mesh.colored_vertex(inner.right_bottom(), b);
        mesh.colored_vertex(inner.left_bottom(), lerp(a, b));
    } else {
        mesh.colored_vertex(inner.left_top(), a);
        mesh.colored_vertex(inner.right_top(), a);
        mesh.colored_vertex(inner.right_bottom(), b);
        mesh.colored_vertex(inner.left_bottom(), b);
    }
    mesh.add_triangle(0, 1, 2);
    mesh.add_triangle(0, 2, 3);
    p.add(egui::Shape::mesh(mesh));
}

fn lerp(a: Color32, b: Color32) -> Color32 {
    let m = |x: u8, y: u8| ((x as u16 + y as u16) / 2) as u8;
    Color32::from_rgb(m(a.r(), b.r()), m(a.g(), b.g()), m(a.b(), b.b()))
}

impl eframe::App for App {
    fn update(&mut self, ctx: &egui::Context, _frame: &mut eframe::Frame) {
        self.poll_reply();
        let st = self.status.lock().unwrap().clone();
        self.ensure_ollama(st.reachable);
        let t = self.cfg.theme();
        let surface = if self.has_wall() { Color32::from_rgba_unmultiplied(t.surface.r(), t.surface.g(), t.surface.b(), 0xE0) } else { t.surface };

        // Dropping an image onto the window sets it as the wallpaper.
        let dropped: Option<PathBuf> = ctx.input(|i| i.raw.dropped_files.iter().find_map(|f| f.path.clone()));
        if let Some(p) = dropped {
            self.cfg.photo = p.to_string_lossy().into_owned();
            self.cfg.wall = "Your photo".into();
            self.cfg.save();
        }

        egui::CentralPanel::default().frame(egui::Frame::none()).show(ctx, |ui| {
            self.paint_wallpaper(ctx, ui);
            ui.add_space(14.0);

            // Header
            ui.horizontal(|ui| {
                ui.add_space(20.0);
                ui.vertical(|ui| {
                    ui.label(RichText::new("The Claww").size(22.0).strong().color(t.fg));
                    ui.horizontal(|ui| {
                        let (color, text) = if st.has_model && self.cfg.builtin {
                            (GOOD, format!("Ready · {} on this PC", self.cfg.model))
                        } else if st.has_model {
                            (GOOD, "Ready · connected to Ollama".to_string())
                        } else if st.reachable {
                            (AMBER, format!("Ollama running · {} not downloaded", self.cfg.model))
                        } else if self.cfg.builtin {
                            (t.muted, "Starting Ollama…".to_string())
                        } else {
                            (t.muted, "Can't reach Ollama".to_string())
                        };
                        let (r, _) = ui.allocate_exact_size(Vec2::splat(9.0), Sense::hover());
                        ui.painter().circle_filled(r.center(), 4.5, color);
                        ui.label(RichText::new(text).size(13.0).color(t.muted));
                    });
                });
                ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
                    ui.add_space(16.0);
                    if Self::chip(ui, &t, "Settings").clicked() {
                        self.show_settings = true;
                    }
                    if Self::chip(ui, &t, "Look").clicked() {
                        self.show_look = true;
                    }
                    let model = self.cfg.model.clone();
                    if Self::chip(ui, &t, &model).clicked() {
                        self.show_settings = true;
                    }
                });
            });
            ui.add_space(8.0);

            // Input pill pinned to the bottom, chat fills the rest.
            let busy = self.reply.is_some();
            egui::TopBottomPanel::bottom("input").frame(egui::Frame::none().inner_margin(Margin { left: 16.0, right: 16.0, top: 8.0, bottom: 16.0 })).show_inside(ui, |ui| {
                egui::Frame::none().fill(surface).rounding(Rounding::same(26.0)).inner_margin(Margin { left: 18.0, right: 4.0, top: 4.0, bottom: 4.0 }).show(ui, |ui| {
                    ui.horizontal(|ui| {
                        let w = ui.available_width() - 52.0;
                        let edit = egui::TextEdit::multiline(&mut self.input)
                            .hint_text(RichText::new("Message The Claww").color(t.muted))
                            .desired_rows(1)
                            .frame(false)
                            .desired_width(w)
                            .text_color(t.fg)
                            .font(egui::FontId::proportional(15.5));
                        let resp = ui.add(edit);
                        let enter = resp.has_focus() && ui.input(|i| i.key_pressed(egui::Key::Enter) && !i.modifiers.shift);
                        if enter {
                            // Drop the newline Enter just typed; Shift+Enter keeps it.
                            while self.input.ends_with('\n') {
                                self.input.pop();
                            }
                        }
                        let (rect, btn) = ui.allocate_exact_size(Vec2::splat(44.0), Sense::click());
                        ui.painter().circle_filled(rect.center(), 22.0, t.accent);
                        if busy {
                            ui.painter().rect_filled(egui::Rect::from_center_size(rect.center(), Vec2::splat(14.0)), 3.0, t.on_accent);
                        } else {
                            ui.painter().text(rect.center(), egui::Align2::CENTER_CENTER, "↑", egui::FontId::proportional(22.0), t.on_accent);
                        }
                        if btn.clicked() || enter {
                            self.send(ctx);
                            resp.request_focus();
                        }
                    });
                });
            });

            egui::CentralPanel::default().frame(egui::Frame::none()).show_inside(ui, |ui| {
                let stick = std::mem::take(&mut self.scroll_to_bottom);
                egui::ScrollArea::vertical().auto_shrink([false, false]).stick_to_bottom(true).show(ui, |ui| {
                    ui.add_space(4.0);
                    let max_w = (ui.available_width() - 32.0) * 0.8;
                    for m in &self.chat {
                        let layout = if m.user { Layout::right_to_left(Align::Min) } else { Layout::left_to_right(Align::Min) };
                        ui.with_layout(layout, |ui| {
                            ui.add_space(16.0);
                            egui::Frame::none()
                                .fill(if m.user { t.accent } else { surface })
                                .rounding(Rounding::same(18.0))
                                .inner_margin(Margin::symmetric(14.0, 10.0))
                                .show(ui, |ui| {
                                    ui.set_max_width(max_w);
                                    ui.with_layout(Layout::top_down(Align::Min), |ui| {
                                        if !m.text.is_empty() {
                                            ui.add(egui::Label::new(RichText::new(&m.text).size(15.5).color(if m.user { t.on_accent } else { t.fg })).selectable(true).wrap());
                                        }
                                        if m.stopped {
                                            ui.label(RichText::new("Not finished · you stopped this reply").italics().size(13.0).color(t.muted));
                                        }
                                    });
                                });
                        });
                        ui.add_space(4.0);
                    }
                    if stick {
                        ui.scroll_to_cursor(Some(Align::BOTTOM));
                    }
                });
            });
        });

        if self.show_settings {
            self.settings_window(ctx);
        }
        if self.show_look {
            self.look_window(ctx);
        }
        if self.reply.is_some() || self.pull.lock().unwrap().running {
            ctx.request_repaint_after(Duration::from_millis(100));
        }
    }

    fn on_exit(&mut self, _gl: Option<&eframe::glow::Context>) {
        self.cfg.save();
        if let Some((_, child, _)) = &self.reply {
            if let Some(c) = child.lock().unwrap().as_mut() {
                let _ = c.kill();
            }
        }
        // Only stop Ollama if this app started it.
        if let Some(mut c) = self.ollama.take() {
            let _ = c.kill();
        }
    }
}

fn main() -> eframe::Result<()> {
    if std::env::args().any(|a| a == "--version" || a == "-V") {
        println!("the-claww {}", env!("CARGO_PKG_VERSION"));
        return Ok(());
    }
    let opts = eframe::NativeOptions {
        viewport: egui::ViewportBuilder::default()
            .with_title("The Claww")
            .with_app_id("the-claww")
            .with_inner_size([960.0, 720.0])
            .with_min_inner_size([420.0, 480.0])
            .with_drag_and_drop(true),
        ..Default::default()
    };
    eframe::run_native("The Claww", opts, Box::new(|cc| Ok(Box::new(App::new(cc)))))
}
