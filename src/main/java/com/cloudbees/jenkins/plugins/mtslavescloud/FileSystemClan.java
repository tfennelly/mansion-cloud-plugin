package com.cloudbees.jenkins.plugins.mtslavescloud;

import com.cloudbees.api.oauth.OauthClientException;
import com.cloudbees.mtslaves.client.FileSystemRef;
import com.cloudbees.mtslaves.client.SnapshotRef;
import com.cloudbees.mtslaves.client.VirtualMachine;
import com.cloudbees.mtslaves.client.VirtualMachineSpec;
import com.cloudbees.mtslaves.client.properties.FileSystemsProperty;
import hudson.XmlFile;
import jenkins.model.Jenkins;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Logger;

import static java.util.logging.Level.WARNING;

/**
 * A set of {@link FileSystemLineage}s that are used together for a single {@link SlaveTemplate}.
 *
 * @author Kohsuke Kawaguchi
 */
public class FileSystemClan implements Iterable<FileSystemLineage> {
    private final MansionCloud cloud;
    /**
     * A clan is a record of how this master have used a template, so it's tied to one.
     */
    private final transient SlaveTemplate template;

    private final List<FileSystemLineage> lineages = new ArrayList<FileSystemLineage>();

    FileSystemClan(MansionCloud cloud, SlaveTemplate template) {
        this.cloud = cloud;
        this.template = template;
    }

    public Iterator<FileSystemLineage> iterator() {
        return lineages.iterator();
    }

    /**
     * The file in which we store the latest snapshots of workspace file systems.
     */
    private XmlFile getPersistentFileSystemRecordFile() {
        return new XmlFile(new File(Jenkins.getInstance().getRootDir(),"cloudSlaves/"+template.id+"/clan.properties"));
    }

    public void add(FileSystemLineage fsl) {
        for (FileSystemLineage l : lineages) {
            if (l.getPath().equals(fsl.getPath())) {
                lineages.remove(l);
                break;
            }
        }
        lineages.add(fsl);
    }

    public void load() throws IOException {
        XmlFile props = getPersistentFileSystemRecordFile();
        if (props.exists()) {
            props.unmarshal(this);
        }
    }

    public void save() throws IOException {
        getPersistentFileSystemRecordFile().write(this);
    }

    public void applyTo(VirtualMachineSpec spec) {
        for (FileSystemLineage e : this) {
            e.applyTo(spec);
        }
    }

    /**
     * Takes snapshots of the current file systems attached to the given VM and
     * use that as the latest generation of this clan.
     */
    public void update(VirtualMachine vm) {
        FileSystemsProperty fsp = vm.getProperty(FileSystemsProperty.class);
        if (fsp==null)  return;

        for (String persistedPath : template.persistentFileSystems) {
            URL fs = fsp.getFileSystemUrlFor(persistedPath);
            if (fs==null)   continue;   // shouldn't happen, but let's be defensive

            try {
                FileSystemRef fsr = new FileSystemRef(fs,cloud.createAccessToken(fs));
                SnapshotRef snapshot = fsr.snapshot();
                add(new FileSystemLineage(persistedPath,snapshot.url));
            } catch (IOException e) {
                LOGGER.log(WARNING, "Failed to take snapshot of "+fs,e);
            } catch (OauthClientException e) {
                LOGGER.log(WARNING, "Failed to take snapshot of "+fs,e);
            }
        }
        try {
            save();
        } catch (IOException e) {
            LOGGER.log(WARNING, "Failed to persiste the clan",e);
        }
    }

    private static final Logger LOGGER = Logger.getLogger(FileSystemClan.class.getName());
}
