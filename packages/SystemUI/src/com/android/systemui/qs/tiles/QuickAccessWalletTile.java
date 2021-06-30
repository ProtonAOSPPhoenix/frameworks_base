/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.qs.tiles;

import static android.provider.Settings.Secure.NFC_PAYMENT_DEFAULT_COMPONENT;

import static com.android.systemui.wallet.controller.QuickAccessWalletController.WalletChangeEvent.DEFAULT_PAYMENT_APP_CHANGE;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.service.quickaccesswallet.GetWalletCardsError;
import android.service.quickaccesswallet.GetWalletCardsResponse;
import android.service.quickaccesswallet.QuickAccessWalletClient;
import android.service.quickaccesswallet.WalletCard;
import android.service.quicksettings.Tile;
import android.util.Log;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.jank.InteractionJankMonitor;
import com.android.internal.logging.MetricsLogger;
import com.android.systemui.R;
import com.android.systemui.animation.ActivityLaunchAnimator;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.util.settings.SecureSettings;
import com.android.systemui.wallet.controller.QuickAccessWalletController;
import com.android.systemui.wallet.ui.WalletActivity;

import java.util.List;

import javax.inject.Inject;

/** Quick settings tile: Quick access wallet **/
public class QuickAccessWalletTile extends QSTileImpl<QSTile.State> {

    private static final String TAG = "QuickAccessWalletTile";
    private static final String FEATURE_CHROME_OS = "org.chromium.arc";

    private final CharSequence mLabel = mContext.getString(R.string.wallet_title);
    private final WalletCardRetriever mCardRetriever = new WalletCardRetriever();
    private final KeyguardStateController mKeyguardStateController;
    private final PackageManager mPackageManager;
    private final SecureSettings mSecureSettings;
    private final QuickAccessWalletController mController;

    private WalletCard mSelectedCard;
    private boolean mIsWalletUpdating = true;
    @VisibleForTesting Drawable mCardViewDrawable;

    @Inject
    public QuickAccessWalletTile(
            QSHost host,
            @Background Looper backgroundLooper,
            @Main Handler mainHandler,
            FalsingManager falsingManager,
            MetricsLogger metricsLogger,
            StatusBarStateController statusBarStateController,
            ActivityStarter activityStarter,
            QSLogger qsLogger,
            KeyguardStateController keyguardStateController,
            PackageManager packageManager,
            SecureSettings secureSettings,
            QuickAccessWalletController quickAccessWalletController) {
        super(host, backgroundLooper, mainHandler, falsingManager, metricsLogger,
                statusBarStateController, activityStarter, qsLogger);
        mController = quickAccessWalletController;
        mKeyguardStateController = keyguardStateController;
        mPackageManager = packageManager;
        mSecureSettings = secureSettings;
    }


    @Override
    public State newTileState() {
        State state = new State();
        state.handlesLongClick = false;
        return state;
    }

    @Override
    protected void handleSetListening(boolean listening) {
        super.handleSetListening(listening);
        if (listening) {
            mController.setupWalletChangeObservers(mCardRetriever, DEFAULT_PAYMENT_APP_CHANGE);
            if (!mController.getWalletClient().isWalletServiceAvailable()
                    || !mController.getWalletClient().isWalletFeatureAvailable()) {
                Log.i(TAG, "QAW service is unavailable, recreating the wallet client.");
                mController.reCreateWalletClient();
            }
            mController.queryWalletCards(mCardRetriever);
        }
    }

    @Override
    protected void handleClick(@Nullable View view) {
        ActivityLaunchAnimator.Controller animationController =
                view == null ? null : ActivityLaunchAnimator.Controller.fromView(view,
                        InteractionJankMonitor.CUJ_SHADE_APP_LAUNCH_FROM_QS_TILE);

        mUiHandler.post(() -> {
            if (mSelectedCard != null) {
                Intent intent = new Intent(mContext, WalletActivity.class)
                        .setAction(Intent.ACTION_VIEW)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                if (mKeyguardStateController.isUnlocked()) {
                    mActivityStarter.startActivity(intent, true /* dismissShade */,
                            animationController);
                } else {
                    mHost.collapsePanels();
                    // Do not use ActivityStarter here because the WalletActivity is required to be
                    // started without prompting keyguard when the device is locked.
                    mContext.startActivity(intent);
                }
            } else {
                if (mController.getWalletClient().createWalletIntent() == null) {
                    Log.w(TAG, "Could not get intent of the wallet app.");
                    return;
                }
                mActivityStarter.postStartActivityDismissingKeyguard(
                        mController.getWalletClient().createWalletIntent(),
                        /* delay= */ 0,
                        animationController);
            }
        });
    }

    @Override
    protected void handleUpdateState(State state, Object arg) {
        CharSequence label = mController.getWalletClient().getServiceLabel();
        state.label = label == null ? mLabel : label;
        state.contentDescription = state.label;
        state.icon = ResourceIcon.get(R.drawable.ic_wallet_lockscreen);
        boolean isDeviceLocked = !mKeyguardStateController.isUnlocked();
        if (mController.getWalletClient().isWalletServiceAvailable()
                && mController.getWalletClient().isWalletFeatureAvailable()) {
            if (mSelectedCard != null) {
                if (isDeviceLocked) {
                    state.state = Tile.STATE_INACTIVE;
                    state.secondaryLabel =
                            mContext.getString(R.string.wallet_secondary_label_device_locked);
                    state.sideViewCustomDrawable = null;
                } else {
                    state.state = Tile.STATE_ACTIVE;
                    state.secondaryLabel = mSelectedCard.getContentDescription();
                    state.sideViewCustomDrawable = mCardViewDrawable;
                }
            } else {
                state.state = Tile.STATE_INACTIVE;
                state.secondaryLabel =
                        mContext.getString(
                                mIsWalletUpdating
                                        ? R.string.wallet_secondary_label_updating
                                        : R.string.wallet_secondary_label_no_card);
                state.sideViewCustomDrawable = null;
            }
            state.stateDescription = state.secondaryLabel;
        } else {
            state.state = Tile.STATE_UNAVAILABLE;
            state.secondaryLabel = null;
            state.sideViewCustomDrawable = null;
        }
    }

    @Override
    public int getMetricsCategory() {
        return 0;
    }

    @Override
    public boolean isAvailable() {
        return mPackageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION)
                && !mPackageManager.hasSystemFeature(FEATURE_CHROME_OS)
                && mSecureSettings.getString(NFC_PAYMENT_DEFAULT_COMPONENT) != null;
    }

    @Override
    public Intent getLongClickIntent() {
        return null;
    }

    @Override
    public CharSequence getTileLabel() {
        CharSequence label = mController.getWalletClient().getServiceLabel();
        return label == null ? mLabel : label;
    }

    @Override
    protected void handleDestroy() {
        super.handleDestroy();
        mController.unregisterWalletChangeObservers(DEFAULT_PAYMENT_APP_CHANGE);
    }

    private class WalletCardRetriever implements
            QuickAccessWalletClient.OnWalletCardsRetrievedCallback {

        @Override
        public void onWalletCardsRetrieved(@NonNull GetWalletCardsResponse response) {
            Log.i(TAG, "Successfully retrieved wallet cards.");
            mIsWalletUpdating = false;
            List<WalletCard> cards = response.getWalletCards();
            if (cards.isEmpty()) {
                Log.d(TAG, "No wallet cards exist.");
                mCardViewDrawable = null;
                mSelectedCard = null;
                refreshState();
                return;
            }
            int selectedIndex = response.getSelectedIndex();
            if (selectedIndex >= cards.size()) {
                Log.w(TAG, "Error retrieving cards: Invalid selected card index.");
                mSelectedCard = null;
                mCardViewDrawable = null;
                return;
            }
            mSelectedCard = cards.get(selectedIndex);
            mCardViewDrawable = mSelectedCard.getCardImage().loadDrawable(mContext);
            refreshState();
        }

        @Override
        public void onWalletCardRetrievalError(@NonNull GetWalletCardsError error) {
            mIsWalletUpdating = false;
            mCardViewDrawable = null;
            mSelectedCard = null;
            refreshState();
        }
    }
}
