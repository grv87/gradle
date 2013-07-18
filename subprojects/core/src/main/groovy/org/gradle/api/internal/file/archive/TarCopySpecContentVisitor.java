/*
 * Copyright 2009 the original author or authors.
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
package org.gradle.api.internal.file.archive;

import org.apache.tools.tar.TarEntry;
import org.apache.tools.tar.TarOutputStream;
import org.apache.tools.zip.UnixStat;
import org.gradle.api.Action;
import org.gradle.api.GradleException;
import org.gradle.api.UncheckedIOException;
import org.gradle.api.file.FileCopyDetails;
import org.gradle.api.internal.file.archive.compression.ArchiveOutputStreamFactory;
import org.gradle.api.internal.file.copy.CopySpecContentVisitor;
import org.gradle.api.internal.file.copy.FileCopyDetailsInternal;
import org.gradle.api.internal.tasks.SimpleWorkResult;
import org.gradle.api.tasks.WorkResult;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;

public class TarCopySpecContentVisitor implements CopySpecContentVisitor {
    private final File tarFile;
    private final ArchiveOutputStreamFactory compressor;

    public TarCopySpecContentVisitor(File tarFile, ArchiveOutputStreamFactory compressor) {
        this.tarFile = tarFile;
        this.compressor = compressor;
    }

    public WorkResult visit(Action<Action<? super FileCopyDetailsInternal>> visitor) {
        final TarOutputStream tarOutStr;

        try {
            OutputStream outStr = compressor.createArchiveOutputStream(tarFile);
            tarOutStr = new TarOutputStream(outStr);
            tarOutStr.setLongFileMode(TarOutputStream.LONGFILE_GNU);
        } catch (Exception e) {
            throw new GradleException(String.format("Could not create TAR '%s'.", tarFile), e);
        }

        visitor.execute(new Action<FileCopyDetailsInternal>() {
            public void execute(FileCopyDetailsInternal details) {
                if (details.isDirectory()) {
                    visitDir(details);
                } else {
                    visitFile(details);
                }
            }

            private void visitFile(FileCopyDetails fileDetails) {
                try {
                    TarEntry archiveEntry = new TarEntry(fileDetails.getRelativePath().getPathString());
                    archiveEntry.setModTime(fileDetails.getLastModified());
                    archiveEntry.setSize(fileDetails.getSize());
                    archiveEntry.setMode(UnixStat.FILE_FLAG | fileDetails.getMode());
                    tarOutStr.putNextEntry(archiveEntry);
                    fileDetails.copyTo(tarOutStr);
                    tarOutStr.closeEntry();
                } catch (Exception e) {
                    throw new GradleException(String.format("Could not add %s to TAR '%s'.", fileDetails, tarFile), e);
                }
            }

            private void visitDir(FileCopyDetails dirDetails) {
                try {
                    // Trailing slash on name indicates entry is a directory
                    TarEntry archiveEntry = new TarEntry(dirDetails.getRelativePath().getPathString() + '/');
                    archiveEntry.setModTime(dirDetails.getLastModified());
                    archiveEntry.setMode(UnixStat.DIR_FLAG | dirDetails.getMode());
                    tarOutStr.putNextEntry(archiveEntry);
                    tarOutStr.closeEntry();
                } catch (Exception e) {
                    throw new GradleException(String.format("Could not add %s to TAR '%s'.", dirDetails, tarFile), e);
                }
            }
        });

        try {
            tarOutStr.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        return new SimpleWorkResult(true);
    }

}
