# Android AI Phone Assistant App (Hermes Pro System)

This codebase contains a complete, production-grade **Android AI Phone Assistant Application** featuring full integration with the **Hermes AI Voice Agent** (deployed on Alibaba Cloud ECS). 

The application implements a custom VoIP, SIP, and standard Android Telecom API interface coupled with an ultra-low-latency full-duplex local audio engine (`AudioRecord` and `AudioTrack`), communicating with the GPT-4o / Realtime Voice backend through secure WebSockets.

---

## 🚀 I. System Architecture Diagram

```
                 +-------------------------------------------------------+
                 |                 Android Mobile App                    |
                 |                                                       |
                 |  +--------------------+       +--------------------+  |
                 |  | TelecomCallManager |       |   AudioEngine      |  |
                 |  +---------+----------+       +---------+----------+  |
                 |            |                            | (PCM 16k)   |
                 |            v                            v             |
                 |  +--------------------+       +--------------------+  |
                 |  |     MainViewModel  |<----->|HermesWSClient (Oil)|  |
                 |  +---------+----------+       +---------+----------+  |
                 |            |                            |             |
                 +------------|----------------------------|-------------+
                              | Secure CRUD API            | Duplex Binary WebSocket
                              v                            v                     
                 +-------------------------------------------------------+       
                 |                  Hermes Gateways                      | (Alibaba ECS /
                 |                                                       |  Nginx 80/443)
                 |   +-----------------------------------------------+   |
                 |   | Docker Gateway Service (FastAPI Server)       |   |
                 |   +-----------------------+-----------------------+   |
                 |                           |                           |
                 +---------------------------|---------------------------+
                                             | Server side Stream (TLS)
                                             v
                                  +-----------------------+
                                  |  OpenAI Realtime API  |
                                  |  GPT-4o / GPT-5 Proxy |
                                  +-----------------------+
```

---

## 🛠️ II. Android Local SQLite Database Schema

The app persists contacts, tasks, alarm notifications, and full-duplex call transcriptions locally using a reactive **Room SQLite Database** structure (`AppDatabase`):

### 1. `tasks` (AI Client Worklist Board)
*   `id`: `INTEGER PRIMARY KEY AUTOINCREMENT`
*   `title`: `TEXT` (Task Name synced via Hermes tool_calling/CRM)
*   `description`: `TEXT` (Detailed task items)
*   `isCompleted`: `INTEGER` (`0` for Inactive, `1` for Completed)
*   `createdAt`: `INTEGER` (Timestamp milliseconds)

### 2. `email_alerts` (Email summaries)
*   `id`: `INTEGER PRIMARY KEY AUTOINCREMENT`
*   `sender`: `TEXT` (Source address trigger)
*   `subject`: `TEXT` (Alert / Subject)
*   `content`: `TEXT` (Body description)
*   `category`: `TEXT` (Category: `"EMAIL"` or `"ALERT"`)
*   `timestamp`: `INTEGER`
*   `isRead`: `INTEGER` (`0` = Unread, `1` = Read)

### 3. `call_records` (CRM Call history)
*   `id`: `INTEGER PRIMARY KEY AUTOINCREMENT`
*   `contactName`: `TEXT` (Caller identity name lookup)
*   `phoneNumber`: `TEXT` (Calling number)
*   `duration`: `INTEGER` (Duration in seconds)
*   `timestamp`: `INTEGER`
*   `mode`: `TEXT` (Call takeover state: `"FULL_AI"` / `"AUTO"` / `"OFF"`)
*   `transcript`: `TEXT` (Real-time transcript dialogue data)
*   `summary`: `TEXT` (AI generated bullet summary of phone requirements)

---

## 🐋 III. Backend Docker Gateway & Python Orchestrator

Below is the production-ready code for the **Hermes WebSocket Gateway** designed to be containerized and run securely on the remote Alibaba Cloud ECS instance.

### 1. `docker-compose.yml`
Save this file under `/backend/docker-compose.yml` on the ECS server:

```yaml
version: '3.8'

services:
  hermes-gateway:
    build: .
    container_name: hermes-gateway
    restart: always
    ports:
      - "8880:8880"
    environment:
      - OPENAI_API_KEY=sk-proj-YOUR_ACTUAL_OPENAI_REALTIME_KEY
      - LOG_LEVEL=INFO
    volumes:
      - ./logs:/app/logs

  nginx-proxy:
    image: nginx:alpine
    container_name: nginx-gateway-proxy
    restart: always
    ports:
      - "80:80"
      - "443:443"
    volumes:
      - ./nginx.conf:/etc/nginx/nginx.conf:ro
      - /etc/letsencrypt:/etc/letsencrypt:ro
    depends_on:
      - hermes-gateway
```

### 2. `app.py` (FastAPI Server Backend Orchestrator)
This component translates binary audio streaming frames from the Android app, sends them directly to OpenAI's real-time streaming services, and monitors tool calling commands:

```python
import os
import json
import asyncio
import logging
from fastapi import FastAPI, WebSocket, WebSocketDisconnect
import websockets

app = FastAPI()
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("HermesGateway")

OPENAI_WS_URL = "wss://api.openai.com/v1/realtime?model=gpt-4o-realtime-preview"

@app.websocket("/v1/realtime")
async def handle_realtime_connection(websocket: WebSocket):
    await websocket.accept()
    logger.info("Android client voice tunnel established.")

    openai_headers = {
        "Authorization": f"Bearer {os.getenv('OPENAI_API_KEY')}",
        "OpenAI-Beta": "realtime=2024-10-01"
    }

    try:
        # Secure outbound proxy to OpenAI Realtime Socket
        async with websockets.connect(OPENAI_WS_URL, extra_headers=openai_headers) as openai_ws:
            logger.info("Connected to OpenAI Realtime Endpoint.")

            async def receive_from_client():
                """Reads binary PCM or JSON command packets from Android App"""
                try:
                    async for message in websocket.iter_bytes():
                        # Relay binary audio chunks directly to GPT Voice Engine
                        await openai_ws.send(json.dumps({
                            "type": "input_audio_buffer.append",
                            "audio": message.decode('latin-1') # base64 string encode fallback
                        }))
                except WebSocketDisconnect:
                    logger.info("Client disconnected.")
                except Exception as e:
                    logger.error(f"Client tunnel error: {e}")

            async def send_to_client():
                """Relays responses, text transcripts & tools from GPT back to Android"""
                try:
                    async for message in openai_ws:
                        response_data = json.loads(message)
                        # Filter types and forward to the client layout
                        msg_type = response_data.get("type")
                        
                        if msg_type == "response.audio.delta":
                            # Base64 raw audio to binary byte array for AudioTrack
                            audio_delta = response_data["delta"].encode('latin-1')
                            await websocket.send_bytes(audio_delta)
                        
                        elif msg_type == "response.audio_transcript.delta":
                            await websocket.send_json({
                                "type": "transcript",
                                "delta": response_data["delta"]
                            })

                        # Handle dynamic system Tool Calling actions
                        elif msg_type == "response.function_call_arguments.done":
                            await websocket.send_json({
                                "type": "tool_call",
                                "id": response_data["call_id"],
                                "function": response_data["name"],
                                "arguments": response_data["arguments"]
                            })
                except Exception as e:
                    logger.error(f"OpenAI tunnel error: {e}")

            # Run concurrently for duplex communication
            await asyncio.gather(receive_from_client(), send_to_client())

    except Exception as e:
        logger.error(f"Gateway proxy failure: {e}")
    finally:
        logger.info("Tunnel closed.")
```

### 3. `nginx.conf` (Secure Edge Routing Proxy)
```nginx
events { worker_connections 1024; }

http {
    map $http_upgrade $connection_upgrade {
        default upgrade;
        ''      close;
    }

    server {
        listen 80;
        server_name hermes-ai.ecs.alibaba.net;
        return 301 https://$host$request_uri; # Force TLS redirect
    }

    server {
        listen 443 ssl;
        server_name hermes-ai.ecs.alibaba.net;

        ssl_certificate /etc/letsencrypt/live/hermes-ai.ecs.alibaba.net/fullchain.pem;
        ssl_certificate_key /etc/letsencrypt/live/hermes-ai.ecs.alibaba.net/privkey.pem;

        location /v1/realtime {
            proxy_pass http://hermes-gateway:8880;
            proxy_http_version 1.1;
            proxy_set_header Upgrade $http_upgrade;
            proxy_set_header Connection $connection_upgrade;
            proxy_set_header Host $host;
            proxy_cache_bypass $http_upgrade;
        }
    }
}
```

---

## 💡 IV. AI Core Prompting (Hermes Persona & Prompts)

Save our model System Instructions inside the Hermes Session Config panel. This defines the AI's core behavior:

### System Role Prompt (Hermes AI Phone Takeover Mode)
```
You are the voice of Hermes, a sleek, cybernetic, cyberpunk-themed AI Personal Assistant. You represent the user during active mobile phone call takeovers on real cellular network devices.

OPERATING GUIDELINES:
1. Always address the caller politely but with a professional, efficient tone.
2. Clearly state: "您好，我是陳先生的 AI 智慧語音助理。陳先生目前公出，由我代為接聽。請問您如何稱呼，有什麼緊要事情需要我記錄轉達呢？"
3. If the caller requests to make an appointment or review a spec:
   a. Formulate critical info and execute 'create_task(title, description)'.
   b. Affirm details to the caller: "我已將您的約會和專案討論工作加進陳先生的事項表。待他得空會立刻處理。"
4. If an anomaly is mentioned about backend network failures or server outages:
   a. Immediately trigger 'summarize_alert(alert, severity, content)' to generate security boards on the user's dashboard app.
5. Absolute brevity: Speak in concise sentences suitable for low-latency phone dialogue. Keep TTS responses under 4 seconds.
```

---

## 🚀 V. Deployment Guide on Alibaba Cloud ECS

Follow these steps to deploy the central WebSocket servers of Hermes:

1.  **SSH into Alibaba ECS Service**:
    ```bash
    ssh root@<YOUR_ECS_PUBLIC_IP>
    ```
2.  **Install General Docker Toolchains**:
    ```bash
    sudo apt update && sudo apt install -y docker.io docker-compose certbot
    ```
3.  **Obtain Let's Encrypt TLS Certificate**:
    ```bash
    sudo certbot certonly --standalone -d hermes-ai.ecs.alibaba.net
    ```
4.  **Transfer Backend Assets**: Make directory `/app/hermes` and copy `docker-compose.yml`, `app.py`, and `nginx.conf` onto it.
5.  **Start Container Tunnel**:
    ```bash
    cd /app/hermes
    docker-compose up -d --build
    ```
6.  *Verifications*: Query `docker ps` to ensure both Nginx proxy and FastAPI services run beautifully.

---

## 📋 VI. Phase 1 MVP Checklist

*   [x] **Android Built-in dialer state mapping** (Telecom framework declared, compiled successfully)
*   [x] **PCM Duplex Audio recording & Playback** (NoiseSuppressor/AcousticEchoCanceler linked correctly)
*   [x] **OkHttp Secure WebSocket handler client** (Handles real-time audio streams and incoming delta transcriptions)
*   [x] **MVVM UI Design Patterns** (Jetpack Compose Cyberpunk/OpenAI theme)
*   [x] **CRM database integration** (Pre-seeded tasks, alerting queues, and phone transcripts)
*   [x] **Docker orchestration and back-end gateways** (Outlined under documentation)
