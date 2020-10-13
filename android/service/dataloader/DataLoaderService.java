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
 * limitations under the License.
 */

package android.service.dataloader;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.app.Service;
import android.content.Intent;
import android.content.pm.DataLoaderParams;
import android.content.pm.DataLoaderParamsParcel;
import android.content.pm.FileSystemControlParcel;
import android.content.pm.IDataLoader;
import android.content.pm.IDataLoaderStatusListener;
import android.content.pm.InstallationFile;
import android.content.pm.InstallationFileParcel;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.util.ExceptionUtils;
import android.util.Slog;

import libcore.io.IoUtils;

import java.io.IOException;
import java.util.Collection;

/**
 * The base class for implementing data loader service to control data loaders. Expecting
 * Incremental Service to bind to a children class of this.
 *
 * WARNING: This is a system API to aid internal development.
 * Use at your own risk. It will change or be removed without warning.
 *
 * TODO(b/136132412): update with latest API design
 *
 * @hide
 */
@SystemApi
public abstract class DataLoaderService extends Service {
    private static final String TAG = "DataLoaderService";
    private final DataLoaderBinderService mBinder = new DataLoaderBinderService();

    /**
     * Managed DataLoader interface. Each instance corresponds to a single installation session.
     * @hide
     */
    @SystemApi
    public interface DataLoader {
        /**
         * A virtual constructor.
         *
         * @param dataLoaderParams parameters set in the installation session
         * @param connector FS API wrapper
         * @return True if initialization of a Data Loader was successful. False will be reported to
         * PackageManager and fail the installation
         */
        boolean onCreate(@NonNull DataLoaderParams dataLoaderParams,
                @NonNull FileSystemConnector connector);

        /**
         * Prepare installation image. After this method succeeds installer will validate the files
         * and continue installation.
         *
         * @param addedFiles   list of files created in this installation session.
         * @param removedFiles list of files removed in this installation session.
         * @return false if unable to create and populate all addedFiles.
         */
        boolean onPrepareImage(@NonNull Collection<InstallationFile> addedFiles,
                @NonNull Collection<String> removedFiles);
    }

    /**
     * DataLoader factory method.
     *
     * @return An instance of a DataLoader.
     * @hide
     */
    @SystemApi
    public @Nullable DataLoader onCreateDataLoader(@NonNull DataLoaderParams dataLoaderParams) {
        return null;
    }

    /**
     * @hide
     */
    public final @NonNull IBinder onBind(@NonNull Intent intent) {
        return (IBinder) mBinder;
    }

    private class DataLoaderBinderService extends IDataLoader.Stub {
        @Override
        public void create(int id, @NonNull DataLoaderParamsParcel params,
                @NonNull FileSystemControlParcel control,
                @NonNull IDataLoaderStatusListener listener)
                throws RuntimeException {
            try {
                nativeCreateDataLoader(id, control, params, listener);
            } catch (Exception ex) {
                Slog.e(TAG, "Failed to create native loader for " + id, ex);
                destroy(id);
                throw new RuntimeException(ex);
            } finally {
                if (control.incremental != null) {
                    IoUtils.closeQuietly(control.incremental.cmd);
                    IoUtils.closeQuietly(control.incremental.pendingReads);
                    IoUtils.closeQuietly(control.incremental.log);
                }
            }
        }

        @Override
        public void start(int id) {
            if (!nativeStartDataLoader(id)) {
                Slog.e(TAG, "Failed to start loader: " + id);
            }
        }

        @Override
        public void stop(int id) {
            if (!nativeStopDataLoader(id)) {
                Slog.w(TAG, "Failed to stop loader: " + id);
            }
        }

        @Override
        public void destroy(int id) {
            if (!nativeDestroyDataLoader(id)) {
                Slog.w(TAG, "Failed to destroy loader: " + id);
            }
        }

        @Override
        public void prepareImage(int id, InstallationFileParcel[] addedFiles,
                String[] removedFiles) {
            if (!nativePrepareImage(id, addedFiles, removedFiles)) {
                Slog.w(TAG, "Failed to prepare image for data loader: " + id);
            }
        }
    }

    /**
     * Used by the DataLoaderService implementations.
     *
     * @hide
     */
    @SystemApi
    public static final class FileSystemConnector {
        /**
         * Create a wrapper for a native instance.
         *
         * @hide
         */
        FileSystemConnector(long nativeInstance) {
            mNativeInstance = nativeInstance;
        }

        /**
         * Write data to an installation file from an arbitrary FD.
         *
         * @param name        name of file previously added to the installation session.
         * @param offsetBytes offset into the file to begin writing at, or 0 to start at the
         *                    beginning of the file.
         * @param lengthBytes total size of the file being written, used to preallocate the
         *                    underlying disk space, or -1 if unknown. The system may clear various
         *                    caches as needed to allocate this space.
         * @param incomingFd  FD to read bytes from.
         * @throws IOException if trouble opening the file for writing, such as lack of disk space
         *                     or unavailable media.
         */
        @RequiresPermission(android.Manifest.permission.INSTALL_PACKAGES)
        public void writeData(@NonNull String name, long offsetBytes, long lengthBytes,
                @NonNull ParcelFileDescriptor incomingFd) throws IOException {
            try {
                nativeWriteData(mNativeInstance, name, offsetBytes, lengthBytes, incomingFd);
            } catch (RuntimeException e) {
                ExceptionUtils.maybeUnwrapIOException(e);
                throw e;
            }
        }

        private final long mNativeInstance;
    }

    /* Native methods */
    private native boolean nativeCreateDataLoader(int storageId,
            @NonNull FileSystemControlParcel control,
            @NonNull DataLoaderParamsParcel params,
            IDataLoaderStatusListener listener);

    private native boolean nativeStartDataLoader(int storageId);

    private native boolean nativeStopDataLoader(int storageId);

    private native boolean nativeDestroyDataLoader(int storageId);

    private native boolean nativePrepareImage(int storageId,
            InstallationFileParcel[] addedFiles, String[] removedFiles);

    private static native void nativeWriteData(long nativeInstance, String name, long offsetBytes,
            long lengthBytes, ParcelFileDescriptor incomingFd);

}
