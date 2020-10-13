/*
 * Copyright (C) 2013 The Android Open Source Project
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
package com.android.server.location;

import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.hardware.location.GeofenceHardwareService;
import android.hardware.location.IGeofenceHardware;
import android.location.IGeofenceProvider;
import android.location.IGpsGeofenceHardware;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;

import com.android.server.FgThread;
import com.android.server.ServiceWatcher;

import java.util.Objects;

/**
 * @hide
 */
public final class GeofenceProxy {

    private static final String TAG = "GeofenceProxy";
    private static final String SERVICE_ACTION = "com.android.location.service.GeofenceProvider";

    @Nullable
    public static GeofenceProxy createAndBind(Context context, IGpsGeofenceHardware gpsGeofence) {
        GeofenceProxy proxy = new GeofenceProxy(context, gpsGeofence);
        if (proxy.register(context)) {
            return proxy;
        } else {
            return null;
        }
    }

    private final IGpsGeofenceHardware mGpsGeofenceHardware;
    private final ServiceWatcher mServiceWatcher;

    private volatile IGeofenceHardware mGeofenceHardware;

    private GeofenceProxy(Context context, IGpsGeofenceHardware gpsGeofence) {
        mGpsGeofenceHardware = Objects.requireNonNull(gpsGeofence);
        mServiceWatcher = new ServiceWatcher(context, FgThread.getHandler(), SERVICE_ACTION,
                this::updateGeofenceHardware, null,
                com.android.internal.R.bool.config_enableGeofenceOverlay,
                com.android.internal.R.string.config_geofenceProviderPackageName);

        mGeofenceHardware = null;
    }

    private void updateGeofenceHardware(IBinder binder) throws RemoteException {
        IGeofenceProvider.Stub.asInterface(binder).setGeofenceHardware(mGeofenceHardware);
    }

    private boolean register(Context context) {
        if (mServiceWatcher.register()) {
            context.bindServiceAsUser(
                    new Intent(context, GeofenceHardwareService.class),
                    new GeofenceProxyServiceConnection(),
                    Context.BIND_AUTO_CREATE,
                    UserHandle.SYSTEM);
            return true;
        }

        return false;
    }

    private class GeofenceProxyServiceConnection implements ServiceConnection {

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            IGeofenceHardware geofenceHardware = IGeofenceHardware.Stub.asInterface(service);

            try {
                geofenceHardware.setGpsGeofenceHardware(mGpsGeofenceHardware);
                mGeofenceHardware = geofenceHardware;
                mServiceWatcher.runOnBinder(GeofenceProxy.this::updateGeofenceHardware);
            } catch (RemoteException e) {
                Log.w(TAG, "unable to initialize geofence hardware", e);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mGeofenceHardware = null;
            mServiceWatcher.runOnBinder(GeofenceProxy.this::updateGeofenceHardware);
        }
    }
}
