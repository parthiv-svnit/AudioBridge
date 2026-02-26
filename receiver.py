"""
AudioBridge — PC Receiver
=========================
Receives raw PCM audio from the AudioBridge Android app over UDP
and plays it through a virtual audio device with minimum latency.

Protocol:
  Bytes [0–3]  → uint32 sequence number (big-endian)
  Bytes [4+]   → raw PCM (signed 16-bit, little-endian, stereo interleaved)

Dependencies:
  pip install sounddevice numpy

Usage:
  python receiver.py

Stop:
  Ctrl+C
"""

import socket
import struct
import threading
import signal
import sys
import time
from collections import deque

import numpy as np
import sounddevice as sd

# ── Configuration (edit these if needed) ──────────────────────────────────────

PORT          = 7355       # UDP port to listen on
SAMPLE_RATE   = 48000      # Hz — must match Android app (48000 default)
CHANNELS      = 2          # Stereo
CHUNK_MS      = 10         # Milliseconds per packet — must match Android (10ms)
BUFFER_CHUNKS = 2          # Jitter buffer size in chunks = 20ms. DO NOT increase.
                           # Rationale: 2 chunks = 20ms is the minimum that smooths
                           # typical LAN jitter (~5–15ms) without adding perceptible delay.
                           # Increasing this sacrifices the #1 priority (lowest latency).
DTYPE         = 'int16'    # PCM format — signed 16-bit

# ── Derived constants (do not edit) ───────────────────────────────────────────

BYTES_PER_SAMPLE = 2                          # 16-bit
CHUNK_SAMPLES    = SAMPLE_RATE * CHANNELS * CHUNK_MS // 1000   # samples per chunk
CHUNK_BYTES      = CHUNK_SAMPLES * BYTES_PER_SAMPLE             # bytes per chunk
HEADER_SIZE      = 4                          # sequence number bytes

# ── State ──────────────────────────────────────────────────────────────────────

running          = True
last_seq         = -1
dropped_packets  = 0
reordered_packets = 0

# Jitter buffer: deque of numpy arrays ready to play
jitter_buffer    = deque(maxlen=BUFFER_CHUNKS * 4)  # extra room, we drain at BUFFER_CHUNKS
buffer_lock      = threading.Lock()
buffer_ready     = threading.Event()  # set when buffer has BUFFER_CHUNKS chunks

# ── Helpers ────────────────────────────────────────────────────────────────────

def get_local_ips() -> list[str]:
    """Return all non-loopback local IP addresses."""
    import socket as _socket
    ips = []
    try:
        hostname = _socket.gethostname()
        for info in _socket.getaddrinfo(hostname, None):
            ip = info[4][0]
            if not ip.startswith("127.") and ":" not in ip:  # IPv4 only
                if ip not in ips:
                    ips.append(ip)
    except Exception:
        pass
    # Fallback: connect trick
    try:
        s = _socket.socket(_socket.AF_INET, _socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        if ip not in ips:
            ips.append(ip)
    except Exception:
        pass
    return ips or ["(could not determine)"]

def print_startup_info():
    ips = get_local_ips()
    buffer_ms = BUFFER_CHUNKS * CHUNK_MS
    print()
    print("═" * 52)
    print("  AudioBridge PC Receiver")
    print("═" * 52)
    print(f"  Listening on    : 0.0.0.0:{PORT} (UDP)")
    print(f"  Local IP(s)     : {', '.join(ips)}")
    print(f"  Format          : {SAMPLE_RATE}Hz · {CHANNELS}ch · {DTYPE}")
    print(f"  Chunk size      : {CHUNK_MS}ms ({CHUNK_BYTES} bytes)")
    print(f"  Jitter buffer   : {BUFFER_CHUNKS} chunks = {buffer_ms}ms")
    print(f"  Target latency  : <{buffer_ms + CHUNK_MS * 2}ms end-to-end")
    print("─" * 52)
    print("  Enter this IP in the AudioBridge Android app:")
    for ip in ips:
        print(f"    → {ip}")
    print("─" * 52)
    print("  Press Ctrl+C to stop")
    print()

# ── UDP receive loop ───────────────────────────────────────────────────────────

def receive_loop(sock: socket.socket):
    """
    Receives UDP packets, validates headers, and pushes PCM frames
    into the jitter buffer.
    """
    global last_seq, dropped_packets, reordered_packets, running

    while running:
        try:
            data, addr = sock.recvfrom(CHUNK_BYTES + HEADER_SIZE + 64)  # +64 headroom
        except socket.timeout:
            continue
        except OSError:
            break  # socket closed — shutting down

        if len(data) < HEADER_SIZE:
            continue

        # Unpack sequence number
        seq = struct.unpack(">I", data[:HEADER_SIZE])[0]
        pcm_bytes = data[HEADER_SIZE:]

        # Sequence tracking
        if last_seq >= 0:
            expected = (last_seq + 1) & 0xFFFFFFFF
            if seq != expected:
                gap = (seq - last_seq) & 0xFFFFFFFF
                if gap > 0x7FFFFFFF:
                    # Reordered (seq wrapped backwards)
                    reordered_packets += 1
                    print(f"  [seq] Reordered packet: got {seq}, expected {expected}", flush=True)
                    # Drop reordered packet — adding it would break timing
                    continue
                else:
                    missed = gap - 1
                    if missed > 0:
                        dropped_packets += missed
                        print(f"  [seq] Dropped {missed} packet(s) "
                              f"(seq {last_seq+1}–{seq-1})", flush=True)

        last_seq = seq

        # Decode PCM
        if len(pcm_bytes) < 2:
            continue

        try:
            samples = np.frombuffer(pcm_bytes, dtype=np.int16)
        except ValueError:
            continue

        # Reshape to (frames, channels) for sounddevice
        frames = len(samples) // CHANNELS
        if frames == 0:
            continue
        audio = samples[:frames * CHANNELS].reshape((frames, CHANNELS))

        with buffer_lock:
            jitter_buffer.append(audio)
            if len(jitter_buffer) >= BUFFER_CHUNKS and not buffer_ready.is_set():
                buffer_ready.set()

# ── Audio playback ─────────────────────────────────────────────────────────────

def audio_callback(outdata: np.ndarray, frames: int,
                   time_info, status: sd.CallbackFlags):
    """
    sounddevice callback — called every chunk period on the audio thread.
    Pulls one frame from the jitter buffer. If empty, outputs silence.
    """
    if status:
        print(f"  [audio] {status}", flush=True)

    with buffer_lock:
        if jitter_buffer:
            chunk = jitter_buffer.popleft()
        else:
            chunk = None

    if chunk is not None and len(chunk) == frames:
        outdata[:] = chunk
    elif chunk is not None and len(chunk) > frames:
        outdata[:] = chunk[:frames]
        # Push remainder back (unlikely with exact chunk sizes)
        with buffer_lock:
            jitter_buffer.appendleft(chunk[frames:])
    else:
        # Underrun — output silence, do not request retransmit
        outdata[:] = 0

# ── Shutdown ───────────────────────────────────────────────────────────────────

def shutdown(sock: socket.socket):
    global running
    running = False
    buffer_ready.set()  # unblock any waiter
    try:
        sock.close()
    except Exception:
        pass
    print(f"\n  Stopped. Stats: dropped={dropped_packets}, reordered={reordered_packets}")
    print("  AudioBridge receiver exited.")

# ── Main ───────────────────────────────────────────────────────────────────────

def main():
    global running

    print_startup_info()

    # Open UDP socket
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 1024 * 1024)  # 1MB recv buffer
    sock.bind(("0.0.0.0", PORT))
    sock.settimeout(1.0)  # allow receive loop to check `running` flag

    # Handle Ctrl+C
    def on_signal(sig, frame):
        shutdown(sock)
        sys.exit(0)

    signal.signal(signal.SIGINT,  on_signal)
    signal.signal(signal.SIGTERM, on_signal)

    # Start receive thread
    recv_thread = threading.Thread(target=receive_loop, args=(sock,),
                                   name="AudioBridge-Recv", daemon=True)
    recv_thread.start()

    print("  Waiting for phone to connect...", flush=True)

    # Wait until jitter buffer has enough chunks before opening audio stream
    buffer_ready.wait()
    if not running:
        return

    print("  Buffered — starting playback", flush=True)

    # Open sounddevice output stream
    # latency='low' tells PortAudio to use the minimum safe buffer for the device
    with sd.OutputStream(
        samplerate=SAMPLE_RATE,
        channels=CHANNELS,
        dtype=DTYPE,
        blocksize=CHUNK_SAMPLES // CHANNELS,  # frames per callback = 1 chunk
        latency='low',
        callback=audio_callback,
    ):
        print("  ▶ Streaming — audio is live\n", flush=True)
        while running:
            time.sleep(0.5)

    shutdown(sock)


if __name__ == "__main__":
    main()