# ScreenPin Live Protocol v1

Transport is TCP. All integers/floats are network byte order (big endian). One TCP connection carries sequential complete frames.

| Field | Type | Notes |
|---|---|---|
| magic | 4 bytes | ASCII `SPL1` |
| version | int32 | `1` |
| timestampNs | int64 | Android monotonic `System.nanoTime()` |
| width | int32 | JPEG width |
| height | int32 | JPEG height |
| corners | 8 × float32 | TL,TR,BR,BL normalized x/y |
| jpegLength | int32 | following byte count |
| JPEG | bytes | camera image |

Discovery uses UDP port 45901. The PC broadcasts UTF-8 `SCREENPIN|1|45900`; the Android client uses the source IP of that datagram as the TCP host.
