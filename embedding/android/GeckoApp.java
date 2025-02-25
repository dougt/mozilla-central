/* -*- Mode: Java; c-basic-offset: 4; tab-width: 20; indent-tabs-mode: nil; -*-
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
 * Portions created by the Initial Developer are Copyright (C) 2009-2010
 * the Initial Developer. All Rights Reserved.
 *
 * Contributor(s):
 *   Vladimir Vukicevic <vladimir@pobox.com>
 *   Matt Brubeck <mbrubeck@mozilla.com>
 *   Vivien Nicolas <vnicolas@mozilla.com>
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
import java.util.*;
import java.util.zip.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.util.concurrent.*;
import java.lang.reflect.*;

import android.os.*;
import android.app.*;
import android.text.*;
import android.view.*;
import android.view.inputmethod.*;
import android.content.*;
import android.content.res.*;
import android.graphics.*;
import android.widget.*;
import android.hardware.*;

import android.util.*;
import android.net.*;
import android.database.*;
import android.database.sqlite.*;
import android.provider.*;
import android.content.pm.*;
import android.content.pm.PackageManager.*;
import dalvik.system.*;

abstract public class GeckoApp
    extends Activity
{
    private static final String LOG_FILE_NAME     = "GeckoApp";

    public static final String ACTION_ALERT_CLICK = "org.mozilla.gecko.ACTION_ALERT_CLICK";
    public static final String ACTION_ALERT_CLEAR = "org.mozilla.gecko.ACTION_ALERT_CLEAR";
    public static final String ACTION_WEBAPP      = "org.mozilla.gecko.WEBAPP";
    public static final String ACTION_DEBUG       = "org.mozilla.gecko.DEBUG";
    public static final String ACTION_BOOKMARK    = "org.mozilla.gecko.BOOKMARK";

    public static LinearLayout mainLayout;
    public static AbsoluteLayout geckoLayout;
    public static GeckoSurfaceView surfaceView;
    public static GeckoApp mAppContext;
    public static boolean mFullscreen = false;
    public static File sGREDir = null;
    public Handler mMainHandler;
    private IntentFilter mConnectivityFilter;
    private BroadcastReceiver mConnectivityReceiver;
    public static EditText mAwesomeBar;
    public static ProgressBar mProgressBar;
    private static SQLiteDatabase mDb;
    private static DatabaseHelper mDbHelper;
    private static Stack<HistoryEntry> sessionHistory;

    enum LaunchState {Launching, WaitButton,
                      Launched, GeckoRunning, GeckoExiting};
    private static LaunchState sLaunchState = LaunchState.Launching;
    private static boolean sTryCatchAttached = false;

    private static final int FILE_PICKER_REQUEST = 1;
    private static final int AWESOMEBAR_REQUEST = 2;

    public static class HistoryEntry {
        public String uri;
        public String title;
        public HistoryEntry(String uri, String title) {
            this.uri = uri;
            this.title = title;
        }
    }

    static boolean checkLaunchState(LaunchState checkState) {
        synchronized(sLaunchState) {
            return sLaunchState == checkState;
        }
    }

    static void setLaunchState(LaunchState setState) {
        synchronized(sLaunchState) {
            sLaunchState = setState;
        }
    }

    // if mLaunchState is equal to checkState this sets mLaunchState to setState
    // and return true. Otherwise we return false.
    static boolean checkAndSetLaunchState(LaunchState checkState, LaunchState setState) {
        synchronized(sLaunchState) {
            if (sLaunchState != checkState)
                return false;
            sLaunchState = setState;
            return true;
        }
    }

    void showErrorDialog(String message)
    {
        new AlertDialog.Builder(this)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton(R.string.exit_label,
                               new DialogInterface.OnClickListener() {
                                   public void onClick(DialogInterface dialog,
                                                       int id)
                                   {
                                       GeckoApp.this.finish();
                                       System.exit(0);
                                   }
                               }).show();
    }

    public static final String PLUGIN_ACTION = "android.webkit.PLUGIN";

    /**
     * A plugin that wish to be loaded in the WebView must provide this permission
     * in their AndroidManifest.xml.
     */
    public static final String PLUGIN_PERMISSION = "android.webkit.permission.PLUGIN";

    private static final String LOGTAG = "PluginManager";

    private static final String PLUGIN_SYSTEM_LIB = "/system/lib/plugins/";

    private static final String PLUGIN_TYPE = "type";
    private static final String TYPE_NATIVE = "native";
    public ArrayList<PackageInfo> mPackageInfoCache = new ArrayList<PackageInfo>();

    String[] getPluginDirectories() {

        Log.w(LOGTAG, "zerdatime " + new Date().getTime() + " - start of getPluginDirectories");

        ArrayList<String> directories = new ArrayList<String>();
        PackageManager pm = this.mAppContext.getPackageManager();
        List<ResolveInfo> plugins = pm.queryIntentServices(new Intent(PLUGIN_ACTION),
                PackageManager.GET_SERVICES | PackageManager.GET_META_DATA);

        synchronized(mPackageInfoCache) {

            // clear the list of existing packageInfo objects
            mPackageInfoCache.clear();


            for (ResolveInfo info : plugins) {

                // retrieve the plugin's service information
                ServiceInfo serviceInfo = info.serviceInfo;
                if (serviceInfo == null) {
                    Log.w(LOGTAG, "Ignore bad plugin");
                    continue;
                }

                Log.w(LOGTAG, "Loading plugin: " + serviceInfo.packageName);


                // retrieve information from the plugin's manifest
                PackageInfo pkgInfo;
                try {
                    pkgInfo = pm.getPackageInfo(serviceInfo.packageName,
                                    PackageManager.GET_PERMISSIONS
                                    | PackageManager.GET_SIGNATURES);
                } catch (Exception e) {
                    Log.w(LOGTAG, "Can't find plugin: " + serviceInfo.packageName);
                    continue;
                }
                if (pkgInfo == null) {
                    Log.w(LOGTAG, "Loading plugin: " + serviceInfo.packageName + ". Could not load package information.");
                    continue;
                }

                /*
                 * find the location of the plugin's shared library. The default
                 * is to assume the app is either a user installed app or an
                 * updated system app. In both of these cases the library is
                 * stored in the app's data directory.
                 */
                String directory = pkgInfo.applicationInfo.dataDir + "/lib";
                final int appFlags = pkgInfo.applicationInfo.flags;
                final int updatedSystemFlags = ApplicationInfo.FLAG_SYSTEM |
                                               ApplicationInfo.FLAG_UPDATED_SYSTEM_APP;
                // preloaded system app with no user updates
                if ((appFlags & updatedSystemFlags) == ApplicationInfo.FLAG_SYSTEM) {
                    directory = PLUGIN_SYSTEM_LIB + pkgInfo.packageName;
                }

                // check if the plugin has the required permissions
                String permissions[] = pkgInfo.requestedPermissions;
                if (permissions == null) {
                    Log.w(LOGTAG, "Loading plugin: " + serviceInfo.packageName + ". Does not have required permission.");
                    continue;
                }
                boolean permissionOk = false;
                for (String permit : permissions) {
                    if (PLUGIN_PERMISSION.equals(permit)) {
                        permissionOk = true;
                        break;
                    }
                }
                if (!permissionOk) {
                    Log.w(LOGTAG, "Loading plugin: " + serviceInfo.packageName + ". Does not have required permission (2).");
                    continue;
                }

                // check to ensure the plugin is properly signed
                Signature signatures[] = pkgInfo.signatures;
                if (signatures == null) {
                    Log.w(LOGTAG, "Loading plugin: " + serviceInfo.packageName + ". Not signed.");
                    continue;
                }

                // determine the type of plugin from the manifest
                if (serviceInfo.metaData == null) {
                    Log.e(LOGTAG, "The plugin '" + serviceInfo.name + "' has no type defined");
                    continue;
                }

                String pluginType = serviceInfo.metaData.getString(PLUGIN_TYPE);
                if (!TYPE_NATIVE.equals(pluginType)) {
                    Log.e(LOGTAG, "Unrecognized plugin type: " + pluginType);
                    continue;
                }

                try {
                    Class<?> cls = getPluginClass(serviceInfo.packageName, serviceInfo.name);

                    //TODO implement any requirements of the plugin class here!
                    boolean classFound = true;

                    if (!classFound) {
                        Log.e(LOGTAG, "The plugin's class' " + serviceInfo.name + "' does not extend the appropriate class.");
                        continue;
                    }

                } catch (NameNotFoundException e) {
                    Log.e(LOGTAG, "Can't find plugin: " + serviceInfo.packageName);
                    continue;
                } catch (ClassNotFoundException e) {
                    Log.e(LOGTAG, "Can't find plugin's class: " + serviceInfo.name);
                    continue;
                }

                // if all checks have passed then make the plugin available
                mPackageInfoCache.add(pkgInfo);
                directories.add(directory);
            }
        }

        String [] result = directories.toArray(new String[directories.size()]);
        Log.w(LOGTAG, "zerdatime " + new Date().getTime() + " - end of getPluginDirectories");
        return result;
    }

    Class<?> getPluginClass(String packageName, String className)
            throws NameNotFoundException, ClassNotFoundException {
        Context pluginContext = this.mAppContext.createPackageContext(packageName,
                Context.CONTEXT_INCLUDE_CODE |
                Context.CONTEXT_IGNORE_SECURITY);
        ClassLoader pluginCL = pluginContext.getClassLoader();
        return pluginCL.loadClass(className);
    }

    // Returns true when the intent is going to be handled by gecko launch
    boolean launch(Intent intent)
    {
        Log.w(LOGTAG, "zerdatime " + new Date().getTime() + " - launch");

        if (!checkAndSetLaunchState(LaunchState.Launching, LaunchState.Launched))
            return false;

        if (intent == null)
            intent = getIntent();
        final Intent i = intent;
        new Thread() {
            public void run() {
                File cacheFile = GeckoAppShell.getCacheDir();
                File libxulFile = new File(cacheFile, "libxul.so");

                if ((!libxulFile.exists() ||
                     new File(getApplication().getPackageResourcePath()).lastModified() >= libxulFile.lastModified())) {
                    File[] libs = cacheFile.listFiles(new FilenameFilter() {
                            public boolean accept(File dir, String name) {
                                return name.endsWith(".so");
                            }
                        });
                    if (libs != null) {
                        for (int i = 0; i < libs.length; i++) {
                            libs[i].delete();
                        }
                    }
                 }
 
                // At some point while loading the gecko libs our default locale gets set
                // so just save it to locale here and reset it as default after the join
                Locale locale = Locale.getDefault();
                GeckoAppShell.loadGeckoLibs(
                    getApplication().getPackageResourcePath());
                Locale.setDefault(locale);
                Resources res = getBaseContext().getResources();
                Configuration config = res.getConfiguration();
                config.locale = locale;
                res.updateConfiguration(config, res.getDisplayMetrics());

                Log.w(LOGTAG, "zerdatime " + new Date().getTime() + " - runGecko");

                // and then fire us up
                try {
                    String env = i.getStringExtra("env0");
                    GeckoAppShell.runGecko(getApplication().getPackageResourcePath(),
                                           i.getStringExtra("args"),
                                           i.getDataString());
                } catch (Exception e) {
                    Log.e(LOG_FILE_NAME, "top level exception", e);
                    StringWriter sw = new StringWriter();
                    e.printStackTrace(new PrintWriter(sw));
                    GeckoAppShell.reportJavaCrash(sw.toString());
                }
            }
        }.start();
        return true;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu)
    {
        final Activity self = this;

        MenuItem quitItem = menu.add("Quit");
        quitItem.setIcon(android.R.drawable.ic_menu_close_clear_cancel);
        quitItem.setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
                public boolean onMenuItemClick(MenuItem item) {
                    quit();
                    return true;
                }
            });
        return true;
    }

    private void quit() {
        Log.i(LOG_FILE_NAME, "pleaseKillMe");
        if (surfaceView != null)
            surfaceView.saveLast();
        System.exit(0);
    }

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState)
    {
        Log.w(LOGTAG, "zerdatime " + new Date().getTime() + " - onCreate");

        super.onCreate(savedInstanceState);
        
        getWindow().setFlags(mFullscreen ?
                             WindowManager.LayoutParams.FLAG_FULLSCREEN : 0,
                             WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        
        setContentView(R.layout.gecko_app);
        mAppContext = this;

        // setup gecko layout
        geckoLayout = (AbsoluteLayout) findViewById(R.id.geckoLayout);
        surfaceView = new GeckoSurfaceView(this);
        geckoLayout.addView(surfaceView,
                            new AbsoluteLayout.LayoutParams(AbsoluteLayout.LayoutParams.MATCH_PARENT,
                                                            AbsoluteLayout.LayoutParams.MATCH_PARENT,
                                                            0,
                                                            0));
        Log.w(LOGTAG, "zerdatime " + new Date().getTime() + " - UI almost up");

        if (sGREDir == null)
            sGREDir = new File(this.getApplicationInfo().dataDir);

        mDbHelper = new DatabaseHelper(this);

        sessionHistory = new Stack<HistoryEntry>();

        mMainHandler = new Handler();

        if (!sTryCatchAttached) {
            sTryCatchAttached = true;
            mMainHandler.post(new Runnable() {
                public void run() {
                    try {
                        Looper.loop();
                    } catch (Exception e) {
                        Log.e(LOG_FILE_NAME, "top level exception", e);
                        StringWriter sw = new StringWriter();
                        e.printStackTrace(new PrintWriter(sw));
                        GeckoAppShell.reportJavaCrash(sw.toString());
                    }
                    // resetting this is kinda pointless, but oh well
                    sTryCatchAttached = false;
                }
            });
        }

        mainLayout = (LinearLayout) findViewById(R.id.mainLayout);
        mProgressBar = (ProgressBar) findViewById(R.id.progressBar);

        // setup awesome bar
        mAwesomeBar = (EditText) findViewById(R.id.awesomeBar);
        mAwesomeBar.setOnClickListener(new EditText.OnClickListener() {
            public void onClick(View v) {
                onSearchRequested();
            }
        });

        ImageButton reload = (ImageButton) findViewById(R.id.reload);
        reload.setOnClickListener(new ImageButton.OnClickListener() {
            public void onClick(View v) {
                doReload();
            }
        });

        mConnectivityFilter = new IntentFilter();
        mConnectivityFilter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        mConnectivityReceiver = new GeckoConnectivityReceiver();

        mMainHandler.post(new Runnable() {
            public void run() {
                surfaceView.loadStartupBitmap();
            }
        });

        final GeckoApp self = this;
 
        mMainHandler.postDelayed(new Runnable() {
            public void run() {

                Log.w(LOGTAG, "zerdatime " + new Date().getTime() + " - pre checkLaunchState");

                SharedPreferences settings = getPreferences(Activity.MODE_PRIVATE);
                String localeCode = settings.getString(getPackageName() + ".locale", "");
                if (localeCode != null && localeCode.length() > 0)
                    GeckoAppShell.setSelectedLocale(localeCode);

                if (!checkLaunchState(LaunchState.Launched)) {
                    return;
                }

                if (false) {
                    checkAndLaunchUpdate();
                }
            }
        }, 50);
    }

    public static void addHistoryEntry(final HistoryEntry entry) {
        new Thread(new Runnable() {
            public void run() {
                Log.d("GeckoApp", "adding uri=" + entry.uri + ", title=" + entry.title + " to history");
                ContentValues values = new ContentValues();
                values.put("url", entry.uri);
                values.put("title", entry.title);
                if (sessionHistory.empty() || !sessionHistory.peek().uri.equals(entry.uri))
                    sessionHistory.push(entry);
                mDb = mDbHelper.getWritableDatabase();
                long id = mDb.insertWithOnConflict("moz_places", null, values, SQLiteDatabase.CONFLICT_REPLACE);
                values = new ContentValues();
                values.put("place_id", id);
                mDb.insertWithOnConflict("moz_historyvisits", null, values, SQLiteDatabase.CONFLICT_REPLACE);
            }
        }).start();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        Log.w(LOGTAG, "zerdatime " + new Date().getTime() + " - onNewIntent");

        if (checkLaunchState(LaunchState.GeckoExiting)) {
            // We're exiting and shouldn't try to do anything else just incase
            // we're hung for some reason we'll force the process to exit
            System.exit(0);
            return;
        }
        final String action = intent.getAction();
        if (ACTION_DEBUG.equals(action) &&
            checkAndSetLaunchState(LaunchState.Launching, LaunchState.WaitButton)) {
            final Button launchButton = new Button(this);
            launchButton.setText("Launch"); // don't need to localize
            launchButton.setOnClickListener(new Button.OnClickListener() {
                public void onClick (View v) {
                    // hide the button so we can't be launched again
                    mainLayout.removeView(launchButton);
                    setLaunchState(LaunchState.Launching);
                    launch(null);
                }
            });
            mainLayout.addView(launchButton, 300, 200);
            return;
        }
        if (checkLaunchState(LaunchState.WaitButton) || launch(intent))
            return;

        if (Intent.ACTION_MAIN.equals(action)) {
            Log.i(LOG_FILE_NAME, "Intent : ACTION_MAIN");
            GeckoAppShell.sendEventToGecko(new GeckoEvent(""));
        }
        else if (Intent.ACTION_VIEW.equals(action)) {
            String uri = intent.getDataString();
            GeckoAppShell.sendEventToGecko(new GeckoEvent(uri));
            Log.i(LOG_FILE_NAME,"onNewIntent: "+uri);
        }
        else if (ACTION_WEBAPP.equals(action)) {
            String uri = intent.getStringExtra("args");
            GeckoAppShell.sendEventToGecko(new GeckoEvent(uri));
            Log.i(LOG_FILE_NAME,"Intent : WEBAPP - " + uri);
        }
        else if (ACTION_BOOKMARK.equals(action)) {
            String args = intent.getStringExtra("args");
            GeckoAppShell.sendEventToGecko(new GeckoEvent(args));
            Log.i(LOG_FILE_NAME,"Intent : BOOKMARK - " + args);
        }
    }

    @Override
    public void onPause()
    {
        Log.i(LOG_FILE_NAME, "pause");
        GeckoAppShell.sendEventToGecko(new GeckoEvent(GeckoEvent.ACTIVITY_PAUSING));
        // The user is navigating away from this activity, but nothing
        // has come to the foreground yet; for Gecko, we may want to
        // stop repainting, for example.

        // Whatever we do here should be fast, because we're blocking
        // the next activity from showing up until we finish.

        // onPause will be followed by either onResume or onStop.
        super.onPause();

        unregisterReceiver(mConnectivityReceiver);
    }

    @Override
    public void onResume()
    {
        Log.i(LOG_FILE_NAME, "resume");
        if (checkLaunchState(LaunchState.GeckoRunning))
            GeckoAppShell.onResume();
        // After an onPause, the activity is back in the foreground.
        // Undo whatever we did in onPause.
        super.onResume();

        // Just in case. Normally we start in onNewIntent
        if (checkLaunchState(LaunchState.Launching))
            onNewIntent(getIntent());

        registerReceiver(mConnectivityReceiver, mConnectivityFilter);
    }

    @Override
    public void onStop()
    {
        Log.i(LOG_FILE_NAME, "stop");
        // We're about to be stopped, potentially in preparation for
        // being destroyed.  We're killable after this point -- as I
        // understand it, in extreme cases the process can be terminated
        // without going through onDestroy.
        //
        // We might also get an onRestart after this; not sure what
        // that would mean for Gecko if we were to kill it here.
        // Instead, what we should do here is save prefs, session,
        // etc., and generally mark the profile as 'clean', and then
        // dirty it again if we get an onResume.


        GeckoAppShell.sendEventToGecko(new GeckoEvent(GeckoEvent.ACTIVITY_STOPPING));
        super.onStop();
        GeckoAppShell.putChildInBackground();
    }

    @Override
    public void onRestart()
    {
        Log.i(LOG_FILE_NAME, "restart");
        GeckoAppShell.putChildInForeground();
        super.onRestart();
    }

    @Override
    public void onStart()
    {
        Log.w(LOGTAG, "zerdatime " + new Date().getTime() + " - onStart");

        Log.i(LOG_FILE_NAME, "start");
        GeckoAppShell.sendEventToGecko(new GeckoEvent(GeckoEvent.ACTIVITY_START));
        super.onStart();
    }

    @Override
    public void onDestroy()
    {
        Log.i(LOG_FILE_NAME, "destroy");

        if (mDb != null)
            mDb.close();

        // Tell Gecko to shutting down; we'll end up calling System.exit()
        // in onXreExit.
        if (isFinishing())
            GeckoAppShell.sendEventToGecko(new GeckoEvent(GeckoEvent.ACTIVITY_SHUTDOWN));

        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig)
    {
        Log.i(LOG_FILE_NAME, "configuration changed");
        // nothing, just ignore
        super.onConfigurationChanged(newConfig);
    }

    @Override
    public void onLowMemory()
    {
        Log.e(LOG_FILE_NAME, "low memory");
        if (checkLaunchState(LaunchState.GeckoRunning))
            GeckoAppShell.onLowMemory();
        super.onLowMemory();
    }

    abstract public String getPackageName();
    abstract public String getContentProcessName();

    public void addEnvToIntent(Intent intent) {
        Map<String,String> envMap = System.getenv();
        Set<Map.Entry<String,String>> envSet = envMap.entrySet();
        Iterator<Map.Entry<String,String>> envIter = envSet.iterator();
        StringBuffer envstr = new StringBuffer();
        int c = 0;
        while (envIter.hasNext()) {
            Map.Entry<String,String> entry = envIter.next();
            intent.putExtra("env" + c, entry.getKey() + "="
                            + entry.getValue());
            c++;
        }
    }

    public void doRestart() {
        try {
            String action = "org.mozilla.gecko.restart";
            Intent intent = new Intent(action);
            intent.setClassName(getPackageName(),
                                getPackageName() + ".Restarter");
            addEnvToIntent(intent);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
                            Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
            Log.i(LOG_FILE_NAME, intent.toString());
            GeckoAppShell.killAnyZombies();
            startActivity(intent);
        } catch (Exception e) {
            Log.i(LOG_FILE_NAME, "error doing restart", e);
        }
        finish();
        // Give the restart process time to start before we die
        GeckoAppShell.waitForAnotherGeckoProc();
    }

    public void handleNotification(String action, String alertName, String alertCookie) {
        GeckoAppShell.handleNotification(action, alertName, alertCookie);
    }

    private void checkAndLaunchUpdate() {
        Log.i(LOG_FILE_NAME, "Checking for an update");

        int statusCode = 8; // UNEXPECTED_ERROR
        File baseUpdateDir = null;
        if (Build.VERSION.SDK_INT >= 8)
            baseUpdateDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        else
            baseUpdateDir = new File(Environment.getExternalStorageDirectory().getPath(), "download");

        File updateDir = new File(new File(baseUpdateDir, "updates"),"0");

        File updateFile = new File(updateDir, "update.apk");
        File statusFile = new File(updateDir, "update.status");

        if (!statusFile.exists() || !readUpdateStatus(statusFile).equals("pending"))
            return;

        if (!updateFile.exists())
            return;

        Log.i(LOG_FILE_NAME, "Update is available!");

        // Launch APK
        File updateFileToRun = new File(updateDir, getPackageName() + "-update.apk");
        try {
            if (updateFile.renameTo(updateFileToRun)) {
                String amCmd = "/system/bin/am start -a android.intent.action.VIEW " +
                               "-n com.android.packageinstaller/.PackageInstallerActivity -d file://" +
                               updateFileToRun.getPath();
                Log.i(LOG_FILE_NAME, amCmd);
                Runtime.getRuntime().exec(amCmd);
                statusCode = 0; // OK
            } else {
                Log.i(LOG_FILE_NAME, "Cannot rename the update file!");
                statusCode = 7; // WRITE_ERROR
            }
        } catch (Exception e) {
            Log.i(LOG_FILE_NAME, "error launching installer to update", e);
        }

        // Update the status file
        String status = statusCode == 0 ? "succeeded\n" : "failed: "+ statusCode + "\n";

        OutputStream outStream;
        try {
            byte[] buf = status.getBytes("UTF-8");
            outStream = new FileOutputStream(statusFile);
            outStream.write(buf, 0, buf.length);
            outStream.close();
        } catch (Exception e) {
            Log.i(LOG_FILE_NAME, "error writing status file", e);
        }

        if (statusCode == 0)
            System.exit(0);
    }

    private String readUpdateStatus(File statusFile) {
        String status = "";
        try {
            BufferedReader reader = new BufferedReader(new FileReader(statusFile));
            status = reader.readLine();
            reader.close();
        } catch (Exception e) {
            Log.i(LOG_FILE_NAME, "error reading update status", e);
        }
        return status;
    }

    private SynchronousQueue<String> mFilePickerResult = new SynchronousQueue();
    public String showFilePicker(String aMimeType) {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(aMimeType);
        GeckoApp.this.
            startActivityForResult(
                Intent.createChooser(intent, getString(R.string.choose_file)),
                FILE_PICKER_REQUEST);
        String filePickerResult = "";

        try {
            while (null == (filePickerResult = mFilePickerResult.poll(1, TimeUnit.MILLISECONDS))) {
                Log.i("GeckoApp", "processing events from showFilePicker ");
                GeckoAppShell.processNextNativeEvent();
            }
        } catch (InterruptedException e) {
            Log.i(LOG_FILE_NAME, "showing file picker ",  e);
        }

        return filePickerResult;
    }

    @Override
    public boolean onSearchRequested() {
        Intent searchIntent = new Intent(getBaseContext(), AwesomeBar.class);
        searchIntent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION | Intent.FLAG_ACTIVITY_NO_HISTORY);
        if (!sessionHistory.empty())
            searchIntent.putExtra(AwesomeBar.CURRENT_URL_KEY, sessionHistory.peek().uri);

        startActivityForResult(searchIntent, AWESOMEBAR_REQUEST);
        return true;
    }

    public boolean doReload() {
        Log.i("GeckoApp", "Reload requested");
        if (sessionHistory.empty())
            return false;
        String currUri = sessionHistory.peek().uri;
        GeckoAppShell.sendEventToGecko(new GeckoEvent(currUri));
        return true;
    }

    @Override
    public void onBackPressed() {
        if (sessionHistory.size() > 1) {
            sessionHistory.pop();
            String uri = sessionHistory.peek().uri;
            Log.i("GeckoApp", "going back to page: " + uri);
            loadUrl(uri);
        } else {
            finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode,
                                    Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        switch (requestCode) {
        case FILE_PICKER_REQUEST:
            String filePickerResult = "";
            if (data != null && resultCode == RESULT_OK) {
                try {
                    ContentResolver cr = getContentResolver();
                    Uri uri = data.getData();
                    Cursor cursor = GeckoApp.mAppContext.getContentResolver().query(
                        uri, 
                        new String[] { OpenableColumns.DISPLAY_NAME },
                        null, 
                        null, 
                        null);
                    String name = null;
                    if (cursor != null) {
                        try {
                            if (cursor.moveToNext()) {
                                name = cursor.getString(0);
                            }
                        } finally {
                            cursor.close();
                        }
                    }
                    String fileName = "tmp_";
                    String fileExt = null;
                    int period;
                    if (name == null || (period = name.lastIndexOf('.')) == -1) {
                        String mimeType = cr.getType(uri);
                        fileExt = "." + GeckoAppShell.getExtensionFromMimeType(mimeType);
                    } else {
                        fileExt = name.substring(period);
                        fileName = name.substring(0, period);
                    }
                    File file = File.createTempFile(fileName, fileExt, sGREDir);

                    FileOutputStream fos = new FileOutputStream(file);
                    InputStream is = cr.openInputStream(uri);
                    byte[] buf = new byte[4096];
                    int len = is.read(buf);
                    while (len != -1) {
                        fos.write(buf, 0, len);
                        len = is.read(buf);
                    }
                    fos.close();
                    filePickerResult =  file.getAbsolutePath();
                }catch (Exception e) {
                    Log.e(LOG_FILE_NAME, "showing file picker", e);
                }
            }
            try {
                mFilePickerResult.put(filePickerResult);
            } catch (InterruptedException e) {
                Log.i(LOG_FILE_NAME, "error returning file picker result", e);
            }
            break;
        case AWESOMEBAR_REQUEST:

            if (data != null) {
                String url = data.getStringExtra(AwesomeBar.URL_KEY);
                if (url != null && url.length() > 0) {
                    mProgressBar.setVisibility(View.VISIBLE);
                    mProgressBar.setIndeterminate(true);
                    loadUrl(url);
                }
            }


            break;
        }
    }

    public void loadUrl(String url) {
        mAwesomeBar.setText(url);
        GeckoAppShell.sendEventToGecko(new GeckoEvent(url));
    }

}
