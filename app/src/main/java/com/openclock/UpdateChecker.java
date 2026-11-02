package com.openclock;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Manual, unauthenticated GitHub Releases updater. The repository must be public.
 *
 * Integration contract for each APK: INTERNET and REQUEST_INSTALL_PACKAGES permissions;
 * non-exported androidx.core.content.FileProvider with grantUriPermissions=true, authority
 * ${applicationId}.updates, and an XML cache-path with path="updates/". The AndroidX core
 * dependency must be present. No background checks, browser, or automatic installation.
 * Android's installer always asks the user; launching it does not mean installation succeeded.
 */
public final class UpdateChecker {
    private static final String API = "https://api.github.com/repos/lnovelli/open-clock/releases/latest";
    private static final long MAX_JSON = 1024 * 1024;
    private static final long MAX_APK = 256L * 1024 * 1024;
    private static final int TIMEOUT_MS = 15000;
    private static final long DEADLINE_NS = TimeUnit.MINUTES.toNanos(3);
    private static final String APK_TYPE = "application/vnd.android.package-archive";

    private UpdateChecker() {}

    /** Shows a manual check for exactly the "phone" or "watch" release asset. */
    public static void show(Activity activity, String role) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        if (!"phone".equals(role) && !"watch".equals(role)) {
            new AlertDialog.Builder(activity).setTitle("Update unavailable")
                    .setMessage("Invalid device type.").setPositiveButton("OK", null).show();
            return;
        }
        new Check(activity, role).start();
    }

    private static final class Release {
        final long version;
        final String url;
        Release(long version, String url) { this.version = version; this.url = url; }
    }

    private static final class Check {
        final WeakReference<Activity> activity;
        final String role;
        final Handler main = new Handler(Looper.getMainLooper());
        volatile boolean cancelled;
        volatile HttpURLConnection connection;
        AlertDialog dialog;
        Application application;
        Application.ActivityLifecycleCallbacks lifecycle;

        Check(Activity activity, String role) {
            this.activity = new WeakReference<>(activity);
            this.role = role;
        }

        Activity live() {
            Activity a = activity.get();
            return a == null || a.isFinishing() || a.isDestroyed() ? null : a;
        }

        void start() {
            Activity a = live();
            if (a == null) return;
            dialog = new AlertDialog.Builder(a).setTitle("Checking for updates")
                    .setMessage("Checking GitHub…").setNegativeButton("Cancel", null)
                    .setPositiveButton("Install", null).create();
            dialog.setOnDismissListener(ignored -> cancel());
            dialog.show();
            application = a.getApplication();
            lifecycle = new Application.ActivityLifecycleCallbacks() {
                @Override public void onActivityCreated(Activity target, Bundle state) { }
                @Override public void onActivityStarted(Activity target) { }
                @Override public void onActivityResumed(Activity target) { }
                @Override public void onActivityPaused(Activity target) { }
                @Override public void onActivitySaveInstanceState(Activity target, Bundle state) { }
                @Override public void onActivityStopped(Activity target) { stopFor(target); }
                @Override public void onActivityDestroyed(Activity target) { stopFor(target); }
                private void stopFor(Activity target) {
                    if (target == activity.get() && dialog != null) dialog.dismiss();
                }
            };
            application.registerActivityLifecycleCallbacks(lifecycle);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setVisibility(View.GONE);
            Context context = a.getApplicationContext();
            new Thread(() -> {
                try {
                    long installed = installedVersion(context.getPackageManager(), context.getPackageName());
                    Release release = fetchRelease();
                    post(() -> {
                        if (release == null) {
                            message("No release found", "GitHub returned HTTP 404. There may be no stable release, or the repository may be private or unavailable.");
                        } else if (release.version <= installed) {
                            message("Up to date", "Installed version code: " + installed
                                    + "\nLatest " + role + " version code: " + release.version + ".");
                        } else {
                            offer(release, installed);
                        }
                    });
                } catch (Exception e) {
                    post(() -> message("Update check failed", errorMessage(e)));
                }
            }, "openclock-update-check").start();
        }

        void cancel() {
            cancelled = true;
            HttpURLConnection c = connection;
            if (c != null) c.disconnect();
            if (application != null && lifecycle != null) {
                application.unregisterActivityLifecycleCallbacks(lifecycle);
                lifecycle = null;
                application = null;
            }
            dialog = null;
        }

        void post(Runnable action) {
            main.post(() -> {
                if (!cancelled && live() != null && dialog != null && dialog.isShowing()) action.run();
            });
        }

        void message(String title, String text) {
            if (dialog == null) return;
            dialog.setTitle(title);
            dialog.setMessage(text);
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setText("Close");
        }

        void offer(Release release, long installed) {
            if (dialog == null) return;
            dialog.setTitle("Update available");
            dialog.setMessage("Installed: " + installed + "\nAvailable " + role + ": " + release.version
                    + "\nDownload and verify the APK before opening the system installer?");
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setText("Cancel");
            // Replace the listener so Install does not dismiss the running dialog.
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setVisibility(View.VISIBLE);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> beginInstall(release));
        }

        void beginInstall(Release release) {
            Activity a = live();
            if (a == null || dialog == null) return;
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            if (!a.getPackageManager().canRequestPackageInstalls()) {
                dialog.setTitle("Installation permission needed");
                dialog.setMessage("Allow Open Clock to install apps in system settings, then check for updates again. "
                        + "If your device does not offer this setting, install the release manually.");
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setText("Settings");
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                    Activity current = live();
                    if (current == null) return;
                    try {
                        current.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + current.getPackageName())));
                        dialog.dismiss();
                    } catch (ActivityNotFoundException | SecurityException e) {
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                        message("Settings unavailable", "This device cannot open the install permission settings. "
                                + "Install the release manually instead.");
                    }
                });
                return;
            }
            dialog.setTitle("Preparing update");
            dialog.setMessage("Downloading and verifying…");
            new Thread(() -> {
                File file = null;
                try {
                    Activity current = live();
                    if (current == null) return;
                    File dir = new File(current.getCacheDir(), "updates");
                    if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
                        throw new IOException("Cannot create update cache.");
                    }
                    file = File.createTempFile("open-clock-", ".apk", dir);
                    download(release.url, file);
                    verifyApk(current.getPackageManager(), current.getPackageName(), file, release.version);
                    if (cancelled || live() == null) return;
                    File verified = file;
                    main.post(() -> {
                        if (cancelled || live() == null || dialog == null || !dialog.isShowing()) {
                            verified.delete();
                            return;
                        }
                        launchInstaller(verified);
                    });
                    file = null; // installer needs this cache file after the dialog closes
                } catch (Exception e) {
                    post(() -> message("Update not installed", errorMessage(e)));
                } finally {
                    if (file != null) file.delete();
                }
            }, "openclock-update-download").start();
        }

        void launchInstaller(File file) {
            Activity a = live();
            if (a == null || dialog == null) { file.delete(); return; }
            try {
                // Recheck because settings and the installed app can change during download.
                if (!a.getPackageManager().canRequestPackageInstalls()) {
                    throw new IOException("Install permission is no longer available. Check again after enabling it.");
                }
                Uri uri = FileProvider.getUriForFile(a, a.getPackageName() + ".updates", file);
                Intent intent = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, APK_TYPE)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                intent.setClipData(ClipData.newRawUri("Open Clock update", uri));
                a.startActivity(intent);
                dialog.dismiss();
            } catch (Exception e) {
                file.delete();
                message("Installer unavailable", "Could not open the system installer. "
                        + "The update was not installed; install it manually if supported.");
            }
        }

        Release fetchRelease() throws Exception {
            HttpURLConnection c = open(API, true);
            try {
                int status = c.getResponseCode();
                if (status == 404) return null;
                if (status != 200) throw new IOException("GitHub returned HTTP " + status + ".");
                byte[] bytes = readLimited(c.getInputStream(), MAX_JSON, null, System.nanoTime());
                JSONObject json = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
                if (json.optBoolean("draft", false) || json.optBoolean("prerelease", false)) {
                    throw new IOException("GitHub did not return a stable release.");
                }
                JSONArray assets = json.getJSONArray("assets");
                Release best = null;
                Set<Long> versions = new HashSet<>();
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject item = assets.getJSONObject(i);
                    String name = item.getString("name");
                    long version = assetVersion(name, role);
                    if (version <= 0) continue;
                    if (!versions.add(version)) throw new IOException("Duplicate release assets for this device.");
                    String url = item.getString("browser_download_url");
                    if (!validAssetUrl(url, name)) throw new IOException("Untrusted release asset URL.");
                    if (best == null || version > best.version) best = new Release(version, url);
                }
                if (best == null) throw new IOException("The latest release has no " + role + " APK.");
                return best;
            } finally { close(c); }
        }

        void download(String url, File file) throws Exception {
            HttpURLConnection c = open(url, false);
            try {
                int status = c.getResponseCode();
                if (status != 200) throw new IOException("Download returned HTTP " + status + ".");
                long length = c.getContentLengthLong();
                if (length > MAX_APK) throw new IOException("APK is too large.");
                try (FileOutputStream out = new FileOutputStream(file)) {
                    readLimited(c.getInputStream(), MAX_APK, out, System.nanoTime());
                }
            } finally { close(c); }
        }

        HttpURLConnection open(String initial, boolean api) throws Exception {
            String current = initial;
            long start = System.nanoTime();
            for (int redirects = 0; redirects <= 5; redirects++) {
                if (cancelled || System.nanoTime() - start > DEADLINE_NS) throw new IOException("Request cancelled or timed out.");
                URI uri = URI.create(current);
                if (!(api ? API.equals(current) : trustedDownloadUri(uri))) throw new IOException("Untrusted download host.");
                HttpURLConnection c = (HttpURLConnection) new URL(current).openConnection();
                connection = c;
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(TIMEOUT_MS);
                c.setReadTimeout(TIMEOUT_MS);
                c.setRequestProperty("User-Agent", "OpenClock-UpdateChecker");
                c.setRequestProperty("Accept", api ? "application/vnd.github+json" : "application/octet-stream");
                int status;
                try {
                    if (cancelled) throw new IOException("Request cancelled.");
                    status = c.getResponseCode();
                } catch (Exception e) {
                    close(c);
                    throw e;
                }
                if (status < 300 || status > 399) return c;
                String location = c.getHeaderField("Location");
                close(c);
                if (api || location == null || redirects == 5) throw new IOException("Unexpected redirect.");
                current = uri.resolve(location).toString();
            }
            throw new IOException("Too many redirects.");
        }

        byte[] readLimited(InputStream in, long max, FileOutputStream out, long start) throws IOException {
            ByteArrayOutputStream memory = out == null ? new ByteArrayOutputStream() : null;
            try (InputStream input = in) {
                byte[] buffer = new byte[16384];
                long total = 0;
                int n;
                while ((n = input.read(buffer)) != -1) {
                    if (cancelled || System.nanoTime() - start > DEADLINE_NS) throw new IOException("Request cancelled or timed out.");
                    total += n;
                    if (total > max) throw new IOException("Response is too large.");
                    if (out != null) out.write(buffer, 0, n); else memory.write(buffer, 0, n);
                }
            }
            return memory == null ? null : memory.toByteArray();
        }

        void close(HttpURLConnection c) {
            c.disconnect();
            if (connection == c) connection = null;
        }
    }

    static long assetVersion(String name, String role) {
        if (!"phone".equals(role) && !"watch".equals(role)) return -1;
        Matcher m = Pattern.compile("open-clock-" + role + "-v([1-9][0-9]*)\\.apk").matcher(name);
        if (!m.matches()) return -1;
        try { return Long.parseLong(m.group(1)); } catch (NumberFormatException e) { return -1; }
    }

    static boolean trustedDownloadUri(URI uri) {
        String host = uri.getHost();
        return "https".equalsIgnoreCase(uri.getScheme()) && uri.getUserInfo() == null
                && (uri.getPort() == -1 || uri.getPort() == 443) && uri.getFragment() == null
                && host != null && (host.equalsIgnoreCase("github.com")
                || host.equalsIgnoreCase("release-assets.githubusercontent.com")
                || host.equalsIgnoreCase("objects.githubusercontent.com")
                || host.equalsIgnoreCase("github-releases.githubusercontent.com"));
    }

    static boolean validAssetUrl(String url, String name) {
        try {
            URI uri = URI.create(url);
            if (!trustedDownloadUri(uri) || !"github.com".equalsIgnoreCase(uri.getHost())
                    || uri.getRawQuery() != null) return false;
            String path = uri.getRawPath();
            String prefix = "/lnovelli/open-clock/releases/download/";
            String suffix = "/" + name;
            if (path == null || !path.startsWith(prefix) || !path.endsWith(suffix)) return false;
            String tag = path.substring(prefix.length(), path.length() - suffix.length());
            return !tag.isEmpty() && tag.indexOf('/') < 0 && !tag.equals(".") && !tag.equals("..");
        } catch (RuntimeException e) { return false; }
    }

    @SuppressWarnings("deprecation")
    private static PackageInfo packageInfo(PackageManager pm, String name) throws Exception {
        return pm.getPackageInfo(name, Build.VERSION.SDK_INT >= 28
                ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES);
    }

    @SuppressWarnings("deprecation")
    private static long version(PackageInfo info) {
        return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
    }

    private static long installedVersion(PackageManager pm, String name) throws Exception {
        return version(packageInfo(pm, name));
    }

    @SuppressWarnings("deprecation")
    private static Signature[] currentSigners(PackageInfo info) {
        return Build.VERSION.SDK_INT >= 28
                ? (info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners())
                : info.signatures;
    }

    private static void verifyApk(PackageManager pm, String packageName, File file, long expected) throws Exception {
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo archive = pm.getPackageArchiveInfo(file.getAbsolutePath(), flags);
        PackageInfo installed = packageInfo(pm, packageName);
        if (archive == null || !packageName.equals(archive.packageName)) throw new IOException("APK package does not match the installed app.");
        if (version(archive) != expected || version(archive) <= version(installed)) {
            throw new IOException("APK version does not match the release or is not newer.");
        }
        Signature[] a = currentSigners(archive), b = currentSigners(installed);
        if (a == null || b == null || a.length == 0 || b.length == 0
                || a.length != b.length || !new HashSet<>(Arrays.asList(a)).equals(new HashSet<>(Arrays.asList(b)))) {
            throw new IOException("APK signing certificate does not match the installed app.");
        }
    }

    private static String errorMessage(Exception e) {
        if (e instanceof IOException && e.getMessage() != null) return e.getMessage();
        return "Unable to check or verify the update. Check your connection and try again.";
    }
}
