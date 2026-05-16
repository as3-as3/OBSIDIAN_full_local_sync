import os
import hashlib
import shutil
import time
import base64
from pathlib import Path
from fastapi import FastAPI, HTTPException, BackgroundTasks, UploadFile, File, WebSocket, WebSocketDisconnect
from pydantic import BaseModel
from typing import List, Dict, Optional
import uvicorn
import threading
import pystray
from PIL import Image, ImageDraw

# === Configuration ===
CONFIG_FILE = Path("vault_config.json")
DEFAULT_VAULT_PATH = Path(r"C:\Users\arneh\Documents\Obsidian\As3As3")

def load_vault_path() -> Path:
    if CONFIG_FILE.exists():
        import json
        try:
            with open(CONFIG_FILE, "r") as f:
                data = json.load(f)
                path_str = data.get("vault_path")
                if path_str:
                    return Path(path_str)
        except Exception:
            pass
    return DEFAULT_VAULT_PATH

def save_vault_path(path: Path):
    import json
    try:
        with open(CONFIG_FILE, "w") as f:
            json.dump({"vault_path": str(path)}, f)
    except Exception as e:
        print(f"Failed to save vault path: {e}")

VAULT_PATH = load_vault_path()
PORT = 8080
SERVICE_ACTIVE = True

app = FastAPI(title="Vault-Link Pro Server")

# === Models ===
class FileInfo(BaseModel):
    rel_path: str
    hash: str
    size: int
    modified: float

class ScanResponse(BaseModel):
    status: str
    files: List[FileInfo]
    timestamp: float

# === Core Logic ===
def get_file_hash(file_path):
    sha256_hash = hashlib.sha256()
    with open(file_path, "rb") as f:
        for byte_block in iter(lambda: f.read(4096), b""):
            sha256_hash.update(byte_block)
    return sha256_hash.hexdigest()

def safe_path(rel_path: str) -> Path:
    target = (VAULT_PATH / rel_path).resolve()
    if not str(target).startswith(str(VAULT_PATH.resolve())):
        raise ValueError("Security Violation: Path outside vault")
    return target

# === API Endpoints ===
@app.get("/api/status")
def get_status():
    return {"status": "active" if SERVICE_ACTIVE else "paused", "vault": str(VAULT_PATH)}

# === Real-Time WebSocket Connections for Sync Trigger ===
ACTIVE_CONNECTIONS: List[WebSocket] = []

@app.websocket("/api/ws/sync")
async def websocket_endpoint(websocket: WebSocket):
    await websocket.accept()
    ACTIVE_CONNECTIONS.append(websocket)
    print(f"Handy connected to Sync-WebSocket! Active connections: {len(ACTIVE_CONNECTIONS)}")
    try:
        while True:
            await websocket.receive_text()
    except WebSocketDisconnect:
        if websocket in ACTIVE_CONNECTIONS:
            ACTIVE_CONNECTIONS.remove(websocket)
        print(f"Handy disconnected from Sync-WebSocket. Active connections: {len(ACTIVE_CONNECTIONS)}")

@app.post("/api/sync/trigger")
async def trigger_sync():
    if not SERVICE_ACTIVE:
        raise HTTPException(status_code=503, detail="Service Paused")
    
    print(f"Broadcasting manual sync trigger to {len(ACTIVE_CONNECTIONS)} device(s)...")
    dead_connections = []
    for connection in ACTIVE_CONNECTIONS:
        try:
            await connection.send_text("trigger_sync")
        except Exception:
            dead_connections.append(connection)
            
    for dead in dead_connections:
        if dead in ACTIVE_CONNECTIONS:
            ACTIVE_CONNECTIONS.remove(dead)
        
    return {"status": "ok", "broadcasted_to": len(ACTIVE_CONNECTIONS)}

# === Performance & Cache ===
HASH_CACHE = {}
CACHE_FILE = Path("vault_hash_cache.json")

def load_cache():
    global HASH_CACHE
    if CACHE_FILE.exists():
        import json
        with open(CACHE_FILE, "r") as f:
            HASH_CACHE = json.load(f)

def save_cache():
    import json
    with open(CACHE_FILE, "w") as f:
        json.dump(HASH_CACHE, f)

def get_fast_hash(file_path: Path):
    rel_path = str(file_path.relative_to(VAULT_PATH))
    mtime = file_path.stat().st_mtime
    size = file_path.stat().st_size
    
    cache_entry = HASH_CACHE.get(rel_path)
    if cache_entry and cache_entry["mtime"] == mtime and cache_entry["size"] == size:
        return cache_entry["hash"]
    
    # Recalculate
    new_hash = get_file_hash(file_path)
    HASH_CACHE[rel_path] = {"hash": new_hash, "mtime": mtime, "size": size}
    return new_hash

@app.get("/api/sync/scan", response_model=ScanResponse)
def scan_vault():
    if not SERVICE_ACTIVE:
        raise HTTPException(status_code=503, detail="Service Paused")
    
    files_info = []
    for file_path in VAULT_PATH.rglob("*"):
        if file_path.is_file() and not file_path.name.startswith(".") and ".trash" not in file_path.parts:
            try:
                rel_path = str(file_path.relative_to(VAULT_PATH))
                files_info.append(FileInfo(
                    rel_path=rel_path,
                    hash=get_fast_hash(file_path),
                    size=file_path.stat().st_size,
                    modified=file_path.stat().st_mtime
                ))
            except Exception:
                continue
    save_cache()
    return ScanResponse(status="ok", files=files_info, timestamp=time.time())

# === Direct File Sync Endpoints ===
@app.post("/api/sync/push")
async def push_file(rel_path: str, last_modified: float, file: UploadFile = File(...)):
    try:
        target = safe_path(rel_path)
        target.parent.mkdir(parents=True, exist_ok=True)
        
        with open(target, "wb") as buffer:
            shutil.copyfileobj(file.file, buffer)
            
        os.utime(target, (last_modified, last_modified))
        
        # Invalidate/update cache entry
        new_hash = get_file_hash(target)
        HASH_CACHE[rel_path] = {"hash": new_hash, "mtime": last_modified, "size": target.stat().st_size}
        save_cache()
        
        return {"status": "success", "file": rel_path}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

from fastapi.responses import FileResponse

@app.get("/api/sync/download")
def download_file(rel_path: str):
    try:
        target = safe_path(rel_path)
        if not target.exists():
            raise HTTPException(status_code=404, detail="File not found")
        return FileResponse(target)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

# === Chunked Upload Support ===
@app.post("/api/sync/push/chunk")
async def push_chunk(rel_path: str, chunk_index: int, total_chunks: int, file: UploadFile = File(...)):
    temp_dir = VAULT_PATH / ".tmp_chunks" / hashlib.md5(rel_path.encode()).hexdigest()
    temp_dir.mkdir(parents=True, exist_ok=True)
    
    chunk_file = temp_dir / f"chunk_{chunk_index}"
    with open(chunk_file, "wb") as buffer:
        shutil.copyfileobj(file.file, buffer)
        
    # Check if all chunks present
    if len(list(temp_dir.glob("chunk_*"))) == total_chunks:
        # Reassemble
        target = safe_path(rel_path)
        with open(target, "wb") as outfile:
            for i in range(total_chunks):
                with open(temp_dir / f"chunk_{i}", "rb") as infile:
                    outfile.write(infile.read())
        shutil.rmtree(temp_dir)
        return {"status": "complete"}
    
    return {"status": "chunk_received"}


@app.delete("/api/sync/delete")
def delete_file(rel_path: str):
    try:
        target = safe_path(rel_path)
        if target.exists():
            trash_dir = VAULT_PATH / ".trash"
            trash_dir.mkdir(exist_ok=True)
            trash_target = trash_dir / f"{rel_path}.trash_{int(time.time())}"
            trash_target.parent.mkdir(parents=True, exist_ok=True)
            shutil.move(target, trash_target)
            return {"status": "deleted"}
        return {"status": "not_found"}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

# === Tray Icon Logic ===
def create_image(color):
    image = Image.new('RGB', (64, 64), color=(30, 30, 30))
    dc = ImageDraw.Draw(image)
    dc.ellipse([10, 10, 54, 54], fill=color)
    return image

def on_toggle(icon, item):
    global SERVICE_ACTIVE
    SERVICE_ACTIVE = not SERVICE_ACTIVE
    icon.icon = create_image('green' if SERVICE_ACTIVE else 'red')
    print(f"Service Active: {SERVICE_ACTIVE}")

def on_quit(icon, item):
    icon.stop()
    os._exit(0)

def on_sync_now(icon, item):
    import http.client
    try:
        conn = http.client.HTTPConnection("127.0.0.1", PORT)
        conn.request("POST", "/api/sync/trigger")
        res = conn.getresponse()

        if res.status == 200:
            print("Manual Sync triggered via System Tray!")
        else:
            print(f"Failed to broadcast sync: {res.status}")
    except Exception as e:
        print(f"Could not connect to trigger sync: {e}")

def on_change_vault(icon, item):
    global VAULT_PATH, HASH_CACHE
    import tkinter as tk
    from tkinter import filedialog
    
    def ask_dir():
        root = tk.Tk()
        root.withdraw()
        root.attributes("-topmost", True)
        selected = filedialog.askdirectory(
            initialdir=str(VAULT_PATH),
            title="Obsidian-Vault Ordner auswählen"
        )
        root.destroy()
        if selected:
            new_path = Path(selected)
            VAULT_PATH = new_path
            save_vault_path(new_path)
            HASH_CACHE.clear()
            save_cache()
            print(f"Vault-Pfad geändert auf: {VAULT_PATH}")
            try:
                icon.notify(f"Neuer Obsidian-Vault: {VAULT_PATH}", title="Vault geändert")
            except Exception:
                pass

    threading.Thread(target=ask_dir, daemon=True).start()

def run_tray():
    icon = pystray.Icon("VaultLink", create_image('green'), menu=pystray.Menu(
        pystray.MenuItem("Jetzt synchronisieren", on_sync_now),
        pystray.MenuItem("Vault-Ordner ändern...", on_change_vault),
        pystray.MenuItem("Sync Aktiv", on_toggle, checked=lambda item: SERVICE_ACTIVE),
        pystray.MenuItem("Beenden", on_quit)
    ))
    icon.run()

if __name__ == "__main__":
    load_cache()
    
    # Start Tray in separate thread
    tray_thread = threading.Thread(target=run_tray, daemon=True)
    tray_thread.start()
    
    # Start Server
    print(f"Starting Vault-Link Pro on port {PORT}...")
    try:
        uvicorn.run(app, host="0.0.0.0", port=PORT, log_level="info")
    finally:
        save_cache()
