import json
import math
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

SAMPLE_RATE = 16_000
BYTES_PER_SECOND = SAMPLE_RATE * 2
MAX_LENGTH = 14_400 * BYTES_PER_SECOND
WINDOW_BYTES = 30 * BYTES_PER_SECOND
MAX_SEGMENTS = 10_000


class Transcriber:
    def __init__(self, model, decode_pcm):
        self.model = model
        self.decode_pcm = decode_pcm
        self.slot = threading.Lock()

    def transcribe(self, stream, length):
        offset_samples = 0
        remaining = length
        results = []
        while remaining:
            size = min(WINDOW_BYTES, remaining)
            pcm = stream.read(size)
            if len(pcm) != size:
                raise ValueError("Incomplete PCM request")
            audio = self.decode_pcm(pcm)
            with self.slot:
                parts, _ = self.model.transcribe(audio, language="ko", task="transcribe",
                                                  beam_size=3, vad_filter=True,
                                                  condition_on_previous_text=False)
                # faster-whisper performs the inference when its segment generator is iterated.
                window_segments = list(parts)
            for part in window_segments:
                if not math.isfinite(part.start) or not math.isfinite(part.end):
                    continue
                start = min(len(audio), max(0, round(part.start * SAMPLE_RATE)))
                end = min(len(audio), max(start, round(part.end * SAMPLE_RATE)))
                text = part.text.strip()
                if text and end > start:
                    results.append({
                        "startMs": (offset_samples + start) * 1000 // SAMPLE_RATE,
                        "endMs": (offset_samples + end) * 1000 // SAMPLE_RATE,
                        "text": text,
                    })
                    if len(results) > MAX_SEGMENTS:
                        raise ValueError("Too many transcript segments")
            remaining -= size
            offset_samples += len(audio)
        return results


class RequestHandler(BaseHTTPRequestHandler):
    def setup(self):
        self.request.settimeout(60)
        super().setup()

    def do_GET(self):
        if self.path != "/health":
            self.respond(404, {"error": "Unknown endpoint"})
            return
        self.respond(200, {"model": "large-v3", "device": "cuda", "compute_type": "int8_float16"})

    def do_POST(self):
        if self.path != "/transcribe":
            self.respond(404, {"error": "Unknown endpoint"})
            return
        if self.headers.get_content_type() != "application/octet-stream":
            self.respond(415, {"error": "Expected PCM16 binary audio"})
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            length = 0
        if length <= 0 or length > MAX_LENGTH or length % 2:
            self.respond(400, {"error": "Invalid PCM16 length"})
            return
        if not self.server.slots.acquire(blocking=False):
            self.respond(503, {"error": "Transcription workers are busy"})
            return
        try:
            segments = self.server.transcriber.transcribe(self.rfile, length)
        except (ValueError, TimeoutError):
            self.respond(400, {"error": "Invalid PCM16 audio"})
        except Exception as failure:
            self.log_error("Transcription inference failed: %s", type(failure).__name__)
            self.respond(503, {"error": "Transcription failed"})
        else:
            self.respond(200, {"segments": segments})
        finally:
            self.server.slots.release()

    def respond(self, status, data):
        body = json.dumps(data, ensure_ascii=False).encode("utf-8")
        try:
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def log_request(self, code="-", size="-"):
        pass


def main():
    import numpy as np
    from faster_whisper import WhisperModel

    model = WhisperModel("large-v3", device="cuda", compute_type="int8_float16")
    silence = np.zeros(SAMPLE_RATE, dtype=np.float32)
    warmup, _ = model.transcribe(silence, language="ko", beam_size=1)
    list(warmup)
    server = ThreadingHTTPServer(("0.0.0.0", 8768), RequestHandler)
    server.transcriber = Transcriber(model, lambda pcm: np.frombuffer(pcm, dtype="<i2").astype(np.float32) / 32768.0)
    server.slots = threading.BoundedSemaphore(2)
    print("Whisper large-v3 ready on CUDA (int8_float16)")
    server.serve_forever()


if __name__ == "__main__":
    main()
