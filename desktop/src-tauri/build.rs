fn main() {
    // Tauri 默认构建即会把 WebView2 loader 一并打包；本客户端采用
    // webviewInstallMode = downloadBootstrapper，目标机缺 WebView2 时自动下载，
    // 因此无需手动复制 WebView2Loader.dll。
    tauri_build::build();
}
