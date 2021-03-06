/*
 * This file is part of dependency-check-core.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Copyright (c) 2013 Jeremy Long. All Rights Reserved.
 */
package org.owasp.dependencycheck.data.update.nvd;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.zip.GZIPInputStream;
import org.apache.commons.io.FileUtils;
import org.owasp.dependencycheck.data.nvdcve.CveDB;
import org.owasp.dependencycheck.data.update.exception.UpdateException;
import org.owasp.dependencycheck.utils.DownloadFailedException;
import org.owasp.dependencycheck.utils.Downloader;
import org.owasp.dependencycheck.utils.Settings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A callable object to download two files.
 *
 * @author Jeremy Long
 */
public class DownloadTask implements Callable<Future<ProcessTask>> {

    /**
     * The Logger.
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(DownloadTask.class);

    /**
     * Simple constructor for the callable download task.
     *
     * @param nvdCveInfo the NVD CVE info
     * @param processor the processor service to submit the downloaded files to
     * @param cveDB the CVE DB to use to store the vulnerability data
     * @param settings a reference to the global settings object; this is
     * necessary so that when the thread is started the dependencies have a
     * correct reference to the global settings.
     * @throws UpdateException thrown if temporary files could not be created
     */
    public DownloadTask(NvdCveInfo nvdCveInfo, ExecutorService processor, CveDB cveDB, Settings settings) throws UpdateException {
        this.nvdCveInfo = nvdCveInfo;
        this.processorService = processor;
        this.cveDB = cveDB;
        this.settings = settings;

        final File file1;
        final File file2;

        try {
            file1 = File.createTempFile("cve" + nvdCveInfo.getId() + '_', ".xml", Settings.getTempDirectory());
            file2 = File.createTempFile("cve_1_2_" + nvdCveInfo.getId() + '_', ".xml", Settings.getTempDirectory());
        } catch (IOException ex) {
            throw new UpdateException("Unable to create temporary files", ex);
        }
        this.first = file1;
        this.second = file2;

    }
    /**
     * The CVE DB to use when processing the files.
     */
    private final CveDB cveDB;
    /**
     * The processor service to pass the results of the download to.
     */
    private final ExecutorService processorService;
    /**
     * The NVD CVE Meta Data.
     */
    private NvdCveInfo nvdCveInfo;
    /**
     * A reference to the global settings object.
     */
    private final Settings settings;

    /**
     * Get the value of nvdCveInfo.
     *
     * @return the value of nvdCveInfo
     */
    public NvdCveInfo getNvdCveInfo() {
        return nvdCveInfo;
    }

    /**
     * Set the value of nvdCveInfo.
     *
     * @param nvdCveInfo new value of nvdCveInfo
     */
    public void setNvdCveInfo(NvdCveInfo nvdCveInfo) {
        this.nvdCveInfo = nvdCveInfo;
    }
    /**
     * a file.
     */
    private File first;

    /**
     * Get the value of first.
     *
     * @return the value of first
     */
    public File getFirst() {
        return first;
    }

    /**
     * Set the value of first.
     *
     * @param first new value of first
     */
    public void setFirst(File first) {
        this.first = first;
    }
    /**
     * a file.
     */
    private File second;

    /**
     * Get the value of second.
     *
     * @return the value of second
     */
    public File getSecond() {
        return second;
    }

    /**
     * Set the value of second.
     *
     * @param second new value of second
     */
    public void setSecond(File second) {
        this.second = second;
    }

    @Override
    public Future<ProcessTask> call() throws Exception {
        try {
            Settings.setInstance(settings);
            final URL url1 = new URL(nvdCveInfo.getUrl());
            final URL url2 = new URL(nvdCveInfo.getOldSchemaVersionUrl());
            LOGGER.info("Download Started for NVD CVE - {}", nvdCveInfo.getId());
            final long startDownload = System.currentTimeMillis();
            try {
                Downloader.fetchFile(url1, first);
                Downloader.fetchFile(url2, second);
            } catch (DownloadFailedException ex) {
                LOGGER.warn("Download Failed for NVD CVE - {}\nSome CVEs may not be reported.", nvdCveInfo.getId());
                if (Settings.getString(Settings.KEYS.PROXY_SERVER) == null) {
                    LOGGER.info(
                            "If you are behind a proxy you may need to configure dependency-check to use the proxy.");
                }
                LOGGER.debug("", ex);
                return null;
            }
            if (url1.toExternalForm().endsWith(".xml.gz") && !isXml(first)) {
                extractGzip(first);
            }
            if (url2.toExternalForm().endsWith(".xml.gz") && !isXml(second)) {
                extractGzip(second);
            }

            LOGGER.info("Download Complete for NVD CVE - {}  ({} ms)", nvdCveInfo.getId(),
                    System.currentTimeMillis() - startDownload);
            if (this.processorService == null) {
                return null;
            }
            final ProcessTask task = new ProcessTask(cveDB, this, settings);
            return this.processorService.submit(task);

        } catch (Throwable ex) {
            LOGGER.warn("An exception occurred downloading NVD CVE - {}\nSome CVEs may not be reported.", nvdCveInfo.getId());
            LOGGER.debug("Download Task Failed", ex);
        } finally {
            Settings.cleanup(false);
        }
        return null;
    }

    /**
     * Attempts to delete the files that were downloaded.
     */
    public void cleanup() {
        if (first != null && first.exists() && first.delete()) {
            LOGGER.debug("Failed to delete first temporary file {}", second.toString());
            first.deleteOnExit();
        }
        if (second != null && second.exists() && !second.delete()) {
            LOGGER.debug("Failed to delete second temporary file {}", second.toString());
            second.deleteOnExit();
        }
    }

    /**
     * Checks the file header to see if it is an XML file.
     *
     * @param file the file to check
     * @return true if the file is XML
     */
    public static boolean isXml(File file) {
        if (file == null || !file.isFile()) {
            return false;
        }
        InputStream is = null;
        try {
            is = new FileInputStream(file);

            final byte[] buf = new byte[5];
            int read = 0;
            try {
                read = is.read(buf);
            } catch (IOException ex) {
                return false;
            }
            return read == 5
                    && buf[0] == '<'
                    && (buf[1] == '?')
                    && (buf[2] == 'x' || buf[2] == 'X')
                    && (buf[3] == 'm' || buf[3] == 'M')
                    && (buf[4] == 'l' || buf[4] == 'L');
        } catch (FileNotFoundException ex) {
            return false;
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (IOException ex) {
                    LOGGER.debug("Error closing stream", ex);
                }
            }
        }
    }

    /**
     * Extracts the file contained in a gzip archive. The extracted file is
     * placed in the exact same path as the file specified.
     *
     * @param file the archive file
     * @throws FileNotFoundException thrown if the file does not exist
     * @throws IOException thrown if there is an error extracting the file.
     */
    private void extractGzip(File file) throws FileNotFoundException, IOException {
        final String originalPath = file.getPath();
        final File gzip = new File(originalPath + ".gz");
        if (gzip.isFile() && !gzip.delete()) {
            LOGGER.debug("Failed to delete initial temporary file when extracting 'gz' {}", gzip.toString());
            gzip.deleteOnExit();
        }
        if (!file.renameTo(gzip)) {
            throw new IOException("Unable to rename '" + file.getPath() + "'");
        }
        final File newfile = new File(originalPath);

        final byte[] buffer = new byte[4096];

        GZIPInputStream cin = null;
        FileOutputStream out = null;
        try {
            cin = new GZIPInputStream(new FileInputStream(gzip));
            out = new FileOutputStream(newfile);

            int len;
            while ((len = cin.read(buffer)) > 0) {
                out.write(buffer, 0, len);
            }
        } finally {
            if (cin != null) {
                try {
                    cin.close();
                } catch (IOException ex) {
                    LOGGER.trace("ignore", ex);
                }
            }
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ex) {
                    LOGGER.trace("ignore", ex);
                }
            }
            if (gzip.isFile() && !FileUtils.deleteQuietly(gzip)) {
                LOGGER.debug("Failed to delete temporary file when extracting 'gz' {}", gzip.toString());
                gzip.deleteOnExit();
            }
        }
    }
}
