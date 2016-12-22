package com.burnweb.rnwebview;

import android.annotation.SuppressLint;
import com.facebook.react.common.annotations.VisibleForTesting;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.webkit.JsResult;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.os.Environment;

import com.facebook.react.bridge.ActivityEventListener;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;

import java.io.File;
import java.io.IOException;

public class RNWebViewModule extends ReactContextBaseJavaModule implements ActivityEventListener {

    @VisibleForTesting
    public static final String REACT_CLASS = "RNWebViewAndroidModule";

    private RNWebViewPackage aPackage;

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
        AlertDialog ad = new AlertDialog.Builder(getCurrentActivity())
                                .setMessage(message)
                                .setPositiveButton("Ok", new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface dialog, int which) {
                                        result.confirm();
                                    }
                                })
                                .create();

        ad.show();
    }
    
    @SuppressLint("SdCardPath")
    private File createImageFile() {
        //mCameraPhotoPath="/mnt/sdcard/tmp.png";
        File file=new File(Environment.getExternalStorageDirectory()+"/","tmp.png");
        mCameraPhotoPath=file.getAbsolutePath();
        if(!file.exists())
        {
            try {
                file.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return file;
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

        if(acceptType == null || acceptType.isEmpty()) {
            acceptType = "*/*";
        }

        Intent intentChoose = new Intent(Intent.ACTION_GET_CONTENT);
        intentChoose.addCategory(Intent.CATEGORY_OPENABLE);
        intentChoose.setType(acceptType);

        Activity currentActivity = getCurrentActivity();
        if (currentActivity == null) {
            Log.w(REACT_CLASS, "No context available");
            return false;
        }

        try {
            currentActivity.startActivityForResult(intentChoose, REQUEST_SELECT_FILE_LEGACY, new Bundle());
        } catch (ActivityNotFoundException e) {
            Log.e(REACT_CLASS, "No context available");
            e.printStackTrace();

            if (mUploadMessage != null) {
                mUploadMessage.onReceiveValue(null);
                mUploadMessage = null;
            }
            return false;
        }

        return true;
    }

    // For Android 5.0+
    @SuppressLint("NewApi")
    public boolean startFileChooserIntent(ValueCallback<Uri[]> filePathCallback, Intent intentChoose) {
        Log.d(REACT_CLASS, "Open new file dialog");

        if (mUploadMessageArr != null) {
            mUploadMessageArr.onReceiveValue(null);
            mUploadMessageArr = null;
        }

        mUploadMessageArr = filePathCallback;

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
                    //����MediaStore.EXTRA_OUTPUT·��,�������д���ȫ·��
                    photoFile = createImageFile();
                    takePictureIntent.putExtra("PhotoPath", mCameraPhotoPath);
                } catch (Exception ex) {
                    // Error occurred while creating the File
                    Log.e("WebViewSetting", "Unable to create Image File", ex);
                }

                // Continue only if the File was successfully created
                if (photoFile != null) {
                    mCameraPhotoPath = "file:" + photoFile.getAbsolutePath();
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

    @SuppressLint({"NewApi", "Deprecated"})
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        Activity currentActivity = getCurrentActivity();
        if (requestCode == FILECHOOSER_RESULTCODE) {
            if (null == mUploadMessage) return;
            Uri result = data == null || resultCode != Activity.RESULT_OK ? null
                    : data.getData();
            if (result != null) {
                String imagePath = ImageFilePath.getPath(currentActivity, result);
                if (!TextUtils.isEmpty(imagePath)) {
                    result = Uri.parse("file:///" + imagePath);
                }
            }
            mUploadMessage.onReceiveValue(result);
            mUploadMessage = null;
        } else if (requestCode == INPUT_FILE_REQUEST_CODE && mUploadMessageArr != null) {
            // 5.0�Ļص�
            Uri[] results = null;

            // Check that the response is a good one
            if (resultCode == Activity.RESULT_OK) {
                if (data == null) {
                    // If there is not data, then we may have taken a photo
                    if (mCameraPhotoPath != null) {                        
                        results = new Uri[]{Uri.parse(mCameraPhotoPath)};
                    }
                } else {
                    String dataString = data.getDataString();                    
                    if (dataString != null) {
                        results = new Uri[]{Uri.parse(dataString)};
                    }
                }
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

    public void onNewIntent(Intent intent) {}

}
