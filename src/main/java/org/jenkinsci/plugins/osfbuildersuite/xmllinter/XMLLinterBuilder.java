package org.jenkinsci.plugins.osfbuildersuite.xmllinter;

import hudson.AbortException;
import hudson.Launcher;
import hudson.Extension;
import hudson.FilePath;
import hudson.model.*;
import hudson.remoting.VirtualChannel;
import hudson.tasks.Builder;
import hudson.tasks.BuildStepDescriptor;
import jenkins.MasterToSlaveFileCallable;
import jenkins.tasks.SimpleBuildStep;
import org.apache.commons.lang3.StringUtils;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.*;
import org.xml.sax.SAXException;

import javax.annotation.Nonnull;
import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import java.io.*;


@SuppressWarnings("unused")
public class XMLLinterBuilder extends Builder implements SimpleBuildStep {

    private String xmlPath;
    private String xsdPath;

    @DataBoundConstructor
    public XMLLinterBuilder(String xmlPath, String xsdPath) {
        this.xmlPath = xmlPath;
        this.xsdPath = xsdPath;
    }

    @SuppressWarnings("unused")
    public String getXmlPath() {
        return xmlPath;
    }

    @SuppressWarnings("unused")
    @DataBoundSetter
    public void setXmlPath(String xmlPath) {
        this.xmlPath = xmlPath;
    }

    @SuppressWarnings("unused")
    public String getXsdPath() {
        return xsdPath;
    }

    @SuppressWarnings("unused")
    @DataBoundSetter
    public void setXsdPath(String xsdPath) {
        this.xsdPath = xsdPath;
    }

    @Override
    public void perform(
            @Nonnull Run<?, ?> build,
            @Nonnull FilePath workspace,
            @Nonnull Launcher launcher,
            @Nonnull TaskListener listener) throws InterruptedException, IOException {

        PrintStream logger = listener.getLogger();

        logger.println();
        logger.println(String.format("--[B: %s]--", getDescriptor().getDisplayName()));

        workspace.act(new XMLLinterCallable(workspace, listener, xmlPath, xsdPath));

        logger.println(String.format("--[E: %s]--", getDescriptor().getDisplayName()));
        logger.println();
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    @Extension
    @Symbol("osfBuilderSuiteXMLLint")
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {
        public DescriptorImpl() {
            load();
        }

        public String getDisplayName() {
            return "OSF Builder Suite :: XML Linter";
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }
    }

    private static class XMLLinterCallable extends MasterToSlaveFileCallable<Void> {

        private static final long serialVersionUID = 1L;

        private final FilePath workspace;
        private final TaskListener listener;
        private final String xmlPath;
        private final String xsdPath;

        @SuppressWarnings("WeakerAccess")
        public XMLLinterCallable(FilePath workspace, TaskListener listener, String xmlPath, String xsdPath) {
            this.workspace = workspace;
            this.listener = listener;
            this.xmlPath = xmlPath;
            this.xsdPath = xsdPath;
        }

        @Override
        public Void invoke(File dir, VirtualChannel channel) throws IOException, InterruptedException {
            PrintStream logger = listener.getLogger();

            if (StringUtils.isEmpty(xmlPath)) {
                throw new AbortException("Missing value for \"XML Path\"!");
            }

            if (StringUtils.isEmpty(xsdPath)) {
                throw new AbortException("Missing value for \"XSD Path\"!");
            }

            File xmlFile = new File(dir, xmlPath);
            if (!xmlFile.toPath().normalize().startsWith(dir.toPath())) {
                throw new AbortException("Invalid value for \"XML Path\"! The path needs to be inside the workspace!");
            }

            if (!xmlFile.exists()) {
                throw new AbortException(String.format("\"%s\" does not exist!", xmlPath));
            }

            File xsdFile = new File(dir, xsdPath);
            if (!xsdFile.toPath().normalize().startsWith(dir.toPath())) {
                throw new AbortException("Invalid value for \"XSD Path\"! The path needs to be inside the workspace!");
            }

            if (!xsdFile.exists()) {
                throw new AbortException(String.format("\"%s\" does not exist!", xsdPath));
            }

            logger.println(String.format("[+] Linting %s, %s", xmlPath, xsdPath));

            try {
                SchemaFactory schemaFactory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
                Schema schema = schemaFactory.newSchema(new StreamSource(xsdFile));
                Validator validator = schema.newValidator();
                validator.validate(new StreamSource(xmlFile));
            } catch (SAXException e) {
                throw new AbortException(e.getMessage());
            }

            logger.println(" + Ok");
            return null;
        }
    }
}

