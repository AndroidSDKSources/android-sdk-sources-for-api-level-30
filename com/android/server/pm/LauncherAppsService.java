/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.server.pm;

import static android.content.Intent.FLAG_ACTIVITY_MULTIPLE_TASK;
import static android.content.Intent.FLAG_ACTIVITY_NEW_DOCUMENT;
import static android.content.pm.LauncherApps.FLAG_CACHE_BUBBLE_SHORTCUTS;
import static android.content.pm.LauncherApps.FLAG_CACHE_NOTIFICATION_SHORTCUTS;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.ActivityOptions;
import android.app.AppGlobals;
import android.app.IApplicationThread;
import android.app.PendingIntent;
import android.app.admin.DevicePolicyManager;
import android.app.usage.UsageStatsManagerInternal;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.LocusId;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ILauncherApps;
import android.content.pm.IOnAppsChangedListener;
import android.content.pm.IPackageInstallerCallback;
import android.content.pm.IPackageManager;
import android.content.pm.IShortcutChangeCallback;
import android.content.pm.LauncherApps;
import android.content.pm.LauncherApps.ShortcutQuery;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller.SessionInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ParceledListSlice;
import android.content.pm.ResolveInfo;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutQueryWrapper;
import android.content.pm.ShortcutServiceInternal;
import android.content.pm.ShortcutServiceInternal.ShortcutChangeListener;
import android.content.pm.UserInfo;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IInterface;
import android.os.ParcelFileDescriptor;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.UserManagerInternal;
import android.provider.Settings;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.content.PackageMonitor;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.CollectionUtils;
import com.android.internal.util.Preconditions;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.pm.parsing.pkg.AndroidPackage;
import com.android.server.wm.ActivityTaskManagerInternal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Service that manages requests and callbacks for launchers that support
 * managed profiles.
 */
public class LauncherAppsService extends SystemService {

    private final LauncherAppsImpl mLauncherAppsImpl;

    public LauncherAppsService(Context context) {
        super(context);
        mLauncherAppsImpl = new LauncherAppsImpl(context);
    }

    @Override
    public void onStart() {
        publishBinderService(Context.LAUNCHER_APPS_SERVICE, mLauncherAppsImpl);
    }

    static class BroadcastCookie {
        public final UserHandle user;
        public final String packageName;
        public final int callingUid;
        public final int callingPid;

        BroadcastCookie(UserHandle userHandle, String packageName, int callingPid, int callingUid) {
            this.user = userHandle;
            this.packageName = packageName;
            this.callingUid = callingUid;
            this.callingPid = callingPid;
        }
    }

    @VisibleForTesting
    static class LauncherAppsImpl extends ILauncherApps.Stub {
        private static final boolean DEBUG = false;
        private static final String TAG = "LauncherAppsService";

        private final Context mContext;
        private final UserManager mUm;
        private final IPackageManager mIPM;
        private final UserManagerInternal mUserManagerInternal;
        private final UsageStatsManagerInternal mUsageStatsManagerInternal;
        private final ActivityManagerInternal mActivityManagerInternal;
        private final ActivityTaskManagerInternal mActivityTaskManagerInternal;
        private final ShortcutServiceInternal mShortcutServiceInternal;
        private final PackageCallbackList<IOnAppsChangedListener> mListeners
                = new PackageCallbackList<IOnAppsChangedListener>();
        private final DevicePolicyManager mDpm;

        private final MyPackageMonitor mPackageMonitor = new MyPackageMonitor();

        private final ShortcutChangeHandler mShortcutChangeHandler;

        private final Handler mCallbackHandler;

        private PackageInstallerService mPackageInstallerService;

        public LauncherAppsImpl(Context context) {
            mContext = context;
            mIPM = AppGlobals.getPackageManager();
            mUm = (UserManager) mContext.getSystemService(Context.USER_SERVICE);
            mUserManagerInternal = Objects.requireNonNull(
                    LocalServices.getService(UserManagerInternal.class));
            mUsageStatsManagerInternal = Objects.requireNonNull(
                    LocalServices.getService(UsageStatsManagerInternal.class));
            mActivityManagerInternal = Objects.requireNonNull(
                    LocalServices.getService(ActivityManagerInternal.class));
            mActivityTaskManagerInternal = Objects.requireNonNull(
                    LocalServices.getService(ActivityTaskManagerInternal.class));
            mShortcutServiceInternal = Objects.requireNonNull(
                    LocalServices.getService(ShortcutServiceInternal.class));
            mShortcutServiceInternal.addListener(mPackageMonitor);
            mShortcutChangeHandler = new ShortcutChangeHandler(mUserManagerInternal);
            mShortcutServiceInternal.addShortcutChangeCallback(mShortcutChangeHandler);
            mCallbackHandler = BackgroundThread.getHandler();
            mDpm = (DevicePolicyManager) mContext.getSystemService(Context.DEVICE_POLICY_SERVICE);
        }

        @VisibleForTesting
        int injectBinderCallingUid() {
            return getCallingUid();
        }

        @VisibleForTesting
        int injectBinderCallingPid() {
            return getCallingPid();
        }

        final int injectCallingUserId() {
            return UserHandle.getUserId(injectBinderCallingUid());
        }

        @VisibleForTesting
        long injectClearCallingIdentity() {
            return Binder.clearCallingIdentity();
        }

        // Injection point.
        @VisibleForTesting
        void injectRestoreCallingIdentity(long token) {
            Binder.restoreCallingIdentity(token);
        }

        private int getCallingUserId() {
            return UserHandle.getUserId(injectBinderCallingUid());
        }

        /*
         * @see android.content.pm.ILauncherApps#addOnAppsChangedListener
         */
        @Override
        public void addOnAppsChangedListener(String callingPackage, IOnAppsChangedListener listener)
                throws RemoteException {
            verifyCallingPackage(callingPackage);
            synchronized (mListeners) {
                if (DEBUG) {
                    Log.d(TAG, "Adding listener from " + Binder.getCallingUserHandle());
                }
                if (mListeners.getRegisteredCallbackCount() == 0) {
                    if (DEBUG) {
                        Log.d(TAG, "Starting package monitoring");
                    }
                    startWatchingPackageBroadcasts();
                }
                mListeners.unregister(listener);
                mListeners.register(listener, new BroadcastCookie(UserHandle.of(getCallingUserId()),
                        callingPackage, injectBinderCallingPid(), injectBinderCallingUid()));
            }
        }

        /*
         * @see android.content.pm.ILauncherApps#removeOnAppsChangedListener
         */
        @Override
        public void removeOnAppsChangedListener(IOnAppsChangedListener listener)
                throws RemoteException {
            synchronized (mListeners) {
                if (DEBUG) {
                    Log.d(TAG, "Removing listener from " + Binder.getCallingUserHandle());
                }
                mListeners.unregister(listener);
                if (mListeners.getRegisteredCallbackCount() == 0) {
                    stopWatchingPackageBroadcasts();
                }
            }
        }

        /**
         * @see android.content.pm.ILauncherApps#registerPackageInstallerCallback
         */
        @Override
        public void registerPackageInstallerCallback(String callingPackage,
                IPackageInstallerCallback callback) {
            verifyCallingPackage(callingPackage);
            UserHandle callingIdUserHandle = new UserHandle(getCallingUserId());
            getPackageInstallerService().registerCallback(callback, eventUserId ->
                            isEnabledProfileOf(callingIdUserHandle,
                                    new UserHandle(eventUserId), "shouldReceiveEvent"));
        }

        @Override
        public ParceledListSlice<SessionInfo> getAllSessions(String callingPackage) {
            verifyCallingPackage(callingPackage);
            List<SessionInfo> sessionInfos = new ArrayList<>();
            int[] userIds = mUm.getEnabledProfileIds(getCallingUserId());
            long token = Binder.clearCallingIdentity();
            try {
                for (int userId : userIds) {
                    sessionInfos.addAll(getPackageInstallerService().getAllSessions(userId)
                            .getList());
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
            return new ParceledListSlice<>(sessionInfos);
        }

        private PackageInstallerService getPackageInstallerService() {
            if (mPackageInstallerService == null) {
                mPackageInstallerService = ((PackageInstallerService) ((PackageManagerService)
                        ServiceManager.getService("package")).getPackageInstaller());
            }
            return mPackageInstallerService;
        }

        /**
         * Register a receiver to watch for package broadcasts
         */
        private void startWatchingPackageBroadcasts() {
            mPackageMonitor.register(mContext, UserHandle.ALL, true, mCallbackHandler);
        }

        /**
         * Unregister package broadcast receiver
         */
        private void stopWatchingPackageBroadcasts() {
            if (DEBUG) {
                Log.d(TAG, "Stopped watching for packages");
            }
            mPackageMonitor.unregister();
        }

        void checkCallbackCount() {
            synchronized (mListeners) {
                if (DEBUG) {
                    Log.d(TAG, "Callback count = " + mListeners.getRegisteredCallbackCount());
                }
                if (mListeners.getRegisteredCallbackCount() == 0) {
                    stopWatchingPackageBroadcasts();
                }
            }
        }

        /**
         * Checks if the calling user is in the same group as {@code targetUser}, and allowed
         * to access it.
         *
         * @return TRUE if the calling user can access {@code targetUserId}.  FALSE if not *but
         * they're still in the same profile group*.
         *
         * @throws SecurityException if the calling user and {@code targetUser} are not in the same
         * group.
         */
        private boolean canAccessProfile(int targetUserId, String message) {
            final int callingUserId = injectCallingUserId();

            if (targetUserId == callingUserId) return true;
            if (injectHasInteractAcrossUsersFullPermission(injectBinderCallingPid(),
                    injectBinderCallingUid())) {
                return true;
            }

            long ident = injectClearCallingIdentity();
            try {
                final UserInfo callingUserInfo = mUm.getUserInfo(callingUserId);
                if (callingUserInfo != null && callingUserInfo.isProfile()) {
                    Slog.w(TAG, message + " for another profile "
                            + targetUserId + " from " + callingUserId + " not allowed");
                    return false;
                }
            } finally {
                injectRestoreCallingIdentity(ident);
            }

            return mUserManagerInternal.isProfileAccessible(injectCallingUserId(), targetUserId,
                    message, true);
        }

        @VisibleForTesting // We override it in unit tests
        void verifyCallingPackage(String callingPackage) {
            int packageUid = -1;
            try {
                packageUid = AppGlobals.getPackageManager().getPackageUid(callingPackage,
                        PackageManager.MATCH_DIRECT_BOOT_AWARE
                                | PackageManager.MATCH_DIRECT_BOOT_UNAWARE
                                | PackageManager.MATCH_UNINSTALLED_PACKAGES,
                        UserHandle.getUserId(getCallingUid()));
            } catch (RemoteException ignore) {
            }
            if (packageUid < 0) {
                Log.e(TAG, "Package not found: " + callingPackage);
            }
            if (packageUid != injectBinderCallingUid()) {
                throw new SecurityException("Calling package name mismatch");
            }
        }

        private ResolveInfo getHiddenAppActivityInfo(String packageName, int callingUid,
                UserHandle user) {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(packageName,
                    PackageManager.APP_DETAILS_ACTIVITY_CLASS_NAME));
            final PackageManagerInternal pmInt =
                    LocalServices.getService(PackageManagerInternal.class);
            List<ResolveInfo> apps = pmInt.queryIntentActivities(intent,
                    intent.resolveTypeIfNeeded(mContext.getContentResolver()),
                    PackageManager.MATCH_DIRECT_BOOT_AWARE
                            | PackageManager.MATCH_DIRECT_BOOT_UNAWARE,
                    callingUid, user.getIdentifier());
            if (apps.size() > 0) {
                return apps.get(0);
            }
            return null;
        }

        @Override
        public boolean shouldHideFromSuggestions(String packageName, UserHandle user) {
            if (!canAccessProfile(user.getIdentifier(), "cannot get shouldHideFromSuggestions")) {
                return false;
            }
            final PackageManagerInternal pmi = LocalServices.getService(
                    PackageManagerInternal.class);
            int flags = pmi.getDistractingPackageRestrictions(packageName, user.getIdentifier());
            return (flags & PackageManager.RESTRICTION_HIDE_FROM_SUGGESTIONS) != 0;
        }

        @Override
        public ParceledListSlice<ResolveInfo> getLauncherActivities(String callingPackage,
                String packageName, UserHandle user) throws RemoteException {
            ParceledListSlice<ResolveInfo> launcherActivities = queryActivitiesForUser(
                    callingPackage,
                    new Intent(Intent.ACTION_MAIN)
                            .addCategory(Intent.CATEGORY_LAUNCHER)
                            .setPackage(packageName),
                    user);
            if (Settings.Global.getInt(mContext.getContentResolver(),
                    Settings.Global.SHOW_HIDDEN_LAUNCHER_ICON_APPS_ENABLED, 1) == 0) {
                return launcherActivities;
            }
            if (launcherActivities == null) {
                // Cannot access profile, so we don't even return any hidden apps.
                return null;
            }

            final int callingUid = injectBinderCallingUid();
            final long ident = injectClearCallingIdentity();
            try {
                if (mUm.getUserInfo(user.getIdentifier()).isManagedProfile()) {
                    // Managed profile should not show hidden apps
                    return launcherActivities;
                }
                if (mDpm.getDeviceOwnerComponentOnAnyUser() != null) {
                    // Device owner devices should not show hidden apps
                    return launcherActivities;
                }

                final ArrayList<ResolveInfo> result = new ArrayList<>(launcherActivities.getList());
                final PackageManagerInternal pmInt =
                        LocalServices.getService(PackageManagerInternal.class);
                if (packageName != null) {
                    // If this hidden app should not be shown, return the original list.
                    // Otherwise, inject hidden activity that forwards user to app details page.
                    if (result.size() > 0) {
                        return launcherActivities;
                    }
                    ApplicationInfo appInfo = pmInt.getApplicationInfo(packageName, /*flags*/ 0,
                            callingUid, user.getIdentifier());
                    if (shouldShowSyntheticActivity(user, appInfo)) {
                        ResolveInfo info = getHiddenAppActivityInfo(packageName, callingUid, user);
                        if (info != null) {
                            result.add(info);
                        }
                    }
                    return new ParceledListSlice<>(result);
                }
                final HashSet<String> visiblePackages = new HashSet<>();
                for (ResolveInfo info : result) {
                    visiblePackages.add(info.activityInfo.packageName);
                }
                List<ApplicationInfo> installedPackages = pmInt.getInstalledApplications(0,
                        user.getIdentifier(), callingUid);
                for (ApplicationInfo applicationInfo : installedPackages) {
                    if (!visiblePackages.contains(applicationInfo.packageName)) {
                        if (!shouldShowSyntheticActivity(user, applicationInfo)) {
                            continue;
                        }
                        ResolveInfo info = getHiddenAppActivityInfo(applicationInfo.packageName,
                                callingUid, user);
                        if (info != null) {
                            result.add(info);
                        }
                    }
                }
                return new ParceledListSlice<>(result);
            } finally {
                injectRestoreCallingIdentity(ident);
            }
        }

        private boolean shouldShowSyntheticActivity(UserHandle user, ApplicationInfo appInfo) {
            if (appInfo == null || appInfo.isSystemApp() || appInfo.isUpdatedSystemApp()) {
                return false;
            }
            if (isManagedProfileAdmin(user, appInfo.packageName)) {
                return false;
            }
            final PackageManagerInternal pmInt =
                    LocalServices.getService(PackageManagerInternal.class);
            final AndroidPackage pkg = pmInt.getPackage(appInfo.packageName);
            if (pkg == null) {
                // Should not happen, but we shouldn't be failing if it does
                return false;
            }
            // If app does not have any default enabled launcher activity or any permissions,
            // the app can legitimately have no icon so we do not show the synthetic activity.
            return requestsPermissions(pkg) && hasDefaultEnableLauncherActivity(
                    appInfo.packageName);
        }

        private boolean requestsPermissions(@NonNull AndroidPackage pkg) {
            return !ArrayUtils.isEmpty(pkg.getRequestedPermissions());
        }

        private boolean hasDefaultEnableLauncherActivity(@NonNull String packageName) {
            final PackageManagerInternal pmInt =
                    LocalServices.getService(PackageManagerInternal.class);
            final Intent matchIntent = new Intent(Intent.ACTION_MAIN);
            matchIntent.addCategory(Intent.CATEGORY_LAUNCHER);
            matchIntent.setPackage(packageName);
            final List<ResolveInfo> infoList = pmInt.queryIntentActivities(matchIntent,
                    matchIntent.resolveTypeIfNeeded(mContext.getContentResolver()),
                    PackageManager.MATCH_DISABLED_COMPONENTS, Binder.getCallingUid(),
                    getCallingUserId());
            final int size = infoList.size();
            for (int i = 0; i < size; i++) {
                if (infoList.get(i).activityInfo.enabled) {
                    return true;
                }
            }
            return false;
        }

        private boolean isManagedProfileAdmin(UserHandle user, String packageName) {
            final List<UserInfo> userInfoList = mUm.getProfiles(user.getIdentifier());
            for (int i = 0; i < userInfoList.size(); i++) {
                UserInfo userInfo = userInfoList.get(i);
                if (!userInfo.isManagedProfile()) {
                    continue;
                }
                ComponentName componentName = mDpm.getProfileOwnerAsUser(userInfo.getUserHandle());
                if (componentName == null) {
                    continue;
                }
                if (componentName.getPackageName().equals(packageName)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public ActivityInfo resolveActivity(
                String callingPackage, ComponentName component, UserHandle user)
                throws RemoteException {
            if (!canAccessProfile(user.getIdentifier(), "Cannot resolve activity")) {
                return null;
            }

            final int callingUid = injectBinderCallingUid();
            long ident = Binder.clearCallingIdentity();
            try {
                final PackageManagerInternal pmInt =
                        LocalServices.getService(PackageManagerInternal.class);
                return pmInt.getActivityInfo(component,
                        PackageManager.MATCH_DIRECT_BOOT_AWARE
                                | PackageManager.MATCH_DIRECT_BOOT_UNAWARE,
                        callingUid, user.getIdentifier());
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override
        public ParceledListSlice getShortcutConfigActivities(
                String callingPackage, String packageName, UserHandle user)
                throws RemoteException {
            return queryActivitiesForUser(callingPackage,
                    new Intent(Intent.ACTION_CREATE_SHORTCUT).setPackage(packageName), user);
        }

        private ParceledListSlice<ResolveInfo> queryActivitiesForUser(String callingPackage,
                Intent intent, UserHandle user) {
            if (!canAccessProfile(user.getIdentifier(), "Cannot retrieve activities")) {
                return null;
            }

            final int callingUid = injectBinderCallingUid();
            long ident = injectClearCallingIdentity();
            try {
                final PackageManagerInternal pmInt =
                        LocalServices.getService(PackageManagerInternal.class);
                List<ResolveInfo> apps = pmInt.queryIntentActivities(intent,
                        intent.resolveTypeIfNeeded(mContext.getContentResolver()),
                        PackageManager.MATCH_DIRECT_BOOT_AWARE
                                | PackageManager.MATCH_DIRECT_BOOT_UNAWARE,
                        callingUid, user.getIdentifier());
                return new ParceledListSlice<>(apps);
            } finally {
                injectRestoreCallingIdentity(ident);
            }
        }

        @Override
        public IntentSender getShortcutConfigActivityIntent(String callingPackage,
                ComponentName component, UserHandle user) throws RemoteException {
            ensureShortcutPermission(callingPackage);
            if (!canAccessProfile(user.getIdentifier(), "Cannot check package")) {
                return null;
            }
            Objects.requireNonNull(component);

            // All right, create the sender.
            Intent intent = new Intent(Intent.ACTION_CREATE_SHORTCUT).setComponent(component);
            final long identity = Binder.clearCallingIdentity();
            try {
                final PendingIntent pi = PendingIntent.getActivityAsUser(
                        mContext, 0, intent, PendingIntent.FLAG_ONE_SHOT
                                | PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_CANCEL_CURRENT,
                        null, user);
                return pi == null ? null : pi.getIntentSender();
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public boolean isPackageEnabled(String callingPackage, String packageName, UserHandle user)
                throws RemoteException {
            if (!canAccessProfile(user.getIdentifier(), "Cannot check package")) {
                return false;
            }

            final int callingUid = injectBinderCallingUid();
            long ident = Binder.clearCallingIdentity();
            try {
                final PackageManagerInternal pmInt =
                        LocalServices.getService(PackageManagerInternal.class);
                PackageInfo info = pmInt.getPackageInfo(packageName,
                        PackageManager.MATCH_DIRECT_BOOT_AWARE
                                | PackageManager.MATCH_DIRECT_BOOT_UNAWARE,
                        callingUid, user.getIdentifier());
                return info != null && info.applicationInfo.enabled;
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override
        public Bundle getSuspendedPackageLauncherExtras(String packageName,
                UserHandle user) {
            if (!canAccessProfile(user.getIdentifier(), "Cannot get launcher extras")) {
                return null;
            }
            final PackageManagerInternal pmi =
                    LocalServices.getService(PackageManagerInternal.class);
            return pmi.getSuspendedPackageLauncherExtras(packageName, user.getIdentifier());
        }

        @Override
        public ApplicationInfo getApplicationInfo(
                String callingPackage, String packageName, int flags, UserHandle user)
                throws RemoteException {
            if (!canAccessProfile(user.getIdentifier(), "Cannot check package")) {
                return null;
            }

            final int callingUid = injectBinderCallingUid();
            long ident = Binder.clearCallingIdentity();
            try {
                final PackageManagerInternal pmInt =
                        LocalServices.getService(PackageManagerInternal.class);
                ApplicationInfo info = pmInt.getApplicationInfo(packageName, flags,
                        callingUid, user.getIdentifier());
                return info;
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override
        public LauncherApps.AppUsageLimit getAppUsageLimit(String callingPackage,
                String packageName, UserHandle user) {
            verifyCallingPackage(callingPackage);
            if (!canAccessProfile(user.getIdentifier(), "Cannot access usage limit")) {
                return null;
            }
            if (!mActivityTaskManagerInternal.isCallerRecents(Binder.getCallingUid())) {
                throw new SecurityException("Caller is not the recents app");
            }

            final UsageStatsManagerInternal.AppUsageLimitData data =
                    mUsageStatsManagerInternal.getAppUsageLimit(packageName, user);
            if (data == null) {
                return null;
            }
            return new LauncherApps.AppUsageLimit(
                    data.getTotalUsageLimit(), data.getUsageRemaining());
        }

        private void ensureShortcutPermission(@NonNull String callingPackage) {
            verifyCallingPackage(callingPackage);
            if (!mShortcutServiceInternal.hasShortcutHostPermission(getCallingUserId(),
                    callingPackage, injectBinderCallingPid(), injectBinderCallingUid())) {
                throw new SecurityException("Caller can't access shortcut information");
            }
        }

        private void ensureStrictAccessShortcutsPermission(@NonNull String callingPackage) {
            verifyCallingPackage(callingPackage);
            if (!injectHasAccessShortcutsPermission(injectBinderCallingPid(),
                    injectBinderCallingUid())) {
                throw new SecurityException("Caller can't access shortcut information");
            }
        }

        /**
         * Returns true if the caller has the "ACCESS_SHORTCUTS" permission.
         */
        @VisibleForTesting
        boolean injectHasAccessShortcutsPermission(int callingPid, int callingUid) {
            return mContext.checkPermission(android.Manifest.permission.ACCESS_SHORTCUTS,
                    callingPid, callingUid) == PackageManager.PERMISSION_GRANTED;
        }

        /**
         * Returns true if the caller has the "INTERACT_ACROSS_USERS_FULL" permission.
         */
        @VisibleForTesting
        boolean injectHasInteractAcrossUsersFullPermission(int callingPid, int callingUid) {
            return mContext.checkPermission(android.Manifest.permission.INTERACT_ACROSS_USERS_FULL,
                    callingPid, callingUid) == PackageManager.PERMISSION_GRANTED;
        }

        @Override
        public ParceledListSlice getShortcuts(@NonNull final String callingPackage,
                @NonNull final ShortcutQueryWrapper query, @NonNull final UserHandle targetUser) {
            ensureShortcutPermission(callingPackage);
            if (!canAccessProfile(targetUser.getIdentifier(), "Cannot get shortcuts")) {
                return new ParceledListSlice<>(Collections.EMPTY_LIST);
            }

            final long changedSince = query.getChangedSince();
            final String packageName = query.getPackage();
            final List<String> shortcutIds = query.getShortcutIds();
            final List<LocusId> locusIds = query.getLocusIds();
            final ComponentName componentName = query.getActivity();
            final int flags = query.getQueryFlags();
            if (shortcutIds != null && packageName == null) {
                throw new IllegalArgumentException(
                        "To query by shortcut ID, package name must also be set");
            }
            if (locusIds != null && packageName == null) {
                throw new IllegalArgumentException(
                        "To query by locus ID, package name must also be set");
            }

            // TODO(b/29399275): Eclipse compiler requires explicit List<ShortcutInfo> cast below.
            return new ParceledListSlice<>((List<ShortcutInfo>)
                    mShortcutServiceInternal.getShortcuts(getCallingUserId(),
                            callingPackage, changedSince, packageName, shortcutIds, locusIds,
                            componentName, flags, targetUser.getIdentifier(),
                            injectBinderCallingPid(), injectBinderCallingUid()));
        }

        @Override
        public void registerShortcutChangeCallback(@NonNull final String callingPackage,
                @NonNull final ShortcutQueryWrapper query,
                @NonNull final IShortcutChangeCallback callback) {

            ensureShortcutPermission(callingPackage);

            if (query.getShortcutIds() != null && query.getPackage() == null) {
                throw new IllegalArgumentException(
                        "To query by shortcut ID, package name must also be set");
            }
            if (query.getLocusIds() != null && query.getPackage() == null) {
                throw new IllegalArgumentException(
                        "To query by locus ID, package name must also be set");
            }

            UserHandle user = UserHandle.of(injectCallingUserId());
            if (injectHasInteractAcrossUsersFullPermission(injectBinderCallingPid(),
                    injectBinderCallingUid())) {
                user = null;
            }

            mShortcutChangeHandler.addShortcutChangeCallback(callback, query, user);
        }

        @Override
        public void unregisterShortcutChangeCallback(String callingPackage,
                IShortcutChangeCallback callback) {
            ensureShortcutPermission(callingPackage);

            mShortcutChangeHandler.removeShortcutChangeCallback(callback);
        }

        @Override
        public void pinShortcuts(String callingPackage, String packageName, List<String> ids,
                UserHandle targetUser) {
            ensureShortcutPermission(callingPackage);
            if (!canAccessProfile(targetUser.getIdentifier(), "Cannot pin shortcuts")) {
                return;
            }

            mShortcutServiceInternal.pinShortcuts(getCallingUserId(),
                    callingPackage, packageName, ids, targetUser.getIdentifier());
        }

        @Override
        public void cacheShortcuts(String callingPackage, String packageName, List<String> ids,
                UserHandle targetUser, int cacheFlags) {
            ensureStrictAccessShortcutsPermission(callingPackage);
            if (!canAccessProfile(targetUser.getIdentifier(), "Cannot cache shortcuts")) {
                return;
            }

            mShortcutServiceInternal.cacheShortcuts(
                    getCallingUserId(), callingPackage, packageName, ids,
                    targetUser.getIdentifier(), toShortcutsCacheFlags(cacheFlags));
        }

        @Override
        public void uncacheShortcuts(String callingPackage, String packageName, List<String> ids,
                UserHandle targetUser, int cacheFlags) {
            ensureStrictAccessShortcutsPermission(callingPackage);
            if (!canAccessProfile(targetUser.getIdentifier(), "Cannot uncache shortcuts")) {
                return;
            }

            mShortcutServiceInternal.uncacheShortcuts(
                    getCallingUserId(), callingPackage, packageName, ids,
                    targetUser.getIdentifier(), toShortcutsCacheFlags(cacheFlags));
        }

        @Override
        public int getShortcutIconResId(String callingPackage, String packageName, String id,
                int targetUserId) {
            ensureShortcutPermission(callingPackage);
            if (!canAccessProfile(targetUserId, "Cannot access shortcuts")) {
                return 0;
            }

            return mShortcutServiceInternal.getShortcutIconResId(getCallingUserId(),
                    callingPackage, packageName, id, targetUserId);
        }

        @Override
        public ParcelFileDescriptor getShortcutIconFd(String callingPackage,
                String packageName, String id, int targetUserId) {
            ensureShortcutPermission(callingPackage);
            if (!canAccessProfile(targetUserId, "Cannot access shortcuts")) {
                return null;
            }

            return mShortcutServiceInternal.getShortcutIconFd(getCallingUserId(),
                    callingPackage, packageName, id, targetUserId);
        }

        @Override
        public String getShortcutIconUri(String callingPackage, String packageName,
                String shortcutId, int userId) {
            ensureShortcutPermission(callingPackage);
            if (!canAccessProfile(userId, "Cannot access shortcuts")) {
                return null;
            }

            return mShortcutServiceInternal.getShortcutIconUri(getCallingUserId(), callingPackage,
                    packageName, shortcutId, userId);
        }

        @Override
        public boolean hasShortcutHostPermission(String callingPackage) {
            verifyCallingPackage(callingPackage);
            return mShortcutServiceInternal.hasShortcutHostPermission(getCallingUserId(),
                    callingPackage, injectBinderCallingPid(), injectBinderCallingUid());
        }

        @Override
        public boolean startShortcut(String callingPackage, String packageName, String featureId,
                String shortcutId, Rect sourceBounds, Bundle startActivityOptions,
                int targetUserId) {
            verifyCallingPackage(callingPackage);
            if (!canAccessProfile(targetUserId, "Cannot start activity")) {
                return false;
            }

            // Even without the permission, pinned shortcuts are always launchable.
            if (!mShortcutServiceInternal.isPinnedByCaller(getCallingUserId(),
                    callingPackage, packageName, shortcutId, targetUserId)) {
                ensureShortcutPermission(callingPackage);
            }

            final Intent[] intents = mShortcutServiceInternal.createShortcutIntents(
                    getCallingUserId(), callingPackage, packageName, shortcutId, targetUserId,
                    injectBinderCallingPid(), injectBinderCallingUid());
            if (intents == null || intents.length == 0) {
                return false;
            }
            // Note the target activity doesn't have to be exported.

            // Flag for bubble
            ActivityOptions options = ActivityOptions.fromBundle(startActivityOptions);
            if (options != null && options.isApplyActivityFlagsForBubbles()) {
                // Flag for bubble to make behaviour match documentLaunchMode=always.
                intents[0].addFlags(FLAG_ACTIVITY_NEW_DOCUMENT);
                intents[0].addFlags(FLAG_ACTIVITY_MULTIPLE_TASK);
            }

            intents[0].addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intents[0].setSourceBounds(sourceBounds);

            return startShortcutIntentsAsPublisher(
                    intents, packageName, featureId, startActivityOptions, targetUserId);
        }

        private boolean startShortcutIntentsAsPublisher(@NonNull Intent[] intents,
                @NonNull String publisherPackage, @Nullable String publishedFeatureId,
                Bundle startActivityOptions, int userId) {
            final int code;
            try {
                code = mActivityTaskManagerInternal.startActivitiesAsPackage(publisherPackage,
                        publishedFeatureId, userId, intents, startActivityOptions);
                if (ActivityManager.isStartResultSuccessful(code)) {
                    return true; // Success
                } else {
                    Log.e(TAG, "Couldn't start activity, code=" + code);
                }
                return false;
            } catch (SecurityException e) {
                if (DEBUG) {
                    Slog.d(TAG, "SecurityException while launching intent", e);
                }
                return false;
            }
        }

        @Override
        public boolean isActivityEnabled(
                String callingPackage, ComponentName component, UserHandle user)
                throws RemoteException {
            if (!canAccessProfile(user.getIdentifier(), "Cannot check component")) {
                return false;
            }

            final int callingUid = injectBinderCallingUid();
            long ident = Binder.clearCallingIdentity();
            try {
                final int state = mIPM.getComponentEnabledSetting(component, user.getIdentifier());
                switch (state) {
                    case PackageManager.COMPONENT_ENABLED_STATE_DEFAULT:
                        break; // Need to check the manifest's enabled state.
                    case PackageManager.COMPONENT_ENABLED_STATE_ENABLED:
                        return true;
                    case PackageManager.COMPONENT_ENABLED_STATE_DISABLED:
                    case PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER:
                    case PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED:
                        return false;
                }
                final PackageManagerInternal pmInt =
                        LocalServices.getService(PackageManagerInternal.class);
                ActivityInfo info = pmInt.getActivityInfo(component,
                        PackageManager.MATCH_DIRECT_BOOT_AWARE
                                | PackageManager.MATCH_DIRECT_BOOT_UNAWARE,
                        callingUid, user.getIdentifier());
                // Note we don't check "exported" because if the caller has the same UID as the
                // callee's UID, it can still be launched.
                // (If an app doesn't export a front door activity and causes issues with the
                // launcher, that's just the app's bug.)
                return info != null && info.isEnabled();
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override
        public void startSessionDetailsActivityAsUser(IApplicationThread caller,
                String callingPackage, String callingFeatureId, SessionInfo sessionInfo,
                Rect sourceBounds, Bundle opts, UserHandle userHandle) throws RemoteException {
            int userId = userHandle.getIdentifier();
            if (!canAccessProfile(userId, "Cannot start details activity")) {
                return;
            }

            Intent i = new Intent(Intent.ACTION_VIEW)
                    .setData(new Uri.Builder()
                            .scheme("market")
                            .authority("details")
                            .appendQueryParameter("id", sessionInfo.appPackageName)
                            .build())
                    .putExtra(Intent.EXTRA_REFERRER, new Uri.Builder().scheme("android-app")
                            .authority(callingPackage).build());
            i.setSourceBounds(sourceBounds);

            mActivityTaskManagerInternal.startActivityAsUser(caller, callingPackage,
                    callingFeatureId, i, /* resultTo= */ null, Intent.FLAG_ACTIVITY_NEW_TASK, opts,
                    userId);
        }

        @Override
        public void startActivityAsUser(IApplicationThread caller, String callingPackage,
                String callingFeatureId, ComponentName component, Rect sourceBounds,
                Bundle opts, UserHandle user) throws RemoteException {
            if (!canAccessProfile(user.getIdentifier(), "Cannot start activity")) {
                return;
            }

            Intent launchIntent = new Intent(Intent.ACTION_MAIN);
            launchIntent.addCategory(Intent.CATEGORY_LAUNCHER);
            launchIntent.setSourceBounds(sourceBounds);
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            launchIntent.setPackage(component.getPackageName());

            boolean canLaunch = false;

            final int callingUid = injectBinderCallingUid();
            long ident = Binder.clearCallingIdentity();
            try {
                final PackageManagerInternal pmInt =
                        LocalServices.getService(PackageManagerInternal.class);
                // Check that the component actually has Intent.CATEGORY_LAUCNCHER
                // as calling startActivityAsUser ignores the category and just
                // resolves based on the component if present.
                List<ResolveInfo> apps = pmInt.queryIntentActivities(launchIntent,
                        launchIntent.resolveTypeIfNeeded(mContext.getContentResolver()),
                        PackageManager.MATCH_DIRECT_BOOT_AWARE
                                | PackageManager.MATCH_DIRECT_BOOT_UNAWARE,
                        callingUid, user.getIdentifier());
                final int size = apps.size();
                for (int i = 0; i < size; ++i) {
                    ActivityInfo activityInfo = apps.get(i).activityInfo;
                    if (activityInfo.packageName.equals(component.getPackageName()) &&
                            activityInfo.name.equals(component.getClassName())) {
                        if (!activityInfo.exported) {
                            throw new SecurityException("Cannot launch non-exported components "
                                    + component);
                        }

                        // Found an activity with category launcher that matches
                        // this component so ok to launch.
                        launchIntent.setPackage(null);
                        launchIntent.setComponent(component);
                        canLaunch = true;
                        break;
                    }
                }
                if (!canLaunch) {
                    throw new SecurityException("Attempt to launch activity without "
                            + " category Intent.CATEGORY_LAUNCHER " + component);
                }
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
            mActivityTaskManagerInternal.startActivityAsUser(caller, callingPackage,
                    callingFeatureId, launchIntent, /* resultTo= */ null,
                    Intent.FLAG_ACTIVITY_NEW_TASK, opts, user.getIdentifier());
        }

        @Override
        public void showAppDetailsAsUser(IApplicationThread caller,
                String callingPackage, String callingFeatureId, ComponentName component,
                Rect sourceBounds, Bundle opts, UserHandle user) throws RemoteException {
            if (!canAccessProfile(user.getIdentifier(), "Cannot show app details")) {
                return;
            }

            final Intent intent;
            long ident = Binder.clearCallingIdentity();
            try {
                String packageName = component.getPackageName();
                intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", packageName, null));
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                intent.setSourceBounds(sourceBounds);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
            mActivityTaskManagerInternal.startActivityAsUser(caller, callingPackage,
                    callingFeatureId, intent, /* resultTo= */ null, Intent.FLAG_ACTIVITY_NEW_TASK,
                    opts, user.getIdentifier());
        }

        /** Checks if user is a profile of or same as listeningUser.
         * and the user is enabled. */
        private boolean isEnabledProfileOf(UserHandle listeningUser, UserHandle user,
                String debugMsg) {
            return mUserManagerInternal.isProfileAccessible(listeningUser.getIdentifier(),
                    user.getIdentifier(), debugMsg, false);
        }

        private int toShortcutsCacheFlags(int cacheFlags) {
            int ret = 0;
            if (cacheFlags == FLAG_CACHE_NOTIFICATION_SHORTCUTS) {
                ret = ShortcutInfo.FLAG_CACHED_NOTIFICATIONS;
            } else if (cacheFlags == FLAG_CACHE_BUBBLE_SHORTCUTS) {
                ret = ShortcutInfo.FLAG_CACHED_BUBBLES;
            }
            Preconditions.checkArgumentPositive(ret, "Invalid cache owner");

            return ret;
        }

        @VisibleForTesting
        void postToPackageMonitorHandler(Runnable r) {
            mCallbackHandler.post(r);
        }

        public static class ShortcutChangeHandler implements LauncherApps.ShortcutChangeCallback {
            private final UserManagerInternal mUserManagerInternal;

            ShortcutChangeHandler(UserManagerInternal userManager) {
                mUserManagerInternal = userManager;
            }

            private final RemoteCallbackList<IShortcutChangeCallback> mCallbacks =
                    new RemoteCallbackList<>();

            public synchronized void addShortcutChangeCallback(IShortcutChangeCallback callback,
                    ShortcutQueryWrapper query, UserHandle user) {
                mCallbacks.unregister(callback);
                mCallbacks.register(callback, new Pair<>(query, user));
            }

            public synchronized void removeShortcutChangeCallback(
                    IShortcutChangeCallback callback) {
                mCallbacks.unregister(callback);
            }

            @Override
            public void onShortcutsAddedOrUpdated(String packageName, List<ShortcutInfo> shortcuts,
                    UserHandle user) {
                onShortcutEvent(packageName, shortcuts, user, false);
            }

            @Override
            public void onShortcutsRemoved(String packageName, List<ShortcutInfo> shortcuts,
                    UserHandle user) {
                onShortcutEvent(packageName, shortcuts, user, true);
            }

            private void onShortcutEvent(String packageName,
                    List<ShortcutInfo> shortcuts, UserHandle user, boolean shortcutsRemoved) {
                int count = mCallbacks.beginBroadcast();

                for (int i = 0; i < count; i++) {
                    final IShortcutChangeCallback callback = mCallbacks.getBroadcastItem(i);
                    final Pair<ShortcutQueryWrapper, UserHandle> cookie =
                            (Pair<ShortcutQueryWrapper, UserHandle>)
                                    mCallbacks.getBroadcastCookie(i);

                    final UserHandle callbackUser = cookie.second;
                    if (callbackUser != null && !hasUserAccess(callbackUser, user)) {
                        // Callback owner does not have access to the shortcuts' user.
                        continue;
                    }

                    // Filter the list by query, if any matches exists, send via callback.
                    List<ShortcutInfo> matchedList = filterShortcutsByQuery(packageName, shortcuts,
                            cookie.first, shortcutsRemoved);
                    if (!CollectionUtils.isEmpty(matchedList)) {
                        try {
                            if (shortcutsRemoved) {
                                callback.onShortcutsRemoved(packageName, matchedList, user);
                            } else {
                                callback.onShortcutsAddedOrUpdated(packageName, matchedList, user);
                            }
                        } catch (RemoteException e) {
                            // The RemoteCallbackList will take care of removing the dead object.
                        }
                    }
                }

                mCallbacks.finishBroadcast();
            }

            public static List<ShortcutInfo> filterShortcutsByQuery(String packageName,
                    List<ShortcutInfo> shortcuts, ShortcutQueryWrapper query,
                    boolean shortcutsRemoved) {
                final long changedSince = query.getChangedSince();
                final String queryPackage = query.getPackage();
                final List<String> shortcutIds = query.getShortcutIds();
                final List<LocusId> locusIds = query.getLocusIds();
                final ComponentName activity = query.getActivity();
                final int flags = query.getQueryFlags();

                if (queryPackage != null && !queryPackage.equals(packageName)) {
                    return null;
                }

                List<ShortcutInfo> matches = new ArrayList<>();

                final boolean matchDynamic = (flags & ShortcutQuery.FLAG_MATCH_DYNAMIC) != 0;
                final boolean matchPinned = (flags & ShortcutQuery.FLAG_MATCH_PINNED) != 0;
                final boolean matchManifest = (flags & ShortcutQuery.FLAG_MATCH_MANIFEST) != 0;
                final boolean matchCached = (flags & ShortcutQuery.FLAG_MATCH_CACHED) != 0;
                final int shortcutFlags = (matchDynamic ? ShortcutInfo.FLAG_DYNAMIC : 0)
                        | (matchPinned ? ShortcutInfo.FLAG_PINNED : 0)
                        | (matchManifest ? ShortcutInfo.FLAG_MANIFEST : 0)
                        | (matchCached ? ShortcutInfo.FLAG_CACHED_ALL : 0);

                for (int i = 0; i < shortcuts.size(); i++) {
                    final ShortcutInfo si = shortcuts.get(i);

                    if (activity != null && !activity.equals(si.getActivity())) {
                        continue;
                    }
                    if (changedSince != 0 && changedSince > si.getLastChangedTimestamp()) {
                        continue;
                    }
                    if (shortcutIds != null && !shortcutIds.contains(si.getId())) {
                        continue;
                    }
                    if (locusIds != null && !locusIds.contains(si.getLocusId())) {
                        continue;
                    }
                    if (shortcutsRemoved || (shortcutFlags & si.getFlags()) != 0) {
                        matches.add(si);
                    }
                }

                return matches;
            }

            private boolean hasUserAccess(UserHandle callbackUser, UserHandle shortcutUser) {
                final int callbackUserId = callbackUser.getIdentifier();
                final int shortcutUserId = shortcutUser.getIdentifier();

                if (shortcutUser == callbackUser) return true;
                return mUserManagerInternal.isProfileAccessible(callbackUserId, shortcutUserId,
                        null, false);
            }
        }

        private class MyPackageMonitor extends PackageMonitor implements ShortcutChangeListener {

            // TODO Simplify with lambdas.

            @Override
            public void onPackageAdded(String packageName, int uid) {
                UserHandle user = new UserHandle(getChangingUserId());
                final int n = mListeners.beginBroadcast();
                try {
                    for (int i = 0; i < n; i++) {
                        IOnAppsChangedListener listener = mListeners.getBroadcastItem(i);
                        BroadcastCookie cookie = (BroadcastCookie) mListeners.getBroadcastCookie(i);
                        if (!isEnabledProfileOf(cookie.user, user, "onPackageAdded")) continue;
                        try {
                            listener.onPackageAdded(user, packageName);
                        } catch (RemoteException re) {
                            Slog.d(TAG, "Callback failed ", re);
                        }
                    }
                } finally {
                    mListeners.finishBroadcast();
                }

                super.onPackageAdded(packageName, uid);
            }

            @Override
            public void onPackageRemoved(String packageName, int uid) {
                UserHandle user = new UserHandle(getChangingUserId());
                final int n = mListeners.beginBroadcast();
                try {
                    for (int i = 0; i < n; i++) {
                        IOnAppsChangedListener listener = mListeners.getBroadcastItem(i);
                        BroadcastCookie cookie = (BroadcastCookie) mListeners.getBroadcastCookie(i);
                        if (!isEnabledProfileOf(cookie.user, user, "onPackageRemoved")) continue;
                        try {
                            listener.onPackageRemoved(user, packageName);
                        } catch (RemoteException re) {
                            Slog.d(TAG, "Callback failed ", re);
                        }
                    }
                } finally {
                    mListeners.finishBroadcast();
                }

                super.onPackageRemoved(packageName, uid);
            }

            @Override
            public void onPackageModified(String packageName) {
                UserHandle user = new UserHandle(getChangingUserId());
                final int n = mListeners.beginBroadcast();
                try {
                    for (int i = 0; i < n; i++) {
                        IOnAppsChangedListener listener = mListeners.getBroadcastItem(i);
                        BroadcastCookie cookie = (BroadcastCookie) mListeners.getBroadcastCookie(i);
                        if (!isEnabledProfileOf(cookie.user, user, "onPackageModified")) continue;
                        try {
                            listener.onPackageChanged(user, packageName);
                        } catch (RemoteException re) {
                            Slog.d(TAG, "Callback failed ", re);
                        }
                    }
                } finally {
                    mListeners.finishBroadcast();
                }

                super.onPackageModified(packageName);
            }

            @Override
            public void onPackagesAvailable(String[] packages) {
                UserHandle user = new UserHandle(getChangingUserId());
                final int n = mListeners.beginBroadcast();
                try {
                    for (int i = 0; i < n; i++) {
                        IOnAppsChangedListener listener = mListeners.getBroadcastItem(i);
                        BroadcastCookie cookie = (BroadcastCookie) mListeners.getBroadcastCookie(i);
                        if (!isEnabledProfileOf(cookie.user, user, "onPackagesAvailable")) continue;
                        try {
                            listener.onPackagesAvailable(user, packages, isReplacing());
                        } catch (RemoteException re) {
                            Slog.d(TAG, "Callback failed ", re);
                        }
                    }
                } finally {
                    mListeners.finishBroadcast();
                }

                super.onPackagesAvailable(packages);
            }

            @Override
            public void onPackagesUnavailable(String[] packages) {
                UserHandle user = new UserHandle(getChangingUserId());
                final int n = mListeners.beginBroadcast();
                try {
                    for (int i = 0; i < n; i++) {
                        IOnAppsChangedListener listener = mListeners.getBroadcastItem(i);
                        BroadcastCookie cookie = (BroadcastCookie) mListeners.getBroadcastCookie(i);
                        if (!isEnabledProfileOf(cookie.user, user, "onPackagesUnavailable")) continue;
                        try {
                            listener.onPackagesUnavailable(user, packages, isReplacing());
                        } catch (RemoteException re) {
                            Slog.d(TAG, "Callback failed ", re);
                        }
                    }
                } finally {
                    mListeners.finishBroadcast();
                }

                super.onPackagesUnavailable(packages);
            }

            @Override
            public void onPackagesSuspended(String[] packages) {
                UserHandle user = new UserHandle(getChangingUserId());
                PackageManagerInternal pmi = LocalServices.getService(PackageManagerInternal.class);
                final ArrayList<Pair<String, Bundle>> packagesWithExtras = new ArrayList<>();
                final ArrayList<String> packagesWithoutExtras = new ArrayList<>();
                for (String pkg : packages) {
                    final Bundle launcherExtras = pmi.getSuspendedPackageLauncherExtras(pkg,
                            user.getIdentifier());
                    if (launcherExtras != null) {
                        packagesWithExtras.add(new Pair<>(pkg, launcherExtras));
                    } else {
                        packagesWithoutExtras.add(pkg);
                    }
                }
                final String[] packagesNullExtras = packagesWithoutExtras.toArray(
                        new String[packagesWithoutExtras.size()]);
                final int n = mListeners.beginBroadcast();
                try {
                    for (int i = 0; i < n; i++) {
                        IOnAppsChangedListener listener = mListeners.getBroadcastItem(i);
                        BroadcastCookie cookie = (BroadcastCookie) mListeners.getBroadcastCookie(i);
                        if (!isEnabledProfileOf(cookie.user, user, "onPackagesSuspended")) continue;
                        try {
                            listener.onPackagesSuspended(user, packagesNullExtras, null);
                            for (int idx = 0; idx < packagesWithExtras.size(); idx++) {
                                Pair<String, Bundle> packageExtraPair = packagesWithExtras.get(idx);
                                listener.onPackagesSuspended(user,
                                        new String[]{packageExtraPair.first},
                                        packageExtraPair.second);
                            }
                        } catch (RemoteException re) {
                            Slog.d(TAG, "Callback failed ", re);
                        }
                    }
                } finally {
                    mListeners.finishBroadcast();
                }
            }

            @Override
            public void onPackagesUnsuspended(String[] packages) {
                UserHandle user = new UserHandle(getChangingUserId());
                final int n = mListeners.beginBroadcast();
                try {
                    for (int i = 0; i < n; i++) {
                        IOnAppsChangedListener listener = mListeners.getBroadcastItem(i);
                        BroadcastCookie cookie = (BroadcastCookie) mListeners.getBroadcastCookie(i);
                        if (!isEnabledProfileOf(cookie.user, user, "onPackagesUnsuspended")) continue;
                        try {
                            listener.onPackagesUnsuspended(user, packages);
                        } catch (RemoteException re) {
                            Slog.d(TAG, "Callback failed ", re);
                        }
                    }
                } finally {
                    mListeners.finishBroadcast();
                }

                super.onPackagesUnsuspended(packages);
            }

            @Override
            public void onShortcutChanged(@NonNull String packageName,
                    @UserIdInt int userId) {
                postToPackageMonitorHandler(() -> onShortcutChangedInner(packageName, userId));
            }

            private void onShortcutChangedInner(@NonNull String packageName,
                    @UserIdInt int userId) {
                final int n = mListeners.beginBroadcast();
                try {
                    final UserHandle user = UserHandle.of(userId);

                    for (int i = 0; i < n; i++) {
                        IOnAppsChangedListener listener = mListeners.getBroadcastItem(i);
                        BroadcastCookie cookie = (BroadcastCookie) mListeners.getBroadcastCookie(i);
                        if (!isEnabledProfileOf(cookie.user, user, "onShortcutChanged")) continue;

                        final int launcherUserId = cookie.user.getIdentifier();

                        // Make sure the caller has the permission.
                        if (!mShortcutServiceInternal.hasShortcutHostPermission(
                                launcherUserId, cookie.packageName,
                                cookie.callingPid, cookie.callingUid)) {
                            continue;
                        }
                        // Each launcher has a different set of pinned shortcuts, so we need to do a
                        // query in here.
                        // (As of now, only one launcher has the permission at a time, so it's bit
                        // moot, but we may change the permission model eventually.)
                        final List<ShortcutInfo> list =
                                mShortcutServiceInternal.getShortcuts(launcherUserId,
                                        cookie.packageName,
                                        /* changedSince= */ 0, packageName, /* shortcutIds=*/ null,
                                        /* locusIds=*/ null, /* component= */ null,
                                        ShortcutQuery.FLAG_GET_KEY_FIELDS_ONLY
                                        | ShortcutQuery.FLAG_MATCH_ALL_KINDS_WITH_ALL_PINNED
                                        , userId, cookie.callingPid, cookie.callingUid);
                        try {
                            listener.onShortcutChanged(user, packageName,
                                    new ParceledListSlice<>(list));
                        } catch (RemoteException re) {
                            Slog.d(TAG, "Callback failed ", re);
                        }
                    }
                } catch (RuntimeException e) {
                    // When the user is locked we get IllegalState, so just catch all.
                    Log.w(TAG, e.getMessage(), e);
                } finally {
                    mListeners.finishBroadcast();
                }
            }
        }

        class PackageCallbackList<T extends IInterface> extends RemoteCallbackList<T> {
            @Override
            public void onCallbackDied(T callback, Object cookie) {
                checkCallbackCount();
            }
        }
    }
}
