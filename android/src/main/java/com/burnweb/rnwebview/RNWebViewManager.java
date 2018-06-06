/**
 * Copyright (c) 2015-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

package com.burnweb.rnwebview;

import javax.annotation.Nullable;

import java.io.UnsupportedEncodingException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Picture;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.webkit.GeolocationPermissions;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.widget.Toast;

import com.facebook.common.logging.FLog;
import com.facebook.react.*;
import com.facebook.react.common.ReactConstants;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.LifecycleEventListener;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.ReadableMapKeySetIterator;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.common.MapBuilder;
import com.facebook.react.common.build.ReactBuildConfig;
import com.facebook.react.module.annotations.ReactModule;
import com.facebook.react.modules.core.DeviceEventManagerModule;
import com.facebook.react.uimanager.SimpleViewManager;
import com.facebook.react.uimanager.ThemedReactContext;
import com.facebook.react.uimanager.UIManagerModule;
import com.facebook.react.uimanager.annotations.ReactProp;
import com.facebook.react.uimanager.events.ContentSizeChangeEvent;
import com.facebook.react.uimanager.events.Event;
import com.facebook.react.uimanager.events.EventDispatcher;
import com.facebook.react.uimanager.events.RCTEventEmitter;
import com.facebook.react.views.webview.events.TopLoadingErrorEvent;
import com.facebook.react.views.webview.events.TopLoadingFinishEvent;
import com.facebook.react.views.webview.events.TopLoadingStartEvent;
import com.facebook.react.views.webview.events.TopMessageEvent;

import org.json.JSONObject;
import org.json.JSONException;

/**
 * Manages instances of {@link WebView}
 *
 * Can accept following commands:
 *  - GO_BACK
 *  - GO_FORWARD
 *  - RELOAD
 *
 * {@link WebView} instances could emit following direct events:
 *  - topLoadingFinish
 *  - topLoadingStart
 *  - topLoadingError
 *
 * Each event will carry the following properties:
 *  - target - view's react tag
 *  - url - url set for the webview
 *  - loading - whether webview is in a loading state
 *  - title - title of the current page
 *  - canGoBack - boolean, whether there is anything on a history stack to go back
 *  - canGoForward - boolean, whether it is possible to request GO_FORWARD command
 */
@ReactModule(name = RNWebViewManager.REACT_CLASS)
public class RNWebViewManager extends SimpleViewManager<WebView> {

  protected static final String REACT_CLASS = "RNWebViewAndroid";

  private static final String HTML_ENCODING = "UTF-8";
  private static final String HTML_MIME_TYPE = "text/html; charset=utf-8";
  private static final String BRIDGE_NAME = "__REACT_WEB_VIEW_BRIDGE";

  private static final String HTTP_METHOD_POST = "POST";

  public static final int COMMAND_GO_BACK = 1;
  public static final int COMMAND_GO_FORWARD = 2;
  public static final int COMMAND_RELOAD = 3;
  public static final int COMMAND_STOP_LOADING = 4;
  public static final int COMMAND_POST_MESSAGE = 5;
  public static final int COMMAND_INJECT_JAVASCRIPT = 6;

  private ThemedReactContext _reactContext;
  private ReactWebView _webView;

  private RNWebViewPackage aPackage;

  // Use `webView.loadUrl("about:blank")` to reliably reset the view
  // state and release page resources (including any running JavaScript).
  private static final String BLANK_URL = "about:blank";

  private WebViewConfig mWebViewConfig;
  private @Nullable WebView.PictureListener mPictureListener;

  protected static class ReactWebViewClient extends WebViewClient {

    private boolean mLastLoadFailed = false;
    // 避免 onPageFinished 执行两次
    private boolean isRedirected = false;
    // 标记 BLANK_URL 已加载
    private boolean isBlankurlLoad = false;

    @Override
    public void onPageFinished(WebView webView, String url) {
      super.onPageFinished(webView, url);

      if (!mLastLoadFailed) {

        if(isRedirected) {
          isRedirected = false;
          ReactWebView reactWebView = (ReactWebView) webView;
          reactWebView.callInjectedJavaScript();
          reactWebView.linkBridge();
        }

        emitFinishEvent(webView, url);
      }

      if(url.equals(BLANK_URL)) {
        isBlankurlLoad = true;
      }
    }

    @Override
    public void onPageStarted(WebView webView, String url, Bitmap favicon) {
      super.onPageStarted(webView, url, favicon);
      mLastLoadFailed = false;
      isRedirected = true;
      dispatchEvent(
              webView,
              new TopLoadingStartEvent(
                      webView.getId(),
                      createWebViewEvent(webView, url)));
    }

    @Override
    public boolean shouldOverrideUrlLoading(WebView view, String url) {
      if (url.startsWith("http://") || url.startsWith("https://") ||
              url.startsWith("file://")) {
        return false;
      } else {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        view.getContext().startActivity(intent);
        return true;
      }
    }

    @Override
    public void onReceivedError(
            WebView webView,
            int errorCode,
            String description,
            String failingUrl) {
      super.onReceivedError(webView, errorCode, description, failingUrl);
      mLastLoadFailed = true;

      // In case of an error JS side expect to get a finish event first, and then get an error event
      // Android WebView does it in the opposite way, so we need to simulate that behavior
      emitFinishEvent(webView, failingUrl);

      WritableMap eventData = createWebViewEvent(webView, failingUrl);
      eventData.putDouble("code", errorCode);
      eventData.putString("description", description);

      dispatchEvent(
              webView,
              new TopLoadingErrorEvent(webView.getId(), eventData));
    }

    @Override
    public void doUpdateVisitedHistory(WebView webView, String url, boolean isReload) {
      super.doUpdateVisitedHistory(webView, url, isReload);

      if(!url.endsWith("#")) {
        dispatchEvent(
                webView,
                new TopLoadingStartEvent(
                        webView.getId(),
                        createWebViewEvent(webView, url)));
      }

      // 清除 BLANK_URL 历史记录
      if(isBlankurlLoad) {
        webView.clearHistory();
        isBlankurlLoad = false;
      }
    }

    private void emitFinishEvent(WebView webView, String url) {
      dispatchEvent(
              webView,
              new TopLoadingFinishEvent(
                      webView.getId(),
                      createWebViewEvent(webView, url)));
    }

    private WritableMap createWebViewEvent(WebView webView, String url) {
      WritableMap event = Arguments.createMap();
      event.putDouble("target", webView.getId());
      // Don't use webView.getUrl() here, the URL isn't updated to the new value yet in callbacks
      // like onPageFinished
      event.putString("url", url);
      event.putBoolean("loading", !mLastLoadFailed && webView.getProgress() != 100);
      event.putString("title", webView.getTitle());
      event.putBoolean("canGoBack", webView.canGoBack());
      event.putBoolean("canGoForward", webView.canGoForward());
      return event;
    }
  }

  protected class CustomWebChromeClient extends WebChromeClient {
    @Override
    public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
      callback.invoke(origin, true, false);
      super.onGeolocationPermissionsShowPrompt(origin, callback);
    }

    @Override
    public boolean onJsAlert(WebView view, String url, String message, JsResult result) {

      getModule().showAlert(url, message, result);

      return true;
    }

    @Override
    public boolean onJsConfirm(WebView view, String url, String message, final JsResult result) {

      getModule().showConfirm(url, message, result);

      return true;
    }

    // For Android 4.1+
    @SuppressWarnings("unused")
    public void openFileChooser(ValueCallback<Uri> uploadMsg, String acceptType, String capture) {
      getModule().startFileChooserIntent(uploadMsg, acceptType);
    }

    // For Android 5.0+
    @SuppressLint("NewApi")
    public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
      String acceptType = "*/*";
      if (fileChooserParams != null && fileChooserParams.getAcceptTypes() != null
              && fileChooserParams.getAcceptTypes().length > 0) {
         if(!fileChooserParams.getAcceptTypes()[0].isEmpty()) {
           acceptType = fileChooserParams.getAcceptTypes()[0];
         }
      }

      return getModule().startFileChooserIntent(filePathCallback, acceptType, Boolean.toString(fileChooserParams.isCaptureEnabled()));
    }
  }

  /**
   * Subclass of {@link WebView} that implements {@link LifecycleEventListener} interface in order
   * to call {@link WebView#destroy} on activty destroy event and also to clear the client
   */
  protected static class ReactWebView extends WebView implements LifecycleEventListener {
    private @Nullable String injectedJS;
    private boolean messagingEnabled = false;

    private class ReactWebViewBridge {
      ReactWebView mContext;

      ReactWebViewBridge(ReactWebView c) {
        mContext = c;
      }

      @JavascriptInterface
      public void postMessage(String message) {
        mContext.onMessage(message);
      }
    }

    /**
     * WebView must be created with an context of the current activity
     *
     * Activity Context is required for creation of dialogs internally by WebView
     * Reactive Native needed for access to ReactNative internal system functionality
     *
     */
    public ReactWebView(ThemedReactContext reactContext) {
      super(reactContext);
    }

    @Override
    public void onHostResume() {
      onResume();
      resumeTimers();
    }

    @Override
    public void onHostPause() {
      onPause();
      pauseTimers();
    }

    @Override
    public void onHostDestroy() {
      cleanupCallbacksAndDestroy();
    }

    public void setInjectedJavaScript(@Nullable String js) {
      injectedJS = js;
    }

    public void setMessagingEnabled(boolean enabled) {
      if (messagingEnabled == enabled) {
        return;
      }

      messagingEnabled = enabled;
      if (enabled) {
        addJavascriptInterface(new ReactWebViewBridge(this), BRIDGE_NAME);
        linkBridge();
      } else {
        removeJavascriptInterface(BRIDGE_NAME);
      }
    }

    public void callInjectedJavaScript() {
      if (getSettings().getJavaScriptEnabled() &&
              injectedJS != null &&
              !TextUtils.isEmpty(injectedJS)) {
        loadUrl("javascript:(function() {\n" + injectedJS + ";\n})();");
      }
    }

    public void linkBridge() {
      if (messagingEnabled) {
        if (ReactBuildConfig.DEBUG && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
          // See isNative in lodash
          String testPostMessageNative = "String(window.postMessage) === String(Object.hasOwnProperty).replace('hasOwnProperty', 'postMessage')";
          evaluateJavascript(testPostMessageNative, new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String value) {
              if (value.equals("true")) {
                FLog.w(ReactConstants.TAG, "Setting onMessage on a WebView overrides existing values of window.postMessage, but a previous value was defined");
              }
            }
          });
        }

        loadUrl("javascript:(" +
                "window.originalPostMessage = window.postMessage," +
                "window.postMessage = function(data) {" +
                BRIDGE_NAME + ".postMessage(String(data));" +
                "}" +
                ")");
      }
    }

    public void onMessage(String message) {
      dispatchEvent(this, new TopMessageEvent(this.getId(), message));
    }

    private void cleanupCallbacksAndDestroy() {
      stopLoading();
      clearHistory();
//      clearCache(true);
      clearFormData();
      clearMatches();
      clearSslPreferences();
      clearDisappearingChildren();
      clearAnimation();
      loadUrl("about:blank");
      removeAllViews();
      setWebChromeClient(null);
      setWebViewClient(null);
      if(this != null) {
        ViewGroup parent = (ViewGroup)getParent();
        if(parent != null) {
          parent.removeView(this);
        }
      }
      destroy();
    }
  }

  public RNWebViewManager() {
    mWebViewConfig = new WebViewConfig() {
      public void configWebView(WebView webView) {
      }
    };
  }

  public RNWebViewManager(WebViewConfig webViewConfig) {
    mWebViewConfig = webViewConfig;
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

  public RNWebViewModule getModule() {
    return this.getPackage().getModule();
  }

  @Override
  protected WebView createViewInstance(ThemedReactContext reactContext) {
    _reactContext = reactContext;
    ReactWebView webView = new ReactWebView(reactContext);
    _webView = webView;

    reactContext.addLifecycleEventListener(webView);

    mWebViewConfig.configWebView(webView);

    WebSettings webSettings = webView.getSettings();

    webSettings.setBuiltInZoomControls(true);
    webSettings.setDisplayZoomControls(false);
    webSettings.setDefaultFontSize(16);
    webSettings.setTextSize(WebSettings.TextSize.NORMAL);

    webSettings.setDatabaseEnabled(true);    
// 启用地理定位
    webSettings.setGeolocationEnabled(true);
// 设置定位的数据库路径
    webSettings.setGeolocationDatabasePath(reactContext.getFilesDir().getPath());

    this.enableHTML5AppCache(webView, reactContext);

    //    webSettings.setLightTouchEnabled(false);
//    webView.setLongClickable(true);
//    this.longClickListener();

    // Fixes broken full-screen modals/galleries due to body height being 0.
    webView.setLayoutParams(
            new LayoutParams(LayoutParams.MATCH_PARENT,
                    LayoutParams.MATCH_PARENT));

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
      WebView.setWebContentsDebuggingEnabled(true);
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      webView.getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
    }

    webView.setWebChromeClient(new CustomWebChromeClient());

    return webView;
  }

  // 开启 html5 appchache 离线
  private void enableHTML5AppCache(WebView webView, ThemedReactContext reactContext) {

    webView.getSettings().setDomStorageEnabled(true);

    // Set cache size to 8 mb by default. should be more than enough
    webView.getSettings().setAppCacheMaxSize(1024*1024*20);

    // This next one is crazy. It's the DEFAULT location for your app's cache
    // But it didn't work for me without this line
    webView.getSettings().setAppCachePath(reactContext.getFilesDir().getAbsolutePath()+"cache/");
    webView.getSettings().setAllowFileAccess(true);
    webView.getSettings().setAppCacheEnabled(true);

    webView.getSettings().setCacheMode(WebSettings.LOAD_DEFAULT);
  }

//  private void longClickListener() {
//    _webView.setOnLongClickListener(new View.OnLongClickListener() {
//      @Override
//      public boolean onLongClick(View v) {
//
//          WebView.HitTestResult result = _webView.getHitTestResult();
//          if (null == result)
//            return false;
//          int type = result.getType();
//          WritableMap event = Arguments.createMap();
//          switch (type) {
//            case WebView.HitTestResult.EDIT_TEXT_TYPE: // 选中的文字类型
//              break;
//            case WebView.HitTestResult.PHONE_TYPE: // 处理拨号
//              break;
//            case WebView.HitTestResult.EMAIL_TYPE: // 处理Email
//              break;
//            case WebView.HitTestResult.GEO_TYPE: // 　地图类型
//              break;
//            case WebView.HitTestResult.SRC_ANCHOR_TYPE: // 超链接
//
//              event.putDouble("target", _webView.getId());
//              event.putString("url", result.getExtra());
//              event.putString("type", "link");
//
//              _reactContext
//                      .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
//                      .emit("webViewLongClick", event);
//
//              return true;
//            case WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE: // 带有链接的图片类型
//            case WebView.HitTestResult.IMAGE_TYPE: // 处理长按图片的菜单项 }
////              Toast.makeText(_reactContext, result.getExtra(), Toast.LENGTH_SHORT).show();
//
//              event.putDouble("target", _webView.getId());
//              event.putString("url", result.getExtra());
//              event.putString("type", "image");
//
//              _reactContext
//                      .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
//                      .emit("webViewLongClick", event);
//
//              return true;
//            case WebView.HitTestResult.UNKNOWN_TYPE: //未知
//              break;
//          }
//          return false;
//      }
//    });
//  }

  @ReactProp(name = "javaScriptEnabled")
  public void setJavaScriptEnabled(WebView view, boolean enabled) {
    view.getSettings().setJavaScriptEnabled(enabled);
  }

  @ReactProp(name = "scalesPageToFit")
  public void setScalesPageToFit(WebView view, boolean enabled) {
    view.getSettings().setUseWideViewPort(!enabled);
  }

  @ReactProp(name = "domStorageEnabled")
  public void setDomStorageEnabled(WebView view, boolean enabled) {
    view.getSettings().setDomStorageEnabled(enabled);
  }

  @ReactProp(name = "userAgent")
  public void setUserAgent(WebView view, @Nullable String userAgent) {
    if (userAgent != null) {
      // TODO(8496850): Fix incorrect behavior when property is unset (uA == null)
      view.getSettings().setUserAgentString(userAgent);
    }
  }

  @ReactProp(name = "mediaPlaybackRequiresUserAction")
  public void setMediaPlaybackRequiresUserAction(WebView view, boolean requires) {
    view.getSettings().setMediaPlaybackRequiresUserGesture(requires);
  }

  @ReactProp(name = "allowUniversalAccessFromFileURLs")
  public void setAllowUniversalAccessFromFileURLs(WebView view, boolean allow) {
    view.getSettings().setAllowUniversalAccessFromFileURLs(allow);
  }

  @ReactProp(name = "injectedJavaScript")
  public void setInjectedJavaScript(WebView view, @Nullable String injectedJavaScript) {
    ((ReactWebView) view).setInjectedJavaScript(injectedJavaScript);
  }

  @ReactProp(name = "messagingEnabled")
  public void setMessagingEnabled(WebView view, boolean enabled) {
    ((ReactWebView) view).setMessagingEnabled(enabled);
  }

  @ReactProp(name = "source")
  public void setSource(WebView view, @Nullable ReadableMap source) {
    if (source != null) {
      if (source.hasKey("html")) {
        String html = source.getString("html");
        if (source.hasKey("baseUrl")) {
          view.loadDataWithBaseURL(
                  source.getString("baseUrl"), html, HTML_MIME_TYPE, HTML_ENCODING, null);
        } else {
          view.loadData(html, HTML_MIME_TYPE, HTML_ENCODING);
        }
        return;
      }
      if (source.hasKey("uri")) {
        String url = source.getString("uri");
        String previousUrl = view.getUrl();
        if (previousUrl != null && previousUrl.equals(url)) {
          return;
        }
        if (source.hasKey("method")) {
          String method = source.getString("method");
          if (method.equals(HTTP_METHOD_POST)) {
            byte[] postData = null;
            if (source.hasKey("body")) {
              String body = source.getString("body");
              try {
                postData = body.getBytes("UTF-8");
              } catch (UnsupportedEncodingException e) {
                postData = body.getBytes();
              }
            }
            if (postData == null) {
              postData = new byte[0];
            }
            view.postUrl(url, postData);
            return;
          }
        }
        HashMap<String, String> headerMap = new HashMap<>();
        if (source.hasKey("headers")) {
          ReadableMap headers = source.getMap("headers");
          ReadableMapKeySetIterator iter = headers.keySetIterator();
          while (iter.hasNextKey()) {
            String key = iter.nextKey();
            if ("user-agent".equals(key.toLowerCase(Locale.ENGLISH))) {
              if (view.getSettings() != null) {
                view.getSettings().setUserAgentString(headers.getString(key));
              }
            } else {
              headerMap.put(key, headers.getString(key));
            }
          }
        }
        view.loadUrl(url, headerMap);
        return;
      }
    }
    view.loadUrl(BLANK_URL);
  }

  @ReactProp(name = "onContentSizeChange")
  public void setOnContentSizeChange(WebView view, boolean sendContentSizeChangeEvents) {
    if (sendContentSizeChangeEvents) {
      view.setPictureListener(getPictureListener());
    } else {
      view.setPictureListener(null);
    }
  }

  @Override
  protected void addEventEmitters(ThemedReactContext reactContext, WebView view) {
    // Do not register default touch emitter and let WebView implementation handle touches
    view.setWebViewClient(new ReactWebViewClient());
  }

  @Override
  public @Nullable Map<String, Integer> getCommandsMap() {
    return MapBuilder.of(
            "goBack", COMMAND_GO_BACK,
            "goForward", COMMAND_GO_FORWARD,
            "reload", COMMAND_RELOAD,
            "stopLoading", COMMAND_STOP_LOADING,
            "postMessage", COMMAND_POST_MESSAGE,
            "injectJavaScript", COMMAND_INJECT_JAVASCRIPT);

  }

  @Override
  public void receiveCommand(WebView root, int commandId, @Nullable ReadableArray args) {
    switch (commandId) {
      case COMMAND_GO_BACK:
        root.goBack();
        break;
      case COMMAND_GO_FORWARD:
        root.goForward();
        break;
      case COMMAND_RELOAD:
        root.reload();
        break;
      case COMMAND_STOP_LOADING:
        root.stopLoading();
        break;
      case COMMAND_POST_MESSAGE:
        try {
          JSONObject eventInitDict = new JSONObject();
          eventInitDict.put("data", args.getString(0));
          root.loadUrl("javascript:(function () {" +
                  "var event;" +
                  "var data = " + eventInitDict.toString() + ";" +
                  "try {" +
                  "event = new MessageEvent('message', data);" +
                  "} catch (e) {" +
                  "event = document.createEvent('MessageEvent');" +
                  "event.initMessageEvent('message', true, true, data.data, data.origin, data.lastEventId, data.source);" +
                  "}" +
                  "document.dispatchEvent(event);" +
                  "})();");
        } catch (JSONException e) {
          throw new RuntimeException(e);
        }
        break;
      case COMMAND_INJECT_JAVASCRIPT:
        root.loadUrl("javascript:" + args.getString(0));
        break;
    }
  }

  @Override
  public void onDropViewInstance(WebView webView) {
    super.onDropViewInstance(webView);
    ((ThemedReactContext) webView.getContext()).removeLifecycleEventListener((ReactWebView) webView);
    ((ReactWebView) webView).cleanupCallbacksAndDestroy();
  }

  private WebView.PictureListener getPictureListener() {
    if (mPictureListener == null) {
      mPictureListener = new WebView.PictureListener() {
        @Override
        public void onNewPicture(WebView webView, Picture picture) {
          dispatchEvent(
                  webView,
                  new ContentSizeChangeEvent(
                          webView.getId(),
                          webView.getWidth(),
                          webView.getContentHeight()));
        }
      };
    }
    return mPictureListener;
  }

  private static void dispatchEvent(WebView webView, Event event) {
    ReactContext reactContext = (ReactContext) webView.getContext();
    EventDispatcher eventDispatcher =
            reactContext.getNativeModule(UIManagerModule.class).getEventDispatcher();
    eventDispatcher.dispatchEvent(event);
  }
}
