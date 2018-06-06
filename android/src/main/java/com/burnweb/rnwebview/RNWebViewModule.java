package com.burnweb.rnwebview;

import android.Manifest;
import android.annotation.SuppressLint;
import com.facebook.react.common.annotations.VisibleForTesting;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.webkit.JsResult;
import android.webkit.ValueCallback;
import android.provider.MediaStore;
import android.os.Environment;
import android.widget.Toast;

import com.facebook.react.bridge.ActivityEventListener;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import static android.Manifest.permission.WRITE_EXTERNAL_STORAGE;

public class RNWebViewModule extends ReactContextBaseJavaModule implements ActivityEventListener {

    @VisibleForTesting
    public static final String REACT_CLASS = "RNWebViewAndroidModule";

    private RNWebViewPackage aPackage;

    private static final String IMAGE_TYPE = "image/";
    private static final String VIDEO_TYPE = "video/";
    private static final String AUDIO_TYPE = "audio/";
    private static final String ALL_IMAGE_TYPES = IMAGE_TYPE + "*";
    private static final String ALL_VIDEO_TYPES = VIDEO_TYPE + "*";
    private static final String ALL_AUDIO_TYPES = AUDIO_TYPE + "*";
    private static final String ANY_TYPES = "*/*";
    private static final String SPLIT_EXPRESSION = ",";
    private static final String PATH_PREFIX = "file:";

    private static final String TAG = "RNWebViewModule";


    /* FOR UPLOAD DIALOG */
    private final static int REQUEST_SELECT_FILE = 1001;
    private final static int REQUEST_SELECT_FILE_LEGACY = 1002;
    public static final int INPUT_FILE_REQUEST_CODE = 1;
    private final static int FILECHOOSER_RESULTCODE = 2;

    private ValueCallback<Uri> mUploadMessage = null;
    private ValueCallback<Uri[]> mUploadMessageArr = null;

    private String mCameraPhotoPath;

    public RNWebViewModule(ReactApplicationContext reactContext) {
        super(reactContext);

        reactContext.addActivityEventListener(this);
    }

    @Override
    public String getName() {
        return REACT_CLASS;
    }

    public void setPackage(RNWebViewPackage aPackage) {
        this.aPackage = aPackage;
    }

    public RNWebViewPackage getPackage() {
        return this.aPackage;
    }

    @SuppressWarnings("unused")
    public Activity getActivity() {
        return getCurrentActivity();
    }

    public void showAlert(String url, String message, final JsResult result) {
        new AlertDialog.Builder(getCurrentActivity())
                .setCancelable(false)
                .setMessage(message)
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        result.confirm();
                    }
                })
                .create().show();
    }

    public boolean showConfirm(String url, String message, final JsResult result) {
        new AlertDialog.Builder(getCurrentActivity())
                .setCancelable(false)
                .setTitle("提示")
                .setMessage(message)
                .setPositiveButton("确定",
                        new DialogInterface.OnClickListener()
                        {
                            public void onClick(DialogInterface dialog, int which)
                            {
                                result.confirm();
                            }
                        })
                .setNegativeButton("取消",
                        new DialogInterface.OnClickListener()
                        {
                            public void onClick(DialogInterface dialog, int which)
                            {
                                result.cancel();
                            }
                        })
                .create().show();

        return true;
    }

    @SuppressLint("SdCardPath")
    private File createImageFile() {
        // FIXME: If the external storage state is not "MEDIA_MOUNTED", we need to get
        // other volume paths by "getVolumePaths()" when it was exposed.
        String state = Environment.getExternalStorageState();
        if (!state.equals(Environment.MEDIA_MOUNTED)) {
            Log.e(TAG, "External storage is not mounted.");
            return null;
        }

        // Create an image file name
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String imageFileName = "JPEG_" + timeStamp + "_";
        File storageDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_PICTURES);
        if (!storageDir.exists()) {
            storageDir.mkdirs();
        }

        try {
            File file = File.createTempFile(imageFileName, ".jpg", storageDir);
            Log.d(TAG, "Created image file: " +  file.getAbsolutePath());
            return file;
        } catch (IOException e) {
            // Error occurred while creating the File
            Log.e(TAG, "Unable to create Image File, " +
                    "please make sure permission 'WRITE_EXTERNAL_STORAGE' was added.");
            return null;
        }
    }

    // For Android 4.1+
    @SuppressWarnings("unused")
    public boolean startFileChooserIntent(ValueCallback<Uri> uploadMsg, String acceptType) {
        Log.d(REACT_CLASS, "Open old file dialog");

        if (mUploadMessage != null) {
            mUploadMessage.onReceiveValue(null);
            mUploadMessage = null;
        }

        mUploadMessage = uploadMsg;

        Activity currentActivity = getCurrentActivity();
        if (currentActivity == null) {
            Log.w(REACT_CLASS, "No context available");
            return false;
        }

        Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (takePictureIntent.resolveActivity(currentActivity.getPackageManager()) != null) {
            // Create the File where the photo should go
            File photoFile = null;
            try {
                photoFile = createImageFile();
            } catch (Exception ex) {
                // Error occurred while creating the File
                Log.e("WebViewSetting", "Unable to create Image File", ex);
            }

            // Continue only if the File was successfully created
            if (photoFile != null) {
                mCameraPhotoPath = "file:" + photoFile.getAbsolutePath();
                takePictureIntent.putExtra("PhotoPath", mCameraPhotoPath);
                takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT,
                        Uri.fromFile(photoFile));

                System.out.println(mCameraPhotoPath);
            } else {
                takePictureIntent = null;
            }
        }

        Intent contentSelectionIntent = new Intent(Intent.ACTION_GET_CONTENT);
        contentSelectionIntent.addCategory(Intent.CATEGORY_OPENABLE);
        contentSelectionIntent.setType("*/*");
        Intent[] intentArray;
        if (takePictureIntent != null) {
            intentArray = new Intent[]{takePictureIntent};
            System.out.println(takePictureIntent);
        } else {
            intentArray = new Intent[0];
        }
        Intent chooserIntent = new Intent(Intent.ACTION_CHOOSER);
        chooserIntent.putExtra(Intent.EXTRA_INTENT, contentSelectionIntent);
        chooserIntent.putExtra(Intent.EXTRA_TITLE, "文件选择");
        chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, intentArray);

        try {
            currentActivity.startActivityForResult(chooserIntent, INPUT_FILE_REQUEST_CODE);
        } catch (ActivityNotFoundException e) {
            Log.e(REACT_CLASS, "No context available");
            e.printStackTrace();
            if (mUploadMessageArr != null) {
                mUploadMessageArr.onReceiveValue(null);
                mUploadMessageArr = null;
            }
            return false;
        }
        return true;
    }

    // For Android 5.0+
    @SuppressLint("NewApi")
    public boolean startFileChooserIntent(ValueCallback<Uri[]> filePathCallback, String acceptType, String capture) {
        Log.d(REACT_CLASS, "Open new file dialog");

        Activity currentActivity = getCurrentActivity();

        List<String> permissionList = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(currentActivity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED){
            permissionList.add(Manifest.permission.CAMERA);
        }

        if (ContextCompat.checkSelfPermission(currentActivity,Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED){
            permissionList.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }

        if (ContextCompat.checkSelfPermission(currentActivity,Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED){
            permissionList.add(Manifest.permission.RECORD_AUDIO);
        }

        if(!permissionList.isEmpty()) {
            ActivityCompat.requestPermissions(currentActivity, permissionList.toArray(new String[permissionList.size()]), 2);
        }


        if (mUploadMessageArr != null) {
            mUploadMessageArr.onReceiveValue(null);
            mUploadMessageArr = null;
        }

        mUploadMessageArr = filePathCallback;


        if (currentActivity == null) {
            Log.w(REACT_CLASS, "No context available");
            return false;
        }

        Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (takePictureIntent.resolveActivity(currentActivity.getPackageManager()) != null) {
            // Create the File where the photo should go
            File photoFile = null;
            try {
                photoFile = createImageFile();
            } catch (Exception ex) {
                // Error occurred while creating the File
                Log.e("WebViewSetting", "Unable to create Image File", ex);
            }

            // Continue only if the File was successfully created
            if (photoFile != null) {
                mCameraPhotoPath = "file:" + photoFile.getAbsolutePath();
                takePictureIntent.putExtra("PhotoPath", mCameraPhotoPath);
                takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT,
                        Uri.fromFile(photoFile));

                System.out.println(mCameraPhotoPath);
            } else {
                takePictureIntent = null;
            }
        }

        Intent camcorder = new Intent(MediaStore.ACTION_VIDEO_CAPTURE);
        Intent soundRecorder = new Intent(MediaStore.Audio.Media.RECORD_SOUND_ACTION);
        Intent contentSelectionIntent = new Intent(Intent.ACTION_GET_CONTENT);
        contentSelectionIntent.addCategory(Intent.CATEGORY_OPENABLE);
        ArrayList<Intent> extraIntents = new ArrayList<Intent>();

        if (!(acceptType.contains(SPLIT_EXPRESSION) || acceptType.contains(ANY_TYPES))) {
            if (capture.equals("true")) {
                if (acceptType.startsWith(IMAGE_TYPE)) {
                    if (takePictureIntent != null) {
                        currentActivity.startActivityForResult(takePictureIntent, INPUT_FILE_REQUEST_CODE);
                        Log.d(TAG, "Started taking picture");
                        return true;
                    }
                } else if (acceptType.startsWith(VIDEO_TYPE)) {
                    currentActivity.startActivityForResult(camcorder, INPUT_FILE_REQUEST_CODE);
                    Log.d(TAG, "Started camcorder");
                    return true;
                } else if (acceptType.startsWith(AUDIO_TYPE)) {
                    currentActivity.startActivityForResult(soundRecorder, INPUT_FILE_REQUEST_CODE);
                    Log.d(TAG, "Started sound recorder");
                    return true;
                }
            } else {
                if (acceptType.startsWith(IMAGE_TYPE)) {
                    if (takePictureIntent != null) {
                        extraIntents.add(takePictureIntent);
                    }
                    contentSelectionIntent.setType(ALL_IMAGE_TYPES);
                } else if (acceptType.startsWith(VIDEO_TYPE)) {
                    extraIntents.add(camcorder);
                    contentSelectionIntent.setType(ALL_VIDEO_TYPES);
                } else if (acceptType.startsWith(AUDIO_TYPE)) {
                    extraIntents.add(soundRecorder);
                    contentSelectionIntent.setType(ALL_AUDIO_TYPES);
                }
            }
        }

        if (extraIntents.isEmpty() && canWriteExternalStorage()) {
            if (takePictureIntent != null) {
                extraIntents.add(takePictureIntent);
            }
            extraIntents.add(camcorder);
            extraIntents.add(soundRecorder);
            contentSelectionIntent.setType(ANY_TYPES);
        }

        Intent chooserIntent = new Intent(Intent.ACTION_CHOOSER);
        chooserIntent.putExtra(Intent.EXTRA_INTENT, contentSelectionIntent);
        chooserIntent.putExtra(Intent.EXTRA_TITLE, "文件选择");

        if (!extraIntents.isEmpty()) {
            chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS,
                    extraIntents.toArray(new Intent[] { }));
        }

        try {
            currentActivity.startActivityForResult(chooserIntent, INPUT_FILE_REQUEST_CODE);
        } catch (ActivityNotFoundException e) {
            Log.e(REACT_CLASS, "No context available");
            e.printStackTrace();

            if (mUploadMessageArr != null) {
                mUploadMessageArr.onReceiveValue(null);
                mUploadMessageArr = null;
            }
            return false;
        }

        return true;
    }

    @SuppressLint({"NewApi", "Deprecated"})
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == INPUT_FILE_REQUEST_CODE && mUploadMessage != null) {
            if (null == mUploadMessage) return;
            Uri results = null;
            // Check that the response is a good one
            if(Activity.RESULT_OK == resultCode) {
                // In Android M, camera results return an empty Intent rather than null.
                if(data == null || (data.getAction() == null && data.getData() == null)) {
                    // If there is not data, then we may have taken a photo
                    if(mCameraPhotoPath != null) {
                        results = Uri.parse(mCameraPhotoPath);
                    }
                } else {
                    String dataString = data.getDataString();
                    if (dataString != null) {
                        results = Uri.parse(dataString);
                    }
                    deleteImageFile();
                }
            } else if (Activity.RESULT_CANCELED == resultCode) {
                deleteImageFile();
            }

            if (results != null) {
                Log.d(TAG, "Received file: " + results.toString());
            }
            mUploadMessage.onReceiveValue(results);
            mUploadMessage = null;
        } else if (requestCode == INPUT_FILE_REQUEST_CODE && mUploadMessageArr != null) {
            // 5.0
            Uri[] results = null;

            // Check that the response is a good one
            if (resultCode == Activity.RESULT_OK) {
                // In Android M, camera results return an empty Intent rather than null.
                if(data == null || (data.getAction() == null && data.getData() == null)) {
                    // If there is not data, then we may have taken a photo
                    if(mCameraPhotoPath != null) {
                        results = new Uri[]{Uri.parse(mCameraPhotoPath)};
                    }
                } else {
                    String dataString = data.getDataString();
                    if (dataString != null) {
                        results =new Uri[]{Uri.parse(dataString)};
                    }
                    deleteImageFile();
                }
            } else if (Activity.RESULT_CANCELED == resultCode) {
                deleteImageFile();
            }

            if (results != null) {
                Log.d(TAG, "Received file: " + results.toString());
            }
            mUploadMessageArr.onReceiveValue(results);
            mUploadMessageArr = null;
        } else {
            this.onActivityResult(requestCode, resultCode, data);
            return;
        }
    }

    public void onActivityResult(Activity activity, int requestCode, int resultCode, Intent data) {
        this.onActivityResult(requestCode, resultCode, data);
    }

    private boolean canWriteExternalStorage() {
        try {
            Activity currentActivity = getCurrentActivity();
            if (currentActivity == null) {
                Log.w(REACT_CLASS, "No context available");
                return false;
            }

            PackageManager packageManager = currentActivity.getPackageManager();
            PackageInfo packageInfo = packageManager.getPackageInfo(
                    currentActivity.getPackageName(), PackageManager.GET_PERMISSIONS);
            return Arrays.asList(packageInfo.requestedPermissions).contains(WRITE_EXTERNAL_STORAGE);
        } catch (PackageManager.NameNotFoundException | NullPointerException e) {
            return false;
        }
    }

    private boolean deleteImageFile() {
        if (mCameraPhotoPath == null || !mCameraPhotoPath.contains(PATH_PREFIX)) {
            return false;
        }
        String filePath = mCameraPhotoPath.split(PATH_PREFIX)[1];
        boolean result = new File(filePath).delete();
        Log.d(TAG, "Delete image file: " + filePath + " result: " + result);
        return result;
    }

    public void onNewIntent(Intent intent) {}

}
