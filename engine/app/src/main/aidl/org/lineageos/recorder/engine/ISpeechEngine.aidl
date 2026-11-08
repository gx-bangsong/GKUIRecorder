package org.lineageos.recorder.engine;

import android.os.ParcelFileDescriptor;

/**
 * Contract between the Recorder app and the on-demand speech engine APK.
 * Keep this file identical in both projects.
 */
interface ISpeechEngine {
    /** Protocol version implemented by the engine. */
    int getProtocolVersion();

    /**
     * Makes sure the model package at [url] is downloaded and loaded. Returns an empty
     * string on success, otherwise a human readable error. [sha256] may be empty.
     */
    String prepareModel(String url, String sha256);

    /**
     * Transcribes the WAV or m4a audio read from [audio]. Throws ServiceSpecificException
     * with a message when recognition fails.
     */
    String transcribe(in ParcelFileDescriptor audio, String language);
}
