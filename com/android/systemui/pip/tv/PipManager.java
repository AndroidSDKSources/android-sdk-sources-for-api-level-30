/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.systemui.pip.tv;

import static android.app.ActivityTaskManager.INVALID_STACK_ID;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;

import android.app.ActivityManager.RunningTaskInfo;
import android.app.ActivityManager.StackInfo;
import android.app.ActivityTaskManager;
import android.app.IActivityTaskManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ParceledListSlice;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Rect;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Debug;
import android.os.Handler;
import android.os.RemoteException;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;
import android.view.DisplayInfo;

import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.UiOffloadThread;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.pip.BasePipManager;
import com.android.systemui.pip.PipBoundsHandler;
import com.android.systemui.pip.PipSurfaceTransactionHelper;
import com.android.systemui.pip.PipTaskOrganizer;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.shared.system.PinnedStackListenerForwarder.PinnedStackListener;
import com.android.systemui.shared.system.TaskStackChangeListener;
import com.android.systemui.shared.system.WindowManagerWrapper;
import com.android.systemui.stackdivider.Divider;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Manages the picture-in-picture (PIP) UI and states.
 */
@Singleton
public class PipManager implements BasePipManager, PipTaskOrganizer.PipTransitionCallback {
    private static final String TAG = "PipManager";
    static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private static final String SETTINGS_PACKAGE_AND_CLASS_DELIMITER = "/";

    private static List<Pair<String, String>> sSettingsPackageAndClassNamePairList;

    /**
     * State when there's no PIP.
     */
    public static final int STATE_NO_PIP = 0;
    /**
     * State when PIP is shown. This is used as default PIP state.
     */
    public static final int STATE_PIP = 1;
    /**
     * State when PIP menu dialog is shown.
     */
    public static final int STATE_PIP_MENU = 2;

    private static final int TASK_ID_NO_PIP = -1;
    private static final int INVALID_RESOURCE_TYPE = -1;

    public static final int SUSPEND_PIP_RESIZE_REASON_WAITING_FOR_MENU_ACTIVITY_FINISH = 0x1;

    /**
     * PIPed activity is playing a media and it can be paused.
     */
    static final int PLAYBACK_STATE_PLAYING = 0;
    /**
     * PIPed activity has a paused media and it can be played.
     */
    static final int PLAYBACK_STATE_PAUSED = 1;
    /**
     * Users are unable to control PIPed activity's media playback.
     */
    static final int PLAYBACK_STATE_UNAVAILABLE = 2;

    private static final int CLOSE_PIP_WHEN_MEDIA_SESSION_GONE_TIMEOUT_MS = 3000;

    private int mSuspendPipResizingReason;

    private Context mContext;
    private PipBoundsHandler mPipBoundsHandler;
    private PipTaskOrganizer mPipTaskOrganizer;
    private IActivityTaskManager mActivityTaskManager;
    private MediaSessionManager mMediaSessionManager;
    private int mState = STATE_NO_PIP;
    private int mResumeResizePinnedStackRunnableState = STATE_NO_PIP;
    private final Handler mHandler = new Handler();
    private List<Listener> mListeners = new ArrayList<>();
    private List<MediaListener> mMediaListeners = new ArrayList<>();
    private Rect mCurrentPipBounds;
    private Rect mPipBounds;
    private Rect mDefaultPipBounds = new Rect();
    private Rect mSettingsPipBounds;
    private Rect mMenuModePipBounds;
    private int mLastOrientation = Configuration.ORIENTATION_UNDEFINED;
    private boolean mInitialized;
    private int mPipTaskId = TASK_ID_NO_PIP;
    private int mPinnedStackId = INVALID_STACK_ID;
    private ComponentName mPipComponentName;
    private MediaController mPipMediaController;
    private String[] mLastPackagesResourceGranted;
    private PipNotification mPipNotification;
    private ParceledListSlice mCustomActions;
    private int mResizeAnimationDuration;

    // Used to calculate the movement bounds
    private final DisplayInfo mTmpDisplayInfo = new DisplayInfo();
    private final Rect mTmpInsetBounds = new Rect();
    private final Rect mTmpNormalBounds = new Rect();

    // Keeps track of the IME visibility to adjust the PiP when the IME is visible
    private boolean mImeVisible;
    private int mImeHeightAdjustment;

    private final PinnedStackListener mPinnedStackListener = new PipManagerPinnedStackListener();

    private final Runnable mResizePinnedStackRunnable = new Runnable() {
        @Override
        public void run() {
            resizePinnedStack(mResumeResizePinnedStackRunnableState);
        }
    };
    private final Runnable mClosePipRunnable = new Runnable() {
        @Override
        public void run() {
            closePip();
        }
    };

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_MEDIA_RESOURCE_GRANTED.equals(action)) {
                String[] packageNames = intent.getStringArrayExtra(Intent.EXTRA_PACKAGES);
                int resourceType = intent.getIntExtra(Intent.EXTRA_MEDIA_RESOURCE_TYPE,
                        INVALID_RESOURCE_TYPE);
                if (packageNames != null && packageNames.length > 0
                        && resourceType == Intent.EXTRA_MEDIA_RESOURCE_TYPE_VIDEO_CODEC) {
                    handleMediaResourceGranted(packageNames);
                }
            }

        }
    };
    private final MediaSessionManager.OnActiveSessionsChangedListener mActiveMediaSessionListener =
            new MediaSessionManager.OnActiveSessionsChangedListener() {
                @Override
                public void onActiveSessionsChanged(List<MediaController> controllers) {
                    updateMediaController(controllers);
                }
            };

    /**
     * Handler for messages from the PIP controller.
     */
    private class PipManagerPinnedStackListener extends PinnedStackListener {
        @Override
        public void onImeVisibilityChanged(boolean imeVisible, int imeHeight) {
            if (mState == STATE_PIP) {
                if (mImeVisible != imeVisible) {
                    if (imeVisible) {
                        // Save the IME height adjustment, and offset to not occlude the IME
                        mPipBounds.offset(0, -imeHeight);
                        mImeHeightAdjustment = imeHeight;
                    } else {
                        // Apply the inverse adjustment when the IME is hidden
                        mPipBounds.offset(0, mImeHeightAdjustment);
                    }
                    mImeVisible = imeVisible;
                    resizePinnedStack(STATE_PIP);
                }
            }
        }

        @Override
        public void onMovementBoundsChanged(boolean fromImeAdjustment) {
            mHandler.post(() -> {
                // Populate the inset / normal bounds and DisplayInfo from mPipBoundsHandler first.
                final Rect destinationBounds = new Rect();
                mPipBoundsHandler.onMovementBoundsChanged(mTmpInsetBounds, mTmpNormalBounds,
                        destinationBounds, mTmpDisplayInfo);
                mDefaultPipBounds.set(destinationBounds);
            });
        }

        @Override
        public void onActionsChanged(ParceledListSlice actions) {
            mCustomActions = actions;
            mHandler.post(() -> {
                for (int i = mListeners.size() - 1; i >= 0; --i) {
                    mListeners.get(i).onPipMenuActionsChanged(mCustomActions);
                }
            });
        }
    }

    @Inject
    public PipManager(Context context, BroadcastDispatcher broadcastDispatcher,
            PipBoundsHandler pipBoundsHandler,
            PipTaskOrganizer pipTaskOrganizer,
            PipSurfaceTransactionHelper surfaceTransactionHelper,
            Divider divider) {
        if (mInitialized) {
            return;
        }

        mInitialized = true;
        mContext = context;
        mPipBoundsHandler = pipBoundsHandler;
        // Ensure that we have the display info in case we get calls to update the bounds before the
        // listener calls back
        final DisplayInfo displayInfo = new DisplayInfo();
        context.getDisplay().getDisplayInfo(displayInfo);
        mPipBoundsHandler.onDisplayInfoChanged(displayInfo);

        mResizeAnimationDuration = context.getResources()
                .getInteger(R.integer.config_pipResizeAnimationDuration);
        mPipTaskOrganizer = pipTaskOrganizer;
        mPipTaskOrganizer.registerPipTransitionCallback(this);
        mActivityTaskManager = ActivityTaskManager.getService();
        ActivityManagerWrapper.getInstance().registerTaskStackListener(mTaskStackListener);
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_MEDIA_RESOURCE_GRANTED);
        broadcastDispatcher.registerReceiver(mBroadcastReceiver, intentFilter,
                null /* handler */, UserHandle.ALL);

        if (sSettingsPackageAndClassNamePairList == null) {
            String[] settings = mContext.getResources().getStringArray(
                    R.array.tv_pip_settings_class_name);
            sSettingsPackageAndClassNamePairList = new ArrayList<>();
            if (settings != null) {
                for (int i = 0; i < settings.length; i++) {
                    Pair<String, String> entry = null;
                    String[] packageAndClassName =
                            settings[i].split(SETTINGS_PACKAGE_AND_CLASS_DELIMITER);
                    switch (packageAndClassName.length) {
                        case 1:
                            entry = Pair.<String, String>create(packageAndClassName[0], null);
                            break;
                        case 2:
                            if (packageAndClassName[1] != null) {
                                entry = Pair.<String, String>create(packageAndClassName[0],
                                        packageAndClassName[1].startsWith(".")
                                                ? packageAndClassName[0] + packageAndClassName[1]
                                                : packageAndClassName[1]);
                            }
                            break;
                    }
                    if (entry != null) {
                        sSettingsPackageAndClassNamePairList.add(entry);
                    } else {
                        Log.w(TAG, "Ignoring malformed settings name " + settings[i]);
                    }
                }
            }
        }

        // Initialize the last orientation and apply the current configuration
        Configuration initialConfig = mContext.getResources().getConfiguration();
        mLastOrientation = initialConfig.orientation;
        loadConfigurationsAndApply(initialConfig);

        mMediaSessionManager =
                (MediaSessionManager) mContext.getSystemService(Context.MEDIA_SESSION_SERVICE);

        try {
            WindowManagerWrapper.getInstance().addPinnedStackListener(mPinnedStackListener);
            mPipTaskOrganizer.registerOrganizer(WINDOWING_MODE_PINNED);
        } catch (RemoteException | UnsupportedOperationException e) {
            Log.e(TAG, "Failed to register pinned stack listener", e);
        }

        mPipNotification = new PipNotification(context, broadcastDispatcher, this);
    }

    private void loadConfigurationsAndApply(Configuration newConfig) {
        if (mLastOrientation != newConfig.orientation) {
            // Don't resize the pinned stack on orientation change. TV does not care about this case
            // and this could clobber the existing animation to the new bounds calculated by WM.
            mLastOrientation = newConfig.orientation;
            return;
        }

        Resources res = mContext.getResources();
        mSettingsPipBounds = Rect.unflattenFromString(res.getString(
                R.string.pip_settings_bounds));
        mMenuModePipBounds = Rect.unflattenFromString(res.getString(
                R.string.pip_menu_bounds));

        // Reset the PIP bounds and apply. PIP bounds can be changed by two reasons.
        //   1. Configuration changed due to the language change (RTL <-> RTL)
        //   2. SystemUI restarts after the crash
        mPipBounds = isSettingsShown() ? mSettingsPipBounds : mDefaultPipBounds;
        resizePinnedStack(getPinnedStackInfo() == null ? STATE_NO_PIP : STATE_PIP);
    }

    /**
     * Updates the PIP per configuration changed.
     */
    public void onConfigurationChanged(Configuration newConfig) {
        loadConfigurationsAndApply(newConfig);
        mPipNotification.onConfigurationChanged(mContext);
    }

    /**
     * Shows the picture-in-picture menu if an activity is in picture-in-picture mode.
     */
    public void showPictureInPictureMenu() {
        if (DEBUG) Log.d(TAG, "showPictureInPictureMenu(), current state=" + getStateDescription());

        if (getState() == STATE_PIP) {
            resizePinnedStack(STATE_PIP_MENU);
        }
    }

    /**
     * Closes PIP (PIPed activity and PIP system UI).
     */
    public void closePip() {
        if (DEBUG) Log.d(TAG, "closePip(), current state=" + getStateDescription());

        closePipInternal(true);
    }

    private void closePipInternal(boolean removePipStack) {
        if (DEBUG) {
            Log.d(TAG,
                    "closePipInternal() removePipStack=" + removePipStack + ", current state="
                            + getStateDescription());
        }

        mState = STATE_NO_PIP;
        mPipTaskId = TASK_ID_NO_PIP;
        mPipMediaController = null;
        mMediaSessionManager.removeOnActiveSessionsChangedListener(mActiveMediaSessionListener);
        if (removePipStack) {
            try {
                mActivityTaskManager.removeStack(mPinnedStackId);
            } catch (RemoteException e) {
                Log.e(TAG, "removeStack failed", e);
            } finally {
                mPinnedStackId = INVALID_STACK_ID;
            }
        }
        for (int i = mListeners.size() - 1; i >= 0; --i) {
            mListeners.get(i).onPipActivityClosed();
        }
        mHandler.removeCallbacks(mClosePipRunnable);
        updatePipVisibility(false);
    }

    /**
     * Moves the PIPed activity to the fullscreen and closes PIP system UI.
     */
    void movePipToFullscreen() {
        if (DEBUG) Log.d(TAG, "movePipToFullscreen(), current state=" + getStateDescription());

        mPipTaskId = TASK_ID_NO_PIP;
        for (int i = mListeners.size() - 1; i >= 0; --i) {
            mListeners.get(i).onMoveToFullscreen();
        }
        resizePinnedStack(STATE_NO_PIP);
        updatePipVisibility(false);
    }

    /**
     * Suspends resizing operation on the Pip until {@link #resumePipResizing} is called
     * @param reason The reason for suspending resizing operations on the Pip.
     */
    public void suspendPipResizing(int reason) {
        if (DEBUG) Log.d(TAG,
                "suspendPipResizing() reason=" + reason + " callers=" + Debug.getCallers(2));

        mSuspendPipResizingReason |= reason;
    }

    /**
     * Resumes resizing operation on the Pip that was previously suspended.
     * @param reason The reason resizing operations on the Pip was suspended.
     */
    public void resumePipResizing(int reason) {
        if ((mSuspendPipResizingReason & reason) == 0) {
            return;
        }
        if (DEBUG) Log.d(TAG,
                "resumePipResizing() reason=" + reason + " callers=" + Debug.getCallers(2));
        mSuspendPipResizingReason &= ~reason;
        mHandler.post(mResizePinnedStackRunnable);
    }

    /**
     * Resize the Pip to the appropriate size for the input state.
     * @param state In Pip state also used to determine the new size for the Pip.
     */
    void resizePinnedStack(int state) {
        if (DEBUG) {
            Log.d(TAG, "resizePinnedStack() state=" + stateToName(state) + ", current state="
                    + getStateDescription(), new Exception());
        }

        boolean wasStateNoPip = (mState == STATE_NO_PIP);
        for (int i = mListeners.size() - 1; i >= 0; --i) {
            mListeners.get(i).onPipResizeAboutToStart();
        }
        if (mSuspendPipResizingReason != 0) {
            mResumeResizePinnedStackRunnableState = state;
            if (DEBUG) Log.d(TAG, "resizePinnedStack() deferring"
                    + " mSuspendPipResizingReason=" + mSuspendPipResizingReason
                    + " mResumeResizePinnedStackRunnableState="
                    + stateToName(mResumeResizePinnedStackRunnableState));
            return;
        }
        mState = state;
        switch (mState) {
            case STATE_NO_PIP:
                mCurrentPipBounds = null;
                // If the state was already STATE_NO_PIP, then do not resize the stack below as it
                // will not exist
                if (wasStateNoPip) {
                    return;
                }
                break;
            case STATE_PIP_MENU:
                mCurrentPipBounds = mMenuModePipBounds;
                break;
            case STATE_PIP: // fallthrough
            default:
                mCurrentPipBounds = mPipBounds;
                break;
        }
        if (mCurrentPipBounds != null) {
            mPipTaskOrganizer.scheduleAnimateResizePip(mCurrentPipBounds, mResizeAnimationDuration,
                    null);
        } else {
            mPipTaskOrganizer.exitPip(mResizeAnimationDuration);
        }
    }

    /**
     * @return the current state, or the pending state if the state change was previously suspended.
     */
    private int getState() {
        if (mSuspendPipResizingReason != 0) {
            return mResumeResizePinnedStackRunnableState;
        }
        return mState;
    }

    /**
     * Shows PIP menu UI by launching {@link PipMenuActivity}. It also locates the pinned
     * stack to the centered PIP bound {@link R.config_centeredPictureInPictureBounds}.
     */
    private void showPipMenu() {
        if (DEBUG) Log.d(TAG, "showPipMenu(), current state=" + getStateDescription());

        mState = STATE_PIP_MENU;
        for (int i = mListeners.size() - 1; i >= 0; --i) {
            mListeners.get(i).onShowPipMenu();
        }
        Intent intent = new Intent(mContext, PipMenuActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra(PipMenuActivity.EXTRA_CUSTOM_ACTIONS, mCustomActions);
        mContext.startActivity(intent);
    }

    /**
     * Adds a {@link Listener} to PipManager.
     */
    public void addListener(Listener listener) {
        mListeners.add(listener);
    }

    /**
     * Removes a {@link Listener} from PipManager.
     */
    public void removeListener(Listener listener) {
        mListeners.remove(listener);
    }

    /**
     * Adds a {@link MediaListener} to PipManager.
     */
    public void addMediaListener(MediaListener listener) {
        mMediaListeners.add(listener);
    }

    /**
     * Removes a {@link MediaListener} from PipManager.
     */
    public void removeMediaListener(MediaListener listener) {
        mMediaListeners.remove(listener);
    }

    /**
     * Returns {@code true} if PIP is shown.
     */
    public boolean isPipShown() {
        return mState != STATE_NO_PIP;
    }

    private StackInfo getPinnedStackInfo() {
        StackInfo stackInfo = null;
        try {
            stackInfo = ActivityTaskManager.getService().getStackInfo(
                    WINDOWING_MODE_PINNED, ACTIVITY_TYPE_UNDEFINED);
        } catch (RemoteException e) {
            Log.e(TAG, "getStackInfo failed", e);
        }
        return stackInfo;
    }

    private void handleMediaResourceGranted(String[] packageNames) {
        if (getState() == STATE_NO_PIP) {
            mLastPackagesResourceGranted = packageNames;
        } else {
            boolean requestedFromLastPackages = false;
            if (mLastPackagesResourceGranted != null) {
                for (String packageName : mLastPackagesResourceGranted) {
                    for (String newPackageName : packageNames) {
                        if (TextUtils.equals(newPackageName, packageName)) {
                            requestedFromLastPackages = true;
                            break;
                        }
                    }
                }
            }
            mLastPackagesResourceGranted = packageNames;
            if (!requestedFromLastPackages) {
                closePip();
            }
        }
    }

    private void updateMediaController(List<MediaController> controllers) {
        MediaController mediaController = null;
        if (controllers != null && getState() != STATE_NO_PIP && mPipComponentName != null) {
            for (int i = controllers.size() - 1; i >= 0; i--) {
                MediaController controller = controllers.get(i);
                // We assumes that an app with PIPable activity
                // keeps the single instance of media controller especially when PIP is on.
                if (controller.getPackageName().equals(mPipComponentName.getPackageName())) {
                    mediaController = controller;
                    break;
                }
            }
        }
        if (mPipMediaController != mediaController) {
            mPipMediaController = mediaController;
            for (int i = mMediaListeners.size() - 1; i >= 0; i--) {
                mMediaListeners.get(i).onMediaControllerChanged();
            }
            if (mPipMediaController == null) {
                mHandler.postDelayed(mClosePipRunnable,
                        CLOSE_PIP_WHEN_MEDIA_SESSION_GONE_TIMEOUT_MS);
            } else {
                mHandler.removeCallbacks(mClosePipRunnable);
            }
        }
    }

    /**
     * Gets the {@link android.media.session.MediaController} for the PIPed activity.
     */
    MediaController getMediaController() {
        return mPipMediaController;
    }

    /**
     * Returns the PIPed activity's playback state.
     * This returns one of {@link #PLAYBACK_STATE_PLAYING}, {@link #PLAYBACK_STATE_PAUSED},
     * or {@link #PLAYBACK_STATE_UNAVAILABLE}.
     */
    int getPlaybackState() {
        if (mPipMediaController == null || mPipMediaController.getPlaybackState() == null) {
            return PLAYBACK_STATE_UNAVAILABLE;
        }
        int state = mPipMediaController.getPlaybackState().getState();
        boolean isPlaying = (state == PlaybackState.STATE_BUFFERING
                || state == PlaybackState.STATE_CONNECTING
                || state == PlaybackState.STATE_PLAYING
                || state == PlaybackState.STATE_FAST_FORWARDING
                || state == PlaybackState.STATE_REWINDING
                || state == PlaybackState.STATE_SKIPPING_TO_PREVIOUS
                || state == PlaybackState.STATE_SKIPPING_TO_NEXT);
        long actions = mPipMediaController.getPlaybackState().getActions();
        if (!isPlaying && ((actions & PlaybackState.ACTION_PLAY) != 0)) {
            return PLAYBACK_STATE_PAUSED;
        } else if (isPlaying && ((actions & PlaybackState.ACTION_PAUSE) != 0)) {
            return PLAYBACK_STATE_PLAYING;
        }
        return PLAYBACK_STATE_UNAVAILABLE;
    }

    private boolean isSettingsShown() {
        List<RunningTaskInfo> runningTasks;
        try {
            runningTasks = mActivityTaskManager.getTasks(1);
            if (runningTasks.isEmpty()) {
                return false;
            }
        } catch (RemoteException e) {
            Log.d(TAG, "Failed to detect top activity", e);
            return false;
        }
        ComponentName topActivity = runningTasks.get(0).topActivity;
        for (Pair<String, String> componentName : sSettingsPackageAndClassNamePairList) {
            String packageName = componentName.first;
            if (topActivity.getPackageName().equals(packageName)) {
                String className = componentName.second;
                if (className == null || topActivity.getClassName().equals(className)) {
                    return true;
                }
            }
        }
        return false;
    }

    private TaskStackChangeListener mTaskStackListener = new TaskStackChangeListener() {
        @Override
        public void onTaskStackChanged() {
            if (DEBUG) Log.d(TAG, "onTaskStackChanged()");

            if (getState() != STATE_NO_PIP) {
                boolean hasPip = false;

                StackInfo stackInfo = getPinnedStackInfo();
                if (stackInfo == null || stackInfo.taskIds == null) {
                    Log.w(TAG, "There is nothing in pinned stack");
                    closePipInternal(false);
                    return;
                }
                for (int i = stackInfo.taskIds.length - 1; i >= 0; --i) {
                    if (stackInfo.taskIds[i] == mPipTaskId) {
                        // PIP task is still alive.
                        hasPip = true;
                        break;
                    }
                }
                if (!hasPip) {
                    // PIP task doesn't exist anymore in PINNED_STACK.
                    closePipInternal(true);
                    return;
                }
            }
            if (getState() == STATE_PIP) {
                Rect bounds = isSettingsShown() ? mSettingsPipBounds : mDefaultPipBounds;
                if (mPipBounds != bounds) {
                    mPipBounds = bounds;
                    resizePinnedStack(STATE_PIP);
                }
            }
        }

        @Override
        public void onActivityPinned(String packageName, int userId, int taskId, int stackId) {
            if (DEBUG) Log.d(TAG, "onActivityPinned()");

            StackInfo stackInfo = getPinnedStackInfo();
            if (stackInfo == null) {
                Log.w(TAG, "Cannot find pinned stack");
                return;
            }
            if (DEBUG) Log.d(TAG, "PINNED_STACK:" + stackInfo);
            mPinnedStackId = stackInfo.stackId;
            mPipTaskId = stackInfo.taskIds[stackInfo.taskIds.length - 1];
            mPipComponentName = ComponentName.unflattenFromString(
                    stackInfo.taskNames[stackInfo.taskNames.length - 1]);
            // Set state to STATE_PIP so we show it when the pinned stack animation ends.
            mState = STATE_PIP;
            mCurrentPipBounds = mPipBounds;
            mMediaSessionManager.addOnActiveSessionsChangedListener(
                    mActiveMediaSessionListener, null);
            updateMediaController(mMediaSessionManager.getActiveSessions(null));
            for (int i = mListeners.size() - 1; i >= 0; i--) {
                mListeners.get(i).onPipEntered(packageName);
            }
            updatePipVisibility(true);
        }

        @Override
        public void onActivityRestartAttempt(RunningTaskInfo task, boolean homeTaskVisible,
                boolean clearedTask, boolean wasVisible) {
            if (task.configuration.windowConfiguration.getWindowingMode()
                    != WINDOWING_MODE_PINNED) {
                return;
            }
            if (DEBUG) Log.d(TAG, "onPinnedActivityRestartAttempt()");

            // If PIPed activity is launched again by Launcher or intent, make it fullscreen.
            movePipToFullscreen();
        }
    };

    @Override
    public void onPipTransitionStarted(ComponentName activity, int direction) { }

    @Override
    public void onPipTransitionFinished(ComponentName activity, int direction) {
        onPipTransitionFinishedOrCanceled();
    }

    @Override
    public void onPipTransitionCanceled(ComponentName activity, int direction) {
        onPipTransitionFinishedOrCanceled();
    }

    private void onPipTransitionFinishedOrCanceled() {
        if (DEBUG) Log.d(TAG, "onPipTransitionFinishedOrCanceled()");

        if (getState() == STATE_PIP_MENU) {
            showPipMenu();
        }
    }

    /**
     * A listener interface to receive notification on changes in PIP.
     */
    public interface Listener {
        /**
         * Invoked when an activity is pinned and PIP manager is set corresponding information.
         * Classes must use this instead of {@link android.app.ITaskStackListener.onActivityPinned}
         * because there's no guarantee for the PIP manager be return relavent information
         * correctly. (e.g. {@link isPipShown}).
         */
        void onPipEntered(String packageName);
        /** Invoked when a PIPed activity is closed. */
        void onPipActivityClosed();
        /** Invoked when the PIP menu gets shown. */
        void onShowPipMenu();
        /** Invoked when the PIP menu actions change. */
        void onPipMenuActionsChanged(ParceledListSlice actions);
        /** Invoked when the PIPed activity is about to return back to the fullscreen. */
        void onMoveToFullscreen();
        /** Invoked when we are above to start resizing the Pip. */
        void onPipResizeAboutToStart();
    }

    /**
     * A listener interface to receive change in PIP's media controller
     */
    public interface MediaListener {
        /** Invoked when the MediaController on PIPed activity is changed. */
        void onMediaControllerChanged();
    }

    private void updatePipVisibility(final boolean visible) {
        Dependency.get(UiOffloadThread.class).execute(() -> {
            WindowManagerWrapper.getInstance().setPipVisibility(visible);
        });
    }

    private String getStateDescription() {
        if (mSuspendPipResizingReason == 0) {
            return stateToName(mState);
        }
        return stateToName(mResumeResizePinnedStackRunnableState) + " (while " + stateToName(mState)
                + " is suspended)";
    }

    private static String stateToName(int state) {
        switch (state) {
            case STATE_NO_PIP:
                return "NO_PIP";

            case STATE_PIP:
                return "PIP";

            case STATE_PIP_MENU:
                return "PIP_MENU";

            default:
                return "UNKNOWN(" + state + ")";
        }
    }
}
