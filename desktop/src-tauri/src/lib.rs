use std::io::Read;

// Learn more about Tauri commands at https://tauri.app/develop/calling-rust/
#[tauri::command]
fn fetch_m3u(url: String) -> Result<String, String> {
    let response = ureq::get(&url)
        .timeout(std::time::Duration::from_secs(90))
        .call()
        .map_err(|e| e.to_string())?;
    
    let mut reader = response.into_reader();
    let mut body = String::new();
    reader.read_to_string(&mut body).map_err(|e| e.to_string())?;
    Ok(body)
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .invoke_handler(tauri::generate_handler![fetch_m3u])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
