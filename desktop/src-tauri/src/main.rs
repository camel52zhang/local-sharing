use std::io::Write;
use std::sync::Mutex;
use std::time::{Duration, Instant};

use tauri::{
    menu::{Menu, MenuItem},
    tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent},
    Manager, RunEvent, WebviewUrl, WebviewWindowBuilder, WindowEvent,
};
use tauri_plugin_shell::process::CommandEvent;
use tauri_plugin_shell::ShellExt;

/// sidecar 子进程句柄，存到托管状态里，退出时 kill 释放端口。
type ChildStore = Mutex<Option<tauri_plugin_shell::process::CommandChild>>;

fn main() {
    let builder = tauri::Builder::default()
        .plugin(tauri_plugin_shell::init())
        .plugin(tauri_plugin_single_instance::init(|app, _argv, _cwd| {
            // 第二个实例：聚焦已存在的窗口，不起第二个服务器
            if let Some(w) = app.get_webview_window("main") {
                let _ = w.unminimize();
                let _ = w.show();
                let _ = w.set_focus();
            }
        }))
        // 接入开机自启插件，但默认不启用（不调用 enable()）
        .plugin(tauri_plugin_autostart::init(
            tauri_plugin_autostart::MacosLauncher::LaunchAgent,
            None,
        ))
        .setup(|app| {
            // 1) 探测空闲端口（系统分配，避免 8080 冲突）
            let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
            let port = listener.local_addr().unwrap().port();
            drop(listener);

            // 2) 数据目录（绝对路径，避免落进安装/资源目录）
            let data_dir = dirs::data_local_dir().unwrap().join("local-sharing");
            std::fs::create_dir_all(&data_dir).unwrap();

            // 3) 启动 Node sidecar（dist/index.js + node_modules 已在 resources/server）
            let resource_dir = app.path().resource_dir().unwrap();
            let server_dir = resource_dir.join("server");

            let (mut rx, child) = app
                .shell()
                .sidecar("node")
                .expect("未找到 node sidecar（请先运行 scripts/bundle-server.mjs）")
                .args(["dist/index.js"])
                .current_dir(&server_dir)
                .env("PORT", port.to_string())
                .env("HOST", "0.0.0.0") // 手机要连 LAN，必须 0.0.0.0
                .env("DATA_DIR", data_dir.to_string_lossy().to_string())
                .env("DEVICE_NAME", "我的电脑")
                .spawn()
                .expect("启动 Node sidecar 失败");

            app.manage(ChildStore::new(Some(child)));

            // sidecar 日志 -> %LOCALAPPDATA%/local-sharing/logs/server.log
            let log_path = data_dir.join("logs").join("server.log");
            tauri::async_runtime::spawn(async move {
                if let Some(parent) = log_path.parent() {
                    let _ = std::fs::create_dir_all(parent);
                }
                let mut file = std::fs::OpenOptions::new()
                    .create(true)
                    .append(true)
                    .open(&log_path)
                    .ok();
                while let Some(event) = rx.recv().await {
                    match event {
                        CommandEvent::Stdout(bytes) | CommandEvent::Stderr(bytes) => {
                            if let Some(f) = file.as_mut() {
                                let _ = f.write_all(&bytes);
                                let _ = f.flush();
                            }
                        }
                        CommandEvent::Error(e) => {
                            if let Some(f) = file.as_mut() {
                                let _ = writeln!(f, "[error] {}", e);
                                let _ = f.flush();
                            }
                        }
                        CommandEvent::Terminated(_) => {
                            if let Some(f) = file.as_mut() {
                                let _ = writeln!(f, "[terminated]");
                                let _ = f.flush();
                            }
                        }
                        _ => {}
                    }
                }
            });

            // 4) 轮询 /health 直到就绪
            let client = reqwest::blocking::Client::new();
            let health_url = format!("http://127.0.0.1:{}/health", port);
            let start = Instant::now();
            let mut ready = false;
            while start.elapsed() < Duration::from_secs(30) {
                if let Ok(resp) = client
                    .get(&health_url)
                    .timeout(Duration::from_secs(1))
                    .send()
                {
                    if resp.status().is_success() {
                        ready = true;
                        break;
                    }
                }
                std::thread::sleep(Duration::from_millis(300));
            }
            if !ready {
                eprintln!("警告: 30s 内未能从 {} 收到 /health 响应", health_url);
            }

            // 5) 建窗口（动态端口 + 外部本地 URL）
            let url = format!("http://127.0.0.1:{}", port)
                .parse::<url::Url>()
                .unwrap();
            let _window = WebviewWindowBuilder::new(app, "main", WebviewUrl::External(url))
                .title("local-sharing")
                .inner_size(1120.0, 760.0)
                .min_inner_size(820.0, 560.0)
                .build()
                .expect("创建主窗口失败");

            // 6) 系统托盘（⇄ 图标）
            let show_item =
                MenuItem::with_id(app, "show", "显示主窗口", true, None::<&str>).unwrap();
            let quit_item = MenuItem::with_id(app, "quit", "退出", true, None::<&str>).unwrap();
            let menu = Menu::with_items(app, &[&show_item, &quit_item]).unwrap();
            TrayIconBuilder::with_id("main-tray")
                .icon(app.default_window_icon().cloned().expect("缺少窗口图标"))
                .tooltip("local-sharing")
                .menu(&menu)
                .show_menu_on_left_click(false)
                .on_menu_event(|app, event| match event.id().as_ref() {
                    "show" => {
                        if let Some(w) = app.get_webview_window("main") {
                            let _ = w.unminimize();
                            let _ = w.show();
                            let _ = w.set_focus();
                        }
                    }
                    "quit" => {
                        app.exit(0);
                    }
                    _ => {}
                })
                .on_tray_icon_event(|tray, event| {
                    if let TrayIconEvent::Click {
                        button: MouseButton::Left,
                        button_state: MouseButtonState::Up,
                        ..
                    } = event
                    {
                        let app = tray.app_handle();
                        if let Some(w) = app.get_webview_window("main") {
                            let visible = w.is_visible().unwrap_or(false);
                            if visible {
                                let _ = w.hide();
                            } else {
                                let _ = w.unminimize();
                                let _ = w.show();
                                let _ = w.set_focus();
                            }
                        }
                    }
                })
                .build(app)
                .unwrap();

            Ok(())
        })
        // 关闭窗口 -> 隐藏到托盘，而非退出程序
        .on_window_event(|window, event| {
            if let WindowEvent::CloseRequested { api, .. } = event {
                api.prevent_close();
                let _ = window.hide();
            }
        });

    let app = builder
        .build(tauri::generate_context!())
        .expect("build error");

    // 退出清理：kill sidecar，释放端口，确保下次启动不报 EADDRINUSE
    app.run(|app_handle, event| match event {
        RunEvent::ExitRequested { .. } | RunEvent::Exit => {
            if let Some(child) = app_handle
                .state::<ChildStore>()
                .lock()
                .unwrap()
                .take()
            {
                let _ = child.kill();
            }
        }
        _ => {}
    });
}
