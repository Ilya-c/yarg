/*
 * Copyright 2013 Haulmont
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.haulmont.yarg.reporting;

import com.google.common.base.Preconditions;
import com.haulmont.yarg.exception.ReportingException;
import com.haulmont.yarg.exception.ReportingInterruptedException;
import com.haulmont.yarg.exception.ValidationException;
import com.haulmont.yarg.formatters.ReportFormatter;
import com.haulmont.yarg.formatters.factory.FormatterFactoryInput;
import com.haulmont.yarg.formatters.factory.ReportFormatterFactory;
import com.haulmont.yarg.formatters.impl.ReportPostProcessor;
import com.haulmont.yarg.loaders.factory.ReportLoaderFactory;
import com.haulmont.yarg.structure.*;
import com.haulmont.yarg.util.converter.ObjectToStringConverter;
import com.haulmont.yarg.util.converter.ObjectToStringConverterImpl;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.lang.String.format;

public class Reporting implements ReportingAPI {
    protected ReportFormatterFactory formatterFactory;

    protected ReportLoaderFactory loaderFactory;

    protected DataExtractor dataExtractor;

    protected ObjectToStringConverter objectToStringConverter = new ObjectToStringConverterImpl();

    protected Logger logger = LoggerFactory.getLogger(getClass());

    public void setFormatterFactory(ReportFormatterFactory formatterFactory) {
        this.formatterFactory = formatterFactory;
    }

    public void setLoaderFactory(ReportLoaderFactory loaderFactory) {
        this.loaderFactory = loaderFactory;
        if (loaderFactory != null && dataExtractor == null) {
            dataExtractor = new DataExtractorImpl(loaderFactory);
        }
    }

    public void setDataExtractor(DataExtractorImpl dataExtractor) {
        this.dataExtractor = dataExtractor;
    }

    public void setObjectToStringConverter(ObjectToStringConverter objectToStringConverter) {
        this.objectToStringConverter = objectToStringConverter;
    }

    @Override
    public ReportOutputDocument runReport(RunParams runParams, OutputStream outputStream) {
        return runReport(runParams.report, runParams.reportTemplate, runParams.outputType, runParams.params, outputStream);
    }

    @Override
    public ReportOutputDocument runReport(RunParams runParams) {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        ReportOutputDocument reportOutputDocument = runReport(runParams.report, runParams.reportTemplate, runParams.outputType, runParams.params, result);
        reportOutputDocument.setContent(result.toByteArray());
        return reportOutputDocument;
    }

    protected ReportOutputDocument runReport(Report report, ReportTemplate reportTemplate, ReportOutputType outputType, Map<String, Object> params, OutputStream outputStream) {
        try {
            Preconditions.checkNotNull(report, "\"report\" parameter can not be null");
            Preconditions.checkNotNull(reportTemplate, "\"reportTemplate\" can not be null");
            Preconditions.checkNotNull(params, "\"params\" can not be null");
            Preconditions.checkNotNull(outputStream, "\"outputStream\" can not be null");

            Map<String, Object> handledParams = handleParameters(report, params);
            logReport("Started report [%s] with parameters [%s]", report, handledParams);

            ReportOutputType finalOutputType = (outputType != null) ? outputType : reportTemplate.getOutputType();
            BandData rootBand = loadBandData(report, handledParams);
            generateReport(report, reportTemplate, finalOutputType, outputStream, handledParams, rootBand);

            logReport("Finished report [%s] with parameters [%s]", report, handledParams);

            String outputName = resolveOutputFileName(report, reportTemplate, outputType, rootBand);
            return createReportOutputDocument(report, finalOutputType, outputName, rootBand);
        } catch (ReportingInterruptedException e) {
            logReport("Report is canceled by user request. Report [%s] with parameters [%s].", report, params);
            throw e;
        } catch (ReportingException e) {
            logReport("An error occurred while running report [%s] with parameters [%s].", report, params);
            logException(e);
            //validation exception is usually shown to clients, so probably there is no need to add report name there (to keep the original message)
            if (!(e instanceof ValidationException)) {
                e.setReportDetails(format(" Report name [%s]", report.getName()));
            }
            throw e;
        }
    }

    protected void generateReport(Report report, ReportTemplate reportTemplate, ReportOutputType outputType,
                                  OutputStream outputStream, Map<String, Object> handledParams, BandData rootBand) {
        String extension = StringUtils.substringAfterLast(reportTemplate.getDocumentName(), ".");
        if (reportTemplate.isCustom()) {
            try {
                byte[] bytes = reportTemplate.getCustomReport().createReport(report, rootBand, handledParams);
                IOUtils.write(bytes, outputStream);
            } catch (IOException e) {
                throw new ReportingException(format("An error occurred while processing custom template [%s].", reportTemplate.getDocumentName()), e);
            }
        } else if (isPostProcessorSet(reportTemplate)) {
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            renderDocumentToOutputStream(extension, rootBand, reportTemplate, outputType, result);
            byte[] bytes = result.toByteArray();
            String postProcessorName = reportTemplate.getPostProcessor();
            bytes = applyPostProcessor(bytes, postProcessorName, rootBand);

            try {
                outputStream.write(bytes);
            } catch (IOException e) {
                throw new ReportingException("Cannot save report to output stream", e);
            } finally {
                IOUtils.closeQuietly(outputStream);
            }

        } else {
            renderDocumentToOutputStream(extension, rootBand, reportTemplate, outputType, outputStream);
        }
    }

    private void renderDocumentToOutputStream(String templateExtension, BandData rootBand, ReportTemplate reportTemplate,
                                              ReportOutputType outputType, OutputStream outputStream) {
        FormatterFactoryInput factoryInput = new FormatterFactoryInput(templateExtension, rootBand, reportTemplate,
                                                                        outputType, outputStream);
        ReportFormatter formatter = formatterFactory.createFormatter(factoryInput);
        formatter.renderDocument();
    }

    protected byte[] applyPostProcessor(byte[] bytes, String postProcessorClassName, BandData rootBand) {
        try {
            Class<?> aClass = Class.forName(postProcessorClassName);

            if (ReportPostProcessor.class.isAssignableFrom(aClass)) {
                ReportPostProcessor postProcessor = (ReportPostProcessor) aClass.newInstance();
                bytes = postProcessor.postProcessReport(bytes, rootBand);
            } else {
                throw new ReportingException(
                        String.format("Class %s does not implement ReportPostProcessor interface.", postProcessorClassName));
            }
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
            throw new ReportingException(String.format("Class %s not found.\nPlease ensure that you entered a " +
                    "fully qualified class for report post processor name and that you class exists in class path", postProcessorClassName), e);
        } catch (IllegalAccessException | InstantiationException e) {
            throw new RuntimeException(String.format("An error occurred while instantiating class %s.", postProcessorClassName), e);
        }

        return bytes;
    }

    protected boolean isPostProcessorSet(ReportTemplate reportTemplate) {
        return StringUtils.isNotEmpty(reportTemplate.getPostProcessor());
    }

    protected BandData loadBandData(Report report, Map<String, Object> handledParams) {
        BandData rootBand = new BandData(BandData.ROOT_BAND_NAME);
        rootBand.setData(new HashMap<>(handledParams));
        rootBand.addReportFieldFormats(report.getReportFieldFormats());
        rootBand.setFirstLevelBandDefinitionNames(new HashSet<>());

        dataExtractor.extractData(report, handledParams, rootBand);
        return rootBand;
    }

    protected Map<String, Object> handleParameters(Report report, Map<String, Object> params) {
        Map<String, Object> handledParams = new HashMap<String, Object>(params);
        for (ReportParameter reportParameter : report.getReportParameters()) {
            String paramName = reportParameter.getAlias();

            Object parameterValue = handledParams.get(paramName);
            if (reportParameter instanceof ReportParameterWithDefaultValue) {
                String parameterDefaultValue = ((ReportParameterWithDefaultValue) reportParameter).getDefaultValue();
                if (parameterValue == null && parameterDefaultValue != null) {
                    parameterValue = objectToStringConverter.convertFromString(reportParameter.getParameterClass(), parameterDefaultValue);
                    handledParams.put(paramName, parameterValue);
                }
            }

            if (Boolean.TRUE.equals(reportParameter.getRequired()) && parameterValue == null) {
                throw new IllegalArgumentException(format("Required report parameter \"%s\" not found", paramName));
            }

            if (!handledParams.containsKey(paramName)) {//make sure map contains all user parameters, even if value == null
                handledParams.put(paramName, null);
            }
        }

        return handledParams;
    }

    protected void logReport(String caption, Report report, Map<String, Object> params) {
        StringBuilder parametersString = new StringBuilder();
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            parametersString.append("\n").append(entry.getKey()).append(":").append(entry.getValue());
        }
        logger.info(format(caption, report.getName(), parametersString));
    }

    protected void logException(ReportingException e) {
        logger.info("Trace: ", e);
    }

    protected ReportOutputDocument createReportOutputDocument(Report report, ReportOutputType outputType, String outputName, BandData rootBand) {
        return new ReportOutputDocumentImpl(report, null, outputName, outputType);
    }

    protected String resolveOutputFileName(Report report, ReportTemplate reportTemplate, ReportOutputType outputType, BandData rootBand) {
        String outputNamePattern = reportTemplate.getOutputNamePattern();
        String outputName = reportTemplate.getDocumentName();
        Pattern pattern = Pattern.compile("\\$\\{([A-z0-9_]+)\\.([A-z0-9_]+)\\}");
        if (StringUtils.isNotBlank(outputNamePattern)) {
            Matcher matcher = pattern.matcher(outputNamePattern);
            if (matcher.find()) {
                String bandName = matcher.group(1);
                String paramName = matcher.group(2);

                BandData bandWithFileName = null;
                if (BandData.ROOT_BAND_NAME.equals(bandName)) {
                    bandWithFileName = rootBand;
                } else {
                    bandWithFileName = rootBand.findBandRecursively(bandName);
                }

                if (bandWithFileName != null) {
                    Object fileName = bandWithFileName.getData().get(paramName);

                    if (fileName == null) {
                        throw new ReportingException(
                                format("No data in band [%s] parameter [%s] found. " +
                                        "This band and parameter is used for output file name generation.", bandWithFileName, paramName));
                    } else {
                        outputName = matcher.replaceFirst(fileName.toString());
                    }
                } else {
                    throw new ReportingException(format("No data in band [%s] found.This band is used for output file name generation.", bandName));
                }
            } else {
                outputName = outputNamePattern;
            }
        }

        if (ReportOutputType.custom != reportTemplate.getOutputType()) {
            ReportOutputType finalOutputType = (outputType != null ) ? outputType : reportTemplate.getOutputType();
            outputName = format("%s.%s", StringUtils.substringBeforeLast(outputName, "."), finalOutputType.getId());
        }

        return outputName;
    }
}