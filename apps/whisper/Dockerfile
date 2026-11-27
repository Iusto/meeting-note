FROM nvidia/cuda:12.3.2-cudnn9-runtime-ubuntu22.04
RUN apt-get update && apt-get install -y --no-install-recommends python3 python3-pip ca-certificates \
    && rm -rf /var/lib/apt/lists/*
RUN python3 -m pip install --no-cache-dir faster-whisper==1.2.1 ctranslate2==4.6.0
RUN useradd --create-home --uid 10001 whisper && mkdir -p /models && chown whisper:whisper /models
WORKDIR /app
COPY server.py .
ENV HF_HOME=/models HF_HUB_DISABLE_TELEMETRY=1 PYTHONUNBUFFERED=1
USER whisper
CMD ["python3", "server.py"]
