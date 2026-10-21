package com.local.kakaoautosender;

import android.os.Build;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Launched ONLY by an explicitly paired, loopback ADB shell. No exported component or socket. Turns
 * physical panel power off while the OS stays interactive; never wakes or unlocks a device. A dead
 * client/lease restores panel power. No photo, room, text, key or credential enters here.
 */
@android.annotation.SuppressLint({"PrivateApi", "SoonBlockedPrivateApi", "BlockedPrivateApi"})
public final class PanelShell {
  private static Object power, window;
  private static Method interactive, locked, activity;
  private static Object displayManager;
  private static Method displayPower;
  private static Class<?> surface;
  private static IBinder[] tokens;
  private static boolean touched;
  private static java.io.OutputStream guardOutput;
  private static BufferedReader guardInput;
  private static String guardToken;
  private static Process guardProcess;
  private static int guardPid;
  private static final AtomicBoolean guardClosing = new AtomicBoolean(false);

  private static void closeGuard() {
    if (guardOutput == null || !guardClosing.compareAndSet(false, true)) return;
    try {
      guardWrite("STOP");
      // Keep the pipe owner alive until the detached child has applied restoration.
      if (guardProcess != null) guardProcess.waitFor(2500L, java.util.concurrent.TimeUnit.MILLISECONDS);
    } catch (Throwable ignored) { }
  }

  private static void guardWrite(String command) throws Exception {
    if (guardOutput == null) return;
    synchronized (guardOutput) {
      guardOutput.write(
          (command + " " + guardToken + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
      guardOutput.flush();
    }
  }

  private static void startGuard(String token) throws Exception {
    ProcessBuilder builder =
        new ProcessBuilder(
            "/system/bin/setsid",
            "-w",
            "/system/bin/app_process",
            "/",
            "com.local.kakaoautosender.PanelShell",
            token,
            "guard");
    builder.redirectError(new java.io.File("/dev/null"));
    Process guard = builder.start();
    guardProcess = guard; // Keep Android's Process/pipe owner alive for the entire lease.
    guardToken = token;
    guardOutput = guard.getOutputStream();
    guardInput = new BufferedReader(new InputStreamReader(guard.getInputStream()));
    String ready = guardInput.readLine();
    if (ready == null || !ready.startsWith("KMGUARD READY " + token + " "))
      throw new IllegalStateException("NO_RESTORE_GUARD: " + ready);
    guardPid = Integer.parseInt(ready.substring(ready.lastIndexOf(' ') + 1));
  }

  private static Object service(String name, String stub) throws Exception {
    IBinder b =
        (IBinder)
            Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class)
                .invoke(null, name);
    return Class.forName(stub).getMethod("asInterface", IBinder.class).invoke(null, b);
  }

  private static void initialize() throws Exception {
    power = service("power", "android.os.IPowerManager$Stub");
    window = service("window", "android.view.IWindowManager$Stub");
    interactive =
        Build.VERSION.SDK_INT >= 34
            ? power.getClass().getMethod("isDisplayInteractive", int.class)
            : power.getClass().getMethod("isInteractive");
    locked = window.getClass().getMethod("isKeyguardLocked");
    activity =
        Build.VERSION.SDK_INT >= 31
            ? power
                .getClass()
                .getMethod("userActivity", int.class, long.class, int.class, int.class)
            : power.getClass().getMethod("userActivity", long.class, int.class, int.class);
    if (Build.VERSION.SDK_INT >= 35) {
      Class<?> global = Class.forName("android.hardware.display.DisplayManagerGlobal");
      displayManager = global.getMethod("getInstance").invoke(null);
      displayPower = global.getMethod("requestDisplayPower", int.class, boolean.class);
      return;
    }
    surface = Class.forName("android.view.SurfaceControl");
    Class<?> source = surface;
    try {
      source.getMethod("getPhysicalDisplayIds");
    } catch (NoSuchMethodException missing) {
      // Android 14 moved physical display enumeration to the system-server classpath.
      Class<?> factory = Class.forName("com.android.internal.os.ClassLoaderFactory");
      ClassLoader loader =
          (ClassLoader)
              factory
                  .getDeclaredMethod(
                      "createClassLoader",
                      String.class,
                      String.class,
                      String.class,
                      ClassLoader.class,
                      int.class,
                      boolean.class,
                      String.class)
                  .invoke(
                      null,
                      System.getenv("SYSTEMSERVERCLASSPATH"),
                      null,
                      null,
                      ClassLoader.getSystemClassLoader(),
                      0,
                      true,
                      null);
      source = loader.loadClass("com.android.server.display.DisplayControl");
      Method load = Runtime.class.getDeclaredMethod("loadLibrary0", Class.class, String.class);
      load.setAccessible(true);
      load.invoke(Runtime.getRuntime(), source, "android_servers");
    }
    long[] ids = (long[]) source.getMethod("getPhysicalDisplayIds").invoke(null);
    if (ids == null || ids.length != 1) throw new IllegalStateException("MULTI_PANEL_UNSUPPORTED");
    // Foldable/multiple panels need separate lifecycle validation; fail closed for this preview.
    tokens =
        new IBinder[] {
          (IBinder) source.getMethod("getPhysicalDisplayToken", long.class).invoke(null, ids[0])
        };
    if (tokens[0] == null) throw new IllegalStateException("NO_PANEL");
    surface.getMethod("setDisplayPowerMode", IBinder.class, int.class);
  }

  private static boolean unlocked() throws Exception {
    return Boolean.TRUE.equals(
            Build.VERSION.SDK_INT >= 34 ? interactive.invoke(power, 0) : interactive.invoke(power))
        && Boolean.FALSE.equals(locked.invoke(window));
  }

  private static void keepActive() throws Exception {
    guardWrite("PING");
    if (Build.VERSION.SDK_INT >= 31) activity.invoke(power, 0, SystemClock.uptimeMillis(), 0, 0);
    else activity.invoke(power, SystemClock.uptimeMillis(), 0, 0);
  }

  private static synchronized void setPanel(boolean on) throws Exception {
    if (!on) {
      if (guardOutput != null) {
        guardWrite("ARM");
        if (!("KMGUARD ARMED " + guardToken).equals(guardInput.readLine()))
          throw new IllegalStateException("RESTORE_GUARD_NOT_ARMED");
      }
      touched = true; // Even a partial failure must run restoration.
    }
    if (displayPower != null) {
      if (!Boolean.TRUE.equals(displayPower.invoke(displayManager, 0, on)))
        throw new IllegalStateException("DISPLAY_POWER_REJECTED");
    } else {
      Method setter = surface.getMethod("setDisplayPowerMode", IBinder.class, int.class);
      for (IBinder token : tokens) setter.invoke(null, token, on ? 2 : 0);
    }
    if (on) touched = false;
  }

  private static synchronized void restore() {
    if (!touched) return;
    try {
      setPanel(true);
    } catch (Throwable ignored) {
      System.out.println("KMPANEL RESTORE_FAILED");
    }
  }

  public static void main(String[] args) {
    if ((args.length != 1 && args.length != 2)
        || !args[0].matches("[a-f0-9-]{36}")
        || android.os.Process.myUid() != 2000
        || Build.VERSION.SDK_INT < 30) return;
    String token = args[0];
    if (args.length == 2) {
      if (args[1].equals("guard")) runGuard(token);
      return;
    }
    AtomicLong deadline = new AtomicLong(SystemClock.elapsedRealtime() + 8000L);
    AtomicBoolean off = new AtomicBoolean(false);
    Runtime.getRuntime().addShutdownHook(new Thread(PanelShell::restore));
    try {
      if (Looper.myLooper() == null) Looper.prepare();
      initialize();
      if (!unlocked()) throw new IllegalStateException("LOCKED");
      startGuard(token);
      Thread watchdog =
          new Thread(
              () -> {
                try {
                  while (true) {
                    Thread.sleep(1000L);
                    if (SystemClock.elapsedRealtime() > deadline.get() || !unlocked()) break;
                    if (off.get()) keepActive();
                  }
                } catch (Throwable ignored) {
                }
                restore();
                closeGuard();
                System.exit(0);
              },
              "KakaoMacroPanelLease");
      watchdog.setDaemon(true);
      watchdog.start();
      System.out.println("KMPANEL PIDS " + android.os.Process.myPid() + " " + guardPid);
      System.out.println("KMPANEL READY " + token);
      System.out.flush();
      BufferedReader input = new BufferedReader(new InputStreamReader(System.in));
      String line;
      while ((line = input.readLine()) != null) {
        if (line.equals("STOP " + token)) break;
        if (!line.equals("OFF " + token) && !line.equals("PING " + token)) break;
        if (!unlocked()) break;
        deadline.set(SystemClock.elapsedRealtime() + 7000L);
        if (line.startsWith("OFF ")) {
          keepActive();
          setPanel(false);
          off.set(true);
        }
        System.out.println("KMPANEL " + (off.get() ? "OFF " : "READY ") + token);
        System.out.flush();
      }
    } catch (Throwable failure) {
      Throwable cause = failure.getCause() == null ? failure : failure.getCause();
      System.out.println(
          "KMPANEL ERROR "
              + token
              + " "
              + cause.getClass().getSimpleName()
              + " "
              + String.valueOf(cause.getMessage()).replace('\n', ' '));
      System.out.flush();
    } finally {
      restore();
      closeGuard();
      System.exit(0);
    }
  }

  /**
   * Detached process survives the control process dying or its ADB shell group being terminated.
   */
  private static void runGuard(String token) {
    AtomicLong deadline = new AtomicLong(SystemClock.elapsedRealtime() + 8000L);
    AtomicBoolean armed = new AtomicBoolean(false);
    Runnable recovery =
        () -> {
          if (armed.get()) {
            touched = true;
            restore();
            if (!touched) android.util.Log.i("KakaoMacroPanelGuard", "RESTORED " + token);
            else android.util.Log.e("KakaoMacroPanelGuard", "RESTORE_FAILED " + token);
          }
        };
    Runtime.getRuntime().addShutdownHook(new Thread(recovery));
    try {
      if (Looper.myLooper() == null) Looper.prepare();
      initialize();
      Thread watchdog =
          new Thread(
              () -> {
                try {
                  while (SystemClock.elapsedRealtime() <= deadline.get() && unlocked())
                    Thread.sleep(500L);
                } catch (Throwable ignored) {
                }
                recovery.run();
                System.exit(0);
              },
              "KakaoMacroPanelRecovery");
      watchdog.setDaemon(true);
      watchdog.start();
      System.out.println("KMGUARD READY " + token + " " + android.os.Process.myPid());
      System.out.flush();
      BufferedReader input = new BufferedReader(new InputStreamReader(System.in));
      String line;
      while ((line = input.readLine()) != null) {
        if (line.equals("STOP " + token)) break;
        if (!line.equals("ARM " + token) && !line.equals("PING " + token)) break;
        deadline.set(SystemClock.elapsedRealtime() + 7000L);
        if (line.startsWith("ARM ")) {
          armed.set(true);
          System.out.println("KMGUARD ARMED " + token);
          System.out.flush();
        }
      }
    } catch (Throwable failure) {
      System.out.println("KMGUARD ERROR " + failure.getClass().getSimpleName());
      System.out.flush();
    } finally {
      recovery.run();
      System.exit(0);
    }
  }
}
