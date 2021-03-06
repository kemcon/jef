/* Copyright 2010-2018 Norconex Inc.
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
 */
package com.norconex.jef5.status;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import org.apache.commons.io.FileExistsException;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.norconex.commons.lang.file.FileUtil;
import com.norconex.commons.lang.map.Properties;

/**
 * <p>
 * File-based status store. The created
 * file name matches the job id, plus the ".job" extension. If no
 * status directory is explicitly set, it defaults to:
 * <code>&lt;user.home&gt;/Norconex/jef/workdir</code>
 * </p>
 *
 * <h3>XML configuration usage:</h3>
 * <pre>
 *  &lt;statusStore class="com.norconex.jef4.status.FileJobStatusStore"&gt;
 *      &lt;statusDir&gt;(directory where to store status files)&lt;/statusDir&gt;
 *  &lt;/statusStore&gt;
 * </pre>
 * <h4>Usage example:</h4>
 * <p>
 * The following example indicates status files should be stored in this
 * directory:
 * <code>/tmp/jefstatuses</code>
 * </p>
 * <pre>
 *  &lt;statusStore class="com.norconex.jef4.status.FileJobStatusStore"&gt;
 *      &lt;statusDir&gt;/tmp/jefstatuses&lt;/statusDir&gt;
 *  &lt;/statusStore&gt;
 * </pre>
 *
 * @author Pascal Essiembre
 */
//TODO consider making an interface???  Events could be used if
// progress needs to be stored elsewhere as well.


// workdir and backup dir... should be figured out by job suite and
// passed to read/write methods... if we want to make this DAO reusable
// in JobSuiteStatus.


//TODO read/write index file from here??

public class JobSuiteStatusDAO
        implements Serializable {// IJefEventListener, IXMLConfigurable {

    private static final long serialVersionUID = 1L;

    //TODO if event listener specified, register the listener.
    private static final Logger LOG =
            LoggerFactory.getLogger(JobSuiteStatusDAO.class);

    //TODO make all static?


//    public static final String SESSION_SUBDIR = "session";
//    public static final String SESSION_BACKUP_SUBDIR = "backups/session";

    private final File statusDir;
//    private final Path workdir;
    private final String suiteId;

//    public JobSuiteStatusDAO(/*Path workdir,*/ String suiteId) {
    public JobSuiteStatusDAO(String suiteId, Path statusDir) {
        super();
//        this.workdir = workdir;
        Objects.requireNonNull(suiteId, "suiteId");
        Objects.requireNonNull(statusDir, "statusDir");

        this.suiteId = suiteId;
        this.statusDir = statusDir.toFile();
    }
//    public Path getWorkdir() {
//        return workdir;
//    }
    public String getSuiteId() {
        return suiteId;
    }
    public Path getStatusDir() {
        return statusDir.toPath();
    }
//    public Path getSessionDir() {
//        return getSessionDir(workdir, suiteId);
//    }
//    public static Path getSessionDir(Path suiteWorkdir, String suiteId) {
//        return suiteWorkdir.resolve(Paths.get(
//                FileUtil.toSafeFileName(suiteId), SESSION_SUBDIR));
//    }
//
//    public Path getSessionBackupDir(LocalDateTime date) {
//        return getSessionBackupDir(workdir, suiteId, date);
//    }
//    public static Path getSessionBackupDir(
//            Path suiteWorkdir, String suiteId, LocalDateTime date) {
//        return FileUtil.toDateFormattedDir(suiteWorkdir.resolve(Paths.get(
//                FileUtil.toSafeFileName(suiteId), SESSION_SUBDIR)).toFile(),
//                DateUtil.toDate(date), "yyyy/MM/dd/HH-mm-ss").toPath();
//    }

//    public Path getSessionIndex() {
//        return getSessionIndex(sessionDir);
//    }
//    /**
//     * Gets the path to job suite index.
//     * @param sessionDir suite working directory
//     * @return file the index file
//     */
//    public static Path getSessionIndex(Path sessionDir) {
//        return sessionDir.resolve("suite.index"); // make it "suite.jef"?
//    }




    public final void write(final JobStatus js) throws IOException {

        //The JobStatusData should not be written/read here??? so rename arg to JobStatus?

        Properties config = new Properties();
        config.set("jobId", js.getJobId());
        config.set("progress", js.getProgress());
        config.set("note", js.getNote());
        config.set("startTime", js.getStartTime());
        config.set("endTime", js.getEndTime());

        //TODO store different status for stopping and stopped?
        if (js.isStopping() || js.isStopped()) {
            config.set("stopRequested", true);
        }
        Properties props = js.getProperties();
        for (Entry<String, List<String>> entry : props.entrySet()) {
            config.put("." + entry.getKey(), entry.getValue());
        }
        Path file = resolveJobFile(js.getJobId());
        LOG.trace("Writing status file: {}", file);


        StringWriter sw = new StringWriter();
        config.storeToProperties(sw);

        Files.createDirectories(file.getParent());
        try (BufferedWriter w = Files.newBufferedWriter(file)) {
            w.write(sw.toString());
        }
    }

    public final JobStatus read(final String jobId) throws IOException {

        if (jobId == null) {
            return null;
        }

        Set<JobStatusData> attempts = new TreeSet<>();
        Path file = null;
        int attemptNo = 1;
        while ((file = resolveJobFile(jobId, attemptNo++)).toFile().exists()) {
            JobStatusData data = new JobStatusData();
            read(data, file);
            attempts.add(data);
        }
        JobStatus jobStatus = new JobStatus(jobId, attempts);
        read(jobStatus, resolveJobFile(jobId));
        return jobStatus;
    }

    private final void read(final JobStatusData jsd, final Path file)
            throws IOException {

        if (LOG.isDebugEnabled()) {
            LOG.debug("Reading status file: " + file);
        }

        if (file.getFileName().toString().startsWith("null")) {
            System.out.println("XXXX: " + jsd);
        }

        if (!file.toFile().exists() || Files.size(file) == 0) {
            return;
        }

        Properties config = new Properties();

        try (BufferedReader r = Files.newBufferedReader(file)) {
            config.loadFromProperties(r);
        }

        Instant lastModified = Files.getLastModifiedTime(file).toInstant();
//        LocalDateTime lastModified = LocalDateTime.from(
//                Files.getLastModifiedTime(file).toInstant().atZone(ZoneId.of("UTC")));

        LOG.trace("{} last activity: {}", file.toAbsolutePath(), lastModified);

        jsd.setLastActivity(lastModified);
        jsd.setProgress(config.getDouble("progress", 0d));
        jsd.setNote(config.getString("note", null));
        jsd.setStartTime(config.getInstant("startTime"));
        jsd.setEndTime(config.getInstant("endTime"));
        jsd.setStopRequested(config.getBoolean("stopRequested", false));

        Properties props = jsd.getProperties();
        for (String key : config.keySet()) {
            if (key.startsWith(".")) {
                props.put(StringUtils.removeStart(".", key), props.get(key));
            }
        }
    }

    public final void delete() throws IOException {
        FileUtils.deleteDirectory(statusDir);
    }

    //TODO have built-in methods to load backed-up sessions?
    public final void backup(Path backupDir) throws IOException {
        Objects.requireNonNull(backupDir, "backupDir");
        LOG.debug("Moving {} to {}", statusDir, backupDir);
        try {
            FileUtils.moveDirectory(statusDir, backupDir.toFile());
        } catch (FileExistsException e) {
            LOG.error("Target backup directory already exists: {}", backupDir);
            throw e;
        }
    }

    public Instant touch(String jobId) throws IOException {
        Path file = resolveJobFile(jobId);

        if (!file.toFile().exists()) {
            Files.createDirectories(file.getParent());
            Files.createFile(file);
        }
        Instant now = Instant.now();
        Files.setLastModifiedTime(file, FileTime.from(now));
        return now;
    }


    private Path resolveJobFile(final String jobId) {
        return resolveJobFile(jobId, 0);
    }
    private Path resolveJobFile(final String jobId, final int attemptNo) {
        //TODO cache path for job ID to avoid recreate each time?
        String suffix = StringUtils.EMPTY;
        if (attemptNo > 0) {
            suffix = "." + Integer.toString(attemptNo);
        }
        return statusDir.toPath().resolve(
                FileUtil.toSafeFileName(jobId) + ".job" + suffix);
    }
//    private Path resolveDataDir() {
//        return storeDir.resolve(Paths.get(FileUtil.toSafeFileName(suiteId)));
//    }
//    private Path resolveBackupDir(
//            final String suiteName, final LocalDateTime backupDate)
//                    throws IOException {
//        Path dir = storeBackupDir;
//        if (dir == null) {
//            dir = storeDir.resolveSibling("backups");
//        }
//        return FileUtil.toDateFormattedDir(
//                dir.resolve(FileUtil.toSafeFileName(suiteName)).toFile(),
//                DateUtil.toDate(backupDate), "yyyy/MM/dd/HH-mm-ss").toPath();
//    }

//    @Override
//    public void loadFromXML(Reader in) throws IOException {
//        XMLConfiguration xml = XMLConfigurationUtil.newXMLConfiguration(in);
//        String dir = null;
//
//        dir = xml.getString("storeDir", null);
//        if (dir != null) {
//            setStoreDir(Paths.get(dir));
//        }
//
//        dir = xml.getString("storeBackupDir", null);
//        if (dir != null) {
//            setStoreBackupDir(Paths.get(dir));
//        }
//    }
//
//    @Override
//    public void saveToXML(Writer out) throws IOException {
//        try {
//            EnhancedXMLStreamWriter w = new EnhancedXMLStreamWriter(out);
//            w.writeStartElement("store");
//            w.writeAttribute("class", getClass().getCanonicalName());
//
//            if (storeDir != null) {
//                w.writeElementString("storeDir",
//                        storeDir.toAbsolutePath().toString());
//            }
//            if (storeBackupDir != null) {
//                w.writeElementString("storeBackupDir",
//                        storeBackupDir.toAbsolutePath().toString());
//            }
//            w.writeEndElement();
//            w.flush();
//            w.close();
//        } catch (XMLStreamException e) {
//            throw new IOException("Cannot save as XML.", e);
//        }
//    }

    @Override
    public boolean equals(final Object other) {
        if (!(other instanceof JobSuiteStatusDAO)) {
            return false;
        }
        JobSuiteStatusDAO castOther = (JobSuiteStatusDAO) other;
        return new EqualsBuilder()
                .append(statusDir, castOther.statusDir)
                .append(suiteId, castOther.suiteId)
                .isEquals();
    }

    @Override
    public int hashCode() {
        return new HashCodeBuilder()
                .append(statusDir)
                .append(suiteId)
                .toHashCode();
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this, ToStringStyle.SHORT_PREFIX_STYLE)
                .append("statusDir", statusDir)
                .append("suiteId", suiteId)
                .toString();
    }

//    @Override
//    public void accept(JefEvent event) {
//        if (!event.equalsName(JefEvent.SUITE_STARTED)) {
//            return;
//        }
//
//        Path dir = storeDir;
//        if (storeDir == null) {
//            dir = DEFAULT_STORE_PATH;
//            LOG.error("JEF session store path cannot be null. "
//                    + "Will use default: {}", dir);
//        }
//        try {
//            Files.createDirectories(dir);
//            LOG.info("Job session store directory: {}", dir.toAbsolutePath());
//        } catch (IOException e) {
//            throw new JefException("Cannot create session store directory: "
//                    + dir.toAbsolutePath(), e);
//        }
//
//        if (storeBackupDir != null) {
//            try {
//                Files.createDirectories(storeBackupDir);
//                LOG.info("Job session store backup directory: {}",
//                        storeBackupDir.toAbsolutePath());
//            } catch (IOException e) {
//                throw new JefException(
//                        "Cannot create session store backup directory: "
//                        + storeBackupDir.toAbsolutePath(), e);
//            }
//        } else {
//            LOG.info("No job session store backup directory specified.");
//        }
//    }


}
