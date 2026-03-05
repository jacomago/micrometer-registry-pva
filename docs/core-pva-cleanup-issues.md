# core-pva Cleanup Issue Tickets

Upstream project: **phoebus / core-pva**
Observed in: `micrometer-registry-pva` test suite (phoebus-pva 4.7.3)
Reproducer: `PvaCleanupBehaviourTest` (three focused unit tests)

---

## Issue A — `TCPHandler`: log `SocketException: "Socket is closed"` at DEBUG, not WARNING

### Summary

`TCPHandler.sender()` emits a WARNING with a full Java stack trace when the
underlying socket has been intentionally closed as part of server or channel
shutdown.  This is expected teardown behaviour — not a real error — and
pollutes production logs, making it harder to distinguish genuine connectivity
problems.

### Steps to reproduce

```java
PVAServer server = new PVAServer();
// configure PVASettings.EPICS_PVA_NAME_SERVERS to 127.0.0.1:<port>

ServerPV pv = server.createPV("demo", /* NTScalar structure */);

PVAClient client = new PVAClient();
PVAChannel ch = client.getChannel("demo");
ch.connect().get(5, TimeUnit.SECONDS);   // channel is now CONNECTED

server.close();   // ← triggers the warning
Thread.sleep(200);
client.close();
```

### Observed log output

```
WARNING: TCP sender from /127.0.0.1:<server-port> to /127.0.0.1:<client-port> exits because of error
java.net.SocketException: Socket is closed
    at java.base/java.net.Socket.getOutputStream(Socket.java:998)
    at org.epics.pva.common.TCPHandler.send(TCPHandler.java:243)
    at org.epics.pva.common.TCPHandler.sender(TCPHandler.java:207)
    …
```

### Root cause

`TCPHandler.sender()` runs in a background thread.  When the socket is closed
(e.g. by `PVAServer.close()` or by a client disconnecting), the thread catches
the resulting `SocketException` at the same catch-all `Exception` handler that
also catches genuine I/O errors, and always logs at `WARNING`.

### Expected behaviour

When the exception is `SocketException` with the message `"Socket is closed"`,
the socket was intentionally closed (either by this side or by the peer as part
of the protocol).  No WARNING log entry should appear; FINE/DEBUG at most.

### Suggested fix

In `TCPHandler.sender()`, distinguish intentional closure from real errors:

```java
} catch (Exception ex) {
    if (ex instanceof SocketException && "Socket is closed".equals(ex.getMessage())) {
        logger.log(Level.FINE, () ->
            "TCP sender from " + local + " to " + remote + " closed as expected");
    } else {
        logger.log(Level.WARNING,
            "TCP sender from " + local + " to " + remote + " exits because of error", ex);
    }
}
```

### Impact

- Every test or application that closes a `PVAServer` or `ServerPV` while at
  least one client is connected generates a spurious WARNING with a stack trace.
- Makes log-based alerting noisy and degrades signal-to-noise ratio in
  production deployments.

---

## Issue B — `PVAClient.close()`: auto-close remaining channels (or warn selectively by state)

### Summary

`PVAClient.close()` emits a WARNING for every channel that is not in state
`CLOSED` when the client is closed.  This covers two distinct cases that have
very different semantics and should be treated differently:

| Channel state at `close()` | Meaning | Appropriate log level |
|---|---|---|
| `SEARCHING` / `INIT` | Server destroyed the channel; client is seeking to reconnect — this is *expected* after `ServerPV.close()` | FINE / silent |
| `CONNECTED` | Caller forgot to close the channel before closing the client | WARNING |

### Steps to reproduce

#### Case 1 — SEARCHING (normal after server-side destroy)

```java
ServerPV pv = server.createPV("demo", /* data */);

PVAClient client = new PVAClient();
PVAChannel ch = client.getChannel("demo");
ch.connect().get(5, TimeUnit.SECONDS);

pv.close();           // server sends CMD_DESTROY_CHANNEL
                      // channel transitions CONNECTED → INIT / SEARCHING
Thread.sleep(200);

client.close();       // ← WARNING: remaining channels [...SEARCHING]
```

#### Case 2 — CONNECTED (channel never closed by caller)

```java
PVAClient client = new PVAClient();
PVAChannel ch = client.getChannel("demo");
ch.connect().get(5, TimeUnit.SECONDS);

client.close();       // ← WARNING: remaining channels [...CONNECTED]
                      //   (no channel.close() before client.close())
```

### Observed log output (both cases)

```
WARNING: PVA Client closed with remaining channels: ['demo' [CID N, SID M <STATE>]]
```

### Root cause

`PVAClient.close()` iterates its internal channel map and warns for every entry
that is not in state `CLOSED`, without distinguishing between channels that the
*server* destroyed (normal) and channels that the *user* simply forgot to close.

### Expected behaviour — option A (preferred)

`PVAClient.close()` **silently closes all remaining channels** before shutting
down, analogous to how `ExecutorService.shutdownNow()` cancels outstanding
tasks.  This is consistent with the general `Closeable` contract: closing a
container closes its contents.

### Expected behaviour — option B (lighter change)

Keep the WARNING for `CONNECTED` channels (genuine resource leak), but log at
`FINE` (or not at all) for channels in `SEARCHING` / `INIT` state, since those
are already in mid-teardown and represent no resource leak from the caller's
perspective.

### Impact

- Any code that calls `ServerPV.close()` and then `PVAClient.close()` (the
  standard teardown order) generates a WARNING, even when cleanup is correct.
- Our integration tests (`PvaMeterRegistryTest`,  `PvaServiceBinderTest`) both
  trigger this warning despite performing proper resource management.
- Confuses developers inspecting logs: the WARNING implies a leak where none
  exists.
