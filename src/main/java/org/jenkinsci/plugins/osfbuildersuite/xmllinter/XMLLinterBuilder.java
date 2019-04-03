package org.jenkinsci.plugins.osfbuildersuite.xmllinter;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
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
import org.w3c.dom.Document;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import javax.annotation.Nonnull;
import javax.xml.XMLConstants;
import javax.xml.parsers.*;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.SchemaFactory;
import java.io.*;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;


@SuppressWarnings("unused")
public class XMLLinterBuilder extends Builder implements SimpleBuildStep {

    private String xsdsPath;
    private String xmlsPath;
    private String reportPath;

    @DataBoundConstructor
    public XMLLinterBuilder(String xsdsPath, String xmlsPath, String reportPath) {
        this.xsdsPath = xsdsPath;
        this.xmlsPath = xmlsPath;
        this.reportPath = reportPath;
    }

    @SuppressWarnings("unused")
    public String getXsdsPath() {
        return xsdsPath;
    }

    @SuppressWarnings("unused")
    @DataBoundSetter
    public void setXsdsPath(String xsdsPath) {
        this.xsdsPath = xsdsPath;
    }

    @SuppressWarnings("unused")
    public String getXmlsPath() {
        return xmlsPath;
    }

    @SuppressWarnings("unused")
    @DataBoundSetter
    public void setXmlsPath(String xmlsPath) {
        this.xmlsPath = xmlsPath;
    }

    @SuppressWarnings("unused")
    public String getReportPath() {
        return reportPath;
    }

    @SuppressWarnings("unused")
    @DataBoundSetter
    public void setReportPath(String reportPath) {
        this.reportPath = reportPath;
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

        workspace.act(new XMLLinterCallable(workspace, listener, xsdsPath, xmlsPath, reportPath));

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
        private final String xsdsPath;
        private final String xmlsPath;
        private final String reportPath;

        @SuppressWarnings("WeakerAccess")
        public XMLLinterCallable(
                FilePath workspace,
                TaskListener listener,
                String xsdsPath,
                String xmlsPath,
                String reportPath) {

            this.workspace = workspace;
            this.listener = listener;
            this.xsdsPath = xsdsPath;
            this.xmlsPath = xmlsPath;
            this.reportPath = reportPath;
        }

        @Override
        public Void invoke(File dir, VirtualChannel channel) throws IOException, InterruptedException {
            PrintStream logger = listener.getLogger();

            // XSDs Path
            if (StringUtils.isEmpty(xsdsPath)) {
                throw new AbortException("Missing value for \"XSDs Path\"!");
            }

            File xsdsDir = new File(dir, xsdsPath);
            if (!xsdsDir.toPath().normalize().startsWith(dir.toPath())) {
                throw new AbortException("Invalid value for \"XSDs Path\"! The path needs to be inside the workspace!");
            }

            if (!xsdsDir.exists()) {
                throw new AbortException(String.format("\"%s\" does not exist!", xsdsPath));
            }

            if (!xsdsDir.isDirectory()) {
                throw new AbortException(String.format("\"%s\" is not a directory!", xsdsPath));
            }

            // XMLs Path
            if (StringUtils.isEmpty(xmlsPath)) {
                throw new AbortException("Missing value for \"XMLs Path\"!");
            }

            File xmlsDir = new File(dir, xmlsPath);
            if (!xmlsDir.toPath().normalize().startsWith(dir.toPath())) {
                throw new AbortException("Invalid value for \"XMLs Path\"! The path needs to be inside the workspace!");
            }

            if (!xmlsDir.exists()) {
                throw new AbortException(String.format("\"%s\" does not exist!", xmlsPath));
            }

            if (!xmlsDir.isDirectory()) {
                throw new AbortException(String.format("\"%s\" is not a directory!", xmlsPath));
            }

            JsonArray errors = new JsonArray();

            logger.println(String.format("[+] Loading XSD files from %s", xsdsPath));

            Map<String, File> xsdsMap = new HashMap<>();
            Files.walk(xsdsDir.toPath())
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".xsd"))
                    .forEach((path) -> {
                        String relativePath = dir.toPath().relativize(path).toString();

                        try {
                            Document xsdDoc = DocumentBuilderFactory
                                    .newInstance()
                                    .newDocumentBuilder()
                                    .parse(path.toFile());

                            String targetNamespace = xsdDoc
                                    .getDocumentElement()
                                    .getAttribute("targetNamespace");

                            if (StringUtils.isNotEmpty(targetNamespace)) {
                                xsdsMap.put(targetNamespace, path.toFile());

                                logger.println(String.format(
                                        "    ~ Loaded %s",
                                        relativePath
                                ));
                            } else {
                                logger.println(String.format(
                                        "    ~ Skipping %s. Missing \"targetNamespace\" attribute",
                                        relativePath
                                ));
                            }
                        } catch (SAXParseException e) {
                            logger.println(String.format(
                                    "    ~ ERROR parsing %s@%s:%s",
                                    relativePath,
                                    e.getLineNumber(),
                                    e.getColumnNumber()
                            ));
                            logger.println(String.format(
                                    "      Message = %s",
                                    e.getMessage()
                            ));

                            JsonObject error = new JsonObject();
                            error.addProperty("path", relativePath);
                            error.addProperty("start_line", e.getLineNumber());
                            error.addProperty("end_line", e.getLineNumber());
                            error.addProperty("annotation_level", "failure");
                            error.addProperty("message", e.getMessage());

                            errors.add(error);
                        } catch (SAXException e) {
                            logger.println(String.format("    ~ ERROR parsing %s", relativePath));
                            logger.println(String.format("      Message = %s", e.getMessage()));

                            JsonObject error = new JsonObject();
                            error.addProperty("path", relativePath);
                            error.addProperty("start_line", 0);
                            error.addProperty("end_line", 0);
                            error.addProperty("annotation_level", "failure");
                            error.addProperty("message", e.getMessage());

                            errors.add(error);
                        } catch (IOException e) {
                            logger.println(String.format("    ~ ERROR loading %s", relativePath));
                        } catch (ParserConfigurationException e) {
                            logger.println(String.format(
                                    "    ~ ERROR creating a new instance of a DocumentBuilder for %s",
                                    relativePath
                            ));
                        }
                    });

            logger.println(" + Done");
            logger.println();

            logger.println(String.format("[+] Linting XML files from %s", xmlsPath));

            Files.walk(xmlsDir.toPath())
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".xml"))
                    .forEach((path) -> {
                        String relativePath = dir.toPath().relativize(path).toString();

                        try {
                            Document xmlDoc = DocumentBuilderFactory
                                    .newInstance()
                                    .newDocumentBuilder()
                                    .parse(path.toFile());

                            String xmlNs = xmlDoc
                                    .getDocumentElement()
                                    .getAttribute("xmlns");

                            if (StringUtils.isNotEmpty(xmlNs)) {
                                File xsdFile = xsdsMap.get(xmlNs);
                                if (xsdFile != null) {
                                    SchemaFactory
                                            .newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI)
                                            .newSchema(new StreamSource(xsdFile))
                                            .newValidator()
                                            .validate(new StreamSource(path.toFile()));

                                    logger.println(String.format(
                                            "    ~ Linted %s",
                                            relativePath
                                    ));
                                } else {
                                    logger.println(String.format(
                                            "    ~ Skipping %s. No matching \"XSD\" found",
                                            relativePath
                                    ));
                                }
                            } else {
                                logger.println(String.format(
                                        "    ~ Skipping %s. Missing \"xmlns\" attribute",
                                        relativePath
                                ));
                            }
                        } catch (SAXParseException e) {
                            logger.println(String.format(
                                    "    ~ ERROR parsing %s@%s:%s",
                                    relativePath,
                                    e.getLineNumber(),
                                    e.getColumnNumber()
                            ));
                            logger.println(String.format(
                                    "      Message = %s",
                                    e.getMessage()
                            ));

                            JsonObject error = new JsonObject();
                            error.addProperty("path", relativePath);
                            error.addProperty("start_line", e.getLineNumber());
                            error.addProperty("end_line", e.getLineNumber());
                            error.addProperty("annotation_level", "failure");
                            error.addProperty("message", e.getMessage());

                            errors.add(error);
                        } catch (SAXException e) {
                            logger.println(String.format("    ~ ERROR parsing %s", relativePath));
                            logger.println(String.format("      Message = %s", e.getMessage()));

                            JsonObject error = new JsonObject();
                            error.addProperty("path", relativePath);
                            error.addProperty("start_line", 0);
                            error.addProperty("end_line", 0);
                            error.addProperty("annotation_level", "failure");
                            error.addProperty("message", e.getMessage());

                            errors.add(error);
                        } catch (IOException e) {
                            logger.println(String.format("    ~ ERROR loading %s", relativePath));
                        } catch (ParserConfigurationException e) {
                            logger.println(String.format(
                                    "    ~ ERROR creating a new instance of a DocumentBuilder for %s",
                                    relativePath
                            ));
                        }
                    });

            logger.println(" + Done");
            logger.println();

            if (!StringUtils.isEmpty(reportPath)) {
                File reportDir = new File(dir, reportPath);
                if (!reportDir.toPath().normalize().startsWith(dir.toPath())) {
                    throw new AbortException(
                            "Invalid value for \"Report Path\"! The path needs to be inside the workspace!"
                    );
                }

                if (!reportDir.exists()) {
                    if (!reportDir.mkdirs()) {
                        throw new AbortException(String.format("Failed to create %s!", reportDir.getAbsolutePath()));
                    }
                }

                String randomUUID = UUID.randomUUID().toString();
                File reportFile = new File(reportDir, String.format("XMLLint.%s.json", randomUUID));
                if (reportFile.exists()) {
                    throw new AbortException(String.format(
                            "reportFile=%s already exists!",
                            reportFile.getAbsolutePath()
                    ));
                }

                Writer streamWriter = new OutputStreamWriter(new FileOutputStream(reportFile), "UTF-8");
                streamWriter.write(errors.toString());
                streamWriter.close();
            }

            if (errors.size() > 0) {
                throw new AbortException("XMLLint FAILED!");
            }

            return null;
        }
    }
}

