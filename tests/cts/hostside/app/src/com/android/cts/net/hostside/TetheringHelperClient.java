/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.cts.net.hostside;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.TetheringInterface;
import android.os.ConditionVariable;
import android.os.IBinder;
import android.os.RemoteException;

public class TetheringHelperClient {
    private static final int TIMEOUT_MS = 5000;
    private static final String PACKAGE = TetheringHelperClient.class.getPackage().getName();
    private static final String APP2_PACKAGE = PACKAGE + ".app2";
    private static final String SERVICE_NAME = APP2_PACKAGE + ".TetheringHelperService";

    private Context mContext;
    private ServiceConnection mServiceConnection;
    private ITetheringHelper mService;

    public TetheringHelperClient(Context context) {
        mContext = context;
    }

    /**
     * Binds to TetheringHelperService.
     */
    public void bind() {
        if (mService != null) {
            throw new IllegalStateException("Already bound");
        }

        final ConditionVariable cv = new ConditionVariable();
        mServiceConnection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                mService = ITetheringHelper.Stub.asInterface(service);
                cv.open();
            }
            @Override
            public void onServiceDisconnected(ComponentName name) {
                mService = null;
            }
        };

        final Intent intent = new Intent();
        intent.setComponent(new ComponentName(APP2_PACKAGE, SERVICE_NAME));
        mContext.bindService(intent, mServiceConnection, Context.BIND_AUTO_CREATE);
        cv.block(TIMEOUT_MS);
        if (mService == null) {
            throw new IllegalStateException(
                    "Could not bind to TetheringHelperService after " + TIMEOUT_MS + "ms");
        }
    }

    /**
     * Unbinds from TetheringHelperService.
     */
    public void unbind() {
        if (mService != null) {
            mContext.unbindService(mServiceConnection);
        }
    }

    /**
     * Returns the tethered Wifi interface as seen from TetheringHelperService.
     */
    public TetheringInterface getTetheredWifiInterface() throws RemoteException {
        return mService.getTetheredWifiInterface();
    }
}
