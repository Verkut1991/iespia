# iEspia

An Android client and Node.js API that register named “objectives” from face photos, store facial embeddings in MongoDB, and scan live or gallery images to see if anyone on that list appears. When a match lands, the phone gets a Socket.IO notification almost immediately.

The product fiction is deliberately surveillance-flavored — agents, watchlists, audit logs — because that is a sharp way to exercise camera capture, JWT auth, realtime push, and a Python face stack in one loop. Treat that fiction as a classroom scenario, not a blueprint for shipping software that tracks people.

## Ethics (read this)

Face recognition used to identify and track people is ethically fraught. Building the pipeline is an interesting engineering exercise; using it as a real-world product pattern against people who did not clearly consent is not.

This repo is an **educational / technical demo only**. It is not a recommendation to deploy facial surveillance, covert monitoring, or “watch everyone who walks by” systems. If you run it:

- Use only faces you have a right to process (your own, or people who explicitly agreed for the demo).
- Do not point it at public spaces, classmates, coworkers, or strangers without informed consent.
- Assume misuse risk is real: stored embeddings and match alerts can harm people if the data leaks or the tool is aimed at the wrong crowd.
- Responsibility sits with whoever runs the code — curiosity about the stack does not erase that.

Honest framing for recruiters and reviewers: the interesting part is the integration (CameraX → API → OpenFaceKit → Mongo → Socket.IO). The watchlist metaphor is the problem domain the school assignment chose; it is not an endorsement of facial tracking as a product category.

## What it does

1. **Agent login / API key** — the Android app obtains a JWT (login or keygen) and keeps it in SharedPreferences; OkHttp renews on 401.
2. **Add an objective** — pick a photo from the gallery (or camera flow), send name + Base64 image to `POST /targets`; Node writes the file and shells out to Python to compute an embedding and upsert it into MongoDB (`tensores`).
3. **Scan** — capture or pick an image, send it to `POST /recognition/scan`; the API answers `processing` and runs `reconocimiento.py` in the background (MTCNN detect → embed → cosine compare against stored targets).
4. **Notify** — on a match, Socket.IO pushes a notification to the registered agent; a foreground service on Android keeps the channel alive.
5. **Audit** — Express middleware logs method, path, status, agent id, and duration into `Auditoria_iEspia`.

## Stack / under the hood

| Layer | Tech |
| --- | --- |
| Android app | Java 11, AppCompat / Material, CameraX 1.3, Retrofit + OkHttp, Socket.IO client |
| API | Node.js, Express 5, Socket.IO, JWT (`jsonwebtoken`), rate limiting, Multer-style Base64 uploads to disk |
| Face pipeline | Python 3, OpenFaceKit (`FaceRecognizer` + MTCNN), PyTorch tensors, cosine similarity |
| Data | MongoDB (embeddings collection + audit collection) |
| Build | Gradle + AGP 8.13 · `minSdk` 24 · `targetSdk` / compile 36 |

Layout:

```
App/iEspia/          # Android Studio project (open this folder)
Servidor/Node/       # Express + Socket.IO API
  rutas/             # auth, targets, scan
  procesar_objetivo.py
  reconocimiento.py
  .env.example
```

Android talks to `http://10.0.2.2:6010` by default (emulator → host machine). Point those constants at your LAN IP for a physical device.

## Run locally

**Requirements:** Node.js 18+, MongoDB, Python 3 with OpenFaceKit / PyTorch (and whatever system deps that stack needs), Android Studio for the client.

### 1. API

```bash
cd Servidor/Node
cp .env.example .env
# edit .env — see variables below
npm install
# install OpenFaceKit + PyTorch into the Python env you will call via PYTHON_BIN
node index.js
```

Default listen port is **6010**.

### 2. Android app

1. Open `App/iEspia/` in Android Studio and let Gradle sync (JDK 11+).
2. Start the API on the host.
3. Run the `app` module on an emulator (API 24+) or a device (then change the Retrofit / Socket.IO base URL).

### Environment variables

| Variable | Description |
| --- | --- |
| `MONGODB_URI` | MongoDB URI (default `mongodb://localhost:27017`) |
| `MONGODB_DB` | Database name (default `iespia_demo`) |
| `JWT_SECRET` | Secret used to sign JWTs (change the local default) |
| `PYTHON_BIN` | Python executable for the face scripts (default `python3`) |
| `PORT` | API port (default `6010`) |

## License

ISC (see `Servidor/Node/package.json`).
