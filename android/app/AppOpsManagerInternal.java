/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.app;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.util.SparseArray;
import android.util.SparseIntArray;

import com.android.internal.app.IAppOpsCallback;
import com.android.internal.util.function.HeptFunction;
import com.android.internal.util.function.QuadFunction;

/**
 * App ops service local interface.
 *
 * @hide Only for use within the system server.
 */
public abstract class AppOpsManagerInternal {
    /** Interface to override app ops checks via composition */
    public interface CheckOpsDelegate {
        /**
         * Allows overriding check operation behavior.
         *
         * @param code The op code to check.
         * @param uid The UID for which to check.
         * @param packageName The package for which to check.
         * @param superImpl The super implementation.
         * @param raw Whether to check the raw op i.e. not interpret the mode based on UID state.
         * @return The app op check result.
         */
        int checkOperation(int code, int uid, String packageName, boolean raw,
                QuadFunction<Integer, Integer, String, Boolean, Integer> superImpl);

        /**
         * Allows overriding check audio operation behavior.
         *
         * @param code The op code to check.
         * @param usage The audio op usage.
         * @param uid The UID for which to check.
         * @param packageName The package for which to check.
         * @param superImpl The super implementation.
         * @return The app op check result.
         */
        int checkAudioOperation(int code, int usage, int uid, String packageName,
                QuadFunction<Integer, Integer, Integer, String, Integer> superImpl);

        /**
         * Allows overriding note operation behavior.
         *
         * @param code The op code to note.
         * @param uid The UID for which to note.
         * @param packageName The package for which to note. {@code null} for system package.
         * @param featureId Id of the feature in the package
         * @param shouldCollectAsyncNotedOp If an {@link AsyncNotedAppOp} should be collected
         * @param message The message in the async noted op
         * @param superImpl The super implementation.
         * @return The app op note result.
         */
        int noteOperation(int code, int uid, @Nullable String packageName,
                @Nullable String featureId, boolean shouldCollectAsyncNotedOp,
                @Nullable String message, boolean shouldCollectMessage,
                @NonNull HeptFunction<Integer, Integer, String, String, Boolean, String, Boolean,
                        Integer> superImpl);
    }

    /**
     * Set the currently configured device and profile owners.  Specifies the package uid (value)
     * that has been configured for each user (key) that has one.  These will be allowed privileged
     * access to app ops for their user.
     */
    public abstract void setDeviceAndProfileOwners(SparseIntArray owners);

    /**
     * Update if the list of AppWidget becomes visible/invisible.
     * @param uidPackageNames uid to packageName map.
     * @param visible true for visible, false for invisible.
     */
    public abstract void updateAppWidgetVisibility(SparseArray<String> uidPackageNames,
            boolean visible);

    /**
     * Like {@link AppOpsManager#setUidMode}, but allows ignoring our own callback and not updating
     * the REVOKED_COMPAT flag.
     */
    public abstract void setUidModeFromPermissionPolicy(int code, int uid, int mode,
            @Nullable IAppOpsCallback callback);

    /**
     * Like {@link AppOpsManager#setMode}, but allows ignoring our own callback and not updating the
     * REVOKED_COMPAT flag.
     */
    public abstract void setModeFromPermissionPolicy(int code, int uid, @NonNull String packageName,
            int mode, @Nullable IAppOpsCallback callback);
}
