/* Copyright 2010-2017 Norconex Inc.
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
package com.norconex.jef4.suite;

import java.io.File;
import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.norconex.commons.lang.Sleeper;
import com.norconex.commons.lang.file.FileUtil;
import com.norconex.jef4.JEFException;
import com.norconex.jef4.event.IJobEventListener;
import com.norconex.jef4.event.JobEvent;
import com.norconex.jef4.job.IJob;
import com.norconex.jef4.status.IJobStatus;
import com.norconex.jef4.status.JobState;
import com.norconex.jef4.status.MutableJobStatus;

/**
 * Listens for STOP requests using a stop file.  The stop file
 * file name matches the suite namespace, plus the ".stop" extension.  
 * The directory where to locate the file depends on the constructor invoked.
 *
 * @author Pascal Essiembre
 */
public class StopRequestMonitor extends Thread {

    //TODO rename ShutdownHook?
    
    private static final Logger LOG = 
            LoggerFactory.getLogger(StopRequestMonitor.class);
    
    private static final int STOP_WAIT_DELAY = 3;
    
    private final File stopFile;
    private final JobSuite suite;
    private boolean monitoring = false;
    

    public StopRequestMonitor(JobSuite suite) {
        this.stopFile = suite.getSuiteStopFile();
        this.suite = suite;
    }


    @Override
    public void run() {
        monitoring = true;
        while(monitoring) {
            if (stopFile.exists()) {
                stopMonitoring();
                stopSuite();
            }
            Sleeper.sleepSeconds(1);
        }
    }
    
    public synchronized void stopMonitoring() {
        monitoring = false;
        if (stopFile.exists()) {
            try {
                FileUtil.delete(stopFile);
            } catch (IOException e) {
                throw new JEFException(
                        "Cannot delete stop file: " + stopFile, e);
            }
        }
    }

 
    private void stopSuite() {
        monitoring = false;
        LOG.info("STOP request received.");
        
        // Notify Suite Life Cycle listeners
        for (IJobEventListener l : suite.getEventListeners()) {
            l.onEvent(new JobEvent(JobEvent.SUITE_STOPPING, null, suite));
        }
        
        // Notify Job Life Cycle listeners and stop them
        suite.accept((final IJob job, final IJobStatus jobStatus) -> {
            for (IJobEventListener l : suite.getEventListeners()) {
                l.onEvent(new JobEvent(
                        JobEvent.JOB_STOPPING, jobStatus, suite));
            }
            new Thread(){
                @Override
                public void run() {
                    stopJob(job, jobStatus);
                }
            }.start();                
        });
    }
    private void stopJob(final IJob job, final IJobStatus status) {
        ((MutableJobStatus) status).setStopRequested(true);
        job.stop(status, suite);
        while (status.isRunning()) {
            Sleeper.sleepSeconds(STOP_WAIT_DELAY);
        }
        if (status.getState() == JobState.STOPPED) {
            for (IJobEventListener l : suite.getEventListeners()) {
                l.onEvent(new JobEvent(JobEvent.JOB_STOPPED, status, suite));
            }
            if (job.getId().equals(suite.getRootJob().getId())) {
                for (IJobEventListener l : suite.getEventListeners()) {
                    l.onEvent(new JobEvent(
                            JobEvent.SUITE_STOPPED, null, suite));
                }
            }
        }
    }

}
