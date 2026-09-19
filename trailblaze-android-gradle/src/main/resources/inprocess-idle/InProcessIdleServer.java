package xyz.block.trailblaze.inprocessidle;

import android.os.Handler;
import android.os.Looper;
import android.os.MessageQueue;
import android.util.Log;
import android.view.Choreographer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The Trailblaze in-process idle server: runs inside the app under test's process and answers
 * "is the app idle?" over a localhost TCP socket. Hosted either by
 * {@link InProcessIdleInstrumentation} (attached with {@code am instrument}) or by
 * {@link InProcessIdleProvider} (started by the platform from a split APK of the app itself);
 * both hosts are a few lines that call {@link #start(String)}.
 *
 * Protocol (line-based, one request per line):
 *   PING                      -> "PONG <appPackage>"
 *   AWAIT_IDLE <timeoutMs>    -> "IDLE <elapsedMs>" once the main looper is idle AND a
 *                                Choreographer frame has rendered with no new frame scheduled,
 *                                or "TIMEOUT <elapsedMs>" at the deadline.
 *
 * Framework-only by design: main-looper idleness + Choreographer quiescence. Zero coupling
 * to the app's libraries (no Compose, no Espresso), so one build works against any app
 * signed with a matching certificate.
 */
final class InProcessIdleServer {

  static final String TAG = "TbzInProcessIdle";
  static final int PORT = 7777;

  private final String appPackage;

  private InProcessIdleServer(String appPackage) {
    this.appPackage = appPackage;
  }

  /** Starts serving on a daemon thread; returns immediately. */
  static void start(String appPackage) {
    InProcessIdleServer server = new InProcessIdleServer(appPackage);
    Thread thread = new Thread(server::serveForever, "trailblaze-inprocess-idle");
    thread.setDaemon(true);
    thread.start();
    Log.i(TAG, "idle detector listening on 127.0.0.1:" + PORT + " (in-process, " + appPackage + ")");
  }

  private void serveForever() {
    ServerSocket boundSocket;
    try {
      boundSocket = new ServerSocket(PORT, 4, InetAddress.getByName("127.0.0.1"));
    } catch (Throwable bindFailure) {
      // Most common cause: the app doesn't hold android.permission.INTERNET. This code runs with
      // the app's own permissions, and even a localhost socket needs that permission (the bind
      // fails with EPERM/EACCES). Directed log so an attacher's PONG timeout is diagnosable from
      // logcat instead of reading as a silent no-op.
      Log.e(
          TAG,
          "could not bind 127.0.0.1:"
              + PORT
              + " — if the cause is EPERM/EACCES, the app likely lacks"
              + " android.permission.INTERNET, which the in-process idle needs even for a localhost"
              + " socket (it runs with the app's permissions)",
          bindFailure);
      return;
    }
    try (ServerSocket serverSocket = boundSocket) {
      Log.i(TAG, "server socket BOUND on 127.0.0.1:" + PORT);
      while (true) {
        // Thread-per-connection: clients race AWAIT_IDLE against their own heuristics and
        // abandon the losing request, so a serial accept loop would head-of-line-block the
        // next gate behind a lingering AWAIT_IDLE.
        Socket socket = serverSocket.accept();
        Thread worker =
            new Thread(
                () -> {
                  try (Socket s = socket) {
                    handle(s);
                  } catch (IOException perConn) {
                    Log.w(TAG, "connection error: " + perConn);
                  }
                },
                "trailblaze-inprocess-idle-conn");
        worker.setDaemon(true);
        worker.start();
      }
    } catch (Throwable fatal) {
      Log.e(TAG, "server died", fatal);
    }
  }

  private void handle(Socket socket) throws IOException {
    BufferedReader in =
        new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
    OutputStream out = socket.getOutputStream();
    String line;
    while ((line = in.readLine()) != null) {
      String reply;
      if (line.startsWith("PING")) {
        reply = "PONG " + appPackage;
      } else if (line.startsWith("AWAIT_IDLE")) {
        long timeoutMs = 5000;
        String[] parts = line.trim().split("\\s+");
        if (parts.length > 1) {
          try {
            timeoutMs = Long.parseLong(parts[1]);
          } catch (NumberFormatException ignored) {
          }
        }
        reply = awaitIdle(timeoutMs);
      } else {
        reply = "ERR unknown command";
      }
      out.write((reply + "\n").getBytes(StandardCharsets.UTF_8));
      out.flush();
    }
  }

  /**
   * Deterministic idle: (1) main looper's queue reports idle, then (2) one Choreographer
   * frame callback fires with the queue still idle immediately after — i.e. rendering has
   * quiesced and nothing new was scheduled by that frame.
   */
  private String awaitIdle(long timeoutMs) {
    long start = System.nanoTime();
    long deadline = start + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
    Handler main = new Handler(Looper.getMainLooper());
    MessageQueue queue = Looper.getMainLooper().getQueue();

    while (System.nanoTime() < deadline) {
      // Phase 1: wait until the main queue reports idle.
      CountDownLatch idleLatch = new CountDownLatch(1);
      main.post(
          () -> {
            if (queue.isIdle()) {
              idleLatch.countDown();
            } else {
              queue.addIdleHandler(
                  () -> {
                    idleLatch.countDown();
                    return false; // one-shot
                  });
            }
          });
      if (!await(idleLatch, deadline)) return timeout(start);

      // Phase 2: confirm a frame boundary with the queue still idle right after it.
      CountDownLatch frameLatch = new CountDownLatch(1);
      AtomicLong stillIdle = new AtomicLong(0);
      main.post(
          () ->
              Choreographer.getInstance()
                  .postFrameCallback(
                      frameTimeNanos -> {
                        stillIdle.set(queue.isIdle() ? 1 : 0);
                        frameLatch.countDown();
                      }));
      if (!await(frameLatch, deadline)) return timeout(start);
      if (stillIdle.get() == 1) {
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        return "IDLE " + elapsedMs;
      }
      // Something ran during the frame; loop and re-check until deadline.
    }
    return timeout(start);
  }

  private static boolean await(CountDownLatch latch, long deadlineNanos) {
    try {
      long waitNanos = deadlineNanos - System.nanoTime();
      return waitNanos > 0 && latch.await(waitNanos, TimeUnit.NANOSECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private String timeout(long startNanos) {
    long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    return "TIMEOUT " + elapsedMs;
  }
}
