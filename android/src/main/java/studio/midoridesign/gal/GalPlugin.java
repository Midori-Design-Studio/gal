package studio.midoridesign.gal;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.app.ActivityCompat;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.PluginRegistry;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Build;
import android.os.Environment;
import android.net.Uri;
import android.provider.MediaStore;
import android.Manifest;
import android.app.Activity;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayInputStream;

import org.apache.commons.imaging.ImageFormat;
import org.apache.commons.imaging.Imaging;

public class GalPlugin implements FlutterPlugin, MethodCallHandler, ActivityAware,
        PluginRegistry.RequestPermissionsResultListener {
    private static final String PERMISSION = Manifest.permission.WRITE_EXTERNAL_STORAGE;
    private static final Uri IMAGE_URI = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
    private static final Uri VIDEO_URI = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
    private static final int PERMISSION_REQUEST_CODE = 1317298; // Anything unique in the app.
    private static final boolean USE_EXTERNAL_STORAGE = Build.VERSION.SDK_INT <= 29;

    private MethodChannel channel;
    private FlutterPluginBinding pluginBinding;
    private Activity activity;
    private Runnable requestAccessCallback;

    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding flutterPluginBinding) {
        channel = new MethodChannel(flutterPluginBinding.getBinaryMessenger(), "gal");
        channel.setMethodCallHandler(this);
        pluginBinding = flutterPluginBinding;
    }

    @Override
    public void onMethodCall(@NonNull MethodCall call, @NonNull Result result) {
        switch (call.method) {
            case "putVideo":
            case "putImage": {
                new Thread(() -> {
                    try {
                        putMedia(call.argument("path"), call.argument("album"),
                                call.method.contains("Image"));
                        new Handler(Looper.getMainLooper()).post(() -> result.success(null));
                    } catch (Exception e) {
                        handleError(e, result);
                    }
                }).start();
                break;
            }
            case "putImageBytes": {
                new Thread(() -> {
                    try {
                        putMediaBytes(call.argument("bytes"), call.argument("album"),
                                call.argument("name"));
                        new Handler(Looper.getMainLooper()).post(() -> result.success(null));
                    } catch (Exception e) {
                        handleError(e, result);
                    }
                }).start();
                break;
            }
            case "open": {
                open();
                new Handler(Looper.getMainLooper()).post(() -> result.success(null));
                break;
            }
            case "hasAccess": {
                result.success(hasAccess(call.argument("toAlbum")));
                break;
            }
            case "requestAccess": {
                if (hasAccess(call.argument("toAlbum"))) {
                    result.success(true);
                    return;
                }
                requestAccessCallback = new Runnable() {
                    @Override
                    public void run() {
                        result.success(hasAccess(call.argument("toAlbum")));
                    }
                };
                requestAccess();
                break;
            }
            default:
                result.notImplemented();
        }
    }

    private void putMedia(String path, String album, boolean isImage)
            throws IOException, SecurityException, FileNotFoundException {
        File file = new File(path);
        String name = file.getName();
        int dotIndex = name.lastIndexOf('.');
        if (dotIndex == -1) throw new FileNotFoundException("Extension not found.");

        try (InputStream in = new FileInputStream(file)) {
            writeData(in, isImage, name.substring(0, dotIndex), name.substring(dotIndex), album);
        }
    }

    private void putMediaBytes(byte[] bytes, String album, String name)
            throws IOException, SecurityException {
        ImageFormat imageFormat = Imaging.guessFormat(bytes);
        String extension = "." + imageFormat.getDefaultExtension().toLowerCase();
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            writeData(in, true, name, extension, album);
        }
    }

    private void writeData(InputStream in, boolean isImage, String name, String extension,
            String album) throws IOException, SecurityException, FileNotFoundException {
        ContentResolver resolver = pluginBinding.getApplicationContext().getContentResolver();
        ContentValues values = createContentValues(isImage, name, extension, album);
        Uri uri = getUniqueFileUri(resolver, values, isImage, name, extension);

        try (OutputStream out = resolver.openOutputStream(uri)) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
        }
    }

    // This is necessary because FileUtils.java only checks up to 31 times to generate unique names.
    // See: https://github.com/natsuk4ze/gal/issues/198
    private Uri getUniqueFileUri(ContentResolver resolver, ContentValues values, boolean isImage,
            String name, String extension) throws IllegalStateException {
        for (int suffix = 0;; suffix++) {
            try {
                values.put(MediaStore.MediaColumns.DISPLAY_NAME,
                        name + (suffix > 0 ? suffix : "") + extension);
                return resolver.insert(isImage ? IMAGE_URI : VIDEO_URI, values);
            } catch (IllegalStateException e) {
                if (!e.getMessage().contains("Failed to build unique file")) throw e;
            }
        }
    }

    @SuppressWarnings("EmptyStatementCheck")
    private ContentValues createContentValues(boolean isImage, String name, String extension,
            String album) {
        ContentValues values = new ContentValues();
        String dirPath = isImage ? Environment.DIRECTORY_PICTURES : Environment.DIRECTORY_MOVIES;

        if (USE_EXTERNAL_STORAGE) {
            File dir = new File(Environment.getExternalStoragePublicDirectory(dirPath),
                    album != null ? album : "");
            if (!dir.exists()) dir.mkdirs();
            String path;
            String n = dir.getPath() + File.separator + name;
            for (int i = 0; new File(path = n + (i == 0 ? "" : i) + extension).exists(); i++);

            values.put(MediaStore.MediaColumns.DATA, path);
        } else {
            String path = dirPath + (album != null ? File.separator + album : "");
            values.put(isImage ? MediaStore.Images.Media.RELATIVE_PATH
                    : MediaStore.Video.Media.RELATIVE_PATH, path);
        }
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name + extension);
        return values;
    }

    private void open() {
        Intent intent = new Intent();
        intent.setAction(Intent.ACTION_VIEW);
        if (Build.VERSION.SDK_INT <= 23) {
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {"image/*", "video/*"});
        } else {
            intent.setData(IMAGE_URI);
        }
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        pluginBinding.getApplicationContext().startActivity(intent);
    }

    private boolean hasAccess(boolean toAlbum) {
        if (Build.VERSION.SDK_INT < 23 || Build.VERSION.SDK_INT > 29) return true;
        if (Build.VERSION.SDK_INT == 29 && !toAlbum) return true;
        Context context = pluginBinding.getApplicationContext();
        int status = ContextCompat.checkSelfPermission(context, PERMISSION);
        return status == PackageManager.PERMISSION_GRANTED;
    }

    private void requestAccess() {
        ActivityCompat.requestPermissions(activity, new String[] {PERMISSION},
                PERMISSION_REQUEST_CODE);
    }

    private void sendError(String errorCode, String message, StackTraceElement[] stackTrace,
            Result result) {
        StringBuilder trace = new StringBuilder();
        for (StackTraceElement st : stackTrace) {
            trace.append(st.toString());
            trace.append("\n");
        }
        new Handler(Looper.getMainLooper())
                .post(() -> result.error(errorCode, message, trace.toString()));
    }

    private void handleError(Exception e, Result result) {
        String errorCode;
        if (e instanceof SecurityException || e.toString().contains("Permission denied")) {
            errorCode = "ACCESS_DENIED";
        } else if (e instanceof FileNotFoundException) {
            errorCode = "NOT_SUPPORTED_FORMAT";
        } else if (e instanceof IOException && e.toString().contains("No space left on device")) {
            errorCode = "NOT_ENOUGH_SPACE";
        } else {
            errorCode = "UNEXPECTED";
        }
        sendError(errorCode, e.toString(), e.getStackTrace(), result);
    }

    @Override
    public void onAttachedToActivity(@NonNull ActivityPluginBinding activityPluginBinding) {
        activity = activityPluginBinding.getActivity();
        activityPluginBinding.addRequestPermissionsResultListener(this);
    }

    @Override
    public void onDetachedFromActivity() {
        activity = null;
    }

    @Override
    public void onReattachedToActivityForConfigChanges(
            @NonNull ActivityPluginBinding activityPluginBinding) {
        activity = activityPluginBinding.getActivity();
        activityPluginBinding.addRequestPermissionsResultListener(this);
    }

    @Override
    public void onDetachedFromActivityForConfigChanges() {
        activity = null;
    }

    @Override
    public boolean onRequestPermissionsResult(int requestCode, String[] permissions,
            int[] grantResults) {
        if (requestCode != PERMISSION_REQUEST_CODE || grantResults.length == 0) return false;
        new Handler(Looper.getMainLooper()).post(requestAccessCallback);
        requestAccessCallback = null;
        return true;
    }

    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
        channel.setMethodCallHandler(null);
        pluginBinding = null;
    }
}
