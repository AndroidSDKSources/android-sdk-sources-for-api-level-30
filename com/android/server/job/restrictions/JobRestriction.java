/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.job.restrictions;

import android.app.job.JobInfo;
import android.util.proto.ProtoOutputStream;

import com.android.internal.util.IndentingPrintWriter;
import com.android.server.job.JobSchedulerService;
import com.android.server.job.controllers.JobStatus;

/**
 * Used by {@link JobSchedulerService} to impose additional restrictions regarding whether jobs
 * should be scheduled or not based on the state of the system/device.
 * Every restriction is associated with exactly one reason (from {@link
 * android.app.job.JobParameters#JOB_STOP_REASON_CODES}), which could be retrieved using {@link
 * #getReason()}.
 * Note, that this is not taken into account for the jobs that have priority
 * {@link JobInfo#PRIORITY_FOREGROUND_APP} or higher.
 */
public abstract class JobRestriction {

    final JobSchedulerService mService;
    private final int mReason;

    JobRestriction(JobSchedulerService service, int reason) {
        mService = service;
        mReason = reason;
    }

    /**
     * Called when the system boot phase has reached
     * {@link com.android.server.SystemService#PHASE_SYSTEM_SERVICES_READY}.
     */
    public void onSystemServicesReady() {
    }

    /**
     * Called by {@link JobSchedulerService} to check if it may proceed with scheduling the job (in
     * case all constraints are satisfied and all other {@link JobRestriction}s are fine with it)
     *
     * @param job to be checked
     * @return false if the {@link JobSchedulerService} should not schedule this job at the moment,
     * true - otherwise
     */
    public abstract boolean isJobRestricted(JobStatus job);

    /** Dump any internal constants the Restriction may have. */
    public abstract void dumpConstants(IndentingPrintWriter pw);

    /** Dump any internal constants the Restriction may have. */
    public abstract void dumpConstants(ProtoOutputStream proto);

    /** @return reason code for the Restriction. */
    public final int getReason() {
        return mReason;
    }
}
