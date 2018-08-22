/* Copyright Statement:
 *
 * This software/firmware and related documentation ("MediaTek Software") are
 * protected under relevant copyright laws. The information contained herein is
 * confidential and proprietary to MediaTek Inc. and/or its licensors. Without
 * the prior written permission of MediaTek inc. and/or its licensors, any
 * reproduction, modification, use or disclosure of MediaTek Software, and
 * information contained herein, in whole or in part, shall be strictly
 * prohibited.
 *
 * MediaTek Inc. (C) 2014. All rights reserved.
 *
 * BY OPENING THIS FILE, RECEIVER HEREBY UNEQUIVOCALLY ACKNOWLEDGES AND AGREES
 * THAT THE SOFTWARE/FIRMWARE AND ITS DOCUMENTATIONS ("MEDIATEK SOFTWARE")
 * RECEIVED FROM MEDIATEK AND/OR ITS REPRESENTATIVES ARE PROVIDED TO RECEIVER
 * ON AN "AS-IS" BASIS ONLY. MEDIATEK EXPRESSLY DISCLAIMS ANY AND ALL
 * WARRANTIES, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE OR
 * NONINFRINGEMENT. NEITHER DOES MEDIATEK PROVIDE ANY WARRANTY WHATSOEVER WITH
 * RESPECT TO THE SOFTWARE OF ANY THIRD PARTY WHICH MAY BE USED BY,
 * INCORPORATED IN, OR SUPPLIED WITH THE MEDIATEK SOFTWARE, AND RECEIVER AGREES
 * TO LOOK ONLY TO SUCH THIRD PARTY FOR ANY WARRANTY CLAIM RELATING THERETO.
 * RECEIVER EXPRESSLY ACKNOWLEDGES THAT IT IS RECEIVER'S SOLE RESPONSIBILITY TO
 * OBTAIN FROM ANY THIRD PARTY ALL PROPER LICENSES CONTAINED IN MEDIATEK
 * SOFTWARE. MEDIATEK SHALL ALSO NOT BE RESPONSIBLE FOR ANY MEDIATEK SOFTWARE
 * RELEASES MADE TO RECEIVER'S SPECIFICATION OR TO CONFORM TO A PARTICULAR
 * STANDARD OR OPEN FORUM. RECEIVER'S SOLE AND EXCLUSIVE REMEDY AND MEDIATEK'S
 * ENTIRE AND CUMULATIVE LIABILITY WITH RESPECT TO THE MEDIATEK SOFTWARE
 * RELEASED HEREUNDER WILL BE, AT MEDIATEK'S OPTION, TO REVISE OR REPLACE THE
 * MEDIATEK SOFTWARE AT ISSUE, OR REFUND ANY SOFTWARE LICENSE FEES OR SERVICE
 * CHARGE PAID BY RECEIVER TO MEDIATEK FOR SUCH MEDIATEK SOFTWARE AT ISSUE.
 *
 * The following software/firmware and/or related documentation ("MediaTek
 * Software") have been modified by MediaTek Inc. All revisions are subject to
 * any receiver's applicable license agreements with MediaTek Inc.
 */

package com.mediatek.internal.telephony;

import android.content.Context;
import android.content.Intent;
import android.os.SystemProperties;
import android.telephony.Rlog;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;

import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.IccCardConstants;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneBase;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.PhoneFactory;
import com.mediatek.internal.telephony.ltedc.svlte.SvlteModeController;
import com.mediatek.internal.telephony.ltedc.svlte.SvltePhoneProxy;
import com.mediatek.internal.telephony.ltedc.svlte.SvlteRatController;
import com.mediatek.internal.telephony.ltedc.svlte.SvlteUtils;
import com.mediatek.internal.telephony.uicc.SvlteUiccUtils;

/**
 * Provide SVLTE RadioManager delegate.
 */
public class SvlteRadioManager extends RadioManager {

    private static final String LOG_TAG = "SvlteRadioManager";

    private static final String  PROPERTY_ICCID_SIM_C2K = "ril.iccid.sim1_c2k";
    private static final String  PROPERTY_RIL_CARD_TYPE_SET = "gsm.ril.cardtypeset";
    private static final String  PROPERTY_RIL_CARD_TYPE_SET_2 = "gsm.ril.cardtypeset.2";
    private static final String  PROPERTY_CONFIG_EMDSTATUS_SEND = "ril.cdma.emdstatus.send";
    private static final String CDMA_PROPERTY_SILENT_REBOOT_MD = "cdma.ril.eboot";

    protected SvlteRadioManager(Context context , int phoneCount, CommandsInterface[] ci) {
        super(context, phoneCount, ci);
    }

    /**
     *  if boot up under airplane mode, power-off modem.
     *  @param phoneId for ID of the current phone
     **/
    @Override
    public void notifyRadioAvailable(int phoneId) {
        log("Phone " + phoneId + " notifies radio available");
        if (mAirplaneMode == AIRPLANE_MODE_ON
                && isFlightModePowerOffModemEnabled()
                && !isUnderCryptKeeper()) {
            log("Power off modem because boot up under airplane mode");
            setModemPower(MODEM_POWER_OFF, MODE_PHONE1_ONLY << SvlteUtils.getSlotId(phoneId));
        }
    }

    // Modem power on/off related @{
    /**
     * Set modem power on/off according to DSDS or DSDA.
     *
     * @param power desired power of modem
     * @param phoneBitMap a bit map of phones you want to set
     *              1: phone 1 only
     *              2: phone 2 only
     *              3: phone 1 and phone 2
     */
    @Override
    public void setModemPower(boolean power, int phoneBitMap) {
        log("Set Modem Power according to bitmap, Power:" + power
                + ", PhoneBitMap:" + phoneBitMap);
        TelephonyManager.MultiSimVariants config =
                TelephonyManager.getDefault().getMultiSimConfiguration();
        int phoneId = 0;
        switch(config) {
            case DSDS:
                log("Set Modem Power under DSDS mode, Power:" + power + ", phoneId:"
                        + phoneBitMap);
            case DSDA:
                for (int i = 0; i < mPhoneCount; i++) {
                    phoneId = i;
                    if ((phoneBitMap & (MODE_PHONE1_ONLY << i)) != 0) {
                        log("Set Modem Power under DSDA mode, Power:" + power + ", phoneId:"
                                + phoneId);
                        if (phoneId == SvlteModeController.getCdmaSocketSlotId()) {
                            setSvlteModemsPower(power, phoneId);
                        } else {
                            setGsmModemPower(power, phoneId);
                        }
                    }
                    if (power == MODEM_POWER_OFF) {
                        resetSimInsertedStatus(phoneId);
                    }
                }
                break;

            case TSTS:
                log("SVLTE don't TSTS mode! Power:" + power + ", phoneId:" + phoneId);
                break;

            default:
                log("Set Modem Power under SS mode:" + power + ", phoneId:" + phoneId);
                setSvlteModemsPower(power, phoneId);
                break;
        }
    }

    /**
     *  Check if modem is already power off.
     *  SVLTE project:
     *  getCdmaSocketSlotId = 0
     *      MD3 => ril.ipo.radiooff
     *      MD1 => ril.ipo.radiooff.2
     *  getCdmaSocketSlotId = 1
     *      MD1 => ril.ipo.radiooff
     *      MD3 => ril.ipo.radiooff.2
     **/
    @Override
    protected boolean isModemOff(int phoneId) {
        boolean powerOff = false;
        int cdmaSlot = SvlteModeController.getCdmaSocketSlotId();
        log("isRadioOff: cdmaSlot=" + cdmaSlot + " ,phoneId=" + phoneId);
        Phone phone = getPhoneByPhoneProxy(phoneId);
        if (null != phone && PhoneConstants.PHONE_TYPE_CDMA == phone.getPhoneType()) {
            log("isRadioOff: C2K phone");
            if (cdmaSlot == 0) {
                phoneId = 0;
            } else {
                phoneId = 1;
            }
        } else {
            log("isRadioOff: GSM phone");
            if (cdmaSlot == 0) {
                phoneId = 1;
            } else {
                phoneId = 0;
            }
        }
        powerOff = SystemProperties.get(PROPERTY_RADIO_OFF[phoneId]).equals("1");
        log("isRadioOff: phoneId=" + phoneId + ", powerOff=" + powerOff);
        return powerOff;
    }

    private void setSvlteModemsPower(boolean power, int phoneId) {
        log("SVLTE LTE MD, power: " + power + ", phoneId: " + phoneId);
        PhoneBase phone = getPhoneByPhoneProxy(phoneId, true);
        setModemPowerByPhone(phone, power);
        log("SVLTE C2K MD, power: " + power + ", phoneId: " + phoneId);
        phone = getPhoneByPhoneProxy(phoneId, false);
        setModemPowerByPhone(phone, power);
    }

    private void setGsmModemPower(boolean power, int phoneId) {
        log("GSM MD, power: " + power + ", phoneId: " + phoneId);
        PhoneBase phone = getPhoneByPhoneProxy(phoneId, true);
        setModemPowerByPhone(phone, power);
    }

    private PhoneBase getPhoneByPhoneProxy(int phoneId, boolean isLtePhoneNeeded) {
        SvltePhoneProxy phoneProxy = (SvltePhoneProxy) PhoneFactory.getPhone(phoneId);
        if (null != phoneProxy) {
            if (isLtePhoneNeeded) {
                return phoneProxy.getLtePhone();
            } else {
                return phoneProxy.getNLtePhone();
            }
        } else {
            log("getPhoneProxy: phoneProxy is null");
            return null;
        }
    }

    private Phone getPhoneByPhoneProxy(int phoneId) {
        SvltePhoneProxy phoneProxy = SvlteUtils.getSvltePhoneProxy(phoneId);
        if (null != phoneProxy) {
            return phoneProxy.getPhoneById(phoneId);
        } else {
            log("getPhoneProxy: phoneProxy is null");
            return null;
        }
    }

    private void setModemPowerByPhone(PhoneBase phone, boolean power) {
        if (null != phone) {
            phone.mCi.setModemPower(power, null);
        } else {
            log("setModemPowerByPhone: phone is null");
        }
    }
    // Modem power on/off related @}

    // Radio power on/off related @{
    /**
     * Set MTK radio on/off according to DSDS or DSDA.
     *
     * @param power desired radio of modem
     * @param phoneId a bit map of phones you want to set
     *              1: phone 1 only
     *              2: phone 2 only
     *              3: phone 1 and phone 2
     */
    @Override
    public void setRadioPower(boolean power, int phoneId) {
        log("setRadioPower, power=" + power + "  phoneId=" + phoneId);
        if (!SystemProperties.get(PROPERTY_CONFIG_EMDSTATUS_SEND).equals("1")) {
            log("emdstatus is not sent, wait for " + INITIAL_RETRY_INTERVAL_MSEC + "ms");
            RadioPowerRunnable setRadioPowerRunnable = new RadioPowerRunnable(power, phoneId);
            postDelayed(setRadioPowerRunnable, INITIAL_RETRY_INTERVAL_MSEC);
            return;
        }

        if (isFlightModePowerOffModemEnabled() && mAirplaneMode == AIRPLANE_MODE_ON) {
            log("Set Radio Power under airplane mode, ignore");
            return;
        }

        if (isModemPowerOff(phoneId)) {
            log("modem for phone " + phoneId + " off, do not set radio again");
            return;
        }

        /**
        * We want iccid ready berfore we check if SIM is once manually turned-offedd
        * So we check ICCID repeatedly every 300 ms
        */
        if (!isIccIdReady(phoneId)) {
            log("RILD initialize not completed, wait for " + INITIAL_RETRY_INTERVAL_MSEC + "ms");
            RadioPowerRunnable setRadioPowerRunnable = new RadioPowerRunnable(power, phoneId);
            postDelayed(setRadioPowerRunnable, INITIAL_RETRY_INTERVAL_MSEC);
            return;
        }

        setSimInsertedStatus(phoneId);

        boolean radioPower = power;
        String iccId = readIccIdUsingPhoneId(phoneId);
        //adjust radio power according to ICCID
        if (sIccidPreference.contains(iccId)) {
            log("Adjust radio to off because once manually turned off, iccid: "
                    + iccId + " , phone: " + phoneId);
            radioPower = RADIO_POWER_OFF;
        }

        boolean isCTACase = checkForCTACase();

        if (power && !isAllowRadioPowerOn(phoneId)) {
            log("not allow power on : +phoneId: " + phoneId);
            return;
        }

        if (getSimInsertedStatus(SvlteUtils.getSlotId(phoneId)) == NO_SIM_INSERTED) {
            if (isCTACase == true) {
                int capabilityPhoneId = findMainCapabilityPhoneId();
                log("No SIM inserted, force to turn on 3G/4G phone " +
                        capabilityPhoneId + " radio if no any sim radio is enabled!");
                setPhoneRadioPower(RADIO_POWER_ON, capabilityPhoneId);
            } else if (true == mIsEccCall) {
                log("ECC call Radio Power, power: " + radioPower + ", phoneId: " + phoneId);
                setPhoneRadioPower(radioPower, phoneId);
            } else {
                log("No SIM inserted, turn Radio off!");
                radioPower = RADIO_POWER_OFF;
                setPhoneRadioPower(radioPower, phoneId);
            }
        } else {
            log("Trigger set Radio Power, power: " + radioPower + ", phoneId: " + phoneId);
            // We must refresh sim setting during boot up or if we adjust power according to ICCID
            refreshSimSetting(radioPower, phoneId);
            setRadioPowerById(radioPower, phoneId);
        }
    }

    /**
     * Force turn on/off radio.
     * Remove ICCID for preference to prevent being turned off again
     * For ECC call
     * @param power desired radio of modem
     * @param phoneId a bit map of phones you want to set
     *              1: phone 1 only
     *              2: phone 2 only
     *              3: phone 1 and phone 2
     */
    @Override
    public void forceSetRadioPower(boolean power, int phoneId) {
        if (!SystemProperties.get(PROPERTY_CONFIG_EMDSTATUS_SEND).equals("1")) {
            ForceSetRadioPowerRunnable forceSetRadioPowerRunnable =
                new ForceSetRadioPowerRunnable(power, phoneId);
            postDelayed(forceSetRadioPowerRunnable,
                INITIAL_RETRY_INTERVAL_MSEC);
            return;
        }

        super.forceSetRadioPower(power, phoneId);

        if (SvlteUiccUtils.getInstance().isUsimWithCsim(phoneId) &&
                phoneId == SvlteModeController.getCdmaSocketSlotId()) {
            log("forceSetRadioPower: CT 4G card need turn LTE radio: " + power);
            ((SvltePhoneProxy) PhoneFactory.getPhone(phoneId)).getLtePhone().setRadioPower(power);
        }
    }

    @Override
    protected void setPhoneRadioPower(boolean power, int phoneId) {
        log("setPhoneRadioPower, power: " + power + ", phoneId: " + phoneId);
        if ((SvlteModeController.getCdmaSocketSlotId() == phoneId)
                && (SvlteRatController.getEngineerMode()
                        == SvlteRatController.ENGINEER_MODE_CDMA)) {
            setC2KRadioPower(power, phoneId);
        } else if (SvlteModeController.getActiveSvlteModeSlotId() == phoneId) {
            setSvlteRadioPower(power, phoneId);
        } else {
            setRadioPowerById(power, phoneId);
        }
    }

    private PhonePowerProxy createPhonePowerProxy(Phone phone) {
        if (null != phone) {
            switch (phone.getPhoneType()) {
                case PhoneConstants.PHONE_TYPE_CDMA:
                    log("CdmaPhonePowerProxy is created");
                    return new CdmaPhonePowerProxy();
                //TODO: To add GSM if needed
                default:
                    log("PhonePowerProxy is created");
                    return new PhonePowerProxy();
            }
        } else {
            //default case
            return new PhonePowerProxy();
        }
    }

    /**
     * Phone proxy for checking MD status before radio power.
     */
    private class PhonePowerProxy {
        void setRadioPower(Phone phone, boolean power) {
            if (null != phone) {
                phone.setRadioPower(power);
            }
        }
    }

    /**
     * Check CMDA MD status is set before radio power.
     */
    private class CdmaPhonePowerProxy extends PhonePowerProxy {
        void setRadioPower(Phone phone, boolean power) {
            if (!SystemProperties.get(PROPERTY_CONFIG_EMDSTATUS_SEND).equals("1")) {
                log("CdmaPhonePowerProxy: setRadioPower retry after "
                        + INITIAL_RETRY_INTERVAL_MSEC + "ms");
                PhonePowerProxyRunnable setRadioPowerRunnable =
                        new PhonePowerProxyRunnable(this, phone, power);
                postDelayed(setRadioPowerRunnable, INITIAL_RETRY_INTERVAL_MSEC);
            } else {
                super.setRadioPower(phone, power);
            }
        }
    }

    /**
     * Wait for MD status is set before radio power.
     */
    private class PhonePowerProxyRunnable implements Runnable {
        Phone mRetryPhone;
        boolean mRetryPower;
        PhonePowerProxy mPhonePowerProxy;
        public  PhonePowerProxyRunnable(PhonePowerProxy proxy, Phone phone, boolean power) {
            mPhonePowerProxy = proxy;
            mRetryPhone = phone;
            mRetryPower = power;
        }
        @Override
        public void run() {
            mPhonePowerProxy.setRadioPower(mRetryPhone, mRetryPower);
        }
    }

    private void setRadioPowerById(boolean power, int phoneId) {
        Phone phone = getPhoneByPhoneProxy(phoneId);
        if (phone == null) {
            log("setRadioPowerById: phone" + phoneId + " is null, skip");
            return;
        }
        phone.setRadioPower(power);
    }

    private void setSvlteRadioPower(boolean power, int phoneId) {
        log("SVLTE GSM MD, power: " + power + ", phoneId: " + phoneId);
        Phone phone = getPhoneByPhoneProxy(phoneId, true);
        setRadioPowerByPhone(phone, power);
        log("SVLTE C2K MD, power: " + power + ", phoneId: " + phoneId);
        phone = getPhoneByPhoneProxy(phoneId, false);
        setRadioPowerByPhone(phone, power);
    }

    private void setRadioPowerByPhone(Phone phone, boolean power) {
        if (null != phone) {
            if (!power) {
                log("setRadioPowerByPhone: " + power);
                phone.setRadioPower(power);
            } else {
                int phoneId = phone.getPhoneId();
                if (isAllowRadioPowerOn(phoneId)) {
                    log("setRadioPowerByPhone: " + power + ", phoneId: " + phoneId);
                    //phone.setRadioPower(power);
                    createPhonePowerProxy(phone).setRadioPower(phone, power);
                } else {
                    log("setRadioPowerByPhone, phoneId:" + phoneId + " not allow power on");
                }
            }
        } else {
            log("setRadioPowerByPhone: phone is null");
        }
    }

    private void setC2KRadioPower(boolean power, int phoneId) {
        log("C2K MD, power: " + power + ", phoneId: " + phoneId);
        Phone phone = getPhoneByPhoneProxy(phoneId, false);
        if (null != phone) {
            phone.setRadioPower(power);
        }
    }
    // Radio power on/off related @}

    // MULTI-SIM management related @{
    @Override
    protected void onReceiveSimStateChangedIntent(Intent intent) {
        String simStatus = intent.getStringExtra(IccCardConstants.INTENT_KEY_ICC_STATE);

        // TODO: phone_key now is equals to slot_key, change in the future
        int phoneId = intent.getIntExtra(PhoneConstants.PHONE_KEY, INVALID_PHONE_ID);

        if (!isValidPhoneId(phoneId)) {
            log("INTENT:Invalid phone id:" + phoneId + ", do nothing!");
            return;
        }

        log("INTENT:SIM_STATE_CHANGED: " + intent.getAction()
                + ", sim status: " + simStatus + ", phoneId: " + phoneId);

        int phoneCount = TelephonyManager.getDefault().getSimCount();
        if (phoneCount == 1) {
            if (!SystemProperties.get(PROPERTY_RIL_CARD_TYPE_SET).equals("1")) {
                return;
            }
        } else {
            if (!(SystemProperties.get(PROPERTY_RIL_CARD_TYPE_SET).equals("1")
                && SystemProperties.get(PROPERTY_RIL_CARD_TYPE_SET_2).equals("1"))) {
                return;
            }
        }

        boolean desiredRadioPower = RADIO_POWER_ON;

        if (IccCardConstants.INTENT_VALUE_ICC_READY.equals(simStatus)
            || IccCardConstants.INTENT_VALUE_ICC_LOCKED.equals(simStatus)
            || IccCardConstants.INTENT_VALUE_ICC_LOADED.equals(simStatus)) {
            mSimInsertedStatus[phoneId] = SIM_INSERTED;
            log("Phone[" + phoneId + "]: " + simStatusToString(SIM_INSERTED));

            // if we receive ready, but can't get iccid, we do nothing
            String iccid = readIccIdUsingPhoneId(phoneId);
            if (STRING_NO_SIM_INSERTED.equals(iccid)) {
                if (IccCardConstants.INTENT_VALUE_ICC_LOADED.equals(simStatus)
                        && phoneId == SvlteModeController.getCdmaSocketSlotId()
                        && SvlteUiccUtils.getInstance().isUsimWithCsim(phoneId)) {
                    log("CT 4G card SIM state loaded, c2k iccid not ready, wait for it...");
                    SimStateChangedRunnable simStateChangedRunnable =
                            new SimStateChangedRunnable(intent);
                    postDelayed(simStateChangedRunnable, INITIAL_RETRY_INTERVAL_MSEC);
                    return;
                } else {
                    log("Phone " + phoneId + ":SIM ready but ICCID not ready, do nothing");
                    return;
                }
            } else if (phoneId == SvlteModeController.getCdmaSocketSlotId()
                && (!SvlteUiccUtils.getInstance().isHaveCard(phoneId))) {
                log("Phone " + phoneId + ": No card, do nothing");
                return;
            }

            desiredRadioPower = RADIO_POWER_ON;
            if (mAirplaneMode == AIRPLANE_MODE_OFF) {
                log("Set Radio Power due to SIM_STATE_CHANGED, power: "
                        + desiredRadioPower + ", phoneId: " + phoneId);
                setPhoneRadioPower(desiredRadioPower, phoneId);
            }
        } else if (IccCardConstants.INTENT_VALUE_ICC_ABSENT.equals(simStatus)) {
            if (phoneId == SvlteModeController.getCdmaSocketSlotId()
                && (SvlteUiccUtils.getInstance().isHaveCard(phoneId))) {
                return;
            }
            mSimInsertedStatus[phoneId] = NO_SIM_INSERTED;
            log("Phone[" + phoneId + "]: " + simStatusToString(NO_SIM_INSERTED));
            desiredRadioPower = RADIO_POWER_OFF;
            if (mAirplaneMode == AIRPLANE_MODE_OFF) {
                log("Set Radio Power due to SIM_STATE_CHANGED, power: "
                        + desiredRadioPower + ", phoneId: " + phoneId);
                setRadioPower(desiredRadioPower, phoneId);
            }
        }
    }

    /*
     * Refresh MSIM Settings only when:
     * We auto turn off a SIM card once manually turned off
     */
    @Override
    protected void refreshSimSetting(boolean radioPower, int phoneId) {
        if (radioPower) {
            phoneId = SvlteUtils.getSlotId(phoneId);
        } else {
            // Don't update OFF MSIM_MODE_SETTING except Active phone
            if (SvlteUtils.isLteDcPhoneId(phoneId)) {
                log("refreshSimSetting phoneId=" + phoneId + ", not update SimSetting!");
                return;
            }
        }

        super.refreshSimSetting(radioPower, phoneId);
    }

    @Override
    protected void setSimInsertedStatus(int phoneId) {
        if (!SvlteUtils.isLteDcPhoneId(phoneId)) {
            super.setSimInsertedStatus(phoneId);
        }
    }

    /** IPO power on Runnable.
     *  c2k ICCID may not ready when SIM state changed,
     *  this may cause not set radio power for LTE phone
     *
     */
    private class SimStateChangedRunnable implements Runnable {
        Intent mRetryIntent;

        public SimStateChangedRunnable(Intent intent) {
            mRetryIntent = intent;
        }

        @Override
        public void run() {
            onReceiveSimStateChangedIntent(mRetryIntent);
        }
    }
    // MULTI-SIM management related @}

    // Airplane mode related @{
    /**
     * Modify mAirplaneMode and set modem power.
     * @param enabled 0: normal mode
     *                1: airplane mode
     */
    @Override
    public void notifyAirplaneModeChange(boolean enabled) {
        // we expect airplane mode is on-> off or off->on
        if (enabled == mAirplaneMode) {
            log("enabled = " + enabled + ", mAirplaneMode = "
                    + mAirplaneMode + "is not expected (the same)");
            return;
        }

        mAirplaneMode = enabled;
        log("Airplane mode changed:" + enabled);

        if (isFlightModePowerOffModemEnabled() && !isUnderCryptKeeper()) {
            log("Airplane mode changed: turn on/off all modem");
            boolean modemPower = enabled ? MODEM_POWER_OFF : MODEM_POWER_ON;
            setSilentRebootPropertyForAllModem(IS_SILENT_REBOOT);
            setModemPower(modemPower, mBitmapForPhoneCount);
            SystemProperties.set(PROPERTY_CONFIG_EMDSTATUS_SEND, "0");
        } else if (isMSimModeSupport()) {
            log("Airplane mode changed: turn on/off all radio");
            boolean radioPower = enabled ? RADIO_POWER_OFF : RADIO_POWER_ON;
            for (int i = 0; i < mPhoneCount; i++) {
                setPhoneRadioPower(radioPower, i);
            }
        }
    }

    @Override
    public void setSilentRebootPropertyForAllModem(String isSilentReboot) {
        TelephonyManager.MultiSimVariants config =
                TelephonyManager.getDefault().getMultiSimConfiguration();
        switch(config) {
            case DSDS:
                log("set eboot under DSDS");
                SystemProperties.set(PROPERTY_SILENT_REBOOT_MD1, isSilentReboot);
                SystemProperties.set(CDMA_PROPERTY_SILENT_REBOOT_MD, isSilentReboot);
                break;
            case DSDA:
                log("set eboot under DSDA");
                SystemProperties.set(PROPERTY_SILENT_REBOOT_MD1, isSilentReboot);
                SystemProperties.set(PROPERTY_SILENT_REBOOT_MD2, isSilentReboot);
                SystemProperties.set(CDMA_PROPERTY_SILENT_REBOOT_MD, isSilentReboot);
                break;
            case TSTS:
                log("set eboot under TSTS");
                SystemProperties.set(PROPERTY_SILENT_REBOOT_MD1, isSilentReboot);
                break;
            default:
                log("set eboot under SS");
                SystemProperties.set(PROPERTY_SILENT_REBOOT_MD1, isSilentReboot);
                SystemProperties.set(CDMA_PROPERTY_SILENT_REBOOT_MD, isSilentReboot);
                break;
        }
    }
    // Airplane mode related @}

    // IPO related @{
    @Override
    public void notifyIpoShutDown() {
        resetCardProperties();
        super.notifyIpoShutDown();
    }

    private void resetCardProperties() {
        log("Reset Card Properties");
        SystemProperties.set(PROPERTY_RIL_CARD_TYPE_SET, "0");
        SystemProperties.set(PROPERTY_RIL_CARD_TYPE_SET_2, "0");
        SystemProperties.set(PROPERTY_CONFIG_EMDSTATUS_SEND, "0");
    }
    // IPO related @}

    // Common functions @{
    @Override
    protected String readIccIdUsingPhoneId(int phoneId) {
        String ret = null;
        log("readIccIdUsingPhoneId: phoneId=" + phoneId);
        int radioTechMode =
                SvlteModeController.getRadioTechnologyMode(SvlteUtils.getSlotId(phoneId));
        int slotId = SvlteUtils.getSlotId(phoneId);
        log("readIccIdUsingPhoneId: radioTechMode=" + radioTechMode + ", slotId=" + slotId);
        do {
            //TODO: To check TDD data only Instrument case
            if (SvlteModeController.RADIO_TECH_MODE_SVLTE == radioTechMode) {
                // get ICCID property from C2K MD if it CT 3G SIM
                if (SvlteUiccUtils.getInstance().isRuimCsim(slotId)) {
                    log("readIccIdUsingPhoneId: phoneId=" + phoneId + " for UIM card");
                    ret = SystemProperties.get(PROPERTY_ICCID_SIM_C2K);
                    // update iccid property for CT 3G SIM
                    String iccidCommon = SystemProperties.get(PROPERTY_ICCID_SIM[slotId]);
                    log("readIccIdUsingPhoneId: slotId" + slotId + " iccidCommon=" + iccidCommon);
                    if ((iccidCommon == null || iccidCommon.equals("") || iccidCommon.equals("N/A"))
                        && (ret != null && !ret.equals(""))) {
                        SystemProperties.set(PROPERTY_ICCID_SIM[slotId], ret);
                        log("readIccIdUsingPhoneId: update iccid["
                            + slotId + "] use iccidC2K:" + ret);
                    }
                    break;
                }
                if (SvlteUtils.isLteDcPhoneId(phoneId)) {
                    ret = SystemProperties.get(PROPERTY_ICCID_SIM[slotId]);
                } else {
                    ret = SystemProperties.get(PROPERTY_ICCID_SIM_C2K);
                }
            } else if (SvlteModeController.RADIO_TECH_MODE_CSFB == radioTechMode) {
                if (SvlteUtils.isLteDcPhoneId(phoneId)) {
                    ret = SystemProperties.get(PROPERTY_ICCID_SIM_C2K);
                } else {
                    ret = SystemProperties.get(PROPERTY_ICCID_SIM[slotId]);
                }
            } else {
                log("readIccIdUsingPhoneId: invalid radioTechMode=" + radioTechMode);
            }
        } while (false);

        log("ICCID for phone " + phoneId + " is " + ret);
        return ret;
    }

    /**
     * Check whether power on is allowed.
     * @param phoneId
     * @return true or false
     */
    private boolean isAllowRadioPowerOn(int phoneId) {
        return SvlteUtils.getSvltePhoneProxy(phoneId)
                       .getSvlteRatController().allowRadioPowerOn(phoneId);
    }
    // Common functions @}

    private void log(String s) {
        Rlog.d(LOG_TAG, s);
    }
}
