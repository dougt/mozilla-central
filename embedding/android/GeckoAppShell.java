/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * ***** BEGIN LICENSE BLOCK *****
 * Version: MPL 1.1/GPL 2.0/LGPL 2.1
 *
 * The contents of this file are subject to the Mozilla Public License Version
 * 1.1 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * http://www.mozilla.org/MPL/
 *
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 * for the specific language governing rights and limitations under the
 * License.
 *
 * The Original Code is Mozilla Android code.
 *
 * The Initial Developer of the Original Code is Mozilla Foundation.
 * Portions created by the Initial Developer are Copyright (C) 2010
 * the Initial Developer. All Rights Reserved.
 *
 * Contributor(s):
 *   Vladimir Vukicevic <vladimir@pobox.com>
 *
 * Alternatively, the contents of this file may be used under the terms of
 * either the GNU General Public License Version 2 or later (the "GPL"), or
 * the GNU Lesser General Public License Version 2.1 or later (the "LGPL"),
 * in which case the provisions of the GPL or the LGPL are applicable instead
 * of those above. If you wish to allow use of your version of this file only
 * under the terms of either the GPL or the LGPL, and not to allow others to
 * use your version of this file under the terms of the MPL, indicate your
 * decision by deleting the provisions above and replace them with the notice
 * and other provisions required by the GPL or the LGPL. If you do not delete
 * the provisions above, a recipient may use your version of this file under
 * the terms of any one of the MPL, the GPL or the LGPL.
 *
 * ***** END LICENSE BLOCK ***** */

package org.mozilla.gecko;

import java.io.*;
import java.lang.reflect.*;
import java.nio.*;
import java.nio.channels.*;
import java.text.*;
import java.util.*;
import java.util.zip.*;
import java.util.concurrent.*;

import android.os.*;
import android.app.*;
import android.text.*;
import android.view.*;
import android.view.inputmethod.*;
import android.content.*;
import android.content.res.*;
import android.content.pm.*;
import android.graphics.*;
import android.widget.*;
import android.hardware.*;
import android.location.*;
import android.webkit.MimeTypeMap;
import android.media.MediaScannerConnection;
import android.media.MediaScannerConnection.MediaScannerConnectionClient;
import android.provider.Settings;

import android.util.*;
import android.net.Uri;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;

import android.graphics.drawable.*;
import android.graphics.Bitmap;

import org.json.JSONArray;
import org.json.JSONObject;

public class GeckoAppShell
{
    private static final String LOG_FILE_NAME = "GeckoAppShell";

    // static members only
    private GeckoAppShell() { }

    static private LinkedList<GeckoEvent> gPendingEvents =
        new LinkedList<GeckoEvent>();

    static private boolean gRestartScheduled = false;

    static private final Timer mIMETimer = new Timer();
    static private final HashMap<Integer, AlertNotification>
        mAlertNotifications = new HashMap<Integer, AlertNotification>();

    static private final int NOTIFY_IME_RESETINPUTSTATE = 0;
    static private final int NOTIFY_IME_SETOPENSTATE = 1;
    static private final int NOTIFY_IME_CANCELCOMPOSITION = 2;
    static private final int NOTIFY_IME_FOCUSCHANGE = 3;

    static private File sCacheFile = null;
    static private int sFreeSpace = -1;

    /* The Android-side API: API methods that Android calls */

    // Initialization methods
    public static native void nativeInit();
    public static native void nativeRun(String args);

    // helper methods
    public static native void setSurfaceView(GeckoSurfaceView sv);
    public static native void putenv(String map);
    public static native void onResume();
    public static native void onLowMemory();
    public static native void callObserver(String observerKey, String topic, String data);
    public static native void removeObserver(String observerKey);
    public static native void loadLibs(String apkName, boolean shouldExtract);
    public static native void onChangeNetworkLinkStatus(String status);
    public static native void reportJavaCrash(String stack);

    public static native void processNextNativeEvent();

    // A looper thread, accessed by GeckoAppShell.getHandler
    private static class LooperThread extends Thread {
        public SynchronousQueue<Handler> mHandlerQueue =
            new SynchronousQueue<Handler>();
        
        public void run() {
            Looper.prepare();
            try {
                mHandlerQueue.put(new Handler());
            } catch (InterruptedException ie) {}
            Looper.loop();
        }
    }

    private static class GeckoMediaScannerClient implements MediaScannerConnectionClient {
        private String mFile = "";
        private String mMimeType = "";
        private MediaScannerConnection mScanner = null;

        public GeckoMediaScannerClient(Context aContext, String aFile, String aMimeType) {
            mFile = aFile;
            mMimeType = aMimeType;
            mScanner = new MediaScannerConnection(aContext, this);
            if (mScanner != null)
                mScanner.connect();
        }

        public void onMediaScannerConnected() {
            mScanner.scanFile(mFile, mMimeType);
        }

        public void onScanCompleted(String path, Uri uri) {
            if(path.equals(mFile)) {
                mScanner.disconnect();
                mScanner = null;
            }
        }
    }

    // Get a Handler for the main java thread
    public static Handler getMainHandler() {
        return GeckoApp.mAppContext.mMainHandler;
    }

    private static Handler sHandler = null;

    // Get a Handler for a looper thread, or create one if it doesn't exist yet
    public static Handler getHandler() {
        if (sHandler == null) {
            LooperThread lt = new LooperThread();
            lt.start();
            try {
                sHandler = lt.mHandlerQueue.take();
            } catch (InterruptedException ie) {}
        }
        return sHandler;
    }

    public static File getCacheDir() {
        if (sCacheFile == null)
            sCacheFile = GeckoApp.mAppContext.getCacheDir();
        return sCacheFile;
    }

    public static long getFreeSpace() {
        try {
            if (sFreeSpace == -1) {
                File cacheDir = getCacheDir();
                if (cacheDir != null) {
                    StatFs cacheStats = new StatFs(cacheDir.getPath());
                    sFreeSpace = cacheStats.getFreeBlocks() *
                        cacheStats.getBlockSize();
                } else {
                    Log.i(LOG_FILE_NAME, "Unable to get cache dir");
                }
            }
        } catch (Exception e) {
            Log.e(LOG_FILE_NAME, "exception while stating cache dir: ", e);
        }
        return sFreeSpace;
    }

    static boolean moveFile(File inFile, File outFile)
    {
        Log.i(LOG_FILE_NAME, "moving " + inFile + " to " + outFile);
        if (outFile.isDirectory())
            outFile = new File(outFile, inFile.getName());
        try {
            if (inFile.renameTo(outFile))
                return true;
        } catch (SecurityException se) {
            Log.w(LOG_FILE_NAME, "error trying to rename file", se);
        }
        try {
            long lastModified = inFile.lastModified();
            outFile.createNewFile();
            // so copy it instead
            FileChannel inChannel = new FileInputStream(inFile).getChannel();
            FileChannel outChannel = new FileOutputStream(outFile).getChannel();
            long size = inChannel.size();
            long transferred = inChannel.transferTo(0, size, outChannel);
            inChannel.close();
            outChannel.close();
            outFile.setLastModified(lastModified);

            if (transferred == size)
                inFile.delete();
            else
                return false;
        } catch (Exception e) {
            Log.e(LOG_FILE_NAME, "exception while moving file: ", e);
            try {
                outFile.delete();
            } catch (SecurityException se) {
                Log.w(LOG_FILE_NAME, "error trying to delete file", se);
            }
            return false;
        }
        return true;
    }

    static boolean moveDir(File from, File to) {
        try {
            to.mkdirs();
            if (from.renameTo(to))
                return true;
        } catch (SecurityException se) {
            Log.w(LOG_FILE_NAME, "error trying to rename file", se);
        }
        File[] files = from.listFiles();
        boolean retVal = true;
        if (files == null)
            return false;
        try {
            Iterator fileIterator = Arrays.asList(files).iterator();
            while (fileIterator.hasNext()) {
                File file = (File)fileIterator.next();
                File dest = new File(to, file.getName());
                if (file.isDirectory())
                    retVal = moveDir(file, dest) ? retVal : false;
                else
                    retVal = moveFile(file, dest) ? retVal : false;
            }
            from.delete();
        } catch(Exception e) {
            Log.e(LOG_FILE_NAME, "error trying to move file", e);
        }
        return retVal;
    }

    // java-side stuff
    public static void loadGeckoLibs(String apkName) {
        // The package data lib directory isn't placed in ld.so's
        // search path, so we have to manually load libraries that
        // libxul will depend on.  Not ideal.
        System.loadLibrary("mozutils");
        GeckoApp geckoApp = GeckoApp.mAppContext;
        String homeDir;
        if (Build.VERSION.SDK_INT < 8 ||
            geckoApp.getApplication().getPackageResourcePath().startsWith("/data") ||
            geckoApp.getApplication().getPackageResourcePath().startsWith("/system")) {
            File home = geckoApp.getFilesDir();
            homeDir = home.getPath();
            // handle the application being moved to phone from sdcard
            File profileDir = new File(homeDir, "mozilla");
            File oldHome = new File("/data/data/" + 
                        GeckoApp.mAppContext.getPackageName() + "/mozilla");
            if (oldHome.exists())
                moveDir(oldHome, profileDir);
            if (Build.VERSION.SDK_INT >= 8) {
                File extHome =  geckoApp.getExternalFilesDir(null);
                File extProf = new File (extHome, "mozilla");
                if (extHome != null && extProf != null && extProf.exists())
                    moveDir(extProf, profileDir);
            }
        } else {
            File home = geckoApp.getExternalFilesDir(null);
            homeDir = home.getPath();
            // handle the application being moved to phone from sdcard
            File profileDir = new File(homeDir, "mozilla");
            File oldHome = new File("/data/data/" + 
                        GeckoApp.mAppContext.getPackageName() + "/mozilla");
            if (oldHome.exists())
                moveDir(oldHome, profileDir);

            File intHome =  geckoApp.getFilesDir();
            File intProf = new File(intHome, "mozilla");
            if (intHome != null && intProf != null && intProf.exists())
                moveDir(intProf, profileDir);
        }
        try {
            String[] dirs = GeckoApp.mAppContext.getPluginDirectories();
            StringBuffer pluginSearchPath = new StringBuffer();
            for (int i = 0; i < dirs.length; i++) {
                Log.i("GeckoPlugins", "dir: " + dirs[i]);
                pluginSearchPath.append(dirs[i]);
                pluginSearchPath.append(":");
            }
            GeckoAppShell.putenv("MOZ_PLUGIN_PATH="+pluginSearchPath);
        } catch (Exception ex) {
            Log.i("GeckoPlugins", "exception getting plugin dirs", ex);
        }

        GeckoAppShell.putenv("HOME=" + homeDir);
        GeckoAppShell.putenv("GRE_HOME=" + GeckoApp.sGREDir.getPath());
        Intent i = geckoApp.getIntent();
        String env = i.getStringExtra("env0");
        Log.i(LOG_FILE_NAME, "env0: "+ env);
        for (int c = 1; env != null; c++) {
            GeckoAppShell.putenv(env);
            env = i.getStringExtra("env" + c);
            Log.i(LOG_FILE_NAME, "env"+ c +": "+ env);
        }

        File f = geckoApp.getDir("tmp", Context.MODE_WORLD_READABLE |
                                 Context.MODE_WORLD_WRITEABLE );

        if (!f.exists())
            f.mkdirs();

        GeckoAppShell.putenv("TMPDIR=" + f.getPath());

        f = Environment.getDownloadCacheDirectory();
        GeckoAppShell.putenv("EXTERNAL_STORAGE=" + f.getPath());

        File cacheFile = getCacheDir();
        GeckoAppShell.putenv("CACHE_PATH=" + cacheFile.getPath());

        // gingerbread introduces File.getUsableSpace(). We should use that.
        long freeSpace = getFreeSpace();
        try {
            File downloadDir = null;
            File updatesDir  = null;
            if (Build.VERSION.SDK_INT >= 8) {
                downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                updatesDir  = GeckoApp.mAppContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            } else {
                updatesDir = downloadDir = new File(Environment.getExternalStorageDirectory().getPath(), "download");
            }
            GeckoAppShell.putenv("DOWNLOADS_DIRECTORY=" + downloadDir.getPath());
            GeckoAppShell.putenv("UPDATES_DIRECTORY="   + updatesDir.getPath());
        }
        catch (Exception e) {
            Log.i(LOG_FILE_NAME, "No download directory has been found: " + e);
        }

        putLocaleEnv();

        boolean extractLibs = GeckoApp.ACTION_DEBUG.equals(i.getAction());
        if (!extractLibs) {
            // remove any previously extracted libs
            File[] files = cacheFile.listFiles();
            if (files != null) {
                Iterator cacheFiles = Arrays.asList(files).iterator();
                while (cacheFiles.hasNext()) {
                    File libFile = (File)cacheFiles.next();
                    if (libFile.getName().endsWith(".so"))
                        libFile.delete();
                }
            }
        }
        loadLibs(apkName, extractLibs);
    }

    private static void putLocaleEnv() {
        GeckoAppShell.putenv("LANG=" + Locale.getDefault().toString());
        NumberFormat nf = NumberFormat.getInstance();
        if (nf instanceof DecimalFormat) {
            DecimalFormat df = (DecimalFormat)nf;
            DecimalFormatSymbols dfs = df.getDecimalFormatSymbols();

            GeckoAppShell.putenv("LOCALE_DECIMAL_POINT=" + dfs.getDecimalSeparator());
            GeckoAppShell.putenv("LOCALE_THOUSANDS_SEP=" + dfs.getGroupingSeparator());
            GeckoAppShell.putenv("LOCALE_GROUPING=" + (char)df.getGroupingSize());
        }
    }

    public static void runGecko(String apkPath, String args, String url) {
        // run gecko -- it will spawn its own thread
        GeckoAppShell.nativeInit();

        // Tell Gecko where the target surface view is for rendering
        GeckoAppShell.setSurfaceView(GeckoApp.surfaceView);

        // First argument is the .apk path
        String combinedArgs = apkPath + " -greomni " + apkPath;
        if (args != null)
            combinedArgs += " " + args;
        if (url != null)
            combinedArgs += " " + url;

        // and go
        GeckoAppShell.nativeRun(combinedArgs);
    }

    private static GeckoEvent mLastDrawEvent;

    private static void sendPendingEventsToGecko() {
        try {
            while (!gPendingEvents.isEmpty()) {
                GeckoEvent e = gPendingEvents.removeFirst();
                notifyGeckoOfEvent(e);
            }
        } catch (NoSuchElementException e) {}
    }

    public static void sendEventToGecko(GeckoEvent e) {
        if (GeckoApp.checkLaunchState(GeckoApp.LaunchState.GeckoRunning)) {
            notifyGeckoOfEvent(e);
        } else {
            gPendingEvents.addLast(e);
        }
    }

    public static void sendEventToGeckoSync(GeckoEvent e) {
        sendEventToGecko(e);
        geckoEventSync();
    }

    // Tell the Gecko event loop that an event is available.
    public static native void notifyGeckoOfEvent(GeckoEvent event);

    /*
     *  The Gecko-side API: API methods that Gecko calls
     */
    public static void scheduleRedraw() {
        // Redraw everything
        scheduleRedraw(0, -1, -1, -1, -1);
    }

    public static void scheduleRedraw(int nativeWindow, int x, int y, int w, int h) {
        GeckoEvent e;

        if (x == -1) {
            e = new GeckoEvent(GeckoEvent.DRAW, null);
        } else {
            e = new GeckoEvent(GeckoEvent.DRAW, new Rect(x, y, w, h));
        }

        e.mNativeWindow = nativeWindow;

        sendEventToGecko(e);
    }

    /* Delay updating IME states (see bug 573800) */
    private static final class IMEStateUpdater extends TimerTask
    {
        static private IMEStateUpdater instance;
        private boolean mEnable, mReset;

        static private IMEStateUpdater getInstance() {
            if (instance == null) {
                instance = new IMEStateUpdater();
                mIMETimer.schedule(instance, 200);
            }
            return instance;
        }

        static public synchronized void enableIME() {
            getInstance().mEnable = true;
        }

        static public synchronized void resetIME() {
            getInstance().mReset = true;
        }

        public void run() {
            synchronized(IMEStateUpdater.class) {
                instance = null;
            }

            InputMethodManager imm = (InputMethodManager)
                GeckoApp.surfaceView.getContext().getSystemService(
                    Context.INPUT_METHOD_SERVICE);
            if (imm == null)
                return;

            if (mReset)
                imm.restartInput(GeckoApp.surfaceView);

            if (!mEnable)
                return;

            int state = GeckoApp.surfaceView.mIMEState;
            if (state != GeckoSurfaceView.IME_STATE_DISABLED &&
                state != GeckoSurfaceView.IME_STATE_PLUGIN)
                imm.showSoftInput(GeckoApp.surfaceView, 0);
            else
                imm.hideSoftInputFromWindow(
                    GeckoApp.surfaceView.getWindowToken(), 0);
        }
    }

    public static void notifyIME(int type, int state) {
        if (GeckoApp.surfaceView == null)
            return;

        switch (type) {
        case NOTIFY_IME_RESETINPUTSTATE:
            // Composition event is already fired from widget.
            // So reset IME flags.
            GeckoApp.surfaceView.inputConnection.reset();
            
            // Don't use IMEStateUpdater for reset.
            // Because IME may not work showSoftInput()
            // after calling restartInput() immediately.
            // So we have to call showSoftInput() delay.
            InputMethodManager imm = (InputMethodManager) 
                GeckoApp.surfaceView.getContext().getSystemService(
                    Context.INPUT_METHOD_SERVICE);
            if (imm == null) {
                // no way to reset IME status directly
                IMEStateUpdater.resetIME();
            } else {
                imm.restartInput(GeckoApp.surfaceView);
            }

            // keep current enabled state
            IMEStateUpdater.enableIME();
            break;

        case NOTIFY_IME_CANCELCOMPOSITION:
            IMEStateUpdater.resetIME();
            break;

        case NOTIFY_IME_FOCUSCHANGE:
            IMEStateUpdater.resetIME();
            break;
        }
    }

    public static void notifyIMEEnabled(int state, String typeHint,
                                        String actionHint, boolean landscapeFS)
    {
        if (GeckoApp.surfaceView == null)
            return;

        /* When IME is 'disabled', IME processing is disabled.
           In addition, the IME UI is hidden */
        GeckoApp.surfaceView.mIMEState = state;
        GeckoApp.surfaceView.mIMETypeHint = typeHint;
        GeckoApp.surfaceView.mIMEActionHint = actionHint;
        GeckoApp.surfaceView.mIMELandscapeFS = landscapeFS;
        IMEStateUpdater.enableIME();
    }

    public static void notifyIMEChange(String text, int start, int end, int newEnd) {
        if (GeckoApp.surfaceView == null ||
            GeckoApp.surfaceView.inputConnection == null)
            return;

        InputMethodManager imm = (InputMethodManager)
            GeckoApp.surfaceView.getContext().getSystemService(
                Context.INPUT_METHOD_SERVICE);
        if (imm == null)
            return;

        // Log.d("GeckoAppJava", String.format("IME: notifyIMEChange: t=%s s=%d ne=%d oe=%d",
        //                                      text, start, newEnd, end));

        if (newEnd < 0)
            GeckoApp.surfaceView.inputConnection.notifySelectionChange(
                imm, start, end);
        else
            GeckoApp.surfaceView.inputConnection.notifyTextChange(
                imm, text, start, end, newEnd);
    }

    private static CountDownLatch sGeckoPendingAcks = null;

    // Block the current thread until the Gecko event loop is caught up
    synchronized public static void geckoEventSync() {
        sGeckoPendingAcks = new CountDownLatch(1);
        GeckoAppShell.sendEventToGecko(
            new GeckoEvent(GeckoEvent.GECKO_EVENT_SYNC));
        while (sGeckoPendingAcks.getCount() != 0) {
            try {
                sGeckoPendingAcks.await();
            } catch (InterruptedException e) {}
        }
        sGeckoPendingAcks = null;
    }

    // Signal the Java thread that it's time to wake up
    public static void acknowledgeEventSync() {
        CountDownLatch tmp = sGeckoPendingAcks;
        if (tmp != null)
            tmp.countDown();
    }

    static Sensor gAccelerometerSensor = null;
    static Sensor gOrientationSensor = null;

    public static void enableDeviceMotion(boolean enable) {

        SensorManager sm = (SensorManager)
            GeckoApp.surfaceView.getContext().getSystemService(Context.SENSOR_SERVICE);

        if (gAccelerometerSensor == null || gOrientationSensor == null) {
            gAccelerometerSensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            gOrientationSensor   = sm.getDefaultSensor(Sensor.TYPE_ORIENTATION);
        }

        if (enable) {
            if (gAccelerometerSensor != null)
                sm.registerListener(GeckoApp.surfaceView, gAccelerometerSensor, SensorManager.SENSOR_DELAY_GAME);
            if (gOrientationSensor != null)
                sm.registerListener(GeckoApp.surfaceView, gOrientationSensor,   SensorManager.SENSOR_DELAY_GAME);
        } else {
            if (gAccelerometerSensor != null)
                sm.unregisterListener(GeckoApp.surfaceView, gAccelerometerSensor);
            if (gOrientationSensor != null)
                sm.unregisterListener(GeckoApp.surfaceView, gOrientationSensor);
        }
    }

    public static void enableLocation(final boolean enable) {
     
        getMainHandler().post(new Runnable() { 
                public void run() {
                    GeckoSurfaceView view = GeckoApp.surfaceView;
                    LocationManager lm = (LocationManager)
                        view.getContext().getSystemService(Context.LOCATION_SERVICE);

                    if (enable) {
                        Criteria crit = new Criteria();
                        crit.setAccuracy(Criteria.ACCURACY_FINE);
                        String provider = lm.getBestProvider(crit, true);
                        if (provider == null)
                            return;

                        Looper l = Looper.getMainLooper();
                        Location loc = lm.getLastKnownLocation(provider);
                        if (loc != null) {
                            view.onLocationChanged(loc);
                        }
                        lm.requestLocationUpdates(provider, 100, (float).5, view, l);
                    } else {
                        lm.removeUpdates(view);
                    }
                }
            });
    }

    public static void moveTaskToBack() {
        GeckoApp.mAppContext.moveTaskToBack(true);
    }

    public static void returnIMEQueryResult(String result, int selectionStart, int selectionLength) {
        GeckoApp.surfaceView.inputConnection.mSelectionStart = selectionStart;
        GeckoApp.surfaceView.inputConnection.mSelectionLength = selectionLength;
        try {
            GeckoApp.surfaceView.inputConnection.mQueryResult.put(result);
        } catch (InterruptedException e) {
        }
    }

    static void onAppShellReady()
    {
        // mLaunchState can only be Launched at this point
        GeckoApp.setLaunchState(GeckoApp.LaunchState.GeckoRunning);
        sendPendingEventsToGecko();
    }

    static void onXreExit() {
        // mLaunchState can only be Launched or GeckoRunning at this point
        GeckoApp.setLaunchState(GeckoApp.LaunchState.GeckoExiting);
        Log.i("GeckoAppJava", "XRE exited");
        if (gRestartScheduled) {
            GeckoApp.mAppContext.doRestart();
        } else {
            Log.i("GeckoAppJava", "we're done, good bye");
            GeckoApp.mAppContext.finish();
        }
        System.exit(0);
    }
    static void scheduleRestart() {
        Log.i("GeckoAppJava", "scheduling restart");
        gRestartScheduled = true;
    }

    // "Installs" an application by creating a shortcut
    static void createShortcut(String aTitle, String aURI, String aIconData, String aType) {
        Log.w("GeckoAppJava", "createShortcut for " + aURI + " [" + aTitle + "] > " + aType);

        // the intent to be launched by the shortcut
        Intent shortcutIntent = new Intent();
        if (aType.equalsIgnoreCase("webapp")) {
            shortcutIntent.setAction("org.mozilla.gecko.WEBAPP");
            shortcutIntent.putExtra("args", "--webapp=" + aURI);
        } else {
            shortcutIntent.setAction("org.mozilla.gecko.BOOKMARK");
            shortcutIntent.putExtra("args", "--url=" + aURI);
        }
        shortcutIntent.setClassName(GeckoApp.mAppContext,
                                    GeckoApp.mAppContext.getPackageName() + ".App");

        Intent intent = new Intent();
        intent.putExtra(Intent.EXTRA_SHORTCUT_INTENT, shortcutIntent);
        intent.putExtra(Intent.EXTRA_SHORTCUT_NAME, aTitle);
        byte[] raw = Base64.decode(aIconData.substring(22), Base64.DEFAULT);
        Bitmap bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.length);
        intent.putExtra(Intent.EXTRA_SHORTCUT_ICON, bitmap);
        intent.setAction("com.android.launcher.action.INSTALL_SHORTCUT");
        GeckoApp.mAppContext.sendBroadcast(intent);
    }

    static String[] getHandlersForMimeType(String aMimeType, String aAction) {
        Intent intent = getIntentForActionString(aAction);
        if (aMimeType != null && aMimeType.length() > 0)
            intent.setType(aMimeType);
        return getHandlersForIntent(intent);
    }

    static String[] getHandlersForURL(String aURL, String aAction) {
        // aURL may contain the whole URL or just the protocol
        Uri uri = aURL.indexOf(':') >= 0 ? Uri.parse(aURL) : new Uri.Builder().scheme(aURL).build();
        Intent intent = getIntentForActionString(aAction);
        intent.setData(uri);
        return getHandlersForIntent(intent);
    }

    static String[] getHandlersForIntent(Intent intent) {
        PackageManager pm =
            GeckoApp.surfaceView.getContext().getPackageManager();
        List<ResolveInfo> list = pm.queryIntentActivities(intent, 0);
        int numAttr = 4;
        String[] ret = new String[list.size() * numAttr];
        for (int i = 0; i < list.size(); i++) {
            ResolveInfo resolveInfo = list.get(i);
            ret[i * numAttr] = resolveInfo.loadLabel(pm).toString();
            if (resolveInfo.isDefault)
                ret[i * numAttr + 1] = "default";
            else
                ret[i * numAttr + 1] = "";
            ret[i * numAttr + 2] = resolveInfo.activityInfo.applicationInfo.packageName;
            ret[i * numAttr + 3] = resolveInfo.activityInfo.name;
        }
        return ret;
    }

    static Intent getIntentForActionString(String aAction) {
        // Default to the view action if no other action as been specified.
        if (aAction != null && aAction.length() > 0)
            return new Intent(aAction);
        else
            return new Intent(Intent.ACTION_VIEW);
    }

    static String getExtensionFromMimeType(String aMimeType) {
        return MimeTypeMap.getSingleton().getExtensionFromMimeType(aMimeType);
    }

    static String getMimeTypeFromExtensions(String aFileExt) {
        MimeTypeMap mtm = MimeTypeMap.getSingleton();
        StringTokenizer st = new StringTokenizer(aFileExt, "., ");
        String type = null;
        String subType = null;
        while (st.hasMoreElements()) {
            String ext = st.nextToken();
            String mt = mtm.getMimeTypeFromExtension(ext);
            if (mt == null)
                continue;
            int slash = mt.indexOf('/');
            String tmpType = mt.substring(0, slash);
            if (!tmpType.equalsIgnoreCase(type))
                type = type == null ? tmpType : "*";
            String tmpSubType = mt.substring(slash + 1);
            if (!tmpSubType.equalsIgnoreCase(subType))
                subType = subType == null ? tmpSubType : "*";
        }
        if (type == null)
            type = "*";
        if (subType == null)
            subType = "*";
        return type + "/" + subType;
    }

    static boolean openUriExternal(String aUriSpec, String aMimeType, String aPackageName,
                                   String aClassName, String aAction, String aTitle) {
        Intent intent = getIntentForActionString(aAction);
        if (aAction.equalsIgnoreCase(Intent.ACTION_SEND)) {
            intent.putExtra(Intent.EXTRA_TEXT, aUriSpec);
            intent.putExtra(Intent.EXTRA_SUBJECT, aTitle);
            if (aMimeType != null && aMimeType.length() > 0)
                intent.setType(aMimeType);
        } else if (aMimeType.length() > 0) {
            intent.setDataAndType(Uri.parse(aUriSpec), aMimeType);
        } else {
            Uri uri = Uri.parse(aUriSpec);
            if ("sms".equals(uri.getScheme())) {
                // Have a apecial handling for the SMS, as the message body
                // is not extracted from the URI automatically
                final String query = uri.getEncodedQuery();
                if (query != null && query.length() > 0) {
                    final String[] fields = query.split("&");
                    boolean foundBody = false;
                    String resultQuery = "";
                    for (int i = 0; i < fields.length; i++) {
                        final String field = fields[i];
                        if (field.length() > 5 && "body=".equals(field.substring(0, 5))) {
                            final String body = Uri.decode(field.substring(5));
                            intent.putExtra("sms_body", body);
                            foundBody = true;
                        }
                        else {
                            resultQuery = resultQuery.concat(resultQuery.length() > 0 ? "&" + field : field);
                        }
                    }
                    if (foundBody) {
                        // Put the query without the body field back into the URI
                        final String prefix = aUriSpec.substring(0, aUriSpec.indexOf('?'));
                        uri = Uri.parse(resultQuery.length() > 0 ? prefix + "?" + resultQuery : prefix);
                    }
                }
            }
            intent.setData(uri);
        }
        if (aPackageName.length() > 0 && aClassName.length() > 0)
            intent.setClassName(aPackageName, aClassName);

        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try {
            GeckoApp.surfaceView.getContext().startActivity(intent);
            return true;
        } catch(ActivityNotFoundException e) {
            return false;
        }
    }

    static SynchronousQueue<String> sClipboardQueue =
        new SynchronousQueue<String>();

    // On some devices, access to the clipboard service needs to happen
    // on a thread with a looper, so dispatch this to our looper thread
    // Note: the main looper won't work because it may be blocked on the
    // gecko thread, which is most likely this thread
    static String getClipboardText() {
        getHandler().post(new Runnable() { 
            public void run() {
                Context context = GeckoApp.surfaceView.getContext();
                String text = null;
                if (android.os.Build.VERSION.SDK_INT >= 11) {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager)
                        context.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm.hasPrimaryClip()) {
                        ClipData clip = cm.getPrimaryClip();
                        if (clip != null) {
                            ClipData.Item item = clip.getItemAt(0);
                            text = item.coerceToText(context).toString();
                        }
                    }
                } else {
                    android.text.ClipboardManager cm = (android.text.ClipboardManager)
                        context.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm.hasText())
                        text = cm.getText().toString();
                }
                try {
                    sClipboardQueue.put(text != null ? text : "");
                } catch (InterruptedException ie) {}
            }});
        try {
            String ret = sClipboardQueue.take();
            return ret == "" ? null : ret;
        } catch (InterruptedException ie) {}
        return null;
    }

    static void setClipboardText(final String text) {
        getHandler().post(new Runnable() { 
            public void run() {
                Context context = GeckoApp.surfaceView.getContext();
                if (android.os.Build.VERSION.SDK_INT >= 11) {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager)
                        context.getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("Text", text));
                } else {
                    android.text.ClipboardManager cm = (android.text.ClipboardManager)
                        context.getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setText(text);
                }
            }});
    }

    public static void showAlertNotification(String aImageUrl, String aAlertTitle, String aAlertText,
                                             String aAlertCookie, String aAlertName) {
        Log.i("GeckoAppJava", "GeckoAppShell.showAlertNotification\n" +
            "- image = '" + aImageUrl + "'\n" +
            "- title = '" + aAlertTitle + "'\n" +
            "- text = '" + aAlertText +"'\n" +
            "- cookie = '" + aAlertCookie +"'\n" +
            "- name = '" + aAlertName + "'");

        int icon = R.drawable.icon; // Just use the app icon by default

        Uri imageUri = Uri.parse(aImageUrl);
        String scheme = imageUri.getScheme();
        if ("drawable".equals(scheme)) {
            String resource = imageUri.getSchemeSpecificPart();
            resource = resource.substring(resource.lastIndexOf('/') + 1);
            try {
                Class drawableClass = R.drawable.class;
                Field f = drawableClass.getField(resource);
                icon = f.getInt(null);
            } catch (Exception e) {} // just means the resource doesn't exist
            imageUri = null;
        }

        int notificationID = aAlertName.hashCode();

        // Remove the old notification with the same ID, if any
        removeNotification(notificationID);

        AlertNotification notification = 
            new AlertNotification(GeckoApp.mAppContext,notificationID, icon, 
                                  aAlertTitle, aAlertText, 
                                  System.currentTimeMillis());

        // The intent to launch when the user clicks the expanded notification
        Intent notificationIntent = new Intent(GeckoApp.ACTION_ALERT_CLICK);
        notificationIntent.setClassName(GeckoApp.mAppContext,
            GeckoApp.mAppContext.getPackageName() + ".NotificationHandler");

        // Put the strings into the intent as an URI "alert:<name>#<cookie>"
        Uri dataUri = Uri.fromParts("alert", aAlertName, aAlertCookie);
        notificationIntent.setData(dataUri);

        PendingIntent contentIntent = PendingIntent.getBroadcast(GeckoApp.mAppContext, 0, notificationIntent, 0);
        notification.setLatestEventInfo(GeckoApp.mAppContext, aAlertTitle, aAlertText, contentIntent);
        notification.setCustomIcon(imageUri);
        // The intent to execute when the status entry is deleted by the user with the "Clear All Notifications" button
        Intent clearNotificationIntent = new Intent(GeckoApp.ACTION_ALERT_CLEAR);
        clearNotificationIntent.setClassName(GeckoApp.mAppContext,
            GeckoApp.mAppContext.getPackageName() + ".NotificationHandler");
        clearNotificationIntent.setData(dataUri);
        notification.deleteIntent = PendingIntent.getBroadcast(GeckoApp.mAppContext, 0, clearNotificationIntent, 0);

        mAlertNotifications.put(notificationID, notification);

        notification.show();

        Log.i("GeckoAppJava", "Created notification ID " + notificationID);
    }

    public static void alertsProgressListener_OnProgress(String aAlertName, long aProgress, long aProgressMax, String aAlertText) {
        Log.i("GeckoAppJava", "GeckoAppShell.alertsProgressListener_OnProgress\n" +
            "- name = '" + aAlertName +"', " +
            "progress = " + aProgress +" / " + aProgressMax + ", text = '" + aAlertText + "'");

        int notificationID = aAlertName.hashCode();
        AlertNotification notification = mAlertNotifications.get(notificationID);
        if (notification != null)
            notification.updateProgress(aAlertText, aProgress, aProgressMax);

        if (aProgress == aProgressMax) {
            // Hide the notification at 100%
            removeObserver(aAlertName);
            removeNotification(notificationID);
        }
    }

    public static void alertsProgressListener_OnCancel(String aAlertName) {
        Log.i("GeckoAppJava", "GeckoAppShell.alertsProgressListener_OnCancel('" + aAlertName + "'");

        removeObserver(aAlertName);

        int notificationID = aAlertName.hashCode();
        removeNotification(notificationID);
    }

    public static void handleNotification(String aAction, String aAlertName, String aAlertCookie) {
        int notificationID = aAlertName.hashCode();

        if (GeckoApp.ACTION_ALERT_CLICK.equals(aAction)) {
            Log.i("GeckoAppJava", "GeckoAppShell.handleNotification: callObserver(alertclickcallback)");
            callObserver(aAlertName, "alertclickcallback", aAlertCookie);

            AlertNotification notification = mAlertNotifications.get(notificationID);
            if (notification != null && notification.isProgressStyle()) {
                // When clicked, keep the notification, if it displays a progress
                return;
            }
        }

        callObserver(aAlertName, "alertfinished", aAlertCookie);

        removeObserver(aAlertName);

        removeNotification(notificationID);
    }

    private static void removeNotification(int notificationID) {
        mAlertNotifications.remove(notificationID);

        NotificationManager notificationManager = (NotificationManager)
            GeckoApp.mAppContext.getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.cancel(notificationID);
    }

    public static int getDpi() {
        DisplayMetrics metrics = new DisplayMetrics();
        GeckoApp.mAppContext.getWindowManager().getDefaultDisplay().getMetrics(metrics);
        return metrics.densityDpi;
    }

    public static void setFullScreen(boolean fullscreen) {
        GeckoApp.mFullscreen = fullscreen;

        // force a reconfiguration to hide/show the system bar
        GeckoApp.mAppContext.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        GeckoApp.mAppContext.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        GeckoApp.mAppContext.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_USER);
    }

    public static String showFilePicker(String aFilters) {
        return GeckoApp.mAppContext.
            showFilePicker(getMimeTypeFromExtensions(aFilters));
    }

    public static void performHapticFeedback(boolean aIsLongPress) {
        GeckoApp.surfaceView.
            performHapticFeedback(aIsLongPress ?
                                  HapticFeedbackConstants.LONG_PRESS :
                                  HapticFeedbackConstants.VIRTUAL_KEY);
    }

    public static void showInputMethodPicker() {
        InputMethodManager imm = (InputMethodManager) GeckoApp.surfaceView.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.showInputMethodPicker();
    }

    public static void setKeepScreenOn(final boolean on) {
        GeckoApp.mAppContext.runOnUiThread(new Runnable() {
            public void run() {
                GeckoApp.surfaceView.setKeepScreenOn(on);
            }
        });
    }

    public static boolean isNetworkLinkUp() {
        ConnectivityManager cm = (ConnectivityManager)
            GeckoApp.mAppContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo info = cm.getActiveNetworkInfo();
        if (info == null || !info.isConnected())
            return false;
        return true;
    }

    public static boolean isNetworkLinkKnown() {
        ConnectivityManager cm = (ConnectivityManager)
            GeckoApp.mAppContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm.getActiveNetworkInfo() == null)
            return false;
        return true;
    }

    public static void setSelectedLocale(String localeCode) {
        SharedPreferences settings =
            GeckoApp.mAppContext.getPreferences(Activity.MODE_PRIVATE);
        settings.edit().putString(GeckoApp.mAppContext.getPackageName() + ".locale",
                                  localeCode).commit();
        Locale locale;
        int index;
        if ((index = localeCode.indexOf('-')) != -1 ||
            (index = localeCode.indexOf('_')) != -1) {
            String langCode = localeCode.substring(0, index);
            String countryCode = localeCode.substring(index + 1);
            locale = new Locale(langCode, countryCode);
        } else {
            locale = new Locale(localeCode);
        }
        Locale.setDefault(locale);

        Resources res = GeckoApp.mAppContext.getBaseContext().getResources();
        Configuration config = res.getConfiguration();
        config.locale = locale;
        res.updateConfiguration(config, res.getDisplayMetrics());
    }

    public static int[] getSystemColors() {
        // attrsAppearance[] must correspond to AndroidSystemColors structure in android/AndroidBridge.h
        final int[] attrsAppearance = {
            android.R.attr.textColor,
            android.R.attr.textColorPrimary,
            android.R.attr.textColorPrimaryInverse,
            android.R.attr.textColorSecondary,
            android.R.attr.textColorSecondaryInverse,
            android.R.attr.textColorTertiary,
            android.R.attr.textColorTertiaryInverse,
            android.R.attr.textColorHighlight,
            android.R.attr.colorForeground,
            android.R.attr.colorBackground,
            android.R.attr.panelColorForeground,
            android.R.attr.panelColorBackground
        };

        int[] result = new int[attrsAppearance.length];

        final ContextThemeWrapper contextThemeWrapper =
            new ContextThemeWrapper(GeckoApp.mAppContext, android.R.style.TextAppearance);

        final TypedArray appearance = contextThemeWrapper.getTheme().obtainStyledAttributes(attrsAppearance);

        if (appearance != null) {
            for (int i = 0; i < appearance.getIndexCount(); i++) {
                int idx = appearance.getIndex(i);
                int color = appearance.getColor(idx, 0);
                result[idx] = color;
            }
            appearance.recycle();
        }

        return result;
    }

    public static void putChildInBackground() {
        try {
            File cgroupFile = new File("/proc/" + android.os.Process.myPid() + "/cgroup");
            BufferedReader br = new BufferedReader(new FileReader(cgroupFile));
            String[] cpuLine = br.readLine().split("/");
            br.close();
            final String backgroundGroup = cpuLine.length == 2 ? cpuLine[1] : "";
            GeckoProcessesVisitor visitor = new GeckoProcessesVisitor() {
                public boolean callback(int pid) {
                    if (pid != android.os.Process.myPid()) {
                        try {
                            FileOutputStream fos = new FileOutputStream(
                                new File("/dev/cpuctl/" + backgroundGroup +"/tasks"));
                            fos.write(new Integer(pid).toString().getBytes());
                            fos.close();
                        } catch(Exception e) {
                            Log.e(LOG_FILE_NAME, "error putting child in the background", e);
                        }
                    }
                    return true;
                }
            };
            EnumerateGeckoProcesses(visitor);
        } catch (Exception e) {
            Log.e("GeckoInputStream", "error reading cgroup", e);
        }
    }

    public static void putChildInForeground() {
        GeckoProcessesVisitor visitor = new GeckoProcessesVisitor() {
            public boolean callback(int pid) {
                if (pid != android.os.Process.myPid()) {
                    try {
                        FileOutputStream fos = new FileOutputStream(new File("/dev/cpuctl/tasks"));
                        fos.write(new Integer(pid).toString().getBytes());
                        fos.close();
                    } catch(Exception e) {
                        Log.e(LOG_FILE_NAME, "error putting child in the foreground", e);
                    }
                }
                return true;
            }
        };   
        EnumerateGeckoProcesses(visitor);
    }

    public static void killAnyZombies() {
        GeckoProcessesVisitor visitor = new GeckoProcessesVisitor() {
            public boolean callback(int pid) {
                if (pid != android.os.Process.myPid())
                    android.os.Process.killProcess(pid);
                return true;
            }
        };
            
        EnumerateGeckoProcesses(visitor);
    }

    public static boolean checkForGeckoProcs() {

        class GeckoPidCallback implements GeckoProcessesVisitor {
            public boolean otherPidExist = false;
            public boolean callback(int pid) {
                if (pid != android.os.Process.myPid()) {
                    otherPidExist = true;
                    return false;
                }
                return true;
            }            
        }
        GeckoPidCallback visitor = new GeckoPidCallback();            
        EnumerateGeckoProcesses(visitor);
        return visitor.otherPidExist;
    }

    interface GeckoProcessesVisitor{
        boolean callback(int pid);
    }

    static int sPidColumn = -1;
    static int sUserColumn = -1;
    private static void EnumerateGeckoProcesses(GeckoProcessesVisitor visiter) {

        try {

            // run ps and parse its output
            java.lang.Process ps = Runtime.getRuntime().exec("ps");
            BufferedReader in = new BufferedReader(new InputStreamReader(ps.getInputStream()),
                                                   2048);

            String headerOutput = in.readLine();

            // figure out the column offsets.  We only care about the pid and user fields
            if (sPidColumn == -1 || sUserColumn == -1) {
                StringTokenizer st = new StringTokenizer(headerOutput);
                
                int tokenSoFar = 0;
                while(st.hasMoreTokens()) {
                    String next = st.nextToken();
                    if (next.equalsIgnoreCase("PID"))
                        sPidColumn = tokenSoFar;
                    else if (next.equalsIgnoreCase("USER"))
                        sUserColumn = tokenSoFar;
                    tokenSoFar++;
                }
            }

            // alright, the rest are process entries.
            String psOutput = null;
            while ((psOutput = in.readLine()) != null) {
                String[] split = psOutput.split("\\s+");
                if (split.length <= sPidColumn || split.length <= sUserColumn)
                    continue;
                int uid = android.os.Process.getUidForName(split[sUserColumn]);
                if (uid == android.os.Process.myUid() &&
                    !split[split.length - 1].equalsIgnoreCase("ps")) {
                    int pid = Integer.parseInt(split[sPidColumn]);
                    boolean keepGoing = visiter.callback(pid);
                    if (keepGoing == false)
                        break;
                }
            }
            in.close();
        }
        catch (Exception e) {
            Log.i(LOG_FILE_NAME, "finding procs throws ",  e);
        }
    }

    public static void waitForAnotherGeckoProc(){
        int countdown = 40;
        while (!checkForGeckoProcs() &&  --countdown > 0) {
            try {
                Thread.currentThread().sleep(100);
            } catch (InterruptedException ie) {}
        }
    }

    public static void scanMedia(String aFile, String aMimeType) {
        Context context = GeckoApp.surfaceView.getContext();
        GeckoMediaScannerClient client = new GeckoMediaScannerClient(context, aFile, aMimeType);
    }

    public static byte[] getIconForExtension(String aExt, int iconSize) {
        try {
            if (iconSize <= 0)
                iconSize = 16;

            if (aExt != null && aExt.length() > 1 && aExt.charAt(0) == '.')
                aExt = aExt.substring(1);

            PackageManager pm = GeckoApp.surfaceView.getContext().getPackageManager();
            Drawable icon = getDrawableForExtension(pm, aExt);
            if (icon == null) {
                // Use a generic icon
                icon = pm.getDefaultActivityIcon();
            }

            Bitmap bitmap = ((BitmapDrawable)icon).getBitmap();
            if (bitmap.getWidth() != iconSize || bitmap.getHeight() != iconSize)
                bitmap = Bitmap.createScaledBitmap(bitmap, iconSize, iconSize, true);

            ByteBuffer buf = ByteBuffer.allocate(iconSize * iconSize * 4);
            bitmap.copyPixelsToBuffer(buf);

            return buf.array();
        }
        catch (Exception e) {
            Log.i(LOG_FILE_NAME, "getIconForExtension error: ",  e);
            return null;
        }
    }

    private static Drawable getDrawableForExtension(PackageManager pm, String aExt) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        MimeTypeMap mtm = MimeTypeMap.getSingleton();
        String mimeType = mtm.getMimeTypeFromExtension(aExt);
        if (mimeType != null && mimeType.length() > 0)
            intent.setType(mimeType);
        else
            return null;

        List<ResolveInfo> list = pm.queryIntentActivities(intent, 0);
        if (list.size() == 0)
            return null;

        ResolveInfo resolveInfo = list.get(0);

        if (resolveInfo == null)
            return null;

        ActivityInfo activityInfo = resolveInfo.activityInfo;

        return activityInfo.loadIcon(pm);
    }

    public static boolean getShowPasswordSetting() {
        try {
            int showPassword =
                Settings.System.getInt(GeckoApp.mAppContext.getContentResolver(),
                                       Settings.System.TEXT_SHOW_PASSWORD);
            return (showPassword > 0);
        }
        catch (Exception e) {
            return false;
        }
    }
    public static void addPluginView(final View view,
                                     final double x, final double y,
                                     final double w, final double h) {

        Log.i("GeckoAppShell", "addPluginView:" + view + " @ x:" + x + " y:" + y + " w:" + w + " h:" + h ) ;

        getMainHandler().post(new Runnable() { 
                public void run() {
                    AbsoluteLayout.LayoutParams lp = new AbsoluteLayout.LayoutParams((int)w,
                                                                                     (int)h,
                                                                                     (int)x,
                                                                                     (int)y);

                    if (GeckoApp.geckoLayout.indexOfChild(view) == -1) {
                        view.setWillNotDraw(false);
                        if(view instanceof SurfaceView)
                            ((SurfaceView)view).setZOrderOnTop(true);

                        GeckoApp.geckoLayout.addView(view, lp);
                    }
                    else
                    {
                        try {
                            GeckoApp.geckoLayout.updateViewLayout(view, lp);
                        } catch (IllegalArgumentException e) {
                            Log.i("updateViewLayout - IllegalArgumentException", "e:" + e);
                            // it can be the case where we
                            // get an update before the view
                            // is actually attached.
                        }
                    }
                }
            });
    }

    public static void removePluginView(final View view) {
        Log.i("GeckoAppShell", "remove view:" + view);
        getMainHandler().post(new Runnable() { 
                public void run() {
                    try {
                        GeckoApp.geckoLayout.removeView(view);
                    } catch (Exception e) {}
                }
            });
    }

    public static Class<?> loadPluginClass(String className, String libName) {
        Log.i("GeckoAppShell", "in loadPluginClass... attempting to access className, then libName.....");
        Log.i("GeckoAppShell", "className: " + className);
        Log.i("GeckoAppShell", "libName: " + libName);

        try {
            String[] split = libName.split("/");
            String packageName = split[split.length - 3];
            Log.i("GeckoAppShell", "load \"" + className + "\" from \"" + packageName + 
                  "\" for \"" + libName + "\"");
            Context pluginContext = 
                GeckoApp.mAppContext.createPackageContext(packageName,
                                                          Context.CONTEXT_INCLUDE_CODE |
                                                      Context.CONTEXT_IGNORE_SECURITY);
            ClassLoader pluginCL = pluginContext.getClassLoader();
            return pluginCL.loadClass(className);
        } catch (java.lang.ClassNotFoundException cnfe) {
            Log.i("GeckoAppShell", "class not found", cnfe);
        } catch (android.content.pm.PackageManager.NameNotFoundException nnfe) {
            Log.i("GeckoAppShell", "package not found", nnfe);
        }
        Log.e("GeckoAppShell", "couldn't find class");
        return null;
    }

    static HashMap<SurfaceView, SurfaceLockInfo> sSufaceMap = new HashMap<SurfaceView, SurfaceLockInfo>();

    public static void lockSurfaceANP()
    {
         Log.i("GeckoAppShell", "other lockSurfaceANP");
    }

    public static org.mozilla.gecko.SurfaceLockInfo lockSurfaceANP(android.view.SurfaceView sview, int top, int left, int bottom, int right)
    {
        Log.i("GeckoAppShell", "real lockSurfaceANP " + sview + ", " + top + ",  " + left + ", " + bottom + ", " + right);
        if (sview == null)
            return null;

        int format = -1;
        try {
            Field privateFormatField = SurfaceView.class.getDeclaredField("mFormat");
            privateFormatField.setAccessible(true);
            format = privateFormatField.getInt(sview);
        } catch (Exception e) {
            Log.i("GeckoAppShell", "mFormat is not a field of sview: ", e);
        }

        int n = 0;
        if (format == PixelFormat.RGB_565)
            n = 2;
        else if (format == PixelFormat.RGBA_8888)
            n = 4;

        if (n == 0)
            return null;

        SurfaceLockInfo info = sSufaceMap.get(sview);
        if (info == null) {
            info = new SurfaceLockInfo();
            sSufaceMap.put(sview, info);
        }

        Rect r = new Rect(left, top, right, bottom);

        info.canvas = sview.getHolder().lockCanvas(r);
        int bufSizeRequired = info.canvas.getWidth() * info.canvas.getHeight() * n;
        Log.i("GeckoAppShell", "lockSurfaceANP - bufSizeRequired: " + n + " " + info.canvas.getHeight() + " " + info.canvas.getWidth());

        if (info.width != info.canvas.getWidth() || info.height != info.canvas.getHeight() || info.buffer == null || info.buffer.capacity() < bufSizeRequired) {
            info.width = info.canvas.getWidth();
            info.height = info.canvas.getHeight();

            // XXX Bitmaps instead of ByteBuffer
            info.buffer = ByteBuffer.allocateDirect(bufSizeRequired);  //leak
            Log.i("GeckoAppShell", "!!!!!!!!!!!  lockSurfaceANP - Allocating buffer! " + bufSizeRequired);

        }

        info.canvas.drawColor(Color.WHITE, PorterDuff.Mode.CLEAR);

        info.format = format;
        info.dirtyTop = top;
        info.dirtyBottom = bottom;
        info.dirtyLeft = left;
        info.dirtyRight = right;

        return info;
    }

    public static void unlockSurfaceANP(SurfaceView sview) {
        SurfaceLockInfo info = sSufaceMap.get(sview);

        int n = 0;
        Bitmap.Config config;
        if (info.format == PixelFormat.RGB_565) {
            n = 2;
            config = Bitmap.Config.RGB_565;
        } else {
            n = 4;
            config = Bitmap.Config.ARGB_8888;
        }

        Log.i("GeckoAppShell", "unlockSurfaceANP: " + (info.width * info.height * n));

        Bitmap bm = Bitmap.createBitmap(info.width, info.height, config);
        bm.copyPixelsFromBuffer(info.buffer);
        info.canvas.drawBitmap(bm, 0, 0, null);
        sview.getHolder().unlockCanvasAndPost(info.canvas);
    }

    public static Class getSurfaceLockInfoClass() {
        Log.i("GeckoAppShell", "class name: " + SurfaceLockInfo.class.getName());
        return SurfaceLockInfo.class;
    }

    public static Method getSurfaceLockMethod() {
        Method[] m = GeckoAppShell.class.getMethods();
        for (int i = 0; i < m.length; i++) {
            if (m[i].getName().equals("lockSurfaceANP"))
                return m[i];
        }
        return null;
    }

    static native void executeNextRunnable();

    static class GeckoRunnableCallback implements Runnable {
        public void run() {
            Log.i("GeckoShell", "run GeckoRunnableCallback");
            GeckoAppShell.executeNextRunnable();
        }
    }

    public static void postToJavaThread(boolean mainThread) {
        Log.i("GeckoShell", "post to " + (mainThread ? "main " : "") + "java thread");
        getMainHandler().post(new GeckoRunnableCallback());
    }
    
    public static android.hardware.Camera sCamera = null;
    
    static native void cameraCallbackBridge(byte[] data);

    static int kPreferedFps = 25;
    static byte[] sCameraBuffer = null;
 
    static int[] initCamera(String aContentType, int aCamera, int aWidth, int aHeight) {
        Log.i("GeckoAppJava", "initCamera(" + aContentType + ", " + aWidth + "x" + aHeight + ") on thread " + Thread.currentThread().getId());

        // [0] = 0|1 (failure/success)
        // [1] = width
        // [2] = height
        // [3] = fps
        int[] result = new int[4];
        result[0] = 0;

        if (Build.VERSION.SDK_INT >= 9) {
            if (android.hardware.Camera.getNumberOfCameras() == 0)
                return result;
        }

        try {
            // no front/back camera before API level 9
            if (Build.VERSION.SDK_INT >= 9)
                sCamera = android.hardware.Camera.open(aCamera);
            else
                sCamera = android.hardware.Camera.open();

            android.hardware.Camera.Parameters params = sCamera.getParameters();
            params.setPreviewFormat(ImageFormat.NV21);

            // use the preview fps closest to 25 fps.
            int fpsDelta = 1000;
            try {
                Iterator<Integer> it = params.getSupportedPreviewFrameRates().iterator();
                while (it.hasNext()) {
                    int nFps = it.next();
                    if (Math.abs(nFps - kPreferedFps) < fpsDelta) {
                        fpsDelta = Math.abs(nFps - kPreferedFps);
                        params.setPreviewFrameRate(nFps);
                    }
                }
            } catch(Exception e) {
                params.setPreviewFrameRate(kPreferedFps);
            }

            // set up the closest preview size available
            Iterator<android.hardware.Camera.Size> sit = params.getSupportedPreviewSizes().iterator();
            int sizeDelta = 10000000;
            int bufferSize = 0;
            while (sit.hasNext()) {
                android.hardware.Camera.Size size = sit.next();
                if (Math.abs(size.width * size.height - aWidth * aHeight) < sizeDelta) {
                    sizeDelta = Math.abs(size.width * size.height - aWidth * aHeight);
                    params.setPreviewSize(size.width, size.height);
                    bufferSize = size.width * size.height;
                }
            }

            sCamera.setParameters(params);
            sCameraBuffer = new byte[(bufferSize * 12) / 8];
            sCamera.addCallbackBuffer(sCameraBuffer);
            sCamera.setPreviewCallbackWithBuffer(new android.hardware.Camera.PreviewCallback() {
                public void onPreviewFrame(byte[] data, android.hardware.Camera camera) {
                    cameraCallbackBridge(data);
                    sCamera.addCallbackBuffer(sCameraBuffer);
                }
            });
            sCamera.startPreview();
            params = sCamera.getParameters();
            Log.i("GeckoAppJava", "Camera: " + params.getPreviewSize().width + "x" + params.getPreviewSize().height +
                  " @ " + params.getPreviewFrameRate() + "fps. format is " + params.getPreviewFormat());
            result[0] = 1;
            result[1] = params.getPreviewSize().width;
            result[2] = params.getPreviewSize().height;
            result[3] = params.getPreviewFrameRate();

            Log.i("GeckoAppJava", "Camera preview started");
        } catch(RuntimeException e) {
            Log.e("GeckoAppJava", "initCamera RuntimeException : ", e);
            result[0] = result[1] = result[2] = result[3] = 0;
        }
        return result;
    }

    static synchronized void closeCamera() {
        Log.i("GeckoAppJava", "closeCamera() on thread " + Thread.currentThread().getId());
        if (sCamera != null) {
            sCamera.stopPreview();
            sCamera.release();
            sCamera = null;
            sCameraBuffer = null;
        }
    }

    public static void handleGeckoMessage(String message) {
        //        
        //        {"gecko": {
        //                "type": "value",
        //                "event_specific": "value",
        //                ....
        try {
            JSONObject json = new JSONObject(message);
            JSONObject geckoObject = json.getJSONObject("gecko");
            String type = geckoObject.getString("type");

            if (type.equals("DOMContentLoaded")) {
                final String uri = geckoObject.getString("uri");
                final String title = geckoObject.getString("title");
                final String stat = geckoObject.getString("stat");
                final CharSequence titleText = title;
                getMainHandler().post(new Runnable() { 
                        public void run() {
                            GeckoApp.mAwesomeBar.setText(titleText);
                            GeckoApp.addHistoryEntry(new GeckoApp.HistoryEntry(uri, title));
                            GeckoApp.mProgressBar.setVisibility(View.GONE);
                        }
                    });
                Log.i("GeckoShell", "URI - " + uri + ", title - " + title + ", status - " + stat);
            }
            else if (type.equals("log")) {
                // generic log listener
                final String msg = geckoObject.getString("msg");
                Log.i("GeckoShell", "Log: " + msg);
            }
            else if (type.equals("onLocationChange")) {
                final String uri = geckoObject.getString("uri");
                final CharSequence uriText = uri;
                Log.i("GeckoShell", "URI - " + uri);
                getMainHandler().post(new Runnable() { 
                        public void run() {
                            GeckoApp.mAwesomeBar.setText(uriText);
                        }
                    });
            }
            else if (type.equals("onStateChange")) {
                String state = geckoObject.getString("state");
                String stateIs = geckoObject.getString("stateIs");

                if (state == "start") {
                    GeckoApp.mProgressBar.setVisibility(View.VISIBLE);
                    GeckoApp.mProgressBar.setIndeterminate(true);
                }
            }
            else if (type.equals("onProgressChange")) {
                final int current = geckoObject.getInt("current");
                final int total = geckoObject.getInt("total");

                getMainHandler().post(new Runnable() { 
                        public void run() {
                            if (total == -1) {
                                GeckoApp.mProgressBar.setIndeterminate(true);
                            } else if (current < total) {
                                GeckoApp.mProgressBar.setIndeterminate(false);
                                GeckoApp.mProgressBar.setMax(total);
                                GeckoApp.mProgressBar.setProgress(current);
                            }
                            else {
                                GeckoApp.mProgressBar.setIndeterminate(false);
                            }
                        }
                    });

                Log.i("GeckoShell", "progress - " + current + "/" + total);
            }
        } catch (Exception e) {
            Log.i("GeckoShell", "handleGeckoMessage throws "+e);
        }
    }
}
