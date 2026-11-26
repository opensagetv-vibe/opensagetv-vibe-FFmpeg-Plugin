package org.opensagetv.vibe.ffmpeg;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;

import sage.SageTV;

/** Restricts MIM Direct sources to files already known to stock SageTV. */
final class SageMediaSourceAuthorizer implements MimDirectSessionService.SourceAuthorizer {
    public Boolean isRecording(Path source) {
        if (source == null) return null;
        try {
            Object media = SageTV.api("GetMediaFileForFilePath", new Object[]{source.toFile()});
            if (media == null) return null;
            Object recording = SageTV.api("IsFileCurrentlyRecording", new Object[]{media});
            return recording instanceof Boolean ? (Boolean) recording : null;
        } catch (InvocationTargetException failure) {
            return null;
        }
    }
    public boolean isAllowed(Path source) {
        if (source == null) return false;
        try {
            return SageTV.api("GetMediaFileForFilePath", new Object[]{source.toFile()}) != null;
        } catch (InvocationTargetException failure) {
            return false;
        }
    }
}
