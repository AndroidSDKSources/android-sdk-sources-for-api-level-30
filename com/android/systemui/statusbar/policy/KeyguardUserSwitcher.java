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
 * limitations under the License
 */

package com.android.systemui.statusbar.policy;

import static com.android.systemui.statusbar.policy.UserSwitcherController.USER_SWITCH_DISABLED_ALPHA;
import static com.android.systemui.statusbar.policy.UserSwitcherController.USER_SWITCH_ENABLED_ALPHA;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.database.DataSetObserver;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewStub;
import android.widget.FrameLayout;

import com.android.settingslib.animation.AppearAnimationUtils;
import com.android.settingslib.drawable.CircleFramedDrawable;
import com.android.systemui.Dependency;
import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.qs.tiles.UserDetailItemView;
import com.android.systemui.statusbar.phone.KeyguardStatusBarView;
import com.android.systemui.statusbar.phone.NotificationPanelViewController;

/**
 * Manages the user switcher on the Keyguard.
 */
public class KeyguardUserSwitcher {

    private static final String TAG = "KeyguardUserSwitcher";
    private static final boolean ALWAYS_ON = false;

    private final Container mUserSwitcherContainer;
    private final KeyguardStatusBarView mStatusBarView;
    private final Adapter mAdapter;
    private final AppearAnimationUtils mAppearAnimationUtils;
    private final KeyguardUserSwitcherScrim mBackground;

    private ViewGroup mUserSwitcher;
    private ObjectAnimator mBgAnimator;
    private UserSwitcherController mUserSwitcherController;
    private boolean mAnimating;

    public KeyguardUserSwitcher(Context context, ViewStub userSwitcher,
            KeyguardStatusBarView statusBarView,
            NotificationPanelViewController panelViewController) {
        boolean keyguardUserSwitcherEnabled =
                context.getResources().getBoolean(
                        com.android.internal.R.bool.config_keyguardUserSwitcher) || ALWAYS_ON;
        UserSwitcherController userSwitcherController = Dependency.get(UserSwitcherController.class);
        if (userSwitcherController != null && keyguardUserSwitcherEnabled) {
            mUserSwitcherContainer = (Container) userSwitcher.inflate();
            mBackground = new KeyguardUserSwitcherScrim(context);
            reinflateViews();
            mStatusBarView = statusBarView;
            mStatusBarView.setKeyguardUserSwitcher(this);
            panelViewController.setKeyguardUserSwitcher(this);
            mAdapter = new Adapter(context, userSwitcherController, this);
            mAdapter.registerDataSetObserver(mDataSetObserver);
            mUserSwitcherController = userSwitcherController;
            mAppearAnimationUtils = new AppearAnimationUtils(context, 400, -0.5f, 0.5f,
                    Interpolators.FAST_OUT_SLOW_IN);
            mUserSwitcherContainer.setKeyguardUserSwitcher(this);
        } else {
            mUserSwitcherContainer = null;
            mStatusBarView = null;
            mAdapter = null;
            mAppearAnimationUtils = null;
            mBackground = null;
        }
    }

    private void reinflateViews() {
        if (mUserSwitcher != null) {
            mUserSwitcher.setBackground(null);
            mUserSwitcher.removeOnLayoutChangeListener(mBackground);
        }
        mUserSwitcherContainer.removeAllViews();

        LayoutInflater.from(mUserSwitcherContainer.getContext())
                .inflate(R.layout.keyguard_user_switcher_inner, mUserSwitcherContainer);

        mUserSwitcher = (ViewGroup) mUserSwitcherContainer.findViewById(
                R.id.keyguard_user_switcher_inner);
        mUserSwitcher.addOnLayoutChangeListener(mBackground);
        mUserSwitcher.setBackground(mBackground);
    }

    public void setKeyguard(boolean keyguard, boolean animate) {
        if (mUserSwitcher != null) {
            if (keyguard && shouldExpandByDefault()) {
                show(animate);
            } else {
                hide(animate);
            }
        }
    }

    /**
     * @return true if the user switcher should be expanded by default on the lock screen.
     * @see android.os.UserManager#isUserSwitcherEnabled()
     */
    private boolean shouldExpandByDefault() {
        return (mUserSwitcherController != null) && mUserSwitcherController.isSimpleUserSwitcher();
    }

    public void show(boolean animate) {
        if (mUserSwitcher != null && mUserSwitcherContainer.getVisibility() != View.VISIBLE) {
            cancelAnimations();
            mAdapter.refresh();
            mUserSwitcherContainer.setVisibility(View.VISIBLE);
            mStatusBarView.setKeyguardUserSwitcherShowing(true, animate);
            if (animate) {
                startAppearAnimation();
            }
        }
    }

    private boolean hide(boolean animate) {
        if (mUserSwitcher != null && mUserSwitcherContainer.getVisibility() == View.VISIBLE) {
            cancelAnimations();
            if (animate) {
                startDisappearAnimation();
            } else {
                mUserSwitcherContainer.setVisibility(View.GONE);
            }
            mStatusBarView.setKeyguardUserSwitcherShowing(false, animate);
            return true;
        }
        return false;
    }

    private void cancelAnimations() {
        int count = mUserSwitcher.getChildCount();
        for (int i = 0; i < count; i++) {
            mUserSwitcher.getChildAt(i).animate().cancel();
        }
        if (mBgAnimator != null) {
            mBgAnimator.cancel();
        }
        mUserSwitcher.animate().cancel();
        mAnimating = false;
    }

    private void startAppearAnimation() {
        int count = mUserSwitcher.getChildCount();
        View[] objects = new View[count];
        for (int i = 0; i < count; i++) {
            objects[i] = mUserSwitcher.getChildAt(i);
        }
        mUserSwitcher.setClipChildren(false);
        mUserSwitcher.setClipToPadding(false);
        mAppearAnimationUtils.startAnimation(objects, new Runnable() {
            @Override
            public void run() {
                mUserSwitcher.setClipChildren(true);
                mUserSwitcher.setClipToPadding(true);
            }
        });
        mAnimating = true;
        mBgAnimator = ObjectAnimator.ofInt(mBackground, "alpha", 0, 255);
        mBgAnimator.setDuration(400);
        mBgAnimator.setInterpolator(Interpolators.ALPHA_IN);
        mBgAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mBgAnimator = null;
                mAnimating = false;
            }
        });
        mBgAnimator.start();
    }

    private void startDisappearAnimation() {
        mAnimating = true;
        mUserSwitcher.animate()
                .alpha(0f)
                .setDuration(300)
                .setInterpolator(Interpolators.ALPHA_OUT)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        mUserSwitcherContainer.setVisibility(View.GONE);
                        mUserSwitcher.setAlpha(1f);
                        mAnimating = false;
                    }
                });
    }

    private void refresh() {
        final int childCount = mUserSwitcher.getChildCount();
        final int adapterCount = mAdapter.getCount();
        final int N = Math.max(childCount, adapterCount);
        for (int i = 0; i < N; i++) {
            if (i < adapterCount) {
                View oldView = null;
                if (i < childCount) {
                    oldView = mUserSwitcher.getChildAt(i);
                }
                View newView = mAdapter.getView(i, oldView, mUserSwitcher);
                if (oldView == null) {
                    // We ran out of existing views. Add it at the end.
                    mUserSwitcher.addView(newView);
                } else if (oldView != newView) {
                    // We couldn't rebind the view. Replace it.
                    mUserSwitcher.removeViewAt(i);
                    mUserSwitcher.addView(newView, i);
                }
            } else {
                int lastIndex = mUserSwitcher.getChildCount() - 1;
                mUserSwitcher.removeViewAt(lastIndex);
            }
        }
    }

    public boolean hideIfNotSimple(boolean animate) {
        if (mUserSwitcherContainer != null && !mUserSwitcherController.isSimpleUserSwitcher()) {
            return hide(animate);
        }
        return false;
    }

    boolean isAnimating() {
        return mAnimating;
    }

    public final DataSetObserver mDataSetObserver = new DataSetObserver() {
        @Override
        public void onChanged() {
            refresh();
        }
    };

    public void onDensityOrFontScaleChanged() {
        if (mUserSwitcherContainer != null) {
            reinflateViews();
            refresh();
        }
    }

    public static class Adapter extends UserSwitcherController.BaseUserAdapter implements
            View.OnClickListener {

        private Context mContext;
        private KeyguardUserSwitcher mKeyguardUserSwitcher;
        private View mCurrentUserView;

        public Adapter(Context context, UserSwitcherController controller,
                KeyguardUserSwitcher kgu) {
            super(controller);
            mContext = context;
            mKeyguardUserSwitcher = kgu;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            UserSwitcherController.UserRecord item = getItem(position);
            if (!(convertView instanceof UserDetailItemView)
                    || !(convertView.getTag() instanceof UserSwitcherController.UserRecord)) {
                convertView = LayoutInflater.from(mContext).inflate(
                        R.layout.keyguard_user_switcher_item, parent, false);
                convertView.setOnClickListener(this);
            }
            UserDetailItemView v = (UserDetailItemView) convertView;

            String name = getName(mContext, item);
            if (item.picture == null) {
                v.bind(name, getDrawable(mContext, item).mutate(), item.resolveId());
            } else {
                int avatarSize =
                        (int) mContext.getResources().getDimension(R.dimen.kg_framed_avatar_size);
                Drawable drawable = new CircleFramedDrawable(item.picture, avatarSize);
                drawable.setColorFilter(
                        item.isSwitchToEnabled ? null : getDisabledUserAvatarColorFilter());
                v.bind(name, drawable, item.info.id);
            }
            v.setActivated(item.isCurrent);
            v.setDisabledByAdmin(item.isDisabledByAdmin);
            v.setEnabled(item.isSwitchToEnabled);
            v.setAlpha(v.isEnabled() ? USER_SWITCH_ENABLED_ALPHA : USER_SWITCH_DISABLED_ALPHA);

            if (item.isCurrent) {
                mCurrentUserView = v;
            }
            v.setTag(item);
            return v;
        }

        private static Drawable getDrawable(Context context,
                UserSwitcherController.UserRecord item) {
            Drawable drawable = getIconDrawable(context, item);
            int iconColorRes;
            if (item.isCurrent) {
                iconColorRes = R.color.kg_user_switcher_selected_avatar_icon_color;
            } else if (!item.isSwitchToEnabled) {
                iconColorRes = R.color.GM2_grey_600;
            } else {
                iconColorRes = R.color.kg_user_switcher_avatar_icon_color;
            }
            drawable.setTint(context.getResources().getColor(iconColorRes, context.getTheme()));

            if (item.isCurrent) {
                Drawable bg = context.getDrawable(R.drawable.bg_avatar_selected);
                drawable = new LayerDrawable(new Drawable[]{bg, drawable});
            }

            return drawable;
        }

        @Override
        public void onClick(View v) {
            UserSwitcherController.UserRecord user = (UserSwitcherController.UserRecord) v.getTag();
            if (user.isCurrent && !user.isGuest) {
                // Close the switcher if tapping the current user. Guest is excluded because
                // tapping the guest user while it's current clears the session.
                mKeyguardUserSwitcher.hideIfNotSimple(true /* animate */);
            } else if (user.isSwitchToEnabled) {
                if (!user.isAddUser && !user.isRestricted && !user.isDisabledByAdmin) {
                    if (mCurrentUserView != null) {
                        mCurrentUserView.setActivated(false);
                    }
                    v.setActivated(true);
                }
                switchTo(user);
            }
        }
    }

    public static class Container extends FrameLayout {

        private KeyguardUserSwitcher mKeyguardUserSwitcher;

        public Container(Context context, AttributeSet attrs) {
            super(context, attrs);
            setClipChildren(false);
        }

        public void setKeyguardUserSwitcher(KeyguardUserSwitcher keyguardUserSwitcher) {
            mKeyguardUserSwitcher = keyguardUserSwitcher;
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            // Hide switcher if it didn't handle the touch event (and let the event go through).
            if (mKeyguardUserSwitcher != null && !mKeyguardUserSwitcher.isAnimating()) {
                mKeyguardUserSwitcher.hideIfNotSimple(true /* animate */);
            }
            return false;
        }
    }
}
