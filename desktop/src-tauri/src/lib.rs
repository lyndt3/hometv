use std::io::{Read, Write};
use std::net::TcpListener;
use std::thread;

// O proxy local corre na porta 18087 para contornar Mixed Content / CORS
fn start_proxy_server() {
    thread::spawn(|| {
        if let Ok(listener) = TcpListener::bind("127.0.0.1:18087") {
            for stream in listener.incoming() {
                if let Ok(mut stream) = stream {
                    thread::spawn(move || {
                        let mut buffer = [0; 1024];
                        if let Ok(n) = stream.read(&mut buffer) {
                            let request_str = String::from_utf8_lossy(&buffer[..n]);
                            if let Some(url_start) = request_str.find("/proxy?url=") {
                                let path = &request_str[url_start + 11..];
                                if let Some(url_end) = path.find(' ') {
                                    let encoded_url = &path[..url_end];
                                    if let Ok(decoded_str) = percent_encoding::percent_decode_str(encoded_url).decode_utf8() {
                                        let target_url = decoded_str.into_owned();
                                        if let Ok(response) = ureq::get(&target_url).call() {
                                                let content_type = response.header("Content-Type").unwrap_or("video/mp2t");
                                                let header_response = format!(
                                                    "HTTP/1.1 200 OK\r\n\
                                                     Access-Control-Allow-Origin: *\r\n\
                                                     Content-Type: {}\r\n\
                                                     Connection: close\r\n\r\n",
                                                    content_type
                                                );
                                                let _ = stream.write_all(header_response.as_bytes());
                                                
                                                let mut reader = response.into_reader();
                                                let mut pipe_buffer = [0; 8192];
                                                while let Ok(bytes_read) = reader.read(&mut pipe_buffer) {
                                                    if bytes_read == 0 { break; }
                                                    if stream.write_all(&pipe_buffer[..bytes_read]).is_err() {
                                                        break;
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        });
                    }
            }
        }
    });
}

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
    start_proxy_server();
    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .invoke_handler(tauri::generate_handler![fetch_m3u])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
