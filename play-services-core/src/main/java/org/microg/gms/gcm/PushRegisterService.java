/*
 * Copyright (C) 2013-2017 microG Project Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.microg.gms.gcm;

import android.app.IntentService;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Message;
import android.os.Messenger;
import android.util.Log;

import org.microg.gms.checkin.CheckinService;
import org.microg.gms.checkin.LastCheckinInfo;
import org.microg.gms.common.PackageUtils;
import org.microg.gms.common.Utils;
import org.microg.gms.ui.AskPushPermission;

import java.io.IOException;

import static org.microg.gms.gcm.GcmConstants.ACTION_C2DM_REGISTER;
import static org.microg.gms.gcm.GcmConstants.ACTION_C2DM_REGISTRATION;
import static org.microg.gms.gcm.GcmConstants.ACTION_C2DM_UNREGISTER;
import static org.microg.gms.gcm.GcmConstants.ERROR_SERVICE_NOT_AVAILABLE;
import static org.microg.gms.gcm.GcmConstants.EXTRA_APP;
import static org.microg.gms.gcm.GcmConstants.EXTRA_DELETE;
import static org.microg.gms.gcm.GcmConstants.EXTRA_ERROR;
import static org.microg.gms.gcm.GcmConstants.EXTRA_KID;
import static org.microg.gms.gcm.GcmConstants.EXTRA_MESSENGER;
import static org.microg.gms.gcm.GcmConstants.EXTRA_PENDING_INTENT;
import static org.microg.gms.gcm.GcmConstants.EXTRA_REGISTRATION_ID;
import static org.microg.gms.gcm.GcmConstants.EXTRA_RETRY_AFTER;
import static org.microg.gms.gcm.GcmConstants.EXTRA_SENDER;
import static org.microg.gms.gcm.GcmConstants.EXTRA_UNREGISTERED;

public class PushRegisterService extends IntentService {
    private static final String TAG = "GmsGcmRegisterSvc";
    private static final String EXTRA_SKIP_TRY_CHECKIN = "skip_checkin";

    private GcmDatabase database;
    private static boolean requestPending = false;

    public PushRegisterService() {
        super(TAG);
        setIntentRedelivery(false);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        database = new GcmDatabase(this);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        database.close();
    }

    public static RegisterResponse register(Context context, String packageName, String pkgSignature, String sender, String info) {
        GcmDatabase database = new GcmDatabase(context);
        RegisterResponse response = register(context, packageName, pkgSignature, sender, info, false);
        String regId = response.token;
        if (regId != null) {
            database.noteAppRegistered(packageName, pkgSignature, regId);
        } else {
            database.noteAppRegistrationError(packageName, response.responseText);
        }
        database.close();
        return response;
    }

    public static RegisterResponse unregister(Context context, String packageName, String pkgSignature, String sender, String info) {
        GcmDatabase database = new GcmDatabase(context);
        RegisterResponse response = register(context, packageName, pkgSignature, sender, info, true);
        if (!packageName.equals(response.deleted)) {
            database.noteAppRegistrationError(packageName, response.responseText);
        } else {
            database.noteAppUnregistered(packageName, pkgSignature);
        }
        database.close();
        return response;
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        Log.d(TAG, "onHandleIntent: " + intent);

        String requestId = null;
        if (intent.hasExtra(EXTRA_KID) && intent.getStringExtra(EXTRA_KID).startsWith("|")) {
            String[] kid = intent.getStringExtra(EXTRA_KID).split("\\|");
            if (kid.length >= 3 && "ID".equals(kid[1])) {
                requestId = kid[2];
            }
        }

        if (GcmPrefs.get(this).isEnabled()) {
            if (LastCheckinInfo.read(this).lastCheckin > 0) {
                try {
                    if (ACTION_C2DM_UNREGISTER.equals(intent.getAction()) ||
                            (ACTION_C2DM_REGISTER.equals(intent.getAction()) && "1".equals(intent.getStringExtra(EXTRA_DELETE)))) {
                        unregister(intent, requestId);
                    } else if (ACTION_C2DM_REGISTER.equals(intent.getAction())) {
                        register(intent, requestId);
                    }
                } catch (Exception e) {
                    Log.w(TAG, e);
                }
            } else if (!intent.getBooleanExtra(EXTRA_SKIP_TRY_CHECKIN, false)) {
                Log.d(TAG, "No checkin yet, trying to checkin");
                intent.putExtra(EXTRA_SKIP_TRY_CHECKIN, true);
                Intent subIntent = new Intent(this, CheckinService.class);
                subIntent.putExtra(CheckinService.EXTRA_FORCE_CHECKIN, true);
                subIntent.putExtra(CheckinService.EXTRA_CALLBACK_INTENT, intent);
                startService(subIntent);
            }
        } else {
            // GCM is disabled, deny registration
            replyNotAvailable(this, intent, null, requestId);
        }
    }

    private void register(final Intent intent, String requestId) {
        PendingIntent pendingIntent = intent.getParcelableExtra(EXTRA_APP);
        final String packageName = PackageUtils.packageFromPendingIntent(pendingIntent);
        Log.d(TAG, "register[req]: " + intent.toString() + " extras=" + intent.getExtras());

        GcmDatabase.App app = database.getApp(packageName);
        if (app == null && GcmPrefs.get(this).isConfirmNewApps()) {
            try {
                PackageManager pm = getPackageManager();
                ApplicationInfo info = pm.getApplicationInfo(packageName, 0);
                Intent i = new Intent(this, AskPushPermission.class);
                i.putExtra(EXTRA_PENDING_INTENT, intent);
                i.putExtra(EXTRA_APP, packageName);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (PackageManager.NameNotFoundException e) {
                replyNotAvailable(this, intent, packageName, requestId);
            }
        } else if (app != null && !app.allowRegister) {
            replyNotAvailable(this, intent, packageName, requestId);
        } else {
            registerAndReply(this, intent, packageName, requestId);
        }
    }

    public static void replyNotAvailable(Context context, Intent intent, String packageName, String requestId) {
        if (packageName == null) {
            PendingIntent pendingIntent = intent.getParcelableExtra(EXTRA_APP);
            packageName = PackageUtils.packageFromPendingIntent(pendingIntent);
        }
        if (packageName == null) {
            // skip reply
            return;
        }
        Intent outIntent = new Intent(ACTION_C2DM_REGISTRATION);
        outIntent.putExtra(EXTRA_ERROR, attachRequestId(ERROR_SERVICE_NOT_AVAILABLE, requestId));
        Log.d(TAG, "registration not allowed");
        sendReply(context, intent, packageName, outIntent);
    }

    public static void registerAndReply(Context context, Intent intent, String packageName, String requestId) {
        Intent outIntent = new Intent(ACTION_C2DM_REGISTRATION);
        String sender = intent.getStringExtra(EXTRA_SENDER);
        String appSignature = PackageUtils.firstSignatureDigest(context, packageName);
        String regId = register(context, packageName, appSignature, sender, null).token;
        if (regId != null) {
            outIntent.putExtra(EXTRA_REGISTRATION_ID, attachRequestId(regId, requestId));
        } else {
            outIntent.putExtra(EXTRA_ERROR, attachRequestId(ERROR_SERVICE_NOT_AVAILABLE, requestId));
        }

        Log.d(TAG, "register[res]: " + outIntent + " extras=" + outIntent.getExtras());
        sendReply(context, intent, packageName, outIntent);
    }

    private static void sendReply(Context context, Intent intent, String packageName, Intent outIntent) {

        try {
            if (intent.hasExtra(EXTRA_MESSENGER)) {
                Messenger messenger = intent.getParcelableExtra(EXTRA_MESSENGER);
                Message message = Message.obtain();
                message.obj = outIntent;
                messenger.send(message);
                return;
            }
        } catch (Exception e) {
            Log.w(TAG, e);
        }

        outIntent.setPackage(packageName);
        context.sendOrderedBroadcast(outIntent, null);
    }

    public static RegisterResponse register(Context context, String app, String appSignature, String sender, String info, boolean delete) {
        try {
            RegisterResponse response = new RegisterRequest()
                    .build(Utils.getBuild(context))
                    .sender(sender)
                    .info(info)
                    .checkin(LastCheckinInfo.read(context))
                    .app(app, appSignature, PackageUtils.versionCode(context, app))
                    .delete(delete)
                    .getResponse();
            Log.d(TAG, "received response: " + response);
            return response;
        } catch (IOException e) {
            Log.w(TAG, e);
        }

        return new RegisterResponse();
    }

    private void unregister(Intent intent, String requestId) {
        PendingIntent pendingIntent = intent.getParcelableExtra(EXTRA_APP);
        String packageName = PackageUtils.packageFromPendingIntent(pendingIntent);
        Log.d(TAG, "unregister[req]: " + intent.toString() + " extras=" + intent.getExtras());

        Intent outIntent = new Intent(ACTION_C2DM_REGISTRATION);
        String appSignature = PackageUtils.firstSignatureDigest(this, packageName);

        if (database.getRegistration(packageName, appSignature) == null) {
            outIntent.putExtra(EXTRA_UNREGISTERED, attachRequestId(packageName, requestId));
        } else {
            RegisterResponse response = unregister(this, packageName, appSignature, null, null);
            if (!packageName.equals(response.deleted)) {
                outIntent.putExtra(EXTRA_ERROR, attachRequestId(ERROR_SERVICE_NOT_AVAILABLE, requestId));

                if (response.retryAfter != null && !response.retryAfter.contains(":")) {
                    outIntent.putExtra(EXTRA_RETRY_AFTER, Long.parseLong(response.retryAfter));
                }
            } else {
                outIntent.putExtra(EXTRA_UNREGISTERED, attachRequestId(packageName, requestId));
            }
        }

        Log.d(TAG, "unregister[res]: " + outIntent.toString() + " extras=" + outIntent.getExtras());
        sendReply(this, intent, packageName, outIntent);
    }

    private static String attachRequestId(String msg, String requestId) {
        if (requestId == null) return msg;
        return "|ID|" + requestId + "|" + msg;
    }
}
