/*
 * Copyright (C) 2014 MediaTek Inc.
 * Modification based on code covered by the mentioned copyright
 * and/or permission notice(s).
 */
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

import android.content.Context;
import android.os.AsyncResult;
import android.os.Message;
import android.telephony.Rlog;

import com.android.internal.telephony.CommandException;
import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.RILConstants;
import com.android.internal.telephony.uicc.IccCardProxy;
import com.android.internal.telephony.uicc.UiccCardApplication;
import com.android.internal.telephony.uicc.UiccController;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * Adapter class for IccCardProxy under SVLTE/SRLTE.
 */

public class SvlteIccCardProxy extends IccCardProxy {
    private static final boolean DBG = true;
    private static final String LOG_TAG = "SvlteIccCardProxy";

    //Align with IccCardProxy
    private static final int EVENT_ICC_LOCKED = 5;
    private static final int EVENT_APP_READY = 6;
    private static final int EVENT_SVLTE_ICC_LOCKED = 103;

    //To be adapted under SVLTE/SRLTE
    private Integer mPhoneId = 0;
    private final Object mLock = new Object();
    private UiccController mSvlteUiccController = null;
    private UiccCardApplication mSvlteUiccApplication = null;

    /*Construnct function*/
    public SvlteIccCardProxy(Context context, CommandsInterface ci, int phoneId) {
        super(context, ci, phoneId);
        if (DBG) {
            log("ctor: phoneId=" + phoneId);
        }
        mPhoneId = phoneId;

        mSvlteUiccController = UiccController.getInstance();
    }

    @Override
    public void dispose() {
        synchronized (mLock) {
            log("Disposing");
            // Cleanup icc references
            mSvlteUiccController = null;
            mSvlteUiccApplication = null;
        }
        super.dispose();
    }

    @Override
    public void handleMessage(Message msg) {
        log("receive message " + msg.what);

        switch (msg.what) {
            case EVENT_SVLTE_ICC_LOCKED:
                log("receive EVENT_SVLTE_ICC_LOCKED ");
                msg.what = EVENT_ICC_LOCKED;
                super.handleMessage(msg);
                break;
            case EVENT_ICC_LOCKED:
                if (isSvlteCT4G()) {
                    log("receive EVENT_ICC_LOCKED, SVLTE CT 4G is true, so do nothing");
                } else {
                    super.handleMessage(msg);
                }
                break;
            default:
                super.handleMessage(msg);
                break;
        }
    }

    @Override
    public void supplyPin(String pin, Message onComplete) {
        synchronized (mLock) {
            if (isSvlteCT4G()) {
                refreshUiccApplication();
                if (null != mSvlteUiccApplication) {
                    mSvlteUiccApplication.supplyPin(pin, onComplete);
                } else if (onComplete != null) {
                    supplyPinFail(onComplete, "supplyPin");
                    return;
                }
            } else {
                super.supplyPin(pin, onComplete);
            }
        }
    }

    @Override
    public void supplyPuk(String puk, String newPin, Message onComplete) {
        synchronized (mLock) {
            if (isSvlteCT4G()) {
                refreshUiccApplication();
                if (null != mSvlteUiccApplication) {
                    mSvlteUiccApplication.supplyPuk(puk, newPin, onComplete);
                } else if (onComplete != null) {
                    supplyPinFail(onComplete, "supplyPuk");
                    return;
                }
            } else {
                super.supplyPuk(puk, newPin, onComplete);
            }
        }
    }

    @Override
    public void supplyPin2(String pin2, Message onComplete) {
        synchronized (mLock) {
            if (isSvlteCT4G()) {
                refreshUiccApplication();
                if (null != mSvlteUiccApplication) {
                    mSvlteUiccApplication.supplyPin2(pin2, onComplete);
                } else if (onComplete != null) {
                    supplyPinFail(onComplete, "supplyPin2");
                    return;
                }
            } else {
                super.supplyPin2(pin2, onComplete);
            }
        }
    }

    @Override
    public void supplyPuk2(String puk2, String newPin2, Message onComplete) {
        synchronized (mLock) {
            if (isSvlteCT4G()) {
                refreshUiccApplication();
                if (null != mSvlteUiccApplication) {
                    mSvlteUiccApplication.supplyPuk2(puk2, newPin2, onComplete);
                } else if (onComplete != null) {
                    supplyPinFail(onComplete, "supplyPuk2");
                    return;
                }
            } else {
                super.supplyPuk2(puk2, newPin2, onComplete);
            }
        }
    }

    @Override
    public void setIccLockEnabled(boolean enabled, String password, Message onComplete) {
        synchronized (mLock) {
            log("Try to setIccLockEnabled enabled = " + enabled + " onComplete = " + onComplete);
            if (isSvlteCT4G()) {
                refreshUiccApplication();
                if (null != mSvlteUiccApplication) {
                    mSvlteUiccApplication.setIccLockEnabled(enabled, password, onComplete);
                } else if (onComplete != null) {
                    supplyPinFail(onComplete, "setIccLockEnabled");
                    return;
                }
            } else {
                super.setIccLockEnabled(enabled, password, onComplete);
            }
        }
    }


    @Override
    public void changeIccLockPassword(String oldPassword, String newPassword, Message onComplete) {
        synchronized (mLock) {
            if (isSvlteCT4G()) {
                refreshUiccApplication();
                if (null != mSvlteUiccApplication) {
                    mSvlteUiccApplication.changeIccLockPassword(oldPassword,
                            newPassword, onComplete);
                } else if (onComplete != null) {
                    supplyPinFail(onComplete, "changeIccLockPassword");
                    return;
                }
            } else {
                super.changeIccLockPassword(oldPassword, newPassword, onComplete);
            }
        }
    }

    @Override
    public boolean getIccLockEnabled() {
        synchronized (mLock) {
            if (isSvlteCT4G()) {
                /* defaults to false, if ICC is absent/deactivated */
                Boolean retValue = mSvlteUiccApplication != null ?
                        mSvlteUiccApplication.getIccLockEnabled() : false;
                return retValue;
            } else {
                return super.getIccLockEnabled();
            }
        }
    }

    /**
     * To adapt IccCardProxy dump().
     * @param fd for file descriptor
     * @param pw for print writer
     * @param args for arguments
     */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("SvlteIccCardProxyAdapter: " + this);
        pw.println("mSvlteUiccController: " + mSvlteUiccController);
        pw.println("mSvlteUiccApplication: " + mSvlteUiccApplication);
        super.dump(fd, pw, args);
    }

    private void registerUiccCardEvents() {
        if (mSvlteUiccApplication != null) {
            mSvlteUiccApplication.registerForReady(this, EVENT_SVLTE_ICC_LOCKED, null);
        }
    }

    private void unregisterUiccCardEvents() {
        if (mSvlteUiccApplication != null) {
            mSvlteUiccApplication.unregisterForLocked(this);
        }
    }

    private void refreshUiccApplication() {
        UiccCardApplication newApp = mSvlteUiccController.getUiccCardApplication(mPhoneId,
                getRemoteSimPinAppType());
        if (mSvlteUiccApplication != newApp) {
            if (DBG) {
                log("Icc app changed. Reregestering.");
            }
            unregisterUiccCardEvents();
            mSvlteUiccApplication = newApp;
            registerUiccCardEvents();
        }
        log("refreshUiccApplication() to 3GPP done.");
    }

    private boolean isSvlteCT4G() {
        return SvlteUiccUtils.getInstance().isRemoteSimSlot(mPhoneId);
    }

    private int getRemoteSimPinAppType() {
        log("RemoteSimPinAppType is 3GPP.");
        return UiccController.APP_FAM_3GPP;
    }

    private void supplyPinFail(Message onComplete, String functionName) {
        Exception e = CommandException.fromRilErrno(RILConstants.RADIO_NOT_AVAILABLE);
        log("Fail to " +  functionName + ", hasIccCard = " + hasIccCard());
        AsyncResult.forMessage(onComplete).exception = e;
        onComplete.sendToTarget();
    }

    private void log(String s) {
        Rlog.d(LOG_TAG, s + " (slot " + mPhoneId + ")");
    }

    private void loge(String msg) {
        Rlog.e(LOG_TAG, msg + " (slot " + mPhoneId + ")");
    }
}
