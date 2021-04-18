/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.internal.telephony.dataconnection;

import android.annotation.ElapsedRealtimeLong;
import android.telephony.AccessNetworkConstants;
import android.telephony.Annotation;
import android.telephony.Annotation.ApnType;
import android.telephony.data.ApnSetting;
import android.telephony.data.ThrottleStatus;

import com.android.internal.telephony.RetryManager;
import com.android.telephony.Rlog;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Data throttler tracks the throttling status of the data network and notifies registrants when
 * there are changes.  The throttler is per phone and per transport type.
 */
public class DataThrottler {
    private static final String TAG = DataThrottler.class.getSimpleName();

    private final int mSlotIndex;
    private final @AccessNetworkConstants.TransportType int mTransportType;

    /**
     * Callbacks that report the apn throttle status.
     */
    private final List<DataThrottler.Callback> mCallbacks = new ArrayList<>();

    /**
     * Keeps track of detailed information of the throttle status that is meant to be
     * reported to other components.
     */
    private final Map<Integer, ThrottleStatus> mThrottleStatus = new ConcurrentHashMap<>();

    public DataThrottler(int slotIndex, int transportType) {
        mSlotIndex = slotIndex;
        mTransportType = transportType;
    }

    /**
     * Set the retry time and handover failure mode for the give APN types.
     *
     * @param apnTypes APN types
     * @param retryElapsedTime The elapsed time that data connection for APN types should not be
     * retried. {@link RetryManager#NO_SUGGESTED_RETRY_DELAY} indicates throttling does not exist.
     * {@link RetryManager#NO_RETRY} indicates retry should never happen.
     */
    public void setRetryTime(@ApnType int apnTypes, long retryElapsedTime,
            @DcTracker.RequestNetworkType int newRequestType) {
        if (retryElapsedTime < 0) {
            retryElapsedTime = RetryManager.NO_SUGGESTED_RETRY_DELAY;
        }

        List<ThrottleStatus> changedStatuses = new ArrayList<>();
        while (apnTypes != 0) {

            //Extract the least significant bit.
            int apnType = apnTypes & -apnTypes;

            //Update the apn throttle status
            ThrottleStatus newStatus = createStatus(apnType, retryElapsedTime, newRequestType);

            ThrottleStatus oldStatus = mThrottleStatus.get(apnType);

            //Check to see if there is a change that needs to be applied
            if (!newStatus.equals(oldStatus)) {
                //Mark as changed status
                changedStatuses.add(newStatus);

                //Put the new status in the temp space
                mThrottleStatus.put(apnType, newStatus);
            }

            //Remove the least significant bit.
            apnTypes &= apnTypes - 1;
        }

        if (changedStatuses.size() > 0) {
            sendThrottleStatusChanged(changedStatuses);
        }
    }

    /**
     * Get the earliest retry time for the given APN type. The time is the system's elapse time.
     *
     * @param apnType APN type
     * @return The earliest retry time for APN type. The time is the system's elapse time.
     * {@link RetryManager#NO_SUGGESTED_RETRY_DELAY} indicates there is no throttling for given APN
     * type, {@link RetryManager#NO_RETRY} indicates retry should never happen.
     */
    @ElapsedRealtimeLong
    public long getRetryTime(@ApnType int apnType) {
        // This is the workaround to handle the mistake that
        // ApnSetting.TYPE_DEFAULT = ApnTypes.DEFAULT | ApnTypes.HIPRI.
        if (apnType == ApnSetting.TYPE_DEFAULT) {
            apnType &= ~(ApnSetting.TYPE_HIPRI);
        }

        ThrottleStatus status = mThrottleStatus.get(apnType);
        if (status != null) {
            if (status.getThrottleType() == ThrottleStatus.THROTTLE_TYPE_NONE) {
                return RetryManager.NO_SUGGESTED_RETRY_DELAY;
            } else {
                return status.getThrottleExpiryTimeMillis();
            }
        }
        return RetryManager.NO_SUGGESTED_RETRY_DELAY;
    }

    /**
     * Resets retry times for all APNs to {@link RetryManager.NO_SUGGESTED_RETRY_DELAY}.
     */
    public void reset() {
        final List<Integer> apnTypes = new ArrayList<>();
        for (ThrottleStatus throttleStatus : mThrottleStatus.values()) {
            apnTypes.add(throttleStatus.getApnType());
        }

        for (int apnType : apnTypes) {
            setRetryTime(apnType, RetryManager.NO_SUGGESTED_RETRY_DELAY,
                    DcTracker.REQUEST_TYPE_NORMAL);
        }
    }

    private ThrottleStatus createStatus(@Annotation.ApnType int apnType, long retryElapsedTime,
            @DcTracker.RequestNetworkType int newRequestType) {
        ThrottleStatus.Builder builder = new ThrottleStatus.Builder();

        if (retryElapsedTime == RetryManager.NO_SUGGESTED_RETRY_DELAY) {
            builder
                    .setNoThrottle()
                    .setRetryType(getRetryType(newRequestType));
        } else if (retryElapsedTime == RetryManager.NO_RETRY) {
            builder
                    .setThrottleExpiryTimeMillis(RetryManager.NO_RETRY)
                    .setRetryType(ThrottleStatus.RETRY_TYPE_NONE);
        } else {
            builder
                    .setThrottleExpiryTimeMillis(retryElapsedTime)
                    .setRetryType(getRetryType(newRequestType));
        }
        return builder
                .setSlotIndex(mSlotIndex)
                .setTransportType(mTransportType)
                .setApnType(apnType)
                .build();
    }

    private static int getRetryType(@DcTracker.RequestNetworkType int newRequestType) {
        if (newRequestType == DcTracker.REQUEST_TYPE_NORMAL) {
            return ThrottleStatus.RETRY_TYPE_NEW_CONNECTION;
        }

        if (newRequestType == DcTracker.REQUEST_TYPE_HANDOVER) {
            return  ThrottleStatus.RETRY_TYPE_HANDOVER;
        }

        loge("createStatus: Unknown requestType=" + newRequestType);
        return ThrottleStatus.RETRY_TYPE_NEW_CONNECTION;
    }

    private void sendThrottleStatusChanged(List<ThrottleStatus> statuses) {
        synchronized (mCallbacks) {
            for (int i = 0; i < mCallbacks.size(); i++) {
                mCallbacks.get(i).onThrottleStatusChanged(statuses);
            }
        }
    }

    private static void loge(String s) {
        Rlog.e(TAG, s);
    }

    /**
     * Reports changes to apn throttle statuses.
     *
     * Note: All statuses are sent when first registered.
     *
     * @param callback status changes callback
     */
    public void registerForThrottleStatusChanges(DataThrottler.Callback callback) {
        synchronized (mCallbacks) {
            //Only add if it's not there already
            if (!mCallbacks.contains(callback)) {
                //Report everything the first time
                List<ThrottleStatus> throttleStatuses =
                        new ArrayList<>(mThrottleStatus.values());
                callback.onThrottleStatusChanged(throttleStatuses);
                mCallbacks.add(callback);
            }
        }
    }

    /**
     * Unregister the callback
     * @param callback the callback to unregister
     */
    public void unregisterForThrottleStatusChanges(DataThrottler.Callback callback) {
        synchronized (mCallbacks) {
            mCallbacks.remove(callback);
        }
    }

    /**
     * Callback for when throttle statuses change
     */
    public interface Callback {
        /**
         * Called whenever the throttle status of an APN has changed.
         *
         * Note: Called with all statuses when first registered.
         *
         * @param throttleStatuses the status changes
         */
        void onThrottleStatusChanged(List<ThrottleStatus> throttleStatuses);
    }
}
