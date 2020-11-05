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

package com.android.internal.telephony.metrics;

import static android.text.format.DateUtils.HOUR_IN_MILLIS;
import static android.text.format.DateUtils.MINUTE_IN_MILLIS;
import static android.text.format.DateUtils.SECOND_IN_MILLIS;

import static com.android.internal.telephony.TelephonyStatsLog.INCOMING_SMS;
import static com.android.internal.telephony.TelephonyStatsLog.OUTGOING_SMS;
import static com.android.internal.telephony.TelephonyStatsLog.SIM_SLOT_STATE;
import static com.android.internal.telephony.TelephonyStatsLog.SUPPORTED_RADIO_ACCESS_FAMILY;
import static com.android.internal.telephony.TelephonyStatsLog.VOICE_CALL_RAT_USAGE;
import static com.android.internal.telephony.TelephonyStatsLog.VOICE_CALL_SESSION;

import android.annotation.Nullable;
import android.app.StatsManager;
import android.content.Context;
import android.util.StatsEvent;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.TelephonyStatsLog;
import com.android.internal.telephony.nano.PersistAtomsProto.IncomingSms;
import com.android.internal.telephony.nano.PersistAtomsProto.OutgoingSms;
import com.android.internal.telephony.nano.PersistAtomsProto.RawVoiceCallRatUsage;
import com.android.internal.telephony.nano.PersistAtomsProto.VoiceCallSession;
import com.android.internal.util.ConcurrentUtils;
import com.android.telephony.Rlog;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * Implements statsd pullers for Telephony.
 *
 * <p>This class registers pullers to statsd, which will be called once a day to obtain telephony
 * statistics that cannot be sent to statsd in real time.
 */
public class MetricsCollector implements StatsManager.StatsPullAtomCallback {
    private static final String TAG = MetricsCollector.class.getSimpleName();

    /** Disables various restrictions to ease debugging during development. */
    private static final boolean DBG = false; // STOPSHIP if true

    /**
     * Sets atom pull cool down to 23 hours to help enforcing privacy requirement.
     *
     * <p>Applies to certain atoms. The interval of 23 hours leaves some margin for pull operations
     * that occur once a day.
     */
    private static final long MIN_COOLDOWN_MILLIS =
            DBG ? 10L * SECOND_IN_MILLIS : 23L * HOUR_IN_MILLIS;

    /**
     * Buckets with less than these many calls will be dropped.
     *
     * <p>Applies to metrics with duration fields. Currently used by voice call RAT usages.
     */
    private static final long MIN_CALLS_PER_BUCKET = DBG ? 0L : 5L;

    /** Bucket size in milliseconds to round call durations into. */
    private static final long DURATION_BUCKET_MILLIS =
            DBG ? 2L * SECOND_IN_MILLIS : 5L * MINUTE_IN_MILLIS;

    private static final StatsManager.PullAtomMetadata POLICY_PULL_DAILY =
            new StatsManager.PullAtomMetadata.Builder()
                    .setCoolDownMillis(MIN_COOLDOWN_MILLIS)
                    .build();

    private PersistAtomsStorage mStorage;
    private final StatsManager mStatsManager;
    private final AirplaneModeStats mAirplaneModeStats;
    private static final Random sRandom = new Random();

    public MetricsCollector(Context context) {
        mStorage = new PersistAtomsStorage(context);
        mStatsManager = (StatsManager) context.getSystemService(Context.STATS_MANAGER);
        if (mStatsManager != null) {
            registerAtom(SIM_SLOT_STATE, null);
            registerAtom(SUPPORTED_RADIO_ACCESS_FAMILY, null);
            registerAtom(VOICE_CALL_RAT_USAGE, POLICY_PULL_DAILY);
            registerAtom(VOICE_CALL_SESSION, POLICY_PULL_DAILY);
            registerAtom(INCOMING_SMS, POLICY_PULL_DAILY);
            registerAtom(OUTGOING_SMS, POLICY_PULL_DAILY);
            Rlog.d(TAG, "registered");
        } else {
            Rlog.e(TAG, "could not get StatsManager, atoms not registered");
        }

        mAirplaneModeStats = new AirplaneModeStats(context);
    }

    /** Replaces the {@link PersistAtomsStorage} backing the puller. Used during unit tests. */
    @VisibleForTesting
    public void setPersistAtomsStorage(PersistAtomsStorage storage) {
        mStorage = storage;
    }

    /**
     * {@inheritDoc}
     *
     * @return {@link StatsManager#PULL_SUCCESS} with list of atoms (potentially empty) if pull
     *     succeeded, {@link StatsManager#PULL_SKIP} if pull was too frequent or atom ID is
     *     unexpected.
     */
    @Override
    public int onPullAtom(int atomTag, List<StatsEvent> data) {
        switch (atomTag) {
            case SIM_SLOT_STATE:
                return pullSimSlotState(data);
            case SUPPORTED_RADIO_ACCESS_FAMILY:
                return pullSupportedRadioAccessFamily(data);
            case VOICE_CALL_RAT_USAGE:
                return pullVoiceCallRatUsages(data);
            case VOICE_CALL_SESSION:
                return pullVoiceCallSessions(data);
            case INCOMING_SMS:
                return pullIncomingSms(data);
            case OUTGOING_SMS:
                return pullOutgoingSms(data);
            default:
                Rlog.e(TAG, String.format("unexpected atom ID %d", atomTag));
                return StatsManager.PULL_SKIP;
        }
    }

    /** Returns the {@link PersistAtomsStorage} backing the puller. */
    public PersistAtomsStorage getAtomsStorage() {
        return mStorage;
    }

    private static int pullSimSlotState(List<StatsEvent> data) {
        SimSlotState state;
        try {
            state = SimSlotState.getCurrentState();
        } catch (RuntimeException e) {
            // UiccController has not been made yet
            return StatsManager.PULL_SKIP;
        }

        data.add(TelephonyStatsLog.buildStatsEvent(
                  SIM_SLOT_STATE,
                  state.numActiveSlots,
                  state.numActiveSims,
                  state.numActiveEsims));
        return StatsManager.PULL_SUCCESS;
    }

    private static int pullSupportedRadioAccessFamily(List<StatsEvent> data) {
        long rafSupported = 0L;
        try {
            // The bitmask is defined in android.telephony.TelephonyManager.NetworkTypeBitMask
            for (Phone phone : PhoneFactory.getPhones()) {
                rafSupported |= phone.getRadioAccessFamily();
            }
        } catch (IllegalStateException e) {
            // Phones have not been made yet
            return StatsManager.PULL_SKIP;
        }

        data.add(TelephonyStatsLog.buildStatsEvent(
                  SUPPORTED_RADIO_ACCESS_FAMILY,
                  rafSupported));
        return StatsManager.PULL_SUCCESS;
    }

    private int pullVoiceCallRatUsages(List<StatsEvent> data) {
        RawVoiceCallRatUsage[] usages = mStorage.getVoiceCallRatUsages(MIN_COOLDOWN_MILLIS);
        if (usages != null) {
            // sort by carrier/RAT and remove buckets with insufficient number of calls
            Arrays.stream(usages)
                    .sorted(
                            Comparator.comparingLong(
                                    usage -> ((long) usage.carrierId << 32) | usage.rat))
                    .filter(usage -> usage.callCount >= MIN_CALLS_PER_BUCKET)
                    .forEach(usage -> data.add(buildStatsEvent(usage)));
            Rlog.d(
                    TAG,
                    String.format(
                            "%d out of %d VOICE_CALL_RAT_USAGE pulled",
                            data.size(), usages.length));
            return StatsManager.PULL_SUCCESS;
        } else {
            Rlog.w(TAG, "VOICE_CALL_RAT_USAGE pull too frequent, skipping");
            return StatsManager.PULL_SKIP;
        }
    }

    private int pullVoiceCallSessions(List<StatsEvent> data) {
        VoiceCallSession[] calls = mStorage.getVoiceCallSessions(MIN_COOLDOWN_MILLIS);
        if (calls != null) {
            // call session list is already shuffled when calls were inserted
            Arrays.stream(calls).forEach(call -> data.add(buildStatsEvent(call)));
            return StatsManager.PULL_SUCCESS;
        } else {
            Rlog.w(TAG, "VOICE_CALL_SESSION pull too frequent, skipping");
            return StatsManager.PULL_SKIP;
        }
    }

    private int pullIncomingSms(List<StatsEvent> data) {
        IncomingSms[] smsList = mStorage.getIncomingSms(MIN_COOLDOWN_MILLIS);
        if (smsList != null) {
            // SMS list is already shuffled when SMS were inserted
            Arrays.stream(smsList).forEach(sms -> data.add(buildStatsEvent(sms)));
            return StatsManager.PULL_SUCCESS;
        } else {
            Rlog.w(TAG, "INCOMING_SMS pull too frequent, skipping");
            return StatsManager.PULL_SKIP;
        }
    }

    private int pullOutgoingSms(List<StatsEvent> data) {
        OutgoingSms[] smsList = mStorage.getOutgoingSms(MIN_COOLDOWN_MILLIS);
        if (smsList != null) {
            // SMS list is already shuffled when SMS were inserted
            Arrays.stream(smsList).forEach(sms -> data.add(buildStatsEvent(sms)));
            return StatsManager.PULL_SUCCESS;
        } else {
            Rlog.w(TAG, "OUTGOING_SMS pull too frequent, skipping");
            return StatsManager.PULL_SKIP;
        }
    }

    /** Registers a pulled atom ID {@code atomId} with optional {@code policy} for pulling. */
    private void registerAtom(int atomId, @Nullable StatsManager.PullAtomMetadata policy) {
        mStatsManager.setPullAtomCallback(atomId, policy, ConcurrentUtils.DIRECT_EXECUTOR, this);
    }

    private static StatsEvent buildStatsEvent(RawVoiceCallRatUsage usage) {
        return TelephonyStatsLog.buildStatsEvent(
                VOICE_CALL_RAT_USAGE,
                usage.carrierId,
                usage.rat,
                round(usage.totalDurationMillis, DURATION_BUCKET_MILLIS) / SECOND_IN_MILLIS,
                usage.callCount);
    }

    private static StatsEvent buildStatsEvent(VoiceCallSession session) {
        return TelephonyStatsLog.buildStatsEvent(
                VOICE_CALL_SESSION,
                session.bearerAtStart,
                session.bearerAtEnd,
                session.direction,
                session.setupDuration,
                session.setupFailed,
                session.disconnectReasonCode,
                session.disconnectExtraCode,
                session.disconnectExtraMessage,
                session.ratAtStart,
                session.ratAtEnd,
                session.ratSwitchCount,
                session.codecBitmask,
                session.concurrentCallCountAtStart,
                session.concurrentCallCountAtEnd,
                session.simSlotIndex,
                session.isMultiSim,
                session.isEsim,
                session.carrierId,
                session.srvccCompleted,
                session.srvccFailureCount,
                session.srvccCancellationCount,
                session.rttEnabled,
                session.isEmergency,
                session.isRoaming,
                // workaround: dimension required for keeping multiple pulled atoms
                sRandom.nextInt());
    }

    private static StatsEvent buildStatsEvent(IncomingSms sms) {
        return TelephonyStatsLog.buildStatsEvent(
                INCOMING_SMS,
                sms.smsFormat,
                sms.smsTech,
                sms.rat,
                sms.smsType,
                sms.totalParts,
                sms.receivedParts,
                sms.blocked,
                sms.error,
                sms.isRoaming,
                sms.simSlotIndex,
                sms.isMultiSim,
                sms.isEsim,
                sms.carrierId,
                sms.messageId);
    }

    private static StatsEvent buildStatsEvent(OutgoingSms sms) {
        return TelephonyStatsLog.buildStatsEvent(
                OUTGOING_SMS,
                sms.smsFormat,
                sms.smsTech,
                sms.rat,
                sms.sendResult,
                sms.errorCode,
                sms.isRoaming,
                sms.isFromDefaultApp,
                sms.simSlotIndex,
                sms.isMultiSim,
                sms.isEsim,
                sms.carrierId,
                sms.messageId,
                sms.retryId);
    }

    /** Returns the value rounded to the bucket. */
    private static long round(long value, long bucket) {
        return ((value + bucket / 2) / bucket) * bucket;
    }
}
