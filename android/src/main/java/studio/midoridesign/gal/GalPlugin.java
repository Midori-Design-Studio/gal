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
import android.media.MediaScannerConnection;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayInputStream;

import java.util.UUID;

public class GalPlugin implements FlutterPlugin, MethodCallHandler, ActivityAware,
        PluginRegistry.RequestPermissionsResultListener {
    private static final String PERMISSION = Manifest.permission.WRITE_EXTERNAL_STORAGE;
    private static final Uri IMAGE_URI = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
    private static final Uri VIDEO_URI = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
    private static final int PERMISSION_REQUEST_CODE = 1317298; // Anything unique in the app.
    private static final boolean USE_MEDIA_STORE = Build.VERSION.SDK_INT > 23;
    private static final boolean HAS_ACCESS_BY_DEFAULT =
            Build.VERSION.SDK_INT < 23 || Build.VERSION.SDK_INT >= 29;

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
                        putMedia(pluginBinding.getApplicationContext(),
                                (String) call.argument("path"), call.method.contains("Image"));
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
                        putMediaBytes(pluginBinding.getApplicationContext(),
                                (byte[]) call.argument("bytes"));
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
                result.success(hasAccess());
                break;
            }
            case "requestAccess": {
                if (hasAccess()) {
                    result.success(true);
                    return;
                }
                requestAccessCallback = new Runnable() {
                    @Override
                    public void run() {
                        result.success(hasAccess());
                    }
                };
                requestAccess();
                break;
            }
            default:
                result.notImplemented();
        }
    }

    private void putMedia(Context context, String path, boolean isImage)
            throws IOException, SecurityException, FileNotFoundException {
        File file = new File(path);
        try (InputStream in = new FileInputStream(file)) {
            if (USE_MEDIA_STORE) {
                putMediaToMediaStore(context, in, isImage);
            } else {
                putMediaToExternalStorage(context, in, isImage, file.getName());
            }
        }
    }

    private void putMediaBytes(Context context, byte[] bytes)
            throws IOException, SecurityException {
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            if (USE_MEDIA_STORE) {
                putMediaToMediaStore(context, in, true);
            } else {
                putMediaToExternalStorage(context, in, true, "image.jpg");
            }
        }
    }

    private void putMediaToMediaStore(Context context, InputStream in, boolean isImage)
            throws IOException, SecurityException, FileNotFoundException {
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        Uri uri = resolver.insert(isImage ? IMAGE_URI : VIDEO_URI, values);

        try (OutputStream out = resolver.openOutputStream(uri)) {
            writeData(in, out);
        }
    }

    private void putMediaToExternalStorage(Context context, InputStream in, boolean isImage,
            String name) throws IOException, SecurityException, FileNotFoundException {
        File directory = Environment.getExternalStoragePublicDirectory(
                isImage ? Environment.DIRECTORY_PICTURES : Environment.DIRECTORY_MOVIES);
        if (!directory.exists()) directory.mkdirs();

        int dotIndex = name.lastIndexOf('.');
        if (dotIndex == -1) throw new FileNotFoundException("Extension not found.");
        String extension = name.substring(dotIndex);
        File file = new File(directory, UUID.randomUUID().toString() + extension);

        try (OutputStream out = new FileOutputStream(file)) {
            writeData(in, out);
        }
        MediaScannerConnection.scanFile(context, new String[] {file.getAbsolutePath()}, null, null);
    }

    private void writeData(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[8192];
        int bytesRead;
        while ((bytesRead = in.read(buffer)) != -1) {
            out.write(buffer, 0, bytesRead);
        }
    }

    private void open() {
        Context context = pluginBinding.getApplicationContext();
        Intent intent = new Intent();
        intent.setAction(Intent.ACTION_VIEW);
        intent.setData(IMAGE_URI);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    private boolean hasAccess() {
        if (HAS_ACCESS_BY_DEFAULT) return true;
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
