/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.internal.telephony;

import static com.android.internal.telephony.RILConstants.*;
import static com.android.internal.telephony.MtkRILConstants.*;
import static com.android.internal.util.Preconditions.checkNotNull;

import android.content.Context;
import android.hardware.radio.V1_0.Carrier;
import android.hardware.radio.V1_0.CarrierRestrictions;
import android.hardware.radio.V1_0.CdmaBroadcastSmsConfigInfo;
import android.hardware.radio.V1_0.CdmaSmsAck;
import android.hardware.radio.V1_0.CdmaSmsMessage;
import android.hardware.radio.V1_0.CdmaSmsWriteArgs;
import android.hardware.radio.V1_0.CellInfoCdma;
import android.hardware.radio.V1_0.CellInfoGsm;
import android.hardware.radio.V1_0.CellInfoLte;
import android.hardware.radio.V1_0.CellInfoType;
import android.hardware.radio.V1_0.CellInfoWcdma;
import android.hardware.radio.V1_0.DataProfileInfo;
import android.hardware.radio.V1_0.Dial;
import android.hardware.radio.V1_0.GsmBroadcastSmsConfigInfo;
import android.hardware.radio.V1_0.GsmSmsMessage;
import android.hardware.radio.V1_0.HardwareConfigModem;
import android.hardware.radio.V1_0.IRadio;
import android.hardware.radio.V1_0.IccIo;
import android.hardware.radio.V1_0.ImsSmsMessage;
import android.hardware.radio.V1_0.LceDataInfo;
import android.hardware.radio.V1_0.MvnoType;
import android.hardware.radio.V1_0.NvWriteItem;
import android.hardware.radio.V1_0.RadioError;
import android.hardware.radio.V1_0.RadioIndicationType;
import android.hardware.radio.V1_0.RadioResponseInfo;
import android.hardware.radio.V1_0.RadioResponseType;
import android.hardware.radio.V1_0.ResetNvType;
import android.hardware.radio.V1_0.SelectUiccSub;
import android.hardware.radio.V1_0.SetupDataCallResult;
import android.hardware.radio.V1_0.SimApdu;
import android.hardware.radio.V1_0.SmsWriteArgs;
import android.hardware.radio.V1_0.UusInfo;
import android.hardware.radio.deprecated.V1_0.IOemHook;
import android.net.ConnectivityManager;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.HwBinder;
import android.os.Message;
import android.os.Parcel;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.WorkSource;
import android.service.carrier.CarrierIdentifier;
import android.telephony.CellInfo;
import android.telephony.ClientRequestStats;
import android.telephony.ImsiEncryptionInfo;
import android.telephony.ModemActivityInfo;
import android.telephony.NeighboringCellInfo;
import android.telephony.NetworkScanRequest;
import android.telephony.PhoneNumberUtils;
import android.telephony.RadioAccessFamily;
import android.telephony.RadioAccessSpecifier;
import android.telephony.RadioNetworkConstants.RadioAccessNetworks;
import android.telephony.Rlog;
import android.telephony.SignalStrength;
import android.telephony.SmsManager;
import android.telephony.TelephonyHistogram;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.telephony.cdma.CdmaInformationRecords;
import com.android.internal.telephony.cdma.CdmaSmsBroadcastConfigInfo;
import com.android.internal.telephony.dataconnection.DataCallResponse;
import com.android.internal.telephony.dataconnection.DataProfile;
import com.android.internal.telephony.gsm.SmsBroadcastConfigInfo;
import com.android.internal.telephony.metrics.TelephonyMetrics;
import com.android.internal.telephony.nano.TelephonyProto.SmsSession;
import com.android.internal.telephony.uicc.IccUtils;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * RIL implementation of the CommandsInterface.
 *
 * {@hide}
 */
public class RIL extends BaseCommands implements CommandsInterface {
    static final String RILJ_LOG_TAG = "RILJ";
    // Have a separate wakelock instance for Ack
    static final String RILJ_ACK_WAKELOCK_NAME = "RILJ_ACK_WL";
    public static final boolean RILJ_LOGD = true;
    public static final boolean RILJ_LOGV = false; // STOPSHIP if true
    static final int RIL_HISTOGRAM_BUCKET_COUNT = 5;

    /**
     * Wake lock timeout should be longer than the longest timeout in
     * the vendor ril.
     */
    private static final int DEFAULT_WAKE_LOCK_TIMEOUT_MS = 60000;

    // Wake lock default timeout associated with ack
    private static final int DEFAULT_ACK_WAKE_LOCK_TIMEOUT_MS = 200;

    private static final int DEFAULT_BLOCKING_MESSAGE_RESPONSE_TIMEOUT_MS = 2000;

    // Variables used to differentiate ack messages from request while calling clearWakeLock()
    public static final int INVALID_WAKELOCK = -1;
    public static final int FOR_WAKELOCK = 0;
    public static final int FOR_ACK_WAKELOCK = 1;
    private final ClientWakelockTracker mClientWakelockTracker = new ClientWakelockTracker();

    //***** Instance Variables

    final WakeLock mWakeLock;           // Wake lock associated with request/response
    final WakeLock mAckWakeLock;        // Wake lock associated with ack sent
    final int mWakeLockTimeout;         // Timeout associated with request/response
    final int mAckWakeLockTimeout;      // Timeout associated with ack sent
    // The number of wakelock requests currently active.  Don't release the lock
    // until dec'd to 0
    int mWakeLockCount;

    // Variables used to identify releasing of WL on wakelock timeouts
    volatile int mWlSequenceNum = 0;
    volatile int mAckWlSequenceNum = 0;

    SparseArray<RILRequest> mRequestList = new SparseArray<RILRequest>();
    static SparseArray<TelephonyHistogram> mRilTimeHistograms = new
            SparseArray<TelephonyHistogram>();

    Object[]     mLastNITZTimeInfo;

    // When we are testing emergency calls
    AtomicBoolean mTestingEmergencyCall = new AtomicBoolean(false);

    final Integer mPhoneId;

    /* default work source which will blame phone process */
    protected WorkSource mRILDefaultWorkSource;

    /* Worksource containing all applications causing wakelock to be held */
    private WorkSource mActiveWakelockWorkSource;

    /** Telephony metrics instance for logging metrics event */
    private TelephonyMetrics mMetrics = TelephonyMetrics.getInstance();

    protected boolean mIsMobileNetworkSupported;
    RadioResponse mRadioResponse;
    RadioIndication mRadioIndication;
    volatile IRadio mRadioProxy = null;
    OemHookResponse mOemHookResponse;
    OemHookIndication mOemHookIndication;
    volatile IOemHook mOemHookProxy = null;
    final AtomicLong mRadioProxyCookie = new AtomicLong(0);
    final RadioProxyDeathRecipient mRadioProxyDeathRecipient;
    final RilHandler mRilHandler;

    //***** Events
    static final int EVENT_WAKE_LOCK_TIMEOUT    = 2;
    static final int EVENT_ACK_WAKE_LOCK_TIMEOUT    = 4;
    static final int EVENT_BLOCKING_RESPONSE_TIMEOUT = 5;
    static final int EVENT_RADIO_PROXY_DEAD     = 6;

    //***** Constants

    static final String[] HIDL_SERVICE_NAME = {"slot1", "slot2", "slot3"};

    static final int IRADIO_GET_SERVICE_DELAY_MILLIS = 4 * 1000;

    public static List<TelephonyHistogram> getTelephonyRILTimingHistograms() {
        List<TelephonyHistogram> list;
        synchronized (mRilTimeHistograms) {
            list = new ArrayList<>(mRilTimeHistograms.size());
            for (int i = 0; i < mRilTimeHistograms.size(); i++) {
                TelephonyHistogram entry = new TelephonyHistogram(mRilTimeHistograms.valueAt(i));
                list.add(entry);
            }
        }
        return list;
    }

    class RilHandler extends Handler {
        //***** Handler implementation
        @Override public void
        handleMessage(Message msg) {
            RILRequest rr;

            switch (msg.what) {
                case EVENT_WAKE_LOCK_TIMEOUT:
                    // Haven't heard back from the last request.  Assume we're
                    // not getting a response and  release the wake lock.

                    // The timer of WAKE_LOCK_TIMEOUT is reset with each
                    // new send request. So when WAKE_LOCK_TIMEOUT occurs
                    // all requests in mRequestList already waited at
                    // least DEFAULT_WAKE_LOCK_TIMEOUT_MS but no response.
                    //
                    // Note: Keep mRequestList so that delayed response
                    // can still be handled when response finally comes.

                    synchronized (mRequestList) {
                        if (msg.arg1 == mWlSequenceNum && clearWakeLock(FOR_WAKELOCK)) {
                            if (RILJ_LOGD) {
                                int count = mRequestList.size();
                                Rlog.d(RILJ_LOG_TAG, "WAKE_LOCK_TIMEOUT " +
                                        " mRequestList=" + count);
                                for (int i = 0; i < count; i++) {
                                    rr = mRequestList.valueAt(i);
                                    Rlog.d(RILJ_LOG_TAG, i + ": [" + rr.mSerial + "] "
                                            + requestToString(rr.mRequest));
                                }
                            }
                        }
                    }
                    break;

                case EVENT_ACK_WAKE_LOCK_TIMEOUT:
                    if (msg.arg1 == mAckWlSequenceNum && clearWakeLock(FOR_ACK_WAKELOCK)) {
                        if (RILJ_LOGV) {
                            Rlog.d(RILJ_LOG_TAG, "ACK_WAKE_LOCK_TIMEOUT");
                        }
                    }
                    break;

                case EVENT_BLOCKING_RESPONSE_TIMEOUT:
                    int serial = msg.arg1;
                    rr = findAndRemoveRequestFromList(serial);
                    // If the request has already been processed, do nothing
                    if(rr == null) {
                        break;
                    }

                    //build a response if expected
                    if (rr.mResult != null) {
                        Object timeoutResponse = getResponseForTimedOutRILRequest(rr);
                        AsyncResult.forMessage( rr.mResult, timeoutResponse, null);
                        rr.mResult.sendToTarget();
                        mMetrics.writeOnRilTimeoutResponse(mPhoneId, rr.mSerial, rr.mRequest);
                    }

                    decrementWakeLock(rr);
                    rr.release();
                    break;

                case EVENT_RADIO_PROXY_DEAD:
                    riljLog("handleMessage: EVENT_RADIO_PROXY_DEAD cookie = " + msg.obj +
                            " mRadioProxyCookie = " + mRadioProxyCookie.get());
                    if ((long) msg.obj == mRadioProxyCookie.get()) {
                        resetProxyAndRequestList();

                        // todo: rild should be back up since message was sent with a delay. this is
                        // a hack.
                        getRadioProxy(null);
                        getOemHookProxy(null);
                    }
                    break;
            }
        }
    }

    /**
     * In order to prevent calls to Telephony from waiting indefinitely
     * low-latency blocking calls will eventually time out. In the event of
     * a timeout, this function generates a response that is returned to the
     * higher layers to unblock the call. This is in lieu of a meaningful
     * response.
     * @param rr The RIL Request that has timed out.
     * @return A default object, such as the one generated by a normal response
     * that is returned to the higher layers.
     **/
    private static Object getResponseForTimedOutRILRequest(RILRequest rr) {
        if (rr == null ) return null;

        Object timeoutResponse = null;
        switch(rr.mRequest) {
            case RIL_REQUEST_GET_ACTIVITY_INFO:
                timeoutResponse = new ModemActivityInfo(
                        0, 0, 0, new int [ModemActivityInfo.TX_POWER_LEVELS], 0, 0);
                break;
        };
        return timeoutResponse;
    }

    final class RadioProxyDeathRecipient implements HwBinder.DeathRecipient {
        @Override
        public void serviceDied(long cookie) {
            // Deal with service going away
            riljLog("serviceDied");
            // todo: temp hack to send delayed message so that rild is back up by then
            //mRilHandler.sendMessage(mRilHandler.obtainMessage(EVENT_RADIO_PROXY_DEAD, cookie));
            mRilHandler.sendMessageDelayed(
                    mRilHandler.obtainMessage(EVENT_RADIO_PROXY_DEAD, cookie),
                    IRADIO_GET_SERVICE_DELAY_MILLIS);
        }
    }

    protected void resetProxyAndRequestList() {
        mRadioProxy = null;
        mOemHookProxy = null;

        // increment the cookie so that death notification can be ignored
        mRadioProxyCookie.incrementAndGet();

        setRadioState(RadioState.RADIO_UNAVAILABLE);

        RILRequest.resetSerial();
        // Clear request list on close
        clearRequestList(RADIO_NOT_AVAILABLE, false);

        // todo: need to get service right away so setResponseFunctions() can be called for
        // unsolicited indications. getService() is not a blocking call, so it doesn't help to call
        // it here. Current hack is to call getService() on death notification after a delay.
    }

    private IRadio getRadioProxy(Message result) {
        if (!mIsMobileNetworkSupported) {
            if (RILJ_LOGV) riljLog("getRadioProxy: Not calling getService(): wifi-only");
            if (result != null) {
                AsyncResult.forMessage(result, null,
                        CommandException.fromRilErrno(RADIO_NOT_AVAILABLE));
                result.sendToTarget();
            }
            return null;
        }

        if (mRadioProxy != null) {
            return mRadioProxy;
        }

        try {
            mRadioProxy = IRadio.getService(HIDL_SERVICE_NAME[mPhoneId == null ? 0 : mPhoneId]);
            if (mRadioProxy != null) {
                mRadioProxy.linkToDeath(mRadioProxyDeathRecipient,
                        mRadioProxyCookie.incrementAndGet());
                mRadioProxy.setResponseFunctions(mRadioResponse, mRadioIndication);
            } else {
                riljLoge("getRadioProxy: mRadioProxy == null");
            }
        } catch (RemoteException | RuntimeException e) {
            mRadioProxy = null;
            riljLoge("RadioProxy getService/setResponseFunctions: " + e);
        }

        if (mRadioProxy == null) {
            if (result != null) {
                AsyncResult.forMessage(result, null,
                        CommandException.fromRilErrno(RADIO_NOT_AVAILABLE));
                result.sendToTarget();
            }

            // if service is not up, treat it like death notification to try to get service again
            mRilHandler.sendMessageDelayed(
                    mRilHandler.obtainMessage(EVENT_RADIO_PROXY_DEAD,
                            mRadioProxyCookie.incrementAndGet()),
                    IRADIO_GET_SERVICE_DELAY_MILLIS);
        }

        return mRadioProxy;
    }

    private IOemHook getOemHookProxy(Message result) {
        if (!mIsMobileNetworkSupported) {
            if (RILJ_LOGV) riljLog("getOemHookProxy: Not calling getService(): wifi-only");
            if (result != null) {
                AsyncResult.forMessage(result, null,
                        CommandException.fromRilErrno(RADIO_NOT_AVAILABLE));
                result.sendToTarget();
            }
            return null;
        }

        if (mOemHookProxy != null) {
            return mOemHookProxy;
        }

        try {
            mOemHookProxy = IOemHook.getService(
                    HIDL_SERVICE_NAME[mPhoneId == null ? 0 : mPhoneId]);
            if (mOemHookProxy != null) {
                // not calling linkToDeath() as ril service runs in the same process and death
                // notification for that should be sufficient
                mOemHookProxy.setResponseFunctions(mOemHookResponse, mOemHookIndication);
            } else {
                riljLoge("getOemHookProxy: mOemHookProxy == null");
            }
        } catch (RemoteException | RuntimeException e) {
            mOemHookProxy = null;
            riljLoge("OemHookProxy getService/setResponseFunctions: " + e);
        }

        if (mOemHookProxy == null) {
            if (result != null) {
                AsyncResult.forMessage(result, null,
                        CommandException.fromRilErrno(RADIO_NOT_AVAILABLE));
                result.sendToTarget();
            }

            // if service is not up, treat it like death notification to try to get service again
            mRilHandler.sendMessageDelayed(
                    mRilHandler.obtainMessage(EVENT_RADIO_PROXY_DEAD,
                            mRadioProxyCookie.incrementAndGet()),
                    IRADIO_GET_SERVICE_DELAY_MILLIS);
        }

        return mOemHookProxy;
    }

    //***** Constructors

    public RIL(Context context, int preferredNetworkType, int cdmaSubscription) {
        this(context, preferredNetworkType, cdmaSubscription, null);
    }

    public RIL(Context context, int preferredNetworkType,
            int cdmaSubscription, Integer instanceId) {
        super(context);
        if (RILJ_LOGD) {
            riljLog("RIL: init preferredNetworkType=" + preferredNetworkType
                    + " cdmaSubscription=" + cdmaSubscription + ")");
        }

        mContext = context;
        mCdmaSubscription  = cdmaSubscription;
        mPreferredNetworkType = preferredNetworkType;
        mPhoneType = RILConstants.NO_PHONE;
        mPhoneId = instanceId;

        ConnectivityManager cm = (ConnectivityManager)context.getSystemService(
                Context.CONNECTIVITY_SERVICE);
        mIsMobileNetworkSupported = cm.isNetworkSupported(ConnectivityManager.TYPE_MOBILE);

        mRadioResponse = createRadioResponse(this);
        mRadioIndication = createRadioIndication(this);
        mOemHookResponse = new OemHookResponse(this);
        mOemHookIndication = new OemHookIndication(this);
        mRilHandler = new RilHandler();
        mRadioProxyDeathRecipient = new RadioProxyDeathRecipient();

        PowerManager pm = (PowerManager)context.getSystemService(Context.POWER_SERVICE);
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, RILJ_LOG_TAG);
        mWakeLock.setReferenceCounted(false);
        mAckWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, RILJ_ACK_WAKELOCK_NAME);
        mAckWakeLock.setReferenceCounted(false);
        mWakeLockTimeout = SystemProperties.getInt(TelephonyProperties.PROPERTY_WAKE_LOCK_TIMEOUT,
                DEFAULT_WAKE_LOCK_TIMEOUT_MS);
        mAckWakeLockTimeout = SystemProperties.getInt(
                TelephonyProperties.PROPERTY_WAKE_LOCK_TIMEOUT, DEFAULT_ACK_WAKE_LOCK_TIMEOUT_MS);
        mWakeLockCount = 0;
        mRILDefaultWorkSource = new WorkSource(context.getApplicationInfo().uid,
                context.getPackageName());

        TelephonyDevController tdc = TelephonyDevController.getInstance();
        tdc.registerRIL(this);

        // set radio callback; needed to set RadioIndication callback (should be done after
        // wakelock stuff is initialized above as callbacks are received on separate binder threads)
        getRadioProxy(null);
        getOemHookProxy(null);
    }

    protected RadioResponse createRadioResponse(RIL ril) {
        return new RadioResponse(ril);
    }

    protected RadioIndication createRadioIndication(RIL ril) {
        return new RadioIndication(ril);
    }

    @Override public void
    setOnNITZTime(Handler h, int what, Object obj) {
        super.setOnNITZTime(h, what, obj);

        // Send the last NITZ time if we have it
        if (mLastNITZTimeInfo != null) {
            mNITZTimeRegistrant
                .notifyRegistrant(
                    new AsyncResult (null, mLastNITZTimeInfo, null));
        }
    }

    private void addRequest(RILRequest rr) {
        acquireWakeLock(rr, FOR_WAKELOCK);
        synchronized (mRequestList) {
            rr.mStartTimeMs = SystemClock.elapsedRealtime();
            mRequestList.append(rr.mSerial, rr);
        }
    }

    private RILRequest obtainRequest(int request, Message result, WorkSource workSource) {
        RILRequest rr = RILRequest.obtain(request, result, workSource);
        addRequest(rr);
        return rr;
    }

    protected int obtainRequestSerial(int request, Message result, WorkSource workSource) {
        RILRequest rr = RILRequest.obtain(request, result, workSource);
        addRequest(rr);
        return rr.mSerial;
    }

    private void handleRadioProxyExceptionForRR(RILRequest rr, String caller, Exception e) {
        riljLoge(caller + ": " + e);
        resetProxyAndRequestList();

        // service most likely died, handle exception like death notification to try to get service
        // again
        mRilHandler.sendMessageDelayed(
                mRilHandler.obtainMessage(EVENT_RADIO_PROXY_DEAD,
                        mRadioProxyCookie.incrementAndGet()),
                IRADIO_GET_SERVICE_DELAY_MILLIS);
    }

    private String convertNullToEmptyString(String string) {
        return string != null ? string : "";
    }

    @Override
    public void
    getIccCardStatus(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_GET_SIM_STATUS, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            try {
                radioProxy.getIccCardStatus(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "getIccCardStatus", e);
            }
        }
    }

    @Override public void
    supplyIccPin(String pin, Message result) {
        supplyIccPinForApp(pin, null, result);
    }

    @Override public void
    supplyIccPinForApp(String pin, String aid, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_ENTER_SIM_PIN, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " aid = " + aid);
            }

            try {
                radioProxy.supplyIccPinForApp(rr.mSerial,
                        convertNullToEmptyString(pin),
                        convertNullToEmptyString(aid));
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "supplyIccPinForApp", e);
            }
        }
    }

    @Override
    public void supplyIccPuk(String puk, String newPin, Message result) {
        supplyIccPukForApp(puk, newPin, null, result);
    }

    @Override
    public void supplyIccPukForApp(String puk, String newPin, String aid, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_ENTER_SIM_PUK, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " aid = " + aid);
            }

            try {
                radioProxy.supplyIccPukForApp(rr.mSerial,
                        convertNullToEmptyString(puk),
                        convertNullToEmptyString(newPin),
                        convertNullToEmptyString(aid));
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "supplyIccPukForApp", e);
            }
        }
    }

    @Override public void
    supplyIccPin2(String pin, Message result) {
        supplyIccPin2ForApp(pin, null, result);
    }

    @Override public void
    supplyIccPin2ForApp(String pin, String aid, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_ENTER_SIM_PIN2, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " aid = " + aid);
            }

            try {
                radioProxy.supplyIccPin2ForApp(rr.mSerial,
                        convertNullToEmptyString(pin),
                        convertNullToEmptyString(aid));
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "supplyIccPin2ForApp", e);
            }
        }
    }

    @Override public void
    supplyIccPuk2(String puk2, String newPin2, Message result) {
        supplyIccPuk2ForApp(puk2, newPin2, null, result);
    }

    @Override public void
    supplyIccPuk2ForApp(String puk, String newPin2, String aid, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_ENTER_SIM_PUK2, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " aid = " + aid);
            }

            try {
                radioProxy.supplyIccPuk2ForApp(rr.mSerial,
                        convertNullToEmptyString(puk),
                        convertNullToEmptyString(newPin2),
                        convertNullToEmptyString(aid));
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "supplyIccPuk2ForApp", e);
            }
        }
    }

    @Override public void
    changeIccPin(String oldPin, String newPin, Message result) {
        changeIccPinForApp(oldPin, newPin, null, result);
    }

    @Override public void
    changeIccPinForApp(String oldPin, String newPin, String aid, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_CHANGE_SIM_PIN, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest) + " oldPin = "
                        + oldPin + " newPin = " + newPin + " aid = " + aid);
            }

            try {
                radioProxy.changeIccPinForApp(rr.mSerial,
                        convertNullToEmptyString(oldPin),
                        convertNullToEmptyString(newPin),
                        convertNullToEmptyString(aid));
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "changeIccPinForApp", e);
            }
        }
    }

    @Override public void
    changeIccPin2(String oldPin2, String newPin2, Message result) {
        changeIccPin2ForApp(oldPin2, newPin2, null, result);
    }

    @Override public void
    changeIccPin2ForApp(String oldPin2, String newPin2, String aid, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_CHANGE_SIM_PIN2, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest) + " oldPin = "
                        + oldPin2 + " newPin = " + newPin2 + " aid = " + aid);
            }

            try {
                radioProxy.changeIccPin2ForApp(rr.mSerial,
                        convertNullToEmptyString(oldPin2),
                        convertNullToEmptyString(newPin2),
                        convertNullToEmptyString(aid));
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "changeIccPin2ForApp", e);
            }
        }
    }

@Override
    public void setTrm(int mode, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_SET_TRM, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                mtkRiljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
            }

            try {
                radioProxy.setTrm(rr.mSerial, mode);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "setTrm", e);
            }
        }
    }

    // MTK-START: SIM
    /**
     * {@inheritDoc}
     */
    public void getATR(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(MtkRILConstants.RIL_REQUEST_SIM_GET_ATR, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) RiljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            try {
                radioProxy.getATR(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "getATR", e);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    public void setSimPower(int mode, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(MtkRILConstants.RIL_REQUEST_SET_SIM_POWER, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) RiljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            try {
                radioProxy.setSimPower(rr.mSerial, mode);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "setSimPower", e);
            }
        }
    }

    protected RegistrantList mVirtualSimOn = new RegistrantList();
    protected RegistrantList mVirtualSimOff = new RegistrantList();
    protected RegistrantList mImeiLockRegistrant = new RegistrantList();
    protected RegistrantList mImsiRefreshDoneRegistrant = new RegistrantList();

    public void registerForVirtualSimOn(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        mVirtualSimOn.add(r);
    }

    public void unregisterForVirtualSimOn(Handler h) {
        mVirtualSimOn.remove(h);
    }

    public void registerForVirtualSimOff(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        mVirtualSimOff.add(r);
    }

    public void unregisterForVirtualSimOff(Handler h) {
        mVirtualSimOff.remove(h);
    }

    public void registerForIMEILock(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        mImeiLockRegistrant.add(r);
    }

    public void unregisterForIMEILock(Handler h) {
        mImeiLockRegistrant.remove(h);
    }

    public void registerForImsiRefreshDone(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        mImsiRefreshDoneRegistrant.add(r);
    }

    public void unregisterForImsiRefreshDone(Handler h) {
        mImsiRefreshDoneRegistrant.remove(h);
    }

    // MTK-END

    // MTK-START: SIM GBA
    /**
     * Convert to SimAuthStructure defined in types.hal
     * @param sessionId sessionId
     * @param mode Auth mode
     * @param tag Used for GBA mode
     * @param param1
     * @param param2
     * @return A converted SimAuthStructure for hal
     */
    private SimAuthStructure convertToHalSimAuthStructure(int sessionId, int mode,
            int tag, String param1, String param2) {
        SimAuthStructure simAuth = new SimAuthStructure();
        simAuth.sessionId = sessionId;
        simAuth.mode = mode;
        if (param1 != null && param1.length() > 0) {
            String length = Integer.toHexString(param1.length() / 2);
            length = (((length.length() % 2 == 1) ? "0" : "") + length);
            // Session id is equal to 0, for backward compability, we use old AT command
            // old AT command no need to include param's length
            simAuth.param1 = convertNullToEmptyString(((sessionId == 0) ?
                    param1 : (length + param1)));
        } else {
            simAuth.param1 = convertNullToEmptyString(param1);
        }

        // Calcuate param2 length in byte length
        if (param2 != null && param2.length() > 0) {
            String length = Integer.toHexString(param2.length() / 2);
            length = (((length.length() % 2 == 1) ? "0" : "") + length);
            // Session id is equal to 0, for backward compability, we use old AT command
            // old AT command no need to include param's length
            simAuth.param2 = convertNullToEmptyString(((sessionId == 0) ?
                    param2 : (length + param2)));
        } else {
            simAuth.param2 = convertNullToEmptyString(param2);
        }
        if (mode == 1) {
            simAuth.tag = tag;
        }
        return simAuth;
    }

    /**
     * {@inheritDoc}
     */
    public void doGeneralSimAuthentication(int sessionId, int mode, int tag,
            String param1, String param2, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(MtkRILConstants.RIL_REQUEST_GENERAL_SIM_AUTH, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) RiljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            SimAuthStructure simAuth = convertToHalSimAuthStructure(sessionId, mode, tag,
                    param1, param2);
            try {
                radioProxy.doGeneralSimAuthentication(rr.mSerial, simAuth);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "doGeneralSimAuthentication", e);
            }
        }
    }
    // MTK-END

    // MTK-START: NW
    /* M: Network part start */
    public String lookupOperatorName(int subId, String numeric,
            boolean desireLongName, int nLac) {
        String operatorName = null;

        /*
         * Operator name from SIM (EONS/CPHS) has highest priority to
         * display. To get operator name from OPL/PNN/CPHS, we need
         * lac info.
         */
        UiccController uiccController = UiccController.getInstance();
        MtkSIMRecords simRecord = (MtkSIMRecords) uiccController
                .getIccRecords(mInstanceId, UiccController.APP_FAM_3GPP);
        String sEons = null;
        Rlog.d(RILJ_LOG_TAG, "subId=" + subId + " numeric=" + numeric
                + " desireLongName=" + desireLongName + " nLac=" + nLac);

        if (mPhoneType == RILConstants.GSM_PHONE) {
            if ((nLac != 0xfffe) && (nLac != -1)) {
                try {
                    sEons = (simRecord != null) ?
                            simRecord.getEonsIfExist(numeric, nLac, desireLongName) : null;
                } catch (RuntimeException ex) {
                    Rlog.e(RILJ_LOG_TAG, "Exception while getEonsIfExist. " + ex);
                }

                if (sEons != null && !sEons.equals("")) {
                    Rlog.d(RILJ_LOG_TAG, "plmn name update to Eons: " + sEons);
                    return sEons;
                }
            } else {
                Rlog.d(RILJ_LOG_TAG, "invalid lac ignored");
            }

            // CPHS operator name shall
            // only be used for HPLMN name dispaly
            String mSimOperatorNumeric = (simRecord != null) ?
                    simRecord.getOperatorNumeric() : null;
            if ((mSimOperatorNumeric != null) &&
                    (mSimOperatorNumeric.equals(numeric))) {
                String sCphsOns = null;
                sCphsOns = (simRecord != null) ? simRecord.getSIMCPHSOns() : null;
                /// M: check the network operator names' validation
                if (!TextUtils.isEmpty(sCphsOns)) {
                    Rlog.d(RILJ_LOG_TAG, "plmn name update to CPHS Ons: "
                            + sCphsOns);
                    return sCphsOns;
                }
            }
        }

        /* Operator name from network MM information */
        int phoneId = SubscriptionManager.getPhoneId(subId);
        String nitzOperatorNumeric = null;
        String nitzOperatorName = null;

        nitzOperatorNumeric = TelephonyManager.getTelephonyProperty(phoneId,
                MtkTelephonyProperties.PROPERTY_NITZ_OPER_CODE, "");
        if ((numeric != null) && (numeric.equals(nitzOperatorNumeric))) {
            if (desireLongName == true) {
                nitzOperatorName = TelephonyManager.getTelephonyProperty(phoneId,
                        MtkTelephonyProperties.PROPERTY_NITZ_OPER_LNAME, "");
            } else {
                nitzOperatorName = TelephonyManager.getTelephonyProperty(phoneId,
                        MtkTelephonyProperties.PROPERTY_NITZ_OPER_SNAME, "");
            }

            /* handle UCS2 format name : prefix + hex string ex: "uCs2806F767C79D1" */
            if ((nitzOperatorName != null) && (nitzOperatorName.startsWith("uCs2") == true))
            {
                Rlog.d(RILJ_LOG_TAG, "lookupOperatorName() handling UCS2 format name");
                try {
                    nitzOperatorName = new String(
                            IccUtils.hexStringToBytes(nitzOperatorName.substring(4)), "UTF-16");
                } catch (UnsupportedEncodingException ex) {
                    Rlog.d(RILJ_LOG_TAG, "lookupOperatorName() UnsupportedEncodingException");
                }
            }

            Rlog.d(RILJ_LOG_TAG, "plmn name update to Nitz: "
                    + nitzOperatorName);
            /// M: check the network operator names' validation
            if (!TextUtils.isEmpty(nitzOperatorName)) {
                return nitzOperatorName;
            }
        }

        /* Default display manufacturer maintained operator name table */
        if (numeric != null) {
            operatorName = MtkServiceStateTracker.lookupOperatorName(
                    mMtkContext, subId, numeric, desireLongName);
            Rlog.d(RILJ_LOG_TAG, "plmn name update to MVNO: " + operatorName);
            return operatorName;
        }

        return null;
    }

    /**
     * Select a network with act.
     * @param operatorNumeric is mcc/mnc of network.
     * @param act is technology  of network.
     * @param result messge object.
     */
    public void
    setNetworkSelectionModeManualWithAct(String operatorNumeric, String act, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_SET_NETWORK_SELECTION_MANUAL_WITH_ACT, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                RiljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " operatorNumeric = " + operatorNumeric);
            }

            try {
                radioProxy.setNetworkSelectionModeManualWithAct(rr.mSerial,
                        convertNullToEmptyString(operatorNumeric),
                        convertNullToEmptyString(act), "0");
            } catch (RemoteException e) {
                handleRadioProxyExceptionForRR(rr, "setNetworkSelectionModeManual", e);
            }
        }
    }

    /**
     * Queries the currently available networks with ACT.
     * @param result messge object.
     */
    public void
    getAvailableNetworksWithAct(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_QUERY_AVAILABLE_NETWORKS_WITH_ACT, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                RiljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
            }

            try {
                radioProxy.getAvailableNetworksWithAct(rr.mSerial);
            } catch (RemoteException e) {
                handleRadioProxyExceptionForRR(rr, "getAvailableNetworks", e);
            }
        }
    }

    /**
     * Cancel queries the currently available networks with ACT.
     * @param result messge object.
     */
    public void
    cancelAvailableNetworks(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_ABORT_QUERY_AVAILABLE_NETWORKS, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                RiljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
            }

            try {
                radioProxy.cancelAvailableNetworks(rr.mSerial);
            } catch (RemoteException e) {
                handleRadioProxyExceptionForRR(rr, "getAvailableNetworks", e);
            }
        }
    }

    // Femtocell (CSG) feature START
    /**
     * Get Femtocell list.
     * @param result messge object.
     */
    public void getFemtoCellList(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_GET_FEMTOCELL_LIST, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                RiljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
            }
            try {
                radioProxy.getFemtocellList(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "getFemtoCellList", e);
            }
        }
    }

    /**
     * Cancel Femtocell list.
     * @param result messge object.
     */
    public void abortFemtoCellList(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_ABORT_FEMTOCELL_LIST, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                RiljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
            }
            try {
                radioProxy.abortFemtocellList(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "abortFemtoCellList", e);
            }
        }
    }

    /**
     * Select Femtocells.
     * @param femtocell information.
     * @param result messge object.
     */
    public void selectFemtoCell(FemtoCellInfo femtocell, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_SELECT_FEMTOCELL, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                RiljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
            }

            int act = femtocell.getCsgRat();
            if (act == ServiceState.RIL_RADIO_TECHNOLOGY_LTE) {
                act = 7;
            } else if (act == ServiceState.RIL_RADIO_TECHNOLOGY_UMTS) {
                act = 2;
            } else {
                act = 0;
            }

            RiljLog(rr.serialString() + "> " + requestToString(rr.mRequest) + " csgId="
                    + femtocell.getCsgId() + " plmn=" + femtocell.getOperatorNumeric() + " rat="
                    + femtocell.getCsgRat() + " act=" + act);
            try {
                radioProxy.selectFemtocell(rr.mSerial,
                        convertNullToEmptyString(femtocell.getOperatorNumeric()),
                        convertNullToEmptyString(Integer.toString(act)),
                        convertNullToEmptyString(Integer.toString(femtocell.getCsgId())));
            } catch (Exception e) {
                handleRadioProxyExceptionForRR(rr, "selectFemtoCell", e);
            }
        }
    }

    /**
     * Query Femtocell system selection mode.
     * @param result messge object.
     */
    public void queryFemtoCellSystemSelectionMode(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_QUERY_FEMTOCELL_SYSTEM_SELECTION_MODE, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                RiljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
            }

            try {
                radioProxy.queryFemtoCellSystemSelectionMode(rr.mSerial);
            } catch (Exception e) {
                handleRadioProxyExceptionForRR(rr, "queryFemtoCellSystemSelectionMode", e);
            }
        }
    }

    /**
     * Set Femtocell system selection mode.
     * @param mode system selection mode.
     * @param result messge object.
     */
    public void setFemtoCellSystemSelectionMode(int mode, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_SET_FEMTOCELL_SYSTEM_SELECTION_MODE, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                RiljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " mode=" + mode);
            }

            try {
                radioProxy.setFemtoCellSystemSelectionMode(rr.mSerial, mode);
            } catch (Exception e) {
                handleRadioProxyExceptionForRR(rr, "setFemtoCellSystemSelectionMode", e);
            }
        }
    }
    // Femtocell (CSG) feature END
    // MTK-END: NW

    public void setModemPower(boolean isOn, Message result) {

        if (RILJ_LOGD) RiljLog("Set Modem power as: " + isOn);
        RILRequest rr;
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            if (isOn) {
                rr = obtainRequest(MtkRILConstants.RIL_REQUEST_MODEM_POWERON, result,
                        mRILDefaultWorkSource);
            } else {
                rr = obtainRequest(MtkRILConstants.RIL_REQUEST_MODEM_POWEROFF, result,
                        mRILDefaultWorkSource);
            }

            if (RILJ_LOGD) {
                RiljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                + " " + isOn);
            }

            try {
                radioProxy.setModemPower(rr.mSerial, isOn);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "setModemPower", e);
            }
        }
    }

    protected RegistrantList mInvalidSimInfoRegistrant = new RegistrantList();

    public void setInvalidSimInfo(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        mInvalidSimInfoRegistrant.add(r);
    }

    public void unSetInvalidSimInfo(Handler h) {
        mInvalidSimInfoRegistrant.remove(h);
    }

    protected RegistrantList mNetworkEventRegistrants = new RegistrantList();

    public void registerForNetworkEvent(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        mNetworkEventRegistrants.add(r);
    }

    public void unregisterForNetworkEvent(Handler h) {
        mNetworkEventRegistrants.remove(h);
    }

    protected RegistrantList mModulationRegistrants = new RegistrantList();

    public void registerForModulation(Handler h, int what, Object obj) {
        // for op only
        IMtkRilOp rilOp = getRilOp();
        if (rilOp != null) {
            rilOp.registerForModulation(h, what, obj);
            return;
        }
    }

    public void unregisterForModulation(Handler h) {
        // for op only
        IMtkRilOp rilOp = getRilOp();
        if (rilOp != null) {
            rilOp.unregisterForModulation(h);
            return;
        }
    }

    /**
     * Register for femto cell information.
     * @param h Handler for notification message.
     * @param what User-defined message code.
     * @param obj User object.
     */
    public void registerForFemtoCellInfo(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        mFemtoCellInfoRegistrants.add(r);
    }

    /**
     * Unregister for femto cell information.
     * @param h Handler for notification message.
     */
    public void unregisterForFemtoCellInfo(Handler h) {
        mFemtoCellInfoRegistrants.remove(h);
    }

    // SMS-START
    // In order to cache the event from modem at boot-up sequence
    public boolean mIsSmsReady = false;
    protected boolean mIsSmsSimFull = false;
    protected RegistrantList mSmsReadyRegistrants = new RegistrantList();
    protected Registrant mMeSmsFullRegistrant;
    protected Registrant mEtwsNotificationRegistrant;
    protected Registrant mCDMACardEsnMeidRegistrant;
    protected Object mEspOrMeid = null;

    public void registerForSmsReady(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        mSmsReadyRegistrants.add(r);

        if (mIsSmsReady == true) {
            // Only notify the new registrant
            r.notifyRegistrant();
        }
    }

    public void unregisterForSmsReady(Handler h) {
        mSmsReadyRegistrants.remove(h);
    }

    public void setOnMeSmsFull(Handler h, int what, Object obj) {
        mMeSmsFullRegistrant = new Registrant(h, what, obj);
    }

    public void unSetOnMeSmsFull(Handler h) {
        mMeSmsFullRegistrant.clear();
    }

    public void setOnEtwsNotification(Handler h, int what, Object obj) {
        mEtwsNotificationRegistrant = new Registrant(h, what, obj);
    }

    public void unSetOnEtwsNotification(Handler h) {
        mEtwsNotificationRegistrant.clear();
    }

    /**
     * {@inheritDoc}
     */
    public void getSmsParameters(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(MtkRILConstants.RIL_REQUEST_GET_SMS_PARAMS, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) RiljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            try {
                radioProxy.getSmsParameters(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "getSmsParameters", e);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    public void setSmsParameters(MtkSmsParameters params, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(MtkRILConstants.RIL_REQUEST_SET_SMS_PARAMS, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) RiljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            SmsParams smsp = new SmsParams();
            smsp.dcs = params.dcs;
            smsp.format = params.format;
            smsp.pid = params.pid;
            smsp.vp = params.vp;
            try {
                radioProxy.setSmsParameters(rr.mSerial, smsp);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "setSmsParameters", e);
            }
        }
    }
    /**
     * {@inheritDoc}
     */
    public void setEtws(int mode, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(MtkRILConstants.RIL_REQUEST_SET_ETWS, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) RiljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            try {
                radioProxy.setEtws(rr.mSerial, mode);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "setEtws", e);
            }
        }
    }

    public void removeCellBroadcastMsg(int channelId, int serialId, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(MtkRILConstants.RIL_REQUEST_REMOVE_CB_MESSAGE, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) RiljLog(rr.serialString() + "> " + requestToString(rr.mRequest) + " " +
                    channelId + ", " + serialId);

            try {
                radioProxy.removeCbMsg(rr.mSerial, channelId, serialId);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "removeCellBroadcastMsg", e);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    public void getSmsSimMemoryStatus(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(MtkRILConstants.RIL_REQUEST_GET_SMS_SIM_MEM_STATUS,
                    result, mRILDefaultWorkSource);

            if (RILJ_LOGD) RiljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            try {
                radioProxy.getSmsMemStatus(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "getSmsSimMemoryStatus", e);
            }
        }
    }

    public void setGsmBroadcastLangs(String lang, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(MtkRILConstants.RIL_REQUEST_GSM_SET_BROADCAST_LANGUAGE,
                    result, mRILDefaultWorkSource);

            if (RILJ_LOGD) RiljLog(rr.serialString() + "> " + requestToString(rr.mRequest) +
                    ", lang:" + lang);

            try {
                radioProxy.setGsmBroadcastLangs(rr.mSerial, lang);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "setGsmBroadcastLangs", e);
            }
        }
    }

    public void getGsmBroadcastLangs(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(MtkRILConstants.RIL_REQUEST_GSM_GET_BROADCAST_LANGUAGE,
                    result, mRILDefaultWorkSource);

            if (RILJ_LOGD) RiljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            try {
                radioProxy.getGsmBroadcastLangs(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "getGsmBroadcastLangs", e);
            }
        }
    }

    public void getGsmBroadcastActivation(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(
                    MtkRILConstants.RIL_REQUEST_GET_GSM_SMS_BROADCAST_ACTIVATION,
                    result, mRILDefaultWorkSource);

            if (RILJ_LOGD) RiljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            try {
                radioProxy.getGsmBroadcastActivation(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "getGsmBroadcastActivation", e);
            }
        }
    }

    /**
     * Register ESN/MEID change report.
     *
     * @param h the handler to handle the message
     * @param what the message ID
     * @param obj the user data of the message reciever
     */
    public void setCDMACardInitalEsnMeid(Handler h, int what, Object obj) {
        mCDMACardEsnMeidRegistrant = new Registrant(h, what, obj);
        if (mEspOrMeid != null) {
            mCDMACardEsnMeidRegistrant.notifyRegistrant(new AsyncResult(null, mEspOrMeid, null));
        }
    }

    @Override
    public void setOnIccSmsFull(Handler h, int what, Object obj) {
        super.setOnIccSmsFull(h, what, obj);
        if (mIsSmsSimFull == true) {
            mIccSmsFullRegistrant.notifyRegistrant();
            // Already notify, set as false. Because there is no URC to notify avaliable and
            // only one module will register. Looks like a workaround solution and make it easy
            mIsSmsSimFull = false;
        }
    }

    @Override
    public void writeSmsToRuim(int status, String pdu, Message result) {
        status = translateStatus(status);
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_CDMA_WRITE_SMS_TO_RUIM, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGV) {
                RiljLog(rr.serialString() + "> "
                        + requestToString(rr.mRequest)
                        + " status = " + status);
            }

            CdmaSmsWriteArgs args = new CdmaSmsWriteArgs();
            args.status = status;
            constructCdmaSendSmsRilRequest(args.message, IccUtils.hexStringToBytes(pdu));

            try {
                radioProxy.writeSmsToRuim(rr.mSerial, args);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "writeSmsToRuim", e);
            }
        }
    }
    // SMS-END

    // M: [pending data call during located plmn changing] start
    protected RegistrantList mPsNetworkStateRegistrants = new RegistrantList();
    public void registerForPsNetworkStateChanged(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        mPsNetworkStateRegistrants.add(r);
    }

    public void unregisterForPsNetworkStateChanged(Handler h) {
        mPsNetworkStateRegistrants.remove(h);
    }
    // M: [pending data call during located plmn changing] end

    protected RegistrantList mNetworkInfoRegistrant = new RegistrantList();
    public void registerForNetworkInfo(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        mNetworkInfoRegistrant.add(r);
    }

    public void unregisterForNetworkInfo(Handler h) {
        mNetworkInfoRegistrant.remove(h);
    }

    @Override
    public void switchWaitingOrHoldingAndActive(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_SWITCH_WAITING_OR_HOLDING_AND_ACTIVE, result,
                    mRILDefaultWorkSource);
            if (RILJ_LOGD) RiljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
            Object[] params = null;
            handleChldRelatedRequest(rr, params);
        }
    }

    @Override
    public void conference(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_CONFERENCE, result,
                    mRILDefaultWorkSource);
            if (RILJ_LOGD) RiljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
            Object[] params = null;
            handleChldRelatedRequest(rr, params);
        }
    }

    @Override
    public void separateConnection(int gsmIndex, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_SEPARATE_CONNECTION, result,
                    mRILDefaultWorkSource);
            if (RILJ_LOGD) {
                RiljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " gsmIndex = " + gsmIndex);
            }
            Object[] params = { gsmIndex };
            handleChldRelatedRequest(rr, params);
        }
    }

    @Override
    public void explicitCallTransfer(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_EXPLICIT_CALL_TRANSFER, result,
                    mRILDefaultWorkSource);
            if (RILJ_LOGD) RiljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
            Object[] params = null;
            handleChldRelatedRequest(rr, params);
        }
    }

    @Override
    public void startDtmf(char c, Message result) {
        /// M: CC: DTMF request special handling @{
        /* DTMF request will be ignored when the count of requests reaches 32 */
        synchronized (mDtmfReqQueue) {
            if (!mDtmfReqQueue.hasSendChldRequest()
                    && mDtmfReqQueue.size() < mDtmfReqQueue.MAXIMUM_DTMF_REQUEST) {
                if (!mDtmfReqQueue.isStart()) {
                    IRadio radioProxy = getRadioProxy(result);
                    if (radioProxy != null) {
                        RILRequest rr = obtainRequest(RIL_REQUEST_DTMF_START, result,
                                mRILDefaultWorkSource);

                        mDtmfReqQueue.start();
                        Object[] param = { c };
                        DtmfQueueHandler.DtmfQueueRR dqrr = mDtmfReqQueue.buildDtmfQueueRR(rr,
                                param);
                        mDtmfReqQueue.add(dqrr);

                        if (mDtmfReqQueue.size() == 1) {
                            RiljLog("send start dtmf");
                            // Do not log function arg for privacy
                            if (RILJ_LOGD)
                                RiljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
                            sendDtmfQueueRR(dqrr);
                        }
                    }
                } else {
                    RiljLog("DTMF status conflict, want to start DTMF when status is "
                            + mDtmfReqQueue.isStart());
                }
            }
        }
        /// @}
    }

    @Override
    public void stopDtmf(Message result) {
        /// M: CC: DTMF request special handling @{
        /* DTMF request will be ignored when the count of requests reaches 32 */
        synchronized (mDtmfReqQueue) {
            if (!mDtmfReqQueue.hasSendChldRequest()
                    && mDtmfReqQueue.size() < mDtmfReqQueue.MAXIMUM_DTMF_REQUEST) {
                if (mDtmfReqQueue.isStart()) {
                    IRadio radioProxy = getRadioProxy(result);
                    if (radioProxy != null) {
                        RILRequest rr = obtainRequest(RIL_REQUEST_DTMF_STOP, result,
                                mRILDefaultWorkSource);
                        mDtmfReqQueue.stop();
                        Object[] param = null;
                        DtmfQueueHandler.DtmfQueueRR dqrr = mDtmfReqQueue.buildDtmfQueueRR(rr,
                                param);
                        mDtmfReqQueue.add(dqrr);
                        if (mDtmfReqQueue.size() == 1) {
                            RiljLog("send stop dtmf");
                            if (RILJ_LOGD)
                                RiljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
                            sendDtmfQueueRR(dqrr);
                        }
                    }
                } else {
                    RiljLog("DTMF status conflict, want to start DTMF when status is "
                            + mDtmfReqQueue.isStart());
                }
            }
        }
        /// @}
    }

    /// M: CC: HangupAll for FTA 31.4.4.2 @{
    public void
    hangupAll(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_HANGUP_ALL, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                RiljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
            }

            try {
                radioProxy.hangupAll(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "hangupAll", e);
            }
        }
    }
    /// @}

    /// M: CC: Proprietary incoming call handling
    public void setCallIndication(int mode, int callId, int seqNumber, Message result) {

        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_SET_CALL_INDICATION, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                RiljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                + " " + mode + ", " + callId + ", " + seqNumber);
            }

            try {
                radioProxy.setCallIndication(rr.mSerial, mode, callId, seqNumber);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "setCallIndication", e);
            }
        }
    }

    /// M: CC: Proprietary ECC enhancement @{
    /**
     *  returned message
     *  retMsg.obj = AsyncResult ar
     *  ar.exception carries exception on failure
     *  ar.userObject contains the orignal value of result.obj
     *  ar.result is null on success and failure
     *
     * CLIR_DEFAULT     == on "use subscription default value"
     * CLIR_SUPPRESSION == on "CLIR suppression" (allow CLI presentation)
     * CLIR_INVOCATION  == on "CLIR invocation" (restrict CLI presentation)
     */
    public void emergencyDial(String address, int clirMode, UUSInfo uusInfo, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_EMERGENCY_DIAL, result,
                    mRILDefaultWorkSource);

            Dial dialInfo = new Dial();
            dialInfo.address = convertNullToEmptyString(address);
            dialInfo.clir = clirMode;
            if (uusInfo != null) {
                UusInfo info = new UusInfo();
                info.uusType = uusInfo.getType();
                info.uusDcs = uusInfo.getDcs();
                info.uusData = new String(uusInfo.getUserData());
                dialInfo.uusInfo.add(info);
            }

            if (RILJ_LOGD) {
                // Do not log function arg for privacy
                RiljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
            }

            try {
                radioProxy.emergencyDial(rr.mSerial, dialInfo);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "emergencyDial", e);
            }
        }
    }

    public void setEccServiceCategory(int serviceCategory, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_SET_ECC_SERVICE_CATEGORY, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                RiljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " serviceCategory=" + serviceCategory);
            }

            try {
                radioProxy.setEccServiceCategory(rr.mSerial, serviceCategory);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "setEccServiceCategory", e);
            }
        }
    }

    /// M: CC: Vzw/CTVolte ECC @{
    /**
     * Let modem know start of E911 and deliver some information.
     *
     * @param airplaneMode
     *          0 : off
     *          1 : on
     *
     * @param imsReg
     *          0 : ims deregistered
     *          1 : ims registered
     *
     * @hide
     */
    public void setCurrentStatus(int airplaneMode, int imsReg, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_CURRENT_STATUS, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                RiljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " airplaneMode=" + airplaneMode
                        + " imsReg=" + imsReg);
            }

            try {
                radioProxy.currentStatus(rr.mSerial, airplaneMode, imsReg);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "setCurrentStatus", e);
            }
        }
    }
    /// @}

    /**
     * Set ECC preferred Rat
     *
     */
    public void setEccPreferredRat(int phoneType, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_ECC_PREFERRED_RAT, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                RiljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " phoneType=" + phoneType);
            }

            try {
                radioProxy.eccPreferredRat(rr.mSerial, phoneType);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "setEccPreferredRat", e);
            }
        }
    }

    /**
     * Set emergency number list to modem.
     *
     */
    public void setEccList() {
        IRadio radioProxy = getRadioProxy(null);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_SET_ECC_LIST, null,
                    mRILDefaultWorkSource);

            ArrayList<String> eccList = MtkPhoneNumberUtils.getEccList();
            String[] eccListString = {"", ""};
            int i = 0;
            for (String list : eccList) {
                if (i >= 2) {
                    // MD support Max 15 customized ECC so we will at most
                    // sync 2 ecc lists to MD (10 entries at once)
                    break;
                }
                eccListString[i++] = list;
            }

            if (RILJ_LOGD) {
                RiljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " ecc1: " + eccListString[0] + ", ecc2: " + eccListString[1]);
            }

            try {
                radioProxy.setEccList(rr.mSerial, eccListString[0], eccListString[1]);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "setEccList", e);
            }
        }
    }
    /// @}

    /// M: CC: For 3G VT only @{
    public void vtDial(String address, int clirMode, UUSInfo uusInfo, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_VT_DIAL, result,
                    mRILDefaultWorkSource);

            Dial dialInfo = new Dial();
            dialInfo.address = convertNullToEmptyString(address);
            dialInfo.clir = clirMode;
            if (uusInfo != null) {
                UusInfo info = new UusInfo();
                info.uusType = uusInfo.getType();
                info.uusDcs = uusInfo.getDcs();
                info.uusData = new String(uusInfo.getUserData());
                dialInfo.uusInfo.add(info);
            }

            if (RILJ_LOGD) {
                // Do not log function arg for privacy
                RiljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
            }

            try {
                radioProxy.vtDial(rr.mSerial, dialInfo);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "vtDial", e);
            }
        }
    }

    public void
    acceptVtCallWithVoiceOnly(int callId, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_VOICE_ACCEPT, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                RiljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " callId=" + callId);
            }

            try {
                radioProxy.voiceAccept(rr.mSerial, callId);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "acceptVtCallWithVoiceOnly", e);
            }
        }
    }

    public void replaceVtCall(int index, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_REPLACE_VT_CALL, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                RiljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " index=" + index);
            }

            try {
                radioProxy.replaceVtCall(rr.mSerial, index);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "replaceVtCall", e);
            }
        }
    }
    /// @}

    /// M: CC: GSA HD Voice for 2/3G network support @{
    /**
     * Sets the handler for notifying Speech Codec Type for recognizing HD voice capability.
     * Unlike the register* methods, there's only one notification handler
     *
     * @param h Handler for notification message.
     * @param what User-defined message code.
     * @param obj User object.
     */
    public void setOnSpeechCodecInfo(Handler h, int what, Object obj) {
        mSpeechCodecInfoRegistrant = new Registrant(h, what, obj);
    }

    /**
     * Unsets the handler for notifying Speech Codec Type for recognizing HD voice capability.
     *
     * @param h Handler for notification message.
     */
    public void unSetOnSpeechCodecInfo(Handler h) {
        if (mSpeechCodecInfoRegistrant != null && mSpeechCodecInfoRegistrant.getHandler() == h) {
            mSpeechCodecInfoRegistrant.clear();
            mSpeechCodecInfoRegistrant = null;
        }
    }
    /// @}

    // APC
    protected RegistrantList mPseudoCellInfoRegistrants = new RegistrantList();
    public void registerForPseudoCellInfo(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        mPseudoCellInfoRegistrants.add(r);
    }

    public void unregisterForPseudoCellInfo(Handler h) {
        mPseudoCellInfoRegistrants.remove(h);
    }

    public void setApcMode(int apcMode, boolean reportOn, int reportInterval,
                                            Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_SET_PSEUDO_CELL_MODE, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                RiljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                    + " " + apcMode + ", " + reportOn + ", " + reportInterval);
            }

            try {
                int reportMode = (reportOn == true ? 1 : 0);
                radioProxy.setApcMode(rr.mSerial, apcMode, reportMode, reportInterval);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "setApcMode", e);
            }
        }
    }

    public void getApcInfo(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_GET_PSEUDO_CELL_INFO, result,
                    mRILDefaultWorkSource);
            if (RILJ_LOGD) {
                RiljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
            }

            try {
                radioProxy.getApcInfo(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "getApcInfo", e);
            }
        }
    }

    /**
     * If want to call ECC number by CDMA/GSM network when there is no card in phone,
     * should guarantee that: the phone which user wanted to use is CDMA/GSM Phone.
     * If the phone type is not correct, should change the phone type throngh using this
     * mechanism to switch mode to CDMA/CSFB.
     *
     * @param mode 4: CARD_TYPE_CSIM; 1: CARD_TYPE_SIM
     * @param result messge object.
     */
    public void triggerModeSwitchByEcc(int mode, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_SWITCH_MODE_FOR_ECC, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                RiljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
            }

            try {
                radioProxy.triggerModeSwitchByEcc(rr.mSerial, mode);
                Message msg = mRilHandler.obtainMessage(EVENT_BLOCKING_RESPONSE_TIMEOUT);
                msg.obj = null;
                msg.arg1 = rr.mSerial;
                mRilHandler.sendMessageDelayed(msg, DEFAULT_BLOCKING_MESSAGE_RESPONSE_TIMEOUT_MS);
            } catch (RemoteException e) {
                handleRadioProxyExceptionForRR(rr, "triggerModeSwitchByEcc", e);
            }
        }
   }

    /**
     * Get the RUIM SMS memory Status.
     *
     * @param result the response message
     */
    public void getSmsRuimMemoryStatus(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_GET_SMS_RUIM_MEM_STATUS, result,
                    mRILDefaultWorkSource);
            if (RILJ_LOGD) {
                RiljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
            }
            try {
                radioProxy.getSmsRuimMemoryStatus(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "getSmsRuimMemoryStatus", e);
            }
        }
    }

    // FastDormancy
    public void setFdMode(int mode, int para1, int para2, Message response) {
        vendor.mediatek.hardware.radio.V2_0.IRadio radioProxy = getRadioProxy(response);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_SET_FD_MODE, response,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                RiljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
            }

            try {
                radioProxy.setFdMode(rr.mSerial, mode, para1, para2);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "setFdMode", e);
            }
        }
    }

    protected ArrayList<HardwareConfig> convertHalHwConfigList(
            ArrayList<android.hardware.radio.V1_0.HardwareConfig> hwListRil,
            RIL ril) {
        int num;
        ArrayList<HardwareConfig> response;
        HardwareConfig hw;

        num = hwListRil.size();
        response = new ArrayList<HardwareConfig>(num);

        if (RILJ_LOGV) {
            RiljLog("convertHalHwConfigList: num=" + num);
        }
        for (android.hardware.radio.V1_0.HardwareConfig hwRil : hwListRil) {
            int type = hwRil.type;
            switch(type) {
                case HardwareConfig.DEV_HARDWARE_TYPE_MODEM: {
                    hw = new MtkHardwareConfig(type);
                    HardwareConfigModem hwModem = hwRil.modem.get(0);
                    hw.assignModem(hwRil.uuid, hwRil.state, hwModem.rilModel, hwModem.rat,
                            hwModem.maxVoice, hwModem.maxData, hwModem.maxStandby);
                    break;
                }
                case HardwareConfig.DEV_HARDWARE_TYPE_SIM: {
                    hw = new MtkHardwareConfig(type);
                    hw.assignSim(hwRil.uuid, hwRil.state, hwRil.sim.get(0).modemUuid);
                    break;
                }
                default: {
                    throw new RuntimeException(
                            "RIL_REQUEST_GET_HARDWARE_CONFIG invalid hardward type:" + type);
                }
            }
            response.add(hw);
        }

        return response;
    }


    /**
     * Register for plmn.
     * When AP receive plmn Urc to decide to target is in home or roaming.
     * @param h Handler for notification message.
     * @param what User-defined message code.
     * @param obj User object.
     */
    public void setOnPlmnChangeNotification(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
         synchronized (mWPMonitor) {
            mPlmnChangeNotificationRegistrant.add(r);

            if (mEcopsReturnValue != null) {
               // Only notify the new registrant
               r.notifyRegistrant(new AsyncResult(null, mEcopsReturnValue, null));
               mEcopsReturnValue = null;
            }
        }
    }


    public void unSetOnPlmnChangeNotification(Handler h) {
        synchronized (mWPMonitor) {
            mPlmnChangeNotificationRegistrant.remove(h);
        }
    }

    /**
     * Register for EMSR.
     * When AP receive EMSR Urc to decide to resume camping network.
     * @param h Handler for notification message.
     * @param what User-defined message code.
     * @param obj User object.
     */
    public void setOnRegistrationSuspended(Handler h, int what, Object obj) {
        synchronized (mWPMonitor) {
            mRegistrationSuspendedRegistrant = new Registrant(h, what, obj);

            if (mEmsrReturnValue != null) {
                // Only notify the new registrant
                mRegistrationSuspendedRegistrant.notifyRegistrant(
                    new AsyncResult(null, mEmsrReturnValue, null));
                mEmsrReturnValue = null;
            }
        }
    }

    public void unSetOnRegistrationSuspended(Handler h) {
        synchronized (mWPMonitor) {
            mRegistrationSuspendedRegistrant.clear();
        }
    }

    /**
     * Register for GMSS RAT.
     * When boot the phone,AP can use this informaiton decide PS' type(LTE or C2K).
     * @param h Handler for notification message.
     * @param what User-defined message code.
     * @param obj User object.
     */
    public void registerForGmssRatChanged(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        mGmssRatChangedRegistrant.add(r);
    }

    /**
     * Request GSM modem to resume network registration.
     * @param sessionId the session index.
     * @param result the responding message.
     */
    public void setResumeRegistration(int sessionId, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_RESUME_REGISTRATION, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                RiljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " sessionId = " + sessionId);
            }

            try {
                radioProxy.setResumeRegistration(rr.mSerial, sessionId);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "setResumeRegistration", e);
            }
        }
    }

    /**
     * Request GSM modem to store new modem type.
     * @param modemType worldmodeid.
     * @param result the responding message.
     */
    public void storeModemType(int modemType, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_STORE_MODEM_TYPE, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                RiljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " modemType = " + modemType);
            }

            try {
                radioProxy.storeModemType(rr.mSerial, modemType);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "storeModemType", e);
            }
        }
    }

    /**
     * Request GSM modem to reload new modem type.
     * @param modemType worldmodeid.
     * @param result the responding message.
     */
    public void reloadModemType(int modemType, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_RELOAD_MODEM_TYPE, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                RiljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " modemType = " + modemType);
            }

            try {
                radioProxy.reloadModemType(rr.mSerial, modemType);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "reloadModemType", e);
            }
        }
    }

    public void handleStkCallSetupRequestFromSimWithResCode(boolean accept, int resCode,
            Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(
                    RIL_REQUEST_STK_HANDLE_CALL_SETUP_REQUESTED_FROM_SIM_WITH_RESULT_CODE,
                    result, mRILDefaultWorkSource);

            if (RILJ_LOGD) {
               RiljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
            }
            int[] param = new int[1];
            if (resCode == 0x21 || resCode == 0x20) {
                param[0] = resCode;
            } else {
                param[0] = accept ? 1 : 0;
            }

            try {
               radioProxy.handleStkCallSetupRequestFromSimWithResCode(rr.mSerial, param[0]);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr,
                        "handleStkCallSetupRequestFromSimWithResCode", e);
            }
        }
    }

    // MTK-START: SIM COMMON SLOT
    protected RegistrantList mSimTrayPlugIn = new RegistrantList();
    protected RegistrantList mSimCommonSlotNoChanged = new RegistrantList();

    public void registerForSimTrayPlugIn(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        mSimTrayPlugIn.add(r);
    }

    public void unregisterForSimTrayPlugIn(Handler h) {
        mSimTrayPlugIn.remove(h);
    }

    public void registerForCommonSlotNoChanged(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        mSimCommonSlotNoChanged.add(r);
    }

    public void unregisterForCommonSlotNoChanged(Handler h) {
        mSimCommonSlotNoChanged.remove(h);
    }
    // MTK-END

    @Override
    public void resetRadio(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_RESET_RADIO, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                RiljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
            }

            try {
                radioProxy.resetRadio(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "resetRadio", e);
            }
        }
    }

    public void setInitialAttachApnEx(DataProfile dataProfile, boolean isRoaming,
                                      boolean canHandleIms, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_SET_INITIAL_ATTACH_APN, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                RiljLog(rr.serialString() + "> " + requestToString(rr.mRequest) + ","
                        + dataProfile + "," + canHandleIms);
            }

            try {
                radioProxy.setInitialAttachApnEx(rr.mSerial, convertToHalDataProfile(dataProfile),
                        dataProfile.modemCognitive, isRoaming, canHandleIms);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "setInitialAttachApnEx", e);
            }
        }
    }

    // / M: BIP {
    protected RegistrantList mBipProCmdRegistrant = new RegistrantList();

    public void setOnBipProactiveCmd(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        mBipProCmdRegistrant.add(r);
    }

    public void unSetOnBipProactiveCmd(Handler h) {
        mBipProCmdRegistrant.remove(h);
    }

    // / M: BIP }

    // / M: STK {
    protected RegistrantList mStkSetupMenuResetRegistrant = new RegistrantList();

    public void setOnStkSetupMenuReset(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        mStkSetupMenuResetRegistrant.add(r);
    }

    public void unSetOnStkSetupMenuReset(Handler h) {
        mStkSetupMenuResetRegistrant.remove(h);
    }
    // / M: STK }

    // MTK-START: SIM ME LOCK
    public void queryNetworkLock(int category, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(MtkRILConstants.RIL_REQUEST_QUERY_SIM_NETWORK_LOCK,
                    result, mRILDefaultWorkSource);

            if (RILJ_LOGD) RiljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            try {
                radioProxy.queryNetworkLock(rr.mSerial, category);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "queryNetworkLock", e);
            }
        }
    }

    public void setNetworkLock(int category, int lockop, String password,
                        String data_imsi, String gid1, String gid2, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(MtkRILConstants.RIL_REQUEST_SET_SIM_NETWORK_LOCK, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) RiljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            password = (password == null) ? "" : password;
            data_imsi = (data_imsi == null) ? "" : data_imsi;
            gid1 = (gid1 == null) ? "" : gid1;
            gid2 = (gid2 == null) ? "" : gid2;
            try {
                radioProxy.setNetworkLock(rr.mSerial, category, lockop, password, data_imsi,
                        gid1, gid2);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "setNetworkLock", e);
            }
        }
    }
    // MTK-END

    public void registerForResetAttachApn(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        mResetAttachApnRegistrants.add(r);
    }

    public void unregisterForResetAttachApn(Handler h) {
        mResetAttachApnRegistrants.remove(h);
    }

    public void registerForAttachApnChanged(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);

        mAttachApnChangedRegistrants.add(r);
    }

    public void unregisterForAttachApnChanged(Handler h) {
        mAttachApnChangedRegistrants.remove(h);
    }

    // M: [LTE][Low Power][UL traffic shaping] @{
    protected RegistrantList mLteAccessStratumStateRegistrants = new RegistrantList();

    public void registerForLteAccessStratumState(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        mLteAccessStratumStateRegistrants.add(r);
    }

    public void unregisterForLteAccessStratumState(Handler h) {
        mLteAccessStratumStateRegistrants.remove(h);
    }

    public void setLteAccessStratumReport(boolean enable, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_SET_LTE_ACCESS_STRATUM_REPORT, result,
                    mRILDefaultWorkSource);
            int type = enable ? 1 : 0;

            if (RILJ_LOGD) {
                RiljLog(rr.serialString() + "> " + requestToString(rr.mRequest) + ": " + type);
            }

            try {
                radioProxy.setLteAccessStratumReport(rr.mSerial, type);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "setLteAccessStratumReport", e);
            }
        }
    }

    public void setLteUplinkDataTransfer(int state, int interfaceId, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_SET_LTE_UPLINK_DATA_TRANSFER, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                RiljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " state = " + state
                        + ", interfaceId = " + interfaceId);
            }

            try {
                radioProxy.setLteUplinkDataTransfer(rr.mSerial, state, interfaceId);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "setLteUplinkDataTransfer", e);
            }
        }
    }
    // M: [LTE][Low Power][UL traffic shaping] @}

    // MTK-START: SIM HOT SWAP / SIM RECOVERY
    protected RegistrantList mSimPlugIn = new RegistrantList();
    protected RegistrantList mSimPlugOut = new RegistrantList();
    protected RegistrantList mSimMissing = new RegistrantList();
    protected RegistrantList mSimRecovery = new RegistrantList();

    public void registerForSimPlugIn(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        mSimPlugIn.add(r);
    }

    public void unregisterForSimPlugIn(Handler h) {
        mSimPlugIn.remove(h);
    }

    public void registerForSimPlugOut(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        mSimPlugOut.add(r);
    }

    public void unregisterForSimPlugOut(Handler h) {
        mSimPlugOut.remove(h);
    }

    public void registerForSimMissing(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        mSimMissing.add(r);
    }

    public void unregisterForSimMissing(Handler h) {
        mSimMissing.remove(h);
    }

    public void registerForSimRecovery(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        mSimRecovery.add(r);
    }

    public void unregisterForSimRecovery(Handler h) {
        mSimRecovery.remove(h);
    }
    // MTK-END
    // PHB Start
    /**
     * Sets the handler for PHB ready notification.
     *
     * @param h Handler for notification message.
     * @param what User-defined message code.
     * @param obj User object.
     */
    public void registerForPhbReady(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        Rlog.d(RILJ_LOG_TAG, "call registerForPhbReady Handler : " + h);
        mPhbReadyRegistrants.add(r);
    }

    /**
     * Unregister the handler for PHB ready notification.
     *
     * @param h Handler for notification message.
     */
    public void unregisterForPhbReady(Handler h) {
        mPhbReadyRegistrants.remove(h);
    }

    /**
     * Request the information of the given storage type
     *
     * @param type
     *          the type of the storage, refer to PHB_XDN defined in the RilConstants
     * @param result
     *          Callback message
     *          response.obj.result is an int[4]
     *          response.obj.result[0] is number of current used entries
     *          response.obj.result[1] is number of total entries in the storage
     *          response.obj.result[2] is maximum supported length of the number
     *          response.obj.result[3] is maximum supported length of the alphaId
     */
    public void queryPhbStorageInfo(int type, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_QUERY_PHB_STORAGE_INFO, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) RiljLog(rr.serialString() + "> " + requestToString(rr.mRequest) + ": "
                + type);

            try {
                radioProxy.queryPhbStorageInfo(rr.mSerial, type);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "queryPhbStorageInfo", e);
            }
        }
    }

    /**
     * Convert to PhbEntryStructure defined in types.hal.
     * @param pe PHB entry
     * @return A converted PHB entry for hal
     */
    private PhbEntryStructure convertToHalPhbEntryStructure(PhbEntry pe) {
        PhbEntryStructure pes = new PhbEntryStructure();

        pes.type = pe.type;
        pes.index = pe.index;
        pes.number = convertNullToEmptyString(pe.number);
        pes.ton = pe.ton;
        pes.alphaId = convertNullToEmptyString(pe.alphaId);

        return pes;
    }

    /**
     * Request update a PHB entry using the given. {@link PhbEntry}
     *
     * @param entry a PHB entry strucutre {@link PhbEntry}
     *          when one of the following occurs, it means delete the entry.
     *          1. entry.number is NULL
     *          2. entry.number is empty and entry.ton = 0x91
     *          3. entry.alphaId is NULL
     *          4. both entry.number and entry.alphaId are empty.
     * @param result
     *          Callback message containing if the action is success or not.
     */
    public void writePhbEntry(PhbEntry entry, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_WRITE_PHB_ENTRY, result,
                    mRILDefaultWorkSource);

            // Convert to HAL PhbEntry Structure
            PhbEntryStructure pes = convertToHalPhbEntryStructure(entry);

            if (RILJ_LOGD) RiljLog(rr.serialString() + "> " + requestToString(rr.mRequest) + ": "
                + entry);

            try {
                radioProxy.writePhbEntry(rr.mSerial, pes);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "writePhbEntry", e);
            }
        }
    }

    /**
     * Request read PHB entries from the given storage.
     * @param type
     *          the type of the storage, refer to PHB_* defined in the RilConstants
     * @param bIndex
     *          the begin index of the entries to be read
     * @param eIndex
     *          the end index of the entries to be read, note that the (eIndex - bIndex +1)
     *          should not exceed the value RilConstants.PHB_MAX_ENTRY
     *
     * @param result
     *          Callback message containing an array of {@link PhbEntry} structure.
     */
    public void readPhbEntry(int type, int bIndex, int eIndex, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_READ_PHB_ENTRY, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) RiljLog(rr.serialString() + "> " + requestToString(rr.mRequest) + ": "
                + type + " begin: " + bIndex + " end: " + eIndex);

            try {
                radioProxy.readPhbEntry(rr.mSerial, type, bIndex, eIndex);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "readPhbEntry", e);
            }
        }
    }

    /**
     * Query capability of USIM PHB.
     *
     * @param result callback message
     * ((AsyncResult)response.obj).result is
     *  <N_ANR>,<N_EMAIL>,<N_SNE>,<N_AAS>,<L_AAS>,<N_GAS>,<L_GAS>,<N_GRP>
     */
    public void queryUPBCapability(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_QUERY_UPB_CAPABILITY, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) RiljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            try {
                radioProxy.queryUPBCapability(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "queryUPBCapability", e);
            }
        }
    }


    /**
     * Update a USIM PHB field's entry.
     * This is a new API mainly for update EF_ANR.
     *
     * @param entryType must be 0(ANR), 1(EMAIL), 2(SNE), 3(AAS), or 4(GAS)
     * @param adnIndex ADN index
     * @param entryIndex the i-th EF_(EMAIL/ANR/SNE)
     * @param strVal is the value string to be updated
     * @param tonForNum TON for ANR
     * @param aasAnrIndex AAS index of the ANR
     * @param result callback message
     */
    public void editUPBEntry(int entryType, int adnIndex, int entryIndex,
            String strVal, String tonForNum, String aasAnrIndex, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_EDIT_UPB_ENTRY, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) RiljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            ArrayList<String> arrList = new ArrayList<>();
            arrList.add(Integer.toString(entryType));
            arrList.add(Integer.toString(adnIndex));
            arrList.add(Integer.toString(entryIndex));
            arrList.add(strVal);
            if (entryType == 0) {
                arrList.add(tonForNum);
                arrList.add(aasAnrIndex);
            }

            try {
                radioProxy.editUPBEntry(rr.mSerial, arrList);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "editUPBEntry", e);
            }
        }
    }

    /**
     * Update a USIM PHB field's entry.
     *
     * @param entryType must be 0(ANR), 1(EMAIL), 2(SNE), 3(AAS), or 4(GAS)
     * @param adnIndex ADN index
     * @param entryIndex the i-th EF_(EMAIL/ANR/SNE)
     * @param strVal is the value string to be updated
     * @param tonForNum TON for ANR
     * @param result callback message
     */
    public void editUPBEntry(int entryType, int adnIndex, int entryIndex,
            String strVal, String tonForNum, Message result) {
        editUPBEntry(entryType, adnIndex, entryIndex, strVal, tonForNum, null, result);
    }

    /**
     * Delete a USIM PHB field's entry.
     *
     * @param entryType must be 0(ANR), 1(EMAIL), 2(SNE), 3(AAS), or 4(GAS)
     * @param adnIndex ADN index
     * @param entryIndex the i-th EF_(EMAIL/ANR/SNE)
     * @param result callback message
     */
    public void deleteUPBEntry(int entryType, int adnIndex, int entryIndex, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_DELETE_UPB_ENTRY, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) RiljLog(rr.serialString() + "> " + requestToString(rr.mRequest) + ": "
                + entryType + " adnIndex: " + adnIndex + " entryIndex: " + entryIndex);

            try {
                radioProxy.deleteUPBEntry(rr.mSerial, entryType, adnIndex, entryIndex);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "deleteUPBEntry", e);
            }
        }
    }

    /**
     * Read GAS entry by giving range.
     *
     * @param startIndex GAS index start to read
     * @param endIndex GAS index end to read
     * @param result callback message
     * ((AsyncResult)response.obj).result is a GAS string list
     */
    public void readUPBGasList(int startIndex, int endIndex, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_READ_UPB_GAS_LIST, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) RiljLog(rr.serialString() + "> " + requestToString(rr.mRequest) + ": "
                + " startIndex: " + startIndex + " endIndex: " + endIndex);

            try {
                radioProxy.readUPBGasList(rr.mSerial, startIndex, endIndex);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "readUPBGasList", e);
            }
        }
    }

    /**
     * Read a GRP entry by ADN index.
     *
     * @param adnIndex ADN index
     * @param result callback message
     * ((AsyncResult)response.obj).result is a Group id list of the ADN
     */
    public void readUPBGrpEntry(int adnIndex, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_READ_UPB_GRP, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) RiljLog(rr.serialString() + "> " + requestToString(rr.mRequest) + ": "
                + " adnIndex: " + adnIndex);

            try {
                radioProxy.readUPBGrpEntry(rr.mSerial, adnIndex);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "readUPBGrpEntry", e);
            }
        }
    }

    /**
     * Update a GRP entry by ADN index.
     *
     * @param adnIndex ADN index
     * @param grpIds Group id list to be updated
     * @param result callback message
     */
    public void writeUPBGrpEntry(int adnIndex, int[] grpIds, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_WRITE_UPB_GRP, result,
                    mRILDefaultWorkSource);
            int nLen = grpIds.length;
            if (RILJ_LOGD) RiljLog(rr.serialString() + "> " + requestToString(rr.mRequest) + ": "
                + " adnIndex: " + adnIndex + " nLen: " + nLen);

            ArrayList<Integer> intList = new ArrayList<Integer>(grpIds.length);
            for (int i = 0; i < grpIds.length; i++) {
                intList.add(grpIds[i]);
            }
            try {
                radioProxy.writeUPBGrpEntry(rr.mSerial, adnIndex, intList);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "writeUPBGrpEntry", e);
            }
        }
    }

    /**
     * at+cpbr=?
     * @return  <nlength><tlength><glength><slength><elength>
     */
    public void getPhoneBookStringsLength(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_GET_PHB_STRING_LENGTH, result,
                    mRILDefaultWorkSource);
            if (RILJ_LOGD) RiljLog(rr.serialString() + "> :::" + requestToString(rr.mRequest));

            try {
                radioProxy.getPhoneBookStringsLength(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "getPhoneBookStringsLength", e);
            }
        }
    }

    /**
     * at+cpbs?
     * @return  PBMemStorage :: +cpbs:<storage>,<used>,<total>
     */
    public void getPhoneBookMemStorage(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_GET_PHB_MEM_STORAGE, result,
                    mRILDefaultWorkSource);
            if (RILJ_LOGD) RiljLog(rr.serialString() + "> :::" + requestToString(rr.mRequest));

            try {
                radioProxy.getPhoneBookMemStorage(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "getPhoneBookMemStorage", e);
            }
        }
    }

    /**
     * at+epin2=<p2>; at+cpbs=<storage>
     * @return
     */
    public void setPhoneBookMemStorage(String storage, String password, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_SET_PHB_MEM_STORAGE, result,
                    mRILDefaultWorkSource);
            if (RILJ_LOGD) RiljLog(rr.serialString() + "> :::" + requestToString(rr.mRequest));

            try {
                radioProxy.setPhoneBookMemStorage(rr.mSerial,
                        convertNullToEmptyString(storage),
                        convertNullToEmptyString(password));
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "writeUPBGrpEntry", e);
            }
        }
    }

    /**
     * M at+cpbr=<index1>,<index2> +CPBR:<indexn>,<number>,<type>,<text>,
     * <hidden>,<group>,<adnumber>,<adtype>,<secondtext>,<email>
     */
    public void readPhoneBookEntryExt(int index1, int index2, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_READ_PHB_ENTRY_EXT, result,
                    mRILDefaultWorkSource);
            if (RILJ_LOGD) RiljLog(rr.serialString() + "> :::" + requestToString(rr.mRequest));

            try {
                radioProxy.readPhoneBookEntryExt(rr.mSerial, index1, index2);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "readPhoneBookEntryExt", e);
            }
        }
    }

    /**
     * Convert to PhbEntryExt defined in types.hal.
     * @param pbe PHB entry ext
     * @return A converted PHB entry ext for hal
     */
    private static PhbEntryExt convertToHalPhbEntryExt(PBEntry pbe) {
        PhbEntryExt pee = new PhbEntryExt();

        pee.index = pbe.getIndex1();
        pee.number = pbe.getNumber();
        pee.type = pbe.getType();
        pee.text = pbe.getText();
        pee.hidden = pbe.getHidden();
        pee.group = pbe.getGroup();
        pee.adnumber = pbe.getAdnumber();
        pee.adtype = pbe.getAdtype();
        pee.secondtext = pbe.getSecondtext();
        pee.email = pbe.getEmail();

        return pee;
    }

    /**
     * M AT+CPBW=<index>,<number>,<type>,<text>,<hidden>,<group>,<adnumber>,
     * <adtype>,<secondtext>,<email>
     */
    public void writePhoneBookEntryExt(PBEntry entry, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_WRITE_PHB_ENTRY_EXT, result,
                    mRILDefaultWorkSource);
            if (RILJ_LOGD) RiljLog(rr.serialString() + "> :::" + requestToString(rr.mRequest));

            PhbEntryExt pee = convertToHalPhbEntryExt(entry);

            try {
                radioProxy.writePhoneBookEntryExt(rr.mSerial, pee);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "writePhoneBookEntryExt", e);
            }
        }
    }

    /**
     * Query info of the EF_EMAIL/EF_ANR/EF_Sne.
     *
     * @param eftype 0:EF_ANR, 1:EF_EMAIL, 2: EF_SNE
     * @param fileIndex the i-th EF_EMAIL/EF_ANR/EF_SNE (1-based)
     * @param result callback message
     * ((AsyncResult)response.obj).result[0] is <M_NUM>, Max number of entries
     * ((AsyncResult)response.obj).result[1] is <A_NUM>, Available number of entries
     * ((AsyncResult)response.obj).result[2] is <L_XXX>, Max support length
     */
    public void queryUPBAvailable(int eftype, int fileIndex, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_QUERY_UPB_AVAILABLE, result,
                    mRILDefaultWorkSource);
            if (RILJ_LOGD) RiljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
            + " eftype: " + eftype + " fileIndex: " + fileIndex);

            try {
                radioProxy.queryUPBAvailable(rr.mSerial, eftype, fileIndex);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "queryUPBAvailable", e);
            }
        }
    }

    /**
     * Read a Email entry by ADN index.
     *
     * @param adnIndex ADN index
     * @param fileIndex the i-th EF_EMAIL (1-based)
     * @param result callback message
     * ((AsyncResult)response.obj).result is a Email string
     */
    public void readUPBEmailEntry(int adnIndex, int fileIndex, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_READ_EMAIL_ENTRY, result,
                    mRILDefaultWorkSource);
            if (RILJ_LOGD) RiljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
            + " adnIndex: " + adnIndex + " fileIndex: " + fileIndex);

            try {
                radioProxy.readUPBEmailEntry(rr.mSerial, adnIndex, fileIndex);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "readUPBEmailEntry", e);
            }
        }
    }

    /**
     * Read a SNE entry by ADN index.
     *
     * @param adnIndex ADN index
     * @param fileIndex the i-th EF_SNE (1-based)
     * @param result callback message
     * ((AsyncResult)response.obj).result is a SNE string (need to be decoded)
     */
    public void readUPBSneEntry(int adnIndex, int fileIndex, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_READ_SNE_ENTRY, result,
                    mRILDefaultWorkSource);
            if (RILJ_LOGD) RiljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
            + " adnIndex: " + adnIndex + " fileIndex: " + fileIndex);

            try {
                radioProxy.readUPBSneEntry(rr.mSerial, adnIndex, fileIndex);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "readUPBSneEntry", e);
            }
        }
    }

    /**
     * Read a ANR entry by ADN index.
     *
     * @param adnIndex ADN index
     * @param fileIndex the i-th EF_ANR (1-based)
     * @param result callback message
     * ((AsyncResult)response.obj).result is a ANR contains in PhbEntry
     */
    public void readUPBAnrEntry(int adnIndex, int fileIndex, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_READ_ANR_ENTRY, result,
                    mRILDefaultWorkSource);
            if (RILJ_LOGD) RiljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
            + " adnIndex: " + adnIndex + " fileIndex: " + fileIndex);

            try {
                radioProxy.readUPBAnrEntry(rr.mSerial, adnIndex, fileIndex);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "readUPBAnrEntry", e);
            }
        }
    }

    /**
     * Read AAS entry by giving range.
     *
     * @param startIndex AAS index start to read
     * @param endIndex AAS index end to read
     * @param result callback message
     * ((AsyncResult)response.obj).result is a AAS string list
     */
    public void readUPBAasList(int startIndex, int endIndex, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_READ_UPB_AAS_LIST, result,
                    mRILDefaultWorkSource);
            if (RILJ_LOGD) RiljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
            + " startIndex: " + startIndex + " endIndex: " + endIndex);

            try {
                radioProxy.readUPBAasList(rr.mSerial, startIndex, endIndex);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "readUPBAasList", e);
            }
        }
    }
    // PHB End

    // MTK_TC1_FEATURE for Antenna Testing start
    public void setRxTestConfig (int AntType, Message result) {
        // for op only
        IMtkRilOp rilOp = getRilOp();
        if (rilOp != null) {
            rilOp.setRxTestConfig(AntType, result);
            return;
        }
    }

    public void getRxTestResult(Message result) {
        // for op only
        IMtkRilOp rilOp = getRilOp();
        if (rilOp != null) {
            rilOp.getRxTestResult(result);
            return;
        }
    }

    public void getPOLCapability(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_GET_POL_CAPABILITY, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                RiljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
            }

            try {
                radioProxy.getPOLCapability(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "getPOLCapability", e);
            }
        }
    }

    public void getCurrentPOLList(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_GET_POL_LIST, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                RiljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
            }

            try {
                radioProxy.getCurrentPOLList(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "getCurrentPOLList", e);
            }
        }
    }

    public void setPOLEntry(int index, String numeric, int nAct, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_SET_POL_ENTRY, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                RiljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
            }

            try {
                radioProxy.setPOLEntry(rr.mSerial, index, numeric, nAct);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "setPOLEntry", e);
            }
        }
    }

    // M: [VzW] Data Framework @{
    /**
     * Register for the pco status after data attached.
     * @param h Handler for notification message.
     * @param what User-defined message code.
     * @param obj User object.
     */
    public void registerForPcoDataAfterAttached(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        mPcoDataAfterAttachedRegistrants.add(r);
    }

    /**
     * Unregister for the pco status after data attached.
     * @param h Handler for notification message.
     */
    public void unregisterForPcoDataAfterAttached(Handler h) {
        mPcoDataAfterAttachedRegistrants.remove(h);
    }
    // M: [VzW] Data Framework @}

    // M: Data Framework - common part enhancement @{
    /**
     * Sync data related settings to MD
     *
     * @param dataSetting[] data related setting
     *              dataSetting[0]: data setting on/off
     *              dataSetting[1]: data roaming setting on/off
     *              dataSetting[2]: default data sim
     * @param result for result
     */
    public void syncDataSettingsToMd(int[] dataSetting, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            // AT+ECNCFG=<mobile_data>,<data_roaming>,[<volte>,<ims_test_mode>]
            RILRequest rr = obtainRequest(MtkRILConstants.RIL_REQUEST_SYNC_DATA_SETTINGS_TO_MD,
                    result, mRILDefaultWorkSource);

            if (RILJ_LOGV) {
                RiljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                    + ", " + dataSetting[0] + ", " + dataSetting[1] + ", " + dataSetting[2]);
            }

            ArrayList<Integer> settingList = new ArrayList<Integer>(dataSetting.length);
            for (int i = 0; i < dataSetting.length; i++) {
                settingList.add(dataSetting[i]);
            }

            try {
                radioProxy.syncDataSettingsToMd(rr.mSerial, settingList);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "syncDataSettingsToMd", e);
            }
        }
    }
    // M: Data Framework - common part enhancement @}

    // M: Data Framework - Data Retry enhancement @{
    // Reset the data retry count in modem
    public void resetMdDataRetryCount(String apnName, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            //AT+EDRETRY=1,<apn name>
            RILRequest rr = obtainRequest(MtkRILConstants.RIL_REQUEST_RESET_MD_DATA_RETRY_COUNT,
                    result, mRILDefaultWorkSource);

            if (RILJ_LOGD) RiljLog(rr.serialString() + "> "
                                    + requestToString(rr.mRequest) + ": " + apnName);
            try {
                radioProxy.resetMdDataRetryCount(rr.mSerial, apnName);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "resetMdDataRetryCount", e);
            }
        }
    }
    public void registerForMdDataRetryCountReset(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        mMdDataRetryCountResetRegistrants.add(r);
    }
    public void unregisterForMdDataRetryCountReset(Handler h) {
        mMdDataRetryCountResetRegistrants.remove(h);
    }
    // M: Data Framework - Data Retry enhancement @}

    // M: Data Framework - CC 33 @{
    public void setRemoveRestrictEutranMode(boolean enable, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            //AT+ECODE33 = <on/off>
            RILRequest rr = obtainRequest(
                    MtkRILConstants.RIL_REQUEST_SET_REMOVE_RESTRICT_EUTRAN_MODE,
                    result, mRILDefaultWorkSource);
            int type = enable ? 1 : 0;
            if (RILJ_LOGD) RiljLog(rr.serialString() + "> "
                                    + requestToString(rr.mRequest) + ": " + type);
            try {
                radioProxy.setRemoveRestrictEutranMode(rr.mSerial, type);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "setRemoveRestrictEutranMode", e);
            }
        }
    }
    public void registerForRemoveRestrictEutran(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        mRemoveRestrictEutranRegistrants.add(r);
    }
    public void unregisterForRemoveRestrictEutran(Handler h) {
        mRemoveRestrictEutranRegistrants.remove(h);
    }
    // M: Data Framework - CC 33 @}

    // For IMS conference
    /* Register for updating call ids for conference call after SRVCC is done. */
    protected RegistrantList mEconfSrvccRegistrants = new RegistrantList();

    /* Register for updating call ids for conference call after SRVCC is done. */
    public void registerForEconfSrvcc(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        mEconfSrvccRegistrants.add(r);
    }

    public void unregisterForEconfSrvcc(Handler h) {
        mEconfSrvccRegistrants.remove(h);
    }

    /// M: [Network][C2K] Sprint roaming control @{
    public void setRoamingEnable(int[] config, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_SET_ROAMING_ENABLE, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                RiljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
            }

            ArrayList<Integer> intList = new ArrayList<Integer>(config.length);
            for (int i = 0; i < config.length; i++) {
                intList.add(config[i]);
            }

            try {
                radioProxy.setRoamingEnable(rr.mSerial, intList);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "setRoamingEnable", e);
            }
        }
    }

    public void getRoamingEnable(int phoneId, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_GET_ROAMING_ENABLE, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                RiljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
            }

            try {
                radioProxy.getRoamingEnable(rr.mSerial, phoneId);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "getRoamingEnable", e);
            }
        }
    }
    /// @}

    // M: Data Framework - common part enhancement @{
    /**
     * Convert to MtkDataProfileInfo defined in types.hal
     * @param dp Data profile
     * @return A converted data profile
     */
    private static MtkDataProfileInfo convertToHalMtkDataProfile(MtkDataProfile dp) {
        MtkDataProfileInfo dpiResult = new MtkDataProfileInfo();

        dpiResult.dpi.profileId = dp.profileId;
        dpiResult.dpi.apn = dp.apn;
        dpiResult.dpi.protocol = dp.protocol;
        dpiResult.dpi.roamingProtocol = dp.roamingProtocol;
        dpiResult.dpi.authType = dp.authType;
        dpiResult.dpi.user = dp.user;
        dpiResult.dpi.password = dp.password;
        dpiResult.dpi.type = dp.type;
        dpiResult.dpi.maxConnsTime = dp.maxConnsTime;
        dpiResult.dpi.maxConns = dp.maxConns;
        dpiResult.dpi.waitTime = dp.waitTime;
        dpiResult.dpi.enabled = dp.enabled;
        dpiResult.dpi.supportedApnTypesBitmap = dp.supportedApnTypesBitmap;
        dpiResult.dpi.bearerBitmap = dp.bearerBitmap;
        dpiResult.dpi.mtu = dp.mtu;
        dpiResult.dpi.mvnoType = convertToHalMvnoType(dp.mvnoType);
        dpiResult.dpi.mvnoMatchData = dp.mvnoMatchData;
        dpiResult.inactiveTimer = dp.inactiveTimer;

        return dpiResult;
    }

    public void setDataProfileEx(MtkDataProfile[] dps, boolean isRoaming, Message result) {

        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_SET_DATA_PROFILE, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                RiljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " with MtkDataProfiles : ");
                for (MtkDataProfile profile : dps) {
                    RiljLog(profile.toString());
                }
            }

            ArrayList<MtkDataProfileInfo> dpis = new ArrayList<>();
            for (MtkDataProfile dp : dps) {
                dpis.add(convertToHalMtkDataProfile(dp));
            }

            try {
                radioProxy.setDataProfileEx(rr.mSerial, dpis, isRoaming);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "setDataProfileEx", e);
            }
        }
    }
    // M: Data Framework - common part enhancement @}

    // External SIM [Start]
    protected RegistrantList mVsimIndicationRegistrants = new RegistrantList();
    public void registerForVsimIndication(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        if (RILJ_LOGD)  RiljLog("registerForVsimIndication called...");
        mVsimIndicationRegistrants.add(r);
    }

    public void unregisterForVsimIndication(Handler h) {
        if (RILJ_LOGD)  RiljLog("unregisterForVsimIndication called...");
        mVsimIndicationRegistrants.remove(h);
    }

    public boolean sendVsimNotification(
            int transactionId, int eventId, int simType, Message message) {
        boolean result = true;

        IRadio radioProxy = getRadioProxy(message);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_VSIM_NOTIFICATION, message,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                RiljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + ", eventId: " +  eventId
                        + ", simTpye: " + simType);
            }

            try {
                radioProxy.sendVsimNotification(rr.mSerial, transactionId, eventId, simType);
            } catch (RemoteException e) {
                handleRadioProxyExceptionForRR(rr, "sendVsimNotification", e);
                result = false;
            }
        }

        return result;
    }

    public boolean sendVsimOperation(int transactionId, int eventId, int message,
            int dataLength, byte[] data, Message response) {
        boolean result = true;

        vendor.mediatek.hardware.radio.V2_0.IRadio radioProxy = getRadioProxy(response);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_VSIM_OPERATION, response,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                RiljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
            }

            if (RILJ_LOGV) {
                RiljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + ", eventId: " + eventId
                        + ", length: " + dataLength
                        + " data = 0x" + IccUtils.bytesToHexString(data));
            }

            ArrayList<Byte> arrList = new ArrayList<>();
            for (int i = 0; i < data.length; i++) {
                arrList.add(data[i]);
            }

            try {
                radioProxy.sendVsimOperation(rr.mSerial, transactionId, eventId,
                        message, dataLength, arrList);
            } catch (RemoteException e) {
                handleRadioProxyExceptionForRR(rr, "sendVsimOperation", e);
                result = false;
            }
        }

        return result;
    }
    // External SIM [End]

    /// Ims Data Framework {@
    protected RegistrantList mDedicatedBearerActivedRegistrants = new RegistrantList();
    public void registerForDedicatedBearerActivated(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        mDedicatedBearerActivedRegistrants.add(r);
    }

    public void unregisterForDedicatedBearerActivated(Handler h) {
        mDedicatedBearerActivedRegistrants.remove(h);
    }

    protected RegistrantList mDedicatedBearerModifiedRegistrants = new RegistrantList();
    public void registerForDedicatedBearerModified(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        mDedicatedBearerModifiedRegistrants.add(r);
    }

    public void unregisterForDedicatedBearerModified(Handler h) {
        mDedicatedBearerModifiedRegistrants.remove(h);
    }

    protected RegistrantList mDedicatedBearerDeactivatedRegistrants = new RegistrantList();
    public void registerForDedicatedBearerDeactivationed(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        mDedicatedBearerDeactivatedRegistrants.add(r);
    }

    public void unregisterForDedicatedBearerDeactivationed(Handler h) {
        mDedicatedBearerDeactivatedRegistrants.remove(h);
    }

    public MtkDedicateDataCallResponse convertDedicatedDataCallResult(DedicateDataCall ddcResult) {

        int ddcId = ddcResult.ddcId;
        int interfaceId = ddcResult.interfaceId;
        int primaryCid = ddcResult.primaryCid;
        int cid = ddcResult.cid;
        int active = ddcResult.active;
        int signalingFlag = ddcResult.signalingFlag;
        int bearerId = ddcResult.bearerId;
        int failCause = ddcResult.failCause;

        MtkQosStatus mtkQosStatus = null;
        RiljLog("ddcResult.hasQos: " + ddcResult.hasQos);
        if (ddcResult.hasQos != 0) {
            int qci = ddcResult.qos.qci;
            int dlGbr = ddcResult.qos.dlGbr;
            int ulGbr = ddcResult.qos.ulGbr;
            int dlMbr = ddcResult.qos.dlMbr;
            int ulMbr = ddcResult.qos.ulMbr;
            mtkQosStatus = new MtkQosStatus(qci, dlGbr, ulGbr, dlMbr, ulMbr);
        }

        MtkTftStatus mtkTftStatus = null;
        RiljLog("ddcResult.hasTft: " + ddcResult.hasTft);
        if (ddcResult.hasTft != 0) {
            int operation = ddcResult.tft.operation;
            ArrayList<MtkPacketFilterInfo> mtkPacketFilterInfo
                    = new ArrayList<MtkPacketFilterInfo>();
            for(PktFilter info : ddcResult.tft.pfList){
                MtkPacketFilterInfo pfInfo
                        = new MtkPacketFilterInfo(info.id, info.precedence, info.direction,
                                                  info.networkPfIdentifier, info.bitmap,
                                                  info.address, info.mask,
                                                  info.protocolNextHeader, info.localPortLow,
                                                  info.localPortHigh, info.remotePortLow,
                                                  info.remotePortHigh, info.spi, info.tos,
                                                  info.tosMask, info.flowLabel);
                mtkPacketFilterInfo.add(pfInfo);
            }

            ArrayList<Integer> pfList = (ArrayList<Integer>)ddcResult.tft.tftParameter.linkedPfList;
            MtkTftParameter mtkTftParameter = new MtkTftParameter(pfList);
            mtkTftStatus = new MtkTftStatus(operation, mtkPacketFilterInfo, mtkTftParameter);
        }

        String pcscfAddress = ddcResult.pcscf;
        return new MtkDedicateDataCallResponse(interfaceId, primaryCid, cid, active,
                                               signalingFlag, bearerId, failCause,
                                               mtkQosStatus, mtkTftStatus, pcscfAddress);
    }
    /// @}

    public void setWifiEnabled(String ifName, int isEnabled, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(MtkRILConstants.RIL_REQUEST_SET_WIFI_ENABLED,
                    result, mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                RiljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
            }
            try {
                radioProxy.setWifiEnabled(rr.mSerial, mInstanceId.intValue(), ifName, isEnabled);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "setWifiEnabled", e);
            }
        }
    }

    public void setWifiFlightModeEnabled(String ifName, int isWifiEnabled, int isFlightModeOn,
            Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            vendor.mediatek.hardware.radio.V2_3.IRadio radioProxy23 =
                    vendor.mediatek.hardware.radio.V2_3.IRadio.castFrom(radioProxy);
            if (radioProxy23 == null) {
                if (result != null) {
                    AsyncResult.forMessage(result, null,
                            CommandException.fromRilErrno(REQUEST_NOT_SUPPORTED));
                    result.sendToTarget();
                }
            } else {
                RILRequest rr = obtainRequest(MtkRILConstants.RIL_REQUEST_SET_WIFI_ENABLED,
                    result, mRILDefaultWorkSource);

                if (RILJ_LOGD) {
                    RiljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
                }
                try {
                    radioProxy23.setWifiFlightModeEnabled(rr.mSerial, mInstanceId.intValue(), ifName,
                        isWifiEnabled, isFlightModeOn);
                } catch (RemoteException | RuntimeException e) {
                    handleRadioProxyExceptionForRR(rr, "setWifiFlightModeEnabled", e);
                }
            }
        }
    }

    public void setWifiAssociated(String ifName, boolean associated, String ssid,
            String apMac, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(MtkRILConstants.RIL_REQUEST_SET_WIFI_ASSOCIATED,
                    result, mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                RiljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
            }
            try {
                radioProxy.setWifiAssociated(rr.mSerial, mInstanceId.intValue(),
                    ifName, (associated ? 1 : 0), ssid, apMac);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "setWifiSignalLevel", e);
            }
        }
    }

    public void setWifiSignalLevel(int rssi, int snr, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(MtkRILConstants.RIL_REQUEST_SET_WIFI_SIGNAL_LEVEL, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                RiljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " " + rssi + ", " + snr);
            }
            try {
                radioProxy.setWifiSignalLevel(rr.mSerial, mInstanceId.intValue(), rssi, snr);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "setWifiSignalLevel", e);
            }
        }
    }

    public void setWifiIpAddress(String ifName, String ipv4Addr,
            String ipv6Addr, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(MtkRILConstants.RIL_REQUEST_SET_WIFI_IP_ADDRESS,
                    result, mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                RiljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
            }
            try {
                radioProxy.setWifiIpAddress(rr.mSerial, mInstanceId.intValue(),
                        ifName, ipv4Addr, ipv6Addr);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "setWifiIpAddress", e);
            }
        }
    }

    public void setLocationInfo(String accountId, String broadcastFlag, String latitude,
            String longitude, String accuracy, String method, String city, String state,
            String zip, String countryCode, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(MtkRILConstants.RIL_REQUEST_SET_GEO_LOCATION,
                    result, mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                RiljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
            }
            try {
                radioProxy.setLocationInfo(rr.mSerial, mInstanceId.intValue(),
                        convertNullToEmptyString(accountId),
                        convertNullToEmptyString(broadcastFlag),
                        convertNullToEmptyString(latitude),
                        convertNullToEmptyString(longitude),
                        convertNullToEmptyString(accuracy),
                        convertNullToEmptyString(method),
                        convertNullToEmptyString(city),
                        convertNullToEmptyString(state),
                        convertNullToEmptyString(zip),
                        convertNullToEmptyString(countryCode));

            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "setLocationInfo", e);
            }
        }
    }

    public void setLocationInfoWlanMac(String accountId, String broadcastFlag, String latitude,
                String longitude, String accuracy, String method, String city, String state,
                String zip, String countryCode, String ueWlanMac, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            vendor.mediatek.hardware.radio.V2_3.IRadio radioProxy23 =
                    vendor.mediatek.hardware.radio.V2_3.IRadio.castFrom(radioProxy);
            if (radioProxy23 == null) {
                if (result != null) {
                    AsyncResult.forMessage(result, null,
                            CommandException.fromRilErrno(REQUEST_NOT_SUPPORTED));
                    result.sendToTarget();
                }
            } else {
                RILRequest rr = obtainRequest(MtkRILConstants.RIL_REQUEST_SET_GEO_LOCATION,
                    result, mRILDefaultWorkSource);

                if (RILJ_LOGD) {
                    RiljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
                }
                try {
                    radioProxy23.setLocationInfoWlanMac(rr.mSerial, mInstanceId.intValue(),
                            convertNullToEmptyString(accountId),
                            convertNullToEmptyString(broadcastFlag),
                            convertNullToEmptyString(latitude),
                            convertNullToEmptyString(longitude),
                            convertNullToEmptyString(accuracy),
                            convertNullToEmptyString(method),
                            convertNullToEmptyString(city),
                            convertNullToEmptyString(state),
                            convertNullToEmptyString(zip),
                            convertNullToEmptyString(countryCode),
                            convertNullToEmptyString(ueWlanMac));
                } catch (RemoteException | RuntimeException e) {
                    handleRadioProxyExceptionForRR(rr, "setLocationInfoWlanMac", e);
                }
            }
        }
    }

    public void setEmergencyAddressId(String aid, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(MtkRILConstants.RIL_REQUEST_SET_EMERGENCY_ADDRESS_ID,
                    result, mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                RiljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
            }
            try {
                radioProxy.setEmergencyAddressId(rr.mSerial, mInstanceId.intValue(), aid);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "setEmergencyAddressId", e);
            }
        }
    }

    public void setNattKeepAliveStatus(String ifName, boolean enable,
            String srcIp, int srcPort,
            String dstIp, int dstPort, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(MtkRILConstants.RIL_REQUEST_SET_NATT_KEEPALIVE_STATUS,
                    result, mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                RiljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
            }
            try {
                radioProxy.setNattKeepAliveStatus(rr.mSerial, mInstanceId.intValue(),
                        ifName, enable, srcIp, srcPort, dstIp, dstPort);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "setNattKeepAliveStatus", e);
            }
        }
    }

    /**
     * Request modem to exit ECBM(MD1)
     *
     * @param result callback message
     * @hide
     */
    public void setE911State(int state, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(
                    MtkRILConstants.RIL_REQUEST_SET_E911_STATE,
                    result, mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                RiljLog(rr.serialString() + "> " + requestToString(rr.mRequest) +
                        ", state: " + state);
            }

            try {
                radioProxy.setE911State(rr.mSerial, state);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "setE911State", e);
            }
        }
    }

    public void setServiceStateToModem(int voiceRegState, int dataRegState, int voiceRoamingType,
            int dataRoamingType, int rilVoiceRegState, int rilDataRegState, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(MtkRILConstants.RIL_REQUEST_SET_SERVICE_STATE, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                RiljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                + " voiceRegState: " + voiceRegState
                + " dataRegState: " + dataRegState
                + " voiceRoamingType: " + voiceRoamingType
                + " dataRoamingType: " + dataRoamingType
                + " rilVoiceRegState: " + rilVoiceRegState
                + " rilDataRegState:" + rilDataRegState);
            }

            try {
                radioProxy.setServiceStateToModem(rr.mSerial,
                        voiceRegState, dataRegState, voiceRoamingType, dataRoamingType,
                        rilVoiceRegState, rilDataRegState);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "setServiceStateToModem", e);
            }
        }
    }

    @Override
    public void supplyNetworkDepersonalization(String netpin, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_ENTER_NETWORK_DEPERSONALIZATION, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest) + " netpin = "
                        + netpin);
            }

            try {
                radioProxy.supplyNetworkDepersonalization(rr.mSerial,
                        convertNullToEmptyString(netpin));
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "supplyNetworkDepersonalization", e);
            }
        }
    }

    @Override
    public void getCurrentCalls(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_GET_CURRENT_CALLS, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
            }

            try {
                radioProxy.getCurrentCalls(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "getCurrentCalls", e);
            }
        }
    }

    @Override
    public void dial(String address, int clirMode, Message result) {
        dial(address, clirMode, null, result);
    }

    @Override
    public void dial(String address, int clirMode, UUSInfo uusInfo, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_DIAL, result,
                    mRILDefaultWorkSource);

            Dial dialInfo = new Dial();
            dialInfo.address = convertNullToEmptyString(address);
            dialInfo.clir = clirMode;
            if (uusInfo != null) {
                UusInfo info = new UusInfo();
                info.uusType = uusInfo.getType();
                info.uusDcs = uusInfo.getDcs();
                info.uusData = new String(uusInfo.getUserData());
                dialInfo.uusInfo.add(info);
            }

            if (RILJ_LOGD) {
                // Do not log function arg for privacy
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
            }

            try {
                radioProxy.dial(rr.mSerial, dialInfo);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "dial", e);
            }
        }
    }

    @Override
    public void getIMSI(Message result) {
        getIMSIForApp(null, result);
    }

    @Override
    public void getIMSIForApp(String aid, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_GET_IMSI, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString()
                        + ">  " + requestToString(rr.mRequest) + " aid = " + aid);
            }
            try {
                radioProxy.getImsiForApp(rr.mSerial, convertNullToEmptyString(aid));
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "getIMSIForApp", e);
            }
        }
    }

    @Override
    public void hangupConnection(int gsmIndex, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_HANGUP, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest) + " gsmIndex = "
                        + gsmIndex);
            }

            try {
                radioProxy.hangup(rr.mSerial, gsmIndex);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "hangupConnection", e);
            }
        }
    }

    @Override
    public void hangupWaitingOrBackground(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_HANGUP_WAITING_OR_BACKGROUND, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            try {
                radioProxy.hangupWaitingOrBackground(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "hangupWaitingOrBackground", e);
            }
        }
    }

    @Override
    public void hangupForegroundResumeBackground(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_HANGUP_FOREGROUND_RESUME_BACKGROUND, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            try {
                radioProxy.hangupForegroundResumeBackground(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "hangupForegroundResumeBackground", e);
            }
        }
    }

    @Override
    public void switchWaitingOrHoldingAndActive(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_SWITCH_WAITING_OR_HOLDING_AND_ACTIVE, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            try {
                radioProxy.switchWaitingOrHoldingAndActive(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "switchWaitingOrHoldingAndActive", e);
            }
        }
    }

    @Override
    public void conference(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_CONFERENCE, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            try {
                radioProxy.conference(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "conference", e);
            }
        }
    }

    @Override
    public void rejectCall(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_UDUB, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            try {
                radioProxy.rejectCall(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "rejectCall", e);
            }
        }
    }

    @Override
    public void getLastCallFailCause(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_LAST_CALL_FAIL_CAUSE, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            try {
                radioProxy.getLastCallFailCause(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "getLastCallFailCause", e);
            }
        }
    }

    @Override
    public void getSignalStrength(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_SIGNAL_STRENGTH, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            try {
                radioProxy.getSignalStrength(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "getSignalStrength", e);
            }
        }
    }

    @Override
    public void getVoiceRegistrationState(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_VOICE_REGISTRATION_STATE, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            try {
                radioProxy.getVoiceRegistrationState(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "getVoiceRegistrationState", e);
            }
        }
    }

    @Override
    public void getDataRegistrationState(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_DATA_REGISTRATION_STATE, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            try {
                radioProxy.getDataRegistrationState(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "getDataRegistrationState", e);
            }
        }
    }

    @Override
    public void getOperator(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_OPERATOR, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            try {
                radioProxy.getOperator(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "getOperator", e);
            }
        }
    }

    @Override
    public void setRadioPower(boolean on, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_RADIO_POWER, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " on = " + on);
            }

            try {
                radioProxy.setRadioPower(rr.mSerial, on);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "setRadioPower", e);
            }
        }
    }

    @Override
    public void sendDtmf(char c, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_DTMF, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                // Do not log function arg for privacy
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
            }

            try {
                radioProxy.sendDtmf(rr.mSerial, c + "");
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "sendDtmf", e);
            }
        }
    }

    private GsmSmsMessage constructGsmSendSmsRilRequest(String smscPdu, String pdu) {
        GsmSmsMessage msg = new GsmSmsMessage();
        msg.smscPdu = smscPdu == null ? "" : smscPdu;
        msg.pdu = pdu == null ? "" : pdu;
        return msg;
    }

    @Override
    public void sendSMS(String smscPdu, String pdu, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_SEND_SMS, result,
                    mRILDefaultWorkSource);

            // Do not log function args for privacy
            if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            GsmSmsMessage msg = constructGsmSendSmsRilRequest(smscPdu, pdu);

            try {
                radioProxy.sendSms(rr.mSerial, msg);
                mMetrics.writeRilSendSms(mPhoneId, rr.mSerial, SmsSession.Event.Tech.SMS_GSM,
                        SmsSession.Event.Format.SMS_FORMAT_3GPP);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "sendSMS", e);
            }
        }
    }

    @Override
    public void sendSMSExpectMore(String smscPdu, String pdu, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_SEND_SMS_EXPECT_MORE, result,
                    mRILDefaultWorkSource);

            // Do not log function arg for privacy
            if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            GsmSmsMessage msg = constructGsmSendSmsRilRequest(smscPdu, pdu);

            try {
                radioProxy.sendSMSExpectMore(rr.mSerial, msg);
                mMetrics.writeRilSendSms(mPhoneId, rr.mSerial, SmsSession.Event.Tech.SMS_GSM,
                        SmsSession.Event.Format.SMS_FORMAT_3GPP);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "sendSMSExpectMore", e);
            }
        }
    }

    /**
     * Convert MVNO type string into MvnoType defined in types.hal.
     * @param mvnoType MVNO type
     * @return MVNO type in integer
     */
    private static int convertToHalMvnoType(String mvnoType) {
        switch (mvnoType) {
            case "imsi" : return MvnoType.IMSI;
            case "gid" : return MvnoType.GID;
            case "spn" : return MvnoType.SPN;
            default: return MvnoType.NONE;
        }
    }

    /**
     * Convert to DataProfileInfo defined in types.hal
     * @param dp Data profile
     * @return A converted data profile
     */
    private static DataProfileInfo convertToHalDataProfile(DataProfile dp) {
        DataProfileInfo dpi = new DataProfileInfo();

        dpi.profileId = dp.profileId;
        dpi.apn = dp.apn;
        dpi.protocol = dp.protocol;
        dpi.roamingProtocol = dp.roamingProtocol;
        dpi.authType = dp.authType;
        dpi.user = dp.user;
        dpi.password = dp.password;
        dpi.type = dp.type;
        dpi.maxConnsTime = dp.maxConnsTime;
        dpi.maxConns = dp.maxConns;
        dpi.waitTime = dp.waitTime;
        dpi.enabled = dp.enabled;
        dpi.supportedApnTypesBitmap = dp.supportedApnTypesBitmap;
        dpi.bearerBitmap = dp.bearerBitmap;
        dpi.mtu = dp.mtu;
        dpi.mvnoType = convertToHalMvnoType(dp.mvnoType);
        dpi.mvnoMatchData = dp.mvnoMatchData;

        return dpi;
    }

    /**
     * Convert NV reset type into ResetNvType defined in types.hal.
     * @param resetType NV reset type.
     * @return Converted reset type in integer or -1 if param is invalid.
     */
    private static int convertToHalResetNvType(int resetType) {
        /**
         * resetType values
         * 1 - reload all NV items
         * 2 - erase NV reset (SCRTN)
         * 3 - factory reset (RTN)
         */
        switch (resetType) {
            case 1: return ResetNvType.RELOAD;
            case 2: return ResetNvType.ERASE;
            case 3: return ResetNvType.FACTORY_RESET;
        }
        return -1;
    }

    /**
     * Convert SetupDataCallResult defined in types.hal into DataCallResponse
     * @param dcResult setup data call result
     * @return converted DataCallResponse object
     */
    static DataCallResponse convertDataCallResult(SetupDataCallResult dcResult) {
        return new DataCallResponse(dcResult.status,
                dcResult.suggestedRetryTime,
                dcResult.cid,
                dcResult.active,
                dcResult.type,
                dcResult.ifname,
                dcResult.addresses,
                dcResult.dnses,
                dcResult.gateways,
                dcResult.pcscf,
                dcResult.mtu
        );
    }

    @Override
    public void setupDataCall(int radioTechnology, DataProfile dataProfile, boolean isRoaming,
                              boolean allowRoaming, Message result) {

        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {

            RILRequest rr = obtainRequest(RIL_REQUEST_SETUP_DATA_CALL, result,
                    mRILDefaultWorkSource);

            // Convert to HAL data profile
            DataProfileInfo dpi = convertToHalDataProfile(dataProfile);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + ",radioTechnology=" + radioTechnology + ",isRoaming="
                        + isRoaming + ",allowRoaming=" + allowRoaming + "," + dataProfile);
            }

            try {
                radioProxy.setupDataCall(rr.mSerial, radioTechnology, dpi,
                        dataProfile.modemCognitive, allowRoaming, isRoaming);
                mMetrics.writeRilSetupDataCall(mPhoneId, rr.mSerial, radioTechnology, dpi.profileId,
                        dpi.apn, dpi.authType, dpi.protocol);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "setupDataCall", e);
            }
        }
    }

    @Override
    public void iccIO(int command, int fileId, String path, int p1, int p2, int p3,
                      String data, String pin2, Message result) {
        iccIOForApp(command, fileId, path, p1, p2, p3, data, pin2, null, result);
    }

    @Override
    public void iccIOForApp(int command, int fileId, String path, int p1, int p2, int p3,
                 String data, String pin2, String aid, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_SIM_IO, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> iccIO: "
                        + requestToString(rr.mRequest) + " command = 0x"
                        + Integer.toHexString(command) + " fileId = 0x"
                        + Integer.toHexString(fileId) + " path = " + path + " p1 = "
                        + p1 + " p2 = " + p2 + " p3 = " + " data = " + data
                        + " aid = " + aid);
            }

            IccIo iccIo = new IccIo();
            iccIo.command = command;
            iccIo.fileId = fileId;
            iccIo.path = convertNullToEmptyString(path);
            iccIo.p1 = p1;
            iccIo.p2 = p2;
            iccIo.p3 = p3;
            iccIo.data = convertNullToEmptyString(data);
            iccIo.pin2 = convertNullToEmptyString(pin2);
            iccIo.aid = convertNullToEmptyString(aid);

            try {
                radioProxy.iccIOForApp(rr.mSerial, iccIo);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "iccIOForApp", e);
            }
        }
    }

    @Override
    public void sendUSSD(String ussd, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_SEND_USSD, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                String logUssd = "*******";
                if (RILJ_LOGV) logUssd = ussd;
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " ussd = " + logUssd);
            }

            try {
                radioProxy.sendUssd(rr.mSerial, convertNullToEmptyString(ussd));
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "sendUSSD", e);
            }
        }
    }

    @Override
    public void cancelPendingUssd(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_CANCEL_USSD, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString()
                        + "> " + requestToString(rr.mRequest));
            }

            try {
                radioProxy.cancelPendingUssd(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "cancelPendingUssd", e);
            }
        }
    }

    @Override
    public void getCLIR(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_GET_CLIR, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            try {
                radioProxy.getClir(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "getCLIR", e);
            }
        }
    }

    @Override
    public void setCLIR(int clirMode, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_SET_CLIR, result, mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " clirMode = " + clirMode);
            }

            try {
                radioProxy.setClir(rr.mSerial, clirMode);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "setCLIR", e);
            }
        }
    }

    @Override
    public void queryCallForwardStatus(int cfReason, int serviceClass,
                           String number, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_QUERY_CALL_FORWARD_STATUS, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " cfreason = " + cfReason + " serviceClass = " + serviceClass);
            }

            android.hardware.radio.V1_0.CallForwardInfo cfInfo =
                    new android.hardware.radio.V1_0.CallForwardInfo();
            cfInfo.reason = cfReason;
            cfInfo.serviceClass = serviceClass;
            cfInfo.toa = PhoneNumberUtils.toaFromString(number);
            cfInfo.number = convertNullToEmptyString(number);
            cfInfo.timeSeconds = 0;

            try {
                radioProxy.getCallForwardStatus(rr.mSerial, cfInfo);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "queryCallForwardStatus", e);
            }
        }
    }

    @Override
    public void setCallForward(int action, int cfReason, int serviceClass,
                   String number, int timeSeconds, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_SET_CALL_FORWARD, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " action = " + action + " cfReason = " + cfReason + " serviceClass = "
                        + serviceClass + " timeSeconds = " + timeSeconds);
            }

            android.hardware.radio.V1_0.CallForwardInfo cfInfo =
                    new android.hardware.radio.V1_0.CallForwardInfo();
            cfInfo.status = action;
            cfInfo.reason = cfReason;
            cfInfo.serviceClass = serviceClass;
            cfInfo.toa = PhoneNumberUtils.toaFromString(number);
            cfInfo.number = convertNullToEmptyString(number);
            cfInfo.timeSeconds = timeSeconds;

            try {
                radioProxy.setCallForward(rr.mSerial, cfInfo);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "setCallForward", e);

            }
        }
    }

    @Override
    public void queryCallWaiting(int serviceClass, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_QUERY_CALL_WAITING, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " serviceClass = " + serviceClass);
            }

            try {
                radioProxy.getCallWaiting(rr.mSerial, serviceClass);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "queryCallWaiting", e);
            }
        }
    }

    @Override
    public void setCallWaiting(boolean enable, int serviceClass, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_SET_CALL_WAITING, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " enable = " + enable + " serviceClass = " + serviceClass);
            }

            try {
                radioProxy.setCallWaiting(rr.mSerial, enable, serviceClass);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "setCallWaiting", e);
            }
        }
    }

    @Override
    public void acknowledgeLastIncomingGsmSms(boolean success, int cause, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_SMS_ACKNOWLEDGE, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " success = " + success + " cause = " + cause);
            }

            try {
                radioProxy.acknowledgeLastIncomingGsmSms(rr.mSerial, success, cause);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "acknowledgeLastIncomingGsmSms", e);
            }
        }
    }

    @Override
    public void acceptCall(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_ANSWER, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
            }

            try {
                radioProxy.acceptCall(rr.mSerial);
                mMetrics.writeRilAnswer(mPhoneId, rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "acceptCall", e);
            }
        }
    }

    @Override
    public void deactivateDataCall(int cid, int reason, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_DEACTIVATE_DATA_CALL, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> "
                        + requestToString(rr.mRequest) + " cid = " + cid + " reason = " + reason);
            }

            try {
                radioProxy.deactivateDataCall(rr.mSerial, cid, (reason == 0) ? false : true);
                mMetrics.writeRilDeactivateDataCall(mPhoneId, rr.mSerial,
                        cid, reason);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "deactivateDataCall", e);
            }
        }
    }

    @Override
    public void queryFacilityLock(String facility, String password, int serviceClass,
                                  Message result) {
        queryFacilityLockForApp(facility, password, serviceClass, null, result);
    }

    @Override
    public void queryFacilityLockForApp(String facility, String password, int serviceClass,
                                        String appId, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_QUERY_FACILITY_LOCK, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " facility = " + facility + " serviceClass = " + serviceClass
                        + " appId = " + appId);
            }

            try {
                radioProxy.getFacilityLockForApp(rr.mSerial,
                        convertNullToEmptyString(facility),
                        convertNullToEmptyString(password),
                        serviceClass,
                        convertNullToEmptyString(appId));
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "getFacilityLockForApp", e);
            }
        }
    }

    @Override
    public void setFacilityLock(String facility, boolean lockState, String password,
                                int serviceClass, Message result) {
        setFacilityLockForApp(facility, lockState, password, serviceClass, null, result);
    }

    @Override
    public void setFacilityLockForApp(String facility, boolean lockState, String password,
                                      int serviceClass, String appId, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_SET_FACILITY_LOCK, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " facility = " + facility + " lockstate = " + lockState
                        + " serviceClass = " + serviceClass + " appId = " + appId);
            }

            try {
                radioProxy.setFacilityLockForApp(rr.mSerial,
                        convertNullToEmptyString(facility),
                        lockState,
                        convertNullToEmptyString(password),
                        serviceClass,
                        convertNullToEmptyString(appId));
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "setFacilityLockForApp", e);
            }
        }
    }

    @Override
    public void changeBarringPassword(String facility, String oldPwd, String newPwd,
                                      Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_CHANGE_BARRING_PASSWORD, result,
                    mRILDefaultWorkSource);

            // Do not log all function args for privacy
            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + "facility = " + facility);
            }

            try {
                radioProxy.setBarringPassword(rr.mSerial,
                        convertNullToEmptyString(facility),
                        convertNullToEmptyString(oldPwd),
                        convertNullToEmptyString(newPwd));
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "changeBarringPassword", e);
            }
        }
    }

    @Override
    public void getNetworkSelectionMode(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_QUERY_NETWORK_SELECTION_MODE, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            try {
                radioProxy.getNetworkSelectionMode(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "getNetworkSelectionMode", e);
            }
        }
    }

    @Override
    public void setNetworkSelectionModeAutomatic(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_SET_NETWORK_SELECTION_AUTOMATIC, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            try {
                radioProxy.setNetworkSelectionModeAutomatic(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "setNetworkSelectionModeAutomatic", e);
            }
        }
    }

    @Override
    public void setNetworkSelectionModeManual(String operatorNumeric, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_SET_NETWORK_SELECTION_MANUAL, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " operatorNumeric = " + operatorNumeric);
            }

            try {
                radioProxy.setNetworkSelectionModeManual(rr.mSerial,
                        convertNullToEmptyString(operatorNumeric));
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "setNetworkSelectionModeManual", e);
            }
        }
    }

    @Override
    public void getAvailableNetworks(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_QUERY_AVAILABLE_NETWORKS, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            try {
                radioProxy.getAvailableNetworks(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "getAvailableNetworks", e);
            }
        }
    }

    @Override
    public void startNetworkScan(NetworkScanRequest nsr, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            android.hardware.radio.V1_1.IRadio radioProxy11 =
                    android.hardware.radio.V1_1.IRadio.castFrom(radioProxy);
            if (radioProxy11 == null) {
                if (result != null) {
                    AsyncResult.forMessage(result, null,
                            CommandException.fromRilErrno(REQUEST_NOT_SUPPORTED));
                    result.sendToTarget();
                }
            } else {
                android.hardware.radio.V1_1.NetworkScanRequest request =
                        new android.hardware.radio.V1_1.NetworkScanRequest();
                request.type = nsr.scanType;
                request.interval = 60;
                for (RadioAccessSpecifier ras : nsr.specifiers) {
                    android.hardware.radio.V1_1.RadioAccessSpecifier s =
                            new android.hardware.radio.V1_1.RadioAccessSpecifier();
                    s.radioAccessNetwork = ras.radioAccessNetwork;
                    List<Integer> bands = null;
                    switch (ras.radioAccessNetwork) {
                        case RadioAccessNetworks.GERAN:
                            bands = s.geranBands;
                            break;
                        case RadioAccessNetworks.UTRAN:
                            bands = s.utranBands;
                            break;
                        case RadioAccessNetworks.EUTRAN:
                            bands = s.eutranBands;
                            break;
                        default:
                            Log.wtf(RILJ_LOG_TAG, "radioAccessNetwork " + ras.radioAccessNetwork
                                    + " not supported!");
                            return;
                    }
                    if (ras.bands != null) {
                        for (int band : ras.bands) {
                            bands.add(band);
                        }
                    }
                    if (ras.channels != null) {
                        for (int channel : ras.channels) {
                            s.channels.add(channel);
                        }
                    }
                    request.specifiers.add(s);
                }

                RILRequest rr = obtainRequest(RIL_REQUEST_START_NETWORK_SCAN, result,
                        mRILDefaultWorkSource);

                if (RILJ_LOGD) {
                    riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
                }

                try {
                    radioProxy11.startNetworkScan(rr.mSerial, request);
                } catch (RemoteException | RuntimeException e) {
                    handleRadioProxyExceptionForRR(rr, "startNetworkScan", e);
                }
            }
        }
    }

    @Override
    public void stopNetworkScan(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            android.hardware.radio.V1_1.IRadio radioProxy11 =
                    android.hardware.radio.V1_1.IRadio.castFrom(radioProxy);
            if (radioProxy11 == null) {
                if (result != null) {
                    AsyncResult.forMessage(result, null,
                            CommandException.fromRilErrno(REQUEST_NOT_SUPPORTED));
                    result.sendToTarget();
                }
            } else {
                RILRequest rr = obtainRequest(RIL_REQUEST_STOP_NETWORK_SCAN, result,
                        mRILDefaultWorkSource);

                if (RILJ_LOGD) {
                    riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
                }

                try {
                    radioProxy11.stopNetworkScan(rr.mSerial);
                } catch (RemoteException | RuntimeException e) {
                    handleRadioProxyExceptionForRR(rr, "stopNetworkScan", e);
                }
            }
        }
    }

    @Override
    public void startDtmf(char c, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_DTMF_START, result,
                    mRILDefaultWorkSource);

            // Do not log function arg for privacy
            if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            try {
                radioProxy.startDtmf(rr.mSerial, c + "");
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "startDtmf", e);
            }
        }
    }

    @Override
    public void stopDtmf(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_DTMF_STOP, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            try {
                radioProxy.stopDtmf(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "stopDtmf", e);
            }
        }
    }

    @Override
    public void separateConnection(int gsmIndex, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_SEPARATE_CONNECTION, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " gsmIndex = " + gsmIndex);
            }

            try {
                radioProxy.separateConnection(rr.mSerial, gsmIndex);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "separateConnection", e);
            }
        }
    }

    @Override
    public void getBasebandVersion(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_BASEBAND_VERSION, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            try {
                radioProxy.getBasebandVersion(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "getBasebandVersion", e);
            }
        }
    }

    @Override
    public void setMute(boolean enableMute, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_SET_MUTE, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " enableMute = " + enableMute);
            }

            try {
                radioProxy.setMute(rr.mSerial, enableMute);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "setMute", e);
            }
        }
    }

    @Override
    public void getMute(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_GET_MUTE, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            try {
                radioProxy.getMute(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "getMute", e);
            }
        }
    }

    @Override
    public void queryCLIP(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_QUERY_CLIP, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            try {
                radioProxy.getClip(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "queryCLIP", e);
            }
        }
    }

    /**
     * @deprecated
     */
    @Override
    @Deprecated
    public void getPDPContextList(Message result) {
        getDataCallList(result);
    }

    @Override
    public void getDataCallList(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_DATA_CALL_LIST, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            try {
                radioProxy.getDataCallList(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "getDataCallList", e);
            }
        }
    }

    @Override
    public void invokeOemRilRequestRaw(byte[] data, Message response) {
        IOemHook oemHookProxy = getOemHookProxy(response);
        if (oemHookProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_OEM_HOOK_RAW, response,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + "[" + IccUtils.bytesToHexString(data) + "]");
            }

            try {
                oemHookProxy.sendRequestRaw(rr.mSerial, primitiveArrayToArrayList(data));
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "invokeOemRilRequestStrings", e);
            }
        }
    }

    @Override
    public void invokeOemRilRequestStrings(String[] strings, Message result) {
        IOemHook oemHookProxy = getOemHookProxy(result);
        if (oemHookProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_OEM_HOOK_STRINGS, result,
                    mRILDefaultWorkSource);

            String logStr = "";
            for (int i = 0; i < strings.length; i++) {
                logStr = logStr + strings[i] + " ";
            }
            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest) + " strings = "
                        + logStr);
            }

            try {
                oemHookProxy.sendRequestStrings(rr.mSerial,
                        new ArrayList<String>(Arrays.asList(strings)));
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "invokeOemRilRequestStrings", e);
            }
        }
    }

    @Override
    public void setSuppServiceNotifications(boolean enable, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_SET_SUPP_SVC_NOTIFICATION, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest) + " enable = "
                        + enable);
            }

            try {
                radioProxy.setSuppServiceNotifications(rr.mSerial, enable);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "setSuppServiceNotifications", e);
            }
        }
    }

    @Override
    public void writeSmsToSim(int status, String smsc, String pdu, Message result) {
        status = translateStatus(status);
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_WRITE_SMS_TO_SIM, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGV) {
                riljLog(rr.serialString() + "> "
                        + requestToString(rr.mRequest)
                        + " " + status);
            }

            SmsWriteArgs args = new SmsWriteArgs();
            args.status = status;
            args.smsc = convertNullToEmptyString(smsc);
            args.pdu = convertNullToEmptyString(pdu);

            try {
                radioProxy.writeSmsToSim(rr.mSerial, args);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "writeSmsToSim", e);
            }
        }
    }

    @Override
    public void deleteSmsOnSim(int index, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_DELETE_SMS_ON_SIM, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGV) {
                riljLog(rr.serialString() + "> "
                        + requestToString(rr.mRequest) + " index = " + index);
            }

            try {
                radioProxy.deleteSmsOnSim(rr.mSerial, index);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "deleteSmsOnSim", e);
            }
        }
    }

    @Override
    public void setBandMode(int bandMode, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_SET_BAND_MODE, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " bandMode = " + bandMode);
            }

            try {
                radioProxy.setBandMode(rr.mSerial, bandMode);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "setBandMode", e);
            }
        }
    }

    @Override
    public void queryAvailableBandMode(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_QUERY_AVAILABLE_BAND_MODE, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            try {
                radioProxy.getAvailableBandModes(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "queryAvailableBandMode", e);
            }
        }
    }

    @Override
    public void sendEnvelope(String contents, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_STK_SEND_ENVELOPE_COMMAND, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest) + " contents = "
                        + contents);
            }

            try {
                radioProxy.sendEnvelope(rr.mSerial, convertNullToEmptyString(contents));
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "sendEnvelope", e);
            }
        }
    }

    @Override
    public void sendTerminalResponse(String contents, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_STK_SEND_TERMINAL_RESPONSE, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest) + " contents = "
                        + contents);
            }

            try {
                radioProxy.sendTerminalResponseToSim(rr.mSerial,
                        convertNullToEmptyString(contents));
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "sendTerminalResponse", e);
            }
        }
    }

    @Override
    public void sendEnvelopeWithStatus(String contents, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_STK_SEND_ENVELOPE_WITH_STATUS, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest) + " contents = "
                        + contents);
            }

            try {
                radioProxy.sendEnvelopeWithStatus(rr.mSerial, convertNullToEmptyString(contents));
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "sendEnvelopeWithStatus", e);
            }
        }
    }

    @Override
    public void explicitCallTransfer(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_EXPLICIT_CALL_TRANSFER, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            try {
                radioProxy.explicitCallTransfer(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "explicitCallTransfer", e);
            }
        }
    }

    @Override
    public void setPreferredNetworkType(int networkType , Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_SET_PREFERRED_NETWORK_TYPE, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " networkType = " + networkType);
            }
            mPreferredNetworkType = networkType;
            mMetrics.writeSetPreferredNetworkType(mPhoneId, networkType);

            try {
                radioProxy.setPreferredNetworkType(rr.mSerial, networkType);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "setPreferredNetworkType", e);
            }
        }
    }

    @Override
    public void getPreferredNetworkType(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_GET_PREFERRED_NETWORK_TYPE, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            try {
                radioProxy.getPreferredNetworkType(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "getPreferredNetworkType", e);
            }
        }
    }

    @Override
    public void getNeighboringCids(Message result, WorkSource workSource) {
        workSource = getDeafultWorkSourceIfInvalid(workSource);
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_GET_NEIGHBORING_CELL_IDS, result,
                    workSource);

            if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            try {
                radioProxy.getNeighboringCids(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "getNeighboringCids", e);
            }
        }
    }

    @Override
    public void setLocationUpdates(boolean enable, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_SET_LOCATION_UPDATES, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> "
                        + requestToString(rr.mRequest) + " enable = " + enable);
            }

            try {
                radioProxy.setLocationUpdates(rr.mSerial, enable);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "setLocationUpdates", e);
            }
        }
    }

    @Override
    public void setCdmaSubscriptionSource(int cdmaSubscription , Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_CDMA_SET_SUBSCRIPTION_SOURCE, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " cdmaSubscription = " + cdmaSubscription);
            }

            try {
                radioProxy.setCdmaSubscriptionSource(rr.mSerial, cdmaSubscription);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "setCdmaSubscriptionSource", e);
            }
        }
    }

    @Override
    public void queryCdmaRoamingPreference(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_CDMA_QUERY_ROAMING_PREFERENCE, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            try {
                radioProxy.getCdmaRoamingPreference(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "queryCdmaRoamingPreference", e);
            }
        }
    }

    @Override
    public void setCdmaRoamingPreference(int cdmaRoamingType, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_CDMA_SET_ROAMING_PREFERENCE, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " cdmaRoamingType = " + cdmaRoamingType);
            }

            try {
                radioProxy.setCdmaRoamingPreference(rr.mSerial, cdmaRoamingType);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "setCdmaRoamingPreference", e);
            }
        }
    }

    @Override
    public void queryTTYMode(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_QUERY_TTY_MODE, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            try {
                radioProxy.getTTYMode(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "queryTTYMode", e);
            }
        }
    }

    @Override
    public void setTTYMode(int ttyMode, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_SET_TTY_MODE, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " ttyMode = " + ttyMode);
            }

            try {
                radioProxy.setTTYMode(rr.mSerial, ttyMode);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "setTTYMode", e);
            }
        }
    }

    @Override
    public void setPreferredVoicePrivacy(boolean enable, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_CDMA_SET_PREFERRED_VOICE_PRIVACY_MODE, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " enable = " + enable);
            }

            try {
                radioProxy.setPreferredVoicePrivacy(rr.mSerial, enable);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "setPreferredVoicePrivacy", e);
            }
        }
    }

    @Override
    public void getPreferredVoicePrivacy(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_CDMA_QUERY_PREFERRED_VOICE_PRIVACY_MODE,
                    result, mRILDefaultWorkSource);

            if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            try {
                radioProxy.getPreferredVoicePrivacy(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "getPreferredVoicePrivacy", e);
            }
        }
    }

    @Override
    public void sendCDMAFeatureCode(String featureCode, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_CDMA_FLASH, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " featureCode = " + featureCode);
            }

            try {
                radioProxy.sendCDMAFeatureCode(rr.mSerial, convertNullToEmptyString(featureCode));
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "sendCDMAFeatureCode", e);
            }
        }
    }

    @Override
    public void sendBurstDtmf(String dtmfString, int on, int off, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_CDMA_BURST_DTMF, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " dtmfString = " + dtmfString + " on = " + on + " off = " + off);
            }

            try {
                radioProxy.sendBurstDtmf(rr.mSerial, convertNullToEmptyString(dtmfString), on, off);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "sendBurstDtmf", e);
            }
        }
    }

    private void constructCdmaSendSmsRilRequest(CdmaSmsMessage msg, byte[] pdu) {
        int addrNbrOfDigits;
        int subaddrNbrOfDigits;
        int bearerDataLength;
        ByteArrayInputStream bais = new ByteArrayInputStream(pdu);
        DataInputStream dis = new DataInputStream(bais);

        try {
            msg.teleserviceId = dis.readInt(); // teleServiceId
            msg.isServicePresent = (byte) dis.readInt() == 1 ? true : false; // servicePresent
            msg.serviceCategory = dis.readInt(); // serviceCategory
            msg.address.digitMode = dis.read();  // address digit mode
            msg.address.numberMode = dis.read(); // address number mode
            msg.address.numberType = dis.read(); // address number type
            msg.address.numberPlan = dis.read(); // address number plan
            addrNbrOfDigits = (byte) dis.read();
            for (int i = 0; i < addrNbrOfDigits; i++) {
                msg.address.digits.add(dis.readByte()); // address_orig_bytes[i]
            }
            msg.subAddress.subaddressType = dis.read(); //subaddressType
            msg.subAddress.odd = (byte) dis.read() == 1 ? true : false; //subaddr odd
            subaddrNbrOfDigits = (byte) dis.read();
            for (int i = 0; i < subaddrNbrOfDigits; i++) {
                msg.subAddress.digits.add(dis.readByte()); //subaddr_orig_bytes[i]
            }

            bearerDataLength = dis.read();
            for (int i = 0; i < bearerDataLength; i++) {
                msg.bearerData.add(dis.readByte()); //bearerData[i]
            }
        } catch (IOException ex) {
            if (RILJ_LOGD) {
                riljLog("sendSmsCdma: conversion from input stream to object failed: "
                        + ex);
            }
        }
    }

    @Override
    public void sendCdmaSms(byte[] pdu, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_CDMA_SEND_SMS, result,
                    mRILDefaultWorkSource);

            // Do not log function arg for privacy
            if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            CdmaSmsMessage msg = new CdmaSmsMessage();
            constructCdmaSendSmsRilRequest(msg, pdu);

            try {
                radioProxy.sendCdmaSms(rr.mSerial, msg);
                mMetrics.writeRilSendSms(mPhoneId, rr.mSerial, SmsSession.Event.Tech.SMS_CDMA,
                        SmsSession.Event.Format.SMS_FORMAT_3GPP2);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "sendCdmaSms", e);
            }
        }
    }

    @Override
    public void acknowledgeLastIncomingCdmaSms(boolean success, int cause, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_CDMA_SMS_ACKNOWLEDGE, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " success = " + success + " cause = " + cause);
            }

            CdmaSmsAck msg = new CdmaSmsAck();
            msg.errorClass = success ? 0 : 1;
            msg.smsCauseCode = cause;

            try {
                radioProxy.acknowledgeLastIncomingCdmaSms(rr.mSerial, msg);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "acknowledgeLastIncomingCdmaSms", e);
            }
        }
    }

    @Override
    public void getGsmBroadcastConfig(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_GSM_GET_BROADCAST_CONFIG, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            try {
                radioProxy.getGsmBroadcastConfig(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "getGsmBroadcastConfig", e);
            }
        }
    }

    @Override
    public void setGsmBroadcastConfig(SmsBroadcastConfigInfo[] config, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_GSM_SET_BROADCAST_CONFIG, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " with " + config.length + " configs : ");
                for (int i = 0; i < config.length; i++) {
                    riljLog(config[i].toString());
                }
            }

            ArrayList<GsmBroadcastSmsConfigInfo> configs = new ArrayList<>();

            int numOfConfig = config.length;
            GsmBroadcastSmsConfigInfo info;

            for (int i = 0; i < numOfConfig; i++) {
                info = new GsmBroadcastSmsConfigInfo();
                info.fromServiceId = config[i].getFromServiceId();
                info.toServiceId = config[i].getToServiceId();
                info.fromCodeScheme = config[i].getFromCodeScheme();
                info.toCodeScheme = config[i].getToCodeScheme();
                info.selected = config[i].isSelected();
                configs.add(info);
            }

            try {
                radioProxy.setGsmBroadcastConfig(rr.mSerial, configs);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "setGsmBroadcastConfig", e);
            }
        }
    }

    @Override
    public void setGsmBroadcastActivation(boolean activate, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_GSM_BROADCAST_ACTIVATION, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " activate = " + activate);
            }

            try {
                radioProxy.setGsmBroadcastActivation(rr.mSerial, activate);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "setGsmBroadcastActivation", e);
            }
        }
    }

    @Override
    public void getCdmaBroadcastConfig(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_CDMA_GET_BROADCAST_CONFIG, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            try {
                radioProxy.getCdmaBroadcastConfig(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "getCdmaBroadcastConfig", e);
            }
        }
    }

    @Override
    public void setCdmaBroadcastConfig(CdmaSmsBroadcastConfigInfo[] configs, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_CDMA_SET_BROADCAST_CONFIG, result,
                    mRILDefaultWorkSource);

            ArrayList<CdmaBroadcastSmsConfigInfo> halConfigs = new ArrayList<>();

            for (CdmaSmsBroadcastConfigInfo config: configs) {
                for (int i = config.getFromServiceCategory();
                        i <= config.getToServiceCategory();
                        i++) {
                    CdmaBroadcastSmsConfigInfo info = new CdmaBroadcastSmsConfigInfo();
                    info.serviceCategory = i;
                    info.language = config.getLanguage();
                    info.selected = config.isSelected();
                    halConfigs.add(info);
                }
            }

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " with " + halConfigs.size() + " configs : ");
                for (CdmaBroadcastSmsConfigInfo config : halConfigs) {
                    riljLog(config.toString());
                }
            }

            try {
                radioProxy.setCdmaBroadcastConfig(rr.mSerial, halConfigs);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "setCdmaBroadcastConfig", e);
            }
        }
    }

    @Override
    public void setCdmaBroadcastActivation(boolean activate, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_CDMA_BROADCAST_ACTIVATION, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " activate = " + activate);
            }

            try {
                radioProxy.setCdmaBroadcastActivation(rr.mSerial, activate);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "setCdmaBroadcastActivation", e);
            }
        }
    }

    @Override
    public void getCDMASubscription(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_CDMA_SUBSCRIPTION, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            try {
                radioProxy.getCDMASubscription(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "getCDMASubscription", e);
            }
        }
    }

    @Override
    public void writeSmsToRuim(int status, String pdu, Message result) {
        status = translateStatus(status);
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_CDMA_WRITE_SMS_TO_RUIM, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGV) {
                riljLog(rr.serialString() + "> "
                        + requestToString(rr.mRequest)
                        + " status = " + status);
            }

            CdmaSmsWriteArgs args = new CdmaSmsWriteArgs();
            args.status = status;
            constructCdmaSendSmsRilRequest(args.message, IccUtils.hexStringToBytes(pdu));

            try {
                radioProxy.writeSmsToRuim(rr.mSerial, args);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "writeSmsToRuim", e);
            }
        }
    }

    @Override
    public void deleteSmsOnRuim(int index, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_CDMA_DELETE_SMS_ON_RUIM, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGV) {
                riljLog(rr.serialString() + "> "
                        + requestToString(rr.mRequest)
                        + " index = " + index);
            }

            try {
                radioProxy.deleteSmsOnRuim(rr.mSerial, index);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "deleteSmsOnRuim", e);
            }
        }
    }

    @Override
    public void getDeviceIdentity(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_DEVICE_IDENTITY, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            try {
                radioProxy.getDeviceIdentity(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "getDeviceIdentity", e);
            }
        }
    }

    @Override
    public void exitEmergencyCallbackMode(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_EXIT_EMERGENCY_CALLBACK_MODE, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            try {
                radioProxy.exitEmergencyCallbackMode(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "exitEmergencyCallbackMode", e);
            }
        }
    }

    @Override
    public void getSmscAddress(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_GET_SMSC_ADDRESS, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            try {
                radioProxy.getSmscAddress(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "getSmscAddress", e);
            }
        }
    }

    @Override
    public void setSmscAddress(String address, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_SET_SMSC_ADDRESS, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " address = " + address);
            }

            try {
                radioProxy.setSmscAddress(rr.mSerial, convertNullToEmptyString(address));
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "setSmscAddress", e);
            }
        }
    }

    @Override
    public void reportSmsMemoryStatus(boolean available, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_REPORT_SMS_MEMORY_STATUS, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> "
                        + requestToString(rr.mRequest) + " available = " + available);
            }

            try {
                radioProxy.reportSmsMemoryStatus(rr.mSerial, available);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "reportSmsMemoryStatus", e);
            }
        }
    }

    @Override
    public void reportStkServiceIsRunning(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_REPORT_STK_SERVICE_IS_RUNNING, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            try {
                radioProxy.reportStkServiceIsRunning(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "reportStkServiceIsRunning", e);
            }
        }
    }

    @Override
    public void getCdmaSubscriptionSource(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_CDMA_GET_SUBSCRIPTION_SOURCE, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            try {
                radioProxy.getCdmaSubscriptionSource(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "getCdmaSubscriptionSource", e);
            }
        }
    }

    @Override
    public void requestIsimAuthentication(String nonce, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_ISIM_AUTHENTICATION, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " nonce = " + nonce);
            }

            try {
                radioProxy.requestIsimAuthentication(rr.mSerial, convertNullToEmptyString(nonce));
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "requestIsimAuthentication", e);
            }
        }
    }

    @Override
    public void acknowledgeIncomingGsmSmsWithPdu(boolean success, String ackPdu, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_ACKNOWLEDGE_INCOMING_GSM_SMS_WITH_PDU, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " success = " + success);
            }

            try {
                radioProxy.acknowledgeIncomingGsmSmsWithPdu(rr.mSerial, success,
                        convertNullToEmptyString(ackPdu));
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "acknowledgeIncomingGsmSmsWithPdu", e);
            }
        }
    }

    @Override
    public void getVoiceRadioTechnology(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_VOICE_RADIO_TECH, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            try {
                radioProxy.getVoiceRadioTechnology(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "getVoiceRadioTechnology", e);
            }
        }
    }

    @Override
    public void getCellInfoList(Message result, WorkSource workSource) {
        workSource = getDeafultWorkSourceIfInvalid(workSource);
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_GET_CELL_INFO_LIST, result,
                    workSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
            }

            try {
                radioProxy.getCellInfoList(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "getCellInfoList", e);
            }
        }
    }

    @Override
    public void setCellInfoListRate(int rateInMillis, Message result, WorkSource workSource) {
        workSource = getDeafultWorkSourceIfInvalid(workSource);
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_SET_UNSOL_CELL_INFO_LIST_RATE, result,
                    workSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " rateInMillis = " + rateInMillis);
            }

            try {
                radioProxy.setCellInfoListRate(rr.mSerial, rateInMillis);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "setCellInfoListRate", e);
            }
        }
    }

    void setCellInfoListRate() {
        setCellInfoListRate(Integer.MAX_VALUE, null, mRILDefaultWorkSource);
    }

    @Override
    public void setInitialAttachApn(DataProfile dataProfile, boolean isRoaming, Message result) {

        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_SET_INITIAL_ATTACH_APN, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest) + dataProfile);
            }

            try {
                radioProxy.setInitialAttachApn(rr.mSerial, convertToHalDataProfile(dataProfile),
                        dataProfile.modemCognitive, isRoaming);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "setInitialAttachApn", e);
            }
        }
    }

    @Override
    public void getImsRegistrationState(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_IMS_REGISTRATION_STATE, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
            }

            try {
                radioProxy.getImsRegistrationState(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "getImsRegistrationState", e);
            }
        }
    }

    @Override
    public void sendImsGsmSms(String smscPdu, String pdu, int retry, int messageRef,
                   Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_IMS_SEND_SMS, result,
                    mRILDefaultWorkSource);

            // Do not log function args for privacy
            if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            ImsSmsMessage msg = new ImsSmsMessage();
            msg.tech = RILConstants.GSM_PHONE;
            msg.retry = (byte) retry == 1 ? true : false;
            msg.messageRef = messageRef;

            GsmSmsMessage gsmMsg = constructGsmSendSmsRilRequest(smscPdu, pdu);
            msg.gsmMessage.add(gsmMsg);
            try {
                radioProxy.sendImsSms(rr.mSerial, msg);
                mMetrics.writeRilSendSms(mPhoneId, rr.mSerial, SmsSession.Event.Tech.SMS_IMS,
                        SmsSession.Event.Format.SMS_FORMAT_3GPP);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "sendImsGsmSms", e);
            }
        }
    }

    @Override
    public void sendImsCdmaSms(byte[] pdu, int retry, int messageRef, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_IMS_SEND_SMS, result,
                    mRILDefaultWorkSource);

            // Do not log function args for privacy
            if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            ImsSmsMessage msg = new ImsSmsMessage();
            msg.tech = RILConstants.CDMA_PHONE;
            msg.retry = (byte) retry == 1 ? true : false;
            msg.messageRef = messageRef;

            CdmaSmsMessage cdmaMsg = new CdmaSmsMessage();
            constructCdmaSendSmsRilRequest(cdmaMsg, pdu);
            msg.cdmaMessage.add(cdmaMsg);

            try {
                radioProxy.sendImsSms(rr.mSerial, msg);
                mMetrics.writeRilSendSms(mPhoneId, rr.mSerial, SmsSession.Event.Tech.SMS_IMS,
                        SmsSession.Event.Format.SMS_FORMAT_3GPP);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "sendImsCdmaSms", e);
            }
        }
    }

    private SimApdu createSimApdu(int channel, int cla, int instruction, int p1, int p2, int p3,
                                  String data) {
        SimApdu msg = new SimApdu();
        msg.sessionId = channel;
        msg.cla = cla;
        msg.instruction = instruction;
        msg.p1 = p1;
        msg.p2 = p2;
        msg.p3 = p3;
        msg.data = convertNullToEmptyString(data);
        return msg;
    }

    @Override
    public void iccTransmitApduBasicChannel(int cla, int instruction, int p1, int p2,
                                            int p3, String data, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_SIM_TRANSMIT_APDU_BASIC, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " cla = " + cla + " instruction = " + instruction
                        + " p1 = " + p1 + " p2 = " + " p3 = " + p3 + " data = " + data);
            }

            SimApdu msg = createSimApdu(0, cla, instruction, p1, p2, p3, data);
            try {
                radioProxy.iccTransmitApduBasicChannel(rr.mSerial, msg);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "iccTransmitApduBasicChannel", e);
            }
        }
    }

    @Override
    public void iccOpenLogicalChannel(String aid, int p2, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_SIM_OPEN_CHANNEL, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest) + " aid = " + aid
                        + " p2 = " + p2);
            }

            try {
                radioProxy.iccOpenLogicalChannel(rr.mSerial, convertNullToEmptyString(aid), p2);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "iccOpenLogicalChannel", e);
            }
        }
    }

    @Override
    public void iccCloseLogicalChannel(int channel, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_SIM_CLOSE_CHANNEL, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest) + " channel = "
                        + channel);
            }

            try {
                radioProxy.iccCloseLogicalChannel(rr.mSerial, channel);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "iccCloseLogicalChannel", e);
            }
        }
    }

    @Override
    public void iccTransmitApduLogicalChannel(int channel, int cla, int instruction,
                                              int p1, int p2, int p3, String data,
                                              Message result) {
        if (channel <= 0) {
            throw new RuntimeException(
                    "Invalid channel in iccTransmitApduLogicalChannel: " + channel);
        }

        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_SIM_TRANSMIT_APDU_CHANNEL, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest) + " channel = "
                        + channel + " cla = " + cla + " instruction = " + instruction
                        + " p1 = " + p1 + " p2 = " + " p3 = " + p3 + " data = " + data);
            }

            SimApdu msg = createSimApdu(channel, cla, instruction, p1, p2, p3, data);

            try {
                radioProxy.iccTransmitApduLogicalChannel(rr.mSerial, msg);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "iccTransmitApduLogicalChannel", e);
            }
        }
    }

    @Override
    public void nvReadItem(int itemID, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_NV_READ_ITEM, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " itemId = " + itemID);
            }

            try {
                radioProxy.nvReadItem(rr.mSerial, itemID);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "nvReadItem", e);
            }
        }
    }

    @Override
    public void nvWriteItem(int itemId, String itemValue, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_NV_WRITE_ITEM, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " itemId = " + itemId + " itemValue = " + itemValue);
            }

            NvWriteItem item = new NvWriteItem();
            item.itemId = itemId;
            item.value = convertNullToEmptyString(itemValue);

            try {
                radioProxy.nvWriteItem(rr.mSerial, item);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "nvWriteItem", e);
            }
        }
    }

    @Override
    public void nvWriteCdmaPrl(byte[] preferredRoamingList, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_NV_WRITE_CDMA_PRL, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " PreferredRoamingList = 0x"
                        + IccUtils.bytesToHexString(preferredRoamingList));
            }

            ArrayList<Byte> arrList = new ArrayList<>();
            for (int i = 0; i < preferredRoamingList.length; i++) {
                arrList.add(preferredRoamingList[i]);
            }

            try {
                radioProxy.nvWriteCdmaPrl(rr.mSerial, arrList);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "nvWriteCdmaPrl", e);
            }
        }
    }

    @Override
    public void nvResetConfig(int resetType, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_NV_RESET_CONFIG, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " resetType = " + resetType);
            }

            try {
                radioProxy.nvResetConfig(rr.mSerial, convertToHalResetNvType(resetType));
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "nvResetConfig", e);
            }
        }
    }

    @Override
    public void setUiccSubscription(int slotId, int appIndex, int subId,
                                    int subStatus, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_SET_UICC_SUBSCRIPTION, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " slot = " + slotId + " appIndex = " + appIndex
                        + " subId = " + subId + " subStatus = " + subStatus);
            }

            SelectUiccSub info = new SelectUiccSub();
            info.slot = slotId;
            info.appIndex = appIndex;
            info.subType = subId;
            info.actStatus = subStatus;

            try {
                radioProxy.setUiccSubscription(rr.mSerial, info);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "setUiccSubscription", e);
            }
        }
    }

    @Override
    public void setDataAllowed(boolean allowed, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_ALLOW_DATA, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " allowed = " + allowed);
            }

            try {
                radioProxy.setDataAllowed(rr.mSerial, allowed);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "setDataAllowed", e);
            }
        }
    }

    @Override
    public void
    getHardwareConfig (Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_GET_HARDWARE_CONFIG, result,
                    mRILDefaultWorkSource);

            // Do not log function args for privacy
            if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            try {
                radioProxy.getHardwareConfig(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "getHardwareConfig", e);
            }
        }
    }

    @Override
    public void requestIccSimAuthentication(int authContext, String data, String aid,
                                            Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_SIM_AUTHENTICATION, result,
                    mRILDefaultWorkSource);

            // Do not log function args for privacy
            if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            try {
                radioProxy.requestIccSimAuthentication(rr.mSerial,
                        authContext,
                        convertNullToEmptyString(data),
                        convertNullToEmptyString(aid));
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "requestIccSimAuthentication", e);
            }
        }
    }

    @Override
    public void setDataProfile(DataProfile[] dps, boolean isRoaming, Message result) {

        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_SET_DATA_PROFILE, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " with data profiles : ");
                for (DataProfile profile : dps) {
                    riljLog(profile.toString());
                }
            }

            ArrayList<DataProfileInfo> dpis = new ArrayList<>();
            for (DataProfile dp : dps) {
                dpis.add(convertToHalDataProfile(dp));
            }

            try {
                radioProxy.setDataProfile(rr.mSerial, dpis, isRoaming);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "setDataProfile", e);
            }
        }
    }

    @Override
    public void requestShutdown(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_SHUTDOWN, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
            }

            try {
                radioProxy.requestShutdown(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "requestShutdown", e);
            }
        }
    }

    @Override
    public void getRadioCapability(Message response) {
        IRadio radioProxy = getRadioProxy(response);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_GET_RADIO_CAPABILITY, response,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
            }

            try {
                radioProxy.getRadioCapability(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "getRadioCapability", e);
            }
        }
    }

    @Override
    public void setRadioCapability(RadioCapability rc, Message response) {
        IRadio radioProxy = getRadioProxy(response);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_SET_RADIO_CAPABILITY, response,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " RadioCapability = " + rc.toString());
            }

            android.hardware.radio.V1_0.RadioCapability halRc =
                    new android.hardware.radio.V1_0.RadioCapability();

            halRc.session = rc.getSession();
            halRc.phase = rc.getPhase();
            halRc.raf = rc.getRadioAccessFamily();
            halRc.logicalModemUuid = convertNullToEmptyString(rc.getLogicalModemUuid());
            halRc.status = rc.getStatus();

            try {
                radioProxy.setRadioCapability(rr.mSerial, halRc);
            } catch (Exception e) {
                handleRadioProxyExceptionForRR(rr, "setRadioCapability", e);
            }
        }
    }

    @Override
    public void startLceService(int reportIntervalMs, boolean pullMode, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_START_LCE, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " reportIntervalMs = " + reportIntervalMs + " pullMode = " + pullMode);
            }

            try {
                radioProxy.startLceService(rr.mSerial, reportIntervalMs, pullMode);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "startLceService", e);
            }
        }
    }

    @Override
    public void stopLceService(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_STOP_LCE, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
            }

            try {
                radioProxy.stopLceService(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "stopLceService", e);
            }
        }
    }

    @Override
    public void pullLceData(Message response) {
        IRadio radioProxy = getRadioProxy(response);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_PULL_LCEDATA, response,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
            }

            try {
                radioProxy.pullLceData(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "pullLceData", e);
            }
        }
    }

    @Override
    public void getModemActivityInfo(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_GET_ACTIVITY_INFO, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
            }

            try {
                radioProxy.getModemActivityInfo(rr.mSerial);

                Message msg = mRilHandler.obtainMessage(EVENT_BLOCKING_RESPONSE_TIMEOUT);
                msg.obj = null;
                msg.arg1 = rr.mSerial;
                mRilHandler.sendMessageDelayed(msg, DEFAULT_BLOCKING_MESSAGE_RESPONSE_TIMEOUT_MS);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "getModemActivityInfo", e);
            }
        }


    }

    @Override
    public void setAllowedCarriers(List<CarrierIdentifier> carriers, Message result) {
        checkNotNull(carriers, "Allowed carriers list cannot be null.");
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_SET_ALLOWED_CARRIERS, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                String logStr = "";
                for (int i = 0; i < carriers.size(); i++) {
                    logStr = logStr + carriers.get(i) + " ";
                }
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest) + " carriers = "
                        + logStr);
            }

            boolean allAllowed;
            if (carriers.size() == 0) {
                allAllowed = true;
            } else {
                allAllowed = false;
            }
            CarrierRestrictions carrierList = new CarrierRestrictions();

            for (CarrierIdentifier ci : carriers) { /* allowed carriers */
                Carrier c = new Carrier();
                c.mcc = convertNullToEmptyString(ci.getMcc());
                c.mnc = convertNullToEmptyString(ci.getMnc());
                int matchType = CarrierIdentifier.MatchType.ALL;
                String matchData = null;
                if (!TextUtils.isEmpty(ci.getSpn())) {
                    matchType = CarrierIdentifier.MatchType.SPN;
                    matchData = ci.getSpn();
                } else if (!TextUtils.isEmpty(ci.getImsi())) {
                    matchType = CarrierIdentifier.MatchType.IMSI_PREFIX;
                    matchData = ci.getImsi();
                } else if (!TextUtils.isEmpty(ci.getGid1())) {
                    matchType = CarrierIdentifier.MatchType.GID1;
                    matchData = ci.getGid1();
                } else if (!TextUtils.isEmpty(ci.getGid2())) {
                    matchType = CarrierIdentifier.MatchType.GID2;
                    matchData = ci.getGid2();
                }
                c.matchType = matchType;
                c.matchData = convertNullToEmptyString(matchData);
                carrierList.allowedCarriers.add(c);
            }

            /* TODO: add excluded carriers */

            try {
                radioProxy.setAllowedCarriers(rr.mSerial, allAllowed, carrierList);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "setAllowedCarriers", e);
            }
        }
    }

    @Override
    public void getAllowedCarriers(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_GET_ALLOWED_CARRIERS, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
            }

            try {
                radioProxy.getAllowedCarriers(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "getAllowedCarriers", e);
            }
        }
    }

    @Override
    public void sendDeviceState(int stateType, boolean state,
                                Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_SEND_DEVICE_STATE, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest) + " "
                        + stateType + ":" + state);
            }

            try {
                radioProxy.sendDeviceState(rr.mSerial, stateType, state);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "sendDeviceState", e);
            }
        }
    }

    @Override
    public void setUnsolResponseFilter(int filter, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_SET_UNSOLICITED_RESPONSE_FILTER, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest) + " " + filter);
            }

            try {
                radioProxy.setIndicationFilter(rr.mSerial, filter);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "setIndicationFilter", e);
            }
        }
    }

    @Override
    public void setSimCardPower(int state, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_SET_SIM_CARD_POWER, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest) + " " + state);
            }
            android.hardware.radio.V1_1.IRadio radioProxy11 =
                    android.hardware.radio.V1_1.IRadio.castFrom(radioProxy);
            if (radioProxy11 == null) {
                try {
                    switch (state) {
                        case TelephonyManager.CARD_POWER_DOWN: {
                            radioProxy.setSimCardPower(rr.mSerial, false);
                            break;
                        }
                        case TelephonyManager.CARD_POWER_UP: {
                            radioProxy.setSimCardPower(rr.mSerial, true);
                            break;
                        }
                        default: {
                            if (result != null) {
                                AsyncResult.forMessage(result, null,
                                        CommandException.fromRilErrno(REQUEST_NOT_SUPPORTED));
                                result.sendToTarget();
                            }
                        }
                    }
                } catch (RemoteException | RuntimeException e) {
                    handleRadioProxyExceptionForRR(rr, "setSimCardPower", e);
                }
            } else {
                try {
                    radioProxy11.setSimCardPower_1_1(rr.mSerial, state);
                } catch (RemoteException | RuntimeException e) {
                    handleRadioProxyExceptionForRR(rr, "setSimCardPower", e);
                }
            }
        }
    }

    @Override
    public void setCarrierInfoForImsiEncryption(ImsiEncryptionInfo imsiEncryptionInfo,
                                                Message result) {
        checkNotNull(imsiEncryptionInfo, "ImsiEncryptionInfo cannot be null.");
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            android.hardware.radio.V1_1.IRadio radioProxy11 =
                    android.hardware.radio.V1_1.IRadio.castFrom(radioProxy);
            if (radioProxy11 == null) {
                if (result != null) {
                    AsyncResult.forMessage(result, null,
                            CommandException.fromRilErrno(REQUEST_NOT_SUPPORTED));
                    result.sendToTarget();
                }
            } else {
                RILRequest rr = obtainRequest(RIL_REQUEST_SET_CARRIER_INFO_IMSI_ENCRYPTION, result,
                        mRILDefaultWorkSource);
                if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

                try {
                    android.hardware.radio.V1_1.ImsiEncryptionInfo halImsiInfo =
                            new android.hardware.radio.V1_1.ImsiEncryptionInfo();
                    halImsiInfo.mnc = imsiEncryptionInfo.getMnc();
                    halImsiInfo.mcc = imsiEncryptionInfo.getMcc();
                    halImsiInfo.keyIdentifier = imsiEncryptionInfo.getKeyIdentifier();
                    if (imsiEncryptionInfo.getExpirationTime() != null) {
                        halImsiInfo.expirationTime =
                                imsiEncryptionInfo.getExpirationTime().getTime();
                    }
                    for (byte b : imsiEncryptionInfo.getPublicKey().getEncoded()) {
                        halImsiInfo.carrierKey.add(new Byte(b));
                    }

                    radioProxy11.setCarrierInfoForImsiEncryption(
                            rr.mSerial, halImsiInfo);
                } catch (RemoteException | RuntimeException e) {
                    handleRadioProxyExceptionForRR(rr, "setCarrierInfoForImsiEncryption", e);
                }
            }
        }
    }

   @Override
    public void getAtr(Message response) {
    }

    @Override
    public void getIMEI(Message result) {
        throw new RuntimeException("getIMEI not expected to be called");
    }

    @Override
    public void getIMEISV(Message result) {
        throw new RuntimeException("getIMEISV not expected to be called");
    }

    /**
     * @deprecated
     */
    @Deprecated
    @Override
    public void getLastPdpFailCause(Message result) {
        throw new RuntimeException("getLastPdpFailCause not expected to be called");
    }

    /**
     * The preferred new alternative to getLastPdpFailCause
     */
    @Override
    public void getLastDataCallFailCause(Message result) {
        throw new RuntimeException("getLastDataCallFailCause not expected to be called");
    }

    /**
     *  Translates EF_SMS status bits to a status value compatible with
     *  SMS AT commands.  See TS 27.005 3.1.
     */
    private int translateStatus(int status) {
        switch(status & 0x7) {
            case SmsManager.STATUS_ON_ICC_READ:
                return 1;
            case SmsManager.STATUS_ON_ICC_UNREAD:
                return 0;
            case SmsManager.STATUS_ON_ICC_SENT:
                return 3;
            case SmsManager.STATUS_ON_ICC_UNSENT:
                return 2;
        }

        // Default to READ.
        return 1;
    }

    @Override
    public void resetRadio(Message result) {
        throw new RuntimeException("resetRadio not expected to be called");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void handleCallSetupRequestFromSim(boolean accept, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_STK_HANDLE_CALL_SETUP_REQUESTED_FROM_SIM,
                    result, mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
            }

            try {
                radioProxy.handleStkCallSetupRequestFromSim(rr.mSerial, accept);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "getAllowedCarriers", e);
            }
        }
    }

    //***** Private Methods

    /**
     * This is a helper function to be called when a RadioIndication callback is called.
     * It takes care of acquiring wakelock and sending ack if needed.
     * @param indicationType RadioIndicationType received
     */
    protected void processIndication(int indicationType) {
        if (indicationType == RadioIndicationType.UNSOLICITED_ACK_EXP) {
            sendAck();
            if (RILJ_LOGD) riljLog("Unsol response received; Sending ack to ril.cpp");
        } else {
            // ack is not expected to be sent back. Nothing is required to be done here.
        }
    }

    void processRequestAck(int serial) {
        RILRequest rr;
        synchronized (mRequestList) {
            rr = mRequestList.get(serial);
        }
        if (rr == null) {
            Rlog.w(RIL.RILJ_LOG_TAG, "processRequestAck: Unexpected solicited ack response! "
                    + "serial: " + serial);
        } else {
            decrementWakeLock(rr);
            if (RIL.RILJ_LOGD) {
                riljLog(rr.serialString() + " Ack < " + RIL.requestToString(rr.mRequest));
            }
        }
    }

    /**
     * This is a helper function to be called when a RadioResponse callback is called.
     * It takes care of acks, wakelocks, and finds and returns RILRequest corresponding to the
     * response if one is found.
     * @param responseInfo RadioResponseInfo received in response callback
     * @return RILRequest corresponding to the response
     */
    protected RILRequest processResponse(RadioResponseInfo responseInfo) {
        int serial = responseInfo.serial;
        int error = responseInfo.error;
        int type = responseInfo.type;

        RILRequest rr = null;

        if (type == RadioResponseType.SOLICITED_ACK) {
            synchronized (mRequestList) {
                rr = mRequestList.get(serial);
            }
            if (rr == null) {
                Rlog.w(RILJ_LOG_TAG, "Unexpected solicited ack response! sn: " + serial);
            } else {
                decrementWakeLock(rr);
                if (RILJ_LOGD) {
                    riljLog(rr.serialString() + " Ack < " + requestToString(rr.mRequest));
                }
            }
            return rr;
        }

        rr = findAndRemoveRequestFromList(serial);
        if (rr == null) {
            Rlog.e(RIL.RILJ_LOG_TAG, "processResponse: Unexpected response! serial: " + serial
                    + " error: " + error);
            return null;
        }

        // Time logging for RIL command and storing it in TelephonyHistogram.
        addToRilHistogram(rr);

        if (type == RadioResponseType.SOLICITED_ACK_EXP) {
            sendAck();
            if (RIL.RILJ_LOGD) {
                riljLog("Response received for " + rr.serialString() + " "
                        + RIL.requestToString(rr.mRequest) + " Sending ack to ril.cpp");
            }
        } else {
            // ack sent for SOLICITED_ACK_EXP above; nothing to do for SOLICITED response
        }

        // Here and below fake RIL_UNSOL_RESPONSE_SIM_STATUS_CHANGED, see b/7255789.
        // This is needed otherwise we don't automatically transition to the main lock
        // screen when the pin or puk is entered incorrectly.
        switch (rr.mRequest) {
            case RIL_REQUEST_ENTER_SIM_PUK:
            case RIL_REQUEST_ENTER_SIM_PUK2:
                if (mIccStatusChangedRegistrants != null) {
                    if (RILJ_LOGD) {
                        riljLog("ON enter sim puk fakeSimStatusChanged: reg count="
                                + mIccStatusChangedRegistrants.size());
                    }
                    mIccStatusChangedRegistrants.notifyRegistrants();
                }
                break;
            case RIL_REQUEST_SHUTDOWN:
                setRadioState(RadioState.RADIO_UNAVAILABLE);
                break;
        }

        if (error != RadioError.NONE) {
            switch (rr.mRequest) {
                case RIL_REQUEST_ENTER_SIM_PIN:
                case RIL_REQUEST_ENTER_SIM_PIN2:
                case RIL_REQUEST_CHANGE_SIM_PIN:
                case RIL_REQUEST_CHANGE_SIM_PIN2:
                case RIL_REQUEST_SET_FACILITY_LOCK:
                    if (mIccStatusChangedRegistrants != null) {
                        if (RILJ_LOGD) {
                            riljLog("ON some errors fakeSimStatusChanged: reg count="
                                    + mIccStatusChangedRegistrants.size());
                        }
                        mIccStatusChangedRegistrants.notifyRegistrants();
                    }
                    break;

            }
        } else {
            switch (rr.mRequest) {
                case RIL_REQUEST_HANGUP_FOREGROUND_RESUME_BACKGROUND:
                if (mTestingEmergencyCall.getAndSet(false)) {
                    if (mEmergencyCallbackModeRegistrant != null) {
                        riljLog("testing emergency call, notify ECM Registrants");
                        mEmergencyCallbackModeRegistrant.notifyRegistrant();
                    }
                }
            }
        }
        return rr;
    }

    protected Message getMessageFromRequest(Object request) {
        RILRequest rr = (RILRequest)request;
        Message result = null;
        if (rr != null) {
                result = rr.mResult;
        }
        return result;
    }

    /**
     * This is a helper function to be called at the end of all RadioResponse callbacks.
     * It takes care of sending error response, logging, decrementing wakelock if needed, and
     * releases the request from memory pool.
     * @param rr RILRequest for which response callback was called
     * @param responseInfo RadioResponseInfo received in the callback
     * @param ret object to be returned to request sender
     */
    protected void processResponseDone(RILRequest rr, RadioResponseInfo responseInfo, Object ret) {
        if (responseInfo.error == 0) {
            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "< " + requestToString(rr.mRequest)
                        + " " + retToString(rr.mRequest, ret));
            }
        } else {
            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "< " + requestToString(rr.mRequest)
                        + " error " + responseInfo.error);
            }
            rr.onError(responseInfo.error, ret);
        }
        mMetrics.writeOnRilSolicitedResponse(mPhoneId, rr.mSerial, responseInfo.error,
                rr.mRequest, ret);
        if (rr != null) {
            if (responseInfo.type == RadioResponseType.SOLICITED) {
                decrementWakeLock(rr);
            }
            rr.release();
        }
    }

    protected void processResponseDone(Object request, RadioResponseInfo responseInfo, Object ret) {
        RILRequest rr = (RILRequest)request;
        processResponseDone(rr, responseInfo, ret);
    }
    /**
     * Function to send ack and acquire related wakelock
     */
    private void sendAck() {
        // TODO: Remove rr and clean up acquireWakelock for response and ack
        RILRequest rr = RILRequest.obtain(RIL_RESPONSE_ACKNOWLEDGEMENT, null,
                mRILDefaultWorkSource);
        acquireWakeLock(rr, RIL.FOR_ACK_WAKELOCK);
        IRadio radioProxy = getRadioProxy(null);
        if (radioProxy != null) {
            try {
                radioProxy.responseAcknowledgement();
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "sendAck", e);
                riljLoge("sendAck: " + e);
            }
        } else {
            Rlog.e(RILJ_LOG_TAG, "Error trying to send ack, radioProxy = null");
        }
        rr.release();
    }

    private WorkSource getDeafultWorkSourceIfInvalid(WorkSource workSource) {
        if (workSource == null) {
            workSource = mRILDefaultWorkSource;
        }

        return workSource;
    }

    private String getWorkSourceClientId(WorkSource workSource) {
        if (workSource != null) {
            return String.valueOf(workSource.get(0)) + ":" + workSource.getName(0);
        }

        return null;
    }

    /**
     * Holds a PARTIAL_WAKE_LOCK whenever
     * a) There is outstanding RIL request sent to RIL deamon and no replied
     * b) There is a request pending to be sent out.
     *
     * There is a WAKE_LOCK_TIMEOUT to release the lock, though it shouldn't
     * happen often.
     */

    private void acquireWakeLock(RILRequest rr, int wakeLockType) {
        synchronized (rr) {
            if (rr.mWakeLockType != INVALID_WAKELOCK) {
                Rlog.d(RILJ_LOG_TAG, "Failed to aquire wakelock for " + rr.serialString());
                return;
            }

            switch(wakeLockType) {
                case FOR_WAKELOCK:
                    synchronized (mWakeLock) {
                        mWakeLock.acquire();
                        mWakeLockCount++;
                        mWlSequenceNum++;

                        String clientId = getWorkSourceClientId(rr.mWorkSource);
                        if (!mClientWakelockTracker.isClientActive(clientId)) {
                            if (mActiveWakelockWorkSource != null) {
                                mActiveWakelockWorkSource.add(rr.mWorkSource);
                            } else {
                                mActiveWakelockWorkSource = rr.mWorkSource;
                            }
                            mWakeLock.setWorkSource(mActiveWakelockWorkSource);
                        }

                        mClientWakelockTracker.startTracking(rr.mClientId,
                                rr.mRequest, rr.mSerial, mWakeLockCount);

                        Message msg = mRilHandler.obtainMessage(EVENT_WAKE_LOCK_TIMEOUT);
                        msg.arg1 = mWlSequenceNum;
                        mRilHandler.sendMessageDelayed(msg, mWakeLockTimeout);
                    }
                    break;
                case FOR_ACK_WAKELOCK:
                    synchronized (mAckWakeLock) {
                        mAckWakeLock.acquire();
                        mAckWlSequenceNum++;

                        Message msg = mRilHandler.obtainMessage(EVENT_ACK_WAKE_LOCK_TIMEOUT);
                        msg.arg1 = mAckWlSequenceNum;
                        mRilHandler.sendMessageDelayed(msg, mAckWakeLockTimeout);
                    }
                    break;
                default: //WTF
                    Rlog.w(RILJ_LOG_TAG, "Acquiring Invalid Wakelock type " + wakeLockType);
                    return;
            }
            rr.mWakeLockType = wakeLockType;
        }
    }

    private void decrementWakeLock(RILRequest rr) {
        synchronized (rr) {
            switch(rr.mWakeLockType) {
                case FOR_WAKELOCK:
                    synchronized (mWakeLock) {
                        mClientWakelockTracker.stopTracking(rr.mClientId,
                                rr.mRequest, rr.mSerial,
                                (mWakeLockCount > 1) ? mWakeLockCount - 1 : 0);
                        String clientId = getWorkSourceClientId(rr.mWorkSource);;
                        if (!mClientWakelockTracker.isClientActive(clientId)
                                && (mActiveWakelockWorkSource != null)) {
                            mActiveWakelockWorkSource.remove(rr.mWorkSource);
                            if (mActiveWakelockWorkSource.size() == 0) {
                                mActiveWakelockWorkSource = null;
                            }
                            mWakeLock.setWorkSource(mActiveWakelockWorkSource);
                        }

                        if (mWakeLockCount > 1) {
                            mWakeLockCount--;
                        } else {
                            mWakeLockCount = 0;
                            mWakeLock.release();
                        }
                    }
                    break;
                case FOR_ACK_WAKELOCK:
                    //We do not decrement the ACK wakelock
                    break;
                case INVALID_WAKELOCK:
                    break;
                default:
                    Rlog.w(RILJ_LOG_TAG, "Decrementing Invalid Wakelock type " + rr.mWakeLockType);
            }
            rr.mWakeLockType = INVALID_WAKELOCK;
        }
    }

    private boolean clearWakeLock(int wakeLockType) {
        if (wakeLockType == FOR_WAKELOCK) {
            synchronized (mWakeLock) {
                if (mWakeLockCount == 0 && !mWakeLock.isHeld()) return false;
                Rlog.d(RILJ_LOG_TAG, "NOTE: mWakeLockCount is " + mWakeLockCount
                        + "at time of clearing");
                mWakeLockCount = 0;
                mWakeLock.release();
                mClientWakelockTracker.stopTrackingAll();
                mActiveWakelockWorkSource = null;
                return true;
            }
        } else {
            synchronized (mAckWakeLock) {
                if (!mAckWakeLock.isHeld()) return false;
                mAckWakeLock.release();
                return true;
            }
        }
    }

    /**
     * Release each request in mRequestList then clear the list
     * @param error is the RIL_Errno sent back
     * @param loggable true means to print all requests in mRequestList
     */
    private void clearRequestList(int error, boolean loggable) {
        RILRequest rr;
        synchronized (mRequestList) {
            int count = mRequestList.size();
            if (RILJ_LOGD && loggable) {
                Rlog.d(RILJ_LOG_TAG, "clearRequestList " + " mWakeLockCount="
                        + mWakeLockCount + " mRequestList=" + count);
            }

            for (int i = 0; i < count; i++) {
                rr = mRequestList.valueAt(i);
                if (RILJ_LOGD && loggable) {
                    Rlog.d(RILJ_LOG_TAG, i + ": [" + rr.mSerial + "] "
                            + requestToString(rr.mRequest));
                }
                rr.onError(error, null);
                decrementWakeLock(rr);
                rr.release();
            }
            mRequestList.clear();
        }
    }

    private RILRequest findAndRemoveRequestFromList(int serial) {
        RILRequest rr = null;
        synchronized (mRequestList) {
            rr = mRequestList.get(serial);
            if (rr != null) {
                mRequestList.remove(serial);
            }
        }

        return rr;
    }

    private void addToRilHistogram(RILRequest rr) {
        long endTime = SystemClock.elapsedRealtime();
        int totalTime = (int) (endTime - rr.mStartTimeMs);

        synchronized (mRilTimeHistograms) {
            TelephonyHistogram entry = mRilTimeHistograms.get(rr.mRequest);
            if (entry == null) {
                // We would have total #RIL_HISTOGRAM_BUCKET_COUNT range buckets for RIL commands
                entry = new TelephonyHistogram(TelephonyHistogram.TELEPHONY_CATEGORY_RIL,
                        rr.mRequest, RIL_HISTOGRAM_BUCKET_COUNT);
                mRilTimeHistograms.put(rr.mRequest, entry);
            }
            entry.addTimeTaken(totalTime);
        }
    }

    RadioCapability makeStaticRadioCapability() {
        // default to UNKNOWN so we fail fast.
        int raf = RadioAccessFamily.RAF_UNKNOWN;

        String rafString = mContext.getResources().getString(
                com.android.internal.R.string.config_radio_access_family);
        if (!TextUtils.isEmpty(rafString)) {
            raf = RadioAccessFamily.rafTypeFromString(rafString);
        }
        RadioCapability rc = new RadioCapability(mPhoneId.intValue(), 0, 0, raf,
                "", RadioCapability.RC_STATUS_SUCCESS);
        if (RILJ_LOGD) riljLog("Faking RIL_REQUEST_GET_RADIO_CAPABILITY response using " + raf);
        return rc;
    }

    static String retToString(int req, Object ret) {
        if (ret == null) return "";
        switch (req) {
            // Don't log these return values, for privacy's sake.
            case RIL_REQUEST_GET_IMSI:
            case RIL_REQUEST_GET_IMEI:
            case RIL_REQUEST_GET_IMEISV:
            case RIL_REQUEST_SIM_OPEN_CHANNEL:
            case RIL_REQUEST_SIM_TRANSMIT_APDU_CHANNEL:

                if (!RILJ_LOGV) {
                    // If not versbose logging just return and don't display IMSI and IMEI, IMEISV
                    return "";
                }
        }

        StringBuilder sb;
        String s;
        int length;
        if (ret instanceof int[]) {
            int[] intArray = (int[]) ret;
            length = intArray.length;
            sb = new StringBuilder("{");
            if (length > 0) {
                int i = 0;
                sb.append(intArray[i++]);
                while (i < length) {
                    sb.append(", ").append(intArray[i++]);
                }
            }
            sb.append("}");
            s = sb.toString();
        } else if (ret instanceof String[]) {
            String[] strings = (String[]) ret;
            length = strings.length;
            sb = new StringBuilder("{");
            if (length > 0) {
                int i = 0;
                sb.append(strings[i++]);
                while (i < length) {
                    sb.append(", ").append(strings[i++]);
                }
            }
            sb.append("}");
            s = sb.toString();
        } else if (req == RIL_REQUEST_GET_CURRENT_CALLS) {
            ArrayList<DriverCall> calls = (ArrayList<DriverCall>) ret;
            sb = new StringBuilder("{");
            for (DriverCall dc : calls) {
                sb.append("[").append(dc).append("] ");
            }
            sb.append("}");
            s = sb.toString();
        } else if (req == RIL_REQUEST_GET_NEIGHBORING_CELL_IDS) {
            ArrayList<NeighboringCellInfo> cells = (ArrayList<NeighboringCellInfo>) ret;
            sb = new StringBuilder("{");
            for (NeighboringCellInfo cell : cells) {
                sb.append("[").append(cell).append("] ");
            }
            sb.append("}");
            s = sb.toString();
        } else if (req == RIL_REQUEST_QUERY_CALL_FORWARD_STATUS) {
            CallForwardInfo[] cinfo = (CallForwardInfo[]) ret;
            length = cinfo.length;
            sb = new StringBuilder("{");
            for (int i = 0; i < length; i++) {
                sb.append("[").append(cinfo[i]).append("] ");
            }
            sb.append("}");
            s = sb.toString();
        } else if (req == RIL_REQUEST_GET_HARDWARE_CONFIG) {
            ArrayList<HardwareConfig> hwcfgs = (ArrayList<HardwareConfig>) ret;
            sb = new StringBuilder(" ");
            for (HardwareConfig hwcfg : hwcfgs) {
                sb.append("[").append(hwcfg).append("] ");
            }
            s = sb.toString();
        } else {
            s = ret.toString();
        }
        return s;
    }

    void writeMetricsNewSms(int tech, int format) {
        mMetrics.writeRilNewSms(mPhoneId, tech, format);
    }

    void writeMetricsCallRing(char[] response) {
        mMetrics.writeRilCallRing(mPhoneId, response);
    }

    void writeMetricsSrvcc(int state) {
        mMetrics.writeRilSrvcc(mPhoneId, state);
    }

    void writeMetricsModemRestartEvent(String reason) {
        mMetrics.writeModemRestartEvent(mPhoneId, reason);
    }

    /**
     * Notify all registrants that the ril has connected or disconnected.
     *
     * @param rilVer is the version of the ril or -1 if disconnected.
     */
    void notifyRegistrantsRilConnectionChanged(int rilVer) {
        mRilVersion = rilVer;
        if (mRilConnectedRegistrants != null) {
            mRilConnectedRegistrants.notifyRegistrants(
                    new AsyncResult(null, new Integer(rilVer), null));
        }
    }

    void
    notifyRegistrantsCdmaInfoRec(CdmaInformationRecords infoRec) {
        int response = RIL_UNSOL_CDMA_INFO_REC;
        if (infoRec.record instanceof CdmaInformationRecords.CdmaDisplayInfoRec) {
            if (mDisplayInfoRegistrants != null) {
                if (RILJ_LOGD) unsljLogRet(response, infoRec.record);
                mDisplayInfoRegistrants.notifyRegistrants(
                        new AsyncResult(null, infoRec.record, null));
            }
        } else if (infoRec.record instanceof CdmaInformationRecords.CdmaSignalInfoRec) {
            if (mSignalInfoRegistrants != null) {
                if (RILJ_LOGD) unsljLogRet(response, infoRec.record);
                mSignalInfoRegistrants.notifyRegistrants(
                        new AsyncResult(null, infoRec.record, null));
            }
        } else if (infoRec.record instanceof CdmaInformationRecords.CdmaNumberInfoRec) {
            if (mNumberInfoRegistrants != null) {
                if (RILJ_LOGD) unsljLogRet(response, infoRec.record);
                mNumberInfoRegistrants.notifyRegistrants(
                        new AsyncResult(null, infoRec.record, null));
            }
        } else if (infoRec.record instanceof CdmaInformationRecords.CdmaRedirectingNumberInfoRec) {
            if (mRedirNumInfoRegistrants != null) {
                if (RILJ_LOGD) unsljLogRet(response, infoRec.record);
                mRedirNumInfoRegistrants.notifyRegistrants(
                        new AsyncResult(null, infoRec.record, null));
            }
        } else if (infoRec.record instanceof CdmaInformationRecords.CdmaLineControlInfoRec) {
            if (mLineControlInfoRegistrants != null) {
                if (RILJ_LOGD) unsljLogRet(response, infoRec.record);
                mLineControlInfoRegistrants.notifyRegistrants(
                        new AsyncResult(null, infoRec.record, null));
            }
        } else if (infoRec.record instanceof CdmaInformationRecords.CdmaT53ClirInfoRec) {
            if (mT53ClirInfoRegistrants != null) {
                if (RILJ_LOGD) unsljLogRet(response, infoRec.record);
                mT53ClirInfoRegistrants.notifyRegistrants(
                        new AsyncResult(null, infoRec.record, null));
            }
        } else if (infoRec.record instanceof CdmaInformationRecords.CdmaT53AudioControlInfoRec) {
            if (mT53AudCntrlInfoRegistrants != null) {
                if (RILJ_LOGD) {
                    unsljLogRet(response, infoRec.record);
                }
                mT53AudCntrlInfoRegistrants.notifyRegistrants(
                        new AsyncResult(null, infoRec.record, null));
            }
        }
    }

    static String requestToString(int request) {
        switch(request) {
            case RIL_REQUEST_GET_SIM_STATUS:
                return "GET_SIM_STATUS";
            case RIL_REQUEST_ENTER_SIM_PIN:
                return "ENTER_SIM_PIN";
            case RIL_REQUEST_ENTER_SIM_PUK:
                return "ENTER_SIM_PUK";
            case RIL_REQUEST_ENTER_SIM_PIN2:
                return "ENTER_SIM_PIN2";
            case RIL_REQUEST_ENTER_SIM_PUK2:
                return "ENTER_SIM_PUK2";
            case RIL_REQUEST_CHANGE_SIM_PIN:
                return "CHANGE_SIM_PIN";
            case RIL_REQUEST_CHANGE_SIM_PIN2:
                return "CHANGE_SIM_PIN2";
            case RIL_REQUEST_ENTER_NETWORK_DEPERSONALIZATION:
                return "ENTER_NETWORK_DEPERSONALIZATION";
            case RIL_REQUEST_GET_CURRENT_CALLS:
                return "GET_CURRENT_CALLS";
            case RIL_REQUEST_DIAL:
                return "DIAL";
            case RIL_REQUEST_GET_IMSI:
                return "GET_IMSI";
            case RIL_REQUEST_HANGUP:
                return "HANGUP";
            case RIL_REQUEST_HANGUP_WAITING_OR_BACKGROUND:
                return "HANGUP_WAITING_OR_BACKGROUND";
            case RIL_REQUEST_HANGUP_FOREGROUND_RESUME_BACKGROUND:
                return "HANGUP_FOREGROUND_RESUME_BACKGROUND";
            case RIL_REQUEST_SWITCH_WAITING_OR_HOLDING_AND_ACTIVE:
                return "REQUEST_SWITCH_WAITING_OR_HOLDING_AND_ACTIVE";
            case RIL_REQUEST_CONFERENCE:
                return "CONFERENCE";
            case RIL_REQUEST_UDUB:
                return "UDUB";
            case RIL_REQUEST_LAST_CALL_FAIL_CAUSE:
                return "LAST_CALL_FAIL_CAUSE";
            case RIL_REQUEST_SIGNAL_STRENGTH:
                return "SIGNAL_STRENGTH";
            case RIL_REQUEST_VOICE_REGISTRATION_STATE:
                return "VOICE_REGISTRATION_STATE";
            case RIL_REQUEST_DATA_REGISTRATION_STATE:
                return "DATA_REGISTRATION_STATE";
            case RIL_REQUEST_OPERATOR:
                return "OPERATOR";
            case RIL_REQUEST_RADIO_POWER:
                return "RADIO_POWER";
            case RIL_REQUEST_DTMF:
                return "DTMF";
            case RIL_REQUEST_SEND_SMS:
                return "SEND_SMS";
            case RIL_REQUEST_SEND_SMS_EXPECT_MORE:
                return "SEND_SMS_EXPECT_MORE";
            case RIL_REQUEST_SETUP_DATA_CALL:
                return "SETUP_DATA_CALL";
            case RIL_REQUEST_SIM_IO:
                return "SIM_IO";
            case RIL_REQUEST_SEND_USSD:
                return "SEND_USSD";
            case RIL_REQUEST_CANCEL_USSD:
                return "CANCEL_USSD";
            case RIL_REQUEST_GET_CLIR:
                return "GET_CLIR";
            case RIL_REQUEST_SET_CLIR:
                return "SET_CLIR";
            case RIL_REQUEST_QUERY_CALL_FORWARD_STATUS:
                return "QUERY_CALL_FORWARD_STATUS";
            case RIL_REQUEST_SET_CALL_FORWARD:
                return "SET_CALL_FORWARD";
            case RIL_REQUEST_QUERY_CALL_WAITING:
                return "QUERY_CALL_WAITING";
            case RIL_REQUEST_SET_CALL_WAITING:
                return "SET_CALL_WAITING";
            case RIL_REQUEST_SMS_ACKNOWLEDGE:
                return "SMS_ACKNOWLEDGE";
            case RIL_REQUEST_GET_IMEI:
                return "GET_IMEI";
            case RIL_REQUEST_GET_IMEISV:
                return "GET_IMEISV";
            case RIL_REQUEST_ANSWER:
                return "ANSWER";
            case RIL_REQUEST_DEACTIVATE_DATA_CALL:
                return "DEACTIVATE_DATA_CALL";
            case RIL_REQUEST_QUERY_FACILITY_LOCK:
                return "QUERY_FACILITY_LOCK";
            case RIL_REQUEST_SET_FACILITY_LOCK:
                return "SET_FACILITY_LOCK";
            case RIL_REQUEST_CHANGE_BARRING_PASSWORD:
                return "CHANGE_BARRING_PASSWORD";
            case RIL_REQUEST_QUERY_NETWORK_SELECTION_MODE:
                return "QUERY_NETWORK_SELECTION_MODE";
            case RIL_REQUEST_SET_NETWORK_SELECTION_AUTOMATIC:
                return "SET_NETWORK_SELECTION_AUTOMATIC";
            case RIL_REQUEST_SET_NETWORK_SELECTION_MANUAL:
                return "SET_NETWORK_SELECTION_MANUAL";
            case RIL_REQUEST_QUERY_AVAILABLE_NETWORKS :
                return "QUERY_AVAILABLE_NETWORKS ";
            case RIL_REQUEST_DTMF_START:
                return "DTMF_START";
            case RIL_REQUEST_DTMF_STOP:
                return "DTMF_STOP";
            case RIL_REQUEST_BASEBAND_VERSION:
                return "BASEBAND_VERSION";
            case RIL_REQUEST_SEPARATE_CONNECTION:
                return "SEPARATE_CONNECTION";
            case RIL_REQUEST_SET_MUTE:
                return "SET_MUTE";
            case RIL_REQUEST_GET_MUTE:
                return "GET_MUTE";
            case RIL_REQUEST_QUERY_CLIP:
                return "QUERY_CLIP";
            case RIL_REQUEST_LAST_DATA_CALL_FAIL_CAUSE:
                return "LAST_DATA_CALL_FAIL_CAUSE";
            case RIL_REQUEST_DATA_CALL_LIST:
                return "DATA_CALL_LIST";
            case RIL_REQUEST_RESET_RADIO:
                return "RESET_RADIO";
            case RIL_REQUEST_OEM_HOOK_RAW:
                return "OEM_HOOK_RAW";
            case RIL_REQUEST_OEM_HOOK_STRINGS:
                return "OEM_HOOK_STRINGS";
            case RIL_REQUEST_SCREEN_STATE:
                return "SCREEN_STATE";
            case RIL_REQUEST_SET_SUPP_SVC_NOTIFICATION:
                return "SET_SUPP_SVC_NOTIFICATION";
            case RIL_REQUEST_WRITE_SMS_TO_SIM:
                return "WRITE_SMS_TO_SIM";
            case RIL_REQUEST_DELETE_SMS_ON_SIM:
                return "DELETE_SMS_ON_SIM";
            case RIL_REQUEST_SET_BAND_MODE:
                return "SET_BAND_MODE";
            case RIL_REQUEST_QUERY_AVAILABLE_BAND_MODE:
                return "QUERY_AVAILABLE_BAND_MODE";
            case RIL_REQUEST_STK_GET_PROFILE:
                return "REQUEST_STK_GET_PROFILE";
            case RIL_REQUEST_STK_SET_PROFILE:
                return "REQUEST_STK_SET_PROFILE";
            case RIL_REQUEST_STK_SEND_ENVELOPE_COMMAND:
                return "REQUEST_STK_SEND_ENVELOPE_COMMAND";
            case RIL_REQUEST_STK_SEND_TERMINAL_RESPONSE:
                return "REQUEST_STK_SEND_TERMINAL_RESPONSE";
            case RIL_REQUEST_STK_HANDLE_CALL_SETUP_REQUESTED_FROM_SIM:
                return "REQUEST_STK_HANDLE_CALL_SETUP_REQUESTED_FROM_SIM";
            case RIL_REQUEST_EXPLICIT_CALL_TRANSFER: return "REQUEST_EXPLICIT_CALL_TRANSFER";
            case RIL_REQUEST_SET_PREFERRED_NETWORK_TYPE:
                return "REQUEST_SET_PREFERRED_NETWORK_TYPE";
            case RIL_REQUEST_GET_PREFERRED_NETWORK_TYPE:
                return "REQUEST_GET_PREFERRED_NETWORK_TYPE";
            case RIL_REQUEST_GET_NEIGHBORING_CELL_IDS:
                return "REQUEST_GET_NEIGHBORING_CELL_IDS";
            case RIL_REQUEST_SET_LOCATION_UPDATES:
                return "REQUEST_SET_LOCATION_UPDATES";
            case RIL_REQUEST_CDMA_SET_SUBSCRIPTION_SOURCE:
                return "RIL_REQUEST_CDMA_SET_SUBSCRIPTION_SOURCE";
            case RIL_REQUEST_CDMA_SET_ROAMING_PREFERENCE:
                return "RIL_REQUEST_CDMA_SET_ROAMING_PREFERENCE";
            case RIL_REQUEST_CDMA_QUERY_ROAMING_PREFERENCE:
                return "RIL_REQUEST_CDMA_QUERY_ROAMING_PREFERENCE";
            case RIL_REQUEST_SET_TTY_MODE:
                return "RIL_REQUEST_SET_TTY_MODE";
            case RIL_REQUEST_QUERY_TTY_MODE:
                return "RIL_REQUEST_QUERY_TTY_MODE";
            case RIL_REQUEST_CDMA_SET_PREFERRED_VOICE_PRIVACY_MODE:
                return "RIL_REQUEST_CDMA_SET_PREFERRED_VOICE_PRIVACY_MODE";
            case RIL_REQUEST_CDMA_QUERY_PREFERRED_VOICE_PRIVACY_MODE:
                return "RIL_REQUEST_CDMA_QUERY_PREFERRED_VOICE_PRIVACY_MODE";
            case RIL_REQUEST_CDMA_FLASH:
                return "RIL_REQUEST_CDMA_FLASH";
            case RIL_REQUEST_CDMA_BURST_DTMF:
                return "RIL_REQUEST_CDMA_BURST_DTMF";
            case RIL_REQUEST_CDMA_SEND_SMS:
                return "RIL_REQUEST_CDMA_SEND_SMS";
            case RIL_REQUEST_CDMA_SMS_ACKNOWLEDGE:
                return "RIL_REQUEST_CDMA_SMS_ACKNOWLEDGE";
            case RIL_REQUEST_GSM_GET_BROADCAST_CONFIG:
                return "RIL_REQUEST_GSM_GET_BROADCAST_CONFIG";
            case RIL_REQUEST_GSM_SET_BROADCAST_CONFIG:
                return "RIL_REQUEST_GSM_SET_BROADCAST_CONFIG";
            case RIL_REQUEST_CDMA_GET_BROADCAST_CONFIG:
                return "RIL_REQUEST_CDMA_GET_BROADCAST_CONFIG";
            case RIL_REQUEST_CDMA_SET_BROADCAST_CONFIG:
                return "RIL_REQUEST_CDMA_SET_BROADCAST_CONFIG";
            case RIL_REQUEST_GSM_BROADCAST_ACTIVATION:
                return "RIL_REQUEST_GSM_BROADCAST_ACTIVATION";
            case RIL_REQUEST_CDMA_VALIDATE_AND_WRITE_AKEY:
                return "RIL_REQUEST_CDMA_VALIDATE_AND_WRITE_AKEY";
            case RIL_REQUEST_CDMA_BROADCAST_ACTIVATION:
                return "RIL_REQUEST_CDMA_BROADCAST_ACTIVATION";
            case RIL_REQUEST_CDMA_SUBSCRIPTION:
                return "RIL_REQUEST_CDMA_SUBSCRIPTION";
            case RIL_REQUEST_CDMA_WRITE_SMS_TO_RUIM:
                return "RIL_REQUEST_CDMA_WRITE_SMS_TO_RUIM";
            case RIL_REQUEST_CDMA_DELETE_SMS_ON_RUIM:
                return "RIL_REQUEST_CDMA_DELETE_SMS_ON_RUIM";
            case RIL_REQUEST_DEVICE_IDENTITY:
                return "RIL_REQUEST_DEVICE_IDENTITY";
            case RIL_REQUEST_GET_SMSC_ADDRESS:
                return "RIL_REQUEST_GET_SMSC_ADDRESS";
            case RIL_REQUEST_SET_SMSC_ADDRESS:
                return "RIL_REQUEST_SET_SMSC_ADDRESS";
            case RIL_REQUEST_EXIT_EMERGENCY_CALLBACK_MODE:
                return "REQUEST_EXIT_EMERGENCY_CALLBACK_MODE";
            case RIL_REQUEST_REPORT_SMS_MEMORY_STATUS:
                return "RIL_REQUEST_REPORT_SMS_MEMORY_STATUS";
            case RIL_REQUEST_REPORT_STK_SERVICE_IS_RUNNING:
                return "RIL_REQUEST_REPORT_STK_SERVICE_IS_RUNNING";
            case RIL_REQUEST_CDMA_GET_SUBSCRIPTION_SOURCE:
                return "RIL_REQUEST_CDMA_GET_SUBSCRIPTION_SOURCE";
            case RIL_REQUEST_ISIM_AUTHENTICATION:
                return "RIL_REQUEST_ISIM_AUTHENTICATION";
            case RIL_REQUEST_ACKNOWLEDGE_INCOMING_GSM_SMS_WITH_PDU:
                return "RIL_REQUEST_ACKNOWLEDGE_INCOMING_GSM_SMS_WITH_PDU";
            case RIL_REQUEST_STK_SEND_ENVELOPE_WITH_STATUS:
                return "RIL_REQUEST_STK_SEND_ENVELOPE_WITH_STATUS";
            case RIL_REQUEST_VOICE_RADIO_TECH:
                return "RIL_REQUEST_VOICE_RADIO_TECH";
            case RIL_REQUEST_GET_CELL_INFO_LIST:
                return "RIL_REQUEST_GET_CELL_INFO_LIST";
            case RIL_REQUEST_SET_UNSOL_CELL_INFO_LIST_RATE:
                return "RIL_REQUEST_SET_CELL_INFO_LIST_RATE";
            case RIL_REQUEST_SET_INITIAL_ATTACH_APN:
                return "RIL_REQUEST_SET_INITIAL_ATTACH_APN";
            case RIL_REQUEST_SET_DATA_PROFILE:
                return "RIL_REQUEST_SET_DATA_PROFILE";
            case RIL_REQUEST_IMS_REGISTRATION_STATE:
                return "RIL_REQUEST_IMS_REGISTRATION_STATE";
            case RIL_REQUEST_IMS_SEND_SMS:
                return "RIL_REQUEST_IMS_SEND_SMS";
            case RIL_REQUEST_SIM_TRANSMIT_APDU_BASIC:
                return "RIL_REQUEST_SIM_TRANSMIT_APDU_BASIC";
            case RIL_REQUEST_SIM_OPEN_CHANNEL:
                return "RIL_REQUEST_SIM_OPEN_CHANNEL";
            case RIL_REQUEST_SIM_CLOSE_CHANNEL:
                return "RIL_REQUEST_SIM_CLOSE_CHANNEL";
            case RIL_REQUEST_SIM_TRANSMIT_APDU_CHANNEL:
                return "RIL_REQUEST_SIM_TRANSMIT_APDU_CHANNEL";
            case RIL_REQUEST_NV_READ_ITEM:
                return "RIL_REQUEST_NV_READ_ITEM";
            case RIL_REQUEST_NV_WRITE_ITEM:
                return "RIL_REQUEST_NV_WRITE_ITEM";
            case RIL_REQUEST_NV_WRITE_CDMA_PRL:
                return "RIL_REQUEST_NV_WRITE_CDMA_PRL";
            case RIL_REQUEST_NV_RESET_CONFIG:
                return "RIL_REQUEST_NV_RESET_CONFIG";
            case RIL_REQUEST_SET_UICC_SUBSCRIPTION:
                return "RIL_REQUEST_SET_UICC_SUBSCRIPTION";
            case RIL_REQUEST_ALLOW_DATA:
                return "RIL_REQUEST_ALLOW_DATA";
            case RIL_REQUEST_GET_HARDWARE_CONFIG:
                return "GET_HARDWARE_CONFIG";
            case RIL_REQUEST_SIM_AUTHENTICATION:
                return "RIL_REQUEST_SIM_AUTHENTICATION";
            case RIL_REQUEST_SHUTDOWN:
                return "RIL_REQUEST_SHUTDOWN";
            case RIL_REQUEST_SET_RADIO_CAPABILITY:
                return "RIL_REQUEST_SET_RADIO_CAPABILITY";
            case RIL_REQUEST_GET_RADIO_CAPABILITY:
                return "RIL_REQUEST_GET_RADIO_CAPABILITY";
            case RIL_REQUEST_START_LCE:
                return "RIL_REQUEST_START_LCE";
            case RIL_REQUEST_STOP_LCE:
                return "RIL_REQUEST_STOP_LCE";
            case RIL_REQUEST_PULL_LCEDATA:
                return "RIL_REQUEST_PULL_LCEDATA";
            case RIL_REQUEST_GET_ACTIVITY_INFO:
                return "RIL_REQUEST_GET_ACTIVITY_INFO";
            case RIL_REQUEST_SET_ALLOWED_CARRIERS:
                return "RIL_REQUEST_SET_ALLOWED_CARRIERS";
            case RIL_REQUEST_GET_ALLOWED_CARRIERS:
                return "RIL_REQUEST_GET_ALLOWED_CARRIERS";
            case RIL_REQUEST_SET_SIM_CARD_POWER:
                return "RIL_REQUEST_SET_SIM_CARD_POWER";
            case RIL_REQUEST_SEND_DEVICE_STATE:
                return "RIL_REQUEST_SEND_DEVICE_STATE";
            case RIL_REQUEST_SET_UNSOLICITED_RESPONSE_FILTER:
                return "RIL_REQUEST_SET_UNSOLICITED_RESPONSE_FILTER";
            case RIL_RESPONSE_ACKNOWLEDGEMENT:
                return "RIL_RESPONSE_ACKNOWLEDGEMENT";
            case RIL_REQUEST_SIM_QUERY_ATR:
                return "RIL_REQUEST_SIM_QUERY_ATR";
            case RIL_REQUEST_SET_CARRIER_INFO_IMSI_ENCRYPTION:
                return "RIL_REQUEST_SET_CARRIER_INFO_IMSI_ENCRYPTION";
            case RIL_REQUEST_START_NETWORK_SCAN:
                return "RIL_REQUEST_START_NETWORK_SCAN";
            case RIL_REQUEST_STOP_NETWORK_SCAN:
                return "RIL_REQUEST_STOP_NETWORK_SCAN";
            default: return "<unknown request>";
        }
    }

    public static String requestToStringEx(Integer request) {
        String msg;
        switch(request) {
            case RIL_REQUEST_SET_NETWORK_SELECTION_MANUAL_WITH_ACT:
                msg = "SET_NETWORK_SELECTION_MANUAL_WITH_ACT";
                break;
            case RIL_REQUEST_QUERY_AVAILABLE_NETWORKS_WITH_ACT:
                msg = "QUERY_AVAILABLE_NETWORKS_WITH_ACT";
                break;
            case RIL_REQUEST_ABORT_QUERY_AVAILABLE_NETWORKS:
                msg = "ABORT_QUERY_AVAILABLE_NETWORKS";
                break;
            case MtkRILConstants.RIL_REQUEST_MODEM_POWERON:
                msg = "RIL_REQUEST_MODEM_POWERON";
                break;
            case MtkRILConstants.RIL_REQUEST_MODEM_POWEROFF:
                msg = "RIL_REQUEST_MODEM_POWEROFF";
                break;
            /// M: CC: Proprietary incoming call handling
            case RIL_REQUEST_SET_CALL_INDICATION:
                msg = "SET_CALL_INDICATION";
                break;
            /// M: eMBMS feature
            case RIL_REQUEST_EMBMS_AT_CMD:
                msg = "RIL_REQUEST_EMBMS_AT_CMD";
                break;
            /// M: eMBMS end
            /// M: CC: Proprietary ECC enhancement @{
            case RIL_REQUEST_EMERGENCY_DIAL:
                msg = "EMERGENCY_DIAL";
                break;
            case RIL_REQUEST_SET_ECC_SERVICE_CATEGORY:
                msg = "SET_ECC_SERVICE_CATEGORY";
                break;
            case RIL_REQUEST_CURRENT_STATUS:
                msg = "CURRENT_STATUS";
                break;
            case RIL_REQUEST_ECC_PREFERRED_RAT:
                msg = "ECC_PREFERRED_RAT";
                break;
            case RIL_REQUEST_SET_ECC_LIST:
                msg = "RIL_REQUEST_SET_ECC_LIST";
                break;
            /// @}
            /// M: CC: HangupAll for FTA 31.4.4.2
            case RIL_REQUEST_HANGUP_ALL:
                msg = "HANGUP_ALL";
                break;
            /// M: CC: For 3G VT only @{
            case RIL_REQUEST_VT_DIAL:
                msg = "RIL_REQUEST_VT_DIAL";
                break;
            case RIL_REQUEST_VOICE_ACCEPT:
                msg = "VOICE_ACCEPT";
                break;
            case RIL_REQUEST_REPLACE_VT_CALL:
                msg = "RIL_REQUEST_REPLACE_VT_CALL";
                break;
            /// @}
            case MtkRILConstants.RIL_REQUEST_SET_PSEUDO_CELL_MODE:
                msg = "RIL_REQUEST_SET_PSEUDO_CELL_MODE";
                break;
            case MtkRILConstants.RIL_REQUEST_GET_PSEUDO_CELL_INFO:
                msg = "RIL_REQUEST_GET_PSEUDO_CELL_INFO";
                break;
            case RIL_REQUEST_SWITCH_MODE_FOR_ECC:
                msg = "RIL_REQUEST_SWITCH_MODE_FOR_ECC";
                break;
            case RIL_REQUEST_GET_SMS_RUIM_MEM_STATUS:
                msg = "RIL_REQUEST_GET_SMS_RUIM_MEM_STATUS";
                break;
            case RIL_REQUEST_SET_FD_MODE:
                msg = "RIL_REQUEST_SET_FD_MODE";
                break;
            case RIL_REQUEST_RESUME_REGISTRATION:
                msg = "RIL_REQUEST_RESUME_REGISTRATION";
                break;
            case RIL_REQUEST_RELOAD_MODEM_TYPE:
                msg = "RIL_REQUEST_RELOAD_MODEM_TYPE";
                break;
            case RIL_REQUEST_STORE_MODEM_TYPE:
                msg = "RIL_REQUEST_STORE_MODEM_TYPE";
                break;
            case RIL_REQUEST_SET_TRM:
                msg = "RIL_REQUEST_SET_TRM";
                break;
            //Femtocell (CSG) feature START
            case RIL_REQUEST_GET_FEMTOCELL_LIST:
                msg = "REQUEST_GET_FEMTOCELL_LIST";
                break;
            case RIL_REQUEST_ABORT_FEMTOCELL_LIST:
                msg = "REQUEST_ABORT_FEMTOCELL_LIST";
                break;
            case RIL_REQUEST_SELECT_FEMTOCELL:
                msg = "REQUEST_SELECT_FEMTOCELL";
                break;
            case RIL_REQUEST_QUERY_FEMTOCELL_SYSTEM_SELECTION_MODE:
                msg = "REQUEST_QUERY_FEMTOCELL_SYSTEM_SELECTION_MODE";
                break;
            case RIL_REQUEST_SET_FEMTOCELL_SYSTEM_SELECTION_MODE:
                msg = "REQUEST_SET_FEMTOCELL_SYSTEM_SELECTION_MODE";
                break;
            //Femtocell (CSG) feature END
            // PHB Start
            case RIL_REQUEST_QUERY_PHB_STORAGE_INFO: msg = "RIL_REQUEST_QUERY_PHB_STORAGE_INFO";
                break;
            case RIL_REQUEST_WRITE_PHB_ENTRY: msg = "RIL_REQUEST_WRITE_PHB_ENTRY";
                break;
            case RIL_REQUEST_READ_PHB_ENTRY: msg = "RIL_REQUEST_READ_PHB_ENTRY";
                break;
            case RIL_REQUEST_QUERY_UPB_CAPABILITY: msg = "RIL_REQUEST_QUERY_UPB_CAPABILITY";
                break;
            case RIL_REQUEST_EDIT_UPB_ENTRY: msg = "RIL_REQUEST_EDIT_UPB_ENTRY";
                break;
            case RIL_REQUEST_DELETE_UPB_ENTRY: msg = "RIL_REQUEST_DELETE_UPB_ENTRY";
                break;
            case RIL_REQUEST_READ_UPB_GAS_LIST: msg = "RIL_REQUEST_READ_UPB_GAS_LIST";
                break;
            case RIL_REQUEST_READ_UPB_GRP: msg = "RIL_REQUEST_READ_UPB_GRP";
                break;
            case RIL_REQUEST_WRITE_UPB_GRP: msg = "RIL_REQUEST_WRITE_UPB_GRP";
                break;
            case RIL_REQUEST_GET_PHB_STRING_LENGTH: msg = "RIL_REQUEST_GET_PHB_STRING_LENGTH";
                break;
            case RIL_REQUEST_GET_PHB_MEM_STORAGE: msg = "RIL_REQUEST_GET_PHB_MEM_STORAGE";
                break;
            case RIL_REQUEST_SET_PHB_MEM_STORAGE: msg = "RIL_REQUEST_SET_PHB_MEM_STORAGE";
                break;
            case RIL_REQUEST_READ_PHB_ENTRY_EXT: msg = "RIL_REQUEST_READ_PHB_ENTRY_EXT";
                break;
            case RIL_REQUEST_WRITE_PHB_ENTRY_EXT: msg = "RIL_REQUEST_WRITE_PHB_ENTRY_EXT";
                break;
            case RIL_REQUEST_QUERY_UPB_AVAILABLE: msg = "RIL_REQUEST_QUERY_UPB_AVAILABLE";
                break;
            case RIL_REQUEST_READ_EMAIL_ENTRY: msg = "RIL_REQUEST_READ_EMAIL_ENTRY";
                break;
            case RIL_REQUEST_READ_SNE_ENTRY: msg = "RIL_REQUEST_READ_SNE_ENTRY";
                break;
            case RIL_REQUEST_READ_ANR_ENTRY: msg = "RIL_REQUEST_READ_ANR_ENTRY";
                break;
            case RIL_REQUEST_READ_UPB_AAS_LIST: msg = "RIL_REQUEST_READ_UPB_AAS_LIST";
                break;
            // PHB End
            // MTK_TC1_FEATURE for Antenna Testing start
            case RIL_REQUEST_VSS_ANTENNA_CONF:
                msg = "RIL_REQUEST_VSS_ANTENNA_CONF";
                break;
            case RIL_REQUEST_VSS_ANTENNA_INFO:
                msg = "RIL_REQUEST_VSS_ANTENNA_INFO";
                break;
            // MTK_TC1_FEATURE for Antenna Testing end
            case RIL_REQUEST_GET_POL_CAPABILITY:
                msg = "RIL_REQUEST_GET_POL_CAPABILITY";
                break;
            case RIL_REQUEST_GET_POL_LIST:
                msg = "RIL_REQUEST_GET_POL_LIST";
                break;
            case RIL_REQUEST_SET_POL_ENTRY:
                msg = "RIL_REQUEST_SET_POL_ENTRY";
                break;
            // M: Data Framework - common part enhancement @}
            case RIL_REQUEST_SYNC_DATA_SETTINGS_TO_MD:
                msg = "RIL_REQUEST_SYNC_DATA_SETTINGS_TO_MD";
                break;
            // M: Data Framework - common part enhancement @}
            // M: Data Framework - Data Retry enhancement
            case RIL_REQUEST_RESET_MD_DATA_RETRY_COUNT:
                msg = "RIL_REQUEST_RESET_MD_DATA_RETRY_COUNT";
                break;
                // M: Data Framework - CC 33
            case RIL_REQUEST_SET_REMOVE_RESTRICT_EUTRAN_MODE:
                msg = "RIL_REQUEST_SET_REMOVE_RESTRICT_EUTRAN_MODE";
                break;
            // M: [LTE][Low Power][UL traffic shaping] @{
            case RIL_REQUEST_SET_LTE_ACCESS_STRATUM_REPORT:
                msg = "RIL_REQUEST_SET_LTE_ACCESS_STRATUM_REPORT";
                break;
            case RIL_REQUEST_SET_LTE_UPLINK_DATA_TRANSFER:
                msg = "RIL_REQUEST_SET_LTE_UPLINK_DATA_TRANSFER";
                break;
            // M: [LTE][Low Power][UL traffic shaping] @}
            // MTK-START: SIM
            case MtkRILConstants.RIL_REQUEST_QUERY_SIM_NETWORK_LOCK:
                msg = "RIL_REQUEST_QUERY_SIM_NETWORK_LOCK";
                break;
            case MtkRILConstants.RIL_REQUEST_SET_SIM_NETWORK_LOCK:
                msg = "RIL_REQUEST_SET_SIM_NETWORK_LOCK";
                break;
            /// M: [Network][C2K] Sprint roaming control @{
            case RIL_REQUEST_SET_ROAMING_ENABLE:
                msg = "SET_ROAMING_ENABLE";
                break;
            case RIL_REQUEST_GET_ROAMING_ENABLE:
                msg = "GET_ROAMING_ENABLE";
                break;
            /// @}
            // External SIM [Start]
            case MtkRILConstants.RIL_REQUEST_VSIM_NOTIFICATION:
                msg = "RIL_REQUEST_VSIM_NOTIFICATION";
                break;
            case MtkRILConstants.RIL_REQUEST_VSIM_OPERATION:
                msg = "RIL_REQUEST_VSIM_OPERATION";
                break;
            // External SIM [End]
            // WFC [Start]
            case MtkRILConstants.RIL_REQUEST_SET_WIFI_ENABLED:
                msg = "RIL_REQUEST_SET_WIFI_ENABLED";
                break;
            case MtkRILConstants.RIL_REQUEST_SET_WIFI_ASSOCIATED:
                msg = "RIL_REQUEST_SET_WIFI_ASSOCIATED";
                break;
            case MtkRILConstants.RIL_REQUEST_SET_WIFI_SIGNAL_LEVEL:
                msg = "RIL_REQUEST_SET_WIFI_SIGNAL_LEVEL";
                break;
            case MtkRILConstants.RIL_REQUEST_SET_WIFI_IP_ADDRESS:
                msg = "RIL_REQUEST_SET_WIFI_IP_ADDRESS";
                break;
            case MtkRILConstants.RIL_REQUEST_SET_GEO_LOCATION:
                msg = "RIL_REQUEST_SET_GEO_LOCATION";
                break;
            case MtkRILConstants.RIL_REQUEST_SET_EMERGENCY_ADDRESS_ID:
                msg = "RIL_REQUEST_SET_EMERGENCY_ADDRESS_ID";
                break;
            // WFC [End]
            case MtkRILConstants.RIL_REQUEST_SET_E911_STATE:
                msg = "RIL_REQUEST_SET_E911_STATE";
                break;
            // Network [Start]
            case MtkRILConstants.RIL_REQUEST_SET_SERVICE_STATE:
                msg = "RIL_REQUEST_SET_SERVICE_STATE";
                break;
            // Network [End]
            // SS [Start]
            case MtkRILConstants.RIL_REQUEST_SET_CLIP:
                msg = "RIL_REQUEST_SET_CLIP";
                break;
            case MtkRILConstants.RIL_REQUEST_SET_COLP:
                msg = "RIL_REQUEST_SET_COLP";
                break;
            case MtkRILConstants.RIL_REQUEST_GET_COLP:
                msg = "RIL_REQUEST_GET_COLP";
                break;
            case MtkRILConstants.RIL_REQUEST_SET_COLR:
                msg = "RIL_REQUEST_SET_COLR";
                break;
            case MtkRILConstants.RIL_REQUEST_GET_COLR:
                msg = "RIL_REQUEST_GET_COLR";
                break;
            case MtkRILConstants.RIL_REQUEST_SEND_CNAP:
                msg = "RIL_REQUEST_SEND_CNAP";
                break;
            case MtkRILConstants.RIL_REQUEST_QUERY_CALL_FORWARD_IN_TIME_SLOT:
                msg = "RIL_REQUEST_QUERY_CALL_FORWARD_IN_TIME_SLOT";
                break;
            case MtkRILConstants.RIL_REQUEST_SET_CALL_FORWARD_IN_TIME_SLOT:
                msg = "RIL_REQUEST_SET_CALL_FORWARD_IN_TIME_SLOT";
                break;
            // SS [End]
            default:
                msg = "<unknown request>";
                break;
        }
    }

    static String responseToString(int request) {
        switch(request) {
            case RIL_UNSOL_RESPONSE_RADIO_STATE_CHANGED:
                return "UNSOL_RESPONSE_RADIO_STATE_CHANGED";
            case RIL_UNSOL_RESPONSE_CALL_STATE_CHANGED:
                return "UNSOL_RESPONSE_CALL_STATE_CHANGED";
            case RIL_UNSOL_RESPONSE_NETWORK_STATE_CHANGED:
                return "UNSOL_RESPONSE_NETWORK_STATE_CHANGED";
            case RIL_UNSOL_RESPONSE_NEW_SMS:
                return "UNSOL_RESPONSE_NEW_SMS";
            case RIL_UNSOL_RESPONSE_NEW_SMS_STATUS_REPORT:
                return "UNSOL_RESPONSE_NEW_SMS_STATUS_REPORT";
            case RIL_UNSOL_RESPONSE_NEW_SMS_ON_SIM:
                return "UNSOL_RESPONSE_NEW_SMS_ON_SIM";
            case RIL_UNSOL_ON_USSD:
                return "UNSOL_ON_USSD";
            case RIL_UNSOL_ON_USSD_REQUEST:
                return "UNSOL_ON_USSD_REQUEST";
            case RIL_UNSOL_NITZ_TIME_RECEIVED:
                return "UNSOL_NITZ_TIME_RECEIVED";
            case RIL_UNSOL_SIGNAL_STRENGTH:
                return "UNSOL_SIGNAL_STRENGTH";
            case RIL_UNSOL_DATA_CALL_LIST_CHANGED:
                return "UNSOL_DATA_CALL_LIST_CHANGED";
            case RIL_UNSOL_SUPP_SVC_NOTIFICATION:
                return "UNSOL_SUPP_SVC_NOTIFICATION";
            case RIL_UNSOL_STK_SESSION_END:
                return "UNSOL_STK_SESSION_END";
            case RIL_UNSOL_STK_PROACTIVE_COMMAND:
                return "UNSOL_STK_PROACTIVE_COMMAND";
            case RIL_UNSOL_STK_EVENT_NOTIFY:
                return "UNSOL_STK_EVENT_NOTIFY";
            case RIL_UNSOL_STK_CALL_SETUP:
                return "UNSOL_STK_CALL_SETUP";
            case RIL_UNSOL_SIM_SMS_STORAGE_FULL:
                return "UNSOL_SIM_SMS_STORAGE_FULL";
            case RIL_UNSOL_SIM_REFRESH:
                return "UNSOL_SIM_REFRESH";
            case RIL_UNSOL_CALL_RING:
                return "UNSOL_CALL_RING";
            case RIL_UNSOL_RESPONSE_SIM_STATUS_CHANGED:
                return "UNSOL_RESPONSE_SIM_STATUS_CHANGED";
            case RIL_UNSOL_RESPONSE_CDMA_NEW_SMS:
                return "UNSOL_RESPONSE_CDMA_NEW_SMS";
            case RIL_UNSOL_RESPONSE_NEW_BROADCAST_SMS:
                return "UNSOL_RESPONSE_NEW_BROADCAST_SMS";
            case RIL_UNSOL_CDMA_RUIM_SMS_STORAGE_FULL:
                return "UNSOL_CDMA_RUIM_SMS_STORAGE_FULL";
            case RIL_UNSOL_RESTRICTED_STATE_CHANGED:
                return "UNSOL_RESTRICTED_STATE_CHANGED";
            case RIL_UNSOL_ENTER_EMERGENCY_CALLBACK_MODE:
                return "UNSOL_ENTER_EMERGENCY_CALLBACK_MODE";
            case RIL_UNSOL_CDMA_CALL_WAITING:
                return "UNSOL_CDMA_CALL_WAITING";
            case RIL_UNSOL_CDMA_OTA_PROVISION_STATUS:
                return "UNSOL_CDMA_OTA_PROVISION_STATUS";
            case RIL_UNSOL_CDMA_INFO_REC:
                return "UNSOL_CDMA_INFO_REC";
            case RIL_UNSOL_OEM_HOOK_RAW:
                return "UNSOL_OEM_HOOK_RAW";
            case RIL_UNSOL_RINGBACK_TONE:
                return "UNSOL_RINGBACK_TONE";
            case RIL_UNSOL_RESEND_INCALL_MUTE:
                return "UNSOL_RESEND_INCALL_MUTE";
            case RIL_UNSOL_CDMA_SUBSCRIPTION_SOURCE_CHANGED:
                return "CDMA_SUBSCRIPTION_SOURCE_CHANGED";
            case RIL_UNSOl_CDMA_PRL_CHANGED:
                return "UNSOL_CDMA_PRL_CHANGED";
            case RIL_UNSOL_EXIT_EMERGENCY_CALLBACK_MODE:
                return "UNSOL_EXIT_EMERGENCY_CALLBACK_MODE";
            case RIL_UNSOL_RIL_CONNECTED:
                return "UNSOL_RIL_CONNECTED";
            case RIL_UNSOL_VOICE_RADIO_TECH_CHANGED:
                return "UNSOL_VOICE_RADIO_TECH_CHANGED";
            case RIL_UNSOL_CELL_INFO_LIST:
                return "UNSOL_CELL_INFO_LIST";
            case RIL_UNSOL_RESPONSE_IMS_NETWORK_STATE_CHANGED:
                return "UNSOL_RESPONSE_IMS_NETWORK_STATE_CHANGED";
            case RIL_UNSOL_UICC_SUBSCRIPTION_STATUS_CHANGED:
                return "RIL_UNSOL_UICC_SUBSCRIPTION_STATUS_CHANGED";
            case RIL_UNSOL_SRVCC_STATE_NOTIFY:
                return "UNSOL_SRVCC_STATE_NOTIFY";
            case RIL_UNSOL_HARDWARE_CONFIG_CHANGED:
                return "RIL_UNSOL_HARDWARE_CONFIG_CHANGED";
            case RIL_UNSOL_RADIO_CAPABILITY:
                return "RIL_UNSOL_RADIO_CAPABILITY";
            case RIL_UNSOL_ON_SS:
                return "UNSOL_ON_SS";
            case RIL_UNSOL_STK_CC_ALPHA_NOTIFY:
                return "UNSOL_STK_CC_ALPHA_NOTIFY";
            case RIL_UNSOL_LCEDATA_RECV:
                return "UNSOL_LCE_INFO_RECV";
            case RIL_UNSOL_PCO_DATA:
                return "UNSOL_PCO_DATA";
            case RIL_UNSOL_MODEM_RESTART:
                return "UNSOL_MODEM_RESTART";
            case RIL_UNSOL_CARRIER_INFO_IMSI_ENCRYPTION:
                return "RIL_UNSOL_CARRIER_INFO_IMSI_ENCRYPTION";
            case RIL_UNSOL_NETWORK_SCAN_RESULT:
                return "RIL_UNSOL_NETWORK_SCAN_RESULT";
            default:
                return "<unknown response>";
        }
    }

    public static String responseToStringEx(Integer request) {
        String msg;
        switch(request) {
            case RIL_UNSOL_DATA_ALLOWED:
                msg = "RIL_UNSOL_DATA_ALLOWED";
                break;
            /// M: CC: Proprietary incoming call handling
            case RIL_UNSOL_INCOMING_CALL_INDICATION:
                msg = "UNSOL_INCOMING_CALL_INDICATION";
                break;
            /// M: CC: GSM 02.07 B.1.26 Ciphering Indicator support
            case RIL_UNSOL_CIPHER_INDICATION:
                msg = "UNSOL_CIPHER_INDICATION";
                break;
            /// M: CC: Proprietary CRSS handling
            case RIL_UNSOL_CRSS_NOTIFICATION:
                msg = "UNSOL_CRSS_NOTIFICATION";
                break;
            /// M: CC: For 3G VT only @{
            case RIL_UNSOL_VT_STATUS_INFO:
                msg = "UNSOL_VT_STATUS_INFO";
                break;
            /// @}
            /// M: CC: GSA HD Voice for 2/3G network support
            case RIL_UNSOL_SPEECH_CODEC_INFO:
                msg = "UNSOL_SPEECH_CODEC_INFO";
                break;
            /// M: CC: CDMA call accepted. @{
            case RIL_UNSOL_CDMA_CALL_ACCEPTED:
                msg = "UNSOL_CDMA_CALL_ACCEPTED";
                break;
            /// @}
            case RIL_UNSOL_INVALID_SIM:
                msg = "RIL_UNSOL_INVALID_SIM";
                break;
            case RIL_UNSOL_NETWORK_EVENT:
                msg = "RIL_UNSOL_NETWORK_EVENT";
                break;
            case RIL_UNSOL_MODULATION_INFO:
                msg = "RIL_UNSOL_MODULATION_INFO";
                break;
            case RIL_UNSOL_PSEUDO_CELL_INFO:
                msg = "RIL_UNSOL_PSEUDO_CELL_INFO";
                break;
            /// M: eMBMS feature
            case RIL_UNSOL_EMBMS_SESSION_STATUS:
                msg = "RIL_UNSOL_EMBMS_SESSION_STATUS";
                break;
            case RIL_UNSOL_EMBMS_AT_INFO:
                msg = "RIL_UNSOL_EMBMS_AT_INFO";
                break;
            /// M: eMBMS end
            case RIL_UNSOL_WORLD_MODE_CHANGED:
                msg = "RIL_UNSOL_WORLD_MODE_CHANGED";
                break;
            case RIL_UNSOL_GMSS_RAT_CHANGED:
                msg = "RIL_UNSOL_GMSS_RAT_CHANGED";
                break;
            case RIL_UNSOL_RESPONSE_REGISTRATION_SUSPENDED:
                msg = "RIL_UNSOL_RESPONSE_REGISTRATION_SUSPENDED";
                break;
            case RIL_UNSOL_RESPONSE_PLMN_CHANGED:
                msg = "RIL_UNSOL_RESPONSE_PLMN_CHANGED";
                break;
            case RIL_UNSOL_CDMA_CARD_INITIAL_ESN_OR_MEID:
                msg = "RIL_UNSOL_CDMA_CARD_INITIAL_ESN_OR_MEID";
                break;
            case RIL_UNSOL_RESET_ATTACH_APN:
                msg = "RIL_UNSOL_RESET_ATTACH_APN";
                break;
            case RIL_UNSOL_DATA_ATTACH_APN_CHANGED:
                msg = "RIL_UNSOL_DATA_ATTACH_APN_CHANGED";
                break;
            case RIL_UNSOL_FEMTOCELL_INFO:
                msg = "UNSOL_FEMTOCELL_INFO";
                break;
            // M: Data Framework - Data Retry enhancement
            case RIL_UNSOL_MD_DATA_RETRY_COUNT_RESET:
                msg = "RIL_UNSOL_MD_DATA_RETRY_COUNT_RESET";
                break;
            // M: Data Framework - CC 33
            case RIL_UNSOL_REMOVE_RESTRICT_EUTRAN:
                msg = "RIL_UNSOL_REMOVE_RESTRICT_EUTRAN";
                break;
            // PHB START
            case RIL_UNSOL_PHB_READY_NOTIFICATION:
                msg = "UNSOL_PHB_READY_NOTIFICATION";
                break;
            // PHB END
            case RIL_UNSOL_NETWORK_INFO:
                msg = "UNSOL_NETWORK_INFO";
                break;
            case RIL_UNSOL_CALL_FORWARDING:
                msg = "UNSOL_CALL_FORWARDING";
                break;
            // IMS conference SRVCC
            case RIL_UNSOL_ECONF_SRVCC_INDICATION:
                msg = "RIL_UNSOL_ECONF_SRVCC_INDICATION";
                break;
            // M: [LTE][Low Power][UL traffic shaping] @{
            case RIL_UNSOL_LTE_ACCESS_STRATUM_STATE_CHANGE:
                msg = "RIL_UNSOL_LTE_ACCESS_STRATUM_STATE_CHANGE";
                break;
            // M: [LTE][Low Power][UL traffic shaping] @}
            // External SIM [Start]
            case RIL_UNSOL_VSIM_OPERATION_INDICATION:
                msg = "RIL_UNSOL_VSIM_OPERATION_INDICATION";
                break;
            // External SIM [End]
            /// Ims Data Framework @{
            case RIL_UNSOL_DEDICATE_BEARER_ACTIVATED:
                return "RIL_UNSOL_DEDICATE_BEARER_ACTIVATED";
            case RIL_UNSOL_DEDICATE_BEARER_MODIFIED:
                return "RIL_UNSOL_DEDICATE_BEARER_MODIFIED";
            case RIL_UNSOL_DEDICATE_BEARER_DEACTIVATED:
                return "RIL_UNSOL_DEDICATE_BEARER_DEACTIVATED";
            /// @}
            case RIL_UNSOL_MOBILE_WIFI_ROVEOUT:
                msg = "RIL_UNSOL_MOBILE_WIFI_ROVEOUT";
                break;
            case RIL_UNSOL_MOBILE_WIFI_HANDOVER:
                msg = "RIL_UNSOL_MOBILE_WIFI_HANDOVER";
                break;
            case RIL_UNSOL_ACTIVE_WIFI_PDN_COUNT:
                msg = "RIL_UNSOL_ACTIVE_WIFI_PDN_COUNT";
                break;
            case RIL_UNSOL_WIFI_RSSI_MONITORING_CONFIG:
                msg = "RIL_UNSOL_WIFI_RSSI_MONITORING_CONFIG";
                break;
            case RIL_UNSOL_WIFI_PDN_ERROR:
                msg = "RIL_UNSOL_WIFI_PDN_ERROR";
                break;
            case RIL_UNSOL_REQUEST_GEO_LOCATION:
                msg = "RIL_UNSOL_REQUEST_GEO_LOCATION";
                break;
            case RIL_UNSOL_WFC_PDN_STATE:
                msg = "RIL_UNSOL_WFC_PDN_STATE";
                break;
            case RIL_UNSOL_NATT_KEEP_ALIVE_CHANGED:
                msg = "RIL_UNSOL_NATT_KEEP_ALIVE_CHANGED";
                break;
            case RIL_UNSOL_PCO_DATA_AFTER_ATTACHED:
                msg = "RIL_UNSOL_PCO_DATA_AFTER_ATTACHED";
                break;
            default:
                msg = "<unknown response>";
                break;
        }
    }

    void riljLog(String msg) {
        Rlog.d(RILJ_LOG_TAG, msg
                + (mPhoneId != null ? (" [SUB" + mPhoneId + "]") : ""));
    }

    void riljLoge(String msg) {
        Rlog.e(RILJ_LOG_TAG, msg
                + (mPhoneId != null ? (" [SUB" + mPhoneId + "]") : ""));
    }

    void riljLoge(String msg, Exception e) {
        Rlog.e(RILJ_LOG_TAG, msg
                + (mPhoneId != null ? (" [SUB" + mPhoneId + "]") : ""), e);
    }

    void riljLogv(String msg) {
        Rlog.v(RILJ_LOG_TAG, msg
                + (mPhoneId != null ? (" [SUB" + mPhoneId + "]") : ""));
    }

    void unsljLog(int response) {
        riljLog("[UNSL]< " + responseToString(response));
    }

    void unsljLogMore(int response, String more) {
        riljLog("[UNSL]< " + responseToString(response) + " " + more);
    }

    void unsljLogRet(int response, Object ret) {
        riljLog("[UNSL]< " + responseToString(response) + " " + retToString(response, ret));
    }

    void unsljLogvRet(int response, Object ret) {
        riljLogv("[UNSL]< " + responseToString(response) + " " + retToString(response, ret));
    }

    @Override
    public void setPhoneType(int phoneType) { // Called by GsmCdmaPhone
        if (RILJ_LOGD) riljLog("setPhoneType=" + phoneType + " old value=" + mPhoneType);
        mPhoneType = phoneType;
    }

    /* (non-Javadoc)
     * @see com.android.internal.telephony.BaseCommands#testingEmergencyCall()
     */
    @Override
    public void testingEmergencyCall() {
        if (RILJ_LOGD) riljLog("testingEmergencyCall");
        mTestingEmergencyCall.set(true);
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("RIL: " + this);
        pw.println(" mWakeLock=" + mWakeLock);
        pw.println(" mWakeLockTimeout=" + mWakeLockTimeout);
        synchronized (mRequestList) {
            synchronized (mWakeLock) {
                pw.println(" mWakeLockCount=" + mWakeLockCount);
            }
            int count = mRequestList.size();
            pw.println(" mRequestList count=" + count);
            for (int i = 0; i < count; i++) {
                RILRequest rr = mRequestList.valueAt(i);
                pw.println("  [" + rr.mSerial + "] " + requestToString(rr.mRequest));
            }
        }
        pw.println(" mLastNITZTimeInfo=" + Arrays.toString(mLastNITZTimeInfo));
        pw.println(" mTestingEmergencyCall=" + mTestingEmergencyCall.get());
        mClientWakelockTracker.dumpClientRequestTracker(pw);
    }

    public List<ClientRequestStats> getClientRequestStats() {
        return mClientWakelockTracker.getClientRequestStats();
    }

    public static ArrayList<Byte> primitiveArrayToArrayList(byte[] arr) {
        ArrayList<Byte> arrayList = new ArrayList<>(arr.length);
        for (byte b : arr) {
            arrayList.add(b);
        }
        return arrayList;
    }

    public static byte[] arrayListToPrimitiveArray(ArrayList<Byte> bytes) {
        byte[] ret = new byte[bytes.size()];
        for (int i = 0; i < ret.length; i++) {
            ret[i] = bytes.get(i);
        }
        return ret;
    }

    static ArrayList<HardwareConfig> convertHalHwConfigList(
            ArrayList<android.hardware.radio.V1_0.HardwareConfig> hwListRil,
            RIL ril) {
        int num;
        ArrayList<HardwareConfig> response;
        HardwareConfig hw;

        num = hwListRil.size();
        response = new ArrayList<HardwareConfig>(num);

        if (RILJ_LOGV) {
            ril.riljLog("convertHalHwConfigList: num=" + num);
        }
        for (android.hardware.radio.V1_0.HardwareConfig hwRil : hwListRil) {
            int type = hwRil.type;
            switch(type) {
                case HardwareConfig.DEV_HARDWARE_TYPE_MODEM: {
                    hw = new HardwareConfig(type);
                    HardwareConfigModem hwModem = hwRil.modem.get(0);
                    hw.assignModem(hwRil.uuid, hwRil.state, hwModem.rilModel, hwModem.rat,
                            hwModem.maxVoice, hwModem.maxData, hwModem.maxStandby);
                    break;
                }
                case HardwareConfig.DEV_HARDWARE_TYPE_SIM: {
                    hw = new HardwareConfig(type);
                    hw.assignSim(hwRil.uuid, hwRil.state, hwRil.sim.get(0).modemUuid);
                    break;
                }
                default: {
                    throw new RuntimeException(
                            "RIL_REQUEST_GET_HARDWARE_CONFIG invalid hardward type:" + type);
                }
            }

            response.add(hw);
        }

        return response;
    }

    static RadioCapability convertHalRadioCapability(
            android.hardware.radio.V1_0.RadioCapability rcRil, RIL ril) {
        int session = rcRil.session;
        int phase = rcRil.phase;
        int rat = rcRil.raf;
        String logicModemUuid = rcRil.logicalModemUuid;
        int status = rcRil.status;

        ril.riljLog("convertHalRadioCapability: session=" + session +
                ", phase=" + phase +
                ", rat=" + rat +
                ", logicModemUuid=" + logicModemUuid +
                ", status=" + status);
        RadioCapability rc = new RadioCapability(
                ril.mPhoneId, session, phase, rat, logicModemUuid, status);
        return rc;
    }

    static ArrayList<Integer> convertHalLceData(LceDataInfo lce, RIL ril) {
        final ArrayList<Integer> capacityResponse = new ArrayList<Integer>();
        final int capacityDownKbps = lce.lastHopCapacityKbps;
        final int confidenceLevel = Byte.toUnsignedInt(lce.confidenceLevel);
        final int lceSuspended = lce.lceSuspended ? 1 : 0;

        ril.riljLog("LCE capacity information received:" +
                " capacity=" + capacityDownKbps +
                " confidence=" + confidenceLevel +
                " lceSuspended=" + lceSuspended);

        capacityResponse.add(capacityDownKbps);
        capacityResponse.add(confidenceLevel);
        capacityResponse.add(lceSuspended);
        return capacityResponse;
    }

    static ArrayList<CellInfo> convertHalCellInfoList(
            ArrayList<android.hardware.radio.V1_0.CellInfo> records) {
        ArrayList<CellInfo> response = new ArrayList<CellInfo>(records.size());

        for (android.hardware.radio.V1_0.CellInfo record : records) {
            // first convert RIL CellInfo to Parcel
            Parcel p = Parcel.obtain();
            p.writeInt(record.cellInfoType);
            p.writeInt(record.registered ? 1 : 0);
            p.writeInt(record.timeStampType);
            p.writeLong(record.timeStamp);
            switch (record.cellInfoType) {
                case CellInfoType.GSM: {
                    CellInfoGsm cellInfoGsm = record.gsm.get(0);
                    p.writeInt(Integer.parseInt(cellInfoGsm.cellIdentityGsm.mcc));
                    p.writeInt(Integer.parseInt(cellInfoGsm.cellIdentityGsm.mnc));
                    p.writeInt(cellInfoGsm.cellIdentityGsm.lac);
                    p.writeInt(cellInfoGsm.cellIdentityGsm.cid);
                    p.writeInt(cellInfoGsm.cellIdentityGsm.arfcn);
                    p.writeInt(Byte.toUnsignedInt(cellInfoGsm.cellIdentityGsm.bsic));
                    p.writeInt(cellInfoGsm.signalStrengthGsm.signalStrength);
                    p.writeInt(cellInfoGsm.signalStrengthGsm.bitErrorRate);
                    p.writeInt(cellInfoGsm.signalStrengthGsm.timingAdvance);
                    break;
                }

                case CellInfoType.CDMA: {
                    CellInfoCdma cellInfoCdma = record.cdma.get(0);
                    p.writeInt(cellInfoCdma.cellIdentityCdma.networkId);
                    p.writeInt(cellInfoCdma.cellIdentityCdma.systemId);
                    p.writeInt(cellInfoCdma.cellIdentityCdma.baseStationId);
                    p.writeInt(cellInfoCdma.cellIdentityCdma.longitude);
                    p.writeInt(cellInfoCdma.cellIdentityCdma.latitude);
                    p.writeInt(cellInfoCdma.signalStrengthCdma.dbm);
                    p.writeInt(cellInfoCdma.signalStrengthCdma.ecio);
                    p.writeInt(cellInfoCdma.signalStrengthEvdo.dbm);
                    p.writeInt(cellInfoCdma.signalStrengthEvdo.ecio);
                    p.writeInt(cellInfoCdma.signalStrengthEvdo.signalNoiseRatio);
                    break;
                }

                case CellInfoType.LTE: {
                    CellInfoLte cellInfoLte = record.lte.get(0);
                    p.writeInt(Integer.parseInt(cellInfoLte.cellIdentityLte.mcc));
                    p.writeInt(Integer.parseInt(cellInfoLte.cellIdentityLte.mnc));
                    p.writeInt(cellInfoLte.cellIdentityLte.ci);
                    p.writeInt(cellInfoLte.cellIdentityLte.pci);
                    p.writeInt(cellInfoLte.cellIdentityLte.tac);
                    p.writeInt(cellInfoLte.cellIdentityLte.earfcn);
                    p.writeInt(cellInfoLte.signalStrengthLte.signalStrength);
                    p.writeInt(cellInfoLte.signalStrengthLte.rsrp);
                    p.writeInt(cellInfoLte.signalStrengthLte.rsrq);
                    p.writeInt(cellInfoLte.signalStrengthLte.rssnr);
                    p.writeInt(cellInfoLte.signalStrengthLte.cqi);
                    p.writeInt(cellInfoLte.signalStrengthLte.timingAdvance);
                    break;
                }

                case CellInfoType.WCDMA: {
                    CellInfoWcdma cellInfoWcdma = record.wcdma.get(0);
                    p.writeInt(Integer.parseInt(cellInfoWcdma.cellIdentityWcdma.mcc));
                    p.writeInt(Integer.parseInt(cellInfoWcdma.cellIdentityWcdma.mnc));
                    p.writeInt(cellInfoWcdma.cellIdentityWcdma.lac);
                    p.writeInt(cellInfoWcdma.cellIdentityWcdma.cid);
                    p.writeInt(cellInfoWcdma.cellIdentityWcdma.psc);
                    p.writeInt(cellInfoWcdma.cellIdentityWcdma.uarfcn);
                    p.writeInt(cellInfoWcdma.signalStrengthWcdma.signalStrength);
                    p.writeInt(cellInfoWcdma.signalStrengthWcdma.bitErrorRate);
                    break;
                }

                default:
                    throw new RuntimeException("unexpected cellinfotype: " + record.cellInfoType);
            }

            p.setDataPosition(0);
            CellInfo InfoRec = CellInfo.CREATOR.createFromParcel(p);
            p.recycle();
            response.add(InfoRec);
        }

        return response;
    }

    static SignalStrength convertHalSignalStrength(
            android.hardware.radio.V1_0.SignalStrength signalStrength) {
        return new SignalStrength(signalStrength.gw.signalStrength,
                signalStrength.gw.bitErrorRate,
                signalStrength.cdma.dbm,
                signalStrength.cdma.ecio,
                signalStrength.evdo.dbm,
                signalStrength.evdo.ecio,
                signalStrength.evdo.signalNoiseRatio,
                signalStrength.lte.signalStrength,
                signalStrength.lte.rsrp,
                signalStrength.lte.rsrq,
                signalStrength.lte.rssnr,
                signalStrength.lte.cqi,
                signalStrength.tdScdma.rscp,
                false /* gsmFlag - don't care; will be changed by SST */);
    }
}