/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.mediatek.internal.telephony.uicc;

import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.SystemProperties;
import android.telephony.Rlog;
import android.telephony.ServiceState;

import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.uicc.IccCardProxy;
import com.android.internal.telephony.uicc.IccCardStatus.CardState;
import com.android.internal.telephony.uicc.UiccCard;
import com.android.internal.telephony.uicc.UiccController;
import com.mediatek.internal.telephony.cdma.CdmaFeatureOptionUtils;
import com.mediatek.internal.telephony.ltedc.svlte.SvlteModeController;

/**
 * Provide SVLTE UICC card/application related flow control.
 */

public class SvlteUiccController extends Handler {

    private static final boolean DBG = true;
    private static final String LOG_TAG = "SvlteUiccController";

    private static final int EVENT_ICC_CHANGED = 1;

    private UiccController mUiccController;

    /**
     * To make sure SvlteUiccController single instance is created.
     *
     * @return SvlteUiccController instance
     */
    public static SvlteUiccController make() {
        return getInstance();
    }

    /**
     * Singleton to get SvlteUiccController instance.
     *
     * @return SvlteUiccController instance
     */
    public static synchronized SvlteUiccController getInstance() {
        return SingletonHolder.INSTANCE;
    }

    private SvlteUiccController() {
        logd("Constructing");
        mUiccController = UiccController.getInstance();
        mUiccController.registerForIccChanged(this, EVENT_ICC_CHANGED, null);
    }

    /**
     * SvlteUiccController clear up.
     *
     */
    public void dispose() {
        logd("Disposing");
        //Cleanup ICC references
        mUiccController.unregisterForIccChanged(this);
        mUiccController = null;
    }

    /**
     * To check if it is under SvlteTestSimMode.
     *
     * @return true if it is under SvlteTestSimMode
     */
    public boolean isSvlteTestSimMode() {
        String testCardFlag = "persist.sys.forcttestcard";
        String forceCTTestCard = SystemProperties.get(testCardFlag, "0");
        logd("testCardFlag: " + testCardFlag + " = " + forceCTTestCard);
        return forceCTTestCard.equals("1");
    }

    @Override
    public void handleMessage(Message msg) {
        logd("receive message " + msg.what);
        AsyncResult ar = null;

        switch (msg.what) {
            case EVENT_ICC_CHANGED:
                ar = (AsyncResult) msg.obj;
                int index = 0;
                if (ar != null && ar.result instanceof Integer) {
                    index = ((Integer) ar.result).intValue();
                    logd("handleMessage (EVENT_ICC_CHANGED) , index = " + index);
                } else {
                    logd("handleMessage (EVENT_ICC_CHANGED), come from myself");
                }
                // SVLTE
                if (CdmaFeatureOptionUtils.isCdmaLteDcSupport()
                        && UiccController.INDEX_SVLTE == index) {
                    index = SvlteModeController.getCdmaSocketSlotId();
                }
                onIccCardStatusChange(index);
                break;
            default:
                loge("Unhandled message with number: " + msg.what);
                break;
        }
    }

    private void doIccAppTypeSwitch(int phoneId, int radioTech) {
        IccCardProxy iccCard = (IccCardProxy) PhoneFactory.getPhone(phoneId).getIccCard();
        iccCard.setVoiceRadioTech(radioTech);
    }

    private void onIccCardStatusChange(int slotId) {
        if (DBG) {
            logd("slotId: " + slotId);
        }
        if (isSimReady(slotId) && isUsimTestSim(slotId)) {
            doOP09SvlteTestSimAppTypeSwitch(slotId);
        }
    }

    private void doOP09SvlteTestSimAppTypeSwitch(int slotId) {
        //Workaround solution for OP09 SVLTE TDD ONLY USIM test SIM
        if (DBG) {
            logd("OP09 Switch gsm radio technology for usim in slot: " + slotId);
        }
        doIccAppTypeSwitch(slotId, ServiceState.RIL_RADIO_TECHNOLOGY_GSM);
    }

    private boolean isUsimTestSim(int slotId) {
        return (CdmaFeatureOptionUtils.isCdmaLteDcSupport()
                && (isOP09())
                && (SvlteModeController.getCdmaSocketSlotId() == slotId)
                && (SvlteUiccUtils.getInstance().isUsimOnly(slotId))
                && (isSvlteTestSimMode()));
    }

    private boolean isOP09() {
        String OPTR = SystemProperties.get("ro.operator.optr");
        String OPSEG = SystemProperties.get("ro.operator.seg");
        logd("OPTR = " + OPTR + " OPSEG = "+ OPSEG);
        return ("OP09".equals(OPTR) && "SEGDEFAULT".equals(OPSEG));
    }

    private boolean isSimReady(int slotId) {
        UiccCard newCard = mUiccController.getUiccCard(slotId);
        return ((null != newCard)
                && (CardState.CARDSTATE_PRESENT == newCard.getCardState()));
    }

    /**
     * Create SvlteUiccApplicationUpdateStrategy instance.
     *
     * @hide
     */
    private static class SingletonHolder {
        public static final SvlteUiccController INSTANCE =
                new SvlteUiccController();
    }

    /**
     * Log level.
     *
     * @hide
     */
    private void logd(String msg) {
        Rlog.d(LOG_TAG, msg);
    }

    private void loge(String msg) {
        Rlog.d(LOG_TAG, msg);
    }

}
